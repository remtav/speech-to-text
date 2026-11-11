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
        app.settings.prefs.edit().clear().commit()
        app.settings.partials = false
        app.settings.haptics = false
        app.settings.cleanupMode = CleanupMode.OFF
        app.settings.activeModelId = ModelCatalog.parakeet.id
        TestHooks.assumeInstalled = true
        app.engines.release()
        shell("ime enable $imeId")
        shell("ime set $imeId")
        device.setOrientationNatural()
    }

    @After
    fun tearDownIme() {
        DicteeImeService.instance?.get()?.let { svc -> instrumentation.runOnMainSync { svc.controller.cancel() } }
        TestHooks.audioSourceFactory = null
        TestHooks.engineFactory = null
        TestHooks.vadParams = null
        TestHooks.assumeInstalled = false
        if (::scenario.isInitialized) scenario.close()
        app.engines.release()
        device.setOrientationNatural()
        device.unfreezeRotation()
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

    protected fun launchFields() {
        scenario = ActivityScenario.launch(TestFieldActivity::class.java)
        waitFor("keyboard shown", 15_000) {
            val svc = DicteeImeService.instance?.get()
            svc != null && onMain { svc.isInputViewShown }
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

    protected fun focus(which: Int) {
        scenario.onActivity {
            val f = when (which) { 1 -> it.field1; 2 -> it.field2; else -> it.password }
            f.requestFocus()
        }
        instrumentation.waitForIdleSync()
    }

    /** Taps the keyboard's microphone button, through the accessibility tree like a user. */
    protected fun tapMic() {
        val mic = device.wait(Until.findObject(By.res(BuildConfig.APPLICATION_ID, "mic")), 5_000)
        if (mic != null) mic.click() else onMain { service.onMicTapped() }
    }

    protected fun state(): DictationState = service.controller.state

    protected fun awaitIdle(timeout: Long = 30_000) =
        waitFor("dictation finished", timeout) { state() == DictationState.IDLE || state() == DictationState.ERROR }

    protected fun waitFor(what: String, timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (runCatching(condition).getOrDefault(false)) return
            SystemClock.sleep(100)
        }
        fail("timed out waiting for: $what")
    }

    protected fun assertWithin(actual: Float, expected: Float, tolerance: Float) =
        assertTrue("$actual not within $tolerance of $expected", kotlin.math.abs(actual - expected) <= tolerance)
}
