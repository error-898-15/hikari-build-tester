package com.hikari.app.dub

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp

/**
 * Switchboard between the dub engine and the audio stage of the player.
 *
 * The dub engine only writes [target] ("how much of the original dialogue
 * should be removed right now", 0 = none, 1 = all of it). The audio stage
 * ([VoiceCutProcessor], living inside the ExoPlayer audio sink) reads it and
 * reports back through [works] whether it is able to do the job for the
 * video that is playing.
 */
object DubVoiceCut {
    /** 0f..1f, written by the dub engine (main thread), read by the audio thread. */
    @Volatile
    var target: Float = 0f

    /**
     * True when the sound that reaches the player's audio stage is stereo or
     * surround AND has real stereo content, so the dialogue can be taken out
     * while the music and the sound effects stay. False for mono sound (and
     * for stereo that is really mono): there the dub engine falls back to
     * lowering the whole original sound, as before.
     */
    @Volatile
    var works: Boolean = false
}

/**
 * Removes the original DIALOGUE from the movie's sound while the music and the
 * sound effects keep playing.
 *
 * How (no AI, no internet, runs inside the audio sink on every device):
 *
 *  - Stereo: dialogue is almost always mixed into the CENTER (same level in
 *    the left and the right channel), while music and effects are spread
 *    wide. So the "center" part (L+R)/2 of the voice band (everything above
 *    [LOW_CUT_HZ]) is subtracted from both channels. The bass below
 *    [LOW_CUT_HZ] is left alone, so the kick drum and the rumble of
 *    explosions do not disappear.
 *  - 5.1 / 7.1: dialogue lives in the CENTER channel (index 2), so that
 *    channel is turned down. Left, right, surround and LFE are untouched.
 *  - Mono: nothing can be separated, the stage stays out of the way (and
 *    [DubVoiceCut.works] is false so the engine lowers the volume instead).
 *
 * The amount is smoothed (about 30 ms) so switching never clicks. With the
 * amount at 0 the sound passes through bit-exact.
 */
@UnstableApi
class VoiceCutProcessor : AudioProcessor {

    private var inputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var scratch: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var pending: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private var supported = false
    private var channels = 0
    private var isFloat = false
    private var sampleRate = 48000

    // smoothing and low-pass coefficients (set in configure)
    private var smooth = 0.001f
    private var lpAlpha = 0.03f

    // the live (smoothed) amount
    private var cur = 0f

    // two cascaded one-pole low-passes per channel (the part that is kept as it is)
    private var a1L = 0f
    private var a2L = 0f
    private var a1R = 0f
    private var a2R = 0f

