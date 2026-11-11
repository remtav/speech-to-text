package io.github.remtav.dictee

import android.content.ComponentCallbacks2
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.remtav.dictee.cleanup.LlmCleaner
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.settings.AppSettings
import io.github.remtav.dictee.settings.CleanupMode
import io.github.remtav.dictee.stt.Acceleration
import io.github.remtav.dictee.stt.EngineManager
import io.github.remtav.dictee.stt.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Lot 1: the keyboard types what it hears into the focused field. */
@RunWith(AndroidJUnit4::class)
class DictationEndToEndTest : ImeTestBase() {

    @Test
    fun audioFileIsDictatedIntoTheField() {
        useAudio("en1", "fr1")
        useScript("after early nightfall the yellow lamps would light up.", "Alice et moi sommes allés à Paris.")
        launchFields()
        tapMic()
        waitFor("both segments inserted", 30_000) {
            field1() == "After early nightfall the yellow lamps would light up. Alice et moi sommes allés à Paris."
        }
        awaitIdle()
        // The real Silero VAD cut the WAV at the pause: one segment per clip, of the clip's length.
        val durations = factory.last!!.durations
        assertEquals(2, durations.size)
        assertWithin(durations[0], clip("en1").seconds, 1.2f)
        assertWithin(durations[1], clip("fr1").seconds, 1.2f)
    }

    @Test
    fun tapToStopFinalizesWhatWasSaid() {
        useAudio("en2", realTime = true)
        useScript("God as a direct consequence of the sin.")
        launchFields()
        tapMic()
        waitFor("listening", 5_000) { state() == DictationState.LISTENING }
        Thread.sleep(4_000) // mid-sentence
        tapMic()
        awaitIdle()
        assertEquals("God as a direct consequence of the sin.", field1())
        assertTrue(factory.last!!.durations.single() < clip("en2").seconds - 3f)
    }

    @Test
    fun modelCanBeSwitchedFromTheKeyboard() {
        useAudio("en1")
        useScript("one.")
        launchFields()
        waitFor("default model loaded", 10_000) { (app.engines.state as? EngineManager.State.Ready)?.spec == ModelCatalog.parakeet }
        val first = factory.last!!
        onMain { service.switchModel(ModelCatalog.qwen3.id) }
        waitFor("qwen loaded", 10_000) { (app.engines.state as? EngineManager.State.Ready)?.spec == ModelCatalog.qwen3 }
        assertTrue("previous model released", first.released)
        assertEquals(ModelCatalog.qwen3.id, app.settings.activeModelId)
        tapMic()
        waitFor("text", 20_000) { field1() == "One." }
    }

    @Test
    fun passwordFieldsAreNeverDictated() {
        useAudio("en1")
        useScript("secret.")
        launchFields()
        focus(3)
        waitFor("password field active", 5_000) { onMain { service.currentInputEditorInfo?.inputType ?: 0 } and 0x80 != 0 }
        onMain { service.onMicTapped() }
        Thread.sleep(1_500)
        assertEquals(DictationState.IDLE, state())
        assertTrue(factory.created.get() == 0 || factory.last!!.calls.get() == 0)
    }

    @Test
    fun modelIsReleasedUnderMemoryPressureWhenHidden() {
        useAudio("en1")
        useScript("x.")
        launchFields()
        waitFor("model loaded", 10_000) { app.engines.state is EngineManager.State.Ready }
        val engine = factory.last!!
        // Visible keyboard: memory pressure does not take the model away.
        onMain { app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) }
        assertFalse(engine.released)
        scenario.onActivity { it.finish() }
        waitFor("keyboard hidden", 10_000) { !app.keyboardVisible }
        onMain { app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) }
        waitFor("model released", 5_000) { engine.released }
    }
}

/** Lot 2: voice commands, inside the real keyboard. */
@RunWith(AndroidJUnit4::class)
class VoiceCommandInstrumentedTest : ImeTestBase() {

