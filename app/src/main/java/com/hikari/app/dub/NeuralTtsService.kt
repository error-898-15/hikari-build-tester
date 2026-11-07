package com.hikari.app.dub

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The few words the player and the voice helper say to each other. */
internal object NeuralIpc {
    const val MSG_SYNTH = 1
    const val MSG_RESULT = 2
    const val K_ID = "id"
    /** Folder of the voice to use (model.onnx + tokens.txt). */
    const val K_DIR = "dir"
    /** Folder with the espeak-ng data (shared by all voices). */
    const val K_DATA = "data"
    const val K_TEXT = "text"
    const val K_SID = "sid"
    const val K_SPEED = "speed"
    const val K_OUT = "out"
    const val K_VARIANT = "variant"
    /**
     * "kokoro" = the English Kokoro pack (several speakers in one model);
     * "pocket" = the Pocket TTS cloning model (the voice is copied from [K_REF]);
     * anything else = a Piper voice.
     */
    const val K_KIND = "kind"
    /** Pocket only: the recording to copy the voice from (an asset path, or an absolute file path). */
    const val K_REF = "ref"
    /** Pocket only: how many refinement steps the voice generator runs (fewer = faster). */
    const val K_STEPS = "steps"
    /** Where the helper writes what it is doing, so that if it dies we know where. */
    const val TRACE_FILE = "helper_trace.txt"
    const val K_OK = "ok"
    const val K_ERR = "err"
}

/**
 * The neural voice lives in its OWN process (see the manifest: process
 * ":neuraltts"). The voice model is big and uses native code; if it ever runs
 * out of memory or crashes, only this helper process dies - the player and the
 * video keep playing, and the dub simply falls back to the normal voice.
 */
