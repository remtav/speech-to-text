package io.github.remtav.dictee

import android.content.res.Configuration
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #117: the keyboard tests run in portrait, whatever the test before them left.
 *
 * Android 14 « rotation reversion »: when an activity locked to the natural orientation (the launcher,
 * NOSENSOR) comes to the front while the rotation is locked in landscape, the system turns the screen back
 * to portrait and remembers the landscape lock; when the next ordinary activity comes to the front, it puts
 * that lock back, even if the rotation was set to natural in between. A landscape test that closed its
 * activity before restoring the orientation left every following test of the class in landscape, where the
 * keyboard leaves no room for the lower fields (number, amount, phone, password): they had no height and
 * refused the focus, so the keyboard was never told of the field change.
 */
@RunWith(AndroidJUnit4::class)
class RotationInstrumentedTest : ImeTestBase() {

    private fun res(id: String) = By.res(BuildConfig.APPLICATION_ID, id)

    private fun activityOrientation(): Int {
        var o = Configuration.ORIENTATION_UNDEFINED
        scenario.onActivity { o = it.resources.configuration.orientation }
        return o
    }

    @Test
    fun aLandscapeLockRestoredBySystemDoesNotReachTheNextTest() {
        // The end of a landscape test as it was: the activity closed while the rotation is locked in landscape.
        launchFields()
        device.setOrientationLeft()
        device.waitForIdle()
        scenario.close()
        // The launcher (NOSENSOR) comes to the front: the system turns the screen to portrait and keeps the
        // landscape lock aside, to put it back with the next activity.
        assertNotNull("launcher in front", device.wait(Until.hasObject(By.pkg(device.launcherPackageName)), 10_000))
        waitFor("screen turned back to portrait by the launcher", 10_000) { device.displayRotation == Surface.ROTATION_0 }
        // The next test starts as setUpIme does: natural orientation (asked while the launcher is in front).
        device.setOrientationNatural()

        launchFields()
        assertEquals("the keyboard tests run in portrait", Configuration.ORIENTATION_PORTRAIT, activityOrientation())
        focus(7)
        assertNotNull("pad of the amount field", device.wait(Until.findObject(res("pad")), 5_000))
    }

    @Test
    fun aFieldUnderTheKeyboardInLandscapeStillTakesTheFocus() {
        launchFields()
        device.setOrientationLeft()
        device.waitForIdle()
        ensureKeyboardShown()
        waitFor("keyboard shown in landscape", 10_000) { onMain { service.isInputViewShown } }
        // The number field is under the keyboard in landscape: it must still take the focus (and come into view).
        focus(6)
        assertNotNull("pad of the number field", device.wait(Until.findObject(res("pad")), 5_000))
    }
}
