package io.github.remtav.dictee

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.remtav.dictee.ime.DicteeImeService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #90: the keyboard always opens on the microphone, even when it was left on the keys; a rotation keeps
 * the view in use. Apart from [TextKeyboardInstrumentedTest]: these tests hide the keyboard and wait out the
 * rotation grace period, and a keyboard left in that state must not reach the tests of the other class.
 */
@RunWith(AndroidJUnit4::class)
class ReopenKeyboardInstrumentedTest : ImeTestBase() {

    private fun res(id: String) = By.res(BuildConfig.APPLICATION_ID, id)

    private fun showTextKeyboard() {
        onMain { service.setTextKeyboardShown(true) }
        assertNotNull("letters shown", device.wait(Until.findObject(res("letters")), 5_000))
    }

    /** Hides the keyboard (not through Back, which would close the test activity) and brings it back on the field. */
    private fun hideThenReopenKeyboard(waitMs: Long) {
        onMain { service.requestHideSelf(0) }
        waitFor("keyboard hidden", 5_000) { !onMain { service.isInputViewShown } }
        android.os.SystemClock.sleep(waitMs)
        scenario.onActivity {
            it.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .showSoftInput(it.currentFocus ?: it.field1, 0)
        }
        waitFor("keyboard shown again", 10_000) { onMain { service.isInputViewShown } }
    }

    @Test
    fun reopeningTheKeyboardShowsTheMicrophoneEvenIfItWasLeftOnTheKeys() {
        launchFields()
        showTextKeyboard()
        hideThenReopenKeyboard(DicteeImeService.HIDE_GRACE_MS + 1_000)
        assertNotNull("microphone after reopening", device.wait(Until.findObject(res("mic")), 5_000))
        assertNull("no keys after reopening", device.findObject(res("letters")))
    }

    @Test
    fun rotationOnTheTextKeysKeepsTheKeys() {
        launchFields()
        showTextKeyboard()
        device.setOrientationLeft()
        device.waitForIdle()
        ensureKeyboardShown()
        assertNotNull("keys kept after the rotation", device.wait(Until.findObject(res("letters")), 10_000))
        assertNull(device.findObject(res("mic")))
    }
}
