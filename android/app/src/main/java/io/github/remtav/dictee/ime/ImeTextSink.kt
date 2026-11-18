package io.github.remtav.dictee.ime

import android.view.inputmethod.InputConnection
import io.github.remtav.dictee.dictation.TextSink
import kotlin.math.abs

/**
 * [TextSink] over the focused field's [InputConnection]. Main thread only.
 *
 * Dictated text goes at the end of what this dictation already typed, not at the cursor (issue #81): the
 * person may touch the field while the dictation is still typing, to reread or fix the start. Before each
 * edit the cursor is moved to that end, inside a batch edit, and put back afterwards where the person left
 * it. The end is an offset in the field, moved by the dictation's own edits; text changed before it (a paste,
 * a physical keyboard) is noticed from the characters just before it and the end is found again.
 *
 * The field's selection comes from [onUpdateSelection]. Those calls arrive after the edits, possibly several
 * at once, so the selections this sink's own edits produce are remembered and their echo is not taken for a
 * touch.
 */
class ImeTextSink(
    private val ic: InputConnection,
    /** Selection when the field connected (`EditorInfo.initialSelStart/End`), -1 when unknown. */
    selStart: Int = -1,
    selEnd: Int = selStart,
    /** Sink of the previous connection to the same field: the end of the dictation carries over. */
    previous: ImeTextSink? = null,
) : TextSink {
    private data class Sel(val start: Int, val end: Int)

    private var hasComposing = false
    private var composingLength = 0

    /** End of the dictated text in the field, where the next words go; null until the first edit of a dictation. */
    private var anchor: Int? = null
    /** Characters just before [anchor], to notice text changed before it. */
    private var tail = ""
    /** Last known selection of the field. */
    private var sel = Sel(selStart, selEnd)
    /** Selection the person chose away from the end of the dictation; null while the cursor follows it. */
    private var userSel: Sel? = null
    /** Selections this sink's own edits produced, whose [onUpdateSelection] may still come. */
    private val expected = ArrayDeque<Sel>()

    init {
        if (previous != null && previous.anchor != null) {
            anchor = previous.anchor
            tail = previous.tail
            userSel = sel.takeIf { it.start >= 0 && it != followSel() }
        }
    }

    /** Where the cursor sits when it follows the dictation: after the provisional text. */
    private fun followSel(): Sel {
        val end = (anchor ?: 0) + composingLength
        return Sel(end, end)
    }

    /** A new dictation starts: its text goes where the cursor is now. */
    fun beginDictation() {
        anchor = null
        tail = ""
        userSel = null
    }

    /** From `InputMethodService.onUpdateSelection`. */
    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int) {
        val s = Sel(newSelStart, newSelEnd)
        val i = expected.indexOf(s)
        if (i >= 0) {
            // Echo of this sink's own edits (and of the earlier ones, reported together).
            repeat(i + 1) { expected.removeFirst() }
            sel = s
            return
        }
        expected.clear()
        sel = s
        if (anchor == null) return
        userSel = if (s == followSel()) null else s
    }

    override fun textBeforeCursor(max: Int): String = edit { ic.getTextBeforeCursor(max, 0)?.toString() ?: "" }

    override fun commit(text: String) {
        edit {
            val a = anchor
            val removed = composingLength
            if (hasComposing) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
                hasComposing = false
            }
            composingLength = 0
            ic.commitText(text, 1)
            if (a != null) {
                anchor = a + text.length
                shiftUserSel(a, removed, text.length)
                readTail()
            }
        }
    }

    override fun setComposing(text: String) {
        edit {
            val a = anchor
            val removed = composingLength
            ic.setComposingText(text, 1)
            hasComposing = text.isNotEmpty()
            composingLength = text.length
            if (a != null) shiftUserSel(a, removed, text.length)
        }
    }

    override fun clearComposing() {
        if (!hasComposing) return
        edit {
            val a = anchor
            val removed = composingLength
            ic.setComposingText("", 1)
            ic.finishComposingText()
            hasComposing = false
            composingLength = 0
            if (a != null) shiftUserSel(a, removed, 0)
        }
    }

    override fun deleteBefore(count: Int) {
        if (count <= 0) return
        edit {
            val a = anchor
            ic.deleteSurroundingText(count, 0)
            if (a != null) {
                val n = count.coerceAtMost(a)
                anchor = a - n
                userSel = userSel?.let { u -> Sel(deleted(u.start, a, n), deleted(u.end, a, n)) }
                readTail()
            }
        }
    }

    /**
     * Runs [block] with the cursor at the end of the dictation, in one batch edit, then puts the person's
     * cursor back. Without a known selection the edit happens at the cursor, as any keyboard does.
     */
    private fun <T> edit(block: () -> T): T {
        ic.beginBatchEdit()
        try {
            val tracked = locate()
            val result = block()
            if (tracked) settle()
            return result
        } finally {
            ic.endBatchEdit()
        }
    }

    /** Puts the cursor at the end of the dictation, finding that end first if needed. False when unknown. */
    private fun locate(): Boolean {
        val a = anchor
        if (a == null) return start()
        ic.setSelection(a, a)
        if (tail.isEmpty() || ic.getTextBeforeCursor(tail.length, 0)?.toString() == tail) return true
        // Text changed before the end: find the dictated text again, nearest to where it was.
        val found = relocate(a)
        if (found != null) {
            anchor = found
            if (userSel == followSel()) userSel = null
            ic.setSelection(found, found)
            return true
        }
        // The dictated text itself was changed: go on from the person's cursor, as before.
        val u = userSel ?: sel
        val at = maxOf(u.start, u.end)
        ic.setSelection(at, at)
        sel = Sel(at, at)
        anchor = null
        return start()
    }

    /** First edit of the dictation: it starts at the cursor, replacing a selection like typing does. */
    private fun start(): Boolean {
        if (sel.start < 0 || sel.end < 0) return false
        val begin = minOf(sel.start, sel.end)
        if (sel.start != sel.end) ic.commitText("", 1)
        anchor = begin
        userSel = null
        sel = Sel(begin, begin)
        readTail()
        return true
    }

    private fun relocate(a: Int): Int? {
        val before = ic.getTextBeforeCursor(WINDOW, 0)?.toString() ?: return null
        val after = ic.getTextAfterCursor(WINDOW, 0)?.toString() ?: ""
        val around = before + after
        var best = -1
        var i = around.indexOf(tail)
        while (i >= 0) {
            val end = i + tail.length
            if (best < 0 || abs(end - before.length) < abs(best - before.length)) best = end
            i = around.indexOf(tail, i + 1)
        }
        return if (best < 0) null else a - before.length + best
    }

    /** Cursor back where the person put it, or after the dictated text when they did not move it. */
    private fun settle() {
        val final = userSel ?: followSel()
        ic.setSelection(final.start, final.end)
        if (final != sel) {
            expected.addLast(final)
            while (expected.size > MAX_EXPECTED) expected.removeFirst()
        }
        sel = final
    }

    /** The cursor is at [anchor]: remembers the characters before it. */
    private fun readTail() {
        tail = ic.getTextBeforeCursor(TAIL, 0)?.toString() ?: ""
    }

    /** [removed] characters at [at] were replaced by [added]: the person's selection moves with the text after. */
    private fun shiftUserSel(at: Int, removed: Int, added: Int) {
        val u = userSel ?: return
        fun move(p: Int) = when {
            p <= at -> p
            p >= at + removed -> p - removed + added
            else -> at + added
        }
        userSel = Sel(move(u.start), move(u.end))
    }

    private fun deleted(p: Int, at: Int, n: Int) = when {
        p >= at -> p - n
        p > at - n -> at - n
        else -> p
    }

    companion object {
        /** Characters kept before the end of the dictation to recognize it. */
        private const val TAIL = 32
        /** How far around the old end the dictated text is looked for after a change. */
        private const val WINDOW = 4000
        private const val MAX_EXPECTED = 8
    }
}
