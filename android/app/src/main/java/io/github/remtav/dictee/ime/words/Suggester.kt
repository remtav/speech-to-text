package io.github.remtav.dictee.ime.words

/**
 * Word suggestions and the automatic correction of the text keyboard (issue #71), over the word lists
 * of the languages chosen at setup. Pure logic: no Android type, no state between calls.
 *
 * For what was typed so far it offers, best first:
 * - the same letters with their accents and capitals (`eleve` gives `élève`, `quebec` gives `Québec`);
 * - words that start with it (`bonj` gives `bonjour`);
 * - words one or two slips away (`bonjuor` gives `bonjour`).
 *
 * The correction is the one word that replaces the typed one when the person moves on, and only
 * when the typed word is not a word itself.
 */
class Suggester(
    private val lexicons: List<Lexicon>,
    /** Words the person kept against a correction: never corrected again. */
    private val learned: () -> Set<String> = { emptySet() },
    /** Whether two letters are neighbours on the keyboard shown (a slip of the finger). */
    private val neighbours: (Char, Char) -> Boolean = { _, _ -> false },
) {
    data class Result(
        val typed: String,
        /** Suggestions, best first, written with the capitals of what was typed; never [typed] itself. */
        val words: List<String>,
        /** The word to put in place of [typed], or null when [typed] stays. */
        val correction: String?,
        /** [typed] is a word of the lists, a learned word or an everyday informal word. */
        val known: Boolean,
    ) {
        companion object {
            fun none(typed: String) = Result(typed, emptyList(), null, false)
        }
    }

    private class Candidate(val word: String, val score: Int, val distance: Int, val frequency: Int)

    /**
     * Suggestions for [typed], the word before the cursor. [sentenceStart]: a capital may then be
     * the sentence's, not a name's, so the word can be corrected.
     */
    fun suggest(typed: String, sentenceStart: Boolean = false, max: Int = 3): Result {
        if (typed.isEmpty() || typed.length > Lexicon.MAX_WORD || typed.none { it.isLetter() }) return Result.none(typed)
        val whole = lookup(typed, sentenceStart, max)
        if (whole.known || whole.words.isNotEmpty()) return whole
        // « l'arb » : nothing for the whole, so the part after the apostrophe is completed (« l'arbre »).
        val cut = typed.indexOfLast { it == '\'' || it == '’' }
        if (cut < 0 || cut == typed.lastIndex || cut > MAX_ELISION) return whole
        val head = typed.substring(0, cut + 1)
        val tail = lookup(typed.substring(cut + 1), sentenceStart = false, max = max)
        return Result(typed, tail.words.map { head + it }, tail.correction?.let { head + it }, tail.known)
    }

    private fun lookup(typed: String, sentenceStart: Boolean, max: Int): Result {
        val folded = CharArray(typed.length) { Fold.of(typed[it]) }
        val n = folded.size
        val remembered = learned()
        var known = typed in remembered || typed.lowercase() in remembered || typed.lowercase() in Informal.words
        val found = HashMap<String, Candidate>()
        fun offer(word: String, score: Int, distance: Int, frequency: Int) {
            val old = found[word]
            if (old == null || score > old.score) found[word] = Candidate(word, score, distance, frequency)
        }

        for (lexicon in lexicons) {
            // Kept apart from the rest: a short prefix has thousands of completions, only the best matter.
            val best = arrayOfNulls<Candidate>(max + 2)
            for (i in lexicon.prefixRange(folded)) {
                val f = lexicon.frequency(i)
                val extra = lexicon.length(i) - n
                if (extra == 0) {
                    val word = lexicon.word(i)
                    if (writtenLike(word, typed)) known = true
                    else if (!lexicon.isOffensive(i)) offer(word, SAME_LETTERS + f, 0, f)
                    continue
                }
                val score = f - COMPLETION_COST * minOf(extra, 6)
                if (best.last()?.let { it.score >= score } == true || lexicon.isOffensive(i)) continue
                var at = best.lastIndex
                while (at > 0 && (best[at - 1]?.score ?: Int.MIN_VALUE) < score) { best[at] = best[at - 1]; at-- }
                best[at] = Candidate(lexicon.word(i), score, extra, f)
            }
            for (c in best) if (c != null) offer(c.word, c.score, c.distance, c.frequency)

            if (n >= MIN_TO_CORRECT) {
                lexicon.forEachNear(folded, if (n >= LONG_WORD) 2 else 1) { i, d ->
                    if (d > 0 && !lexicon.isOffensive(i)) {
                        val f = lexicon.frequency(i)
                        // A letter typed in excess is the rarest slip: a shorter word costs a little more.
                        val shorter = if (lexicon.length(i) < n) SHORTER_COST else 0
                        offer(lexicon.word(i), f - SLIP_COST * d - shorter + slipBonus(folded, lexicon, i, d), d, f)
                    }
                }
            }
        }
        for (w in remembered) {
            if (w.length > typed.length && w.startsWith(typed, ignoreCase = true)) offer(w, LEARNED_SCORE, w.length - n, LEARNED_SCORE)
        }

        val ranked = found.values.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.word })
        val correction = if (known) null else correction(typed, ranked, sentenceStart)
        val words = LinkedHashSet<String>()
        correction?.let { words += it }
        for (c in ranked) {
            if (words.size >= max) break
            val shaped = shape(c.word, typed)
            if (shaped != typed) words += shaped
        }
        return Result(typed, words.toList(), correction, known)
    }

    /** A slip that is likely: a neighbouring key, or two letters swapped. */
    private fun slipBonus(folded: CharArray, lexicon: Lexicon, i: Int, distance: Int): Int {
        if (distance != 1 || lexicon.length(i) != folded.size) return 0
        var first = -1
        var last = -1
        for (p in folded.indices) if (lexicon.folded(i, p) != folded[p]) { if (first < 0) first = p; last = p }
        return when {
            first < 0 -> 0
            first == last -> if (neighbours(folded[first], lexicon.folded(i, first))) LIKELY_SLIP else 0
            last == first + 1 && folded[first] == lexicon.folded(i, last) && folded[last] == lexicon.folded(i, first) -> LIKELY_SLIP
            else -> 0
        }
    }

    private fun correction(typed: String, ranked: List<Candidate>, sentenceStart: Boolean): String? {
        // Capitals inside the word (« iPhone », « McGill ») or a digit: written on purpose.
        if (typed.drop(1).any { it.isUpperCase() } && typed != typed.uppercase()) return null
        ranked.filter { it.distance == 0 && it.score >= SAME_LETTERS }.maxByOrNull { it.frequency }
            ?.let { return shape(it.word, typed) }
        if (typed.length < MIN_TO_CORRECT) return null
        // A capital in the middle of a sentence is a name the lists may not hold: left alone.
        if (typed[0].isUpperCase() && !sentenceStart) return null
        val slip = ranked.firstOrNull { it.distance == 1 && it.word.length >= typed.length - 1 && it.frequency >= MIN_CORRECTION_FREQUENCY }
        return slip?.let { shape(it.word, typed) }
    }

    companion object {
        private const val SAME_LETTERS = 1000
        private const val COMPLETION_COST = 4
        private const val SLIP_COST = 70
        private const val LIKELY_SLIP = 30
        private const val SHORTER_COST = 30
        private const val LEARNED_SCORE = 160
        private const val MIN_TO_CORRECT = 3
        private const val LONG_WORD = 6
        private const val MIN_CORRECTION_FREQUENCY = 60
        /** « l' », « qu' », « jusqu' », « aujourd' » : the longest head before an apostrophe. */
        private const val MAX_ELISION = 8

        /**
         * [typed] is this list word: the same letters and accents, and every capital of the list word
         * (`paris` is not `Paris`; `Bonjour` and `BONJOUR` are `bonjour`).
         */
        fun writtenLike(word: String, typed: String): Boolean =
            word == typed || word == typed.lowercase() || (typed.length > 1 && word.uppercase() == typed) ||
                (word.replaceFirstChar { it.uppercase() } == typed)

        /** Gives [word] the capitals of [typed]: all capitals, or a first capital. A list word keeps its own. */
        fun shape(word: String, typed: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}

/**
 * Everyday informal words the shipped lists do not hold (written Québec French, texting). They are
 * never offered, only left alone: correcting `chu` to `chut` is worse than no correction.
 */
object Informal {
    val words: Set<String> = setOf(
        "chu", "chus", "tsé", "tse", "faque", "fak", "pis", "ben", "pantoute", "icitte", "astheure", "asteure", "tantôt",
        "moé", "toé", "nous-autres", "vous-autres", "eux-autres", "tk", "tlm", "stp", "svp", "mdr", "lol", "ok", "okay",
        "oki", "allo", "allô", "coudonc", "voyons", "ouin", "ouais", "nah", "yep", "nope", "btw", "omg", "idk", "thx",
        "pls", "plz", "gonna", "wanna", "gotta", "kinda", "yeah", "yup", "hmm", "haha", "hihi", "hehe", "jaja", "kkk",
    )
}