class NeuralTtsService : Service() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var messenger: Messenger? = null
    /** Voices that are loaded right now, least recently used first. */
    private val engines = LinkedHashMap<String, OfflineTts>(4, 0.75f, true)

    /** A reference recording, decoded once (voice cloning). */
    private class Reference(val samples: FloatArray, val rate: Int)
    private val references = HashMap<String, Reference>()

    override fun onCreate() {
        super.onCreate()
        trace("service started")
        val t = HandlerThread("neural-tts", Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        thread = t
        val h = Handler(t.looper) { msg ->
            handle(msg)
            true
        }
        handler = h
        messenger = Messenger(h)
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

    override fun onDestroy() {
        val t = thread
        val h = handler
        if (t != null && h != null) {
            h.post {
                trimEngines(0)
                t.quitSafely()
            }
        }
        super.onDestroy()
    }

    /** The phone is short of memory: keep only the voice that spoke last. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            handler?.post { trimEngines(1) }
        }
    }

    private fun trimEngines(keep: Int) {
        while (engines.size > keep) {
            val key = engines.keys.first()
            val e = engines.remove(key)
            try { e?.release() } catch (t: Throwable) {}
        }
    }

    private fun handle(msg: Message) {
        if (msg.what != NeuralIpc.MSG_SYNTH) return
        val data = msg.data
        val id = data.getInt(NeuralIpc.K_ID)
        val answer = Bundle()
        answer.putInt(NeuralIpc.K_ID, id)
        try {
            synth(data)
            answer.putBoolean(NeuralIpc.K_OK, true)
        } catch (t: Throwable) {
            trace("error: " + t.javaClass.simpleName + " " + (t.message ?: ""))
            answer.putBoolean(NeuralIpc.K_OK, false)
            answer.putString(NeuralIpc.K_ERR, t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
        val reply = Message.obtain(null, NeuralIpc.MSG_RESULT)
        reply.data = answer
        try { msg.replyTo?.send(reply) } catch (e: Throwable) {}
    }

    private fun synth(b: Bundle) {
        val dir = File(b.getString(NeuralIpc.K_DIR) ?: error("no voice folder"))
        // the espeak data folder (Piper / Kokoro only; the cloning model does not use it)
        val data = File(b.getString(NeuralIpc.K_DATA).orEmpty())
        val text = b.getString(NeuralIpc.K_TEXT) ?: error("no text")
        val sid = b.getInt(NeuralIpc.K_SID)
        val speed = b.getFloat(NeuralIpc.K_SPEED, 1.0f).coerceIn(0.6f, 1.6f)
        val out = File(b.getString(NeuralIpc.K_OUT) ?: error("no output file"))
        val variant = b.getInt(NeuralIpc.K_VARIANT)
        val kind = b.getString(NeuralIpc.K_KIND) ?: "piper"
        val pocket = kind == "pocket"

        val engine = engineFor(dir, data, variant, kind)
        val started = System.nanoTime()
        trace("speaking: " + dir.name + ", " + text.length + " characters")
        val audio = if (pocket) {
            val refPath = b.getString(NeuralIpc.K_REF) ?: error("no reference voice")
            val ref = loadReference(refPath)
            trace("cloning the voice of: " + refPath + " (" + (ref.samples.size * 1000L / ref.rate.coerceAtLeast(1)) + " ms)")
            val cfg = GenerationConfig(
                speed = 1.0f,
                sid = 0,
                referenceAudio = ref.samples,
                referenceSampleRate = ref.rate,
                numSteps = b.getInt(NeuralIpc.K_STEPS, 2).coerceIn(1, 8),
            )
            engine.generateWithConfig(text, cfg)
        } else {
            engine.generate(text = text, sid = sid, speed = speed)
        }
        val samples = audio.samples
        if (samples.isEmpty()) error("the voice returned no sound")
        val tookMs = (System.nanoTime() - started) / 1_000_000L
        val audioMs = samples.size * 1000L / audio.sampleRate.coerceAtLeast(1)
        trace("done: " + audioMs + " ms of sound made in " + tookMs + " ms")

        val tmp = File(out.path + ".part")
        writeWav(tmp, samples, audio.sampleRate)
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) error("could not save the audio")
    }

    /**
     * The recording a cloned voice is copied from: a WAV (16-bit) inside the APK's assets
     * ("dub/voices/myira.wav") or, when the path starts with "/", a file on the phone.
     */
    private fun loadReference(path: String): Reference {
        references[path]?.let { return it }
        val bytes = if (path.startsWith("/")) File(path).readBytes()
        else assets.open(path).use { it.readBytes() }
        val ref = parseWav(bytes)
        references[path] = ref
        return ref
    }

    private fun parseWav(b: ByteArray): Reference {
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(b, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) error("the reference voice is not a WAV file")
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1
        var rate = 0
        var bits = 16
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt " && body + 16 <= b.size) {
                channels = bb.getShort(body + 2).toInt() and 0xFFFF
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt() and 0xFFFF
            } else if (id == "data") {
                dataStart = body
                dataLen = if (size < 0 || body + size > b.size) b.size - body else size
                break
            }
            if (size < 0) break
            pos = body + size + (size and 1)
        }
        if (dataStart < 0 || rate <= 0 || channels <= 0 || bits != 16) {
            error("the reference voice must be a 16-bit WAV file")
        }
        val frames = dataLen / (2 * channels)
        if (frames <= 0) error("the reference voice is empty")
        val out = FloatArray(frames)
        var p = dataStart
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += bb.getShort(p).toFloat() / 32768f
                p += 2
            }
            out[i] = acc / channels
        }
        return Reference(out, rate)
    }

    /**
     * [variant] is a safety net: if one way of starting the voice makes the
     * helper die, the player tries the next way on the following attempt.
     *   0 = 3 voices kept in memory, up to 3 threads
     *   1 = 2 voices kept in memory, 2 threads
     *   2 = 1 voice kept in memory, 1 thread
     *
     * [kind] is "piper", "kokoro" or "pocket" (see [NeuralIpc.K_KIND]). Both cloned women
     * share ONE loaded cloning model (the voice comes from the recording, not the model).
     */
    private fun engineFor(dir: File, data: File, variant: Int, kind: String): OfflineTts {
        val key = dir.path + "|" + variant
        engines[key]?.let { return it }

        // make room (a loaded voice costs about 100-150 MB)
        val cap = when (variant) { 0 -> 3; 1 -> 2; else -> 1 }
        trimEngines(cap - 1)

        val kokoro = kind == "kokoro"
        val pocket = kind == "pocket"
        val model = when {
            pocket -> File(dir, "lm_main.int8.onnx")
            kokoro -> {
                val int8 = File(dir, "model.int8.onnx")
                if (int8.length() > 0) int8 else File(dir, "model.onnx")
            }
            else -> File(dir, "model.onnx")
        }
        val threads = when (variant) {
            0 -> (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 3)
            1 -> 2
            else -> 1
        }
        trace("loading voice: " + dir.name + " " + (model.length() / 1_000_000L) + " MB, way " + variant + ", threads " + threads)
        val modelCfg = when {
            pocket -> OfflineTtsModelConfig(
                pocket = OfflineTtsPocketModelConfig(
                    lmFlow = File(dir, "lm_flow.int8.onnx").path,
                    lmMain = File(dir, "lm_main.int8.onnx").path,
                    encoder = File(dir, "encoder.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path,
                    textConditioner = File(dir, "text_conditioner.onnx").path,
                    vocabJson = File(dir, "vocab.json").path,
                    tokenScoresJson = File(dir, "token_scores.json").path,
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            kokoro -> {
                // English lexicon first, Chinese one after it (the pack is multi-language).
                val lexicon = listOf("lexicon-us-en.txt", "lexicon-zh.txt")
                    .map { File(dir, it) }
                    .filter { it.length() > 0 }
                    .joinToString(",") { it.path }
                val dict = File(dir, "dict")
                OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = model.path,
                        voices = File(dir, "voices.bin").path,
                        tokens = File(dir, "tokens.txt").path,
                        dataDir = data.path,
                        lexicon = lexicon,
                        dictDir = if (dict.isDirectory) dict.path else "",
                    ),
                    numThreads = threads,
                    debug = false,
                    provider = "cpu",
                )
            }
            else -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = data.path,
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
        }
        val cfg = OfflineTtsConfig(model = modelCfg)
        val created = OfflineTts(config = cfg)
        trace("voice loaded: " + dir.name)
        engines[key] = created
        return created
    }

    private fun trace(line: String) {
        try {
            val f = File(File(cacheDir, "dub").apply { mkdirs() }, NeuralIpc.TRACE_FILE)
            f.appendText(line + "\n")
        } catch (t: Throwable) {}
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
}
