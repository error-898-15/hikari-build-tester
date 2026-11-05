package com.hikari.app.dub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
 * Offline neural voices (Kokoro through sherpa-onnx). Free, no internet, no
 * API key. Different characters get different actors.
 *
 * The voice itself runs in a separate helper process ([NeuralTtsService]), so
 * a crash or an out-of-memory in the voice can never take the player down:
 * this class only sends it "say this with actor N" and waits for the audio
 * file. If the helper dies twice, the neural voice switches itself off for
 * this dub and the normal voice takes over.
 *
 * Hindi has 4 actors in the model: hf_alpha (31), hf_beta (32) = women,
 * hm_omega (33), hm_psi (34) = men. A character keeps the same actor for the
 * whole video; the pitch / speed edit in [DubFx] then makes characters that
 * share an actor sound different from each other.
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

    // character -> actor, handed out in order of first appearance
    private val assigned = ConcurrentHashMap<String, Int>()
    private var maleCount = 0
    private var femaleCount = 0

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
            val why = "the voice helper stopped (the phone probably ran out of memory)"
            lastError = why
            Logs.log("Dub", "Neural voice helper process died ($crashes)")
            val waiting = pending.values.toList()
            pending.clear()
            waiting.forEach { it.complete(why) }
            if (crashes >= 2) broken = true
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
        }
    }

    private suspend fun ensureConnected(): Boolean {
        if (!bound) {
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
        !broken && supports(lang) && SherpaModels.isInstalled(app)

    override suspend fun synthesize(text: String, lang: String): File? =
        synthesize(text, lang, CastDirector.NARRATOR)

    override suspend fun synthesize(text: String, lang: String, profile: VoiceProfile): File? =
        withContext(Dispatchers.IO) {
            val sid = actorFor(profile)
            val out = File(dir, GoogleTtsVoice.key("n2", lang, "$sid|$text") + ".wav")
            if (out.exists() && out.length() > 0) return@withContext out

            if (!ensureConnected()) return@withContext null
            val link = service ?: return@withContext null

            val id = nextId.getAndIncrement()
            val answer = CompletableDeferred<String>()
            pending[id] = answer
            val data = Bundle().apply {
                putInt(NeuralIpc.K_ID, id)
                putString(NeuralIpc.K_DIR, SherpaModels.dir(app).path)
                putString(NeuralIpc.K_TEXT, text)
                putInt(NeuralIpc.K_SID, sid)
                putString(NeuralIpc.K_OUT, out.path)
                putString(NeuralIpc.K_LANG, lang.substringBefore('-').lowercase())
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

            // The first line also has to load the voice, so allow a long time.
            val res = withTimeoutOrNull(120_000L) { answer.await() }
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
                    isLoaded = true
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

    private fun supports(lang: String): Boolean =
        lang.substringBefore('-').equals("hi", ignoreCase = true)

    /** Picks the actor (speaker id inside the model) for a character. */
    private fun actorFor(p: VoiceProfile): Int = synchronized(assigned) {
        assigned.getOrPut(p.name + "|" + p.key) {
            when {
                p.name == "Narrator" -> HI_WOMAN[1]        // main / unnamed voice
                p.name == "Voice B" -> HI_MAN[1]           // 2nd unnamed voice
                p.gender == Gender.FEMALE -> HI_WOMAN[(femaleCount++) % HI_WOMAN.size]
                p.gender == Gender.MALE -> HI_MAN[(maleCount++) % HI_MAN.size]
                p.gender == Gender.CHILD -> HI_WOMAN[0]
                else -> HI_WOMAN[1]
            }
        }
    }

    companion object {
        /** Kokoro multi-lang v1.0 speaker ids: hf_alpha, hf_beta, hm_omega, hm_psi. */
        private val HI_WOMAN = intArrayOf(32, 31)
        private val HI_MAN = intArrayOf(34, 33)
    }
}
