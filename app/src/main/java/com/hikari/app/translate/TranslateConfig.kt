package com.hikari.app.translate

/**
 * Everything the manga translator needs to know, read once from the settings
 * store (Settings -> Translate/Dub) and handed around as one value.
 *
 * Same fields and same meaning as the config of the Kotatsu build this feature
 * was written for; only [enabled] is new, because here the on/off switch travels
 * with the rest instead of being read from a global preference object.
 */
data class TranslateConfig(
    val enabled: Boolean = false,
    /** "auto" or a language code of the pages ("ja", "zh", "ko", "hi", "en"). */
    val source: String = "auto",
    val target: String = "en",
    /** "mlkit", "google" or "gemini". */
    val engine: String = "mlkit",
    val geminiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
) {
    val useGemini: Boolean get() = engine == "gemini" && geminiKey.isNotBlank()

    /** Google Translate is the main engine, or the quality fallback when Gemini fails. */
    val useGoogle: Boolean get() = engine == "google" || engine == "gemini"

    /** True when pages will actually be changed: the switch is on AND there is something to translate. */
    val active: Boolean get() = enabled && source != target

    /**
     * Part of the reader's page-cache file name. Empty while translation is off,
     * so with the feature unused every cache key is byte-for-byte what it was
     * before this feature existed; with it on, each language/engine choice gets
     * its own cached copy of a page instead of showing a stale one.
     */
    val cacheTag: String
        get() = if (!active) "" else "v5_${source}_${target}_${if (useGemini) "g" else if (useGoogle) "o" else "m"}"

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
        val OFF = TranslateConfig()
    }
}
