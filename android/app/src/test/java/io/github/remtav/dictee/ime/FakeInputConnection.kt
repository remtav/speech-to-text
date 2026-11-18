package io.github.remtav.dictee.ime

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo

/**
 * A text field seen through its [InputConnection], with the rules of Android's `BaseInputConnection`:
 * commit and composing text replace the composing region if there is one, the selection otherwise;
 * the cursor lands after the new text. Selection changes are reported like a `TextView` does them: once
 * at the end of a batch edit, or right away outside one. They wait in [pendingUpdates] until the test
 * delivers them, as the system delivers `onUpdateSelection` later, after the edit.
 */
class FakeInputConnection(initial: String = "", cursor: Int = initial.length) : InputConnection {
    private val buffer = StringBuilder(initial)
    var selStart = cursor
        private set
    var selEnd = cursor
        private set
    private var composingStart = -1
    private var composingEnd = -1
    private var batch = 0
    private var reportedStart = cursor
    private var reportedEnd = cursor

    val text: String get() = buffer.toString()
    val composing: String get() = if (composingStart < 0) "" else buffer.substring(composingStart, composingEnd)

    /** Selection changes not yet delivered to the keyboard: (new start, new end). */
    val pendingUpdates = ArrayDeque<Pair<Int, Int>>()

    // ------------------------------------------------------------ what the person does in the field

    /** The person touches the field: the cursor moves there. */
    fun tap(start: Int, end: Int = start) {
        selStart = start
        selEnd = end
        report()
    }

    /** The person (or the app) changes the text without the keyboard, e.g. a paste or a physical keyboard. */
    fun userReplace(start: Int, end: Int, with: String) {
        buffer.replace(start, end, with)
        val delta = with.length - (end - start)
        fun move(p: Int) = when {
            p < 0 -> p
            p >= end -> p + delta
            p > start -> start + with.length
            else -> p
        }
        if (composingStart >= 0) { composingStart = move(composingStart); composingEnd = move(composingEnd) }
        selStart = if (selStart in start..end) start + with.length else move(selStart)
        selEnd = if (selEnd in start..end) start + with.length else move(selEnd)
        report()
    }

    // ------------------------------------------------------------ InputConnection used by the keyboard

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
        buffer.substring((selStart - n).coerceAtLeast(0), selStart)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
        buffer.substring(selEnd, (selEnd + n).coerceAtMost(buffer.length))

    override fun getSelectedText(flags: Int): CharSequence = buffer.substring(selStart, selEnd)

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        replace(text.toString(), composing = false, newCursorPosition)
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        replace(text.toString(), composing = true, newCursorPosition)
        return true
    }

    override fun finishComposingText(): Boolean {
        composingStart = -1
        composingEnd = -1
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val afterEnd = (selEnd + afterLength).coerceAtMost(buffer.length)
        buffer.delete(selEnd, afterEnd)
        val beforeStart = (selStart - beforeLength).coerceAtLeast(0)
        buffer.delete(beforeStart, selStart)
        val removed = selStart - beforeStart
        selStart -= removed
        selEnd -= removed
        if (composingStart >= 0) {
            composingStart = (composingStart - removed).coerceAtLeast(selStart)
            composingEnd = (composingEnd - removed).coerceAtLeast(composingStart)
        }
        report()
        return true
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        if (start < 0 || end > buffer.length) return false
        selStart = start
        selEnd = end
        report()
        return true
    }

    override fun beginBatchEdit(): Boolean {
        batch++
        return true
    }

    override fun endBatchEdit(): Boolean {
        batch--
        report()
        return batch > 0
    }

    private fun replace(text: String, composing: Boolean, newCursorPosition: Int) {
        var start = if (composingStart >= 0) composingStart else selStart
        var end = if (composingStart >= 0) composingEnd else selEnd
        if (start > end) start = end.also { end = start }
        buffer.replace(start, end, text)
        if (composing) {
            composingStart = start
            composingEnd = start + text.length
        } else {
            composingStart = -1
            composingEnd = -1
        }
        val cursor = if (newCursorPosition > 0) start + text.length + newCursorPosition - 1 else start + newCursorPosition
        selStart = cursor.coerceIn(0, buffer.length)
        selEnd = selStart
        report()
    }

    private fun report() {
        if (batch > 0) return
        if (selStart == reportedStart && selEnd == reportedEnd) return
        reportedStart = selStart
        reportedEnd = selEnd
        pendingUpdates.addLast(selStart to selEnd)
    }

    // ------------------------------------------------------------ unused here

    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurroundingText(beforeLength, afterLength)
    override fun setComposingRegion(start: Int, end: Int): Boolean {
        composingStart = start
        composingEnd = end
        return true
    }
    override fun commitCompletion(text: CompletionInfo?): Boolean = false
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false
    override fun performEditorAction(editorAction: Int): Boolean = false
    override fun performContextMenuAction(id: Int): Boolean = false
    override fun sendKeyEvent(event: KeyEvent?): Boolean = false
    override fun clearMetaKeyStates(states: Int): Boolean = false
    override fun reportFullscreenMode(enabled: Boolean): Boolean = false
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun getHandler(): Handler? = null
    override fun closeConnection() {}
    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
}
