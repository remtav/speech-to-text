package io.github.remtav.dictee.ime.words

import io.github.remtav.dictee.ime.KeyLanguage
import io.github.remtav.dictee.ime.LayoutKind
import io.github.remtav.dictee.ui.Licences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * The Android Gradle plugin unpacks an asset named `*.gz` and drops the suffix when it builds the APK: a list
     * opened under that name is missing on the phone (CI of PR #119, empty bar). The names must not end with `.gz`.
     */
    @Test
    fun assetNamesSurviveThePackaging() {
        for (l in KeyLanguage.entries) assertFalse(WordLists.assetName(l), WordLists.assetName(l).endsWith(".gz"))
    }

    /** A list read as plain text (unpacked) works too, should a build tool unpack it anyway. */
    @Test
    fun aPlainTextListIsReadToo() {
        val lex = Lexicon.read("chat\t180\nchien\t170\n".byteInputStream())
        assertEquals(2, lex.size)
        assertEquals("chat", lex.word(lex.prefixRange("chat").first))
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
            // Each component's own full licence text, by the licence it names (issue #80 added Unicode's).
            val markers = when (c.licence) {
                "Licence Apache, version 2.0" -> listOf("Apache License", "Version 2.0")
                "Licence Unicode, version 3" -> listOf("UNICODE LICENSE V3", "Permission is hereby granted, free of charge")
                else -> throw AssertionError("${c.name}: no check for the licence « ${c.licence} »")
            }
            for (m in markers) assertTrue("${c.name}: « $m » missing from ${c.licenceAsset}", m in text)
        }
        val unicode = Licences.components.single { it.name == "Unicode et CLDR" }
        assertTrue("Unicode's copyright in the notice", "Unicode, Inc." in unicode.notice)
        assertTrue("changes stated", "Modifications" in unicode.notice)
        val ask = Licences.components.single { it.name == "AnySoftKeyboard" }
        assertTrue("copyright of AnySoftKeyboard in the notice", "Menny Even-Danan" in ask.notice)
        assertTrue("changes stated", "Modifications" in ask.notice)
        assertTrue(File(assets, "words/NOTICE.txt").readText().contains("AnySoftKeyboard"))
    }
}
