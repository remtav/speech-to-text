package io.github.remtav.dictee.ime.words

/** The text field as the typing logic needs it (the service gives it the `InputConnection`). */
interface TextField {
    /** Up to [n] characters before the cursor. */
    fun textBefore(n: Int): String
    /** Up to [n] characters after the cursor. */
    fun textAfter(n: Int): String
    fun deleteBefore(n: Int)
    fun commit(text: String)
}

/** One entry of the suggestion bar. */
data class StripItem(val text: String, val kind: Kind) {
    enum class Kind {
        /** A word to put in place of what is typed. */
        WORD,
        /** The word that will replace the typed one when the person moves on. */
        CORRECTION,
        /** What was typed, letter for letter: choosing it keeps it and remembers it. */
        TYPED,
    }
}

/**
 * What the keys do to the text when suggestions are on (issue #71): correction when a word ends,
 * its undo with Backspace, the choice of a suggestion, the word of a slide. Pure logic over
 * [TextField]; it trusts the field's text, never its own memory of it, because the person (or a
 * dictation) can change the text at any time.
 */
class Typist(
    private val suggester: Suggester,
    /** Called with a word the person kept against a correction. */
    private val learn: (String) -> Unit = {},
) {
    var autocorrect: Boolean = true

    private class Undo(val typed: String, val corrected: String, val separator: String)
    private class Slide(val inserted: String, val lead: String, val others: List<String>)

    private var undo: Undo? = null
    private var slide: Slide? = null
    /** A word was put in whole (chosen or slid): a space goes before the next letter. */
    private var spaceAfter: String? = null
    /** Words kept as typed since the field opened: not corrected again there. */
    private val kept = HashSet<String>()

    /** New field: nothing of the previous one applies. */
    fun reset() {
        undo = null
        slide = null
        spaceAfter = null
        kept.clear()
    }

    /** The word being typed: the letters and apostrophes that end at the cursor, when no letter follows it. */
    private fun currentWord(field: TextField): String {
        val before = field.textBefore(WINDOW)
        var start = before.length
        while (start > 0 && isWordChar(before[start - 1])) start--
        val word = before.substring(start).trimStart('\'', '’')
        if (word.isEmpty() || field.textAfter(1).firstOrNull()?.isLetter() == true) return ""
        return word
    }

    private fun sentenceStart(field: TextField, wordLength: Int): Boolean {
        val before = field.textBefore(WINDOW).dropLast(wordLength).trimEnd(' ', ' ')
        return before.isEmpty() || before.last() in ".!?…\n"
    }

    private fun pendingUndo(field: TextField): Undo? =
        undo?.takeIf { field.textBefore(WINDOW).endsWith(it.corrected + it.separator) }

    private fun pendingSlide(field: TextField): Slide? =
        slide?.takeIf { field.textBefore(WINDOW).endsWith(it.inserted) }

    /** What the suggestion bar shows for the text as it is now. */
    fun strip(field: TextField): List<StripItem> {
        pendingUndo(field)?.let { return listOf(StripItem(it.typed, StripItem.Kind.TYPED)) }
        pendingSlide(field)?.let { s -> return s.others.take(3).map { StripItem(it, StripItem.Kind.WORD) } }
        val word = currentWord(field)
        if (word.isEmpty()) return emptyList()
        val result = suggester.suggest(word, sentenceStart(field, word.length))
        val correction = result.correction?.takeIf { autocorrect && word !in kept }
        if (correction == null) return result.words.take(3).map { StripItem(it, StripItem.Kind.WORD) }
        return buildList {
            add(StripItem(word, StripItem.Kind.TYPED))
            add(StripItem(correction, StripItem.Kind.CORRECTION))
            result.words.firstOrNull { it != correction }?.let { add(StripItem(it, StripItem.Kind.WORD)) }
        }
    }

    /** A key was typed: a letter, a punctuation mark, a space. */
    fun type(field: TextField, text: String) {
        val owed = spaceAfter
        spaceAfter = null
        slide = null
        if (owed != null && text.firstOrNull()?.isLetterOrDigit() == true && field.textBefore(WINDOW).endsWith(owed)) {
            field.commit(" ")
        }
        undo = null
        if (autocorrect && text.length == 1 && text[0] in ENDS_A_WORD) {
            val word = currentWord(field)
            if (word.isNotEmpty() && word !in kept) {
                val correction = suggester.suggest(word, sentenceStart(field, word.length)).correction
                if (correction != null) {
                    field.deleteBefore(word.length)
                    field.commit(correction)
                    undo = Undo(word, correction, text)
                }
            }
        }
        field.commit(text)
    }

    /** Corrects the word being typed before Enter sends or breaks the line. */
    fun beforeEnter(field: TextField) {
        spaceAfter = null
        slide = null
        undo = null
        if (!autocorrect) return
        val word = currentWord(field)
        if (word.isEmpty() || word in kept) return
        val correction = suggester.suggest(word, sentenceStart(field, word.length)).correction ?: return
        field.deleteBefore(word.length)
        field.commit(correction)
    }

    /**
     * Backspace. Right after a correction it gives back what was typed (and keeps it from now on);
     * right after a slide it removes the whole word. Returns false when it did nothing: the caller
     * then deletes one character as usual.
     */
    fun backspace(field: TextField): Boolean {
        spaceAfter = null
        pendingUndo(field)?.let { u ->
            field.deleteBefore(u.corrected.length + u.separator.length)
            field.commit(u.typed + u.separator)
            keep(u.typed)
            undo = null
            return true
        }
        undo = null
        pendingSlide(field)?.let { s ->
            field.deleteBefore(s.inserted.length)
            slide = null
            return true
        }
        slide = null
        return false
    }

    private fun keep(word: String) {
        kept += word
        learn(word)
    }

    /** A suggestion of the bar was chosen. */
    fun pick(field: TextField, item: StripItem) {
        pendingUndo(field)?.let { if (item.kind == StripItem.Kind.TYPED) { backspace(field); return } }
        undo = null
        pendingSlide(field)?.let { s ->
            field.deleteBefore(s.inserted.length)
            val inserted = s.lead + item.text
            field.commit(inserted)
            slide = Slide(inserted, s.lead, (s.others - item.text) + s.inserted.removePrefix(s.lead))
            spaceAfter = item.text
            return
        }
        slide = null
        val word = currentWord(field)
        if (item.kind == StripItem.Kind.TYPED) {
            if (word.isNotEmpty()) keep(word)
        } else {
            field.deleteBefore(word.length)
            field.commit(item.text)
        }
        spaceAfter = if (item.kind == StripItem.Kind.TYPED) word else item.text
    }

    /**
     * The finger slid over a word: [candidates], best first. The best one is written, after a space
     * when it follows a word, with a capital when [capital] (Shift is on); the others go to the bar.
     */
    fun slide(field: TextField, candidates: List<String>, capital: Boolean) {
        undo = null
        if (candidates.isEmpty()) return
        val shaped = if (capital) candidates.map { w -> w.replaceFirstChar { it.uppercase() } } else candidates
        val last = field.textBefore(1).lastOrNull()
        val lead = if (last != null && (last.isLetterOrDigit() || last in NEEDS_SPACE_AFTER)) " " else ""
        val inserted = lead + shaped[0]
        field.commit(inserted)
        slide = Slide(inserted, lead, shaped.drop(1).distinct())
        spaceAfter = shaped[0]
    }

    companion object {
        /** Characters read before the cursor: more than the longest word plus a separator. */
        private const val WINDOW = 64
        /** Typing one of these ends the word, which is then corrected. */
        private const val ENDS_A_WORD = " .,;:!?)»”\"…\n"
        private const val NEEDS_SPACE_AFTER = ".,;:!?)»”…"

        fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '’'
    }
}
