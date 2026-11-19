package io.github.remtav.dictee.ime.words

import io.github.remtav.dictee.ime.KeyboardLayout
import io.github.remtav.dictee.ime.LayoutKind

/** A point on the keyboard, in key widths (issue #71, gesture typing). */
data class KeyPoint(val x: Float, val y: Float) {
    fun distanceTo(o: KeyPoint): Float {
        val dx = x - o.x
        val dy = y - o.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

/**
 * Where the letter keys sit (issue #71, gesture typing): the centre of each letter, in key widths, so the
 * gesture decoder works the same on any screen and in both orientations.
 */
class KeyGeometry(val centres: Map<Char, KeyPoint>) {

    fun centre(c: Char): KeyPoint? = centres[c]

    companion object {
        /**
         * The centres of the letter keys as `LetterKeyboardView` lays them out, measured on screen: [bounds] holds
         * `left, top, right, bottom` of each letter, in pixels. Divided by the width of a top-row key.
         */
        fun fromBounds(bounds: Map<Char, FloatArray>): KeyGeometry? {
            val unit = bounds['e']?.let { it[2] - it[0] }?.takeIf { it > 0f } ?: return null
            return KeyGeometry(bounds.mapValues { (_, b) -> KeyPoint((b[0] + b[2]) / 2f / unit, (b[1] + b[3]) / 2f / unit) })
        }

        /** Height of a key row over the width of a key, on a phone held upright (48 dp over about 41 dp). */
        const val ROW_HEIGHT = 1.2f

        /**
         * The centres computed from [KeyboardLayout], with the weights `LetterKeyboardView` gives its keys: the
         * shorter rows centred, Shift and Backspace one and a half keys wide around the last row. For the tests
         * and for a view not measured yet.
         */
        fun of(kind: LayoutKind, rowHeight: Float = ROW_HEIGHT): KeyGeometry {
            val rows = KeyboardLayout.letterRows(kind)
            val full = rows[0].size.toFloat()
            val centres = HashMap<Char, KeyPoint>()
            rows.forEachIndexed { r, row ->
                val last = r == rows.lastIndex
                val before = if (last) 1.5f else (full - row.size) / 2f
                val weights = before * 2 + row.size
                val unit = full / weights
                row.forEachIndexed { i, k ->
                    centres[k[0]] = KeyPoint((before + i + 0.5f) * unit, (r + 0.5f) * rowHeight)
                }
            }
            return KeyGeometry(centres)
        }
    }
}
