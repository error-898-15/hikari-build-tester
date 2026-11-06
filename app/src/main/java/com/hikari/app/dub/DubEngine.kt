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
import kotlin.math.abs

enum class VoiceMode { AUTO, GOOGLE, LOCAL, NEURAL }

/**
 * Dubs a video from its subtitle file:
 *
 *   subtitle cue ──translate──▶ text in the chosen language ──TTS──▶ audio clip
 *                                                                      │
 *   player position ──────────── plays the clip when its cue starts ◀──┘
 *
 * Nothing is processed before the person presses start, and only the cues in
 * a window ahead of the playhead are translated and voiced, so starting is
 * instant and a seek just moves the window. With the offline neural voice
 * (fast) that window is [NEURAL_LOOKAHEAD_MS] long, so a whole episode is
 * voiced within a minute or two while it plays; with the online / phone
 * voices it is [LOOKAHEAD_MS].
 *
 * Lines are prepared in playback order, and a line whose time has already
 * passed is dropped instead of being made (that waste is what used to make
 * the dub fall behind and then go silent).
 *
 * While a line is dubbed, the ORIGINAL DIALOGUE is taken out of the movie's
 * sound but the music and the sound effects keep playing: the audio stage of
 * the player ([VoiceCutProcessor]) removes the centre of the stereo picture
 * (or the centre channel of a 5.1 mix) for exactly the time the subtitle line
 * is on screen. Mono sound cannot be separated, so there (and only there)
 * the whole original sound is lowered ("ducked") instead, as before.
 * A clip longer than its cue is sped up (max [MAX_SPEED]x) so the voice does
 * not run into the next line.
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
    /** How much of the original dialogue is removed while the dub speaks (0..1). */
    private var cutAmount = 0.9f
    /** Live mode: the dialogue stays removed until this moment (elapsedRealtime). */
    private var liveHoldUntil = 0L

    /** True = every character gets its own voice and the clips are edited
     *  (pitch, speed, loudness). False = the plain voice, as before. */
    @Volatile private var smart = true
    @Volatile private var cast: Cast = Cast(emptyList())
    private val dir = File(context.cacheDir, "dub").apply { mkdirs() }

    private var loop: Job? = null
    /** Translating is network work: several at once. */
    private val translateGate = Semaphore(4)
    /** Making the voice: two at once keeps the neural helper busy without losing the order. */
    private val synthGate = Semaphore(2)
    private val ready = ConcurrentHashMap<Int, File>()
    private val inFlight = ConcurrentHashMap.newKeySet<Int>()
    private val failed = ConcurrentHashMap.newKeySet<Int>()
    /** Lines with nothing to say after cleaning (a music note, a sound tag). */
    private val empty = ConcurrentHashMap.newKeySet<Int>()
    private val attempts = ConcurrentHashMap<Int, Int>()
    /** "lang|original line" -> translated line, so a retry never translates twice. */
    private val translations = ConcurrentHashMap<String, String>()
    private var doneNoticeShown = false
    private var failureNoticeShown = false

    private var clip: MediaPlayer? = null
    private var clipToken = 0
    private var spokenIdx = -1
    /** Last playhead position seen by [tick] (main thread); safe to read from any thread. */
    @Volatile private var lastPos = 0L

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
    private var neuralLoadingShown = false
    private var neuralProblemShown = false

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
        /** What listening to the original voices found (who speaks each line, man or woman); null = not scanned. */
        scan: VoiceScanResult? = null,
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
        // "Silent" removes 97% of the dialogue, "Quiet" 88%, "Medium" 76%.
        this.cutAmount = (0.97f - this.duck * 0.6f).coerceIn(0.5f, 0.97f)
        liveHoldUntil = 0L
        this.smart = multi
        this.liveMode = live
        neural.resetCast()
        neuralLoadingShown = false
        neuralProblemShown = false
        liveSeen = false
        liveQueue.clear()
        val neuralLikely = (mode == VoiceMode.NEURAL || mode == VoiceMode.AUTO) &&
            SherpaModels.isInstalledFor(context, lang)
        lookaheadMs = if (neuralLikely) NEURAL_LOOKAHEAD_MS else LOOKAHEAD_MS
        if (translations.size > 4000) translations.clear()
        attempts.clear()
        empty.clear()
        doneNoticeShown = false
        runCatching { DubCache.trim(dir, 300L * 1024 * 1024) }
        this.cast = if (smart && !live) CastDirector.assign(cues, scan) else Cast(emptyList())
        activeLang = lang
        failureNoticeShown = false
        spokenIdx = -1
        lastPos = effectivePos()
        if (neuralLikely && !live) {
            onStatus(
                "Dub: preparing the voices. The whole video takes about 1-2 minutes; " +
                    "the dub starts speaking as soon as the first lines are ready."
            )
        }
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
        restoreOriginal()
        neural.unload()
        ready.clear()
        inFlight.clear()
        failed.clear()
        empty.clear()
        attempts.clear()
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
                prepareCue(line.cue, line.windowMs) { true }
            }
            if (!active) return
            if (file === NOTHING_TO_SAY) continue
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

        // Take the original dialogue out (or, for mono sound, lower it).
        updateOriginal(pos)

        val idx = cueIndexAt(pos)
        if (idx < 0 || idx == spokenIdx) return
        val cue = cues[idx]
        if (pos >= cue.endMs - 300L) return          // too late to start this one
        val file = ready[idx] ?: return              // not voiced yet; try again next tick
        if (!file.exists()) {                        // the cache folder was cleaned: make it again
            ready.remove(idx)
            return
        }
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
                liveHoldUntil = SystemClock.elapsedRealtime() + windowMs + 250L
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
    }

    // ───────────────────────── original dialogue ─────────────────────────

    /**
     * Decides, every tick, what happens to the original sound.
     *
     * Normal case (stereo / 5.1): the dialogue is cut out for the time the
     * subtitle line is heard, starting a little EARLY ([CUT_LEAD_MS]) because
     * the audio stage works a fraction of a second ahead of what is heard.
     * The music and the effects are not touched.
     *
     * Mono sound: nothing can be separated, so the whole sound is lowered
     * for the time the dub clip speaks (never fully silent, see [MONO_FLOOR]).
     */
    private fun updateOriginal(pos: Long) {
        if (DubVoiceCut.works) {
            DubVoiceCut.target = if (covered(pos, CUT_LEAD_MS)) cutAmount else 0f
            if (ducked) {                         // the sound became separable: undo the lowering
                ducked = false
                setOriginalVolume(1f)
            }
        } else {
            DubVoiceCut.target = 0f
            val want = covered(pos, 0L)
            if (want && !ducked) {
                ducked = true
                setOriginalVolume(duck.coerceAtLeast(MONO_FLOOR))
            } else if (!want && ducked) {
                ducked = false
                setOriginalVolume(1f)
            }
        }
    }

    /**
     * True when the original dialogue at [pos] + [lead] must be silenced because
     * the dub speaks (or is about to speak) that line. A line that has no dub
     * (not ready yet, failed, only music) keeps its original voice, so there is
     * never a silent hole.
     */
    private fun covered(pos: Long, lead: Long): Boolean {
        if (!active) return false
        if (liveMode) {
            return (clip != null && clipStarted) || SystemClock.elapsedRealtime() < liveHoldUntil
        }
        val ahead = pos + lead
        val idx = cueIndexAt(ahead)
        if (idx < 0) return false
        val c = cues[idx]
        if (ahead > c.endMs + CUT_TAIL_MS) return false
        if (idx == spokenIdx) return true
        return ready.containsKey(idx) && pos < c.endMs - 300L
    }

    /** Gives the original sound back completely. */
    private fun restoreOriginal() {
        DubVoiceCut.target = 0f
        if (ducked) {
            ducked = false
            setOriginalVolume(1f)
        }
    }

    // ───────────────────────── preparation ─────────────────────────

    /** True while line [idx] can still be spoken in time (its end is not past). */
    private fun wanted(idx: Int): Boolean {
        val c = cues.getOrNull(idx) ?: return false
        // not effectivePos(): the player may only be asked from the main thread
        return active && c.endMs > lastPos - 300L
    }

    private fun prefetch() {
        val pos = effectivePos()
        var i = cueIndexAt(pos - 500L).coerceAtLeast(0)
        var queued = inFlight.size
        // Lines are taken in playback order, a few at a time, so the work
        // always follows the playhead (also after a seek).
        while (i < cues.size && cues[i].startMs <= pos + lookaheadMs && queued < MAX_QUEUED) {
            val idx = i
            i++
            if (cues[idx].endMs < pos - 300L) continue
            if (ready.containsKey(idx) || failed.contains(idx) || empty.contains(idx)) continue
            if (!inFlight.add(idx)) continue
            queued++
            scope.launch(Dispatchers.IO) {
                try {
                    if (!wanted(idx)) return@launch          // too late already: not worth making
                    val file = prepare(idx)
                    if (file === NOTHING_TO_SAY) {
                        empty.add(idx)
                    } else if (file != null) {
                        ready[idx] = file
                    } else if (wanted(idx)) {
                        failed.add(idx)
                        val n = (attempts[idx] ?: 0) + 1
                        attempts[idx] = n
                        // Retry later (a rate-limit is temporary), but not for ever.
                        if (n < MAX_ATTEMPTS) scope.launch { delay(RETRY_AFTER_MS); failed.remove(idx) }
                        noticeFailureOnce()
                    }
                    // else: dropped because its time passed. Not an error.
                    Unit
                } finally {
                    inFlight.remove(idx)
                }
            }
        }
        if (i >= cues.size && inFlight.isEmpty() && failed.isEmpty() &&
            ready.isNotEmpty() && !doneNoticeShown && !liveMode
        ) {
            doneNoticeShown = true
            onStatus("Dub: the whole video is voiced (" + ready.size + " lines).")
        }
    }

    private suspend fun prepare(idx: Int): File? =
        prepareCue(cues[idx], fitWindowMs(idx)) { wanted(idx) }

    /** Answer of [prepareCue] for a line that has nothing to say. */
    private val NOTHING_TO_SAY = File("nothing-to-say")

    /**
     * Translates and voices one line. Returns the audio file, [NOTHING_TO_SAY],
     * or null when it failed OR when [stillWanted] turned false (its time passed).
     */
    private suspend fun prepareCue(cue: DubCue, fitMs: Long, stillWanted: () -> Boolean): File? {
        val tKey = lang + "|" + cue.text
        var translated = translations[tKey]
        if (translated == null) {
            val t = translateGate.withPermit { Translator.translateTo(cue.text, lang) }
            // A failed translation comes back unchanged. Reading English with a
            // Hindi voice only makes noise, so count it as a failure and retry.
            if (looksUntranslated(cue.text, t)) return null
            translated = t.ifBlank { cue.text }
            translations[tKey] = translated
        }
        if (!active || !stillWanted()) return null
        val spoken = SubtitleCues.forSpeech(translated)
        if (spoken.isBlank()) return NOTHING_TO_SAY
        // Which character speaks (and so which actor / pitch / speed).
        val profile = if (smart) cast.profileFor(cue) else CastDirector.NARRATOR
        val voices = when (mode) {
            VoiceMode.GOOGLE -> listOf(google)
            VoiceMode.LOCAL -> listOf(local)
            // Only the neural voice: no Google voice and no phone voice behind it.
            VoiceMode.NEURAL -> listOf<DubVoice>(neural)
            // The neural voice only answers when its voices are installed and
            // the language is supported; otherwise the next one is used.
            VoiceMode.AUTO -> listOf<DubVoice>(neural, google, local)
        }
        var raw: File? = null
        var fromNeural = false
        for (v in voices) {
            if (!active) return null
            if (!v.isAvailable(lang)) continue
            if (v === neural && !neural.isLoaded && !neuralLoadingShown) {
                neuralLoadingShown = true
                withContext(Dispatchers.Main) {
                    onStatus("Dub: loading the neural voices (the first time takes a little while)...")
                }
            }
            val made = synthGate.withPermit {
                if (!active || !stillWanted()) null else v.synthesize(spoken, lang, profile)
            }
            if (made == null && !stillWanted()) return null       // too late, not a failure
            raw = made
            if (raw != null) {
                fromNeural = v === neural
                break
            }
            if (v === neural && !neuralProblemShown && active) {
                neuralProblemShown = true
                val why = neural.lastError ?: "unknown reason"
                withContext(Dispatchers.Main) {
                    Logs.log("Dub", "Neural voice failed: $why")
                    onStatus(
                        if (mode == VoiceMode.NEURAL)
                            "Dub: the neural voice stopped ($why). Open Dub audio to see more."
                        else
                            "Dub: the neural voice stopped, so the normal voice is used. Open Dub audio to see why."
                    )
                }
            }
        }
        val rawClip = raw ?: return null
        if (!smart) return rawClip

        // Give the line its character's voice. Any problem => the plain clip.
        // The neural voice says by itself how much pitch change its actor needs.
        val fx = if (fromNeural) neural.fxFor(profile) else profile
        val question = isQuestion(spoken)
        val name = GoogleTtsVoice.key(
            "fx", lang,
            rawClip.name + "|" + fx.key + "|" + cue.emotion.name + "|" + question + "|e3|" +
                (fitMs / 100L) + "|" + fromNeural,
        ) + ".wav"
        val out = File(dir, name)
        if (out.exists() && out.length() > 0) return out
        return if (DubFx.process(rawClip, out, fx, cue.emotion, fitMs, question)) out else rawClip
    }

    /** A spoken line that ends with a question mark (the voice rises at the end). */
    private fun isQuestion(s: String): Boolean {
        val t = s.trimEnd('"', '\'', '\u201D', '\u2019', ')', ' ')
        return t.endsWith("?") || t.endsWith("\uFF1F") || t.endsWith("\u061F")
    }

    /** The translator gives the text back unchanged when it fails. */
    private fun looksUntranslated(src: String, out: String): Boolean {
        if (lang.substringBefore('-').equals("en", ignoreCase = true)) return false
        val a = src.trim()
        if (a != out.trim()) return false
        val letters = a.count { it.isLetter() }
        return letters >= 4 && a.all { it.code < 128 }
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
                    VoiceMode.NEURAL -> "Dub: some lines could not be voiced (translation failed, " +
                        "usually no internet). It will try again."
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
        /** The neural voice is faster than speech: voice 20 minutes ahead. */
        private const val NEURAL_LOOKAHEAD_MS = 20L * 60_000L
        /** Lines being made at the same moment (keeps the work in playback order). */
        private const val MAX_QUEUED = 8
        private const val MAX_ATTEMPTS = 3
        private const val LIVE_STALE_MS = 15_000L
        private const val RETRY_AFTER_MS = 15_000L
        private const val MAX_SPEED = 1.6f
        /** The audio stage runs this far ahead of what is heard: cut the dialogue that much earlier. */
        private const val CUT_LEAD_MS = 500L
        /** Keep the dialogue cut a little after the subtitle line ends. */
        private const val CUT_TAIL_MS = 250L
        /** Mono sound: the original is never lowered below this (so effects and music stay faintly audible). */
        private const val MONO_FLOOR = 0.10f
    }
}
