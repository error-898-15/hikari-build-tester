package com.hikari.app.dub

import android.content.Context
import kotlinx.coroutines.CancellationException
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

    const val NAME = "kokoro-multi-lang-v1_0"
    private const val URL_TAR =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"

    fun dir(c: Context): File = File(File(c.filesDir, "dub_models"), NAME)

    fun isInstalled(c: Context): Boolean {
        val d = dir(c)
        return File(d, ".ok").exists() &&
            File(d, "model.onnx").length() > 0 &&
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
     */
    suspend fun install(c: Context, onProgress: (Int, Boolean) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val root = File(c.filesDir, "dub_models").apply { mkdirs() }
            val part = File(c.cacheDir, "$NAME.tar.bz2.part")
            val staging = File(root, "$NAME.tmp")
            try {
                if (root.usableSpace < 1_000_000_000L) {
                    return@withContext "Not enough free space (about 1 GB is needed)."
                }

                // 1) download (with resume)
                val have = if (part.exists()) part.length() else 0L
                val conn = openFollowing(URL_TAR, have)
                val code = conn.responseCode
                if (code != 200 && code != 206) {
                    conn.disconnect()
                    return@withContext "Download failed (server answered $code)."
                }
                val append = code == 206 && have > 0
                val len = conn.contentLengthLong
                val total = if (len > 0) len + (if (append) have else 0L) else -1L
                var done = if (append) have else 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt().coerceIn(0, 100)
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
                    return@withContext "Download was cut off. Tap again to continue."
                }

                // 2) unpack
                staging.deleteRecursively()
                staging.mkdirs()
                val stagingPath = staging.canonicalPath
                val size = part.length().coerceAtLeast(1L)
                var lastUnpack = -1
                FileInputStream(part).use { fis ->
                    BZip2CompressorInputStream(BufferedInputStream(fis, 256 * 1024)).use { bz ->
                        TarArchiveInputStream(bz).use { tar ->
                            while (true) {
                                ensureActive()
                                val e = tar.nextEntry ?: break
                                var rel = e.name.replace('\\', '/')
                                if (rel.startsWith("./")) rel = rel.removePrefix("./")
                                if (rel.startsWith("$NAME/")) rel = rel.removePrefix("$NAME/")
                                if (rel.isEmpty() || rel == NAME) continue
                                val target = File(staging, rel)
                                // never write outside the model folder
                                if (!target.canonicalPath.startsWith(stagingPath + File.separator)) continue
                                if (e.isDirectory) {
                                    target.mkdirs()
                                } else {
                                    target.parentFile?.mkdirs()
                                    FileOutputStream(target).use { out -> tar.copyTo(out, 128 * 1024) }
                                }
                                val p = (fis.channel.position() * 100 / size).toInt().coerceIn(0, 100)
                                if (p != lastUnpack) {
                                    lastUnpack = p
                                    onProgress(p, true)
                                }
                            }
                        }
                    }
                }

                // 3) check and switch on
                val ok = File(staging, "model.onnx").length() > 0 &&
                    File(staging, "voices.bin").length() > 0 &&
                    File(staging, "tokens.txt").length() > 0 &&
                    File(staging, "espeak-ng-data").isDirectory
                if (!ok) {
                    staging.deleteRecursively()
                    part.delete()
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
                null
            } catch (ce: CancellationException) {
                staging.deleteRecursively()
                throw ce
            } catch (t: Throwable) {
                staging.deleteRecursively()
                "Voice download failed: " + (t.message ?: t.javaClass.simpleName)
            }
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
