package io.github.remtav.dictee.ime.words

/**
 * What the suggestion bar shows for the word being typed (issue #71).
 *
 * @property words up to three words, best first; never the typed word itself.
 * @property autoCorrect the word the typed one becomes when a space or a punctuation mark follows, when
 *   automatic correction is on; null when the typed word is a known word or nothing is close enough.
 */
data class Suggestions(val typed: String, val words: List<String>, val autoCorrect: String?, val typedIsWord: Boolean) {
    companion object {
        val NONE = Suggestions("", emptyList(), null, typedIsWord = false)
    }
}

/**
 * Suggestions and corrections over the word lists of the chosen languages (issue #71). Pure Kotlin, no Android
 * type: the word lists are those of AnySoftKeyboard, the search is written here (its own engine is C++).
 *
 * The lists are walked like a tree of folded prefixes, keeping one row of edit distance per prefix (a missing,
 * extra, wrong or swapped letter is one mistake; a neighbouring key half of one; an accent none), and a branch
 * is left as soon as every cell of its row is over the allowed distance.
 */
class Suggester(private val lexicons: List<Lexicon>, private val neighbours: KeyNeighbours = KeyNeighbours.NONE) {

    private class Candidate(val word: String, val score: Float, val distance: Float, val completion: Boolean)

    fun suggest(typed: String, limit: Int = 3): Suggestions {
        if (typed.isEmpty() || lexicons.isEmpty()) return Suggestions.NONE
        val t = WordFold.fold(typed)
        if (t.isEmpty() || t.any { it !in 'a'..'z' && it != '\'' && it != '-' }) return Suggestions(typed, emptyList(), null, false)
        // "l'amu", "peut-etr": when no listed word starts with the whole, the part after the last apostrophe or
        // hyphen is searched alone, and the beginning is kept in front of each suggestion.
        val cut = typed.indexOfLast { it == '\'' || it == '’' || it == '-' }
        if (cut in 1 until typed.length - 1 && lexicons.all { it.prefixRange(t).isEmpty() }) {
            val head = typed.substring(0, cut + 1)
            val tail = suggest(typed.substring(cut + 1), limit)
            return Suggestions(typed, tail.words.map { head + it }, tail.autoCorrect?.let { head + it }, tail.typedIsWord)
        }
        val found = HashMap<String, Candidate>()
        fun add(c: Candidate) {
            val old = found[c.word]
            if (old == null || c.score > old.score) found[c.word] = c
        }
        val maxDistance = maxDistance(t.length)
        for (lex in lexicons) {
            Walk(lex, t, typed, maxDistance, ::add).run()
        }
        val typedIsWord = lexicons.any { it.contains(typed) }
        // Longer words start exactly as typed ("bonj"): the person is most likely still typing one of them, so a
        // word reached through a mistake ("bon", "bonne") comes after them.
        val typing = lexicons.any { lex -> lex.prefixRange(t).let { r -> !r.isEmpty() && lex.keyLength(r.last) > t.length } }
        fun rank(c: Candidate) = if (typing && c.distance > 0f) c.score - TYPING_TYPO_COST else c.score
        val ranked = found.values.sortedWith(compareByDescending<Candidate> { rank(it) }.thenBy { it.word })
        val words = ArrayList<String>(limit)
        for (c in ranked) {
            val shown = matchCase(c.word, typed)
            if (shown == typed || shown in words) continue
            words += shown
            if (words.size == limit) break
        }
        val autoCorrect = if (typedIsWord || !correctable(typed)) null else ranked.firstOrNull {
            !it.completion && it.distance <= maxDistance && matchCase(it.word, typed) != typed
        }?.let { best ->
            // A rare word is not worth replacing what was typed, unless only accents differ.
            if (best.distance > 0f && rank(best) < MIN_AUTOCORRECT_SCORE) null else matchCase(best.word, typed)
        }
        // The automatic correction is always shown, first.
        val shown = if (autoCorrect == null) words else (listOf(autoCorrect) + (words - autoCorrect)).take(limit)
        return Suggestions(typed, shown, autoCorrect, typedIsWord)
    }

