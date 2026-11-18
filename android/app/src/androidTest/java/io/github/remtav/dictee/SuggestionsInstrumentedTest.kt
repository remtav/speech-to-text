package io.github.remtav.dictee

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.ui.LicencesActivity
import io.github.remtav.dictee.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Issue #71, lot 1: the suggestion bar of the text keys, the automatic correction (off by default), the licences. */
@RunWith(AndroidJUnit4::class)
class SuggestionsInstrumentedTest : ImeTestBase() {

    private fun res(id: String): BySelector = By.res(BuildConfig.APPLICATION_ID, id)

    private fun obj(selector: BySelector, what: String): UiObject2 {
        device.wait(Until.findObject(selector), 5_000)?.let { return it }
        dismissNotRespondingDialogs()
        return device.wait(Until.findObject(selector), 5_000) ?: throw AssertionError("$what not found\n${windowFocus()}")
    }

    private fun clickFresh(find: () -> UiObject2) {
        repeat(2) {
            try { find().click(); return } catch (_: StaleObjectException) { device.waitForIdle(500) }
        }
        find().click()
    }

    /** Letter keys only, looked for inside the keys: a word of the bar is never taken for a key. */
    private fun tap(vararg labels: String) = labels.forEach { l ->
        clickFresh {
            device.wait(Until.findObject(res("letters")), 5_000)?.findObject(By.text(l))
                ?: throw AssertionError("key '$l' not found\n${windowFocus()}")
        }
    }

    private fun space() = clickFresh { obj(res("kb_space"), "space") }

    private fun showTextKeyboard() {
        onMain { service.setTextKeyboardShown(true) }
        obj(res("letters"), "letters shown")
    }

    private fun barWords(): List<String> =
        listOf("suggestion_left", "suggestion_middle", "suggestion_right").mapNotNull { device.findObject(res(it))?.text }.filter { it.isNotEmpty() }

    private fun french() {
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
    }

    /** Each word list opens from the APK under the name the keyboard asks for (CI of PR #119: `*.gz` was renamed). */
    @Test
    fun everyWordListOpensFromTheApk() {
        for (l in io.github.remtav.dictee.ime.KeyLanguage.entries) {
            val name = io.github.remtav.dictee.ime.words.WordLists.assetName(l)
            val lex = context.assets.open(name).use { io.github.remtav.dictee.ime.words.Lexicon.read(it) }
            assertTrue("$name: ${lex.size} words", lex.size > 150_000)
        }
    }

    @Test
    fun barOffersTheWordBeingTypedAndATapWritesIt() {
        french()
        launchFields()
        showTextKeyboard()
        assertNotNull("bar above the keys", device.findObject(res("suggestions")))
        tap("b", "o", "n", "j")
        waitFor("bonjour offered", 10_000, details = { barWords().toString() }) { "bonjour" in barWords() }
        clickFresh { obj(By.text("bonjour").res(BuildConfig.APPLICATION_ID, "suggestion_middle"), "bonjour in the bar") }
        waitFor("bonjour written", 5_000, details = { field1() }) { field1() == "bonjour " }
        waitFor("bar emptied", 5_000, details = { barWords().toString() }) { barWords().isEmpty() }
    }

    @Test
    fun automaticCorrectionIsOffByDefault() {
        french()
        assertFalse(app.settings.autoCorrect)
        assertTrue(app.settings.wordSuggestions)
        launchFields()
        showTextKeyboard()
        tap("e", "c", "o", "l", "e")
        waitFor("école offered", 10_000, details = { barWords().toString() }) { "école" in barWords() }
        space()
        waitFor("typed word kept", 5_000, details = { field1() }) { field1() == "ecole " }
    }

    @Test
    fun automaticCorrectionWhenTurnedOnAndBackspaceUndoesIt() {
        french()
        app.settings.autoCorrect = true
        launchFields()
        showTextKeyboard()
        tap("e", "c", "o", "l", "e")
        waitFor("correction in the middle", 10_000, details = { barWords().toString() }) {
            device.findObject(res("suggestion_middle"))?.text == "école"
        }
        assertEquals("typed word on the left, to keep it", "ecole", device.findObject(res("suggestion_left"))?.text)
        space()
        waitFor("corrected", 5_000, details = { field1() }) { field1() == "école " }
        clickFresh { obj(res("kb_backspace"), "backspace") }
        waitFor("undone", 5_000, details = { field1() }) { field1() == "ecole " }
    }

    @Test
    fun noSuggestionInAPasswordField() {
        french()
        launchFields()
        focus(3)
        obj(res("letters"), "text view in a password field")
        tap("b", "o", "n", "j")
        waitFor("typed", 5_000) { fieldText { it.password } == "bonj" }
        device.waitForIdle(1_000)
        assertTrue(barWords().toString(), barWords().isEmpty())
    }

    @Test
    fun barIsGoneWhenTheSuggestionsAreOff() {
        app.settings.wordSuggestions = false
        launchFields()
        showTextKeyboard()
        assertNull(device.findObject(res("suggestions")))
    }

    @Test
    fun licencesScreenShowsAnySoftKeyboardAndItsLicence() {
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            s.onActivity { a ->
                assertNotNull("licences button in the settings", a.window.decorView.findViewWithTag<android.view.View>(SettingsActivity.LICENCES_TAG))
            }
        }
        ActivityScenario.launch(LicencesActivity::class.java).use { s ->
            s.onActivity { a ->
                val texts = ArrayList<String>()
                fun walk(v: android.view.View) {
                    if (v is android.widget.TextView) texts += v.text.toString()
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(a.window.decorView)
                val all = texts.joinToString("\n")
                assertTrue("AnySoftKeyboard named", "AnySoftKeyboard" in all)
                assertTrue("notice", "Menny Even-Danan" in all)
                assertTrue("full Apache text", "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION" in all)
            }
        }
    }
}
