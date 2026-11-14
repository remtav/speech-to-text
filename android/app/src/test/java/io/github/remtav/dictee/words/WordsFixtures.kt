package io.github.remtav.dictee.words

import io.github.remtav.dictee.ime.KeyboardLayout
import io.github.remtav.dictee.ime.LayoutKind
import io.github.remtav.dictee.ime.words.GestureDecoder
import io.github.remtav.dictee.ime.words.Lexicon
import io.github.remtav.dictee.ime.words.TextField
import java.io.File

/** The shipped word lists, read once for all the tests of the package. */
object Shipped {
    private val dir = File(System.getProperty("dictee.assetsDir")!!, "words")
    private val cache = HashMap<String, Lexicon>()

    fun file(lang: String) = File(dir, "$lang.tsv.gz")

    @Synchronized
    fun lexicon(lang: String): Lexicon = cache.getOrPut(lang) { file(lang).inputStream().use { Lexicon.read(it) } }
}

/** A text field in memory, with a cursor. */
class FakeField(text: String = "", after: String = "") : TextField {
    var before = text
    var afterCursor = after
    override fun textBefore(n: Int) = before.takeLast(n)
    override fun textAfter(n: Int) = afterCursor.take(n)
    override fun deleteBefore(n: Int) { before = before.dropLast(n) }
    override fun commit(text: String) { before += text }
}

/** The letter keys as a 1080-pixel-wide phone lays them out: 108 x 132 pixels, short rows centred. */
object Geometry {
    const val KEY_W = 108
    const val KEY_H = 132

    fun keys(kind: LayoutKind): List<GestureDecoder.Key> {
        val rows = KeyboardLayout.letterRows(kind)
        return rows.flatMapIndexed { r, row ->
            // The last row sits between Shift and Backspace (1.5 key each).
            val offset = if (r == rows.lastIndex) (KEY_W * 1.5).toInt() + (10 - 3 - row.size) * KEY_W / 2
            else (rows[0].size - row.size) * KEY_W / 2
            row.mapIndexed { c, k ->
                GestureDecoder.Key(k[0], offset + c * KEY_W, r * KEY_H, offset + (c + 1) * KEY_W, (r + 1) * KEY_H)
            }
        }
    }
}
