package io.github.remtav.dictee.ime.words

import io.github.remtav.dictee.ime.ShiftState

/** How a traced word is written into the field (issue #71, lot 2). */
object GestureText {
    /** [word] with the capitals Shift asks for: a capital first letter once, all capitals when locked. */
    fun withCase(word: String, shift: ShiftState): String = when (shift) {
        ShiftState.OFF -> word
        ShiftState.ONCE -> word.replaceFirstChar { it.uppercaseChar() }
        ShiftState.LOCKED -> word.uppercase()
    }

    /**
     * Whether a space goes in front of a traced word, given the text before the cursor: after a word, a digit or
     * the end of a sentence, yes; at the start of the field, after a space, an opening bracket or quote, an
     * apostrophe or a hyphen ("l'", "peut-"), no.
     */
    fun needsSpaceBefore(before: CharSequence): Boolean {
        val c = before.lastOrNull() ?: return false
        return c.isLetterOrDigit() || c in ".,;:!?)]}»”…"
    }

    /** What is committed for [word] after [before]. */
    fun commit(before: CharSequence, word: String): String = if (needsSpaceBefore(before)) " $word" else word
}
