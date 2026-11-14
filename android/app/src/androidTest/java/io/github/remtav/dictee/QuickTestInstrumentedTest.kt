package io.github.remtav.dictee

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.remtav.dictee.audio.AudioSourceFactory
import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.PcmAudioSource
import io.github.remtav.dictee.reading.QuickReading
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelPlainNames
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.SttEngine
import io.github.remtav.dictee.stt.SttEngineFactory
import io.github.remtav.dictee.stt.TranscriptionMode
import io.github.remtav.dictee.ui.QuickTestActivity
import io.github.remtav.dictee.ui.QuickTestModel
import io.github.remtav.dictee.ui.SetupActivity
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
 * Issue #36, recentred on the phone: the quick test reads a short text, transcribes it with the light and the
 * best phone models (scripted engines here, a silent clip instead of the microphone), shows a score, a delay and
 * one sentence, and a button adopts the recommendation. The reading is gone once the result is shown; nothing
 * reaches a server, even one set up and chosen in the advanced settings. Issue #37: no model name on screen.
 */
@RunWith(AndroidJUnit4::class)
class QuickTestInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app: DicteeApp get() = context.applicationContext as DicteeApp

    private val best = ModelCatalog.whisperTurbo
    private val light = ModelCatalog.parakeet

    @Before
    fun setUp() {
        app.settings.prefs.edit().clear().commit()
        TestHooks.installedModelIds = setOf(best.id, light.id)
        TestHooks.audioSourceFactory = AudioSourceFactory {
            PcmAudioSource(listOf(PcmAudio(FloatArray(3 * 16000) { 0.05f }, 16000)), realTime = false, leadSeconds = 0f, tailSeconds = 0f)
        }
    }

    @After
    fun cleanUp() {
        // A slow scripted decode left by a test ends before the next one starts.
        val end = System.currentTimeMillis() + 15_000
        while (QuickTestModel.lingering && System.currentTimeMillis() < end) Thread.sleep(100)
        TestHooks.installedModelIds = null
        TestHooks.audioSourceFactory = null
        TestHooks.engineFactory = null
        TestHooks.quickTestBudgetSeconds = null
        TestHooks.networkAvailable = null
        app.engines.release()
        app.settings.prefs.edit().clear().commit()
    }

    /** Engines that answer [text] per model, after a delay per model; records which models were used. */
    private class Models(private val text: (ModelSpec, String) -> String, private val delayMillis: (ModelSpec) -> Long) : SttEngineFactory {
        val used: MutableList<String> = Collections.synchronizedList(ArrayList())
        lateinit var reference: String
        override fun create(spec: ModelSpec, modelDir: java.io.File, options: EngineOptions): SttEngine {
            used += spec.id
            return ScriptedEngine(spec, List(10) { text(spec, reference) }, delayMillis(spec))
        }
    }

    private fun reference(language: DictationLanguage): String =
        QuickReading.load(language) { path -> context.assets.open(path).bufferedReader().use { it.readText() } }.text.paragraphs[0]

    private fun dropEveryFourthWord(text: String) = text.split(' ').filterIndexed { n, _ -> n % 4 != 3 }.joinToString(" ")

    private fun View.allViews(): Sequence<View> = sequence {
        yield(this@allViews)
        if (this@allViews is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).allViews())
    }

    private fun ActivityScenario<*>.allText(): String {
        var out = ""
        onActivity { a -> out = a.window.decorView.allViews().filterIsInstance<TextView>().joinToString("\n") { it.text } }
        return out
    }

    /** The text of the view tagged [tag]: its own if it is a text, else every text inside it (a result card). */
    private fun ActivityScenario<*>.textOf(tag: String): String? {
        var out: String? = null
        onActivity { a ->
            val v = a.window.decorView.findViewWithTag<View>(tag)
            out = when (v) {
                null -> null
                is TextView -> v.text.toString()
                else -> v.allViews().filterIsInstance<TextView>().joinToString("\n") { it.text }
            }
        }
        return out
    }

    private fun ActivityScenario<*>.has(tag: String): Boolean {
        var found = false
        onActivity { found = it.window.decorView.findViewWithTag<View>(tag) != null }
        return found
    }

    private fun ActivityScenario<*>.click(tag: String) {
        onActivity { checkNotNull(it.window.decorView.findViewWithTag<View>(tag)) { "no view $tag" }.performClick() }
        instrumentation.waitForIdleSync()
    }

    private fun ActivityScenario<*>.await(tag: String, timeoutMillis: Long = 30_000) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            instrumentation.waitForIdleSync()
            if (has(tag)) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $tag\n${allText()}")
    }

    private fun ActivityScenario<QuickTestActivity>.model(): QuickTestModel {
        var m: QuickTestModel? = null
        onActivity { a -> m = androidx.lifecycle.ViewModelProvider(a)[QuickTestModel::class.java] }
        return checkNotNull(m)
    }

    /** Reads (the clip ends by itself) and waits for the result; returns how long the test took after the reading. */
    private fun ActivityScenario<QuickTestActivity>.readAndWait(): Long {
        await(QuickTestActivity.TAG_RECORD)
        val t0 = System.currentTimeMillis()
        click(QuickTestActivity.TAG_RECORD)
        await(QuickTestActivity.TAG_VERDICT, 60_000)
        return System.currentTimeMillis() - t0
    }

    private fun assertNoModelName(text: String) {
        for (word in ModelPlainNames.technicalWords) assertFalse("« $word » on the quick test:\n$text", text.contains(word, ignoreCase = true))
    }

    @Test
    fun lightGoodEnoughAndBestSlowRecommendsTheLightOneAndTheButtonAppliesIt() {
        app.settings.languages = setOf(DictationLanguage.ENGLISH)
        val models = Models(text = { _, ref -> ref }, delayMillis = { if (it.id == best.id) 5_600 else 50 })
        models.reference = reference(DictationLanguage.ENGLISH)
        TestHooks.engineFactory = models
        ActivityScenario.launch(QuickTestActivity::class.java).use { s ->
            assertTrue(s.allText().contains(models.reference.substringBefore(' ')))
            s.readAndWait()
            val text = s.allText()
            assertNoModelName(text)
            // A score and a delay for each model, by effect.
            assertTrue(s.textOf(QuickTestActivity.resultTag(light))!!.contains("Plus léger et plus rapide"))
            assertTrue(s.allText().contains("Ressemblance : 100 %"))
            assertTrue(text.contains("délai"))
            assertEquals(context.getString(R.string.quick_verdict_light_enough), s.textOf(QuickTestActivity.TAG_SENTENCE))
            // The reading is gone once the scores are known.
            assertFalse(s.model().holdsRecording)
            assertEquals(best.id, app.settings.activeModelId)
            s.click(QuickTestActivity.TAG_ADOPT)
            assertEquals(light.id, app.settings.activeModelId)
            assertTrue(s.has(QuickTestActivity.TAG_ALREADY))
        }
        assertEquals(setOf(best.id, light.id), models.used.toSet())
    }

    @Test
    fun lightMissingWordsKeepsTheBestOne() {
        app.settings.languages = setOf(DictationLanguage.FRENCH_FRANCE)
        val models = Models(text = { spec, ref -> if (spec.id == light.id) dropEveryFourthWord(ref) else ref }, delayMillis = { 50 })
        models.reference = reference(DictationLanguage.FRENCH_FRANCE)
        TestHooks.engineFactory = models
        ActivityScenario.launch(QuickTestActivity::class.java).use { s ->
            s.readAndWait()
            assertEquals(context.getString(R.string.quick_verdict_light_inaccurate), s.textOf(QuickTestActivity.TAG_SENTENCE))
            // The best one is already the active model: nothing to adopt.
            assertTrue(s.has(QuickTestActivity.TAG_ALREADY))
            assertFalse(s.has(QuickTestActivity.TAG_ADOPT))
            assertEquals(best.id, app.settings.activeModelId)
        }
    }

    /** A model over the budget is « trop lent », and the test ends without waiting for it. */
    @Test
    fun aModelOverTheBudgetIsTooSlowAndTheTestEndsAnyway() {
        app.settings.languages = setOf(DictationLanguage.ENGLISH)
        TestHooks.quickTestBudgetSeconds = 1.0
        val models = Models(text = { _, ref -> ref }, delayMillis = { if (it.id == best.id) 6_000 else 50 })
        models.reference = reference(DictationLanguage.ENGLISH)
        TestHooks.engineFactory = models
        ActivityScenario.launch(QuickTestActivity::class.java).use { s ->
            val took = s.readAndWait()
            assertTrue("the test waited ${took} ms for the slow model", took < 5_000)
            assertTrue(s.textOf(QuickTestActivity.resultTag(best))!!.contains("Trop lent"))
            assertEquals(context.getString(R.string.quick_verdict_best_too_slow), s.textOf(QuickTestActivity.TAG_SENTENCE))
            // The keyboard is given back at once, even though the slow decode goes on.
            assertFalse(app.benchmarkRunning)
        }
    }

    /** Québec French: one model makes sense, the test simply gives its score. */
    @Test
    fun quebecFrenchGivesASingleScore() {
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
        val models = Models(text = { _, ref -> ref }, delayMillis = { 50 })
        models.reference = reference(DictationLanguage.FRENCH_QUEBEC)
        TestHooks.engineFactory = models
        ActivityScenario.launch(QuickTestActivity::class.java).use { s ->
            s.readAndWait()
            assertEquals(context.getString(R.string.quick_single_scored, 100), s.textOf(QuickTestActivity.TAG_SENTENCE))
            assertFalse(s.has(QuickTestActivity.resultTag(light)))
            assertNoModelName(s.allText())
        }
        assertEquals(listOf(best.id), models.used.distinct())
    }

    /** The light model not on the phone: the test scores the best one and offers the download (outside the minute). */
    @Test
    fun aMissingModelIsOfferedForDownload() {
        app.settings.languages = setOf(DictationLanguage.SPANISH)
        TestHooks.installedModelIds = setOf(best.id)
        val models = Models(text = { _, ref -> ref }, delayMillis = { 50 })
        models.reference = reference(DictationLanguage.SPANISH)
        TestHooks.engineFactory = models
        ActivityScenario.launch(QuickTestActivity::class.java).use { s ->
            s.readAndWait()
            assertTrue(s.has(QuickTestActivity.downloadTag(light)))
            assertEquals(context.getString(R.string.quick_verdict_light_not_installed), s.textOf(QuickTestActivity.TAG_SENTENCE))
        }
        assertEquals(listOf(best.id), models.used.distinct())
    }

    /**
     * The rule that admits no compromise: even with a server set up and chosen in the advanced settings, the quick
     * test never sends the reading anywhere, and never changes the transcription mode.
     */
    @Test
    fun theQuickTestNeverReachesAServer() {
        RecordingServer { 500 to "{}" }.use { server ->
            app.settings.remoteUrl = "http://127.0.0.1:${server.port}"
            app.settings.transcriptionMode = TranscriptionMode.SERVER
            TestHooks.networkAvailable = true
            app.settings.languages = setOf(DictationLanguage.ENGLISH)
            val models = Models(text = { _, ref -> ref }, delayMillis = { 50 })
            models.reference = reference(DictationLanguage.ENGLISH)
            TestHooks.engineFactory = models
            ActivityScenario.launch(QuickTestActivity::class.java).use { s ->
                s.readAndWait()
                assertNotNull(s.textOf(QuickTestActivity.TAG_SENTENCE))
                // Review of #65: the screen says that dictations go to the server while that setting is on.
                assertTrue(s.has(QuickTestActivity.TAG_SERVER_NOTE))
                if (s.has(QuickTestActivity.TAG_ADOPT)) s.click(QuickTestActivity.TAG_ADOPT)
            }
            assertTrue("the server got ${server.requests.size} request(s)", server.requests.isEmpty())
            assertTrue(models.used.none { it == ModelCatalog.remote.id })
            assertEquals(TranscriptionMode.SERVER, app.settings.transcriptionMode)
        }
    }

    /** The installation screen offers the test (outlined) once a model to test is on the phone, for the five languages only. */
    @Test
    fun theSetupScreenOffersTheTestForTheFiveLanguages() {
        app.settings.languages = setOf(DictationLanguage.ENGLISH)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertTrue(s.has(SetupActivity.QUICK_TEST_BUTTON_TAG))
            s.onActivity { a ->
                val b = a.window.decorView.findViewWithTag<View>(SetupActivity.QUICK_TEST_BUTTON_TAG)
                assertEquals(true, b.getTag(R.id.button_outlined))
            }
        }
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
        TestHooks.installedModelIds = emptySet()
        ActivityScenario.launch(SetupActivity::class.java).use { s -> assertFalse(s.has(SetupActivity.QUICK_TEST_BUTTON_TAG)) }
    }

    /** Review of #65: the light model (tried first) over the budget: the best one is not tried, the test ends anyway. */
    @Test
    fun theLightModelOverTheBudgetEndsTheTestWithAnHonestText() {
        app.settings.languages = setOf(DictationLanguage.ENGLISH)
        TestHooks.quickTestBudgetSeconds = 1.0
        val models = Models(text = { _, ref -> ref }, delayMillis = { if (it.id == light.id) 4_000 else 50 })
        models.reference = reference(DictationLanguage.ENGLISH)
        TestHooks.engineFactory = models
        ActivityScenario.launch(QuickTestActivity::class.java).use { s ->
            val took = s.readAndWait()
            assertTrue("the test waited ${took} ms for the slow model", took < 3_500)
            assertTrue(s.textOf(QuickTestActivity.resultTag(light))!!.contains("Trop lent"))
            assertEquals(context.getString(R.string.quick_skipped), s.textOf(QuickTestActivity.resultTag(best))!!.lines().last())
            assertEquals(context.getString(R.string.quick_no_verdict_slow), s.textOf(QuickTestActivity.TAG_SENTENCE))
            assertFalse(s.has(QuickTestActivity.TAG_ADOPT))
            assertFalse(app.benchmarkRunning)
            // A new test is refused until the slow decode ends.
            assertTrue(QuickTestModel.lingering)
            s.click(QuickTestActivity.TAG_REDO)
            s.click(QuickTestActivity.TAG_RECORD)
            assertEquals(context.getString(R.string.quick_previous_running), s.textOf(QuickTestActivity.TAG_NOTICE))
        }
        assertEquals("the best model was never loaded behind the slow one", listOf(light.id), models.used.distinct())
    }

    /** Review of #65: closing the screen while a model is over its budget must not take the app (and keyboard) down. */
    @Test
    fun closingTheScreenDuringTheScoringIsSafe() {
        app.settings.languages = setOf(DictationLanguage.ENGLISH)
        TestHooks.quickTestBudgetSeconds = 1.0
        val models = Models(text = { _, ref -> ref }, delayMillis = { if (it.id == best.id) 3_000 else 50 })
        models.reference = reference(DictationLanguage.ENGLISH)
        TestHooks.engineFactory = models
        ActivityScenario.launch(QuickTestActivity::class.java).use { s ->
            s.await(QuickTestActivity.TAG_RECORD)
            s.click(QuickTestActivity.TAG_RECORD)
            s.await(QuickTestActivity.TAG_SCORING)
        } // closed during the scoring (onCleared)
        // Past the budget and past the slow decode: the process is still here, and the keyboard is free.
        val end = System.currentTimeMillis() + 10_000
        while ((app.benchmarkRunning || QuickTestModel.lingering) && System.currentTimeMillis() < end) Thread.sleep(100)
        assertFalse(app.benchmarkRunning)
        assertFalse(QuickTestModel.lingering)
    }

    /** Review of #65: a reading cut by the app going to the background is dropped, not scored; a rotation is not a cut. */
    @Test
    fun aReadingCutByTheBackgroundIsNotScoredButARotationGoesOn() {
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
        TestHooks.audioSourceFactory = AudioSourceFactory {
            PcmAudioSource(listOf(PcmAudio(FloatArray(4 * 16000) { 0.05f }, 16000)), realTime = true, leadSeconds = 0f, tailSeconds = 0f)
        }
        val models = Models(text = { _, ref -> ref }, delayMillis = { 50 })
        models.reference = reference(DictationLanguage.FRENCH_QUEBEC)
        TestHooks.engineFactory = models
        ActivityScenario.launch(QuickTestActivity::class.java).use { s ->
            s.await(QuickTestActivity.TAG_RECORD)
            s.click(QuickTestActivity.TAG_RECORD)
            Thread.sleep(1_000)
            s.recreate() // rotation: the reading goes on and is scored
            s.await(QuickTestActivity.TAG_VERDICT, 30_000)
            s.click(QuickTestActivity.TAG_REDO)
            s.click(QuickTestActivity.TAG_RECORD)
            Thread.sleep(1_000)
            s.moveToState(androidx.lifecycle.Lifecycle.State.CREATED) // to the background: cut
            s.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            s.await(QuickTestActivity.TAG_NOTICE, 10_000)
            assertEquals(context.getString(R.string.quick_interrupted), s.textOf(QuickTestActivity.TAG_NOTICE))
            assertFalse(s.has(QuickTestActivity.TAG_VERDICT))
            assertFalse(s.model().holdsRecording)
        }
        assertEquals("only the first reading was transcribed", 1, models.used.size)
    }
}
