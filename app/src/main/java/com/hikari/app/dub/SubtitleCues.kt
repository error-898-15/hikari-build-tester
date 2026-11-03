package com.hikari.app.dub

/** One timed line of a subtitle file. */
data class DubCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Parses SRT and WebVTT text into [DubCue]s.
 *
 * Only the spoken words are kept: markup (`<i>`, `<font ...>`), ASS override
 * blocks (`{\an8}`), HTML entities and "sound only" lines (`[music]`, `♪ ... ♪`)
 * are removed, because the text is going to be read out loud by a voice.
 */
object SubtitleCues {

    private val TIME = Regex(
        """(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})"""
    )
    private val TAGS = Regex("<[^>]*>")
    private val ASS = Regex("""\{\\[^}]*\}""")
    private val SOUND_ONLY = Regex("""^[\[(].*[])]$""")
    private val SPACES = Regex("""\s+""")

    fun parse(raw: String): List<DubCue> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<DubCue>()
        for (block in text.split(Regex("\n{2,}"))) {
            val lines = block.split('\n')
            val timeIdx = lines.indexOfFirst { "-->" in it }
            if (timeIdx < 0) continue
            val m = TIME.find(lines[timeIdx]) ?: continue
            val start = ms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            val end = ms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
            if (end <= start) continue
            val spoken = clean(lines.drop(timeIdx + 1).joinToString(" "))
            if (spoken.isBlank()) continue
            out += DubCue(start, end, spoken)
        }
        out.sortBy { it.startMs }
        return out
    }

    private fun clean(s: String): String {
        var t = ASS.replace(s, " ")
        t = TAGS.replace(t, " ")
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        t = SPACES.replace(t, " ").trim()
        // A line that is only a sound description or only music notes is not speech.
        if (SOUND_ONLY.matches(t)) return ""
        if (t.all { it == '♪' || it == '♫' || it == '#' || it.isWhitespace() }) return ""
        return t.trim('♪', '♫', ' ')
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        val hours = h.toLongOrNull() ?: 0L
        // "5" is .500, "05" is .050 — pad on the right to three digits.
        val millis = frac.padEnd(3, '0').take(3).toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }
}
