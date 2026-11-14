package io.github.remtav.dictee

import android.graphics.Point
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.remtav.dictee.ime.words.StripItem
import io.github.remtav.dictee.stt.DictationLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Issue #71: word suggestions, automatic correction and slide typing on the text keyboard, in French. */
@RunWith(AndroidJUnit4::class)
class WordsInstrumentedTest : ImeTestBase() {

    private fun res(id: String): BySelector = By.res(BuildConfig.APPLICATION_ID, id)

    private fun key(label: String): UiObject2 =
        device.wait(Until.findObject(By.text(label).pkg(BuildConfig.APPLICATION_ID).clazz("android.widget.TextView")), 5_000)
            ?: throw AssertionError("key '$label' not found")

    private fun type(word: String) = word.forEach { key(it.toString()).click() }

    private fun open(slide: Boolean = false, focus: Int? = null) {
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
        app.settings.wordSuggestions = true
        app.settings.autocorrect = true
        app.settings.slideTyping = slide
        launchFields()
        if (focus != null) focus(focus)
        onMain { service.setTextKeyboardShown(true) }
        assertNotNull("letters shown", device.wait(Until.findObject(res("letters")), 5_000))
    }

    private fun ready(slide: Boolean = false) = waitFor("word lists read", 30_000) { onMain { service.wordsReady(slide) } }

    private fun shown(): List<StripItem> = onMain { service.shownSuggestions() }

    @Test
    fun aSuggestionCompletesTheWord() {
        open()
        ready()
        type("bonj")
        waitFor("bonjour offered", 5_000) { shown().any { it.text == "bonjour" } }
        val cell = listOf("sug_0", "sug_1", "sug_2").mapNotNull { device.findObject(res(it)) }.first { it.text == "bonjour" }
        cell.click()
        waitFor("bonjour written", 5_000) { field1() == "bonjour" }
        // The space owed after a chosen word comes with the next letter.
        type("a")
        waitFor("space before the next word", 5_000) { field1() == "bonjour a" }
    }

    @Test
    fun aMistypedWordIsCorrectedAndBackspaceGivesItBack() {
        open()
        ready()
        type("bonkour")
        waitFor("correction announced", 5_000) { shown().any { it.kind == StripItem.Kind.CORRECTION && it.text == "bonjour" } }
        device.findObject(res("kb_space")).click()
        waitFor("corrected", 5_000) { field1() == "bonjour " }
        device.findObject(res("kb_backspace")).click()
        waitFor("given back", 5_000) { field1() == "bonkour " }
        assertEquals(setOf("bonkour"), app.settings.learnedWords)
    }

    @Test
    fun accentsComeBackWithoutALongPress() {
        open()
        ready()
        type("deja")
        device.findObject(res("kb_space")).click()
        waitFor("accents", 5_000) { field1() == "déjà " }
    }

    @Test
    fun nothingIsSuggestedOrCorrectedInAPasswordField() {
        open(focus = 3)
        assertNull("no suggestion bar in a password field", device.findObject(res("suggestions")))
        type("deja")
        device.findObject(res("kb_space")).click()
        waitFor("typed as is", 5_000) { fieldText { it.password } == "deja " }
        assertFalse(onMain { service.wordsReady() })
        assertEquals(emptySet<String>(), app.settings.learnedWords)
    }

    @Test
    fun slidingOverTheLettersWritesTheWord() {
        open(slide = true)
        ready(slide = true)
        val path = "merci".map { key(it.toString()).visibleCenter }.toTypedArray<Point>()
        device.swipe(path, 12)
        waitFor("merci written", 5_000) { field1() == "merci" }
        // A tap still types its letter.
        type("s")
        waitFor("space, then the letter", 5_000) { field1() == "merci s" }
    }
}
