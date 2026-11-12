package io.github.remtav.dictee.text

sealed interface Piece {
    data class Text(val text: String) : Piece

    /**
     * A recognised command. [spoken] is the text it came from, typed instead
     * when the command cannot apply (a closing quote with nothing to close).
     * Equality is on [command] only.
     */
    class Cmd(
        val command: Command,
        val spoken: String = "",
        val language: CommandLanguage = CommandLanguage.FRENCH,
    ) : Piece {
        override fun equals(other: Any?) = other is Cmd && other.command == command
        override fun hashCode() = command.hashCode()
        override fun toString() = "Cmd($command, \"$spoken\")"
    }
}

/**
 * Finds the spoken commands of [CommandTable] in a transcribed segment.
 *
 * Trigger rule (DECISIONS.md, issue #15 step 2.2), in doubt the words stay text:
 * - Deletions act only when they are the whole segment.
 * - Line breaks need punctuation, a segment edge or another command on both sides
 *   ("une nouvelle ligne de métro" is text).
 * - A sign is refused when the word before it makes it an ordinary noun or verb
 *   phrase: a determiner, a number, a possessive, a preposition or a modal
 *   ("un point d'interrogation", "il faut ouvrir les guillemets"), with one common
 *   adjective skipped ("un gros point d'interrogation"); also when the next word
 *   continues a noun phrase ("point d'interrogation pour moi").
 * - A homograph ("deux points" without hyphen, "colon") needs punctuation or an
 *   edge on both sides, or another command right after it ("il a dit deux points
 *   ouvrir les guillemets…"): "j'ai marqué deux points" stays text.
 * - The words of a wording must follow each other with only spaces or hyphens
 *   between them ("point, virgule" is not "point-virgule").
 */
object VoiceCommands {
    private const val BOUNDARY_PUNCT = ".,;:!?…"
    private const val SPACES = " \u00A0\u202F\t"

    /** Words that make the next words an ordinary noun or verb phrase. Folded (no accents). */
    private val LEFT_BLOCKERS_FR = setOf(
        // French: determiners, numbers, possessives, prepositions, modals and verbs of placing.
        "le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "ce", "cet", "cette", "ces",
        "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses", "notre", "nos", "votre", "vos", "leur", "leurs",
        "quel", "quelle", "quels", "quelles", "chaque", "aucun", "aucune", "plusieurs", "quelques", "certains",
        "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
        "au", "aux", "a", "en", "par", "pour", "sans", "avec", "sur", "sous", "dans", "entre", "vers", "chez",
        "faut", "fallait", "dois", "doit", "doivent", "devez", "devons", "devrais", "devrait",
        "peux", "peut", "peuvent", "pouvez", "pouvons", "pourrais", "pourrait",
        "veux", "veut", "voulez", "voulons", "voudrais", "vais", "vas", "va", "allons", "allez", "vont",
        "oublie", "oublier", "oublies", "oubliez", "mets", "met", "mettre", "mettez", "mis", "ajoute", "ajouter",
        "comment", "quand", "sais", "sait",
    )
    private val LEFT_BLOCKERS_EN = setOf(
        "the", "a", "an", "this", "that", "these", "those", "my", "your", "his", "her", "its", "our", "their",
        "one", "two", "three", "four", "five", "some", "any", "each", "every", "no", "another", "which", "what",
        "of", "to", "for", "with", "on", "in", "at", "by", "about", "from", "into",
        "put", "puts", "add", "adds", "insert", "type", "use", "uses", "need", "needs", "forgot", "missing",
        "should", "must", "can", "could", "will", "would", "how",
    )

    /** Adjectives that may sit between a determiner and the noun: skipped once. */
    private val PRENOMINAL_FR = setOf(
        "gros", "grosse", "grand", "grande", "petit", "petite", "enorme", "beau", "bel", "belle", "autre",
        "vrai", "vraie", "simple", "seul", "seule", "nouveau", "nouvel", "nouvelle", "dernier", "derniere",
        "premier", "premiere", "immense", "joli", "jolie", "double", "drole",
    )
    private val PRENOMINAL_EN = setOf(
        "big", "little", "small", "huge", "giant", "single", "other", "real", "new", "last", "first", "final",
        "double", "extra", "funny",
    )

    /** Words after a sign that show it is a noun in a sentence ("point d'interrogation pour moi"). */
    private val RIGHT_BLOCKERS_FR = setOf(
        "pour", "de", "d", "qui", "que", "qu", "dans", "sur", "est", "etait", "sont",
    )
    private val RIGHT_BLOCKERS_EN = setOf(
        "for", "of", "in", "on", "is", "was", "that", "which", "at", "to", "means", "looks",
    )

