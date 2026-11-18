package io.github.remtav.dictee.ime.words

import java.text.Normalizer

/**
 * The form words are searched under (issue #71): lowercase, accents removed, `œ` written `oe`. The same rule as
 * `fold()` in `tools/listes-de-mots/construire.py`, which sorts the word lists in this order.
 */
object WordFold {
    private val SPECIAL = mapOf('œ' to "oe", 'æ' to "ae", 'ß' to "ss", '’' to "'")

    fun fold(s: CharSequence): String {
        val out = StringBuilder(s.length + 2)
        for (c in s) {
            val low = c.lowercaseChar()
            val special = SPECIAL[low]
            when {
                special != null -> out.append(special)
                low < '\u0080' -> out.append(low)
                else -> for (d in Normalizer.normalize(low.toString(), Normalizer.Form.NFD)) {
                    if (Character.getType(d) != Character.NON_SPACING_MARK.toInt()) out.append(d)
                }
            }
        }
        return out.toString()
    }
}