    private fun dictate(vararg segments: String): String {
        val clips = Array(segments.size) { if (it % 2 == 0) "en1" else "fr1" }
        useAudio(*clips, gap = 1.2f)
        useScript(*segments, delayMillis = 10)
        launchFields()
        tapMic()
        waitFor("all segments", 60_000) { factory.last?.calls?.get() == segments.size }
        awaitIdle()
        Thread.sleep(300)
        return field1()
    }

    @Test fun newLine() = assertEquals("Bonjour.\nMerci.", dictate("Bonjour.", "Nouvelle ligne.", "merci."))

    @Test fun newParagraph() = assertEquals("Dear John.\n\nThanks.", dictate("Dear John.", "New paragraph.", "Thanks."))

    @Test fun deleteLastSentence() =
        assertEquals("Première phrase.", dictate("Première phrase.", "Deuxième phrase.", "Efface la dernière phrase."))

    @Test fun deleteLastWord() = assertEquals("Un deux", dictate("Un deux trois", "Delete last word."))

    @Test fun commandsCanBeTurnedOff() {
        app.settings.voiceCommands = false
        assertEquals("Bonjour. Nouvelle ligne.", dictate("Bonjour.", "Nouvelle ligne."))
    }
}

/** Lot 2: robustness scenarios. */
@RunWith(AndroidJUnit4::class)
class RobustnessInstrumentedTest : ImeTestBase() {

    @Test
    fun rotationDuringDictationKeepsDictating() {
        useAudio("en1", "fr1", realTime = true)
        useScript("first part.", "second part.")
        launchFields()
        tapMic()
        waitFor("first segment", 20_000) { field1() == "First part." }
        device.setOrientationLeft()
        waitFor("second segment after rotation", 25_000) { field1() == "First part. Second part." }
        awaitIdle()
    }

    @Test
    fun changingFieldMidDictationStopsAndNeverTypesIntoTheNewField() {
        useAudio("en1", "fr1", realTime = true)
        useScript("never shown.", "never shown either.", delayMillis = 4_000)
        launchFields()
        tapMic()
        waitFor("first segment being decoded", 20_000) { (factory.last?.calls?.get() ?: 0) >= 1 }
        focus(2)
        awaitIdle(10_000)
        Thread.sleep(5_000) // the slow decode finishes: its text must be dropped
        assertEquals("", field1())
        assertEquals("", field2())
    }

    @Test
    fun audioFocusLossStopsAndKeepsWhatWasSaid() {
        useAudio("en1", "fr1", "en2", realTime = true)
        useScript("before the interruption.", "after.", "after.")
        launchFields()
        tapMic()
        waitFor("first segment", 20_000) { field1() == "Before the interruption." }
        // Another app (a call, a voice assistant) takes the audio focus.
        val am = context.getSystemService(AudioManager::class.java)
        val other = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
            .build()
        am.requestAudioFocus(other)
        try {
            awaitIdle(15_000)
            Thread.sleep(500)
            assertTrue("dictation kept going after focus loss", (factory.last?.calls?.get() ?: 0) <= 2)
            assertTrue(field1().startsWith("Before the interruption."))
        } finally {
            am.abandonAudioFocusRequest(other)
        }
    }

    @Test
    fun incomingCallStopsDictation() {
        useAudio("en1", "fr1", "en2", realTime = true)
        useScript("said before the call.", "x.", "y.")
        launchFields()
        tapMic()
        waitFor("first segment", 20_000) { field1() == "Said before the call." }
        // What AudioManager reports when the phone rings / a call starts (an emulator
        // test cannot place a real call from inside the device).
        onMain { service.onAudioModeChanged(AudioManager.MODE_RINGTONE) }
        awaitIdle(15_000)
        assertTrue((factory.last?.calls?.get() ?: 0) <= 2)
        assertTrue(field1().startsWith("Said before the call."))
    }

