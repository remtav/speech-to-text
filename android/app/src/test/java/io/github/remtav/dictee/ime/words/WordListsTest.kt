package io.github.remtav.dictee.ime.words

import io.github.remtav.dictee.ime.KeyLanguage
import io.github.remtav.dictee.ime.LayoutKind
import io.github.remtav.dictee.ui.Licences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Issue #71: one word list per keyboard language, read from the assets; the licences shipped with them. */
class WordListsTest {
    private val assets = File(System.getProperty("dictee.assetsDir") ?: "src/main/assets")

    @Test
    fun everyKeyboardLanguageHasItsList() {
        for (l in KeyLanguage.entries) assertTrue(l.name, File(assets, WordLists.assetName(l)).isFile)
    }

    @Test
    fun suggestionsComeFromTheAssetsInTheBackground() {
        val lists = WordLists { name -> File(assets, name).inputStream() }
        val fr = setOf(KeyLanguage.FRENCH)
        // Not read yet: nothing right away, the key is typed as is.
        assertNull(lists.suggestNow(fr, LayoutKind.QWERTY, "ecole"))
        val done = CountDownLatch(1)
        var result: Suggestions? = null
        lists.suggest(fr, LayoutKind.QWERTY, "ecole") { result = it; done.countDown() }
        assertTrue(done.await(60, TimeUnit.SECONDS))
        assertEquals("école", result?.words?.first())
        assertEquals("école", lists.suggestNow(fr, LayoutKind.QWERTY, "ecole")?.autoCorrect)
    }

    @Test
    fun licencesAndNoticeAreShipped() {
        for (c in Licences.components) {
            val text = File(assets, c.licenceAsset).readText()
            assertTrue(c.name, "Apache License" in text && "Version 2.0" in text)
        }
        val ask = Licences.components.single { it.name == "AnySoftKeyboard" }
        assertTrue("copyright of AnySoftKeyboard in the notice", "Menny Even-Danan" in ask.notice)
        assertTrue("changes stated", "Modifications" in ask.notice)
        assertTrue(File(assets, "words/NOTICE.txt").readText().contains("AnySoftKeyboard"))
    }
}
