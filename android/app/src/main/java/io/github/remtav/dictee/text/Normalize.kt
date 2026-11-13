package io.github.remtav.dictee.text

import java.text.Normalizer

object Normalize {
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")

    /** Lowercase, accents removed, typographic apostrophe unified. */
    fun fold(s: String): String =
        DIACRITICS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
            .replace('’', '\'').lowercase()

    /** Words of [s] with their character ranges in [s]. */
    fun words(s: String): List<Pair<String, IntRange>> =
        WORD.findAll(s).map { it.value to it.range }.toList()

    /** Words for WER scoring: lowercase, NFC, punctuation removed, apostrophes split. */
    fun werTokens(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()
            .replace('’', '\'')
            .replace(Regex("[^\\p{L}\\p{N}' ]+"), " ")
            .replace("'", "' ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
}

object Wer {
    data class Result(val errors: Int, val referenceWords: Int) {
        val rate: Double get() = if (referenceWords == 0) 0.0 else errors.toDouble() / referenceWords
    }

    /** Word error rate (substitutions + insertions + deletions) / reference length. */
    fun compute(reference: String, hypothesis: String): Result {
        val r = Normalize.werTokens(reference)
        val h = Normalize.werTokens(hypothesis)
        return Result(Levenshtein.distance(r, h), r.size)
    }
}

object Levenshtein {
    fun <T> distance(a: List<T>, b: List<T>): Int {
        var prev = IntArray(b.size + 1) { it }
        var cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.size]
    }

    fun distance(a: String, b: String): Int = distance(a.toList(), b.toList())

    enum class Op { MATCH, SUBSTITUTE, DELETE, INSERT }

    /**
     * One step of an alignment of [a] (reference) onto [b] (hypothesis): [ai] is the index in a
     * (-1 for an insertion), [bi] the index in b (-1 for a deletion).
     */
    data class Step(val op: Op, val ai: Int, val bi: Int)

    /**
     * A minimal alignment: as many non-[Op.MATCH] steps as [distance] (issue #15, step 4.1: the
     * reading test highlights the reference words that were not heard). Full matrix, so meant for
     * short sequences (a paragraph). Ties prefer a substitution, then a deletion, then an insertion.
     */
    fun <T> align(a: List<T>, b: List<T>): List<Step> {
        val d = Array(a.size + 1) { i -> IntArray(b.size + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.size) for (j in 1..b.size) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j - 1] + cost, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        val steps = ArrayList<Step>(maxOf(a.size, b.size))
        var i = a.size
        var j = b.size
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1)) {
                steps += Step(if (a[i - 1] == b[j - 1]) Op.MATCH else Op.SUBSTITUTE, i - 1, j - 1)
                i--; j--
            } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                steps += Step(Op.DELETE, i - 1, -1)
                i--
            } else {
                steps += Step(Op.INSERT, -1, j - 1)
                j--
            }
        }
        return steps.asReversed()
    }
}
