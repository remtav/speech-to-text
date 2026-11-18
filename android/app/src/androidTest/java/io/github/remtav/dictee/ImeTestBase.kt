package io.github.remtav.dictee

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.remtav.dictee.audio.AudioSourceFactory
import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.PcmAudioSource
import io.github.remtav.dictee.audio.WavReader
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.ime.DicteeImeService
import io.github.remtav.dictee.settings.CleanupMode
import io.github.remtav.dictee.stt.ModelCatalog
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before

/**
 * Drives the real voice keyboard on an emulator: the IME is enabled and
 * selected through `ime` shell commands, a debug-only activity provides text
 * fields, a WAV file replaces the microphone and a scripted engine replaces
 * the 1 GB model (the real models are tested on the CI host, see
 * HostModelTest and DECISIONS.md). Audio still goes through the real capture
 * loop, the real Silero VAD (sherpa-onnx JNI on the emulator) and the real
 * InputConnection.
 */
abstract class ImeTestBase {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context = instrumentation.targetContext
    protected val app: DicteeApp get() = context.applicationContext as DicteeApp
    protected val device: UiDevice = UiDevice.getInstance(instrumentation)
    protected lateinit var scenario: ActivityScenario<TestFieldActivity>
    protected lateinit var factory: ScriptedEngine.Factory

    private val imeId = "${BuildConfig.APPLICATION_ID}/.ime.DicteeImeService"

    protected fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    @Before
    fun setUpIme() {
        shell("logcat -c")
        app.settings.prefs.edit().clear().commit()
        app.settings.partials = false
        app.settings.haptics = false
        app.settings.cleanupMode = CleanupMode.OFF
        app.settings.activeModelId = ModelCatalog.parakeet.id
        TestHooks.assumeInstalled = true
        app.engines.release()
        waitUntilImeIsKnown()
        DicteeImeService.instance?.get()?.let { svc -> instrumentation.runOnMainSync { svc.setTextKeyboardShown(false) } }
        selectIme()
        device.setOrientationNatural()
        dismissNotRespondingDialogs()
    }

    @After
    fun tearDownIme() {
        // The view (microphone or keys) lives in the service since #90, no longer in the settings cleared by
        // setUpIme: a test that left the keys open must not decide the view of the next one, which reopens
        // the same field within the rotation grace period.
        DicteeImeService.instance?.get()?.let { svc ->
            instrumentation.runOnMainSync { svc.controller.cancel(); svc.setTextKeyboardShown(false) }
        }
        TestHooks.audioSourceFactory = null
        TestHooks.engineFactory = null
        TestHooks.assumeInstalled = false
        TestHooks.networkAvailable = null
        if (::scenario.isInitialized) scenario.close()
        app.engines.release()
        device.setOrientationNatural()
        device.unfreezeRotation()
    }

    /**
     * Right after the APKs are installed, the system may not have registered the keyboard service
     * yet: "ime enable" and "ime set" then fail with "unrecognized IME ID", and the first test of the
     * run found the keyboard missing (CI of lot 2, review 2.R). Waits until the system lists it.
     */
    private fun waitUntilImeIsKnown() {
        val end = SystemClock.uptimeMillis() + 60_000
        while (imeId !in shell("ime list -a -s") && SystemClock.uptimeMillis() < end) SystemClock.sleep(1_000)
    }

    /** Enables and selects the keyboard only when it is not already: a no-op otherwise, nothing to reset. */
    private fun selectIme() {
        if (imeId !in shell("settings get secure enabled_input_methods")) shell("ime enable $imeId")
        if (shell("settings get secure default_input_method").trim() != imeId) shell("ime set $imeId")
    }

    /**
     * A cold CI emulator sometimes shows "Pixel Launcher isn't responding" (CI of lot 2, commit
     * 205d898: the dialog stays on top for the whole run, so the test field never gets the window
     * focus, `showSoftInput` fails at PHASE_CLIENT_VIEW_SERVED and every keyboard test fails; the
     * ScreenshotTest captures show it over the setup screen). Answers "Wait", which leaves the
     * other app as it was.
     */
    protected fun dismissNotRespondingDialogs() {
        repeat(3) {
            if (!device.hasObject(By.textContains("isn't responding")) && !device.hasObject(By.res("android", "aerr_wait"))) return
            (device.findObject(By.res("android", "aerr_wait")) ?: device.findObject(By.text("Wait")))?.click()
            device.waitForIdle(1_000)
        }
    }

    protected fun clip(name: String): PcmAudio = context.assets.open("reference/$name.wav").use { WavReader.read(it) }

