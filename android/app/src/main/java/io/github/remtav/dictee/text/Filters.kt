package io.github.remtav.dictee.text

/**
 * Phrases Whisper is known to produce on silence or noise (it learned them
 * from subtitle credits). A segment made only of one of these is dropped.
 */
object HallucinationFilter {
    private val PHRASES = listOf(
        "sous titres realises par la communaute d amara org",
        "sous titres realises para la communaute d amara org",
        "sous titrage st 501",
        "sous titrage societe radio canada",
        "merci d avoir regarde cette video",
        "thanks for watching",
        "thank you for watching",
        "please subscribe",
        "subtitles by the amara org community",
        "amara org",
    ).toSet()

    fun isHallucination(text: String): Boolean {
        val key = Normalize.fold(text).replace(Regex("[^a-z0-9]+"), " ").trim()
        if (key.isEmpty()) return true
        return key in PHRASES || PHRASES.any { key.contains(it) && it.length > 12 && key.length < it.length + 12 }
    }
}

/**
 * Deterministic clean-up, no model involved: hesitations ("euh", "um")
 * removed, stuttered function words collapsed ("le le" -> "le").
 */
object RuleCleaner {
    private val FILLERS = setOf(
        "euh", "euhm", "heu", "hum", "hmm", "hm", "mmh", "mh", "bah", "ben euh",
        "um", "umm", "uh", "uhh", "uhm", "erm", "er", "ah euh",
    )
    private val FILLER_RE = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(" + FILLERS.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])[,.…]*\\s*",
    )
    private val STUTTER = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(le|la|les|un|une|de|des|je|tu|il|on|et|the|a|an|i|to|and|of)(\\s*,?\\s+)\\1(?![\\p{L}\\p{N}])",
    )

    fun clean(text: String): String {
        var out = FILLER_RE.replace(text, "")
        repeat(2) { out = STUTTER.replace(out) { m -> m.groupValues[1] } }
        out = out.replace(Regex("\\s{2,}"), " ")
            .replace(Regex("\\s+([,.;:!?])"), "$1")
            .replace(Regex("^[,;:]\\s*"), "")
            .trim()
        val wasCapitalized = text.firstOrNull { it.isLetter() }?.isUpperCase() == true
        return if (wasCapitalized) TextAssembler.capitalizeFirst(out) else out
    }
}

/**
 * Word-level guard for LLM clean-up, in the spirit of app/polish.py: the
 * cleaned text replaces the transcript only if it did not add, drop or flip
 * content. Any failure keeps the raw transcript.
 */
object SafetyCheck {
    private val FILLER_WORDS = setOf(
        "um", "umm", "uh", "uhh", "er", "erm", "ah", "hmm", "mm", "like", "basically", "literally",
        "euh", "heu", "ben", "bah", "bon", "hein", "genre", "voila", "quoi", "hum",
    )
    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "but", "so", "to", "of", "in", "on", "at", "for", "with", "is",
        "are", "was", "were", "be", "it", "this", "that", "i", "you", "we", "they", "he", "she",
        "le", "la", "les", "un", "une", "des", "du", "de", "et", "ou", "mais", "donc", "a", "au",
        "aux", "en", "dans", "pour", "avec", "sur", "est", "je", "tu", "il", "elle", "on", "nous",
        "vous", "ils", "elles", "ce", "c", "j", "l", "d", "qu", "que", "qui", "s", "n", "m", "t",
        "you know", "y", "se", "sa", "son", "ses",
    )
    private val NEGATIONS = setOf("not", "no", "never", "don't", "n't", "ne", "pas", "jamais", "non", "aucun", "aucune", "rien")
    private val OPENERS = listOf("sure", "here is", "here's", "certainly", "voici", "bien sûr", "bien sur", "d'accord")

    private val CORRECTION = Regex("(?i)[,;]\\s*(non|no|pardon|sorry|je veux dire|i mean|enfin)\\s*[,;]")

    data class Verdict(val ok: Boolean, val rule: String? = null)

    private fun tokens(s: String) = Normalize.fold(s)
        .replace(Regex("[^\\p{L}\\p{N}' ]+"), " ")
        .split(Regex("[\\s']+")).filter { it.isNotBlank() }

    fun check(raw: String, cleaned: String, dictionary: Collection<String> = emptyList()): Verdict {
        val r = raw.trim()
        val c = cleaned.trim()
        if (c.isEmpty()) return Verdict(false, "empty")
        val rt = tokens(r)
        val ct = tokens(c)
        if (ct.isEmpty()) return Verdict(false, "empty")
        val cl = Normalize.fold(c)
        val rl = Normalize.fold(r)
        if (OPENERS.any { cl.startsWith(it) && !rl.startsWith(it) }) return Verdict(false, "assistant_opener")

        fun count(t: List<String>, set: Set<String>) = t.count { it in set }
        // A spoken self-correction (", non, mardi" / ", no, Tuesday") is not a negation.
        val rawForNegation = tokens(CORRECTION.replace(r, ","))
        if (count(rawForNegation, NEGATIONS) != count(ct, NEGATIONS)) return Verdict(false, "negation")

        val digits = Regex("\\d+")
        if (digits.findAll(c).map { it.value }.toList() != digits.findAll(r).map { it.value }.toList()) {
            return Verdict(false, "numbers")
        }

        val content = { t: List<String> -> t.filter { it !in STOPWORDS && it !in FILLER_WORDS } }
        val rc = content(rt).toSet()
        val cc = content(ct)
        val allowed = rt.toSet() + dictionary.flatMap { tokens(it) }
        val novel = cc.filter { it !in allowed }
        val short = rt.size < 8
        if (short) {
            if (ct.size > rt.size + 1) return Verdict(false, "short_length")
            if (novel.isNotEmpty()) return Verdict(false, "novel_words")
        } else {
            val ratio = c.length.toDouble() / r.length
            if (ratio < 0.5 || ratio > 1.15) return Verdict(false, "length_ratio")
            if (novel.size > 3 || novel.size.toDouble() / cc.size.coerceAtLeast(1) > 0.08) {
                return Verdict(false, "novel_words")
            }
        }
        if (rc.isNotEmpty()) {
            val kept = rc.count { it in cc.toSet() }.toDouble() / rc.size
            if (kept < if (short) 0.5 else 0.75) return Verdict(false, "dropped_content")
        }
        return Verdict(true)
    }
}
