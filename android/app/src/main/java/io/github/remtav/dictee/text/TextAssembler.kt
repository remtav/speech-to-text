package io.github.remtav.dictee.text

/**
 * Joins a newly transcribed segment to the text already in the field: spacing
 * and capitalization between segments. Pure function of the text before the
 * cursor and the segment, so the same rules apply to provisional and final text.
 */
object TextAssembler {
    private val SENTENCE_END = setOf('.', '!', '?', '…', '\n')
    private val NO_SPACE_AFTER = setOf('(', '[', '{', '«', '"', '“', '‘', '\'', '’', '/', '-', '\n', '\t', ' ', ' ')
    private val NO_SPACE_BEFORE = setOf(',', '.', ';', ':', '!', '?', '…', ')', ']', '}', '»', '%', '\'', '’')

    /**
     * Common words that a model capitalizes only because it saw the segment as
     * a new sentence. Lowercased when the field is mid-sentence. Proper nouns
     * and "I" are never in this list, so they keep their capital.
     */
    private val LOWERCASE_MID_SENTENCE = setOf(
        // English
        "a", "an", "the", "and", "but", "or", "so", "then", "because", "if", "when", "while", "which",
        "that", "this", "these", "those", "it", "its", "we", "you", "he", "she", "they", "there",
        "is", "are", "was", "were", "of", "to", "in", "on", "at", "for", "with", "as", "also", "maybe",
        "my", "your", "our", "their", "his", "her", "not", "just", "well", "now",
        // French
        "et", "mais", "ou", "donc", "or", "ni", "car", "puis", "alors", "ensuite", "parce", "quand",
        "si", "que", "qui", "le", "la", "les", "un", "une", "des", "du", "de", "au", "aux", "ce", "cet",
        "cette", "ces", "je", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles", "en", "dans",
        "pour", "avec", "sur", "sous", "par", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa",
        "ses", "notre", "votre", "leur", "leurs", "y", "ne", "pas", "aussi", "comme",
        "est", "sont", "était", "puisque", "lorsque", "enfin", "bon",
    )

    /** Text to insert at the cursor for [segment], given the text before the cursor. */
    fun join(before: String, segment: String): String {
        var text = segment.trim()
        if (text.isEmpty()) return ""
        val trimmedBefore = before.trimEnd(' ', ' ')
        val lastChar = before.lastOrNull()
        val lastNonSpace = trimmedBefore.lastOrNull()

        val startsSentence = trimmedBefore.isEmpty() || lastNonSpace in SENTENCE_END
        text = if (startsSentence) capitalizeFirst(text) else lowercaseFirstIfCommon(text)

        val needsSpace = lastChar != null && lastChar !in NO_SPACE_AFTER && text.first() !in NO_SPACE_BEFORE
        return if (needsSpace) " $text" else text
    }

    fun capitalizeFirst(text: String): String {
        val i = text.indexOfFirst { it.isLetter() }
        if (i < 0 || text[i].isUpperCase()) return text
        // Only when the first letter is at the very start (after an opening quote at most).
        if (text.substring(0, i).any { it.isLetterOrDigit() }) return text
        return text.substring(0, i) + text[i].uppercaseChar() + text.substring(i + 1)
    }

    private fun lowercaseFirstIfCommon(text: String): String {
        if (text.isEmpty() || !text[0].isUpperCase()) return text
        val firstWord = text.takeWhile { it.isLetter() || it == '\'' || it == '’' }.replace('’', '\'')
        if (firstWord.length > 1 && firstWord.drop(1).any { it.isUpperCase() }) return text // acronym
        val lower = firstWord.lowercase()
        // "C'est", "J'ai", "L'idée": an elided article or pronoun.
        val elided = lower.contains('\'') && lower.substringBefore('\'') in ELIDED
        return if (lower in LOWERCASE_MID_SENTENCE || elided) {
            text[0].lowercaseChar() + text.substring(1)
        } else {
            text
        }
    }

    private val ELIDED = setOf("c", "j", "l", "d", "qu", "n", "s", "m", "t")
}
