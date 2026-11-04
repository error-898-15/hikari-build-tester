package com.hikari.app.translate

/**
 * The choices offered in Settings -> Translate/Dub. (code, English label) pairs,
 * in the order they are listed; the labels go through `tr()` where they are shown.
 */
object TranslateOptions {

    val SOURCES: List<Pair<String, String>> = listOf(
        "auto" to "Auto detect",
        "ja" to "Japanese",
        "zh" to "Chinese",
        "ko" to "Korean",
        "hi" to "Hindi",
        "en" to "English",
    )

    val TARGETS: List<Pair<String, String>> = listOf(
        "en" to "English",
        "bn" to "Bengali",
        "hi" to "Hindi",
        "id" to "Indonesian",
        "es" to "Spanish",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "ar" to "Arabic",
        "fr" to "French",
        "de" to "German",
        "tr" to "Turkish",
    )

    val ENGINES: List<Triple<String, String, String>> = listOf(
        Triple("mlkit", "ML Kit", "Offline, basic quality"),
        Triple("google", "Google Translate", "Online, good quality"),
        Triple("gemini", "Gemini", "Best quality, needs an API key"),
    )

    fun sourceLabel(code: String): String = SOURCES.firstOrNull { it.first == code }?.second ?: code

    fun targetLabel(code: String): String = TARGETS.firstOrNull { it.first == code }?.second ?: code

    fun engineLabel(key: String): String = ENGINES.firstOrNull { it.first == key }?.second ?: key
}
