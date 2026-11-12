package io.github.remtav.dictee.text

enum class Command { NEW_LINE, NEW_PARAGRAPH, DELETE_LAST_SENTENCE, DELETE_LAST_WORD }

sealed interface Piece {
    data class Text(val text: String) : Piece
    data class Cmd(val command: Command) : Piece
}

/**
 * Spoken commands in French and English, found in a transcribed segment.
 *
 * - "nouvelle ligne" / "new line" and "nouveau paragraphe" / "new paragraph"
 *   may sit inside a segment, but only between punctuation or segment edges:
 *   "Bonjour, nouvelle ligne, merci." is a command, "une nouvelle ligne de
 *   métro" is not.
 * - Deleting commands act only when they are the whole segment, so dictating
 *   "il faut effacer la dernière phrase du contrat" inserts that text.
 */
object VoiceCommands {
    private fun phrases(vararg p: String) = p.map { s -> s.split(' ') }

    private val inline: Map<Command, List<List<String>>> = mapOf(
        Command.NEW_PARAGRAPH to phrases(
            "nouveau paragraphe", "new paragraph", "next paragraph",
        ),
        Command.NEW_LINE to phrases(
            "nouvelle ligne", "retour a la ligne", "a la ligne", "new line", "newline", "next line",
        ),
    )

    private val standalone: Map<Command, List<List<String>>> = mapOf(
        Command.DELETE_LAST_SENTENCE to phrases(
            "efface la derniere phrase", "effacer la derniere phrase", "effacez la derniere phrase",
            "supprime la derniere phrase", "supprimer la derniere phrase", "supprimez la derniere phrase",
            "delete last sentence", "delete the last sentence", "scratch that",
        ),
        Command.DELETE_LAST_WORD to phrases(
            "efface le dernier mot", "effacer le dernier mot", "effacez le dernier mot",
            "supprime le dernier mot", "supprimer le dernier mot", "supprimez le dernier mot",
            "delete last word", "delete the last word",
        ),
    )

    private const val BOUNDARY_PUNCT = ".,;:!?…"

    fun parse(segment: String): List<Piece> {
        val words = Normalize.words(segment)
        if (words.isEmpty()) return listOf(Piece.Text(segment)).filter { segment.isNotBlank() }
        val folded = words.map { Normalize.fold(it.first) }

        // Whole-segment commands first.
        for ((cmd, list) in standalone) {
            if (list.any { it == folded }) return listOf(Piece.Cmd(cmd))
        }
        for ((cmd, list) in inline) {
            if (list.any { it == folded }) return listOf(Piece.Cmd(cmd))
        }

        val pieces = ArrayList<Piece>()
        var textStart = 0 // char index in segment where pending text begins
        var i = 0
        while (i < words.size) {
            val match = matchInline(folded, i)
            if (match != null) {
                val (cmd, len) = match
                val first = words[i].second.first
                val last = words[i + len - 1].second.last
                if (isBoundary(segment, first - 1, backwards = true) && isBoundary(segment, last + 1, backwards = false)) {
                    addText(pieces, segment.substring(textStart, first))
                    pieces += Piece.Cmd(cmd)
                    // Skip punctuation that only closed the command.
                    var end = last + 1
                    while (end < segment.length && (segment[end] in BOUNDARY_PUNCT || segment[end].isWhitespace())) end++
                    textStart = end
                    i += len
                    continue
                }
            }
            i++
        }
        addText(pieces, segment.substring(textStart))
        return pieces
    }

    private fun matchInline(folded: List<String>, at: Int): Pair<Command, Int>? {
        var best: Pair<Command, Int>? = null
        for ((cmd, list) in inline) {
            for (phrase in list) {
                if (at + phrase.size <= folded.size && folded.subList(at, at + phrase.size) == phrase) {
                    if (best == null || phrase.size > best.second) best = cmd to phrase.size
                }
            }
        }
        return best
    }

    /** True when position [pos] is a segment edge or separated from a word by punctuation. */
    private fun isBoundary(s: String, pos: Int, backwards: Boolean): Boolean {
        var p = pos
        while (p in s.indices && s[p] == ' ') p += if (backwards) -1 else 1
        if (p !in s.indices) return true
        return s[p] in BOUNDARY_PUNCT
    }

    private fun addText(pieces: MutableList<Piece>, raw: String) {
        val t = raw.trim().trimStart(*",;:".toCharArray()).trimEnd(*",;:".toCharArray()).trim()
        if (t.isNotEmpty()) pieces += Piece.Text(t)
    }
}

/** Edits computed from the text before the cursor. */
object TextEdits {
    private val ENDS = setOf('.', '!', '?', '…', '\n')

    /** Number of characters to delete to remove the last sentence before the cursor. */
    fun lastSentenceLength(before: String): Int {
        // Last character of the last sentence, then past its own terminator(s),
        // then back to the previous sentence's terminator: everything after it goes,
        // including the space that followed it, so the cursor lands right after it.
        var i = before.trimEnd().length - 1
        while (i >= 0 && before[i] in ENDS && before[i] != '\n') i--
        while (i >= 0 && before[i] !in ENDS) i--
        return before.length - (i + 1)
    }

    /** Number of characters to delete to remove the last word (and the spaces after it). */
    fun lastWordLength(before: String): Int {
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace() && before[i - 1] != '\n') i--
        while (i > 0 && !before[i - 1].isLetterOrDigit() && !before[i - 1].isWhitespace()) i-- // trailing punctuation
        while (i > 0 && (before[i - 1].isLetterOrDigit() || before[i - 1] == '\'' || before[i - 1] == '’' || before[i - 1] == '-')) i--
        while (i > 0 && before[i - 1] == ' ') i--
        return before.length - i
    }
}
