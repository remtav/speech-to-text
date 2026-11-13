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

/** Decides whether an LLM clean-up may replace the text it was given. Never throws. */
fun interface CleanupGuard {
    fun check(raw: String, cleaned: String): SafetyCheck.Verdict
}

/**
 * The product guard for the local LLM clean-up (issue #15, user report
 * "friperie"): the LLM may only remove hesitations, repetitions and spoken
 * self-corrections. Every word of the output must be a word of the input,
 * unchanged and in the same order; not one letter, accent or plural may
 * differ. Otherwise the input is kept.
 *
 * The only normalization, before comparing words:
 * - Unicode NFC, typographic apostrophe and hyphen unified (same characters);
 * - punctuation and spaces ignored (they are not words);
 * - the case of a word's **first letter** ignored (sentence capitals,
 *   "friperie" / "Friperie"); the rest of the word must match exactly. A capital
 *   of the input may only be lowered at the start of a sentence ("Bon. Donc" ->
 *   "Bon, donc"): "j'ai vu Pierre" -> "j'ai vu pierre" is a changed word.
 *
 * Numbers keep their digits and their separators ("3,5" is not "3, 5"), and a
 * question mark of the input is never dropped (a question is not a statement).
 *
 * Words of the input missing from the output must form runs that are each:
 * only hesitations ([FILLERS]); a repetition of the words right before or
 * after ("on va, on va"); a false start that the next words restart ("on va
 * au, on va à"); or a spoken correction ending with a marker set off by a
 * comma ("lundi, non," / "Monday, no,"). Anything else is a dropped word.
 * A repetition or false start never spans a sentence end ("Je vois Marie.
 * Marie arrive") and never removes a spelled letter, a number word or a
 * doubling that grammar requires ([NO_DEDUP]: "c o i f f e", "cinq cinq cinq",
 * "vous vous lavez", "had had"); a correction stays inside its sentence
 * ("Il pleut, enfin. Je reste" drops a sentence, it corrects nothing).
 *
 * Variants (for instance a looser guard evaluated for proper nouns, lot 3
 * step 3.3) implement [CleanupGuard] beside this object, never by loosening it.
 */
object SafetyCheck : CleanupGuard {
    /** Hesitations that can be dropped anywhere. Words with a meaning ("ben", "bon", "like", "quoi") are not here. */
    private val FILLERS = setOf(
        "euh", "euhm", "heu", "hum", "hmm", "hm", "mmh", "mh", "mm", "bah", "ah",
        "um", "umm", "uh", "uhh", "uhm", "erm", "er",
    )
    /** Grammatical words a false start may leave behind ("on va au, on va à la plage"). */
    private val FUNCTION_WORDS = setOf(
        "the", "a", "an", "to", "of", "in", "on", "at", "for", "with",
        "le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "au", "aux", "à", "en", "dans", "pour",
        "avec", "sur", "chez", "et", "ou", "que", "qu",
    )
    private val MARKERS = listOf(
        listOf("non"), listOf("no"), listOf("pardon"), listOf("sorry"), listOf("enfin"),
        listOf("je", "veux", "dire"), listOf("i", "mean"),
    )
    /**
     * Words a repetition or false start may not remove even when doubled (independent review of
     * lot 3): number words (a phone number, "cinq cinq cinq"), and words grammar doubles. Spelled
     * letters (any one-character word) are handled by [noDedup]. "un" and "une" are left out:
     * "une une maison" is a common stutter, and digits are checked separately.
     */
    private val NO_DEDUP = setOf(
        "zéro", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze",
        "treize", "quatorze", "quinze", "seize", "vingt", "trente", "quarante", "cinquante", "soixante",
        "cent", "mille",
        "zero", "oh", "one", "two", "three", "four", "five", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "twenty", "thirty", "forty", "fifty", "sixty", "hundred", "thousand",
        "nous", "vous", "had", "that",
    )
    private const val MAX_RUN = 12
    private const val MAX_REPEAT = 6
    private const val MAX_FALSE_START = 4
    private const val MAX_CORRECTED = 6

