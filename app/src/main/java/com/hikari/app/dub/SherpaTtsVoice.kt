package com.hikari.app.dub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.hikari.app.data.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline neural voices (Piper through sherpa-onnx). Free, no internet, no
 * API key, and fast: a line is made in a fraction of the time it takes to say
 * it, so the whole video can be voiced ahead of the playhead.
 *
 * The voice itself runs in a separate helper process ([NeuralTtsService]), so
 * a crash or an out-of-memory in the voice can never take the player down:
 * this class only sends it "say this with voice X at speed Y" and waits for
 * the audio file. If the helper dies twice, the neural voice switches itself
 * off for this dub.
 *
 * Actors: three real Hindi speakers (Priyamvada = woman, Rohan and Pratham =
 * men) for Hindi, and two English Kokoro speakers (Heart = woman, Michael =
 * man) for English. A character keeps the same actor for the whole video. Characters that
 * have to share a speaker get a different speaking speed (done inside the
 * voice, so it stays clean) and a different pitch ([fxFor], applied by the
 * audio editor), so two characters never sound the same.
 */
class SherpaTtsVoice(context: Context) : DubVoice {

    override val label = "Neural voice (offline)"

    private val app: Context = context.applicationContext
    private val dir = File(app.cacheDir, "dub").apply { mkdirs() }

    @Volatile private var broken = false
    @Volatile private var crashes = 0

    /** Why the last attempt failed (shown to the person so we can fix it). */
    @Volatile var lastError: String? = null
        private set

    /** True once the voice has produced audio in this run. */
    @Volatile var isLoaded: Boolean = false
        private set

    /** Language of the dub that is running (decides which actors are in the cast). */
    @Volatile private var voiceLang = "hi"

    // character -> actor, handed out in order of first appearance (guarded by itself)
    private val assigned = HashMap<String, Actor>()
    private var maleCount = 0
    private var femaleCount = 0

    // ───────────────────────── what went wrong (for the person) ─────────────────────────

    private val prefs by lazy { app.getSharedPreferences("neural_voice", Context.MODE_PRIVATE) }
    private val traceFile get() = File(dir, NeuralIpc.TRACE_FILE)

    /** Which way of starting the voice is tried now (see [NeuralTtsService]). */
    private var variant: Int
        get() = prefs.getInt("variant", 0).coerceIn(0, 2)
        set(v) { prefs.edit().putInt("variant", v).apply() }

    /** A short note about the last problem, or null when there was none. */
    fun report(): String? = prefs.getString("report", null)

    private fun lastStep(): String =
        try {
            traceFile.readLines().lastOrNull { it.isNotBlank() } ?: "before it started"
        } catch (t: Throwable) {
            "unknown"
        }