    private class Word(val folded: String, val range: IntRange)

    private class Candidate(val start: Int, val len: Int, val entry: CommandEntry, val form: CommandForm) {
        val end get() = start + len - 1 // last word index
    }

    private sealed interface Side {
        object Edge : Side
        object Punct : Side
        class Neighbour(val candidate: Int) : Side
        class WordAt(val index: Int) : Side
    }

    /** Pieces of [segment], with the commands of [active] (all of them by default). */
    fun parse(segment: String, active: List<CommandEntry> = CommandTable.entries): List<Piece> {
        val raw = Normalize.words(segment)
        if (raw.isEmpty()) return listOf(Piece.Text(segment)).filter { segment.isNotBlank() }
        val words = raw.map { Word(Normalize.fold(it.first), it.second) }
        val folded = words.map { it.folded }

        // Deletions and line breaks said alone.
        for (entry in active) {
            if (entry.command.kind != CommandKind.EDIT && entry.command.kind != CommandKind.LAYOUT) continue
            if (entry.forms.any { it.words == folded && contiguous(segment, words, 0, words.size) }) {
                return listOf(Piece.Cmd(entry.command, segment.trim(), entry.language))
            }
        }

        val candidates = findCandidates(segment, words, active)
        if (candidates.isEmpty()) return textOnly(segment)

        val left = candidates.mapIndexed { k, c -> leftSide(segment, words, candidates, k, c) }
        val right = candidates.mapIndexed { k, c -> rightSide(segment, words, candidates, k, c) }
        val accepted = BooleanArray(candidates.size)
        var changed = true
        while (changed) {
            changed = false
            for (k in candidates.indices) {
                if (!accepted[k] && accept(candidates[k], left[k], right[k], words, accepted)) {
                    accepted[k] = true
                    changed = true
                }
            }
        }
        if (accepted.none { it }) return textOnly(segment)

        val pieces = ArrayList<Piece>()
        var textStart = 0
        var previous: Command? = null
        for (k in candidates.indices) {
            if (!accepted[k]) continue
            val c = candidates[k]
            val first = words[c.start].range.first
            val last = words[c.end].range.last
            addText(pieces, segment.substring(textStart, first), previous, c.entry.command)
            pieces += Piece.Cmd(c.entry.command, segment.substring(first, last + 1), c.entry.language)
            var end = last + 1
            val absorbed = absorbedAfter(c.entry.command)
            while (end < segment.length && (segment[end] in absorbed || segment[end] in SPACES)) end++
            textStart = end
            previous = c.entry.command
        }
        addText(pieces, segment.substring(textStart), previous, null)
        // A segment made only of commands: what is left is the model's punctuation around them.
        if (pieces.filterIsInstance<Piece.Text>().none { t -> t.text.any { it.isLetterOrDigit() } }) {
            return pieces.filterIsInstance<Piece.Cmd>()
        }
        return pieces
    }

    private fun textOnly(segment: String): List<Piece> {
        val pieces = ArrayList<Piece>()
        addText(pieces, segment, null, null)
        return pieces
    }

    /** Longest wording at each word, left to right, without overlap. */
    private fun findCandidates(segment: String, words: List<Word>, active: List<CommandEntry>): List<Candidate> {
        val out = ArrayList<Candidate>()
        var i = 0
        while (i < words.size) {
            var best: Candidate? = null
            for (entry in active) {
                if (entry.command.kind == CommandKind.EDIT) continue
                for (form in entry.forms) {
                    val n = form.words.size
                    if (n == 0 || i + n > words.size) continue
                    if ((0 until n).any { words[i + it].folded != form.words[it] }) continue
                    if (!contiguous(segment, words, i, n)) continue
                    val better = best == null || n > best.len ||
                        // Same words: the form whose hyphen matches the text ("deux-points" vs "deux points").
                        (n == best.len && hyphenMatches(segment, words, i, n, form) && !hyphenMatches(segment, words, i, n, best.form))
                    if (better) best = Candidate(i, n, entry, form)
                }
            }
            if (best != null) {
                out += best
                i += best.len
            } else {
                i++
            }
        }
        return out
    }

    private fun between(segment: String, words: List<Word>, i: Int, n: Int): List<String> =
        (i until i + n - 1).map { segment.substring(words[it].range.last + 1, words[it + 1].range.first) }

    private fun contiguous(segment: String, words: List<Word>, i: Int, n: Int) =
        between(segment, words, i, n).all { gap -> gap.isNotEmpty() && gap.all { it in SPACES || it == '-' || it == '‐' } }