    private val NEGATIONS = setOf("not", "no", "never", "don't", "n't", "ne", "pas", "jamais", "non", "aucun", "aucune", "rien")
    private val OPENERS = listOf("sure", "here is", "here's", "certainly", "voici", "bien sûr", "bien sur", "d'accord")
    private val CORRECTION = Regex("(?i)[,;]\\s*(non|no|pardon|sorry|je veux dire|i mean|enfin)\\s*[,;]")
    private val WORD = Regex("[\\p{L}\\p{N}]+(?:['-][\\p{L}\\p{N}]+)*")
    private val SET_OFF = Regex("[,;:—–]")
    /** A sentence end: ? ! or a single period (an ellipsis "…" / "..." is a hesitation, not an end). */
    private val SENTENCE_END = Regex("[?!\\n]|(?<!\\.)\\.(?!\\.)")
    private val NUMBER = Regex("\\d+(?:[.,:]\\d+)*")

    data class Verdict(val ok: Boolean, val rule: String? = null)

    private class Word(val text: String, val range: IntRange, val sentenceStart: Boolean) {
        val lower = text.lowercase()
    }

    private fun prepare(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)
        .replace('’', '\'').replace('‘', '\'').replace('‐', '-').replace('‑', '-')

    private fun words(s: String): List<Word> {
        var previousEnd = -1
        return WORD.findAll(s).map {
            val start = previousEnd < 0 || SENTENCE_END.containsMatchIn(s.substring(previousEnd + 1, it.range.first))
            previousEnd = it.range.last
            Word(it.value, it.range, start)
        }.toList()
    }

    /**
     * Same word: identical, except for the case of the first letter; an input capital
     * may only be lowered where the input word starts a sentence.
     */
    private fun same(input: Word, output: Word) = input.text == output.text ||
        (input.text.length == output.text.length && input.text.substring(1) == output.text.substring(1) &&
            input.text[0].lowercaseChar() == output.text[0].lowercaseChar() &&
            (!input.text[0].isUpperCase() || input.sentenceStart))

    /** One-character words (spelled letters, digits) and [NO_DEDUP] words are never dropped as doubles. */
    private fun noDedup(word: String) = word.length == 1 || word in NO_DEDUP

    private fun spansSentenceEnd(raw: String, rw: List<Word>, from: Int, to: Int): Boolean {
        val a = minOf(from, to)
        val b = maxOf(from, to)
        return a < b && SENTENCE_END.containsMatchIn(raw.substring(rw[a].range.last + 1, rw[b].range.first))
    }

    /** Loose tokens, only for counting negations (as before). */
    private fun looseTokens(s: String) = Normalize.fold(s)
        .replace(Regex("[^\\p{L}\\p{N}' ]+"), " ")
        .split(Regex("[\\s']+")).filter { it.isNotBlank() }

    override fun check(raw: String, cleaned: String): Verdict {
        val r = prepare(raw.trim())
        val c = prepare(cleaned.trim())
        val rw = words(r)
        val cw = words(c)
        if (cw.isEmpty()) return Verdict(false, "empty")
        val cl = Normalize.fold(c)
        val rl = Normalize.fold(r)
        if (OPENERS.any { cl.startsWith(it) && !rl.startsWith(it) }) return Verdict(false, "assistant_opener")

        // A spoken self-correction (", non, mardi" / ", no, Tuesday") is not a negation.
        val negRaw = looseTokens(CORRECTION.replace(r, ",")).count { it in NEGATIONS }
        if (negRaw != looseTokens(c).count { it in NEGATIONS }) return Verdict(false, "negation")

        if (NUMBER.findAll(c).map { it.value }.toList() != NUMBER.findAll(r).map { it.value }.toList()) {
            return Verdict(false, "numbers")
        }
        if (c.count { it == '?' } < r.count { it == '?' }) return Verdict(false, "question")

        if (!isSubsequence(rw, cw)) return Verdict(false, "changed_words")
        if (!align(r, rw, cw)) return Verdict(false, "dropped_content")
        return Verdict(true)
    }

