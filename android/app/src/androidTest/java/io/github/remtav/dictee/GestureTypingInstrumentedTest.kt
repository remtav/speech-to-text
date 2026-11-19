package io.github.remtav.dictee

import android.graphics.Point
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.remtav.dictee.ime.LayoutKind
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Issue #71, lot 2: gesture typing on the text keys, with a finger slid by uiautomator from key to key. */
@RunWith(AndroidJUnit4::class)
class GestureTypingInstrumentedTest : ImeTestBase() {

    private fun res(id: String): BySelector = By.res(BuildConfig.APPLICATION_ID, id)

    private fun obj(selector: BySelector, what: String): UiObject2 {
        device.wait(Until.findObject(selector), 5_000)?.let { return it }
        dismissNotRespondingDialogs()
        return device.wait(Until.findObject(selector), 5_000) ?: throw AssertionError("$what not found\n${windowFocus()}")
    }

    private fun showTextKeyboard() {
        onMain { service.setTextKeyboardShown(true) }
        obj(res("letters"), "letters shown")
        awaitLayout(app.settings.keyboardLayout)
    }

    /** Centre of the letter key [c], looked for inside the keys only. */
    private fun keyCentre(c: Char): Point {
        repeat(3) {
            try {
                val key = obj(res("letters"), "letters").findObject(By.text(c.toString()))
                    ?: throw AssertionError("key '$c' not found\n${windowFocus()}")
                return key.visibleCenter
            } catch (_: StaleObjectException) {
                device.waitForIdle(500)
            }
        }
        throw AssertionError("key '$c' kept changing")
    }

    /**
     * Waits until the keys show [kind]: the view kept from the previous test is rebuilt when the field starts, and
     * key positions read before that would be traced on the other layout. On AZERTY, `a` is on the top row.
     */
    private fun awaitLayout(kind: LayoutKind) {
        waitFor("$kind keys", 5_000, details = { windowFocus() }) {
            try {
                (keyCentre('a').y < keyCentre('s').y) == (kind == LayoutKind.AZERTY)
            } catch (_: AssertionError) {
                false
            }
        }
        device.waitForIdle(500)
    }

    /**
     * Slides one finger through the keys of [word], without lifting it. [STEPS] moves from key to key (5 ms each),
     * about a finger's speed: with fewer, each move jumps more than a key and the emulator's input resampling can
     * drop the move on a corner, as no finger does (8 steps turned « maison » into « Mézidon » on the CI).
     */
    private fun trace(word: String) {
        val points = word.map { keyCentre(it) }.fold(ArrayList<Point>()) { acc, p -> if (acc.lastOrNull() != p) acc += p; acc }
        assertTrue("uiautomator slid the finger", device.swipe(points.toTypedArray(), STEPS))
    }

    private companion object {
        const val STEPS = 40
    }

    private fun barWords(): List<String> =
        listOf("suggestion_left", "suggestion_middle", "suggestion_right").mapNotNull { device.findObject(res(it))?.text }.filter { it.isNotEmpty() }

    private fun french(layout: LayoutKind = LayoutKind.QWERTY) {
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
        app.settings.keyboardLayout = layout
    }

    /** The setting as it was before the test: off by default, turned on by the tests that trace a word. */
    private var gestureBefore = false

    @Before
    fun rememberGestureSetting() {
        gestureBefore = app.settings.gestureTyping
    }

    @After
    fun restoreGestureSetting() {
        app.settings.gestureTyping = gestureBefore
    }

    private fun gestureOn() {
        app.settings.gestureTyping = true
    }

    /** No trace was read: at most the one letter under the finger went in (the key the finger went down on). */
    private fun assertNoTraceRead(text: String) {
        assertTrue("no word from the trace, at most one letter: \"$text\"", text.length <= 1)
    }

    @Test
    fun aTracedWordIsWrittenAndTheNextOneGetsASpace() {
        french()
        gestureOn()
        launchFields()
        showTextKeyboard()
        trace("bonjour")
        waitFor("bonjour written", 15_000, details = { field1() }) { field1() == "bonjour" }
        trace("merci")
        waitFor("space then merci", 10_000, details = { field1() }) { field1() == "bonjour merci" }
    }

    @Test
    fun theOtherGuessesAreInTheBarAndATapReplacesTheWord() {
        french()
        gestureOn()
        launchFields()
        showTextKeyboard()
        trace("tout")
        waitFor("a word written", 15_000, details = { field1() }) { field1().isNotEmpty() }
        // The best other guess sits in the middle of the bar.
        fun middle() = device.findObject(res("suggestion_middle"))?.text.orEmpty()
        waitFor("other guesses in the bar", 5_000, details = { barWords().toString() }) { middle().isNotEmpty() }
        val other = middle()
        assertFalse("the written word is not offered again", other == field1())
        obj(By.text(other).res(BuildConfig.APPLICATION_ID, "suggestion_middle"), "first other guess").click()
        waitFor("replaced", 5_000, details = { field1() }) { field1() == "$other " }
    }

    @Test
    fun azertyTracesFollowTheAzertyKeys() {
        french(LayoutKind.AZERTY)
        gestureOn()
        launchFields()
        showTextKeyboard()
        trace("maison")
        waitFor("maison written", 15_000, details = { field1() }) { field1() == "maison" }
    }

    @Test
    fun aTapStillTypesOneLetter() {
        french()
        gestureOn()
        launchFields()
        showTextKeyboard()
        obj(res("letters"), "letters").findObject(By.text("b")).click()
        waitFor("b typed", 5_000, details = { field1() }) { field1() == "b" }
    }

    @Test
    fun noGestureTypingByDefault() {
        french()
        assertFalse("off by default", app.settings.gestureTyping)
        launchFields()
        showTextKeyboard()
        trace("bonjour")
        device.waitForIdle(1_500)
        assertNoTraceRead(field1())
    }

    @Test
    fun noGestureTypingInAPasswordField() {
        french()
        gestureOn()
        launchFields()
        focus(3)
        obj(res("letters"), "text view in a password field")
        trace("bonjour")
        device.waitForIdle(1_500)
        assertNoTraceRead(fieldText { it.password })
    }

    @Test
    fun settingIsInTheSettings() {
        androidx.test.core.app.ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            s.onActivity { a ->
                val sw = a.window.decorView.findViewWithTag<android.widget.CompoundButton>(SettingsActivity.GESTURE_TAG)
                assertNotNull("gesture typing switch", sw)
                assertEquals("off by default", false, sw.isChecked)
            }
        }
    }
}
