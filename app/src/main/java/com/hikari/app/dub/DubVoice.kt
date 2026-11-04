package com.hikari.app.dub

import java.io.File

/**
 * Something that can turn a sentence into an audio file.
 *
 * Three implementations ship:
 *  - [SherpaTtsVoice]  — offline neural voices (Hindi first), several actors.
 *  - [GoogleTtsVoice]  — Google Translate's speaker button, online.
 *  - [LocalTtsVoice]   — the phone's own offline text-to-speech engine.
 */
interface DubVoice {
    val label: String

    /** Whether this voice can be tried at all for [lang] right now. */
    suspend fun isAvailable(lang: String): Boolean

    /** Returns an audio file for [text] spoken in [lang], or null on failure. */
    suspend fun synthesize(text: String, lang: String): File?

    /**
     * Same, but for a particular character. Voices that have several actors
     * (the neural one) pick the actor from [profile]; the others ignore it.
     */
    suspend fun synthesize(text: String, lang: String, profile: VoiceProfile): File? =
        synthesize(text, lang)

    fun release() {}
}
