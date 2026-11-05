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

/**
 * The offline neural voice model (Kokoro, 82M, many speakers incl. 4 Hindi
 * ones). It is NOT inside the APK (it is a few hundred MB): the person taps
 * "Start dubbing" with the neural voice and it is downloaded once, from the
 * official sherpa-onnx releases page on GitHub, and unpacked into the app's
 * private files folder.
 */
object SherpaModels {

    const val NAME = "kokoro-int8-multi-lang-v1_0"
    private const val URL_TAR =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"

    fun dir(c: Context): File = File(File(c.filesDir, "dub_models"), NAME)

    fun isInstalled(c: Context): Boolean {
        val d = dir(c)
        return File(d, ".ok").exists() &&
            (File(d, "model.int8.onnx").length() > 0 || File(d, "model.onnx").length() > 0) &&
            File(d, "voices.bin").length() > 0 &&
            File(d, "tokens.txt").length() > 0 &&
            File(d, "espeak-ng-data").isDirectory
    }

    fun delete(c: Context) {
        dir(c).deleteRecursively()
    }

    /**
     * Downloads (resuming a half-finished download) and unpacks the model.
     * [onProgress] gets (percent 0..100, unpacking?) from a background thread.
     * Returns null on success, otherwise a short message for the person.
     *
     * A fully downloaded file is remembered (".done" marker), so if the app is
     * closed or killed while unpacking, the next try goes straight to
     * unpacking instead of asking the server for bytes past the end of the
     * file (that is what used to end in "server answered 416").
     */
    suspend fun install(c: Context, onProgress: (Int, Boolean) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val root = File(c.filesDir, "dub_models").apply { mkdirs() }
            val part = File(c.cacheDir, "$NAME.tar.bz2.part")
            val marker = File(c.cacheDir, "$NAME.tar.bz2.done")
            val staging = File(root, "$NAME.tmp")
            var unpacking = false
            // The first version of the neural voice used a bigger pack
            // (about 330 MB). Free that space: it is not used any more.
            runCatching {
                File(root, "kokoro-multi-lang-v1_0").deleteRecursively()
                File(c.cacheDir, "kokoro-multi-lang-v1_0.tar.bz2.part").delete()
                File(c.cacheDir, "kokoro-multi-lang-v1_0.tar.bz2.done").delete()
            }
            try {
                val haveWholeFile = part.exists() && part.length() > 0 && marker.exists()
                val needSpace = if (haveWholeFile) 300_000_000L else 500_000_000L
                if (root.usableSpace < needSpace) {
                    return@withContext "Not enough free space (about 500 MB is needed)."
                }

                // 1) download (with resume), skipped when the whole file is already here
                if (!haveWholeFile) {
                    val err = download(part, marker, onProgress)
                    if (err != null) return@withContext err
                }

                // 2) unpack
                unpacking = true
                unpack(part, staging, onProgress)
                unpacking = false

                // 3) check and switch on
                val ok = (File(staging, "model.int8.onnx").length() > 0 || File(staging, "model.onnx").length() > 0) &&
                    File(staging, "voices.bin").length() > 0 &&
                    File(staging, "tokens.txt").length() > 0 &&
                    File(staging, "espeak-ng-data").isDirectory
                if (!ok) {
                    staging.deleteRecursively()
                    part.delete()
                    marker.delete()
                    return@withContext "The downloaded voice pack was incomplete. Tap again to retry."
                }
                File(staging, ".ok").writeText("1")
                val finalDir = dir(c)
                finalDir.deleteRecursively()
                if (!staging.renameTo(finalDir)) {
                    staging.deleteRecursively()
                    return@withContext "Could not save the voice pack."
                }
                part.delete()
                marker.delete()
                null
            } catch (ce: CancellationException) {
                // keep the downloaded file, so the next try does not download again
                staging.deleteRecursively()
                throw ce
            } catch (t: Throwable) {
                staging.deleteRecursively()
                if (unpacking) {
                    val noSpace = t is OutOfMemoryError ||
                        t.message.orEmpty().contains("space", ignoreCase = true) ||
                        root.usableSpace < 100_000_000L
                    if (!noSpace) {
                        // the saved file is damaged: throw it away so the next try starts clean
                        part.delete()
                        marker.delete()
                    }
                }
                "Voice download failed: " + (t.message ?: t.javaClass.simpleName)
            }
        }

    /**
     * Downloads the voice pack into [part] (resuming when a part is there).
     * Returns null when the whole file is on the phone (and writes [marker]),
     * otherwise a short message for the person.
     */
    private fun CoroutineScope.download(
        part: File,
        marker: File,
        onProgress: (Int, Boolean) -> Unit,
    ): String? {
        marker.delete()
        val have = if (part.exists()) part.length() else 0L
        var conn = openFollowing(URL_TAR, have)
        var code = conn.responseCode
        var resumeFrom = have

        if (code == 416) {
            // "Range not satisfiable": we asked for bytes after the end, which means
            // the saved file is already whole. Never ask for that range again.
            val range = conn.getHeaderField("Content-Range")
            conn.disconnect()
            val serverTotal = range?.substringAfter('/', "")?.trim()?.toLongOrNull()
            if (serverTotal == null || serverTotal == have) {
                marker.writeText("1")
                return null
            }
            // the saved file is bigger than the real one (damaged): start again from zero
            part.delete()
            resumeFrom = 0L
            conn = openFollowing(URL_TAR, 0L)
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
     * Unpacks [part] (a .tar.bz2) into [staging]. The percentage moves while a
     * big file (the model is one single, huge file) is being written, not only
     * between files, so it no longer looks frozen.
     */
    private fun CoroutineScope.unpack(
        part: File,
        staging: File,
        onProgress: (Int, Boolean) -> Unit,
    ) {
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingPath = staging.canonicalPath
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
                        var rel = e.name.replace('\\', '/')
                        if (rel.startsWith("./")) rel = rel.removePrefix("./")
                        rel = rel.replaceFirst(Regex("^kokoro[^/]*/"), "")
                        if (rel.isEmpty() || rel.startsWith("kokoro")) continue
                        val target = File(staging, rel)
                        // never write outside the model folder
                        if (!target.canonicalPath.startsWith(stagingPath + File.separator)) continue
                        if (e.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
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