    /** Replaces the microphone with [clips] separated by [gap] seconds of silence. */
    protected fun useAudio(vararg clips: String, realTime: Boolean = false, gap: Float = 1.5f) {
        val audio = clips.map { clip(it) }
        TestHooks.audioSourceFactory = AudioSourceFactory { PcmAudioSource(audio, realTime = realTime, gapSeconds = gap) }
    }

    protected fun useScript(vararg texts: String, delayMillis: Long = 50) {
        factory = ScriptedEngine.Factory(texts.toList(), delayMillis)
        TestHooks.engineFactory = factory
    }

    /** [focus]: "number", "decimal" or "phone" to start on that field instead of the first one. */
    protected fun launchFields(focus: String? = null) {
        val intent = android.content.Intent(context, TestFieldActivity::class.java)
        if (focus != null) intent.putExtra(TestFieldActivity.EXTRA_FOCUS, focus)
        scenario = ActivityScenario.launch(intent)
        dismissNotRespondingDialogs()
        val shown = {
            val svc = DicteeImeService.instance?.get()
            svc != null && onMain { svc.isInputViewShown }
        }
        val end = SystemClock.uptimeMillis() + 45_000
        var tapped = SystemClock.uptimeMillis()
        while (!shown() && SystemClock.uptimeMillis() < end) {
            // A cold emulator sometimes ignores stateAlwaysVisible: tap the field like a user.
            if (SystemClock.uptimeMillis() - tapped > 4_000) {
                // A system dialog may have taken the focus; a cold emulator may not have selected the IME yet
                // (only re-selected when it is not: a no-op "ime set" would not help, a real one resets the input).
                dismissNotRespondingDialogs()
                selectIme()
                device.findObject(By.res(BuildConfig.APPLICATION_ID, "test_field1"))?.click()
                scenario.onActivity {
                    it.field1.requestFocus()
                    it.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .showSoftInput(it.field1, 0)
                }
                tapped = SystemClock.uptimeMillis()
            }
            SystemClock.sleep(100)
        }
        if (!shown()) {
            val log = shell("logcat -d -t 80 -s DicteeIme:I InputMethodManagerService:* ImeTracker:*")
            val focus = shell("dumpsys window").lines().filter { "mCurrentFocus" in it || "mFocusedApp" in it }.joinToString("\n")
            fail("keyboard not shown; current IME: ${shell("settings get secure default_input_method").trim()}\n$focus\n$log")
        }
    }

    protected val service: DicteeImeService get() = DicteeImeService.instance!!.get()!!

    protected fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    protected fun field1(): String { var t = ""; scenario.onActivity { t = it.field1.text.toString() }; return t }
    protected fun field2(): String { var t = ""; scenario.onActivity { t = it.field2.text.toString() }; return t }
    protected fun fieldText(pick: (TestFieldActivity) -> android.widget.EditText): String {
        var t = ""; scenario.onActivity { t = pick(it).text.toString() }; return t
    }

    protected fun focus(which: Int) {
        // The keyboard only hears of a focus change inside a window that has the window focus. A
        // system dialog over the test activity (an "isn't responding" box on a loaded emulator)
        // takes it, and the change never reaches the keyboard: wait for the focus first, then for
        // the keyboard's new input (a fresh EditorInfo), so a test never runs on a change the
        // keyboard did not see (independent review of lot 3, flaky RobustnessInstrumentedTest).
        awaitWindowFocus()
        val before = onMain { DicteeImeService.instance?.get()?.currentInputEditorInfo }
        scenario.onActivity {
            val f = when (which) { 1 -> it.field1; 2 -> it.field2; 4 -> it.noId1; 5 -> it.noId2; 6 -> it.number; 7 -> it.decimal; 8 -> it.phone; else -> it.password }
            f.requestFocus()
        }
        instrumentation.waitForIdleSync()
        waitFor("keyboard told of the focus change to field $which", 10_000, details = ::focusDiagnostics) {
            onMain { DicteeImeService.instance?.get()?.currentInputEditorInfo }.let { it != null && it !== before }
        }
    }