    private inner class Walk(
        private val lex: Lexicon,
        private val t: String,
        private val typed: String,
        private val maxDistance: Float,
        private val add: (Candidate) -> Unit,
    ) {
        private val n = t.length
        /** Prefixes as long as the typed word and this close to it also offer their longer words. */
        private val completeDistance = if (n >= 4) 1f else 0f
        private val descend = maxOf(maxDistance, completeDistance)

        fun run() {
            val first = FloatArray(n + 1) { it.toFloat() }
            walk(0, lex.size, 0, first, null, ' ')
        }

        private fun walk(lo: Int, hi: Int, depth: Int, prev: FloatArray, prevPrev: FloatArray?, prevChar: Char) {
            var i = lo
            // The words exactly `depth` letters long were handled by the caller.
            while (i < hi && lex.keyLength(i) == depth) i++
            while (i < hi) {
                val c = lex.keyChar(i, depth)
                val end = lex.groupEnd(i, hi, depth, c)
                val row = FloatArray(n + 1)
                row[0] = depth + 1f
                var best = row[0]
                for (j in 1..n) {
                    val tc = t[j - 1]
                    val sub = when {
                        tc == c -> 0f
                        neighbours.areNeighbours(tc, c) -> NEIGHBOUR_COST
                        else -> 1f
                    }
                    var v = minOf(prev[j] + 1f, row[j - 1] + 1f, prev[j - 1] + sub)
                    if (prevPrev != null && j > 1 && tc == prevChar && t[j - 2] == c) v = minOf(v, prevPrev[j - 2] + SWAP_COST)
                    row[j] = v
                    if (v < best) best = v
                }
                val distance = row[n]
                if (distance <= maxDistance) {
                    var k = i
                    while (k < end && lex.keyLength(k) == depth + 1) {
                        if (!lex.isOffensive(k)) {
                            val word = lex.word(k)
                            val bonus = if (word.equals(typed, ignoreCase = true)) EXACT_BONUS else 0f
                            add(Candidate(word, lex.frequency(k) - DISTANCE_COST * distance + bonus, distance, false))
                        }
                        k++
                    }
                }
                if (depth + 1 == n && distance <= completeDistance) complete(i, end, distance)
                // Every cell over the allowed distance: no longer word of this branch can come back under it.
                if (best <= descend) walk(i, end, depth + 1, row, prev, c)
                i = end
            }
        }

        /** The most frequent longer words of the range [from, to), whose folded form starts like the typed word. */
        private fun complete(from: Int, to: Int, distance: Float) {
            val keep = COMPLETIONS_PER_PREFIX
            val top = IntArray(keep) { -1 }
            for (k in from until to) {
                if (lex.keyLength(k) <= n || lex.isOffensive(k)) continue
                val f = lex.frequency(k)
                if (top[keep - 1] >= 0 && lex.frequency(top[keep - 1]) >= f) continue
                var p = keep - 1
                while (p > 0 && (top[p - 1] < 0 || lex.frequency(top[p - 1]) < f)) { top[p] = top[p - 1]; p-- }
                top[p] = k
            }
            for (k in top) {
                if (k < 0) break
                val extra = lex.keyLength(k) - n
                add(Candidate(lex.word(k), lex.frequency(k) - DISTANCE_COST * distance - COMPLETION_COST - EXTRA_LETTER_COST * extra, distance, true))
            }
        }
    }

    companion object {
        const val NEIGHBOUR_COST = 0.5f
        const val SWAP_COST = 0.6f
        /** Score lost per mistake; frequencies run from 1 to 255 on a log scale. */
        const val DISTANCE_COST = 45f
        const val COMPLETION_COST = 10f
        const val EXTRA_LETTER_COST = 3f
        const val EXACT_BONUS = 40f
        const val TYPING_TYPO_COST = 60f
        const val MIN_AUTOCORRECT_SCORE = 60f
        const val COMPLETIONS_PER_PREFIX = 6

        /** Mistakes allowed for a typed word of [length] letters: none under three letters, two from seven. */
        fun maxDistance(length: Int): Float = when {
            length < 3 -> 0f
            length < 7 -> 1f
            else -> 2f
        }

        /** A word with capitals inside ("iPhone") or all in capitals ("ONU") is left as typed. */
        fun correctable(typed: String): Boolean {
            val upper = typed.count { it.isUpperCase() }
            return upper == 0 || (upper == 1 && typed[0].isUpperCase())
        }

        /** [word] written with the capitals of [typed]: a capital first letter, or all capitals. */
        fun matchCase(word: String, typed: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }
}
