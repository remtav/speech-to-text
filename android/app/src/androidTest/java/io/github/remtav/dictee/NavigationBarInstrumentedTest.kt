package io.github.remtav.dictee

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #70: the bottom of the keyboard hid under the navigation bar (three buttons on a Pixel).
 * Every key must end above the navigation bar, in the three views (dictation, letters, number pad),
 * with the three-button bar and with gesture navigation, in portrait and in landscape.
 *
 * The mode is set by the test itself (`cmd overlay`), so the same emulator covers both and the CI
 * keeps its three shards. It is put back to what it was afterwards.
 */
@RunWith(AndroidJUnit4::class)
class NavigationBarInstrumentedTest : ImeTestBase() {

    private val threeButton = "com.android.internal.systemui.navbar.threebutton"
    private val gestural = "com.android.internal.systemui.navbar.gestural"
    private var originalMode: String? = null

    private fun setNavigation(overlay: String) {
        if (originalMode == null) {
            originalMode = if (shell("cmd overlay list").lines().any { it.contains("[x] $threeButton") }) threeButton else gestural
        }
        shell("cmd overlay enable-exclusive --category $overlay")
        device.waitForIdle(2_000)
    }

    @After
    fun restoreNavigation() {
        originalMode?.let { shell("cmd overlay enable-exclusive --category $it") }
    }

    /** Bottom of the lowest touchable view of the keyboard, and the top of the navigation bar, in screen pixels. */
    private fun keysBottomAndBarTop(): Triple<Int, Int, Int> = onMain {
        val root = service.window.window!!.decorView.findViewById<View>(R.id.keyboard_root)
        val bar = WindowInsetsCompat.toWindowInsetsCompat(root.rootWindowInsets, root)
            .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
        var lowest = 0
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE || !v.isShown) return
            if (v is ViewGroup) { for (i in 0 until v.childCount) walk(v.getChildAt(i)); if (v.isClickable) lowest = maxOf(lowest, bottomOf(v)) }
            else if (v.isClickable || v.isLongClickable) lowest = maxOf(lowest, bottomOf(v))
        }
        walk(root)
        Triple(lowest, device.displayHeight - bar.bottom, bar.bottom)
    }

    private fun bottomOf(v: View): Int {
        val r = Rect()
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        r.set(at[0], at[1], at[0] + v.width, at[1] + v.height)
        return r.bottom
    }

    private fun check(what: String, expectBar: Boolean) {
        val (keys, barTop, barHeight) = keysBottomAndBarTop()
        if (expectBar) assertTrue("$what: no navigation bar inset reached the keyboard", barHeight > 0)
        assertTrue("$what: the keys end at $keys px, under the navigation bar that starts at $barTop px", keys <= barTop)
    }

    private fun checkThreeViews(mode: String) {
        launchFields()
        check("$mode, dictation view", expectBar = mode == "three buttons")
        onMain { service.setTextKeyboardShown(true) }
        device.waitForIdle()
        check("$mode, letters", expectBar = mode == "three buttons")
        focus(6) // the number field: the pad
        device.waitForIdle()
        check("$mode, number pad", expectBar = mode == "three buttons")
    }

    @Test
    fun keysStayAboveTheThreeButtonBar() {
        setNavigation(threeButton)
        checkThreeViews("three buttons")
    }

    @Test
    fun keysStayAboveTheGestureBar() {
        setNavigation(gestural)
        checkThreeViews("gestures")
    }

    @Test
    fun keysStayAboveTheThreeButtonBarInLandscape() {
        setNavigation(threeButton)
        device.setOrientationLeft()
        device.waitForIdle()
        checkThreeViews("three buttons, landscape")
    }
}
