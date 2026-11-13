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
import io.github.remtav.dictee.ui.HowToActivity
import io.github.remtav.dictee.ui.ReadingTestActivity
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
        // The emulator's launcher sometimes stops answering and its dialog covered the captures: close it first.
        dismissNotRespondingDialogs()
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

    /**
     * The reading test with a result (issue #15, step 4.2): two scripted models (English alone, so both
     * Whisper turbo and Parakeet are offered, issue #22), the active one (Parakeet) loses every fourth word
     * and the recommended one (Whisper turbo) hears everything, so the capture shows the model
     * chooser, the "another model understands you better" card and the misheard words highlighted
     * (colour and underline). The clip is the test's, never the microphone; nothing is downloaded or sent.
     */
    @Test
    fun readingTest() {
        val material = io.github.remtav.dictee.reading.ReadingMaterial.load { path ->
            context.assets.open(path).bufferedReader().use { it.readText() }
        }
        val worse = ModelCatalog.parakeet.id
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                val suffix = mode.name.lowercase()
                setTheme(mode)
                app.settings.quebecDeclaration = io.github.remtav.dictee.reading.Declaration.YES
                app.settings.languages = setOf(io.github.remtav.dictee.stt.DictationLanguage.ENGLISH)
                app.settings.activeModelId = worse
                TestHooks.installedModelIds = setOf(ModelCatalog.whisperTurbo.id, worse)
                TestHooks.engineFactory = ScriptedReadingModels(material) { model, _, text ->
                    if (model == worse) ScriptedReadingModels.dropEveryFourthWord(text) else text
                }
                TestHooks.audioSourceFactory = io.github.remtav.dictee.audio.AudioSourceFactory {
                    io.github.remtav.dictee.audio.PcmAudioSource(
                        listOf(io.github.remtav.dictee.audio.PcmAudio(FloatArray(3 * 16000) { 0.05f }, 16000)),
                        realTime = false, leadSeconds = 0f, tailSeconds = 0f,
                    )
                }
                ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
                    val d = ReadingDriver(instrumentation, s, material)
                    d.awaitTag(ReadingTestActivity.TAG_ACCEPT_START)
                    d.click(ReadingTestActivity.TAG_ACCEPT_START)
                    d.awaitTag(ReadingTestActivity.TAG_PARAGRAPH)
                    d.readAllParagraphs(context.getString(R.string.reading_redo))
                    d.click(ReadingTestActivity.TAG_SCORE)
                    d.awaitTag(ReadingTestActivity.TAG_BETTER, 90_000)
                    s.scrollToTag(ReadingTestActivity.TAG_BETTER, above = 160)
                    shot("reading-$suffix", s)
                }
            }
        } finally {
            TestHooks.installedModelIds = null
        }
    }

    /**
     * The sharing offer of the reading test (issue #15, step 4.3): the recommended model (Whisper turbo) loses
     * every fourth word, above the 20 % line, so the result proposes to share; the capture shows the start
     * of the offer (« what would leave »). Nothing is created (no consent) and nothing is sent.
     */
    @Test
    fun readingShare() {
        val material = io.github.remtav.dictee.reading.ReadingMaterial.load { path ->
            context.assets.open(path).bufferedReader().use { it.readText() }
        }
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                val suffix = mode.name.lowercase()
                setTheme(mode)
                app.settings.languages = setOf(io.github.remtav.dictee.stt.DictationLanguage.FRENCH_QUEBEC)
                app.settings.quebecDeclaration = io.github.remtav.dictee.reading.Declaration.YES
                app.settings.activeModelId = ModelCatalog.whisperTurbo.id
                TestHooks.installedModelIds = setOf(ModelCatalog.whisperTurbo.id)
                TestHooks.engineFactory = ScriptedReadingModels(material) { _, _, text ->
                    ScriptedReadingModels.dropEveryFourthWord(text)
                }
                TestHooks.audioSourceFactory = io.github.remtav.dictee.audio.AudioSourceFactory {
                    io.github.remtav.dictee.audio.PcmAudioSource(
                        listOf(io.github.remtav.dictee.audio.PcmAudio(FloatArray(3 * 16000) { 0.05f }, 16000)),
                        realTime = false, leadSeconds = 0f, tailSeconds = 0f,
                    )
                }
                ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
                    val d = ReadingDriver(instrumentation, s, material)
                    d.awaitTag(ReadingTestActivity.TAG_ACCEPT_START)
                    d.click(ReadingTestActivity.TAG_ACCEPT_START)
                    d.awaitTag(ReadingTestActivity.TAG_PARAGRAPH)
                    d.readAllParagraphs(context.getString(R.string.reading_redo))
                    d.click(ReadingTestActivity.TAG_SCORE)
                    d.awaitTag(ReadingTestActivity.TAG_SHARE_LIST, 90_000)
                    s.scrollToTag(ReadingTestActivity.TAG_SHARE_SLOT, above = 8)
                    shot("reading-share-$suffix", s)
                }
            }
        } finally {
            TestHooks.installedModelIds = null
        }
    }

    @Test
    fun appScreens() {
        // The setup screen opens "Comment dicter" by itself once the keyboard, the microphone and a model
        // are in place (here a scripted model counts as installed): seen already, so the captures show the screens.
        app.settings.howToShown = true
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            val suffix = mode.name.lowercase()
            setTheme(mode)
            // Setup: the top (welcome text, steps), then the "Vos langues" step
            // and the model card carrying the "Recommandé" badge (the screen is
            // taller than the phone). Quebec French is preselected: Whisper turbo is
            // the recommended (and only offered) model. No network, no model downloaded.
            ActivityScenario.launch(SetupActivity::class.java).use { s ->
                shot("setup-$suffix", s)
                s.scrollToTag(languageTag(DictationLanguage.FRENCH_QUEBEC), above = 120)
                shot("setup-languages-$suffix", s)
                s.scrollToTag(SetupActivity.modelCardTag(ModelCatalog.whisperTurbo), above = 24)
                shot("setup-models-$suffix", s)
            }
            // "Comment dicter": generated from the command table (Québec French is the default language).
            ActivityScenario.launch(HowToActivity::class.java).use { shot("howto-$suffix", it) }
            ActivityScenario.launch(SettingsActivity::class.java).use { shot("settings-$suffix", it) }
            ActivityScenario.launch<CompareActivity>(Intent(context, CompareActivity::class.java)).use {
                shot("compare-$suffix", it)
            }
            // Not configured: the screen as first opened, nothing is sent anywhere.
            ActivityScenario.launch(DiagnosticActivity::class.java).use { shot("diagnostic-$suffix", it) }
        }
    }

    /**
     * Issue #55: the installation screen in its three keyboard states (nothing done, enabled, enabled and chosen),
     * forced through [TestHooks]; issue #57: no server on it. The microphone is shown as not allowed yet.
     */
    @Test
    fun setupSteps() {
        app.settings.howToShown = true
        val states = listOf("none" to (false to false), "enabled" to (true to false), "chosen" to (true to true))
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                val suffix = mode.name.lowercase()
                setTheme(mode)
                for ((name, state) in states) {
                    TestHooks.keyboardEnabled = state.first
                    TestHooks.keyboardChosen = state.second
                    TestHooks.micGranted = false
                    app.settings.setupSeen = false // starts on the welcome text
                    app.settings.keyboardChosenOnce = false // each state from scratch
                    ActivityScenario.launch(SetupActivity::class.java).use { s ->
                        s.scrollToTag(SetupActivity.STEPS_TAG, above = 8)
                        shot("setup-steps-$name-$suffix", s)
                    }
                }
            }
        } finally {
            TestHooks.keyboardEnabled = null
            TestHooks.keyboardChosen = null
            TestHooks.micGranted = null
        }
    }

    /** Issue #57: « Avancé » with the server section folded, then unfolded. No address: nothing is sent. */
    @Test
    fun advancedSettings() {
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            val suffix = mode.name.lowercase()
            setTheme(mode)
            ActivityScenario.launch(SettingsActivity::class.java).use { s ->
                s.scrollToTag(SettingsActivity.ADVANCED_TAG, above = 8)
                shot("settings-advanced-$suffix", s)
                s.onActivity { a -> a.window.decorView.findViewWithTag<View>(SettingsActivity.SERVER_SECTION_TAG).performClick() }
                instrumentation.waitForIdleSync()
                s.scrollToTag(SettingsActivity.SERVER_SECTION_TAG, above = 8)
                shot("settings-server-$suffix", s)
            }
        }
    }

    /**
     * Issue #36, recentred on the phone: the quick test's result (English alone: the light model reads as well and
     * the best one is slow, so the light one is recommended), and « Modèles et performances » in « Avancé »
     * (issue #37). Scripted engines, a silent clip: nothing is downloaded, recorded or sent.
     */
    @Test
    fun quickTest() {
        val reference = io.github.remtav.dictee.reading.QuickReading.load(DictationLanguage.ENGLISH) { path ->
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.text.paragraphs[0]
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                val suffix = mode.name.lowercase()
                setTheme(mode)
                app.settings.languages = setOf(DictationLanguage.ENGLISH)
                app.settings.activeModelId = ModelCatalog.whisperTurbo.id
                TestHooks.installedModelIds = setOf(ModelCatalog.whisperTurbo.id, ModelCatalog.parakeet.id)
                TestHooks.engineFactory = io.github.remtav.dictee.stt.SttEngineFactory { spec, _, _ ->
                    ScriptedEngine(spec, List(10) { reference }, if (spec.id == ModelCatalog.whisperTurbo.id) 5_600 else 50)
                }
                TestHooks.audioSourceFactory = io.github.remtav.dictee.audio.AudioSourceFactory {
                    io.github.remtav.dictee.audio.PcmAudioSource(
                        listOf(io.github.remtav.dictee.audio.PcmAudio(FloatArray(3 * 16000) { 0.05f }, 16000)),
                        realTime = false, leadSeconds = 0f, tailSeconds = 0f,
                    )
                }
                ActivityScenario.launch(io.github.remtav.dictee.ui.QuickTestActivity::class.java).use { s ->
                    shot("quick-test-reading-$suffix", s)
                    s.onActivity { a ->
                        a.window.decorView.findViewWithTag<View>(io.github.remtav.dictee.ui.QuickTestActivity.TAG_RECORD).performClick()
                    }
                    waitFor("quick test result", 60_000) {
                        var done = false
                        s.onActivity { a -> done = a.window.decorView.findViewWithTag<View>(io.github.remtav.dictee.ui.QuickTestActivity.TAG_VERDICT) != null }
                        done
                    }
                    shot("quick-test-result-$suffix", s)
                }
                app.engines.release()
                ActivityScenario.launch(SettingsActivity::class.java).use { s ->
                    s.onActivity { a -> a.window.decorView.findViewWithTag<View>(SettingsActivity.MODELS_SECTION_TAG).performClick() }
                    instrumentation.waitForIdleSync()
                    s.scrollToTag(SettingsActivity.MODELS_SECTION_TAG, above = 8)
                    shot("settings-models-$suffix", s)
                }
            }
        } finally {
            TestHooks.installedModelIds = null
        }
    }
}
