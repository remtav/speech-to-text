/*
 * Kotlin port of AnySoftKeyboard's GestureTypingDetector
 * (ime/app/src/main/java/com/anysoftkeyboard/gesturetyping/GestureTypingDetector.java,
 * commit 1ee43e7f037b905e1c762e3beceac1c0178797b6).
 *
 * Copyright (c) AnySoftKeyboard contributors. Licensed under the Apache License, Version 2.0:
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Changes for Kalmia Blabla: Kotlin, no RxJava (the caller builds it off the main thread), words read
 * from [Lexicon] and kept in flat arrays, the two proximity penalties expressed in key widths instead
 * of pixels, and a minimum word frequency.
 */
package io.github.remtav.dictee.ime.words

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds the words a finger slid over (issue #71). Every word of the lists has a path, from the centre
 * of each of its letters to the next, reduced to its corners; the finger's path is reduced the same
 * way and compared with the paths of the words that start near where it started.
 *
 * Building it walks every word once: do it off the main thread. One instance per keyboard geometry.
 */
class GestureDecoder(
    private val keys: List<Key>,
    private val lexicons: List<Lexicon>,
    /** How much one point of word frequency is worth, in pixels of path distance. */
    private val frequencyFactor: Double,
    /** Two points of the finger's path closer than this are one point (pixels). */
    minPointDistance: Int,
    /** A word may start on a key this far from where the finger started (pixels). */
    startKeyProximityThreshold: Int,
    /** Rarer words are not offered: they would rarely be the word meant. */
    minFrequency: Int = MIN_FREQUENCY,
    private val maxSuggestions: Int = 15,
) {
    /** A letter key: its character (lowercase, no accent) and its rectangle in the keyboard view. */
    class Key(val char: Char, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        fun isInside(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

        /** Squared distance from the point to the closest point of the key. */
        fun squaredDistanceFrom(x: Int, y: Int): Int {
            val dx = x.coerceIn(left, right) - x
            val dy = y.coerceIn(top, bottom) - y
            return dx * dx + dy * dy
        }
    }

    private val minPointDistanceSquared = minPointDistance * minPointDistance
    private val startThresholdSquared = startKeyProximityThreshold * startKeyProximityThreshold
    private val keyByChar = HashMap<Char, Int>().apply { for ((i, k) in this@GestureDecoder.keys.withIndex()) this[k.char] = i }

    /** The penalties were tuned by AnySoftKeyboard for keys about 100 pixels wide. */
    private val pixelScale: Double = keys.firstOrNull()?.let { 100.0 / max(1, it.right - it.left) } ?: 1.0
    private val startPenalty = PROXIMITY_PENALTY_FACTOR * pixelScale * pixelScale
    private val endPenalty = END_PROXIMITY_PENALTY_FACTOR * pixelScale * pixelScale

    // Every word offered, in parallel arrays: its list, its index there, and its path corners (x, y pairs).
    private val wordLexicon: ByteArray
    private val wordIndex: IntArray
    private val cornerStart: IntArray
    private val corners: ShortArray
    /** Words that start on each key (indices into the arrays above). */
    private val wordsByStartKey: Array<IntArray>

    val wordCount: Int get() = wordIndex.size

    private val gestureXs = IntArray(MAX_GESTURE_LENGTH)
    private val gestureYs = IntArray(MAX_GESTURE_LENGTH)
    private var gestureSize = 0

    init {
        val lex = ArrayList<Byte>()
        val idx = ArrayList<Int>()
        val starts = ArrayList<Int>()
        var all = ShortArray(1 shl 16)
        var used = 0
        val byKey = Array(keys.size) { ArrayList<Int>() }
        val xs = IntArray(Lexicon.MAX_WORD)
        val ys = IntArray(Lexicon.MAX_WORD)
        lexicons.forEachIndexed { l, lexicon ->
            for (i in 0 until lexicon.size) {
                if (lexicon.frequency(i) < minFrequency || lexicon.isOffensive(i) || lexicon.length(i) < 2) continue
                // The points of the word: the centre of each letter's key, a doubled letter counted once.
                var n = 0
                var last = '\u0000'
                var startKey = -1
                for (p in 0 until lexicon.length(i)) {
                    val c = lexicon.folded(i, p)
                    if (c == last) continue
                    val k = keyByChar[c] ?: continue // apostrophe, hyphen: no key to pass over
                    if (n == 0) startKey = k
                    last = c
                    xs[n] = keys[k].centerX
                    ys[n] = keys[k].centerY
                    n++
                }
                if (n == 0) continue
                if (used + 2 * n > all.size) all = all.copyOf(all.size * 2)
                starts += used
                used = pathCorners(xs, ys, n, all, used)
                byKey[startKey] += idx.size
                lex += l.toByte()
                idx += i
            }
        }
        starts += used
        wordLexicon = lex.toByteArray()
        wordIndex = idx.toIntArray()
        cornerStart = starts.toIntArray()
        corners = all.copyOf(used)
        wordsByStartKey = Array(keys.size) { byKey[it].toIntArray() }
    }

    /**
     * Adds a point to the finger's path, if it is far enough from the previous one.
     * Returns the squared distance from the previous point, or 0 when the point was not kept.
     */
    fun addPoint(x: Int, y: Int): Int {
        var distance = 0
        if (gestureSize > 0) {
            val dx = gestureXs[gestureSize - 1] - x
            val dy = gestureYs[gestureSize - 1] - y
            distance = dx * dx + dy * dy
            if (distance <= minPointDistanceSquared) return 0
        }
        if (gestureSize == MAX_GESTURE_LENGTH) return 0
        gestureXs[gestureSize] = x
        gestureYs[gestureSize] = y
        gestureSize++
        return distance
    }

    fun clearGesture() { gestureSize = 0 }

    /** The words that fit the finger's path, best first. */
    fun candidates(): List<String> {
        if (gestureSize < 2) return emptyList()
        val buffer = ShortArray(2 * gestureSize)
        val user = buffer.copyOf(pathCorners(gestureXs, gestureYs, gestureSize, buffer, 0))
        val startX = user[0].toInt()
        val startY = user[1].toInt()
        val endX = user[user.size - 2].toInt()
        val endY = user[user.size - 1].toInt()
        val startKey = keys.indexOfFirst { it.isInside(startX, startY) }

        val weights = ArrayList<Double>(maxSuggestions + 1)
        val found = ArrayList<Int>(maxSuggestions + 1)
        for (k in keys.indices) {
            var proximityPenalty = 0.0
            if (k != startKey) {
                val d = keys[k].squaredDistanceFrom(startX, startY)
                if (d > startThresholdSquared) continue
                proximityPenalty = d * startPenalty
            }
            for (w in wordsByStartKey[k]) {
                val lexicon = lexicons[wordLexicon[w].toInt()]
                val i = wordIndex[w]
                // The finger must end near the word's last letter; ends are less precise than starts.
                var endProximityPenalty = 0.0
                val endKey = keyByChar[lexicon.folded(i, lexicon.length(i) - 1)]
                if (endKey != null) {
                    val d = keys[endKey].squaredDistanceFrom(endX, endY)
                    if (d > startThresholdSquared * END_DIST_THRESHOLD_MULTIPLIER) continue
                    endProximityPenalty = d * endPenalty
                }
                val failFast = if (weights.size >= maxSuggestions) min(MINIMUM_DISTANCE_FILTER, weights[maxSuggestions - 1]) else MINIMUM_DISTANCE_FILTER
                val distance = pathDistance(user, corners, cornerStart[w], cornerStart[w + 1], failFast)
                if (distance > MINIMUM_DISTANCE_FILTER) continue
                val weight = distance - frequencyFactor * lexicon.frequency(i) + proximityPenalty + endProximityPenalty
                var at = 0
                while (at < weights.size && weights[at] <= weight) at++
                if (at < maxSuggestions) {
                    weights.add(at, weight)
                    found.add(at, w)
                    if (weights.size > maxSuggestions) {
                        weights.removeAt(maxSuggestions)
                        found.removeAt(maxSuggestions)
                    }
                }
            }
        }
        return found.map { lexicons[wordLexicon[it].toInt()].word(wordIndex[it]) }.distinct()
    }

    companion object {
        /** Frequencies go from 1 to 255; below this a word is rare (inflected forms, names). */
        const val MIN_FREQUENCY = 50

        private val CURVATURE_THRESHOLD = Math.toRadians(170.0)
        private const val MINIMUM_DISTANCE_FILTER = 1_000_000.0
        private const val DIRECTION_PENALTY_FACTOR = 1.0
        private const val PROXIMITY_PENALTY_FACTOR = 0.000667
        private const val END_PROXIMITY_PENALTY_FACTOR = 0.000333
        private const val END_DIST_THRESHOLD_MULTIPLIER = 25
        private const val MAX_GESTURE_LENGTH = 1024

        /**
         * Writes into [out], from [at], the corners of the path `(xs, ys)[0 until n]`: its two ends and
         * every point where it turns. Returns the position after the last value written.
         */
        internal fun pathCorners(xs: IntArray, ys: IntArray, n: Int, out: ShortArray, at: Int): Int {
            var p = at
            fun keep(i: Int) { out[p++] = xs[i].toShort(); out[p++] = ys[i].toShort() }
            if (n > 0) keep(0)
            for (i in 1 until n - 1) if (hasEnoughCurvature(xs, ys, i)) keep(i)
            if (n > 1) keep(n - 1)
            return p
        }

        internal fun hasEnoughCurvature(xs: IntArray, ys: IntArray, middle: Int): Boolean {
            val ax = (xs[middle - 1] - xs[middle]).toDouble()
            val ay = (ys[middle - 1] - ys[middle]).toDouble()
            val bx = (xs[middle + 1] - xs[middle]).toDouble()
            val by = (ys[middle + 1] - ys[middle]).toDouble()
            val angle = acos((ax * bx + ay * by) / sqrt(ax * ax + ay * ay) / sqrt(bx * bx + by * by))
            return angle <= CURVATURE_THRESHOLD
        }

        /**
         * Distance between the finger's corners and a word's corners `word[from until to]`. Each of the
         * finger's corners is measured to the word corner it has reached; going in another direction
         * than the word costs more (same direction x1, at right angles x2, opposite x3).
         * `Double.MAX_VALUE` as soon as the sum passes [maxDistance].
         */
        internal fun pathDistance(user: ShortArray, word: ShortArray, from: Int, to: Int, maxDistance: Double): Double {
            val wordLength = to - from
            if (user.size < 2 || wordLength == 0) return Double.MAX_VALUE
            if (wordLength > user.size) return Double.MAX_VALUE
            var total = 0.0
            var w = from
            var lastDirX = 0.0
            var lastDirY = 0.0
            var hasLastDir = false
            var u = 0
            while (u < user.size) {
                val ux = user[u].toInt()
                val uy = user[u + 1].toInt()
                var wx = word[w].toInt()
                var wy = word[w + 1].toInt()
                var d = dist(ux, uy, wx, wy)
                if (w < to - 2) {
                    val nx = word[w + 2].toInt()
                    val ny = word[w + 3].toInt()
                    val dn = dist(ux, uy, nx, ny)
                    if (dn < d) { w += 2; d = dn; wx = nx; wy = ny }
                }
                var penalty = 1.0
                if (u >= 2 && w >= from + 2) {
                    val gx = (ux - user[u - 2]).toDouble()
                    val gy = (uy - user[u - 1]).toDouble()
                    penalty = directionPenalty(gx, gy, (wx - word[w - 2]).toDouble(), (wy - word[w - 1]).toDouble())
                    lastDirX = gx
                    lastDirY = gy
                    hasLastDir = true
                }
                total += d * penalty
                if (total > maxDistance) return Double.MAX_VALUE
                u += 2
            }
            // Corners of the word the finger never reached, measured from where it stopped.
            val lastX = user[user.size - 2].toInt()
            val lastY = user[user.size - 1].toInt()
            while (w < to) {
                val wx = word[w].toInt()
                val wy = word[w + 1].toInt()
                var penalty = 1.0
                if (hasLastDir && w >= from + 2) {
                    penalty = directionPenalty(lastDirX, lastDirY, (wx - word[w - 2]).toDouble(), (wy - word[w - 1]).toDouble())
                }
                total += dist(lastX, lastY, wx, wy) * penalty
                if (total > maxDistance) return Double.MAX_VALUE
                w += 2
            }
            return total
        }

        private fun directionPenalty(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val m1 = ax * ax + ay * ay
            val m2 = bx * bx + by * by
            if (m1 == 0.0 || m2 == 0.0) return 1.0
            val cos = ((ax * bx + ay * by) / sqrt(m1 * m2)).coerceIn(-1.0, 1.0)
            return 1.0 + DIRECTION_PENALTY_FACTOR * (1.0 - cos)
        }

        private fun dist(x1: Int, y1: Int, x2: Int, y2: Int): Double {
            val dx = (x2 - x1).toDouble()
            val dy = (y2 - y1).toDouble()
            return sqrt(dx * dx + dy * dy)
        }
    }
}
