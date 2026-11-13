package io.github.remtav.dictee

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.ime.LayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Issue #21: the text keyboard (letters, accents, backspace, switch with the microphone, password field). */
@RunWith(AndroidJUnit4::class)
class TextKeyboardInstrumentedTest : ImeTestBase() {

    private fun res(id: String): BySelector = By.res(BuildConfig.APPLICATION_ID, id)

    private fun showTextKeyboard() {
        onMain { service.setTextKeyboardShown(true) }
        assertNotNull("letters shown", device.wait(Until.findObject(res("letters")), 5_000))
    }

    private fun key(label: String): UiObject2 =
        device.wait(Until.findObject(By.text(label).pkg(BuildConfig.APPLICATION_ID)), 5_000)
            ?: throw AssertionError("key '$label' not found")

    private fun tap(vararg labels: String) = labels.forEach { key(it).click() }

    /** Long press on a letter, then a tap on the accent shown above it. */
    private fun accent(base: String, accent: String) {
        key(base).click(900L)
        val a = device.wait(Until.findObject(By.text(accent)), 5_000) ?: throw AssertionError("accent '$accent' not shown")
        a.click()
    }

    @Test
    fun typesElephantWithAccents() {
        launchFields()
        showTextKeyboard()
        accent("e", "é")
        tap("l")
        accent("e", "é")
        tap("p", "h", "a", "n", "t")
        waitFor("elephant typed", 5_000) { field1() == "éléphant" }
    }

    @Test
    fun shiftCapitalisesOneLetterAndLockHoldsThem() {
        launchFields()
        showTextKeyboard()
        device.findObject(By.desc("Majuscule")).click()
        tap("A")
        tap("b")
        waitFor("one capital", 5_000) { field1() == "Ab" }
        // Double tap: locked until tapped again.
        device.findObject(By.desc("Majuscule")).click()
        device.findObject(By.descContains("activée")).click()
        device.wait(Until.hasObject(By.desc("Majuscules verrouillées")), 3_000)
        tap("C", "D")
        waitFor("locked capitals", 5_000) { field1() == "AbCD" }
    }

    @Test
    fun backspaceDeletesOneCharacter() {
        launchFields()
        showTextKeyboard()
        tap("a", "b", "c")
        waitFor("abc", 5_000) { field1() == "abc" }
        device.findObject(res("kb_backspace")).click()
        waitFor("one deleted", 5_000) { field1() == "ab" }
    }

    @Test
    fun azertyLayoutIsAnOption() {
        app.settings.keyboardLayout = LayoutKind.AZERTY
        launchFields()
        showTextKeyboard()
        // 'a' is the first key of the AZERTY top row and 'z' follows it.
        val a = key("a").visibleBounds
        val z = key("z").visibleBounds
        val q = key("q").visibleBounds
        assertTrue("a left of z", a.left < z.left)
        assertTrue("q under a", q.top > a.top)
    }

    @Test
    fun symbolsPageTypesDigitsAndSymbols() {
        launchFields()
        showTextKeyboard()
        device.findObject(res("kb_symbols")).click()
        tap("1", "@", "?")
        waitFor("symbols", 5_000) { field1() == "1@?" }
        device.findObject(res("kb_symbols")).click()
        tap("a")
        waitFor("back to letters", 5_000) { field1() == "1@?a" }
    }

    @Test
    fun switchesToTheTextKeyboardAndBackToTheMicrophone() {
        launchFields()
        assertNotNull("microphone first", device.wait(Until.findObject(res("mic")), 5_000))
        assertNull(device.findObject(res("letters")))
        device.findObject(res("toggle_view")).click()
        assertNotNull("letters", device.wait(Until.findObject(res("letters")), 5_000))
        assertNotNull("microphone stays in the bottom row", device.findObject(res("mic_small")))
        assertTrue(app.settings.textKeyboardShown)
        device.findObject(res("toggle_view")).click()
        assertNotNull("microphone back", device.wait(Until.findObject(res("mic")), 5_000))
        assertFalse(app.settings.textKeyboardShown)
    }

    @Test
    fun lastViewIsRememberedAcrossFields() {
        launchFields()
        showTextKeyboard()
        focus(2)
        assertNotNull(device.wait(Until.findObject(res("letters")), 5_000))
        assertNotNull(device.findObject(res("mic_small")))
    }

    @Test
    fun passwordFieldOpensTheTextViewAndDictationStaysRefused() {
        useAudio("en1")
        useScript("secret.")
        launchFields()
        assertNotNull("microphone view in a normal field", device.wait(Until.findObject(res("mic")), 5_000))
        focus(3)
        assertNotNull("text view in a password field", device.wait(Until.findObject(res("letters")), 5_000))
        assertNull("no toggle in a password field", device.findObject(res("toggle_view")))
        assertFalse("stored view untouched", app.settings.textKeyboardShown)
        tap("p", "w")
        waitFor("typed in the password field", 5_000) { var t = ""; scenario.onActivity { t = it.password.text.toString() }; t == "pw" }
        // The microphone key is disabled: nothing starts.
        onMain { service.onMicTapped() }
        assertNotEquals(DictationState.LISTENING, state())
        // Back to a normal field: the microphone view.
        focus(1)
        assertNotNull(device.wait(Until.findObject(res("mic")), 5_000))
    }

    @Test
    fun typingDuringADictationStopsItFirst() {
        useAudio("en2", realTime = true)
        useScript("God as a direct consequence of the sin.")
        launchFields()
        showTextKeyboard()
        onMain { service.onMicTapped() }
        waitFor("listening", 5_000) { state() == DictationState.LISTENING }
        tap("x")
        assertNotEquals(DictationState.LISTENING, state())
        awaitIdle()
        assertTrue("typed letter present: ${field1()}", "x" in field1())
        assertEquals(1, field1().count { it == 'x' })
    }
}
