package io.github.remtav.dictee.ime.words

/**
 * Gesture typing (issue #71, lot 2): the finger slides from letter to letter without lifting, and the word is
 * guessed from the shape of its trace. Pure Kotlin, over the same word lists as the suggestions.
 *
 * Two steps, as in most gesture keyboards (AnySoftKeyboard's `GestureTypingDetector` among them):
 * 1. **Candidates.** The lists are walked like a tree of folded prefixes. A letter is kept only if its key lies
 *    close to the trace, after the point where the previous letter was found; the first letter must be under
 *    the start of the trace, the last one under its end. A whole branch is left as soon as a letter is off the
 *    trace, so only a few hundred words are looked at.
 * 2. **Shape.** Each candidate's ideal trace (straight lines from key centre to key centre) and the real one are
 *    resampled to the same number of points; their mean distance, in key widths, costs points against the
 *    word's frequency.
 */
class GestureDecoder(private val lexicons: List<Lexicon>, private val geometry: KeyGeometry) {

    private class Scored(val word: String, val score: Float)

    /**
     * The words that best fit [trace] (points in key widths, in the order the finger drew them), best first; empty
     * when the trace is too short to be a word.
     */
    fun decode(trace: List<KeyPoint>, limit: Int = 4): List<String> {
        if (trace.size < 2 || lexicons.isEmpty()) return emptyList()
        val path = resample(trace, step = PATH_STEP, maxPoints = MAX_PATH_POINTS)
        if (length(path) < MIN_TRACE_LENGTH) return emptyList()
        val user = resampleCount(path, SHAPE_POINTS)
        val found = HashMap<String, Float>()
        for (lex in lexicons) Walk(lex, path, user) { word, score ->
            val old = found[word]
            if (old == null || score > old) found[word] = score
        }.run()
        return found.entries.map { Scored(it.key, it.value) }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.word })
            .take(limit).map { it.word }
    }

    private inner class Walk(
        private val lex: Lexicon,
        private val path: List<KeyPoint>,
        private val user: List<KeyPoint>,
        private val add: (String, Float) -> Unit,
    ) {
        private val start = path.first()
        private val end = path.last()
        private val letters = CharArray(MAX_WORD)

        fun run() = walk(0, lex.size, 0, 0)

        /** [at]: index in [path] where the previous letter was found. */
        private fun walk(lo: Int, hi: Int, depth: Int, at: Int) {
            if (depth >= MAX_WORD) return
            var i = lo
            while (i < hi && lex.keyLength(i) == depth) i++
            while (i < hi) {
                val c = lex.keyChar(i, depth)
                val groupEnd = lex.groupEnd(i, hi, depth, c)
                val next = locate(c, depth, at)
                if (next >= 0) {
                    letters[depth] = c
                    val centre = geometry.centre(c)
                    // Words that end with this letter: it must be under the end of the trace.
                    if (depth >= 1 && (centre == null || centre.distanceTo(end) <= END_RADIUS)) {
                        var k = i
                        while (k < groupEnd && lex.keyLength(k) == depth + 1) {
                            if (!lex.isOffensive(k)) score(k, depth + 1)
                            k++
                        }
                    }
                    walk(i, groupEnd, depth + 1, next)
                }
                i = groupEnd
            }
        }

        /** Index of [path] where the letter [c] (at [depth] in the word) is found from [at] on, or -1. */
        private fun locate(c: Char, depth: Int, at: Int): Int {
            // An apostrophe or a hyphen has no letter key: it is not traced.
            val centre = geometry.centre(c) ?: return if (depth == 0) -1 else at
            if (depth == 0) return if (centre.distanceTo(start) <= START_RADIUS) 0 else -1
            // A doubled letter ("belle") is one key: the trace does not come back to it.
            if (letters[depth - 1] == c) return at
            for (j in at until path.size) if (centre.distanceTo(path[j]) <= LETTER_RADIUS) return j
            return -1
        }

        private fun score(k: Int, length: Int) {
            val keys = ArrayList<KeyPoint>(length)
            for (p in 0 until length) {
                val centre = geometry.centre(letters[p]) ?: continue
                if (keys.isEmpty() || keys.last() != centre) keys += centre
            }
            if (keys.size < 2) return
            val ideal = resampleCount(keys, SHAPE_POINTS)
            var sum = 0f
            for (p in 0 until SHAPE_POINTS) sum += ideal[p].distanceTo(user[p])
            add(lex.word(k), lex.frequency(k) - SHAPE_COST * (sum / SHAPE_POINTS))
        }
    }

    companion object {
        /** The first letter's key must lie this close to where the finger went down (in key widths). */
        const val START_RADIUS = 1.1f
        const val END_RADIUS = 1.1f
        /** Every other letter's key must lie this close to some point of the trace. */
        const val LETTER_RADIUS = 0.9f
        /** Score lost per key width of mean distance between the trace and the word's ideal trace. */
        const val SHAPE_COST = 160f
        const val SHAPE_POINTS = 48
        const val PATH_STEP = 0.2f
        const val MAX_PATH_POINTS = 400
        /** A trace shorter than this (in key widths) is a tap that moved, not a word. */
        const val MIN_TRACE_LENGTH = 0.6f
        private const val MAX_WORD = 32

        fun length(points: List<KeyPoint>): Float {
            var total = 0f
            for (i in 1 until points.size) total += points[i - 1].distanceTo(points[i])
            return total
        }

        /** [points] with a point every [step] along the line they draw (at most [maxPoints]). */
        fun resample(points: List<KeyPoint>, step: Float, maxPoints: Int): List<KeyPoint> {
            val total = length(points)
            if (total == 0f) return listOf(points.first())
            val count = (total / step).toInt().coerceIn(2, maxPoints)
            return resampleCount(points, count)
        }

        /** [count] points evenly spaced along the line [points] draw, the first and the last included. */
        fun resampleCount(points: List<KeyPoint>, count: Int): List<KeyPoint> {
            val total = length(points)
            if (points.size == 1 || total == 0f) return List(count) { points.first() }
            val out = ArrayList<KeyPoint>(count)
            val interval = total / (count - 1)
            var seg = 1
            var segStart = 0f
            for (n in 0 until count) {
                val target = n * interval
                while (seg < points.size - 1 && segStart + points[seg - 1].distanceTo(points[seg]) < target) {
                    segStart += points[seg - 1].distanceTo(points[seg])
                    seg++
                }
                val a = points[seg - 1]
                val b = points[seg]
                val len = a.distanceTo(b)
                val t = if (len == 0f) 0f else ((target - segStart) / len).coerceIn(0f, 1f)
                out += KeyPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
            return out
        }
    }
}