    // how "wide" the stereo picture is (long-term), to spot mono hidden in stereo
    private var wMid = 0.0
    private var wSide = 0.0
    private var activeFrames = 0.0
    private var monoLike = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        val ok = (enc == C.ENCODING_PCM_16BIT || enc == C.ENCODING_PCM_FLOAT) &&
            (inputAudioFormat.channelCount == 2 || inputAudioFormat.channelCount >= 6) &&
            inputAudioFormat.sampleRate > 0
        inputFormat = inputAudioFormat
        supported = ok
        channels = inputAudioFormat.channelCount
        isFloat = enc == C.ENCODING_PCM_FLOAT
        sampleRate = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate else 48000
        smooth = (1.0 - exp(-1.0 / (0.030 * sampleRate))).toFloat()
        lpAlpha = (1.0 - exp(-2.0 * PI * LOW_CUT_HZ / sampleRate)).toFloat()
        resetState()
        wMid = 0.0
        wSide = 0.0
        activeFrames = 0.0
        monoLike = false
        DubVoiceCut.works = ok
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        supported && inputFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return
        if (scratch.capacity() < size) {
            scratch = ByteBuffer.allocateDirect(size + 8192).order(ByteOrder.nativeOrder())
        } else {
            scratch.clear()
        }
        val dst = scratch
        if (!supported) {
            dst.put(inputBuffer)
        } else if (channels == 2) {
            processStereo(inputBuffer, dst, size)
        } else {
            processSurround(inputBuffer, dst, size)
        }
        // a few stray bytes that do not make a whole frame are passed on unchanged
        while (inputBuffer.hasRemaining()) dst.put(inputBuffer.get())
        dst.flip()
        pending = dst
    }

    private fun readSample(b: ByteBuffer): Float =
        if (isFloat) b.getFloat() else b.getShort() * (1f / 32768f)

    private fun writeSample(b: ByteBuffer, v: Float) {
        if (isFloat) {
            b.putFloat(v)
        } else {
            val s = (v * 32768f).toInt().coerceIn(-32768, 32767)
            b.putShort(s.toShort())
        }
    }

    private fun processStereo(src: ByteBuffer, dst: ByteBuffer, size: Int) {
        val bytesPerFrame = (if (isFloat) 4 else 2) * 2
        val frames = size / bytesPerFrame
        val tgt = DubVoiceCut.target.coerceIn(0f, 1f)
        var k = cur
        val sm = smooth
        val al = lpAlpha
        var lo1L = a1L
        var lo2L = a2L
        var lo1R = a1R
        var lo2R = a2R
        var sumMid = 0.0
        var sumSide = 0.0
        for (i in 0 until frames) {
            val l = readSample(src)
            val r = readSample(src)
            lo1L += al * (l - lo1L)
            lo2L += al * (lo1L - lo2L)
            lo1R += al * (r - lo1R)
            lo2R += al * (lo1R - lo2R)
            val hL = l - lo2L
            val hR = r - lo2R
            val mid = (hL + hR) * 0.5f
            val side = (hL - hR) * 0.5f
            sumMid += (mid * mid).toDouble()
            sumSide += (side * side).toDouble()
            k += (tgt - k) * sm
            writeSample(dst, l - k * mid)
            writeSample(dst, r - k * mid)
        }
        cur = if (k < 0.0005f && tgt == 0f) 0f else k
        a1L = lo1L
        a2L = lo2L
        a1R = lo1R
        a2R = lo2R
        measureWidth(frames, sumMid, sumSide)
    }

    private fun processSurround(src: ByteBuffer, dst: ByteBuffer, size: Int) {
        val bytesPerFrame = (if (isFloat) 4 else 2) * channels
        val frames = size / bytesPerFrame
        val tgt = DubVoiceCut.target.coerceIn(0f, 1f)
        var k = cur
        val sm = smooth
        for (i in 0 until frames) {
            k += (tgt - k) * sm
            val keep = 1f - k
            for (c in 0 until channels) {
                val v = readSample(src)
                writeSample(dst, if (c == 2) v * keep else v)
            }
        }
        cur = if (k < 0.0005f && tgt == 0f) 0f else k
    }

    /**
     * Long-term side/mid energy of the voice band. A picture that stays
     * almost flat for several seconds of real sound is mono in disguise
     * (both channels carry the same thing): taking the "center" out of it
     * would take out everything, so the engine is told it cannot work here.
     */
    private fun measureWidth(frames: Int, sumMid: Double, sumSide: Double) {
        if (frames <= 0) return
        val dec = exp(-(frames.toDouble() / sampleRate) / WIDTH_TAU_S)
        wMid = wMid * dec + sumMid
        wSide = wSide * dec + sumSide
        if (sumMid / frames > 1e-6) activeFrames += frames.toDouble()
        if (activeFrames > MIN_ACTIVE_S * sampleRate && wMid > 1e-9) {
            val ratio = wSide / wMid
            if (ratio < MONO_ENTER) monoLike = true else if (ratio > MONO_EXIT) monoLike = false
            DubVoiceCut.works = !monoLike
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = pending
        pending = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean =
        inputEnded && pending === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        pending = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        resetState()
    }

    override fun reset() {
        flush()
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        scratch = AudioProcessor.EMPTY_BUFFER
        supported = false
        DubVoiceCut.works = false
    }

    private fun resetState() {
        cur = 0f
        a1L = 0f
        a2L = 0f
        a1R = 0f
        a2R = 0f
    }

    private companion object {
        /** Everything below this stays untouched (bass, kick drum, explosions). */
        const val LOW_CUT_HZ = 250.0
        const val WIDTH_TAU_S = 20.0
        const val MIN_ACTIVE_S = 6.0
        const val MONO_ENTER = 0.01
        const val MONO_EXIT = 0.03
    }
}
