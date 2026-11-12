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
 * assertion of looks: it fails only if a capture cannot be written.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest : ImeTestBase() {
    private val dir: File by lazy {
        File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    }

    @org.junit.Before
    fun portrait() {
        device.setOrientationNatural() // and stays frozen for the captures
        instrumentation.waitForIdleSync()
        Thread.sleep(1_500) // let a rotation left by a previous test finish
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(700) // animations, ripple, level meter
        val f = File(dir, "$name.png")
        assertTrue("screenshot $name", device.takeScreenshot(f, 1.0f, 90))
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
            shot("keyboard-idle-$suffix")
            tapMic()
            waitFor("listening", 5_000) { state() == DictationState.LISTENING }
            Thread.sleep(2_500)
            shot("keyboard-listening-$suffix")
            tapMic()
            awaitIdle()
            shot("keyboard-text-$suffix")
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
                shot("setup-$suffix")
                s.scrollToTag(languageTag(DictationLanguage.FRENCH_QUEBEC), above = 120)
                shot("setup-languages-$suffix")
                s.scrollToTag(SetupActivity.modelCardTag(ModelCatalog.parakeet), above = 24)
                shot("setup-models-$suffix")
            }
            ActivityScenario.launch(SettingsActivity::class.java).use { shot("settings-$suffix") }
            ActivityScenario.launch<CompareActivity>(Intent(context, CompareActivity::class.java)).use {
                shot("compare-$suffix")
            }
            // Not configured: the screen as first opened, nothing is sent anywhere.
            ActivityScenario.launch(DiagnosticActivity::class.java).use { shot("diagnostic-$suffix") }
        }
    }
}