    /**
     * What each side believes about the focus when a focus change did not reach the keyboard (issue #117): the
     * window manager (focused window per display), the input method service (focused window and served client),
     * the app's own view of it, and the focus events of the input dispatcher.
     */
    protected fun focusDiagnostics(): String = runCatching {
        val wm = shell("dumpsys window").lines().filter {
            "WINDOW MANAGER" in it || "mCurrentFocus" in it || "mFocusedApp" in it || "mTopFocusedDisplayId" in it ||
                "imeLayeringTarget" in it || "imeInputTarget" in it || "imeControlTarget" in it ||
                "Display: mDisplayId=" in it || "mFocusedWindow" in it
        }.joinToString("\n")
        val displays = shell("dumpsys display").lines().filter { "mDisplayId=" in it || "Display Devices" in it }
            .take(10).joinToString("\n") { it.trim().take(160) }
        val ims = shell("dumpsys input_method").lines().filter {
            "mCurFocusedWindow" in it || "mCurClient" in it || "mServedView" in it || "mNextServedView" in it ||
                "mCurRootView" in it || "mFocusedWindow" in it || "mImeBindingState" in it || "mCurrentRootView" in it
        }.joinToString("\n") { it.trim() }
        val rotation = shell("dumpsys window displays").lines().filter { "otation" in it || "mLastOrientation" in it }
            .take(25).joinToString("\n") { it.trim().take(200) } +
            "\nuser_rotation=${shell("settings get system user_rotation").trim()} accelerometer_rotation=" +
            shell("settings get system accelerometer_rotation").trim() + " uiDevice.displayRotation=${device.displayRotation}" +
            "\n" + shell("dumpsys window displays").substringAfter("RotationHistory", "").lines().take(20).joinToString("\n") { it.trim().take(250) } +
            "\n--- settings writes\n" + shell("dumpsys settings").lines()
                .filter { "user_rotation" in it || "accelerometer_rotation" in it }.takeLast(30).joinToString("\n") { it.trim().take(250) }
        var activity = ""
        if (::scenario.isInitialized) runCatching {
            scenario.onActivity {
                activity = "activity orientation=${it.resources.configuration.orientation} hasWindowFocus=${it.hasWindowFocus()} currentFocus=${it.currentFocus?.let { v -> v.javaClass.simpleName + "#" + v.id }}"
            }
        }.onFailure { activity = "activity: $it" }
        val events = shell("logcat -d -b events -s input_focus:* wm_focus_changed:* wm_on_top_resumed_gained_called:* wm_on_top_resumed_lost_called:* wm_set_resumed_activity:* wm_finish_activity:* wm_destroy_activity:* wm_create_activity:*")
            .lines().takeLast(30).joinToString("\n")
        "--- window manager\n$wm\n--- displays\n$displays\n--- rotation\n$rotation\n--- input method service\n$ims\n--- app\n$activity\n--- focus events\n$events"
    }.getOrDefault("")

    /** Waits until the test activity has the window focus, answering "Wait" to a not-responding box. */
    protected fun awaitWindowFocus(timeout: Long = 10_000) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            var focused = false
            scenario.onActivity { focused = it.hasWindowFocus() }
            if (focused) return
            dismissNotRespondingDialogs()
            SystemClock.sleep(100)
        }
        fail("the test activity never got the window focus\n${windowFocus()}")
    }

    protected fun windowFocus(): String = runCatching {
        shell("dumpsys window").lines().filter { "mCurrentFocus" in it || "mFocusedApp" in it }.joinToString("\n")
    }.getOrDefault("")

    /** Taps the keyboard's microphone button, through the accessibility tree like a user. */
    protected fun tapMic() {
        val mic = device.wait(Until.findObject(By.res(BuildConfig.APPLICATION_ID, "mic")), 5_000)
        if (mic != null) mic.click() else onMain { service.onMicTapped() }
    }

    /** Waits for the keyboard to be back (tapping the field if needed, as a user would). */
    protected fun ensureKeyboardShown(timeout: Long = 3_000) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (onMain { DicteeImeService.instance?.get()?.isInputViewShown == true }) return
            SystemClock.sleep(100)
        }
        device.findObject(By.res(BuildConfig.APPLICATION_ID, "test_field1"))?.click()
    }

    protected fun state(): DictationState = service.controller.state

    protected fun awaitIdle(timeout: Long = 30_000) =
        waitFor("dictation finished", timeout) { state() == DictationState.IDLE || state() == DictationState.ERROR }

    protected fun waitFor(what: String, timeout: Long, details: (() -> String)? = null, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (runCatching(condition).getOrDefault(false)) return
            SystemClock.sleep(100)
        }
        val fields = if (::scenario.isInitialized) runCatching { "field1=\"${field1()}\" field2=\"${field2()}\"" }.getOrDefault("") else ""
        val st = runCatching { "state=${state()} engine=${app.engines.state}" }.getOrDefault("")
        // Enough lines for the keyboard's own lifecycle (startInput, finishInputView, "still hidden: stop"),
        // and which window had the focus: the two causes seen in flaky runs on a loaded emulator.
        val log = runCatching { shell("logcat -d -s DicteeIme:I").lines().takeLast(40).joinToString("\n") }.getOrDefault("")
        val more = details?.let { runCatching(it).getOrDefault("") }.orEmpty()
        fail("timed out waiting for: $what ($fields $st)\n${windowFocus()}\n$log\n$more")
    }

    protected fun assertWithin(actual: Float, expected: Float, tolerance: Float) =
        assertTrue("$actual not within $tolerance of $expected", kotlin.math.abs(actual - expected) <= tolerance)
}
