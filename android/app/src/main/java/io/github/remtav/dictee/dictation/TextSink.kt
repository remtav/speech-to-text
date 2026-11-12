package io.github.remtav.dictee.dictation

/** Where dictated text goes: the focused field's InputConnection in the IME. */
interface TextSink {
    fun textBeforeCursor(max: Int): String

    /** Inserts [text] at the cursor, replacing any provisional (composing) text. */
    fun commit(text: String)

    /** Shows [text] as provisional (underlined) text at the cursor. */
    fun setComposing(text: String)

    /** Removes the provisional text, if any. */
    fun clearComposing()

    fun deleteBefore(count: Int)
}

/** In-memory sink for tests and for the comparison screen. */
class StringSink(initial: String = "") : TextSink {
    private val committed = StringBuilder(initial)
    var composing: String = ""
        private set

    val text: String get() = committed.toString()

    @Synchronized override fun textBeforeCursor(max: Int): String = committed.takeLast(max).toString()
    @Synchronized override fun commit(text: String) { composing = ""; committed.append(text) }
    @Synchronized override fun setComposing(text: String) { composing = text }
    @Synchronized override fun clearComposing() { composing = "" }
    @Synchronized override fun deleteBefore(count: Int) {
        val n = count.coerceAtMost(committed.length)
        committed.setLength(committed.length - n)
    }
}
