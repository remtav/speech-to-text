package io.github.remtav.dictee.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

// What the keys of the keyboard do to the field, apart from typing text.

/** Types [p]; a comma or a period replaces the space just before the cursor. */
internal fun InputConnection.typePunctuation(p: String) {
    val before = getTextBeforeCursor(1, 0)?.toString() ?: ""
    if ((p == "," || p == ".") && before == " ") deleteSurroundingText(1, 0)
    commitText(p, 1)
}

/** Deletes the selection, or the character before the cursor. */
internal fun InputConnection.deleteBackward() {
    val selected = getSelectedText(0)
    if (!selected.isNullOrEmpty()) {
        commitText("", 1)
    } else {
        sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }
}

/** The field's action (Send, Search, Next...) on a single-line field that has one, a new line otherwise. */
internal fun InputConnection.pressEnter(info: EditorInfo?) {
    val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
    val noEnterAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
    val multiLine = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
    if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
        performEditorAction(action)
    } else {
        commitText("\n", 1)
    }
}

/** Identifies a field across `onStartInput` calls: a different key is a different field. */
internal fun EditorInfo.fieldKey() = "$packageName#$fieldId#$fieldName#$inputType#$hintText"

internal fun EditorInfo.isPassword(): Boolean {
    val cls = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return (cls == InputType.TYPE_CLASS_TEXT && (
        variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
}