    private fun hyphenMatches(segment: String, words: List<Word>, i: Int, n: Int, form: CommandForm): Boolean {
        val written = between(segment, words, i, n).any { gap -> gap.any { it == '-' || it == '‐' } }
        return written == form.text.contains('-')
    }

    private fun leftSide(segment: String, words: List<Word>, cs: List<Candidate>, k: Int, c: Candidate): Side {
        val gapStart = if (c.start == 0) 0 else words[c.start - 1].range.last + 1
        val gap = segment.substring(gapStart, words[c.start].range.first)
        if (gap.any { it in BOUNDARY_PUNCT || it == '\n' || it in "«»“”\"()" }) return Side.Punct
        if (c.start == 0) return Side.Edge
        if (k > 0 && cs[k - 1].end == c.start - 1) return Side.Neighbour(k - 1)
        return Side.WordAt(c.start - 1)
    }

    private fun rightSide(segment: String, words: List<Word>, cs: List<Candidate>, k: Int, c: Candidate): Side {
        val gapEnd = if (c.end == words.size - 1) segment.length else words[c.end + 1].range.first
        val gap = segment.substring(words[c.end].range.last + 1, gapEnd)
        if (gap.any { it in BOUNDARY_PUNCT || it == '\n' || it in "«»“”\"()" }) return Side.Punct
        if (c.end == words.size - 1) return Side.Edge
        if (k + 1 < cs.size && cs[k + 1].start == c.end + 1) return Side.Neighbour(k + 1)
        return Side.WordAt(c.end + 1)
    }

    private fun detached(side: Side, accepted: BooleanArray) = when (side) {
        Side.Edge, Side.Punct -> true
        is Side.Neighbour -> accepted[side.candidate]
        is Side.WordAt -> false
    }

    private fun accept(c: Candidate, left: Side, right: Side, words: List<Word>, accepted: BooleanArray): Boolean {
        val l = detached(left, accepted)
        val r = detached(right, accepted)
        if (c.entry.command.kind == CommandKind.LAYOUT) return l && r
        if (leftBlocked(c, words, left)) return false
        if (c.form.strictness == Strictness.HOMOGRAPH) {
            return (l && r) || (right is Side.Neighbour && accepted[right.candidate])
        }
        val rightBlockers = if (c.entry.language == CommandLanguage.FRENCH) RIGHT_BLOCKERS_FR else RIGHT_BLOCKERS_EN
        if (right is Side.WordAt && head(words[right.index].folded) in rightBlockers) return false
        return true
    }

    /** The word before (no punctuation between) makes the wording an ordinary phrase. */
    private fun leftBlocked(c: Candidate, words: List<Word>, left: Side): Boolean {
        if (left == Side.Edge || left == Side.Punct) return false
        val french = c.entry.language == CommandLanguage.FRENCH
        val blockers = if (french) LEFT_BLOCKERS_FR else LEFT_BLOCKERS_EN
        val adjectives = if (french) PRENOMINAL_FR else PRENOMINAL_EN
        val i = c.start - 1
        val w = tail(words[i].folded)
        if (w in blockers) return true
        if (w in adjectives && i > 0) return tail(words[i - 1].folded) in blockers
        return false
    }

    /** "d'un" → "un": the part after an elision. */
    private fun tail(w: String) = w.substringAfterLast('\'')

    /** "qu'il" → "qu": the elided part. */
    private fun head(w: String) = if (w.contains('\'')) w.substringBefore('\'') else w

    /** Punctuation right after a command that only marked its boundary (the model's). */
    private fun absorbedAfter(command: Command): String = when (command) {
        Command.CLOSE_QUOTE -> "»”\""
        Command.CLOSE_PARENTHESIS -> ")"
        Command.QUESTION_MARK -> ",.;:?"
        Command.EXCLAMATION_MARK -> ",.;:!"
        Command.COLON, Command.SEMICOLON, Command.OPEN_QUOTE, Command.OPEN_PARENTHESIS -> ",.;:"
        else -> BOUNDARY_PUNCT
    }

    /**
     * Adds the text between two commands. As before step 2.2, commas, colons and
     * semicolons at a segment edge or next to a line break are the model's
     * boundary marks and go; next to a sign, [TextAssembler.sign] decides.
     */
    private fun addText(pieces: MutableList<Piece>, raw: String, previous: Command?, next: Command?) {
        var t = raw.trim()
        if (previous == null || previous.kind == CommandKind.LAYOUT) t = t.trimStart(*",;:".toCharArray()).trim()
        if (next == null || next.kind == CommandKind.LAYOUT) t = t.trimEnd(*",;:".toCharArray()).trim()
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
