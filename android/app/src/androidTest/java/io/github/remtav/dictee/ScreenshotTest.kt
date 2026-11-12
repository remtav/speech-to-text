package io.github.remtav.dictee

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.ime.DicteeImeService
import io.github.remtav.dictee.settings.ThemeMode
import io.github.remtav.dictee.ui.CompareActivity
import io.github.remtav.dictee.ui.SettingsActivity
import io.github.remtav.dictee.ui.SetupActivity
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
            ActivityScenario.launch(SetupActivity::class.java).use { shot("setup-$suffix") }
            ActivityScenario.launch(SettingsActivity::class.java).use { shot("settings-$suffix") }
            ActivityScenario.launch<CompareActivity>(Intent(context, CompareActivity::class.java)).use {
                shot("compare-$suffix")
            }
        }
    }
}
