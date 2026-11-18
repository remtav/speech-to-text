package io.github.remtav.dictee

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

    /** What the keyboard's touchable views cover and what the navigation bar takes, in screen pixels. */
    private data class Measure(
        val keys: Int, val left: Int, val right: Int, val bottom: Int,
        val barLeft: Int, val barRight: Int, val barBottom: Int,
    )

    private fun measure(): Measure = onMain {
        val root = service.window.window!!.decorView.findViewById<View>(R.id.keyboard_root)
        val bar = WindowInsetsCompat.toWindowInsetsCompat(root.rootWindowInsets, root)
            .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
        var keys = 0
        var left = Int.MAX_VALUE
        var right = 0
        var bottom = 0
        fun walk(v: View) {
            if (!v.isShown) return
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            if (v.isClickable || v.isLongClickable) {
                val at = IntArray(2)
                v.getLocationOnScreen(at)
                keys++
                left = minOf(left, at[0])
                right = maxOf(right, at[0] + v.width)
                bottom = maxOf(bottom, at[1] + v.height)
            }
        }
        walk(root)
        Measure(keys, left, right, bottom, bar.left, bar.right, bar.bottom)
    }

    /** [expectBar]: the three-button bar must have reached the keyboard window (a gesture bar may be thin or absent). */
    private fun check(what: String, expectBar: Boolean) {
        val m = measure()
        assertTrue("$what: no touchable key found, nothing was measured", m.keys > 0)
        if (expectBar) assertTrue("$what: no navigation bar inset reached the keyboard", m.barLeft + m.barRight + m.barBottom > 0)
        val barTop = device.displayHeight - m.barBottom
        assertTrue("$what: the keys end at ${m.bottom} px, under the navigation bar that starts at $barTop px", m.bottom <= barTop)
        assertTrue("$what: the keys start at ${m.left} px, under the navigation bar that ends at ${m.barLeft} px", m.left >= m.barLeft)
        val barStart = device.displayWidth - m.barRight
        assertTrue("$what: the keys end at ${m.right} px, under the navigation bar that starts at $barStart px", m.right <= barStart)
    }

    private fun checkThreeViews(mode: String, expectBar: Boolean) {
        launchFields()
        check("$mode, dictation view", expectBar)
        onMain { service.setTextKeyboardShown(true) }
        device.waitForIdle()
        check("$mode, letters", expectBar)
        focus(6) // the number field: the pad
        device.waitForIdle()
        check("$mode, number pad", expectBar)
    }

    @Test
    fun keysStayAboveTheThreeButtonBar() {
        setNavigation(threeButton)
        checkThreeViews("three buttons", expectBar = true)
    }

    @Test
    fun keysStayAboveTheGestureBar() {
        setNavigation(gestural)
        checkThreeViews("gestures", expectBar = false)
    }

    @Test
    fun keysStayAboveTheThreeButtonBarInLandscape() {
        setNavigation(threeButton)
        device.setOrientationLeft()
        device.waitForIdle()
        checkThreeViews("three buttons, landscape", expectBar = true)
    }
}
