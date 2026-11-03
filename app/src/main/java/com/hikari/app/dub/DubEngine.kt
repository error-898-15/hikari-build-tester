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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

enum class VoiceMode { AUTO, GOOGLE, LOCAL }

/**
 * Dubs a video from its subtitle:
 *
 *   cue ──translate──▶ text in the chosen language ──TTS──▶ audio clip (one voice)
 *                                                              │
 *   character ──▶ pitch / speed profile (male, female, child…) │
 *   player position ───── plays the clip when its cue starts ◀─┘
 *
 * Two ways of getting the cues:
 *  - FILE mode: the whole subtitle text is known. Only the next [LOOKAHEAD_MS]
 *    are translated and voiced, so starting is instant and the voice is
 *    exactly in time.
 *  - LIVE mode: the subtitle is a track inside the video file (no file to
 *    read). Lines are captured as the player shows them ([feedLive]) and
 *    voiced straight away, so the voice starts about a second after the
 *    line appears. Lines already seen are remembered, so rewinding is exact.
 *
 * The original soundtrack is lowered ("ducked") while a clip speaks.
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
    /** A voiced line. [file] is null when there is nothing to say. */
    private class Ready(val file: File?, val pitch: Float, val speed: Float)

    private val google = GoogleTtsVoice(context)
    private val local = LocalTtsVoice(context)

    private val cues = ArrayList<DubCue>()
    private var live = false
    private var multiVoice = true
    private var cast = VoiceCast()
    private val labelSeen = HashMap<String, Int>()
    private var nextId = 0
    private var liveOpenId = -1
    private var liveOpenText = ""

    private var lang = "en"
    private var mode = VoiceMode.AUTO
    private var duck = 0.15f

    private var loop: Job? = null
    private val gate = Semaphore(3)
    private val ready = ConcurrentHashMap<Int, Ready>()
    private val inFlight = ConcurrentHashMap.newKeySet<Int>()
    private val failed = ConcurrentHashMap.newKeySet<Int>()
    private var failureNoticeShown = false

    private var clip: MediaPlayer? = null
    private var clipToken = 0
    private var clipStarted = false
    /** True while WE lowered the original sound, so we only restore what we changed. */
    private var ducked = false
    private var spokenId = -1
    private var lastPos = 0L

    val active: Boolean get() = loop?.isActive == true
    var activeLang: String = ""
        private set

    fun start(
        newCues: List<DubCue>,
        lang: String,
        mode: VoiceMode,
        duckVolume: Float,
        multiVoice: Boolean,
        live: Boolean,
    ) {
        stop()
        if (!live && newCues.isEmpty()) {
            onStatus("No readable lines in that subtitle.")
            return
        }
        this.lang = lang
        this.mode = mode
        this.duck = duckVolume.coerceIn(0f, 1f)
        this.multiVoice = multiVoice
        this.live = live
        cues.addAll(newCues)
        nextId = (newCues.maxOfOrNull { it.id } ?: -1) + 1
        cast = VoiceCast()
        labelSeen.clear()
        if (multiVoice) {
            // Give every character a voice in order of first appearance, so the
            // casting is the same however the person seeks around.
            for (c in cues) cast.profileFor(c.speaker)
            cast.summary()?.let { onStatus(it) }
        }
        activeLang = lang
        failureNoticeShown = false
        spokenId = -1
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
        stopClip()
        cues.clear()
        ready.clear()
        inFlight.clear()
        failed.clear()
        liveOpenId = -1
        liveOpenText = ""
        activeLang = ""
    }

    /** Stops the engine AND frees the text-to-speech engine. */
    fun release() {
        stop()
        local.release()
    }

    // ───────────────────────── live capture ─────────────────────────

    /**
     * LIVE mode: the text of the caption on screen right now, or null when
     * nothing is shown. Called whenever the player changes its caption.
     */
    fun feedLive(rawText: String?) {
        if (!active || !live) return
        val pos = positionMs()
        val key = rawText?.trim().orEmpty()
        if (key == liveOpenText) return

        // The line that was on screen has just ended: now we know how long it was.
        if (liveOpenId >= 0) {
            val i = cues.indexOfFirst { it.id == liveOpenId }
            if (i >= 0) cues[i] = cues[i].copy(endMs = maxOf(pos, cues[i].startMs + 400L))
            liveOpenId = -1
        }
        liveOpenText = key
        if (key.isEmpty()) return

        var t = pos
        for (turn in SubtitleCues.liveTurns(key, labelSeen)) {
            val est = (turn.text.length * 65L).coerceIn(1200L, 8000L)
            // Seen before (the person rewound)? Reuse the voiced clip.
            val dup = cues.firstOrNull { abs(it.startMs - t) < 900L && it.text == turn.text }
            if (dup != null) {
                liveOpenId = dup.id
                t += est
                continue
            }
            val c = DubCue(nextId++, t, t + est, turn.text, turn.speaker, turn.shout)
            val at = cues.indexOfFirst { it.startMs > c.startMs }
            if (at < 0) cues += c else cues.add(at, c)
            if (multiVoice) cast.profileFor(c.speaker)
            liveOpenId = c.id
            t += est
            schedule(c)
        }
    }

    // ───────────────────────── playback ─────────────────────────

    private fun effectivePos(): Long =
        if (live) positionMs() else positionMs() - offsetMs()

    private fun tick() {
        val pos = effectivePos()

        // Seek detection: ticks are 100 ms apart, so a bigger jump is a seek.
        if (pos < lastPos - 300L || pos > lastPos + 2_500L) {
            stopClip()
            spokenId = -1
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
        if (idx < 0) return
        val cue = cues[idx]
        if (cue.id == spokenId) return
        val tooLate = if (live) pos > cue.endMs + LIVE_GRACE_MS else pos >= cue.endMs - 300L
        if (tooLate) return
        val r = ready[cue.id] ?: return              // not voiced yet; try again next tick
        spokenId = cue.id
        val file = r.file ?: return                  // nothing to say for this line

        val nextStart = cues.getOrNull(idx + 1)?.startMs ?: Long.MAX_VALUE
        val windowMs = if (live) {
            (cue.endMs + 900L - pos).coerceAtLeast(1_400L)
        } else {
            (minOf(nextStart, cue.endMs + 700L) - pos).coerceAtLeast(600L)
        }
        playClip(file, windowMs, r)
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

    private fun playClip(file: File, windowMs: Long, r: Ready) {
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
                val clipMs = p.duration.toLong().coerceAtLeast(1L)
                // The character's own speed, sped up further only if the line
                // would otherwise run into the next one.
                val rate = maxOf(r.speed, clipMs.toFloat() / windowMs).coerceIn(0.85f, MAX_SPEED)
                runCatching { p.playbackParams = PlaybackParams().setSpeed(rate).setPitch(r.pitch) }
                setOriginalVolume(duck)
                ducked = true
                clipStarted = true
                // Some Android versions start playing when the params are set:
                // make the state match the video either way.
                if (isPlaying()) { if (!p.isPlaying) p.start() } else runCatching { p.pause() }
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
        var i = cueIndexAt(pos - 500L).coerceAtLeast(0)
        while (i < cues.size && cues[i].startMs <= pos + LOOKAHEAD_MS) {
            val cue = cues[i]
            i++
            if (cue.endMs < pos - 500L) continue
            schedule(cue)
        }
    }

    /** Translates and voices one cue in the background. Main thread only. */
    private fun schedule(cue: DubCue) {
        if (ready.containsKey(cue.id) || failed.contains(cue.id) || !inFlight.add(cue.id)) return
        val profile = if (multiVoice) cast.profileFor(cue.speaker) else VoiceCast.NARRATOR
        scope.launch(Dispatchers.IO) {
            try {
                gate.withPermit {
                    if (!isActive || !active) return@withPermit
                    val r = prepare(cue, profile)
                    if (r != null) {
                        ready[cue.id] = r
                    } else {
                        failed.add(cue.id)
                        // Retry later: a rate-limit is temporary.
                        scope.launch { delay(RETRY_AFTER_MS); failed.remove(cue.id) }
                        noticeFailureOnce()
                    }
                }
            } finally {
                inFlight.remove(cue.id)
            }
        }
    }

    private suspend fun prepare(cue: DubCue, profile: VoiceProfile): Ready? {
        val translated = Translator.translateTo(cue.text, lang).ifBlank { cue.text }
        val spoken = Prosody.forSpeech(translated)
        if (spoken.isBlank()) return Ready(null, 1f, 1f)
        val mood = Prosody.mood(spoken, cue.shout)
        val voices = when (mode) {
            VoiceMode.GOOGLE -> listOf(google)
            VoiceMode.LOCAL -> listOf(local)
            VoiceMode.AUTO -> listOf(google, local)
        }
        for (v in voices) {
            if (!v.isAvailable(lang)) continue
            val file = v.synthesize(spoken, lang) ?: continue
            return Ready(
                file,
                (profile.pitch * mood.pitch).coerceIn(0.6f, 1.6f),
                (profile.speed * mood.speed).coerceIn(0.8f, 1.4f),
            )
        }
        return null
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
        private const val LIVE_GRACE_MS = 3_500L
        private const val MAX_SPEED = 1.6f
    }
}
