package com.hikari.app.dub

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import com.hikari.app.data.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

enum class VoiceMode { AUTO, GOOGLE, LOCAL }

/**
 * Dubs a video from its subtitle file:
 *
 *   subtitle cue ──translate──▶ text in the chosen language ──TTS──▶ audio clip
 *                                                                      │
 *   player position ──────────── plays the clip when its cue starts ◀──┘
 *
 * Nothing is processed up front. Only the cues in the next [LOOKAHEAD_MS] of
 * playback are translated and voiced (a few at a time), so starting is instant,
 * a 2-hour film does not hammer Google, and a seek just moves the window.
 *
 * The original soundtrack is lowered ("ducked") while a clip speaks and
 * restored afterwards. A clip longer than its cue is sped up (max
 * [MAX_SPEED]x) so the voice does not run into the next line.
 *
 * All public methods must be called from the main thread.
 */
class DubEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Current video position in ms. */
    private val positionMs: () -> Long,
    /** True while the video is actually playing (not paused / buffering). */
    private val isPlaying: () -> Boolean,
    /** Subtitle sync offset in ms (the player's "Sync" setting). */
    private val offsetMs: () -> Long,
    /** Sets the ORIGINAL audio's volume, 0f..1f. */
    private val setOriginalVolume: (Float) -> Unit,
    /** Short messages for the user (shown as a toast by the caller). */
    private val onStatus: (String) -> Unit,
) {
    private val google = GoogleTtsVoice(context)
    private val local = LocalTtsVoice(context)

    private var cues: List<DubCue> = emptyList()
    private var lang = "en"
    private var mode = VoiceMode.AUTO
    private var duck = 0.15f

    /** True = every character gets its own voice and the clips are edited
     *  (pitch, speed, loudness). False = the plain voice, as before. */
    @Volatile private var smart = true
    @Volatile private var cast: Cast = Cast(emptyList())
    private val dir = File(context.cacheDir, "dub").apply { mkdirs() }

    private var loop: Job? = null
    private val gate = Semaphore(3)
    private val ready = ConcurrentHashMap<Int, File>()
    private val inFlight = ConcurrentHashMap.newKeySet<Int>()
    private val failed = ConcurrentHashMap.newKeySet<Int>()
    private var failureNoticeShown = false

    private var clip: MediaPlayer? = null
    private var clipToken = 0
    private var spokenIdx = -1
    private var lastPos = 0L

    val active: Boolean get() = loop?.isActive == true
    var activeLang: String = ""
        private set

    /** True when this run reads captions live (see [feedLive]) instead of a cue list. */
    private var liveMode = false
    private var liveText: String? = null
    private var liveToken = 0

    /**
     * [multi] = a different voice for every character.
     * [live] = there is no subtitle text to read in advance (the subtitle is
     * inside the video): lines are voiced when [feedLive] hands them over.
     */
    fun start(
        cues: List<DubCue>,
        lang: String,
        mode: VoiceMode,
        duckVolume: Float,
        multi: Boolean = true,
        live: Boolean = false,
    ) {
        stop()
        if (cues.isEmpty() && !live) {
            onStatus("No readable lines in that subtitle.")
            return
        }
        this.cues = cues
        this.lang = lang
        this.mode = mode
        this.duck = duckVolume.coerceIn(0f, 1f)
        this.smart = multi
        this.liveMode = live
        this.cast = if (smart && !live) CastDirector.assign(cues) else Cast(emptyList())
        activeLang = lang
        failureNoticeShown = false
        spokenIdx = -1
        lastPos = effectivePos()
        loop = scope.launch(Dispatchers.Main) {
            var sincePrefetch = PREFETCH_EVERY_MS   // prefetch on the very first tick
            while (isActive) {
                tick()
                sincePrefetch += TICK_MS
                if (sincePrefetch >= PREFETCH_EVERY_MS) {
                    sincePrefetch = 0
                    prefetch()
                }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        liveToken++
        liveText = null
        stopClip()
        ready.clear()
        inFlight.clear()
        failed.clear()
        activeLang = ""
    }

    /** Stops the engine AND frees the text-to-speech engine. */
    fun release() {
        stop()
        local.release()
    }

    /**
     * LIVE mode: the caption that is on screen right now (null / empty = none).
     * A new line is cleaned, translated, voiced and played at once; a line that
     * is already being read is not repeated. Main thread only.
     */
    fun feedLive(text: String?) {
        if (!active || !liveMode) return
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) {
            liveText = null          // caption gone; a clip that is speaking may finish
            return
        }
        if (t == liveText) return
        liveText = t

        // Reuse the subtitle cleaner: wrap the caption as a one-line subtitle.
        val parsed = SubtitleCues.parse(
            "1\n00:00:00,000 --> 00:00:10,000\n" + t.replace("\n\n", "\n"),
            smart,
        )
        if (parsed.isEmpty()) return
        val first = parsed.first()
        val body = parsed.joinToString(" ") { it.text }
        val windowMs = (body.length * 65L).coerceIn(1_200L, 6_000L)
        val cue = DubCue(0L, windowMs, body, first.speaker, first.turn, first.emotion)

        val token = ++liveToken
        scope.launch(Dispatchers.IO) {
            val file = gate.withPermit {
                if (token != liveToken || !active) null else prepareCue(cue, windowMs)
            }
            withContext(Dispatchers.Main) {
                if (token != liveToken || !active) return@withContext
                if (file != null) playClip(file, windowMs)
                else noticeFailureOnce()
            }
        }
    }

    // ───────────────────────── playback ─────────────────────────

    private fun effectivePos() = positionMs() - offsetMs()

    private fun tick() {
        val pos = effectivePos()

        // Seek detection: ticks are 100 ms apart, so a bigger jump is a seek.
        if (pos < lastPos - 300L || pos > lastPos + 2_500L) {
            stopClip()
            spokenIdx = -1
        }
        lastPos = pos

        // Follow pause / resume of the video.
        clip?.let { mp ->
            runCatching {
                if (!isPlaying() && mp.isPlaying) mp.pause()
                else if (isPlaying() && !mp.isPlaying && clipStarted) mp.start()
            }
        }
        if (!isPlaying()) return

        val idx = cueIndexAt(pos)
        if (idx < 0 || idx == spokenIdx) return
        val cue = cues[idx]
        if (pos >= cue.endMs - 300L) return          // too late to start this one
        val file = ready[idx] ?: return              // not voiced yet; try again next tick
        spokenIdx = idx
        val nextStart = cues.getOrNull(idx + 1)?.startMs ?: Long.MAX_VALUE
        val windowMs = (minOf(nextStart, cue.endMs + 700L) - pos).coerceAtLeast(600L)
        playClip(file, windowMs)
    }

    /** Index of the last cue that starts at or before [pos], or -1. */
    private fun cueIndexAt(pos: Long): Int {
        var lo = 0
        var hi = cues.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    private var clipStarted = false
    /** True while the original sound is lowered by us — so we only ever restore
     *  a volume we changed (never un-mute a player the app silenced itself). */
    private var ducked = false

    private fun playClip(file: File, windowMs: Long) {
        stopClip()
        val token = ++clipToken
        val mp = MediaPlayer()
        clip = mp
        clipStarted = false
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.path)
            mp.setOnPreparedListener { p ->
                if (token != clipToken) return@setOnPreparedListener
                val clipMs = p.duration.toLong()
                if (clipMs > windowMs) {
                    val rate = (clipMs.toFloat() / windowMs).coerceIn(1f, MAX_SPEED)
                    runCatching { p.playbackParams = PlaybackParams().setSpeed(rate) }
                }
                setOriginalVolume(duck)
                ducked = true
                clipStarted = true
                if (isPlaying()) p.start()
            }
            mp.setOnCompletionListener { if (token == clipToken) stopClip() }
            mp.setOnErrorListener { _, _, _ -> if (token == clipToken) stopClip(); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            stopClip()
        }
    }

    private fun stopClip() {
        clipToken++
        clip?.let { runCatching { it.stop() }; runCatching { it.release() } }
        clip = null
        clipStarted = false
        if (ducked) {
            ducked = false
            setOriginalVolume(1f)
        }
    }

    // ───────────────────────── preparation ─────────────────────────

    private fun prefetch() {
        val pos = effectivePos()
        val from = cueIndexAt(pos - 500L).coerceAtLeast(0)
        var i = from
        while (i < cues.size && cues[i].startMs <= pos + LOOKAHEAD_MS) {
            val idx = i
            i++
            if (cues[idx].endMs < pos - 500L) continue
            if (ready.containsKey(idx) || failed.contains(idx) || !inFlight.add(idx)) continue
            scope.launch(Dispatchers.IO) {
                try {
                    gate.withPermit {
                        if (!isActive || !active) return@withPermit
                        val file = prepare(idx)
                        if (file != null) ready[idx] = file
                        else {
                            failed.add(idx)
                            // Retry later: a rate-limit is temporary.
                            scope.launch { delay(RETRY_AFTER_MS); failed.remove(idx) }
                            noticeFailureOnce()
                        }
                    }
                } finally {
                    inFlight.remove(idx)
                }
            }
        }
    }

    private suspend fun prepare(idx: Int): File? = prepareCue(cues[idx], fitWindowMs(idx))

    private suspend fun prepareCue(cue: DubCue, fitMs: Long): File? {
        val translated = Translator.translateTo(cue.text, lang).ifBlank { cue.text }
        val spoken = SubtitleCues.forSpeech(translated)
        if (spoken.isBlank()) return null
        val voices = when (mode) {
            VoiceMode.GOOGLE -> listOf(google)
            VoiceMode.LOCAL -> listOf(local)
            VoiceMode.AUTO -> listOf(google, local)
        }
        var raw: File? = null
        for (v in voices) {
            if (!v.isAvailable(lang)) continue
            raw = v.synthesize(spoken, lang)
            if (raw != null) break
        }
        val rawClip = raw ?: return null
        if (!smart) return rawClip

        // Give the line its character's voice. Any problem => the plain clip.
        val profile = cast.profileFor(cue)
        val name = GoogleTtsVoice.key(
            "fx", lang,
            rawClip.name + "|" + profile.key + "|" + cue.emotion.name + "|" + (fitMs / 100L),
        ) + ".wav"
        val out = File(dir, name)
        if (out.exists() && out.length() > 0) return out
        return if (DubFx.process(rawClip, out, profile, cue.emotion, fitMs)) out else rawClip
    }

    /** Time the line has before the next one starts (same rule as [tick]). */
    private fun fitWindowMs(idx: Int): Long {
        val cue = cues[idx]
        val nextStart = cues.getOrNull(idx + 1)?.startMs ?: Long.MAX_VALUE
        return (minOf(nextStart, cue.endMs + 700L) - cue.startMs).coerceAtLeast(600L)
    }

    private fun noticeFailureOnce() {
        if (failureNoticeShown) return
        failureNoticeShown = true
        scope.launch(Dispatchers.Main) {
            onStatus(
                when (mode) {
                    VoiceMode.LOCAL -> "This phone has no offline voice for that language. " +
                        "Install it in Settings → Text-to-speech, or pick the Google voice."
                    else -> "Could not get dub audio (no internet, or Google is limiting requests). " +
                        "It will keep retrying."
                }
            )
        }
    }

    companion object {
        private const val TICK_MS = 100L
        private const val PREFETCH_EVERY_MS = 700L
        private const val LOOKAHEAD_MS = 40_000L
        private const val RETRY_AFTER_MS = 15_000L
        private const val MAX_SPEED = 1.6f
    }
}
