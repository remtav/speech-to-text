package io.github.remtav.dictee.ime.emoji

/**
 * Where an emoji begins, counted back from the cursor (issue #80). An emoji is often several characters: a flag is two
 * regional indicators, a family is people joined by U+200D, a skin tone or a variation selector rides on the previous
 * character, a keycap ends with U+20E3. Backspace must remove all of it at once, whatever the field's own rule.
 *
 * The text before the cursor is cut into clusters from the left, and the last one is looked at: cutting from the right
 * cannot tell a flag from the end of two flags.
 */
object EmojiCluster {
    private const val ZWJ = 0x200D
    private const val KEYCAP = 0x20E3
    private const val VS16 = 0xFE0F

    /** How far back to look: the longest family (four people, joiners, tones) is about 25 characters. */
    const val WINDOW = 64

    private fun isRegional(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /** Skin tone, variation selector, tag characters and keycap ride on the character before them. */
    private fun isModifier(cp: Int) =
        cp in 0x1F3FB..0x1F3FF || cp == 0xFE0F || cp == 0xFE0E || cp in 0xE0020..0xE007F || cp == KEYCAP

    private fun isPictographic(cp: Int) =
        cp in 0x1F000..0x1FAFF || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF || cp in 0x2460..0x24FF ||
            cp in 0x25A0..0x27BF || cp in 0x2900..0x2BFF || cp in 0x3030..0x303D ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 ||
            cp == 0x3297 || cp == 0x3299

    /**
     * Number of UTF-16 characters before the cursor that make up the last emoji of [before], or 0 when the last
     * character is not part of an emoji (the caller then deletes one character, as usual).
     */
    fun lengthBefore(before: CharSequence): Int {
        var i = maxOf(0, before.length - WINDOW)
        if (i > 0 && Character.isLowSurrogate(before[i])) i++
        var lastStart = -1
        var lastEmoji = false
        while (i < before.length) {
            val start = i
            val first = Character.codePointAt(before, i)
            i += Character.charCount(first)
            fun peek() = if (i < before.length) Character.codePointAt(before, i) else -1
            var emoji = isPictographic(first) || isRegional(first)
            var cp = first
            if (isRegional(first) && isRegional(peek())) i += Character.charCount(peek())
            while (true) {
                val next = peek()
                if (next < 0) break
                if (isModifier(next)) {
                    // A digit, # or * becomes an emoji only with its keycap or variation selector.
                    if (next == KEYCAP || next == VS16) emoji = true
                    cp = next
                    i += Character.charCount(next)
                } else if (next == ZWJ && emoji) {
                    val afterJoiner = if (i + 1 < before.length) Character.codePointAt(before, i + 1) else -1
                    if (afterJoiner >= 0 && (isPictographic(afterJoiner) || isRegional(afterJoiner))) {
                        i += 1 + Character.charCount(afterJoiner)
                    } else {
                        break
                    }
                } else {
                    break
                }
            }
            lastStart = start
            lastEmoji = emoji && cp != -1
        }
        return if (lastStart >= 0 && lastEmoji) before.length - lastStart else 0
    }
}
