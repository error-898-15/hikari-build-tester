package com.hikari.app.dub

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** One offline Hindi voice (a Piper model run by sherpa-onnx). */
data class PiperVoice(val id: String, val gender: Gender, val label: String)

/**
 * The offline neural voices (Piper, run through sherpa-onnx): three real,
 * different Hindi speakers. Piper is MUCH faster than the Kokoro pack that
 * used to be here (a line is made in a fraction of the time it takes to say
 * it), which is what lets a whole video be voiced in a minute or two.
 *
 * Nothing is inside the APK. The first time, [install] downloads the three
 * voices (about 60 MB each) from the official sherpa-onnx releases page on
 * GitHub, once, and unpacks them into the app's private folder:
 *
 *   dub_models/piper-hi/espeak-ng-data/          (shared by all voices)
 *   dub_models/piper-hi/voices/<id>/model.onnx + tokens.txt
 *
 * The first two voices are required; the third one is a bonus (if it cannot
 * be downloaded the dub simply has one actor fewer).
 */
object SherpaModels {

    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
    private const val ESPEAK_URL = BASE + "espeak-ng-data.tar.bz2"

    /** Order matters: 1 woman, 1 man (required), then a second man (bonus). */
    val VOICES: List<PiperVoice> = listOf(
        PiperVoice("vits-piper-hi_IN-priyamvada-medium", Gender.FEMALE, "Priyamvada"),
        PiperVoice("vits-piper-hi_IN-rohan-medium", Gender.MALE, "Rohan"),
        PiperVoice("vits-piper-hi_IN-pratham-medium", Gender.MALE, "Pratham"),
    )
    private const val REQUIRED = 2

    private fun root(c: Context): File = File(File(c.filesDir, "dub_models"), "piper-hi")

    /** Folder with the espeak-ng data (shared by every voice). */
    fun espeakDir(c: Context): File = File(root(c), "espeak-ng-data")

    fun voiceDir(c: Context, v: PiperVoice): File = File(File(root(c), "voices"), v.id)

    private fun espeakReady(c: Context): Boolean =
        File(espeakDir(c), ".ok").exists() && File(espeakDir(c), "phontab").exists()

    fun isVoiceReady(c: Context, v: PiperVoice): Boolean {
        val d = voiceDir(c, v)
        return File(d, ".ok").exists() &&
            File(d, "model.onnx").length() > 0 &&
            File(d, "tokens.txt").length() > 0
    }

    /** The voices that are on the phone and usable right now. */
    fun readyVoices(c: Context): List<PiperVoice> = VOICES.filter { isVoiceReady(c, it) }

    fun isInstalled(c: Context): Boolean = espeakReady(c) && readyVoices(c).size >= REQUIRED

    fun delete(c: Context) {
        root(c).deleteRecursively()
    }

    /**
     * Downloads (resuming a half-finished download) and unpacks the voices.
     * [onProgress] gets (percent 0..100 of the WHOLE job, unpacking?) from a
     * background thread. Returns null on success, otherwise a short message.
     */
    suspend fun install(c: Context, onProgress: (Int, Boolean) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val base = root(c).apply { mkdirs() }
            // The first neural voice (Kokoro, 130-330 MB) is not used any more: free the space.
            runCatching {
                val models = File(c.filesDir, "dub_models")
                File(models, "kokoro-int8-multi-lang-v1_0").deleteRecursively()
                File(models, "kokoro-int8-multi-lang-v1_0.tmp").deleteRecursively()
                File(models, "kokoro-multi-lang-v1_0").deleteRecursively()
                c.cacheDir.listFiles()?.filter { it.name.startsWith("kokoro") }?.forEach { it.delete() }
            }
            if (base.usableSpace < 450_000_000L && !isInstalled(c)) {
                return@withContext "Not enough free space (about 300 MB is needed)."
            }
            val todo = VOICES.filter { !isVoiceReady(c, it) }
            val steps = todo.size + (if (espeakReady(c)) 0 else 1)
            var step = 0
            fun report(pct: Int, unpacking: Boolean) {
                val total = steps.coerceAtLeast(1)
                onProgress(((step * 100 + pct) / total).coerceIn(0, 100), unpacking)
            }

            try {
                for (v in todo) {
                    val required = VOICES.indexOf(v) < REQUIRED
                    val err = installVoice(c, v) { p, u -> report(p, u) }
                    if (err != null) {
                        if (required) return@withContext err
                        // a bonus voice that cannot be fetched is skipped
                    }
                    step++
                }
                if (!espeakReady(c)) {
                    val err = installEspeak(c) { p, u -> report(p, u) }
                    if (err != null) return@withContext err
                    step++
                }
                if (!isInstalled(c)) "The voice pack was incomplete. Tap again to retry." else null
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                "Voice download failed: " + (t.message ?: t.javaClass.simpleName)
            }
        }

