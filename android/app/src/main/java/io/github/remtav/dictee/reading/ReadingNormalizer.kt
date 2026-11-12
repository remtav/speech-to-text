package io.github.remtav.dictee.reading

import io.github.remtav.dictee.text.Normalize
import java.text.Normalizer

/** A scoring token: its normalised [text] and the characters it comes from in the source string. */
data class ReadingToken(val text: String, val range: IntRange)

/**
 * Normalises the reference paragraph and a transcription the same way before the word error rate
 * (issue #15, step 4.1). Pure function of its input and of the [Equivalences] of the text version.
 *
 * 1. **Words** ([baseTokens]): the tokens of [Normalize.werTokens], the WER of `Benchmark` — NFC,
 *    lowercase, typographic apostrophe unified, punctuation and hyphens are separators, an elision
 *    stays attached to its apostrophe (« l'auto » → `l'` `auto`) — but with their character ranges,
 *    so the screen can highlight the misheard words. Accents are kept, as in `Benchmark`: « tantot »
 *    for « tantôt » is a spelling the user would have to fix.
 * 2. **Numbers**: a token made of digits is written in French words (« 60 » → `soixante`), so a
 *    model that writes digits is not penalised against a text written in letters.
 * 3. **Accepted spellings**: the phrases of [Equivalences] (« je suis » → « chu », « tsé » → « t'sais »…),
 *    longest first, each replaced by one token. Both sides go through it, so it is symmetric.
 */
class ReadingNormalizer(val equivalences: Equivalences) {

    fun tokens(s: String): List<ReadingToken> = equivalences.apply(numbersToWords(baseTokens(s)))

    /** Token texts only. */
    fun normalize(s: String): List<String> = tokens(s).map { it.text }

    companion object {
        private val TOKEN = Regex("[\\p{L}\\p{N}]+['’]?")
        private val DIGITS = Regex("[0-9]+")

        /** Same token texts as [Normalize.werTokens] (checked by the tests), with their ranges. */
        fun baseTokens(s: String): List<ReadingToken> = TOKEN.findAll(s).map { m ->
            ReadingToken(Normalizer.normalize(m.value, Normalizer.Form.NFC).lowercase().replace('’', '\''), m.range)
        }.toList()

        /** Replaces each all-digit token by the tokens of its French spelling, with the same range. */
        fun numbersToWords(tokens: List<ReadingToken>): List<ReadingToken> = tokens.flatMap { t ->
            val n = if (DIGITS.matches(t.text) && t.text.length <= 9) t.text.toLongOrNull() else null
            if (n == null) listOf(t)
            else FrenchNumbers.words(n).split(' ', '-').filter { it.isNotEmpty() }.map { ReadingToken(it, t.range) }
        }
    }
}

/**
 * The accepted spellings of a text version, read from `<version>.equivalences.tsv`: per row, the
 * form of the text, its variants and the reason (shown in `DECISIONS.md`). Every phrase of a row
 * (the form itself and each variant) maps to one canonical token, so « je suis », « chus » and
 * « chu » all count as the same word. A phrase may belong to one row only.
 */
class Equivalences(val version: String, val entries: List<Entry>) {

    data class Entry(val reference: String, val variants: List<String>, val reason: String)

    /** Normalised phrase (base tokens, numbers in words) → canonical token. */
    val phrases: Map<List<String>, String>
    private val longest: Int

    init {
        val map = LinkedHashMap<List<String>, String>()
        for (e in entries) {
            val canonical = phraseTokens(e.reference).joinToString(" ")
            require(canonical.isNotEmpty()) { "forme vide" }
            for (p in listOf(e.reference) + e.variants) {
                val key = phraseTokens(p)
                require(key.isNotEmpty()) { "variante vide pour « ${e.reference} »" }
                val previous = map.put(key, canonical)
                require(previous == null || previous == canonical) {
                    "« $p » appartient à deux équivalences : « $previous » et « $canonical »"
                }
            }
        }
        phrases = map
        longest = map.keys.maxOfOrNull { it.size } ?: 0
    }

