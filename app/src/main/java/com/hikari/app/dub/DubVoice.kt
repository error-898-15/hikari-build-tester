package com.hikari.app.dub

import java.io.File

/**
 * Something that can turn a sentence into an audio file.
 *
 * Two implementations ship:
 *  - [GoogleTtsVoice]  — Method 1: the same Google Translate speaker button, online.
 *  - [LocalTtsVoice]   — Method 2: the phone's own offline text-to-speech engine.
 */
interface DubVoice {
    val label: String

    /** Whether this voice can be tried at all for [lang] right now. */
    suspend fun isAvailable(lang: String): Boolean

    /** Returns an audio file for [text] spoken in [lang], or null on failure. */
    suspend fun synthesize(text: String, lang: String): File?

    fun release() {}
}
