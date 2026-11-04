package com.hikari.app.translate

import com.hikari.app.data.Logs
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * Glue between the reader's page cache ([com.hikari.app.reader.cache.WebtoonPageCache])
 * and [MangaTranslator].
 *
 * The cache downloads a page's bytes to a temp file and then moves it to its final
 * name; every viewer reads only that final name. Translating at exactly that move
 * means the viewers, the metadata (size, tall/animated) and the prewarm loop all
 * see the translated page and none of them had to learn about translation.
 *
 * Retry: if a page could not be fully translated (e.g. no connection for the
 * online engine), the ORIGINAL bytes are kept next to it as `<key>.src` and the
 * next time the reader asks for that page -- once per app run -- it is translated
 * again from that copy, with no download.
 */
internal object PageTranslation {

    private const val TAG = "Translate"

    /** Pages already retried in this app run, so a failing page is not re-scanned on every bind. */
    private val retried = ConcurrentHashMap.newKeySet<String>()

    fun retrySource(target: File): File = File(target.parentFile, target.name.removeSuffix(".img") + ".src")

    /** True when [target] is a half-translated page that deserves another go now. */
    fun needsRetry(target: File, cfg: TranslateConfig): Boolean =
        cfg.active && retrySource(target).exists() && !retried.contains(target.name)

    /**
     * Puts [original] in place as [target], translated if translation is on.
     * [original] is either a freshly downloaded temp file, or the `.src` copy of a retry.
     */
    suspend fun place(original: File, target: File, cfg: TranslateConfig) {
        val src = retrySource(target)
        val isRetry = original.absolutePath == src.absolutePath
        if (isRetry) retried.add(target.name)

        if (!cfg.active) {
            if (!isRetry) moveOver(original, target)
            return
        }

        val out = File(target.parentFile, target.name + ".t")
        out.delete()
        val result = try {
            MangaTranslator.translateFile(original, out, cfg)
        } catch (e: CancellationException) {
            out.delete()
            throw e
        } catch (e: Throwable) {
            Logs.log(TAG, "page translation failed: ${e.javaClass.simpleName}: ${e.message}")
            MangaTranslator.PageResult.FAILED
        }

        when (result) {
            MangaTranslator.PageResult.DONE -> {
                moveOver(out, target)
                src.delete()
                if (!isRetry) original.delete()
            }
            MangaTranslator.PageResult.NOTHING -> {
                out.delete()
                if (isRetry) src.delete() else moveOver(original, target)
            }
            MangaTranslator.PageResult.PARTIAL -> {
                moveOver(out, target)
                if (!isRetry) moveOver(original, src)
            }
            MangaTranslator.PageResult.FAILED -> {
                out.delete()
                if (!isRetry) {
                    original.copyTo(src, overwrite = true)
                    moveOver(original, target)
                }
            }
        }
    }

    private fun moveOver(from: File, to: File) {
        to.delete()
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }
}
