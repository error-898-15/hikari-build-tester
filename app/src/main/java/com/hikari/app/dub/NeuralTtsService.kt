package com.hikari.app.dub

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The few words the player and the voice helper say to each other. */
internal object NeuralIpc {
    const val MSG_SYNTH = 1
    const val MSG_RESULT = 2
    const val K_ID = "id"
    const val K_DIR = "dir"
    const val K_TEXT = "text"
    const val K_SID = "sid"
    const val K_OUT = "out"
    const val K_LANG = "lang"
    const val K_VARIANT = "variant"
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
    private var tts: OfflineTts? = null
    private var loadedFor: String? = null

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
                try { tts?.release() } catch (e: Throwable) {}
                tts = null
                t.quitSafely()
            }
        }
        super.onDestroy()
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
        val dir = File(b.getString(NeuralIpc.K_DIR) ?: error("no model folder"))
        val text = b.getString(NeuralIpc.K_TEXT) ?: error("no text")
        val sid = b.getInt(NeuralIpc.K_SID)
        val out = File(b.getString(NeuralIpc.K_OUT) ?: error("no output file"))
        val lang = b.getString(NeuralIpc.K_LANG) ?: "hi"
        val variant = b.getInt(NeuralIpc.K_VARIANT)

        if (text.none { it.isLetterOrDigit() }) error("nothing to say in this line")
        val engine = engineFor(dir, lang, variant)
        trace("speaking: actor " + sid + ", " + text.length + " characters")
        val audio = engine.generate(text = text, sid = sid, speed = 1.0f)
        val samples = audio.samples
        if (samples.isEmpty()) error("the voice returned no sound")
        trace("done: " + samples.size + " samples")

        val tmp = File(out.path + ".part")
        writeWav(tmp, samples, audio.sampleRate)
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) error("could not save the audio")
    }

    /**
     * [variant] is a safety net: if one way of starting the voice makes the
     * helper die, the player tries the next way on the following attempt.
     *   0 = Hindi through espeak, 1 = default language setting, 2 = Hindi on one thread
     */
    private fun engineFor(d: File, lang: String, variant: Int): OfflineTts {
        val key = d.path + "|" + lang + "|" + variant
        tts?.let { if (loadedFor == key) return it }
        try { tts?.release() } catch (e: Throwable) {}
        tts = null

        val model = File(d, "model.int8.onnx").takeIf { it.length() > 0 } ?: File(d, "model.onnx")
        val lexicons = listOf("lexicon-us-en.txt", "lexicon-zh.txt")
            .map { File(d, it) }
            .filter { it.exists() }
            .joinToString(",") { it.path }
        val kokoro = OfflineTtsKokoroModelConfig(
            model = model.path,
            voices = File(d, "voices.bin").path,
            tokens = File(d, "tokens.txt").path,
            dataDir = File(d, "espeak-ng-data").path,
            lexicon = lexicons,
            lang = if (variant == 1) "" else lang,
        )
        // The multi-language Kokoro pack splits Chinese words with a dictionary
        // (the "dict" folder of the pack). sherpa-onnx opens it while the voice
        // starts, and if it is not told where the folder is it tries to open
        // "/jieba.dict.utf8", which does not exist: that is a hard native abort
        // (no Java error), the whole helper process dies, and the player only
        // sees "the voice helper stopped". The official sherpa-onnx Android app
        // passes this folder, so it is set here too.
        //
        // It is set through reflection on purpose: Tts.kt is downloaded by the
        // build workflow (it is not kept in this repository), so the build must
        // compile with whichever version it fetched. If that version has no
        // such setting, nothing happens and the build is not affected.
        val dictDir = File(d, "dict")
        val dictSet = if (dictDir.isDirectory) setStringField(kokoro, "DictDir", dictDir.path) else false
        trace("dict folder: " + (if (!dictDir.isDirectory) "not in the voice pack" else if (dictSet) "set" else "this sherpa-onnx has no such setting"))

        // Fewer threads = less memory (each one holds its own working buffers),
        // and a phone that is also playing a video has little to spare.
        val threads = if (variant == 2) 1 else (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 2)
        trace("loading voice: " + model.name + " " + (model.length() / 1_000_000L) + " MB, way " + variant + ", threads " + threads)
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = kokoro,
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
        )
        val created = OfflineTts(config = cfg)
        trace("voice loaded, actors: " + created.numSpeakers())
        tts = created
        loadedFor = key
        return created
    }

    /** Calls `set<Name>(value)` on [target] when that setting exists. Never throws. */
    private fun setStringField(target: Any, name: String, value: String): Boolean =
        try {
            target.javaClass.getMethod("set$name", String::class.java).invoke(target, value)
            true
        } catch (t: Throwable) {
            false
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
