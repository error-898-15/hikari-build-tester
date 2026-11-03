package com.hikari.app.dub

/**
 * One line to be spoken.
 *
 * @param id       stable key (survives live insertions, unlike a list index)
 * @param speaker  character name when the subtitle names one ("Maya"), "~A" /
 *                 "~B" for an unnamed back-and-forth, or null for "narrator".
 * @param shout    the line was written in capitals (shouting).
 */
data class DubCue(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String? = null,
    val shout: Boolean = false,
)

/**
 * Reads SRT / WebVTT text (or the text of a caption as it is shown) and turns
 * it into [DubCue]s that are ready to be spoken:
 *
 *  - markup, ASS blocks, entities and sound descriptions ("[music]", "♪") are dropped;
 *  - speaker names ("MAYA:", "[John]", "- Mary:") are recognised and removed
 *    from the spoken text, and kept as [DubCue.speaker];
 *  - a cue holding two dash lines ("- Hi" / "- Hello") becomes two cues;
 *  - SHOUTING in capitals is turned into normal sentences (a voice would spell it);
 *  - a sentence that the subtitle broke over two cues is joined again, so the
 *    voice (and the translation) get the whole sentence.
 */
object SubtitleCues {

    internal data class Turn(val speaker: String?, val text: String, val shout: Boolean)
    internal data class Label(val name: String, val rest: String, val caps: Boolean)
    private data class Block(val startMs: Long, val endMs: Long, val lines: List<String>)

    private val TIME = Regex(
        """(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})"""
    )
    private val TAGS = Regex("<[^>]*>")
    private val ASS = Regex("""\{\\[^}]*\}""")
    private val SOUND_ONLY = Regex("""^[\[(].*[\])]$""")
    private val SPACES = Regex("""\s+""")
    private val DASH = Regex("""^[-–—‐‑]+\s*""")
    private val BRACKET_LABEL = Regex("""^[\[(【]\s*([^\])】]{1,24}?)\s*[\])】]\s*:?\s*(\S.*)$""")
    private val COLON_LABEL = Regex("""^([\p{L}][\p{L}.'’ \-]{0,24}?)\s*(?:[\[(][^\])]{1,16}[\])]\s*)?[:：]\s*(\S.*)$""")
    private val STAGE = Regex("""\[[^\]]*\]|\([^)]*\)""")
    private val SENTENCE_END = Regex("""[.!?…。！？।"”')\]]$""")
    private val SHOUT_WORD = Regex("""\b\p{Lu}{3,}\b""")
    private val SENTENCE_START = Regex("""(^|[.!?]\s+)(\p{L})""")
    private val LONE_I = Regex("""\bi\b""")

    private val PARTICLES = setOf("de", "da", "van", "von", "bin", "ibn", "al", "el", "la", "le", "di", "du")

    /** Words that look like a name before a colon / in brackets but are not. */
    private val NOT_NAMES = (
        "note notes warning listen look remember translator translation sign text title subtitle " +
            "subtitles sfx music song lyrics laughs laughter laughing applause cheering sighs sigh " +
            "gasps gasp groans groan screams scream cries crying sobs whispers whispering shouts " +
            "shouting yells yelling chuckles grunts coughs breathing panting humming singing " +
            "inaudible indistinct muffled silence sniffles snorts scoffs thunder bell phone door " +
            "knocking beeping gunshot gunshots explosion engine ringing static radio ok okay yes no " +
            "hey well so but and the i he she we you they it now then here there this that what why " +
            "how who when where fine sorry please thanks hello hi wait stop come go time chapter " +
            "episode part flashback"
        ).split(' ').toHashSet()

    // ───────────────────────── public ─────────────────────────

    fun parse(raw: String, multiVoice: Boolean = true): List<DubCue> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = ArrayList<Block>()
        for (b in text.split(Regex("\n{2,}"))) {
            val lines = b.split('\n')
            val ti = lines.indexOfFirst { "-->" in it }
            if (ti < 0) continue
            val m = TIME.find(lines[ti]) ?: continue
            val start = ms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            val end = ms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
            if (end <= start) continue
            blocks += Block(start, end, lines.drop(ti + 1))
        }

        // Pass 1: how often does each possible speaker name appear? A word before a
        // colon is only trusted as a name if it is a known name, ALL CAPS, or repeats.
        val counts = HashMap<String, Int>()
        if (multiVoice) {
            for (b in blocks) for (l in b.lines) {
                candidateLabel(stripDash(clean(l)))?.let { counts.merge(it.name.lowercase(), 1, Int::plus) }
            }
        }
        val accept: (Label) -> Boolean = { l ->
            l.caps || NameGender.isKnown(l.name) || (counts[l.name.lowercase()] ?: 0) >= 3
        }

