package io.github.remtav.dictee.ime.words

import java.io.InputStream
import java.text.Normalizer
import java.util.BitSet
import java.util.zip.GZIPInputStream

/**
 * Folding of a character for word search: lowercase, no accent (`É` and `é` give `e`, `œ` gives `o`).
 * One character in, one character out, so a folded word keeps its length.
 * `tools/mots/build_wordlists.py` sorts the shipped lists with the same rule.
 */
object Fold {
    private const val TABLE_SIZE = 0x250
    private val special = mapOf('œ' to 'o', 'æ' to 'a', 'ß' to 's', 'ø' to 'o', 'đ' to 'd', 'ł' to 'l')

    private val table = CharArray(TABLE_SIZE) { code ->
        val low = code.toChar().lowercaseChar()
        special[low] ?: Normalizer.normalize(low.toString(), Normalizer.Form.NFD)[0]
    }

    fun of(c: Char): Char = when {
        c.code < TABLE_SIZE -> table[c.code]
        c == '’' -> '\''
        else -> c.lowercaseChar()
    }

    fun of(word: CharSequence): String = buildString(word.length) { for (c in word) append(of(c)) }
}

/**
 * The words of one language with their frequency (1 to 255, the scale of the AOSP word lists that
 * AnySoftKeyboard ships), held in flat arrays and sorted by folded form. No Android type here.
 */
class Lexicon private constructor(
    private val chars: CharArray,
    private val starts: IntArray,
    private val freq: ByteArray,
    private val offensive: BitSet,
) {
    val size: Int get() = freq.size

    /** Letters `a` to `z` present in each folded word, one bit each: a cheap filter before an edit distance. */
    private val masks = IntArray(size) { i ->
        var m = 0
        for (p in starts[i] until starts[i + 1]) m = m or letterBit(Fold.of(chars[p]))
        m
    }

    fun length(i: Int): Int = starts[i + 1] - starts[i]
    fun word(i: Int): String = String(chars, starts[i], length(i))
    fun frequency(i: Int): Int = freq[i].toInt() and 0xFF
    fun isOffensive(i: Int): Boolean = offensive[i]

    /** Folded character [at] of word [i]. */
    fun folded(i: Int, at: Int): Char = Fold.of(chars[starts[i] + at])

    /** Compares the folded word [i], cut to the length of [key], with [key]: 0 when the word starts with it. */
    private fun compareToPrefix(i: Int, key: CharArray): Int {
        val len = length(i)
        for (p in key.indices) {
            if (p >= len) return -1
            val d = folded(i, p).compareTo(key[p])
            if (d != 0) return d
        }
        return 0
    }

    /** Indices `[first, end)` of the words whose folded form starts with [foldedPrefix]. */
    fun prefixRange(foldedPrefix: CharArray): IntRange {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareToPrefix(mid, foldedPrefix) < 0) lo = mid + 1 else hi = mid
        }
        val first = lo
        hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareToPrefix(mid, foldedPrefix) <= 0) lo = mid + 1 else hi = mid
        }
        return first until lo
    }

    /**
     * Calls [found] with every word whose folded form is within [maxDistance] edits of [folded]
     * (a letter replaced, missing, added, or two neighbours swapped), with that distance.
     */
    fun forEachNear(folded: CharArray, maxDistance: Int, found: (index: Int, distance: Int) -> Unit) {
        var mask = 0
        for (c in folded) mask = mask or letterBit(c)
        val n = folded.size
        val work = Array(3) { IntArray(MAX_WORD + 2) }
        for (i in 0 until size) {
            val len = starts[i + 1] - starts[i]
            if (len < n - maxDistance || len > n + maxDistance || len > MAX_WORD) continue
            // One edit changes at most two letters of the set.
            if (Integer.bitCount(masks[i] xor mask) > 2 * maxDistance) continue
            val d = distance(folded, i, len, maxDistance, work)
            if (d in 0..maxDistance) found(i, d)
        }
    }

    /** Edit distance (with swaps of neighbours) between [a] and the folded word [i], or -1 above [max]. */
    private fun distance(a: CharArray, i: Int, len: Int, max: Int, work: Array<IntArray>): Int {
        var prev2 = work[0]
        var prev = work[1]
        var cur = work[2]
        val n = a.size
        for (j in 0..n) prev[j] = j
        var before = ' '
        for (r in 1..len) {
            val c = folded(i, r - 1)
            cur[0] = r
            var best = r
            for (j in 1..n) {
                var v = prev[j - 1] + if (a[j - 1] == c) 0 else 1
                if (prev[j] + 1 < v) v = prev[j] + 1
                if (cur[j - 1] + 1 < v) v = cur[j - 1] + 1
                if (r > 1 && j > 1 && a[j - 1] == before && a[j - 2] == c && prev2[j - 2] + 1 < v) v = prev2[j - 2] + 1
                cur[j] = v
                if (v < best) best = v
            }
            if (best > max) return -1
            before = c
            val t = prev2
            prev2 = prev
            prev = cur
            cur = t
        }
        return if (prev[n] <= max) prev[n] else -1
    }

    companion object {
        /** Longer words are not kept: nobody types them and the search works in fixed arrays. */
        const val MAX_WORD = 40

        private fun letterBit(c: Char): Int = if (c in 'a'..'z') 1 shl (c - 'a') else 0

        /** Reads a shipped list: gzip, lines `word<TAB>frequency[<TAB>x]`, already sorted by folded form. */
        fun read(gzip: InputStream): Lexicon {
            val entries = ArrayList<Entry>(200_000)
            GZIPInputStream(gzip).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0 || tab > MAX_WORD) continue
                    val tab2 = line.indexOf('\t', tab + 1)
                    val f = line.substring(tab + 1, if (tab2 < 0) line.length else tab2).toIntOrNull() ?: continue
                    entries += Entry(line.substring(0, tab), f, tab2 >= 0)
                }
            }
            return build(entries)
        }

        /** A lexicon from words and frequencies, in any order (tests, small lists). */
        fun of(words: List<Pair<String, Int>>): Lexicon =
            build(
                words.filter { it.first.isNotEmpty() && it.first.length <= MAX_WORD }
                    .map { Entry(it.first, it.second, false) }
                    .sortedWith(compareBy({ Fold.of(it.word) }, { it.word })),
            )

        private class Entry(val word: String, val freq: Int, val offensive: Boolean)

        private fun build(entries: List<Entry>): Lexicon {
            val chars = CharArray(entries.sumOf { it.word.length })
            val starts = IntArray(entries.size + 1)
            val freq = ByteArray(entries.size)
            val offensive = BitSet(entries.size)
            var p = 0
            entries.forEachIndexed { i, e ->
                starts[i] = p
                e.word.toCharArray(chars, p)
                p += e.word.length
                freq[i] = e.freq.coerceIn(1, 255).toByte()
                if (e.offensive) offensive.set(i)
            }
            starts[entries.size] = p
            return Lexicon(chars, starts, freq, offensive)
        }
    }
}
