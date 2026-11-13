package io.github.remtav.dictee.text

import java.text.Normalizer
import java.util.Locale

/**
 * Repetition filter for Whisper with a short tail padding (issue #49). With 300 frames of silence
 * instead of 1 000, Whisper turbo is ~30 % faster but sometimes misses the end of a segment and starts
 * the sentence again (« Je m'étonne également. Je m'étonne également. »). This object detects that
 * defect and, for the « cut » correction, removes the repeated text. It is deliberately separate from
 * [io.github.remtav.dictee.cleanup.SafetyCheck], whose rule « a repetition never spans a sentence end »
 * stays as it is, and is called by [io.github.remtav.dictee.stt.SherpaEngine] for the Whisper family only.
 *
 * Detection follows `repetition()` of `android/tools/whisper-turbo-rapide/reprise.py` (issue #40), with
 * guards for short texts (see [Rule] and DECISIONS.md, « Issue #49 »). Words are compared after the same
 * normalisation as that script (`wer.py`): lower case, punctuation removed, hyphens split.
 */
object WhisperRepetition {

    enum class Rule(val label: String) {
        /** Token count at sherpa-onnx's ceiling (6 per second of audio), within one: the decoder ran away. */
        CEILING("plafond"),
        /** The text is X + X, and the first X ends a sentence (« Oui, oui. » and « vous vous » are kept). */
        DOUBLED("X+X"),
        /** A block of at least [MIN_BLOCK] words repeated immediately. */
        REPEATED_BLOCK("bloc répété"),
        /**
         * The end repeats the start after a sentence end, without final punctuation (« Du reste sa mère. Du »).
         * Off below [MIN_WORDS_END_REPEATS_START] words: one matching word out of three is no evidence.
         */
        END_REPEATS_START("fin = début"),
    }

    /** Smallest repeated block for [Rule.REPEATED_BLOCK]: « oui oui », « cinq cinq cinq » are speech. */
    const val MIN_BLOCK = 3

    /** [Rule.END_REPEATS_START] needs at least this many words. */
    const val MIN_WORDS_END_REPEATS_START = 4

    /**
     * sherpa-onnx's token ceiling for [samples] at [sampleRate]: `num_feature_frames / 100 * 6`
     * (offline-whisper-greedy-search-decoder.cc, v2.0.1), with one feature frame per 10 ms of audio,
     * padding excluded. Same formula as `banc_rapide.py`.
     */
    fun tokenCeiling(samples: Int, sampleRate: Int): Int {
        if (sampleRate <= 0) return 0
        val frames = samples.toLong() * 100 / sampleRate
        return (frames / 100.0 * 6).toInt()
    }

    /** The first rule (in [Rule] order) that [text] triggers, or null. [tokens] and [ceiling] feed [Rule.CEILING]. */
    fun detect(text: String, tokens: Int, ceiling: Int): Rule? {
        if (ceiling > 0 && tokens >= ceiling - 1) return Rule.CEILING
        val w = Words(text)
        return when {
            doubledHalf(w) != null -> Rule.DOUBLED
            firstRepeatedBlock(w) != null -> Rule.REPEATED_BLOCK
            endRepeatsStart(w) != null -> Rule.END_REPEATS_START
            else -> null
        }
    }

    /**
     * [text] with the repetition removed (first occurrence kept), or null when no structural rule applies
     * (a ceiling alone gives no position to cut at: the text is then left as it is). Call it once [detect] has
     * fired: it also accepts a restart that stops inside its last word, which [detect] alone does not.
     */
    fun cut(text: String): String? {
        val w = Words(text)
        doubledHalf(w)?.let { return w.keepWords(0 until it) }
        if (firstRepeatedBlock(w) != null) {
            // Collapse every repeated block, then any end that repeats the start.
            var current = text
            repeat(MAX_PASSES) {
                val words = Words(current)
                val block = firstRepeatedBlock(words) ?: return finish(current)
                current = words.keepWords(collapse(words, block))
            }
            return finish(current)
        }
        // Only for cutting, once a rule fired (often the ceiling): the restart may stop inside a word.
        endRepeatsStart(w, cutShortLastWord = true)?.let { k -> return w.keepWords(0 until w.size - k) }
        return null
    }

    private fun finish(text: String): String {
        val w = Words(text)
        val k = endRepeatsStart(w, cutShortLastWord = true) ?: return text
        return w.keepWords(0 until w.size - k)
    }

    // ---------------------------------------------------------------- rules

    /** n / 2 when the words are X + X and the first X ends with sentence-final punctuation. */
    private fun doubledHalf(w: Words): Int? {
        val n = w.size
        if (n < 2 || n % 2 != 0) return null
        val h = n / 2
        if (w.norm.subList(0, h) != w.norm.subList(h, n)) return null
        return if (w.endsSentenceAfterWord(h - 1)) h else null
    }

    /** (start, length) of the first block of at least [MIN_BLOCK] words repeated immediately. */
    private fun firstRepeatedBlock(w: Words): Pair<Int, Int>? {
        val n = w.size
        for (i in 0 until n) {
            var len = MIN_BLOCK
            while (i + 2 * len <= n) {
                if (w.norm.subList(i, i + len) == w.norm.subList(i + len, i + 2 * len)) return i to len
                len++
            }
        }
        return null
    }