    private fun isSubsequence(rw: List<Word>, cw: List<Word>): Boolean {
        var j = 0
        for (w in rw) if (j < cw.size && same(w, cw[j])) j++
        return j == cw.size
    }

    /** Is there a way to match [cw] in [rw] where every run of unmatched input words is allowed? */
    private fun align(raw: String, rw: List<Word>, cw: List<Word>): Boolean {
        val n = rw.size
        val m = cw.size
        val memo = HashMap<Int, Boolean>()
        fun deletable(i: Int, k: Int) = memo.getOrPut(i * (n + 1) + k) { allowedRun(raw, rw, i, k) }
        // reach[i][j]: input[0, i) matched to output[0, j), the last input word kept (or i == 0).
        val reach = Array(n + 1) { BooleanArray(m + 1) }
        reach[0][0] = true
        for (i in 0..n) for (j in 0..m) {
            if (!reach[i][j]) continue
            if (i == n && j == m) return true
            if (i < n && j < m && same(rw[i], cw[j])) reach[i + 1][j + 1] = true
            for (k in i + 1..minOf(n, i + MAX_RUN)) {
                if (!deletable(i, k)) continue
                if (k == n && j == m) return true
                if (k < n && j < m && same(rw[k], cw[j])) reach[k + 1][j + 1] = true
            }
        }
        return false
    }

    /** May the input words [i, k) be dropped? */
    private fun allowedRun(raw: String, rw: List<Word>, i: Int, k: Int): Boolean {
        val kept = (i until k).filter { rw[it].lower !in FILLERS }
        if (kept.isEmpty()) return true
        val run = kept.map { rw[it].lower }
        val afterIdx = (k until rw.size).filter { rw[it].lower !in FILLERS }
        val beforeIdx = (0 until i).filter { rw[it].lower !in FILLERS }
        val after = afterIdx.map { rw[it].lower }
        val before = beforeIdx.map { rw[it].lower }
        // Neither a repetition nor a false start crosses a sentence end, inside the run or towards its echo.
        val runCrossesEnd = spansSentenceEnd(raw, rw, kept.first(), kept.last())
        // Repetition: "on va, on va à la plage", "le le chat".
        if (run.size <= MAX_REPEAT && !runCrossesEnd && run.none { noDedup(it) }) {
            if (after.take(run.size) == run && !spansSentenceEnd(raw, rw, kept.last(), afterIdx.first())) return true
            if (before.takeLast(run.size) == run && !spansSentenceEnd(raw, rw, beforeIdx.last(), kept.first())) return true
        }
        // False start restarted by the next words: "on va au, on va à la plage".
        if (run.size <= MAX_FALSE_START && !runCrossesEnd) {
            val p = run.zip(after).takeWhile { (a, b) -> a == b }.size
            if (p >= 1 && p < run.size && run.take(p).none { noDedup(it) } &&
                run.drop(p).all { it in FUNCTION_WORDS } &&
                !spansSentenceEnd(raw, rw, kept.last(), afterIdx.first())
            ) return true
        }
        // Spoken correction: "lundi, non, mardi", the marker set off by a comma, inside one sentence.
        if (k < rw.size && !spansSentenceEnd(raw, rw, kept.first(), k)) {
            for (marker in MARKERS) {
                if (run.size <= marker.size || run.takeLast(marker.size) != marker) continue
                if (run.size - marker.size > MAX_CORRECTED) continue
                val first = kept[kept.size - marker.size]
                val last = kept.last()
                val gapBefore = raw.substring(rw[first - 1].range.last + 1, rw[first].range.first)
                val gapAfter = raw.substring(rw[last].range.last + 1, rw[last + 1].range.first)
                if (SET_OFF.containsMatchIn(gapBefore) || SET_OFF.containsMatchIn(gapAfter)) return true
            }
        }
        return false
    }
}