        val cues = ArrayList<DubCue>()
        for (b in blocks) {
            val turns = splitTurns(b.lines, multiVoice, accept)
            if (turns.isEmpty()) continue
            val total = (b.endMs - b.startMs).coerceAtLeast(1L)
            val weight = turns.sumOf { it.text.length.coerceAtLeast(1) }.toDouble()
            var cursor = b.startMs
            for ((i, t) in turns.withIndex()) {
                val share = (total * (t.text.length.coerceAtLeast(1) / weight)).toLong()
                val end = if (i == turns.lastIndex) b.endMs else (cursor + share).coerceAtMost(b.endMs)
                cues += DubCue(0, cursor, maxOf(end, cursor + 1), t.text, t.speaker, t.shout)
                cursor = end
            }
        }
        cues.sortBy { it.startMs }
        return mergeFragments(cues).mapIndexed { i, c -> c.copy(id = i) }
    }

    /** Turns the text of a caption that is on screen right now into speakable
     *  turns. [seen] remembers speaker names across calls (a name seen twice
     *  is trusted). */
    internal fun liveTurns(text: String, seen: MutableMap<String, Int>): List<Turn> {
        val lines = text.split('\n')
        for (l in lines) {
            candidateLabel(stripDash(clean(l)))?.let { seen.merge(it.name.lowercase(), 1, Int::plus) }
        }
        val accept: (Label) -> Boolean = { l ->
            l.caps || NameGender.isKnown(l.name) || (seen[l.name.lowercase()] ?: 0) >= 2
        }
        return splitTurns(lines, true, accept)
    }

    // ───────────────────────── turns & labels ─────────────────────────

    private class Draft(var speaker: String?) { val sb = StringBuilder() }

    private fun splitTurns(lines: List<String>, multiVoice: Boolean, accept: (Label) -> Boolean): List<Turn> {
        val drafts = ArrayList<Draft>()
        var cur: Draft? = null
        for (line in lines) {
            val c = clean(line)
            if (c.isBlank()) continue
            val dash = DASH.find(c)
            val body0 = if (dash != null) c.substring(dash.value.length) else c
            var label: String? = null
            var body = body0
            val cand = if (multiVoice) candidateLabel(body0) else null
            if (cand != null && accept(cand)) { label = cand.name; body = cand.rest }
            var d = cur
            if (d == null || dash != null || label != null) {
                d = Draft(label)
                drafts += d
                cur = d
            }
            if (d.sb.isNotEmpty()) d.sb.append(' ')
            d.sb.append(body)
        }

        val out = ArrayList<Turn>()
        for (d in drafts) {
            val original = d.sb.toString()
            val spoken = normalizeCase(SPACES.replace(STAGE.replace(original, " "), " ").trim())
            if (spoken.none { it.isLetterOrDigit() }) continue
            out += Turn(d.speaker, spoken, SHOUT_WORD.containsMatchIn(original))
        }
        // Several unnamed turns in one cue are a conversation: alternate two voices.
        if (multiVoice && out.size >= 2) {
            var k = 0
            for (i in out.indices) {
                if (out[i].speaker == null) {
                    out[i] = out[i].copy(speaker = if (k % 2 == 0) "~A" else "~B")
                    k++
                }
            }
        }
        return out
    }

    private fun candidateLabel(body: String): Label? {
        BRACKET_LABEL.find(body)?.let { m ->
            asName(m.groupValues[1], 2)?.let { return Label(it.first, m.groupValues[2], it.second) }
        }
        COLON_LABEL.find(body)?.let { m ->
            asName(m.groupValues[1], 3)?.let { return Label(it.first, m.groupValues[2], it.second) }
        }
        return null
    }

    /** Returns (display name, wasAllCaps) if [candidate] can be a character's name. */
    private fun asName(candidate: String, maxWords: Int): Pair<String, Boolean>? {
        val t = SPACES.replace(candidate.trim().trim('.'), " ")
        if (t.isEmpty() || t.length > 24 || t.any { it.isDigit() }) return null
        val words = t.split(' ')
        if (words.size > maxWords) return null
        if (words.any { it.lowercase() in NOT_NAMES }) return null
        // Cased scripts only: every word starts with a capital (or is a name particle).
        if (!words.all { it[0].isUpperCase() || it.lowercase() in PARTICLES }) return null
        val caps = t.any { it.isLetter() } && t.none { it.isLowerCase() }
        val pretty = words.joinToString(" ") { w ->
            if (w.lowercase() in PARTICLES) w.lowercase()
            else w.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
        return pretty to caps
    }

    private fun stripDash(s: String): String {
        val m = DASH.find(s)
        return if (m != null) s.substring(m.value.length) else s
    }

    // ───────────────────────── cleaning ─────────────────────────

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

    /** "WHAT ARE YOU DOING?" → "What are you doing?" (a voice would spell capitals). */
    private fun normalizeCase(t: String): String {
        val letters = t.filter { it.isLetter() }
        if (letters.length >= 4 && letters.count { it.isUpperCase() } >= letters.length * 0.7) {
            var s = t.lowercase()
            s = SENTENCE_START.replace(s) { m -> m.groupValues[1] + m.groupValues[2].uppercase() }
            return LONE_I.replace(s, "I")
        }
        return t
    }

    /** Joins a sentence the subtitle broke across two cues. */
    private fun mergeFragments(list: List<DubCue>): List<DubCue> {
        val out = ArrayList<DubCue>()
        for (c in list) {
            val p = out.lastOrNull()
            if (p != null && p.speaker == c.speaker &&
                !SENTENCE_END.containsMatchIn(p.text) &&
                c.startMs - p.endMs in -200L..400L &&
                p.text.length + c.text.length < 240 &&
                c.endMs - p.startMs <= 10_000L
            ) {
                out[out.size - 1] = p.copy(
                    endMs = maxOf(p.endMs, c.endMs),
                    text = p.text + " " + c.text,
                    shout = p.shout || c.shout,
                )
            } else {
                out += c
            }
        }
        return out
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        val hours = h.toLongOrNull() ?: 0L
        // "5" is .500, "05" is .050 — pad on the right to three digits.
        val millis = frac.padEnd(3, '0').take(3).toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }
}
