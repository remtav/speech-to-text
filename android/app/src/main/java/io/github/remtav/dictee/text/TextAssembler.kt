package io.github.remtav.dictee.text

/**
 * Joins a newly transcribed segment to the text already in the field: spacing
 * and capitalization between segments. Pure function of the text before the
 * cursor and the segment, so the same rules apply to provisional and final text.
 */
object TextAssembler {
    private val SENTENCE_END = setOf('.', '!', '?', '…', '\n')
    private val NO_SPACE_AFTER = setOf('(', '[', '{', '«', '"', '“', '‘', '\'', '’', '/', '-', '\n', '\t', ' ', '\u00A0', '\u202F')
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
        val trimmedBefore = before.trimEnd(' ', '\u00A0', '\u202F')
        val lastChar = before.lastOrNull()
        // An opening quote or parenthesis at the start of a sentence: the sentence still starts.
        val beforeOpening = trimmedBefore.trimEnd('«', '“', '(', '"', ' ', '\u00A0', '\u202F')
        val lastNonSpace = beforeOpening.lastOrNull()

        val startsSentence = beforeOpening.isEmpty() || lastNonSpace in SENTENCE_END
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

    // ---- Dictated signs (issue #15, step 2.2) ----

    /** No-break space (U+00A0): the one every font has. See DECISIONS.md, step 2.2. */
    const val NBSP = ' '

    private const val SPACES = "   \t"

    /**
     * How to insert a dictated sign: delete [delete] characters before the
     * cursor, then commit [insert]. [glueNext]: the next text follows with no
     * space (a time, "13:52").
     */
    data class SignEdit(val delete: Int, val insert: String, val glueNext: Boolean = false)

    /**
     * The edit that puts the sign of [command] after [before], with the
     * spacing of [typography]. The sign replaces the model's own mark right
     * before it (the same sign, or a comma, period, colon or semicolon that only
     * marked a pause), so a sign is never doubled. [next] is the text that will
     * follow, if known (for times). Returns null when the sign makes no sense
     * here: a closing sign at the start of the field or of a line, a closing
     * quote or parenthesis with nothing open. The caller then types the words.
     * Only dictated signs are typeset: the model's punctuation is left as it is.
     */
    fun sign(before: String, command: Command, typography: Typography, next: String? = null): SignEdit? {
        require(command.isSign) { "$command is not a sign" }
        var keep = before.trimEnd { it in SPACES }.length
        val last = before.getOrNull(keep - 1)
        if (last != null && last in absorbedBefore(command) && !ellipsis(before, keep)) {
            keep = before.substring(0, keep - 1).trimEnd { it in SPACES }.length
        }
        val kept = before.substring(0, keep)
        val delete = before.length - keep
        val french = typography != Typography.ENGLISH
        val lastKept = kept.lastOrNull()

        if (command.kind == CommandKind.CLOSING_SIGN && (lastKept == null || lastKept == '\n')) return null
        when (command) {
            Command.CLOSE_QUOTE ->
                if (kept.lastIndexOfAny(charArrayOf('«', '“')) <= kept.lastIndexOfAny(charArrayOf('»', '”'))) return null
            Command.CLOSE_PARENTHESIS -> if (kept.count { it == '(' } <= kept.count { it == ')' }) return null
            else -> Unit
        }
        val space = if (lastKept != null && lastKept !in NO_SPACE_AFTER) " " else ""
        return when (command) {
            Command.COLON -> {
                val time = lastKept?.isDigit() == true && next?.firstOrNull()?.isDigit() == true
                when {
                    time -> SignEdit(delete, ":", glueNext = true)
                    french -> SignEdit(delete, "$NBSP:")
                    else -> SignEdit(delete, ":")
                }
            }
            Command.SEMICOLON -> SignEdit(delete, if (typography == Typography.FRANCE) "$NBSP;" else ";")
            Command.QUESTION_MARK -> SignEdit(delete, if (typography == Typography.FRANCE) "$NBSP?" else "?")
            Command.EXCLAMATION_MARK -> SignEdit(delete, if (typography == Typography.FRANCE) "$NBSP!" else "!")
            Command.CLOSE_QUOTE -> SignEdit(delete, if (french) "$NBSP»" else "”")
            Command.CLOSE_PARENTHESIS -> SignEdit(delete, ")")
            Command.OPEN_QUOTE -> SignEdit(delete, space + if (french) "«$NBSP" else "“")
            Command.OPEN_PARENTHESIS -> SignEdit(delete, "$space(")
            else -> null
        }
    }

    /** "etc.." or "...": a period that belongs to an ellipsis is kept. */
    private fun ellipsis(before: String, end: Int) = before[end - 1] == '.' && before.getOrNull(end - 2) == '.'

    /** The model's mark right before a dictated sign, that the sign replaces. */
    private fun absorbedBefore(command: Command): String = when (command) {
        Command.COLON, Command.SEMICOLON -> ",.;:"
        Command.QUESTION_MARK -> ",.;:?"
        Command.EXCLAMATION_MARK -> ",.;:!"
        Command.CLOSE_QUOTE -> ",;:»”"
        Command.CLOSE_PARENTHESIS -> ",;:)"
        Command.OPEN_QUOTE -> ",«“"
        Command.OPEN_PARENTHESIS -> ",("
        else -> ""
    }
}
