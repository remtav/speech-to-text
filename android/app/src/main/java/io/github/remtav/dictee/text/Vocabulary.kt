package io.github.remtav.dictee.text

/**
 * Custom vocabulary, one entry per line:
 *
 *     Rémi                    a word or name to favour
 *     cloud code -> Claude Code   a correction applied after transcription
 *
 * Favoured words are passed to models that accept hotwords (Qwen3-ASR) and,
 * for all models, used to correct near misses after transcription: a word
 * within a small edit distance of a favoured word (accents and case ignored)
 * is replaced by it. Corrections ("->" or "=>") replace a word or phrase,
 * case-insensitively, on word boundaries.
 */
class Vocabulary(val favoured: List<String>, val corrections: List<Pair<String, String>>) {

    val isEmpty: Boolean get() = favoured.isEmpty() && corrections.isEmpty()

    fun apply(text: String): String {
        if (isEmpty || text.isBlank()) return text
        var out = text
        for ((from, to) in corrections) {
            val pattern = Regex(
                "(?<![\\p{L}\\p{N}])" + Regex.escape(from) + "(?![\\p{L}\\p{N}])",
                setOf(RegexOption.IGNORE_CASE),
            )
            out = pattern.replace(out) { to } // lambda result is inserted literally
        }
        if (favoured.isNotEmpty()) out = fuzzyFix(out)
        return out
    }

    private val singleWordFavoured = favoured.filter { ' ' !in it.trim() }
    private val foldedFavoured = singleWordFavoured.map { Normalize.fold(it) }

    private fun fuzzyFix(text: String): String {
        val words = Normalize.words(text)
        if (words.isEmpty()) return text
        val sb = StringBuilder(text)
        // Right to left so earlier ranges stay valid.
        for ((word, range) in words.asReversed()) {
            val replacement = bestMatch(word) ?: continue
            sb.replace(range.first, range.last + 1, replacement)
        }
        return sb.toString()
    }

    private fun bestMatch(word: String): String? {
        val f = Normalize.fold(word)
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        for ((i, fav) in foldedFavoured.withIndex()) {
            val target = singleWordFavoured[i]
            if (f == fav) {
                // Same word up to case/accents: restore the favoured spelling.
                return if (word == target) null else target
            }
            if (f.length < MIN_FUZZY_LENGTH || fav.length < MIN_FUZZY_LENGTH) continue
            val allowed = if (fav.length >= 8) 2 else 1
            if (kotlin.math.abs(f.length - fav.length) > allowed) continue
            // First letter must match: keeps "Claude" from eating "cloud" or "fraude".
            if (f[0] != fav[0]) continue
            val d = Levenshtein.distance(f, fav)
            if (d <= allowed && d < bestDist) {
                best = target
                bestDist = d
            }
        }
        return best
    }

    companion object {
        private const val MIN_FUZZY_LENGTH = 5
        private val ARROW = Regex("\\s*(->|=>|→)\\s*")

        val EMPTY = Vocabulary(emptyList(), emptyList())

        fun parse(text: String): Vocabulary {
            val fav = ArrayList<String>()
            val corr = ArrayList<Pair<String, String>>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(ARROW, limit = 2)
                if (parts.size == 2) {
                    val (from, to) = parts.map { it.trim() }
                    if (from.isNotEmpty() && to.isNotEmpty()) {
                        corr += from to to
                        fav += to
                    }
                } else {
                    fav += line
                }
            }
            return Vocabulary(fav.distinct(), corr)
        }
    }
}
