package com.hikari.app.dub

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline neural voices (Kokoro through sherpa-onnx). Free, no internet, no
 * API key. Different characters get different actors:
 *
 *   Hindi has 4 actors in the model: hf_alpha (31), hf_beta (32) = women,
 *   hm_omega (33), hm_psi (34) = men. A character keeps the same actor for
 *   the whole video; the pitch / speed edit in [DubFx] then makes characters
 *   that share an actor sound different from each other.
 *
 * Only Hindi for now. More languages = one more entry in [actors].
 */
class SherpaTtsVoice(private val context: Context) : DubVoice {

    override val label = "Neural voice (offline)"

    private val dir = File(context.cacheDir, "dub").apply { mkdirs() }
    private val lock = Any()

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var broken = false

    // character -> actor, handed out in order of first appearance
    private val assigned = ConcurrentHashMap<String, Int>()
    private var maleCount = 0
    private var femaleCount = 0

    /** Forget which character has which actor (called when a dub starts). */
    fun resetCast() {
        synchronized(assigned) {
            assigned.clear()
            maleCount = 0
            femaleCount = 0
        }
    }

    override suspend fun isAvailable(lang: String): Boolean =
        !broken && supports(lang) && SherpaModels.isInstalled(context)

    override suspend fun synthesize(text: String, lang: String): File? =
        synthesize(text, lang, CastDirector.NARRATOR)

    override suspend fun synthesize(text: String, lang: String, profile: VoiceProfile): File? =
        withContext(Dispatchers.IO) {
            val sid = actorFor(profile)
            val out = File(dir, GoogleTtsVoice.key("n1", lang, "$sid|$text") + ".wav")
            if (out.exists() && out.length() > 0) return@withContext out

            val engine = engine() ?: return@withContext null
            val audio = synchronized(lock) {
                try {
                    engine.generate(text = text, sid = sid, speed = 1.0f)
                } catch (t: Throwable) {
                    null
                }
            } ?: return@withContext null
            val samples = audio.samples
            if (samples.isEmpty()) return@withContext null

            val tmp = File(dir, out.name + ".part")
            writeWav(tmp, samples, audio.sampleRate)
            if (out.exists()) out.delete()
            if (tmp.renameTo(out)) out else null
        }

    override fun release() {
        synchronized(lock) {
            try { tts?.release() } catch (t: Throwable) {}
            tts = null
        }
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

    // ───────────────────────── engine ─────────────────────────

    private fun engine(): OfflineTts? {
        tts?.let { return it }
        synchronized(lock) {
            tts?.let { return it }
            if (broken) return null
            return try {
                val d = SherpaModels.dir(context)
                val lexicons = listOf("lexicon-us-en.txt", "lexicon-zh.txt")
                    .map { File(d, it) }
                    .filter { it.exists() }
                    .joinToString(",") { it.path }
                val kokoro = OfflineTtsKokoroModelConfig(
                    model = File(d, "model.onnx").path,
                    voices = File(d, "voices.bin").path,
                    tokens = File(d, "tokens.txt").path,
                    dataDir = File(d, "espeak-ng-data").path,
                    lexicon = lexicons,
                    lang = "hi",
                )
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val cfg = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = kokoro,
                        numThreads = threads,
                        debug = false,
                        provider = "cpu",
                    ),
                )
                OfflineTts(config = cfg).also { tts = it }
            } catch (t: Throwable) {
                broken = true
                null
            }
        }
    }

    /** 16-bit mono WAV. */
    private fun writeWav(file: File, x: FloatArray, rate: Int) {
        val data = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) {
            val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
            data.putShort(s.toShort())
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val bytes = x.size * 2
        header.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(bytes)
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            f.write(header.array())
            f.write(data.array())
        }
    }

    companion object {
        /** Kokoro multi-lang v1.0 speaker ids: hf_alpha, hf_beta, hm_omega, hm_psi. */
        private val HI_WOMAN = intArrayOf(32, 31)
        private val HI_MAN = intArrayOf(34, 33)
    }
}
