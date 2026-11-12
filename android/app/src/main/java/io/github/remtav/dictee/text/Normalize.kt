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
}