    // ───────────────────────── one voice ─────────────────────────

    private fun CoroutineScope.installVoice(
        c: Context,
        v: PiperVoice,
        onProgress: (Int, Boolean) -> Unit,
    ): String? {
        val part = File(c.cacheDir, v.id + ".tar.bz2.part")
        val marker = File(c.cacheDir, v.id + ".tar.bz2.done")
        val staging = File(File(root(c), "voices"), v.id + ".tmp")
        val espeakStaging = File(root(c), "espeak-ng-data.tmp")
        try {
            val whole = part.exists() && part.length() > 0 && marker.exists()
            if (!whole) {
                val err = download(BASE + v.id + ".tar.bz2", part, marker, onProgress)
                if (err != null) return err
            }
            val needEspeak = !espeakReady(c)
            staging.deleteRecursively()
            staging.mkdirs()
            if (needEspeak) {
                espeakStaging.deleteRecursively()
                espeakStaging.mkdirs()
            }
            var haveEspeakFiles = false
            unpack(part, onProgress) { parts ->
                val i = parts.indexOf("espeak-ng-data")
                when {
                    i >= 0 -> {
                        if (!needEspeak || i == parts.size - 1) null
                        else {
                            haveEspeakFiles = true
                            File(espeakStaging, parts.drop(i + 1).joinToString("/"))
                        }
                    }
                    parts.last().endsWith(".onnx") -> File(staging, "model.onnx")
                    parts.last() == "tokens.txt" -> File(staging, "tokens.txt")
                    else -> null
                }
            }
            if (File(staging, "model.onnx").length() <= 0 || File(staging, "tokens.txt").length() <= 0) {
                staging.deleteRecursively()
                espeakStaging.deleteRecursively()
                part.delete(); marker.delete()
                return "The voice ${v.label} was incomplete. Tap again to retry."
            }
            if (needEspeak && haveEspeakFiles && File(espeakStaging, "phontab").exists()) {
                File(espeakStaging, ".ok").writeText("1")
                val finalEspeak = espeakDir(c)
                finalEspeak.deleteRecursively()
                if (!espeakStaging.renameTo(finalEspeak)) espeakStaging.deleteRecursively()
            } else {
                espeakStaging.deleteRecursively()
            }
            File(staging, ".ok").writeText("1")
            val finalDir = voiceDir(c, v)
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.deleteRecursively()
                return "Could not save the voice ${v.label}."
            }
            part.delete(); marker.delete()
            return null
        } catch (ce: CancellationException) {
            staging.deleteRecursively()
            espeakStaging.deleteRecursively()
            throw ce
        } catch (t: Throwable) {
            staging.deleteRecursively()
            espeakStaging.deleteRecursively()
            val noSpace = t is OutOfMemoryError ||
                t.message.orEmpty().contains("space", ignoreCase = true) ||
                root(c).usableSpace < 100_000_000L
            if (!noSpace) { part.delete(); marker.delete() }
            return "Voice download failed: " + (t.message ?: t.javaClass.simpleName)
        }
    }

    /** Only needed when the voice packs did not carry the espeak data themselves. */
    private fun CoroutineScope.installEspeak(c: Context, onProgress: (Int, Boolean) -> Unit): String? {
        val part = File(c.cacheDir, "espeak-ng-data.tar.bz2.part")
        val marker = File(c.cacheDir, "espeak-ng-data.tar.bz2.done")
        val staging = File(root(c), "espeak-ng-data.tmp")
        try {
            if (!(part.exists() && part.length() > 0 && marker.exists())) {
                val err = download(ESPEAK_URL, part, marker, onProgress)
                if (err != null) return err
            }
            staging.deleteRecursively()
            staging.mkdirs()
            unpack(part, onProgress) { parts ->
                val i = parts.indexOf("espeak-ng-data")
                val rel = if (i >= 0) parts.drop(i + 1) else parts
                if (rel.isEmpty()) null else File(staging, rel.joinToString("/"))
            }
            if (!File(staging, "phontab").exists()) {
                staging.deleteRecursively(); part.delete(); marker.delete()
                return "The language data was incomplete. Tap again to retry."
            }
            File(staging, ".ok").writeText("1")
            val finalDir = espeakDir(c)
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.deleteRecursively()
                return "Could not save the language data."
            }
            part.delete(); marker.delete()
            return null
        } catch (ce: CancellationException) {
            staging.deleteRecursively()
            throw ce
        } catch (t: Throwable) {
            staging.deleteRecursively()
            part.delete(); marker.delete()
            return "Voice download failed: " + (t.message ?: t.javaClass.simpleName)
        }
    }

    // ───────────────────────── download / unpack ─────────────────────────

    /**
     * Downloads [url] into [part] (resuming when a part is there).
     * Returns null when the whole file is on the phone (and writes [marker]),
     * otherwise a short message for the person.
     */
    private fun CoroutineScope.download(
        url: String,
        part: File,
        marker: File,
        onProgress: (Int, Boolean) -> Unit,
    ): String? {
        marker.delete()
        val have = if (part.exists()) part.length() else 0L
        var conn = openFollowing(url, have)
        var code = conn.responseCode
        var resumeFrom = have

        if (code == 416) {
            // "Range not satisfiable": the saved file is already whole (or damaged).
            val range = conn.getHeaderField("Content-Range")
            conn.disconnect()
            val serverTotal = range?.substringAfter('/', "")?.trim()?.toLongOrNull()
            if (serverTotal == null || serverTotal == have) {
                marker.writeText("1")
                return null
            }
            part.delete()
            resumeFrom = 0L
            conn = openFollowing(url, 0L)
            code = conn.responseCode
        }

        if (code != 200 && code != 206) {
            conn.disconnect()
            return "Download failed (server answered $code)."
        }
        val append = code == 206 && resumeFrom > 0
        val len = conn.contentLengthLong
        val total = if (len > 0) len + (if (append) resumeFrom else 0L) else -1L
        var received = if (append) resumeFrom else 0L
        var lastPct = -1
        conn.inputStream.use { input ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    received += n
                    if (total > 0) {
                        val p = (received * 100 / total).toInt().coerceIn(0, 100)
                        if (p != lastPct) {
                            lastPct = p
                            onProgress(p, false)
                        }
                    }
                }
            }
        }
        conn.disconnect()
        if (total > 0 && part.length() < total) {
            return "Download was cut off. Tap again to continue."
        }
        marker.writeText("1")
        return null
    }

    /**
     * Unpacks a .tar.bz2. [target] gets the entry's path split into parts
     * (e.g. ["vits-piper-x", "tokens.txt"]) and returns where to write it, or
     * null to skip the entry. Nothing is ever written outside the folder that
     * [target]'s files live in.
     */
    private fun CoroutineScope.unpack(
        part: File,
        onProgress: (Int, Boolean) -> Unit,
        target: (List<String>) -> File?,
    ) {
        val size = part.length().coerceAtLeast(1L)
        var lastUnpack = -1
        var lastTick = 0L
        val buf = ByteArray(128 * 1024)
        FileInputStream(part).use { fis ->
            val channel = fis.channel
            fun report() {
                val p = (channel.position() * 100 / size).toInt().coerceIn(0, 100)
                if (p != lastUnpack) {
                    lastUnpack = p
                    onProgress(p, true)
                }
            }
            BZip2CompressorInputStream(BufferedInputStream(fis, 256 * 1024)).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        ensureActive()
                        val e = tar.nextEntry ?: break
                        if (e.isDirectory) continue
                        val parts = e.name.replace('\\', '/').split('/')
                            .filter { it.isNotEmpty() && it != "." }
                        if (parts.isEmpty() || parts.any { it == ".." }) continue
                        val dest = target(parts) ?: continue
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out ->
                            while (true) {
                                ensureActive()
                                val n = tar.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                val now = System.nanoTime()
                                if (now - lastTick > 300_000_000L) {
                                    lastTick = now
                                    report()
                                }
                            }
                        }
                        report()
                    }
                }
            }
        }
        onProgress(100, true)
    }

    /** Opens [url], following redirects by hand (GitHub hands out a signed link). */
    private fun openFollowing(url: String, resumeFrom: Long): HttpURLConnection {
        var u = url
        var hops = 0
        while (true) {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "Hikari")
            if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
            val code = conn.responseCode
            if (code in 300..399 && hops < 6) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) return conn
                u = URL(URL(u), next).toString()
                hops++
                continue
            }
            return conn
        }
    }
}
