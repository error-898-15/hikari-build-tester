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
import android.os.SystemClock
import com.hikari.app.data.Logs
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

enum class VoiceMode { AUTO, GOOGLE, LOCAL, NEURAL }

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
    private val neural = SherpaTtsVoice(context)

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
    private val failureNoticeShown = AtomicBoolean(false)

    private var clip: MediaPlayer? = null
    private var clipToken = 0
    private var spokenIdx = -1
    private var lastPos = 0L

    val active: Boolean get() = loop?.isActive == true

    /** A short note about why the neural voice last stopped (null = no problem). */
    fun neuralReport(): String? = neural.report()
    var activeLang: String = ""
        private set

    /** True when this run reads captions live (see [feedLive]) instead of a cue list. */
    private var liveMode = false
    /** How far ahead lines are prepared; the neural voice is slower, so it starts earlier. */
    private var lookaheadMs = LOOKAHEAD_MS
    private var liveText: String? = null
    private var liveSeen = false
    private var liveWatch: Job? = null
    private var liveWorker: Job? = null
    private val liveQueue = java.util.ArrayDeque<LiveLine>()
    // Up to 3 lines are prepared at the same moment (see [gate]), and when the
    // voice helper stops they all fail in the same instant. A plain Boolean let
    // every one of them see "not shown yet" and each posted the same popup (the
    // popup appeared 3 times). compareAndSet lets exactly one of them through.
    private val neuralLoadingShown = AtomicBoolean(false)
    private val neuralProblemShown = AtomicBoolean(false)
    private val noticeLock = Any()
    private var lastNoticeText: String? = null
    private var lastNoticeAt = 0L

    /** One caption waiting to be voiced (live mode). */
    private class LiveLine(val cue: DubCue, val windowMs: Long, val at: Long)

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
        neural.resetCast()
        neuralLoadingShown.set(false)
        neuralProblemShown.set(false)
        lastNoticeText = null
        lastNoticeAt = 0L
        liveSeen = false
        liveQueue.clear()
        val neuralLikely = (mode == VoiceMode.NEURAL || mode == VoiceMode.AUTO) &&
            lang.substringBefore('-').equals("hi", ignoreCase = true) &&
            SherpaModels.isInstalled(context)
        lookaheadMs = if (neuralLikely) NEURAL_LOOKAHEAD_MS else LOOKAHEAD_MS
        this.cast = if (smart && !live) CastDirector.assign(cues) else Cast(emptyList())
        activeLang = lang
        failureNoticeShown.set(false)
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
        if (live) {
            liveWatch = scope.launch(Dispatchers.Main) {
                delay(30_000L)
                if (active && !liveSeen) {
                    onStatus(
                        "Dub: no captions arrived from this video in 30 seconds, so there is " +
                            "nothing to read. Its built-in subtitle track may be empty. " +
                            "Choose a subtitle with \"Load from internet\" and start dubbing again."
                    )
                }
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        liveWatch?.cancel(); liveWatch = null
        liveWorker?.cancel(); liveWorker = null
        liveQueue.clear()
        liveText = null
        stopClip()
        neural.unload()
        ready.clear()
        inFlight.clear()
        failed.clear()
        activeLang = ""
    }

    /** Stops the engine AND frees the text-to-speech engine. */
    fun release() {
        stop()
        local.release()
        neural.release()
    }

    /**
     * LIVE mode: the caption that is on screen right now (null / empty = none).
     * Every new line joins a short queue and is voiced one after another (a
     * line is never thrown away just because the next caption appeared while
     * it was still being prepared). Main thread only.
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
        if (!liveSeen) {
            liveSeen = true
            onStatus("Dub: captions are arriving. The first line can take a few seconds to prepare.")
        }
        val first = parsed.first()
        val body = parsed.joinToString(" ") { it.text }
        val windowMs = (body.length * 65L).coerceIn(1_200L, 6_000L)
        val cue = DubCue(0L, windowMs, body, first.speaker, first.turn, first.emotion)

        liveQueue.addLast(LiveLine(cue, windowMs, SystemClock.elapsedRealtime()))
        while (liveQueue.size > 3) liveQueue.removeFirst()   // keep only the newest few
        if (liveWorker?.isActive != true) {
            liveWorker = scope.launch(Dispatchers.Main) { runLiveQueue() }
        }
    }

    private suspend fun runLiveQueue() {
        while (active && liveQueue.isNotEmpty()) {
            val line = liveQueue.removeFirst()
            if (SystemClock.elapsedRealtime() - line.at > LIVE_STALE_MS) continue
            val file = withContext(Dispatchers.IO) {
                gate.withPermit { prepareCue(line.cue, line.windowMs) }
            }
            if (!active) return
            if (file == null) {
                noticeFailureOnce()
                continue
            }
            // Let the line that is speaking finish first (but not forever).
            var waited = 0L
            while (active && clip != null && waited < 8_000L) {
                delay(100L)
                waited += 100L
            }
            if (!active) return
            if (SystemClock.elapsedRealtime() - line.at > LIVE_STALE_MS) continue
            playClip(file, line.windowMs)
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
        while (i < cues.size && cues[i].startMs <= pos + lookaheadMs) {
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
        // A line with no letter or digit in it ("...", "♪", "-") has nothing to say.
        if (spoken.none { it.isLetterOrDigit() }) return null
        // Which character speaks (and so which actor / pitch / speed).
        val profile = if (smart) cast.profileFor(cue) else CastDirector.NARRATOR
        val voices = when (mode) {
            VoiceMode.GOOGLE -> listOf(google)
            VoiceMode.LOCAL -> listOf(local)
            // The neural voice only answers when its model is installed and
            // the language is supported; otherwise the next one is used.
            VoiceMode.AUTO, VoiceMode.NEURAL -> listOf<DubVoice>(neural, google, local)
        }
        var raw: File? = null
        var fromNeural = false
        for (v in voices) {
            if (!active) return null
            if (!v.isAvailable(lang)) continue
            if (v === neural && !neural.isLoaded && neuralLoadingShown.compareAndSet(false, true)) {
                withContext(Dispatchers.Main) {
                    showNotice("Dub: loading the neural voice (the first time takes a little while)...")
                }
            }
            raw = v.synthesize(spoken, lang, profile)
            if (raw != null) {
                fromNeural = v === neural
                break
            }
            if (v === neural && active && neuralProblemShown.compareAndSet(false, true)) {
                val why = neural.lastError ?: "unknown reason"
                withContext(Dispatchers.Main) {
                    Logs.log("Dub", "Neural voice failed: $why")
                    showNotice("Dub: the neural voice stopped, so the normal voice is used. Open Dub audio to see why.")
                }
            }
        }
        val rawClip = raw ?: return null
        if (!smart) return rawClip

        // Give the line its character's voice. Any problem => the plain clip.
        // A neural actor is already a real man / woman, so the pitch change is
        // kept small (only enough to tell two characters of one actor apart).
        val fx = if (fromNeural) profile.copy(semitones = profile.semitones * 0.35f) else profile
        val name = GoogleTtsVoice.key(
            "fx", lang,
            rawClip.name + "|" + fx.key + "|" + cue.emotion.name + "|" + (fitMs / 100L) + "|" + fromNeural,
        ) + ".wav"
        val out = File(dir, name)
        if (out.exists() && out.length() > 0) return out
        return if (DubFx.process(rawClip, out, fx, cue.emotion, fitMs)) out else rawClip
    }

    /** Time the line has before the next one starts (same rule as [tick]). */
    private fun fitWindowMs(idx: Int): Long {
        val cue = cues[idx]
        val nextStart = cues.getOrNull(idx + 1)?.startMs ?: Long.MAX_VALUE
        return (minOf(nextStart, cue.endMs + 700L) - cue.startMs).coerceAtLeast(600L)
    }

    /**
     * The one door the prepare-path popups go through. The same text is never
     * shown twice within 20 seconds, so a burst of failures cannot repeat a
     * popup. (The player also replaces a popup that is still on screen instead
     * of queueing a new one behind it.)
     */
    private fun showNotice(text: String) {
        synchronized(noticeLock) {
            val now = SystemClock.elapsedRealtime()
            if (text == lastNoticeText && now - lastNoticeAt < NOTICE_REPEAT_MS) return
            lastNoticeText = text
            lastNoticeAt = now
        }
        onStatus(text)
    }

    private fun noticeFailureOnce() {
        if (!failureNoticeShown.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Main) {
            showNotice(
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
        private const val NEURAL_LOOKAHEAD_MS = 120_000L
        private const val LIVE_STALE_MS = 15_000L
        private const val RETRY_AFTER_MS = 15_000L
        private const val MAX_SPEED = 1.6f
        private const val NOTICE_REPEAT_MS = 20_000L
    }
}
