package io.github.remtav.dictee.ime.words

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/** The real word lists of the APK (read once for all tests), and small hand-made ones. */
object WordListsFixture {
    private val dir = File(System.getProperty("dictee.assetsDir") ?: "src/main/assets", "words")
    private val cache = HashMap<String, Lexicon>()

    fun file(code: String) = File(dir, "$code.txt.gz")

    @Synchronized
    fun real(code: String): Lexicon = cache.getOrPut(code) { file(code).inputStream().use { Lexicon.read(it) } }

    /** A list from `word to frequency` pairs (a trailing `!` marks an offensive word), sorted as the build script does. */
    fun small(vararg entries: Pair<String, Int>): Lexicon {
        val rows = entries.map { (w, f) -> Triple(w.removeSuffix("!"), f, w.endsWith("!")) }
            .sortedWith(compareBy<Triple<String, Int, Boolean>>({ WordFold.fold(it.first) }, { it.first }))
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).bufferedWriter().use { out ->
            for ((w, f, x) in rows) out.write("$w\t$f${if (x) "\tx" else ""}\n")
        }
        return Lexicon.read(ByteArrayInputStream(bytes.toByteArray()))
    }
}
