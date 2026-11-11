package io.github.remtav.dictee.ime

import android.view.inputmethod.InputConnection
import io.github.remtav.dictee.dictation.TextSink

/** [TextSink] over the focused field's [InputConnection]. Main thread only. */
class ImeTextSink(private val ic: InputConnection) : TextSink {
    private var hasComposing = false

    override fun textBeforeCursor(max: Int): String =
        ic.getTextBeforeCursor(max, 0)?.toString() ?: ""

    override fun commit(text: String) {
        ic.beginBatchEdit()
        if (hasComposing) {
            ic.setComposingText("", 1)
            ic.finishComposingText()
            hasComposing = false
        }
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    override fun setComposing(text: String) {
        ic.setComposingText(text, 1)
        hasComposing = text.isNotEmpty()
    }

    override fun clearComposing() {
        if (!hasComposing) return
        ic.setComposingText("", 1)
        ic.finishComposingText()
        hasComposing = false
    }

    override fun deleteBefore(count: Int) {
        if (count > 0) ic.deleteSurroundingText(count, 0)
    }
}
