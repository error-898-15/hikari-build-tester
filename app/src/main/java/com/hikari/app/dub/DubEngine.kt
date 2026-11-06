package com.hikari.app.dub

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.SystemClock
import com.hikari.app.data.Logs
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

enum class VoiceMode { AUTO, GOOGLE, LOCAL, NEURAL }

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
    /** Current playing stream URL for original audio pitch/gender analysis. */
    private val mediaUrl: () -> String? = { null },
    /** Optional HTTP headers for the current playing stream. */
    private val mediaHeaders: () -> Map<String, String> = { emptyMap() },
) {
    private val google = GoogleTtsVoice(context)
    private val local = LocalTtsVoice(context)
    private val neural = SherpaTtsVoice(context)

    private var cues: List<DubCue> = emptyList()
    private var lang = "en"
    private var mode = VoiceMode.AUTO
    private var duck = 0.15f
    private var cutAmount = 0.9f
    private var liveHoldUntil = 0L

    @Volatile private var smart = true
    @Volatile private var cast: Cast = Cast(emptyList())
    private val dir = File(context.cacheDir, "dub").apply { mkdirs() }

    private var loop: Job? = null
    private var genderScanJob: Job? = null
    private val translateGate = Semaphore(4)
    private val synthGate = Semaphore(2)
    private val ready = ConcurrentHashMap<Int, File>()
    private val inFlight = ConcurrentHashMap.newKeySet<Int>()
    private val failed = ConcurrentHashMap.newKeySet<Int>()
    private val empty = ConcurrentHashMap.newKeySet<Int>()
    private val attempts = ConcurrentHashMap<Int, Int>()
    private val translations = ConcurrentHashMap<String, String>()
    private var doneNoticeShown = false
    private var failureNoticeShown = false

    private var clip: MediaPlayer? = null
    private var clipToken = 0
    private var spokenIdx = -1
    @Volatile private var lastPos = 0L

    val active: Boolean get() = loop?.isActive == true

    fun neuralReport(): String? = neural.report()
    var activeLang: String = ""
        private set

    private var liveMode = false
    private var lookaheadMs = LOOKAHEAD_MS
    private var liveText: String? = null
    private var liveSeen = false
    private var liveWatch: Job? = null
    private var liveWorker: Job? = null
    private val liveQueue = java.util.ArrayDeque<LiveLine>()
    private var neuralLoadingShown = false
    private var neuralProblemShown = false

    private class LiveLine(val cue: DubCue, val windowMs: Long, val at: Long)

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
        this.cast = if (smart && !live) CastDirector.assign(cues) else Cast(emptyList())
        activeLang = lang
        failureNoticeShown = false
        spokenIdx = -1
        lastPos = effectivePos()

        // Launch fast (<2 min) background audio pitch + 3D-Speaker gender detection for unnamed cues
        val hasUnnamed = smart && !live && cues.any { it.speaker == null }
        val url = mediaUrl()
        if (hasUnnamed && !url.isNullOrBlank()) {
            val headers = mediaHeaders()
            val currentCast = this.cast
            val currentCues = this.cues
            genderScanJob = scope.launch(Dispatchers.IO) {
                val count = AudioGenderDetector.analyzeEpisode(
                    context = context,
                    mediaUrl = url,
                    headers = headers,
                    cues = currentCues,
                ) { idx, result ->
                    val c = currentCues.getOrNull(idx) ?: return@analyzeEpisode
                    val before = currentCast.profileFor(c)
                    currentCast.updateFromAudio(c, result)
                    val after = currentCast.profileFor(c)
                    // If a future cue was already synthesized with the wrong gender, re-queue it
                    if (before.gender != after.gender && idx > spokenIdx && c.startMs > lastPos + 1_500L) {
                        ready.remove(idx)
                    }
                }
                if (count > 0 && active) {
                    withContext(Dispatchers.Main) {
                        Logs.log("Dub", "Audio pitch & speaker gender detected for $count unnamed lines")
                    }
                }
            }
        }

        if (neuralLikely && !live) {
            onStatus(
                "Dub: preparing the voices & analyzing speaker pitch (< 2 min). " +
                    "The dub starts speaking as soon as the first lines are ready."
            )
        }
        loop = scope.launch(Dispatchers.Main) {
            var sincePrefetch = PREFETCH_EVERY_MS
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
        genderScanJob?.cancel()
        genderScanJob = null
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

    fun release() {
        stop()
        local.release()
        neural.release()
    }

    fun feedLive(text: String?) {
        if (!active || !liveMode) return
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) {
            liveText = null
            return
        }
        if (t == liveText) return
        liveText = t

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
        while (liveQueue.size > 3) liveQueue.removeFirst()
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

    private fun effectivePos() = positionMs() - offsetMs()

    private fun tick() {
        val pos = effectivePos()

        if (pos < lastPos - 300L || pos > lastPos + 2_500L) {
            stopClip()
            spokenIdx = -1
        }
        lastPos = pos

        clip?.let { mp ->
            runCatching {
                if (!isPlaying() && mp.isPlaying) mp.pause()
                else if (isPlaying() && !mp.isPlaying && clipStarted) mp.start()
            }
        }
        if (!isPlaying()) return

        updateOriginal(pos)

        val idx = cueIndexAt(pos)
        if (idx < 0 || idx == spokenIdx) return
        val cue = cues[idx]
        if (pos >= cue.endMs - 300L) return
        val file = ready[idx] ?: return
        if (!file.exists()) {
            ready.remove(idx)
            return
        }
        spokenIdx = idx
        val nextStart = cues.getOrNull(idx + 1)?.startMs ?: Long.MAX_VALUE
        val windowMs = (minOf(nextStart, cue.endMs + 700L) - pos).coerceAtLeast(600L)
        playClip(file, windowMs)
    }

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

    private fun updateOriginal(pos: Long) {
        if (DubVoiceCut.works) {
            DubVoiceCut.target = if (covered(pos, CUT_LEAD_MS)) cutAmount else 0f
            if (ducked) {
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

    private fun restoreOriginal() {
        DubVoiceCut.target = 0f
        if (ducked) {
            ducked = false
            setOriginalVolume(1f)
        }
    }

    private fun wanted(idx: Int): Boolean {
        val c = cues.getOrNull(idx) ?: return false
        return active && c.endMs > lastPos - 300L
    }

    private fun prefetch() {
        val pos = effectivePos()
        var i = cueIndexAt(pos - 500L).coerceAtLeast(0)
        var queued = inFlight.size
        while (i < cues.size && cues[i].startMs <= pos + lookaheadMs && queued < MAX_QUEUED) {
            val idx = i
            i++
            if (cues[idx].endMs < pos - 300L) continue
            if (ready.containsKey(idx) || failed.contains(idx) || empty.contains(idx)) continue
            if (!inFlight.add(idx)) continue
            queued++
            scope.launch(Dispatchers.IO) {
                try {
                    if (!wanted(idx)) return@launch
                    val file = prepare(idx)
                    if (file === NOTHING_TO_SAY) {
                        empty.add(idx)
                    } else if (file != null) {
                        ready[idx] = file
                    } else if (wanted(idx)) {
                        failed.add(idx)
                        val n = (attempts[idx] ?: 0) + 1
                        attempts[idx] = n
                        if (n < MAX_ATTEMPTS) scope.launch { delay(RETRY_AFTER_MS); failed.remove(idx) }
                        noticeFailureOnce()
                    }
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

    private val NOTHING_TO_SAY = File("nothing-to-say")

    private suspend fun prepareCue(cue: DubCue, fitMs: Long, stillWanted: () -> Boolean): File? {
        val tKey = lang + "|" + cue.text
        var translated = translations[tKey]
        if (translated == null) {
            val t = translateGate.withPermit { Translator.translateTo(cue.text, lang) }
            if (looksUntranslated(cue.text, t)) return null
            translated = t.ifBlank { cue.text }
            translations[tKey] = translated
        }
        if (!active || !stillWanted()) return null
        val spoken = SubtitleCues.forSpeech(translated)
        if (spoken.isBlank()) return NOTHING_TO_SAY
        val profile = if (smart) cast.profileFor(cue) else CastDirector.NARRATOR
        val voices = when (mode) {
            VoiceMode.GOOGLE -> listOf(google)
            VoiceMode.LOCAL -> listOf(local)
            VoiceMode.NEURAL -> listOf<DubVoice>(neural)
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
            if (made == null && !stillWanted()) return null
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

    private fun isQuestion(s: String): Boolean {
        val t = s.trimEnd('"', '\'', '\u201D', '\u2019', ')', ' ')
        return t.endsWith("?") || t.endsWith("\uFF1F") || t.endsWith("\u061F")
    }

    private fun looksUntranslated(src: String, out: String): Boolean {
        if (lang.substringBefore('-').equals("en", ignoreCase = true)) return false
        val a = src.trim()
        if (a != out.trim()) return false
        val letters = a.count { it.isLetter() }
        return letters >= 4 && a.all { it.code < 128 }
    }

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
        private const val NEURAL_LOOKAHEAD_MS = 20L * 60_000L
        private const val MAX_QUEUED = 8
        private const val MAX_ATTEMPTS = 3
        private const val LIVE_STALE_MS = 15_000L
        private const val RETRY_AFTER_MS = 15_000L
        private const val MAX_SPEED = 1.6f
        private const val CUT_LEAD_MS = 500L
        private const val CUT_TAIL_MS = 250L
        private const val MONO_FLOOR = 0.10f
    }
}
