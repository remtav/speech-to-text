package io.github.remtav.dictee.ime.words

import android.view.inputmethod.InputConnection

/**
 * What the text keys do around the word before the cursor (issue #71): read it, replace it with a suggestion,
 * correct it when a space or a punctuation mark follows, and undo that correction with Backspace.
 *
 * Nothing is kept as composing text: the field holds plain committed text, so the dictation (which composes
 * its partial results) and the keys never fight over the composing region.
 */
class WordTyping {
    private class Correction(val typed: String, val corrected: String, val separator: String)

    /** The last automatic correction, undone by a Backspace typed right after it. */
    private var last: Correction? = null

    /** The word before the cursor, or "" (see [wordBefore]). */
    fun currentWord(ic: InputConnection): String = wordBefore(ic.getTextBeforeCursor(LOOK_BACK, 0) ?: "")

    /** Replaces the word before the cursor ([typed]) with [word] and a space. */
    fun pick(ic: InputConnection, typed: String, word: String) {
        last = null
        ic.beginBatchEdit()
        if (typed.isNotEmpty() && currentWord(ic) == typed) ic.deleteSurroundingText(typed.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
    }

    /**
     * Types [separator] after the word before the cursor, replaced first with [correction] when there is one and
     * the word is still [typed]. Returns true when it corrected the word.
     */
    fun typeSeparator(ic: InputConnection, separator: String, typed: String, correction: String?): Boolean {
        last = null
        if (correction == null || typed.isEmpty() || correction == typed || currentWord(ic) != typed) {
            ic.commitText(separator, 1)
            return false
        }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(typed.length, 0)
        ic.commitText(correction + separator, 1)
        ic.endBatchEdit()
        last = Correction(typed, correction, separator)
        return true
    }

    /**
     * Backspace right after an automatic correction puts back the word as typed (and keeps the separator, so
     * the next Backspace erases it as usual). Returns false when there is no correction to undo.
     */
    fun undoCorrection(ic: InputConnection): Boolean {
        val c = last ?: return false
        last = null
        val after = c.corrected + c.separator
        val before = ic.getTextBeforeCursor(after.length, 0)?.toString() ?: return false
        if (before != after || !ic.getSelectedText(0).isNullOrEmpty()) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(after.length, 0)
        ic.commitText(c.typed + c.separator, 1)
        ic.endBatchEdit()
        return true
    }

    /** Any other key, a new field: the last correction can no longer be undone. */
    fun forget() {
        last = null
    }

    companion object {
        /** Characters read before the cursor: longer than any listed word. */
        const val LOOK_BACK = 48

        /** Characters that end a word and may trigger the automatic correction. */
        fun isSeparator(text: String): Boolean = text.length == 1 && text[0] in " ,.;:!?)]}\"»”…\n"

        private fun inWord(c: Char) = c.isLetter() || c == '\'' || c == '’' || c == '-'

        /**
         * The word that ends at the end of [text]: letters, with apostrophes and hyphens inside ("aujourd'hui",
         * "l'ami", "peut-être"). Empty when the cursor follows anything else, or when the letters are glued to a
         * digit or a sign that is not punctuation ("abc123", "@nom", "site.com/pa"): no suggestion there.
         */
        fun wordBefore(text: CharSequence): String {
            var start = text.length
            while (start > 0 && inWord(text[start - 1])) start--
            while (start < text.length && !text[start].isLetter()) start++
            if (start >= text.length) return ""
            if (start > 0) {
                val p = text[start - 1]
                if (p.isLetterOrDigit() || p in "@#/_\\=&%$+*") return ""
            }
            return text.substring(start)
        }
    }
}