    /**
     * Number k of final words that repeat the first k, when the text has no final punctuation, at least
     * [MIN_WORDS_END_REPEATS_START] words, more than 2k words, and the repeat starts after a sentence end.
     * The largest such k (« Du reste sa mère. Du reste » drops two words).
     */
    private fun endRepeatsStart(w: Words, cutShortLastWord: Boolean = false): Int? {
        val n = w.size
        if (n < MIN_WORDS_END_REPEATS_START || w.endsWithSentencePunctuation()) return null
        for (k in n / 2 downTo 1) {
            if (!w.endsSentenceAfterWord(n - k - 1)) continue
            val end = w.norm.subList(n - k, n)
            val start = w.norm.subList(0, k)
            if (n > 2 * k && end == start) return k
            // « … les localités. Le mode … les local »: the restart stops inside its last word (the whole first
            // sentence may be restarted: n = 2k is allowed, the shortened word tells the copy from the original).
            if (cutShortLastWord && end.subList(0, k - 1) == start.subList(0, k - 1) &&
                end[k - 1] != start[k - 1] && start[k - 1].startsWith(end[k - 1])) return k
        }
        return null
    }

    /**
     * Word indices kept once the repeats of [block] are removed; a cut-off restart of the block (its first
     * words, the last one possibly cut short) is dropped too.
     */
    private fun collapse(w: Words, block: Pair<Int, Int>): List<Int> {
        val (i, len) = block
        val unit = w.norm.subList(i, i + len)
        var end = i + len
        while (end + len <= w.size && w.norm.subList(end, end + len) == unit) end += len
        val rest = w.norm.subList(end, w.size)
        // The restart may stop inside a word (« Elle s » for « Elle s'est »).
        val restIsCutOffRestart = rest.isNotEmpty() && rest.size <= len &&
            unit.subList(0, rest.size - 1) == rest.subList(0, rest.size - 1) && unit[rest.size - 1].startsWith(rest.last())
        return (0 until i + len) + if (restIsCutOffRestart) emptyList() else (end until w.size).toList()
    }

    private const val MAX_PASSES = 8
    private const val SENTENCE_END = ".?!…"
    private val CLOSING = charArrayOf('"', '»', ')', ']', '\'', '’', '”')
    private const val OPENING = "«(“["

    // ---------------------------------------------------------------- words

    /**
     * The text split on spaces into raw pieces, each normalised into zero or more words (as `wer.py`):
     * « peut-être » is two words, « … » none. Cuts are made on whole pieces, so the kept text keeps its
     * own spelling and punctuation.
     */
    internal class Words(text: String) {
        private val pieces: List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val norm: List<String>
        /** Piece holding each word. */
        private val pieceOf: List<Int>

        init {
            val words = ArrayList<String>()
            val owners = ArrayList<Int>()
            pieces.forEachIndexed { p, piece ->
                for (word in normalize(piece)) { words += word; owners += p }
            }
            norm = words
            pieceOf = owners
        }

        val size: Int get() = norm.size

        /** Whether the pieces from word [index] up to the next word end with sentence-final punctuation. */
        fun endsSentenceAfterWord(index: Int): Boolean {
            val last = if (index + 1 < size) pieceOf[index + 1] - 1 else pieces.size - 1
            if (last < pieceOf[index]) return false // the next word is in the same piece (« peut-être »)
            return (pieceOf[index]..last).any { endsSentence(pieces[it]) }
        }

        fun endsWithSentencePunctuation(): Boolean = pieces.lastOrNull()?.let(::endsSentence) == true

        private fun endsSentence(piece: String): Boolean =
            piece.trimEnd(*CLOSING).lastOrNull()?.let { it in SENTENCE_END } == true

        /**
         * The text made of the pieces holding the kept words, plus the word-less pieces (« — », « … ») that
         * follow a kept word. A piece is kept whole when any of its words is.
         */
        fun keepWords(kept: Iterable<Int>): String {
            val keep = BooleanArray(pieces.size)
            for (i in kept) keep[pieceOf[i]] = true
            // Word-less pieces follow the fate of the previous piece (or the next one at the start).
            var previous: Boolean? = null
            val owned = BooleanArray(pieces.size).also { for (p in pieceOf) it[p] = true }
            for (p in pieces.indices) {
                if (owned[p]) { previous = keep[p]; continue }
                // An opening mark (« or () goes with the next word; other marks with the previous one.
                val next = (p + 1 until pieces.size).firstOrNull { owned[it] }
                keep[p] = when {
                    pieces[p].all { it in OPENING } && next != null -> keep[next]
                    else -> previous ?: next?.let { keep[it] } ?: true
                }
            }
            return pieces.filterIndexed { p, _ -> keep[p] }.joinToString(" ")
        }
    }

    /** `wer.py`'s `norm`: NFC, lower case, ’ → ', everything but letters, digits, _ ' - and spaces removed, - split. */
    internal fun normalize(s: String): List<String> =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFC).replace('’', '\'')
            .replace(NOT_WORD, " ").replace('-', ' ')
            .split(' ').filter { it.isNotEmpty() }

    private val NOT_WORD = Regex("(?U)[^\\w'\\- ]+")
}