    /** Replaces, longest first and left to right, each listed phrase by its canonical token. */
    fun apply(tokens: List<ReadingToken>): List<ReadingToken> {
        if (longest == 0) return tokens
        val out = ArrayList<ReadingToken>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            var matched = false
            for (len in minOf(longest, tokens.size - i) downTo 1) {
                val canonical = phrases[tokens.subList(i, i + len).map { it.text }] ?: continue
                out += ReadingToken(canonical, tokens[i].range.first..tokens[i + len - 1].range.last)
                i += len
                matched = true
                break
            }
            if (!matched) out += tokens[i++]
        }
        return out
    }

    companion object {
        private const val VERSION_KEY = "version:"

        private fun phraseTokens(p: String): List<String> =
            ReadingNormalizer.numbersToWords(ReadingNormalizer.baseTokens(p)).map { it.text }

        /** Parses the TSV: `#` comments, one `version:` line, rows `form<TAB>v1 | v2<TAB>reason`. */
        fun parse(content: String): Equivalences {
            var version: String? = null
            val entries = mutableListOf<Entry>()
            for (raw in content.lines()) {
                val line = raw.trimEnd('\r', '\n')
                if (line.isBlank() || line.startsWith("#")) continue
                if (line.startsWith(VERSION_KEY)) {
                    require(version == null) { "version déclarée deux fois" }
                    version = line.removePrefix(VERSION_KEY).trim()
                    continue
                }
                val cols = line.split('\t')
                require(cols.size == 3) { "trois colonnes attendues : $line" }
                val variants = cols[1].split('|').map { it.trim() }.filter { it.isNotEmpty() }
                require(variants.isNotEmpty()) { "aucune variante pour « ${cols[0]} »" }
                require(cols[2].isNotBlank()) { "raison absente pour « ${cols[0]} »" }
                entries += Entry(cols[0].trim(), variants, cols[2].trim())
            }
            return Equivalences(requireNotNull(version) { "ligne « version: » absente" }, entries)
        }
    }
}

/** French spelling of whole numbers (traditional spelling, as in Québec), for [ReadingNormalizer]. */
object FrenchNumbers {
    private val units = listOf(
        "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
        "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf",
    )
    private val tens = listOf("", "", "vingt", "trente", "quarante", "cinquante", "soixante")

    /** 0 to 999 999 999, e.g. 71 → « soixante et onze », 80 → « quatre-vingts », 200 → « deux cents ». */
    fun words(n: Long): String {
        require(n in 0..999_999_999) { "hors limites : $n" }
        if (n == 0L) return units[0]
        val parts = mutableListOf<String>()
        val millions = (n / 1_000_000).toInt()
        val thousands = ((n / 1000) % 1000).toInt()
        val rest = (n % 1000).toInt()
        if (millions > 0) parts += below1000(millions, final = false) + if (millions > 1) " millions" else " million"
        if (thousands > 0) parts += if (thousands == 1) "mille" else below1000(thousands, final = false) + " mille"
        if (rest > 0) parts += below1000(rest, final = true)
        return parts.joinToString(" ")
    }

    /** [final]: the number ends here, so « quatre-vingts » and « deux cents » take their s. */
    private fun below1000(n: Int, final: Boolean): String {
        val h = n / 100
        val r = n % 100
        val parts = mutableListOf<String>()
        if (h > 0) parts += when {
            h == 1 -> "cent"
            r == 0 && final -> "${units[h]} cents"
            else -> "${units[h]} cent"
        }
        if (r > 0) parts += below100(r, final)
        return parts.joinToString(" ")
    }

    private fun below100(n: Int, final: Boolean): String = when {
        n < 20 -> units[n]
        n < 70 -> {
            val t = tens[n / 10]
            when (n % 10) { 0 -> t; 1 -> "$t et un"; else -> "$t-${units[n % 10]}" }
        }
        n < 80 -> if (n == 71) "soixante et onze" else "soixante-${units[n - 60]}"
        n == 80 -> if (final) "quatre-vingts" else "quatre-vingt"
        else -> "quatre-vingt-${units[n - 80]}"
    }
}
