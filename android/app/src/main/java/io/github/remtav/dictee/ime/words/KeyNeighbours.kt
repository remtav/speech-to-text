package io.github.remtav.dictee.ime.words

import io.github.remtav.dictee.ime.KeyboardLayout
import io.github.remtav.dictee.ime.LayoutKind
import kotlin.math.abs

/**
 * Letters that touch each other on the keyboard (issue #71): hitting the key next to the intended one is the
 * most common typo, so the suggestions count it as half a mistake.
 */
class KeyNeighbours private constructor(private val pairs: Set<Long>) {

    fun areNeighbours(a: Char, b: Char): Boolean = key(a, b) in pairs

    companion object {
        val NONE = KeyNeighbours(emptySet())

        private fun key(a: Char, b: Char): Long = (minOf(a, b).code.toLong() shl 16) or maxOf(a, b).code.toLong()

        /** Horizontal shift of each letter row, in keys: the rows are staggered as drawn by `LetterKeyboardView`. */
        private val shifts = mapOf(LayoutKind.QWERTY to listOf(0f, 0.5f, 1.5f), LayoutKind.AZERTY to listOf(0f, 0f, 1.5f))

        fun of(kind: LayoutKind): KeyNeighbours {
            val rows = KeyboardLayout.letterRows(kind)
            val shift = shifts.getValue(kind)
            val placed = rows.flatMapIndexed { r, row -> row.mapIndexed { i, k -> Triple(k[0], r, i + shift[r]) } }
            val pairs = HashSet<Long>()
            for ((a, ra, xa) in placed) for ((b, rb, xb) in placed) {
                if (a == b) continue
                val dy = abs(ra - rb)
                val dx = abs(xa - xb)
                if ((dy == 0 && dx <= 1.01f) || (dy == 1 && dx <= 0.76f)) pairs += key(a, b)
            }
            return KeyNeighbours(pairs)
        }
    }
}