    @Test
    fun longDictationInsertsEverySegmentInOrder() {
        val n = 16
        val clips = Array(n) { if (it % 2 == 0) "en1" else "fr1" }
        useAudio(*clips, gap = 1.0f)
        useScript(*Array(n) { "phrase ${it + 1}." }, delayMillis = 5)
        launchFields()
        tapMic()
        waitFor("all segments", 120_000) { factory.last?.calls?.get() == n }
        awaitIdle()
        val expected = (1..n).joinToString(" ") { "Phrase $it." }
        waitFor("text", 5_000) { field1() == expected }
        assertTrue(factory.last!!.durations.all { it <= 20.01f })
    }

    @Test
    fun provisionalTextIsReplacedByFinalText() {
        app.settings.partials = true
        useAudio("en2", realTime = true)
        useScript(*Array(200) { "texte en cours" }, delayMillis = 30)
        launchFields()
        tapMic()
        waitFor("provisional text visible while speaking", 15_000) {
            state() == DictationState.LISTENING && field1() == "Texte en cours"
        }
        awaitIdle(40_000)
        assertEquals("Texte en cours", field1())
    }
}

/** Lot 3: optional features are off by default and fail without crashing. */
@RunWith(AndroidJUnit4::class)
class OptionalFeaturesInstrumentedTest : ImeTestBase() {

    @Test
    fun defaultsAreCpuAndNoOptionalFeature() {
        val fresh = AppSettings(context)
        fresh.prefs.edit().clear().commit()
        assertEquals(Acceleration.CPU, fresh.acceleration)
        assertEquals(CleanupMode.OFF, fresh.cleanupMode)
        assertEquals(ModelCatalog.parakeet, fresh.activeModel)
    }

    @Test
    fun llmUnavailableOnThisDeviceReturnsNullWithoutCrashing() {
        val bogus = File(context.cacheDir, "not-a-model.task").apply { writeText("nope") }
        assertNull(LlmCleaner.createFromPath(context, bogus.absolutePath))
    }

    @Test
    fun dictationWorksWithLlmCleanupSelectedButNoModel() {
        app.settings.cleanupMode = CleanupMode.LLM
        useAudio("en1")
        useScript("euh, bonjour à tous.")
        launchFields()
        tapMic()
        // Falls back to rule-based clean-up.
        waitFor("cleaned text", 20_000) { field1() == "Bonjour à tous." }
    }

    @Test
    fun acceleratorRequestFallsBackToCpuWhenRefused() {
        app.settings.acceleration = Acceleration.NNAPI
        val inner = ScriptedEngine.Factory(listOf("accéléré."), 10)
        factory = inner
        TestHooks.engineFactory = io.github.remtav.dictee.stt.SttEngineFactory { spec, dir, opts ->
            if (opts.acceleration != Acceleration.CPU) throw IllegalArgumentException("NNAPI not supported")
            inner.create(spec, dir, opts)
        }
        useAudio("en1")
        launchFields()
        tapMic()
        waitFor("text", 20_000) { field1() == "Accéléré." }
        val st = app.engines.state as EngineManager.State.Ready
        assertTrue(st.note!!.contains("CPU"))
    }

    @Test
    fun acceleratorProvidersOnRealLibraryDoNotCrash() {
        // The real sherpa-onnx JNI with each accelerator request, on the only model the
        // emulator holds (Silero VAD): unsupported providers fall back to the CPU.
        for (acc in Acceleration.entries) {
            val config = com.k2fsa.sherpa.onnx.VadModelConfig(
                sileroVadModelConfig = com.k2fsa.sherpa.onnx.SileroVadModelConfig(model = "silero_vad.onnx"),
                provider = acc.provider,
            )
            val vad = com.k2fsa.sherpa.onnx.Vad(context.assets, config)
            vad.acceptWaveform(FloatArray(16000))
            assertFalse(acc.name, vad.isSpeechDetected())
            vad.release()
        }
    }
}
