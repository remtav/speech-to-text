package io.github.remtav.dictee.ime.words

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * One language's word list (issue #71): each word with its frequency (1 to 255, from the AOSP lists of
 * AnySoftKeyboard) and whether it may offend. Words are kept sorted by their folded form ([WordFold]), so
 * every word starting with a folded prefix sits in one contiguous range, found by binary search.
 *
 * Packed in a few arrays (one string for all the words, one byte per folded letter) rather than one object per
 * word: a list of 260 000 words then takes a few megabytes.
 */
class Lexicon private constructor(
    private val keys: ByteArray,
    private val keyStart: IntArray,
    private val words: String,
    private val wordStart: IntArray,
    private val freqs: ByteArray,
    private val offensive: BooleanArray,
) {
    val size: Int get() = freqs.size

    fun word(i: Int): String = words.substring(wordStart[i], wordStart[i + 1])
    fun frequency(i: Int): Int = freqs[i].toInt() and 0xFF
    fun isOffensive(i: Int): Boolean = offensive[i]
    fun keyLength(i: Int): Int = keyStart[i + 1] - keyStart[i]
    fun keyChar(i: Int, pos: Int): Char = (keys[keyStart[i] + pos].toInt() and 0xFF).toChar()
    fun key(i: Int): String = String(keys, keyStart[i], keyLength(i), Charsets.ISO_8859_1)

    /** Compares the folded word at [i] with [s]; with [asPrefix], a word that starts with [s] compares equal. */
    private fun compare(i: Int, s: String, asPrefix: Boolean): Int {
        val len = keyLength(i)
        for (p in 0 until minOf(len, s.length)) {
            val d = keyChar(i, p) - s[p]
            if (d != 0) return d
        }
        return if (asPrefix && len >= s.length) 0 else len - s.length
    }

    /** First index whose folded word is not before [s]. */
    private fun lowerBound(s: String, from: Int = 0, to: Int = size): Int {
        var lo = from
        var hi = to
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(mid, s, asPrefix = false) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Indices of the words whose folded form starts with [prefix] (already folded). */
    fun prefixRange(prefix: String): IntRange {
        val start = lowerBound(prefix)
        var lo = start
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(mid, prefix, asPrefix = true) <= 0) lo = mid + 1 else hi = mid
        }
        return start until lo
    }

    /** End of the run in [from, to) whose letter at [pos] is [c]; the run starts at [from]. */
    fun groupEnd(from: Int, to: Int, pos: Int, c: Char): Int {
        var lo = from
        var hi = to
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keyChar(mid, pos) <= c) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The words written exactly like [word], capitals aside. */
    fun contains(word: String): Boolean {
        val folded = WordFold.fold(word)
        val range = prefixRange(folded)
        for (i in range) {
            if (keyLength(i) != folded.length) break
            if (word(i).equals(word, ignoreCase = true)) return true
        }
        return false
    }

    companion object {
        private fun unpacked(input: InputStream): InputStream {
            val buffered = BufferedInputStream(input, 1 shl 16)
            buffered.mark(2)
            val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            return if (gzip) GZIPInputStream(buffered, 1 shl 16) else buffered
        }

        /**
         * Reads a list as written by `tools/listes-de-mots/construire.py`: one `word<TAB>freq[<TAB>x]` per line,
         * gzip-compressed or, if a build tool unpacked it, plain.
         */
        fun read(input: InputStream): Lexicon = unpacked(input).bufferedReader(Charsets.UTF_8).use { r ->
            val words = StringBuilder(1 shl 21)
            val keys = java.io.ByteArrayOutputStream(1 shl 21)
            val wordStart = IntArrayBuilder()
            val keyStart = IntArrayBuilder()
            val freqs = java.io.ByteArrayOutputStream(1 shl 18)
            val offensive = ArrayList<Boolean>(1 shl 18)
            while (true) {
                val line = r.readLine() ?: break
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val word = line.substring(0, tab)
                val tab2 = line.indexOf('\t', tab + 1)
                val freq = (if (tab2 < 0) line.substring(tab + 1) else line.substring(tab + 1, tab2)).toIntOrNull() ?: continue
                val key = WordFold.fold(word)
                if (key.any { it.code > 0x7F }) continue
                wordStart.add(words.length)
                words.append(word)
                keyStart.add(keys.size())
                for (c in key) keys.write(c.code)
                freqs.write(freq.coerceIn(0, 255))
                offensive.add(tab2 >= 0 && line.substring(tab2 + 1) == "x")
            }
            wordStart.add(words.length)
            keyStart.add(keys.size())
            Lexicon(keys.toByteArray(), keyStart.toArray(), words.toString(), wordStart.toArray(), freqs.toByteArray(), offensive.toBooleanArray())
        }
    }

    private class IntArrayBuilder {
        private var a = IntArray(1 shl 18)
        private var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): IntArray = a.copyOf(n)
    }
}
