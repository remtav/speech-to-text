package io.github.remtav.dictee

import android.Manifest
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.remtav.dictee.audio.AudioSourceFactory
import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.PcmAudioSource
import io.github.remtav.dictee.reading.ReadingMaterial
import io.github.remtav.dictee.stt.BenchmarkLock
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.SttEngine
import io.github.remtav.dictee.stt.SttEngineFactory
import io.github.remtav.dictee.ui.CompareActivity
import io.github.remtav.dictee.ui.QuickTestActivity
import io.github.remtav.dictee.ui.QuickTestModel
import io.github.remtav.dictee.ui.ReadingTestActivity
import io.github.remtav.dictee.ui.ReadingTestModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Issue #66, point 3: the quick test, « Comparer les modèles » and the reading test share one guard
 * (`DicteeApp.benchmark`). A screen that does not hold it can neither start a measure (no model loaded, no engine
 * released) nor give the keyboard back in the middle of another screen's measure; none starts while a decode of the
 * quick test lingers. Before the fix, « Comparer » and the reading test set `benchmarkRunning` without an owner:
 * each of these tests then loaded a model, and left the keyboard free while the other screen still measured.
 */
@RunWith(AndroidJUnit4::class)
class MeasureLockInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app: DicteeApp get() = context.applicationContext as DicteeApp
    private val busy get() = context.getString(R.string.measure_busy)

    /** Every model created by a screen, by id: none may be created while another screen holds the lock. */
    private class Recorder(private val text: (ModelSpec) -> List<String>) : SttEngineFactory {
        val created: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun create(spec: ModelSpec, modelDir: java.io.File, options: EngineOptions): SttEngine {
            created += spec.id
            return ScriptedEngine(spec, text(spec), 0)
        }
    }

    private var held: BenchmarkLock.Claim? = null

    @Before
    fun setUp() {
        app.settings.prefs.edit().clear().commit()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        TestHooks.installedModelIds = setOf(ModelCatalog.whisperTurbo.id)
        TestHooks.audioSourceFactory = AudioSourceFactory { clip() }
    }

    @After
    fun cleanUp() {
        held?.let { app.benchmark.release(it) }
        held = null
        TestHooks.installedModelIds = null
        TestHooks.audioSourceFactory = null
        TestHooks.engineFactory = null
        app.engines.release()
        app.settings.prefs.edit().clear().commit()
    }

    private fun clip() = PcmAudioSource(listOf(PcmAudio(FloatArray(3 * 16000) { 0.05f }, 16000)), realTime = false, leadSeconds = 0f, tailSeconds = 0f)

    /** Another screen (its thread outliving it, say) measures: it holds the lock. */
    private fun anotherScreenMeasures(holder: String): BenchmarkLock.Claim =
        checkNotNull(app.benchmark.tryClaim(holder)) { "the lock was not free" }.also { held = it }

    private fun ActivityScenario<*>.textOf(tag: String): String? {
        var out: String? = null
        onActivity { a -> out = (a.window.decorView.findViewWithTag<View>(tag) as? TextView)?.text?.toString() }
        return out
    }

    private fun ActivityScenario<*>.click(tag: String) {
        onActivity { checkNotNull(it.window.decorView.findViewWithTag<View>(tag)) { "no view $tag" }.performClick() }
        instrumentation.waitForIdleSync()
    }

    private fun ActivityScenario<*>.awaitText(tag: String, expected: String, timeoutMillis: Long = 20_000) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            instrumentation.waitForIdleSync()
            if (textOf(tag) == expected) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for « $expected » on $tag, got « ${textOf(tag)} »")
    }

    @Test
    fun compareDoesNotStartWhileTheQuickTestMeasures() {
        val recorder = Recorder { List(20) { "texte" } }
        TestHooks.engineFactory = recorder
        val quick = anotherScreenMeasures(QuickTestModel.HOLDER)
        ActivityScenario.launch(CompareActivity::class.java).use { s ->
            s.click(CompareActivity.TAG_RUN)
            assertEquals(busy, s.textOf(CompareActivity.TAG_PROGRESS))
            assertTrue("no model loaded behind the quick test: ${recorder.created}", recorder.created.isEmpty())
            assertTrue("the keyboard still waits for the quick test", app.benchmarkRunning)
            // The quick test ends; a decode it gave up on is still running: still no second big model.
            val decode = app.benchmark.beginLingering()
            assertTrue(app.benchmark.release(quick))
            held = null
            assertFalse("the keyboard gets the engine back at once", app.benchmarkRunning)
            s.click(CompareActivity.TAG_RUN)
            assertEquals(busy, s.textOf(CompareActivity.TAG_PROGRESS))
            assertTrue(recorder.created.isEmpty())
            decode.end()
        }
    }

    @Test
    fun theQuickTestDoesNotStartWhileAnotherScreenMeasures() {
        app.settings.languages = setOf(DictationLanguage.ENGLISH)
        val recorder = Recorder { List(10) { "texte" } }
        TestHooks.engineFactory = recorder
        val compare = anotherScreenMeasures(CompareActivity.HOLDER)
        ActivityScenario.launch(QuickTestActivity::class.java).use { s ->
            s.click(QuickTestActivity.TAG_RECORD)
            assertEquals(busy, s.textOf(QuickTestActivity.TAG_NOTICE))
        }
        assertTrue(recorder.created.isEmpty())
        assertTrue("only « Comparer » gives the keyboard back", app.benchmark.release(compare))
        held = null
    }

    @Test
    fun aMeasureStartedDuringTheQuickTestReadingIsNotOverridden() {
        app.settings.languages = setOf(DictationLanguage.ENGLISH)
        val recorder = Recorder { List(10) { "texte" } }
        TestHooks.engineFactory = recorder
        // « Comparer » starts while the person reads (the microphone opens after the test's own check).
        TestHooks.audioSourceFactory = AudioSourceFactory { anotherScreenMeasures(CompareActivity.HOLDER); clip() }
        ActivityScenario.launch(QuickTestActivity::class.java).use { s ->
            s.click(QuickTestActivity.TAG_RECORD)
            s.awaitText(QuickTestActivity.TAG_NOTICE, busy)
            var model: QuickTestModel? = null
            s.onActivity { model = ViewModelProvider(it)[QuickTestModel::class.java] }
            assertFalse("the reading is dropped, not kept for later", model!!.holdsRecording)
        }
        assertTrue("nothing transcribed behind « Comparer »: ${recorder.created}", recorder.created.isEmpty())
        assertTrue(app.benchmarkRunning)
        assertTrue(app.benchmark.release(held!!))
        held = null
    }

    @Test
    fun theReadingTestWaitsForAnotherScreenAndKeepsTheRecordings() {
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
        app.settings.activeModelId = ModelCatalog.whisperTurbo.id
        val material = ReadingMaterial.load { path -> context.assets.open(path).bufferedReader().use { it.readText() } }
        val models = ScriptedReadingModels(material) { _, _, text -> text }
        TestHooks.engineFactory = models
        val compare = anotherScreenMeasures(CompareActivity.HOLDER)
        ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
            val d = ReadingDriver(instrumentation, s, material)
            d.awaitTag(ReadingTestActivity.TAG_DECLARE_YES)
            d.click(ReadingTestActivity.TAG_DECLARE_YES)
            d.awaitTag(ReadingTestActivity.TAG_ACCEPT_START)
            d.click(ReadingTestActivity.TAG_ACCEPT_START)
            d.awaitTag(ReadingTestActivity.TAG_PARAGRAPH)
            d.readAllParagraphs(context.getString(R.string.reading_redo))
            d.click(ReadingTestActivity.TAG_SCORE)
            assertEquals(busy, d.text(ReadingTestActivity.TAG_NOTICE))
            assertEquals("no model loaded behind « Comparer »", 0, models.created.get())
            assertTrue("« Comparer » still holds the keyboard", app.benchmarkRunning)
            var session: io.github.remtav.dictee.reading.ReadingSession? = null
            s.onActivity { session = ViewModelProvider(it)[ReadingTestModel::class.java].session }
            assertEquals("the recordings stay, to score later", material.text.paragraphs.size, session!!.recordedCount)
            // « Comparer » ends: the same recordings are scored, and the reading test gives the keyboard back.
            assertTrue(app.benchmark.release(compare))
            held = null
            d.click(ReadingTestActivity.TAG_SCORE)
            d.awaitTag(ReadingTestActivity.globalTag(ModelCatalog.whisperTurbo.id), 60_000)
            assertEquals(1, models.created.get())
            d.await("the reading test gave the lock back") { !app.benchmark.busy }
        }
        assertNotNull(app.benchmark.tryClaim("vérification")?.also { app.benchmark.release(it) })
    }
}