    /** Why Android says the helper process ended (Android 11 and newer). */
    private fun exitReason(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val e = am.getHistoricalProcessExitReasons(app.packageName, 0, 12)
                .firstOrNull { it.processName.endsWith(":neuraltts") } ?: return ""
            val name = when (e.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> "ran out of memory"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
                ApplicationExitInfo.REASON_EXIT_SELF -> "exited by itself"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
                else -> "reason " + e.reason
            }
            name + " " + (e.description ?: "") + " (memory " + (e.rss / 1024L) + " MB)"
        } catch (t: Throwable) {
            ""
        }
    }

    // ───────────────────────── helper process link ─────────────────────────

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val nextId = AtomicInteger(1)

    @Volatile private var service: Messenger? = null
    @Volatile private var connected = CompletableDeferred<Unit>()
    @Volatile private var bound = false

    private val replies = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == NeuralIpc.MSG_RESULT) {
                val d = msg.data
                val ok = d.getBoolean(NeuralIpc.K_OK)
                val text = if (ok) "" else (d.getString(NeuralIpc.K_ERR) ?: "failed").ifEmpty { "failed" }
                pending.remove(d.getInt(NeuralIpc.K_ID))?.complete(text)
            }
            true
        }
    )

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            connected.complete(Unit)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The helper process died (crash, or Android took its memory).
            service = null
            connected = CompletableDeferred()
            crashes++
            if (crashes >= 2) broken = true
            val step = lastStep()
            val tried = variant
            // Android writes the reason a moment after the process ends.
            Handler(Looper.getMainLooper()).postDelayed({
                val reason = exitReason()
                val note = "way " + tried + " · last step: " + step +
                    (if (reason.isNotEmpty()) " · " + reason else "")
                prefs.edit().putString("report", note).apply()
                Logs.log("Dub", "Neural voice helper died: $note")
                lastError = "the voice helper stopped ($note)"
                // This way of starting the voice did not survive: try the next
                // one on the following attempt (unless it has worked before).
                if (prefs.getInt("good_variant", -1) != tried) variant = (tried + 1) % 3
                val waiting = pending.values.toList()
                pending.clear()
                waiting.forEach { it.complete("the voice helper stopped") }
            }, 1_200L)
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
        }
    }

    private suspend fun ensureConnected(): Boolean {
        if (!bound) {
            try { traceFile.delete() } catch (t: Throwable) {}
            bound = try {
                app.bindService(Intent(app, NeuralTtsService::class.java), conn, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                false
            }
            if (!bound) {
                broken = true
                lastError = "could not start the voice helper"
                return false
            }
        }
        if (service != null) return true
        val ok = withTimeoutOrNull(20_000L) { connected.await() } != null
        if (!ok) lastError = "the voice helper did not start"
        return ok && service != null
    }

    /** Stops the helper (frees its memory). Safe to call any time. */
    fun unload() {
        if (!bound) return
        bound = false
        service = null
        connected = CompletableDeferred()
        try { app.unbindService(conn) } catch (t: Throwable) {}
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.complete("stopped") }
    }

    /** Forget which character has which actor (called when a dub starts). */
    fun resetCast() {
        synchronized(assigned) {
            assigned.clear()
            maleCount = 0
            femaleCount = 0
        }
        // a new dub gets a fresh chance
        crashes = 0
        broken = false
    }

    // ───────────────────────── DubVoice ─────────────────────────

    override suspend fun isAvailable(lang: String): Boolean =
        !broken && SherpaModels.isInstalledFor(app, lang)

    override suspend fun synthesize(text: String, lang: String): File? =
        synthesize(text, lang, CastDirector.NARRATOR)

    override fun fxFor(profile: VoiceProfile): VoiceProfile {
        val a = try { actorFor(profile) } catch (t: Throwable) { return profile }
        // speed is already inside the clip; only the pitch is left for the editor
        return VoiceProfile(profile.gender, a.semitones, 1f, profile.name)
    }

    override suspend fun synthesize(text: String, lang: String, profile: VoiceProfile): File? =
        withContext(Dispatchers.IO) {
            voiceLang = lang
            val actor = try {
                actorFor(profile)
            } catch (t: Throwable) {
                lastError = "no neural voice is installed"
                return@withContext null
            }
            val out = File(dir, GoogleTtsVoice.key("p1", lang, actor.voice.id + "|" + actor.speed + "|" + text) + ".wav")
            if (out.exists() && out.length() > 0) return@withContext out

            if (!ensureConnected()) return@withContext null
            val link = service ?: return@withContext null

            val id = nextId.getAndIncrement()
            val answer = CompletableDeferred<String>()
            pending[id] = answer
            val data = Bundle().apply {
                putInt(NeuralIpc.K_ID, id)
                if (actor.voice.kokoro) {
                    putString(NeuralIpc.K_DIR, SherpaModels.kokoroDir(app).path)
                    putString(NeuralIpc.K_DATA, SherpaModels.kokoroEspeakDir(app).path)
                    putString(NeuralIpc.K_KIND, "kokoro")
                } else {
                    putString(NeuralIpc.K_DIR, SherpaModels.voiceDir(app, actor.voice).path)
                    putString(NeuralIpc.K_DATA, SherpaModels.espeakDir(app).path)
                    putString(NeuralIpc.K_KIND, "piper")
                }
                putString(NeuralIpc.K_TEXT, text)
                putInt(NeuralIpc.K_SID, actor.voice.sid)
                putFloat(NeuralIpc.K_SPEED, actor.speed)
                putString(NeuralIpc.K_OUT, out.path)
                putInt(NeuralIpc.K_VARIANT, variant)
            }
            val msg = Message.obtain(null, NeuralIpc.MSG_SYNTH)
            msg.data = data
            msg.replyTo = replies
            try {
                link.send(msg)
            } catch (t: Throwable) {
                pending.remove(id)
                lastError = "could not talk to the voice helper"
                return@withContext null
            }

            // The first line of each voice also has to load it, so allow a while.
            val res = withTimeoutOrNull(90_000L) { answer.await() }
            pending.remove(id)
            when {
                res == null -> {
                    lastError = "the voice took too long"
                    null
                }
                res == "stopped" -> null
                res.isNotEmpty() -> {
                    lastError = res
                    Logs.log("Dub", "Neural voice error: $res")
                    null
                }
                out.exists() && out.length() > 0 -> {
                    if (!isLoaded) {
                        isLoaded = true
                        // It works: remember the way that worked, forget old problems.
                        prefs.edit().putInt("good_variant", variant).remove("report").apply()
                    }
                    out
                }
                else -> {
                    lastError = "the voice made no audio file"
                    null
                }
            }
        }

    override fun release() {
        unload()
    }

    // ───────────────────────── actors ─────────────────────────

    /** Which voice, how fast it speaks, and how much the editor shifts its pitch. */
    private class Actor(val voice: PiperVoice, val speed: Float, val semitones: Float)

    /** Picks the actor for a character (same character = same actor all video). */
    private fun actorFor(p: VoiceProfile): Actor = synchronized(assigned) {
        assigned.getOrPut(voiceLang + "|" + p.name + "|" + p.key) {
            val ready = SherpaModels.readyVoicesFor(app, voiceLang)
            when {
                ready.isEmpty() -> error("no voice installed")
                p.name == "Narrator" -> pick(true, 0, ready)       // main / unnamed voice
                p.name == "Voice B" -> pick(false, 0, ready)       // 2nd unnamed voice (man)
                p.name == "Voice C" -> pick(true, 1, ready)        // new unnamed woman
                p.name == "Voice D" -> pick(false, 1, ready)       // new unnamed man
                p.gender == Gender.FEMALE -> pick(true, femaleCount++, ready)
                p.gender == Gender.MALE -> pick(false, maleCount++, ready)
                p.gender == Gender.CHILD -> {
                    val w = ready.firstOrNull { it.gender == Gender.FEMALE } ?: ready.first()
                    Actor(w, 1.08f, 4.5f)
                }
                else -> pick(true, 0, ready)
            }
        }
    }

    /**
     * The k-th woman / man of the video. Real speakers are used first
     * (round robin); once they are all taken the next character shares a
     * speaker but speaks at a different speed and pitch.
     */
    private fun pick(female: Boolean, k: Int, ready: List<PiperVoice>): Actor {
        val own = ready.filter { (it.gender == Gender.FEMALE) == female }
        val pool = if (own.isNotEmpty()) own else ready
        val look = (if (female) FEMALE_LOOK else MALE_LOOK)[(k / pool.size) % FEMALE_LOOK.size]
        // no voice of that sex is installed: borrow the other one and move its pitch
        val borrow = if (own.isEmpty()) (if (female) 4.5f else -4.5f) else 0f
        return Actor(pool[k % pool.size], look.second, look.first + borrow)
    }

    companion object {
        /** (pitch in semitones, speed) for the 1st, 2nd, 3rd ... character on one speaker. */
        private val FEMALE_LOOK = listOf(0f to 1.00f, 2.0f to 1.06f, -1.5f to 0.95f, 3.5f to 1.10f)
        private val MALE_LOOK = listOf(0f to 1.00f, -2.0f to 0.95f, 1.5f to 1.06f, -3.5f to 0.92f)
    }
}
