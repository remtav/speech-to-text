package io.github.remtav.dictee

import android.content.Intent
import android.view.View
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.ime.DicteeImeService
import io.github.remtav.dictee.settings.ThemeMode
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.ui.CompareActivity
import io.github.remtav.dictee.ui.DiagnosticActivity
import io.github.remtav.dictee.ui.SettingsActivity
import io.github.remtav.dictee.ui.SetupActivity
import io.github.remtav.dictee.ui.languageTag
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Captures the keyboard and the app screens, light and dark, for the
 * documentation. CI pulls them from the device (see android.yml). Not an
 * assertion of looks: it fails only if a capture cannot be written or is
 * not in portrait.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest : ImeTestBase() {
    private val dir: File by lazy {
        File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    }

    private var savedAutoRotate: String = ""

    /**
     * Portrait, for real, before any capture. A previous test (rotation during dictation)
     * can leave the display in landscape even after `setOrientationNatural()`: in CI the
     * light captures of the app screens came out at 2400x1080. Auto-rotation is switched off
     * and the user rotation set to 0 through the system settings, the rotation is frozen,
     * and the test waits until the display really is in portrait (fails otherwise).
     */
    @org.junit.Before
    fun portrait() {
        savedAutoRotate = shell("settings get system accelerometer_rotation").trim()
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 0")
        device.setOrientationNatural()
        awaitPortrait()
    }

    @org.junit.After
    fun restoreAutoRotate() {
        if (savedAutoRotate == "0" || savedAutoRotate == "1") shell("settings put system accelerometer_rotation $savedAutoRotate")
    }

    private fun displayIsPortrait() =
        device.displayRotation == android.view.Surface.ROTATION_0 && device.displayHeight > device.displayWidth

    private fun awaitPortrait(screen: ActivityScenario<*>? = null) {
        waitFor("portrait display", 15_000) {
            device.setOrientationNatural()
            displayIsPortrait() && (screen == null || run {
                var portrait = false
                screen.onActivity {
                    portrait = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                }
                portrait
            })
        }
    }

    /**
     * Captures the screen at half size (540x1200 on the Pixel 6 emulator, as the pictures
     * already in the repository), after checking that the display and [screen] are in portrait.
     */
    private fun shot(name: String, screen: ActivityScenario<*>? = null) {
        awaitPortrait(screen)
        instrumentation.waitForIdleSync()
        Thread.sleep(700) // animations, ripple, level meter
        val f = File(dir, "$name.png")
        assertTrue("screenshot $name", device.takeScreenshot(f, 0.5f, 90))
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, bounds)
        assertTrue("screenshot $name is not in portrait: ${bounds.outWidth}x${bounds.outHeight}", bounds.outHeight > bounds.outWidth)
    }

    private fun setTheme(mode: ThemeMode) {
        app.settings.theme = mode
        onMain { app.applyTheme() }
        DicteeImeService.instance?.get()?.let { svc -> onMain { svc.rebuildInputView() } }
    }

    /**
     * Scrolls the screen so the view carrying [tag] sits near the top, [above]
     * dp below the top edge (to leave its heading in the picture).
     */
    private fun ActivityScenario<*>.scrollToTag(tag: String, above: Int) {
        onActivity { a ->
            val target = checkNotNull(a.window.decorView.findViewWithTag<View>(tag)) { "no view tagged $tag" }
            val scroll = generateSequence(target.parent) { it.parent }.filterIsInstance<ScrollView>().first()
            val content = scroll.getChildAt(0)
            var y = 0
            var v: View = target
            while (v !== content) {
                y += v.top
                v = v.parent as View
            }
            scroll.scrollTo(0, maxOf(0, y - (above * a.resources.displayMetrics.density).toInt()))
        }
    }

    @Test
    fun keyboard() {
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            val suffix = mode.name.lowercase()
            app.settings.theme = mode
            onMain { app.applyTheme() }
            useAudio("en2", realTime = true)
            useScript("Bonjour, ceci est une dictée sur le téléphone.", delayMillis = 10)
            launchFields()
            onMain { service.rebuildInputView() }
            waitFor("model ready", 10_000) { app.engines.state is io.github.remtav.dictee.stt.EngineManager.State.Ready }
            shot("keyboard-idle-$suffix", scenario)
            tapMic()
            waitFor("listening", 5_000) { state() == DictationState.LISTENING }
            Thread.sleep(2_500)
            shot("keyboard-listening-$suffix", scenario)
            tapMic()
            awaitIdle()
            shot("keyboard-text-$suffix", scenario)
            scenario.close()
        }
    }

    @Test
    fun appScreens() {
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            val suffix = mode.name.lowercase()
            setTheme(mode)
            // Setup: the top (welcome text, steps), then the "Vos langues" step
            // and the model card carrying the "Recommandé" badge (the screen is
            // taller than the phone). Quebec French is preselected: Parakeet is
            // the recommended model. No network, no model downloaded.
            ActivityScenario.launch(SetupActivity::class.java).use { s ->
                shot("setup-$suffix", s)
                s.scrollToTag(languageTag(DictationLanguage.FRENCH_QUEBEC), above = 120)
                shot("setup-languages-$suffix", s)
                s.scrollToTag(SetupActivity.modelCardTag(ModelCatalog.parakeet), above = 24)
                shot("setup-models-$suffix", s)
            }
            ActivityScenario.launch(SettingsActivity::class.java).use { shot("settings-$suffix", it) }
            ActivityScenario.launch<CompareActivity>(Intent(context, CompareActivity::class.java)).use {
                shot("compare-$suffix", it)
            }
            // Not configured: the screen as first opened, nothing is sent anywhere.
            ActivityScenario.launch(DiagnosticActivity::class.java).use { shot("diagnostic-$suffix", it) }
        }
    }
}
