package io.github.remtav.dictee

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.widget.RadioButton
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.remtav.dictee.audio.AudioSourceFactory
import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.PcmAudioSource
import io.github.remtav.dictee.reading.Declaration
import io.github.remtav.dictee.reading.ReadingMaterial
import io.github.remtav.dictee.reading.ReadingScoring
import io.github.remtav.dictee.reading.similarityPercent
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.ui.PARTICIPATION_CARD_TAG
import io.github.remtav.dictee.ui.PARTICIPATION_DISMISS_TAG
import io.github.remtav.dictee.ui.PARTICIPATION_START_TAG
import io.github.remtav.dictee.ui.ReadingTestActivity
import io.github.remtav.dictee.ui.ReadingTestModel
import io.github.remtav.dictee.ui.SettingsActivity
import io.github.remtav.dictee.ui.SetupActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #15, lot 4, step 4.2: the participation card, the declaration and its exclusion, and the reading
 * test with scripted models and a clip supplied by the test (never the real microphone), with one
 * model installed and with several.
 *
 * Scenarios follow one another, never nested: `ActivityScenario.launch` starts each activity in a
 * cleared task and destroys the previous one (the cause of a CI failure in lot 1). The keyboard is
 * enabled by [ImeTestBase] and the microphone permission is granted here, so the setup screen is
 * "complete" (it only then shows the card); the tutorial counts as seen.
 */
@RunWith(AndroidJUnit4::class)
class ReadingTestInstrumentedTest : ImeTestBase() {
    private val material: ReadingMaterial by lazy {
        ReadingMaterial.load { path -> context.assets.open(path).bufferedReader().use { it.readText() } }
    }
    private val paragraphs get() = material.text.paragraphs
    private val redoLabel get() = context.getString(R.string.reading_redo)
    private val micOpened = AtomicInteger(0)
    private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()

    @Before
    fun prepare() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
        app.settings.activeModelId = ModelCatalog.parakeet.id
        TestHooks.installedModelIds = setOf(ModelCatalog.parakeet.id)
        // The microphone is replaced by a 3 s clip; every opening of the source is counted.
        TestHooks.audioSourceFactory = AudioSourceFactory {
            micOpened.incrementAndGet()
            PcmAudioSource(
                listOf(PcmAudio(FloatArray(3 * 16000) { 0.05f }, 16000)),
                realTime = false, leadSeconds = 0f, tailSeconds = 0f,
            )
        }
    }

    @After
    fun cleanUp() {
        monitors.forEach { instrumentation.removeMonitor(it) }
        monitors.clear()
        TestHooks.installedModelIds = null
    }

    private fun driver(s: ActivityScenario<*>) = ReadingDriver(instrumentation, s, material)

    private fun useModels(vararg ids: String, transcript: (String, Int, String) -> String = { _, _, text -> text }): ScriptedReadingModels {
        TestHooks.installedModelIds = ids.toSet()
        return ScriptedReadingModels(material, transcript).also { TestHooks.engineFactory = it }
    }

    /** Every file the app owns, minus the known library marker files (see [AppFiles]). */
    private fun appFiles(): Set<String> = AppFiles.list(context)

    // ---- the card ----

    @Test
    fun noCardWithoutQuebecFrenchOnTheMainScreenNorInTheSettings() {
        app.settings.languages = setOf(DictationLanguage.FRENCH_FRANCE, DictationLanguage.ENGLISH)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertFalse(driver(s).has(PARTICIPATION_CARD_TAG))
            assertFalse(driver(s).allText().contains(context.getString(R.string.contrib_card_title)))
        }
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { s ->
            assertFalse(driver(s).has(PARTICIPATION_CARD_TAG))
            assertFalse(driver(s).has(SettingsActivity.PARTICIPATION_YES_TAG))
        }
        // Control: with Québec French chosen the card is there, so its absence above is the language's doing.
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.ENGLISH)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertTrue(driver(s).has(PARTICIPATION_CARD_TAG))
            assertTrue(driver(s).allText().contains(context.getString(R.string.contrib_card_title)))
        }
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { s ->
            assertTrue(driver(s).has(PARTICIPATION_CARD_TAG))
            assertTrue(driver(s).has(SettingsActivity.PARTICIPATION_YES_TAG))
            assertFalse("no close button in the settings", driver(s).has(PARTICIPATION_DISMISS_TAG))
        }
        assertEquals(0, micOpened.get())
    }

    @Test
    fun closingTheCardHidesItOnTheMainScreenOnlyAndChangesNothingElse() {
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            val d = driver(s)
            assertTrue(d.has(PARTICIPATION_CARD_TAG))
            d.click(PARTICIPATION_DISMISS_TAG)
            d.await("card gone") { !d.has(PARTICIPATION_CARD_TAG) }
        }
        assertTrue(app.settings.participationCardDismissed)
        assertEquals(Declaration.UNANSWERED, app.settings.quebecDeclaration)
        assertEquals(setOf(DictationLanguage.FRENCH_QUEBEC), app.settings.languages)
        ActivityScenario.launch(SetupActivity::class.java).use { s -> assertFalse(driver(s).has(PARTICIPATION_CARD_TAG)) }
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { s ->
            assertTrue("still reachable from the settings", driver(s).has(PARTICIPATION_CARD_TAG))
        }
        assertEquals(0, micOpened.get())
    }

    @Test
    fun theCardOpensTheTestWhichAsksTheQuestionFirst() {
        val monitor = instrumentation.addMonitor(ReadingTestActivity::class.java.name, null, false).also { monitors += it }
        ActivityScenario.launch(SetupActivity::class.java).use { s -> driver(s).click(PARTICIPATION_START_TAG) }
        val opened = monitor.waitForActivityWithTimeout(10_000)
        assertNotNull("the test screen opens", opened)
        // The screen draws itself in onStart, a moment after the monitor sees the activity.
        val question = context.getString(R.string.contrib_declare_question)
        var text = ""
        val end = android.os.SystemClock.uptimeMillis() + 10_000
        while (!text.contains(question) && android.os.SystemClock.uptimeMillis() < end) {
            instrumentation.runOnMainSync { text = collectText(opened.window.decorView) }
            android.os.SystemClock.sleep(100)
        }
        instrumentation.runOnMainSync { opened.finish() }
        assertTrue(text, text.contains(question))
        assertEquals(0, micOpened.get())
    }

    private fun collectText(view: android.view.View): String = when (view) {
        is TextView -> view.text.toString()
        is android.view.ViewGroup -> (0 until view.childCount).joinToString("\n") { collectText(view.getChildAt(it)) }
        else -> ""
    }

    // ---- « non »: nothing happens ----

    @Test
    fun answeringNoThanksThePersonAndRecordsNothingNorCreatesAnyFile() {
        val models = useModels(ModelCatalog.parakeet.id)
        val before = appFiles()
        ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
            val d = driver(s)
            d.awaitTag(ReadingTestActivity.TAG_DECLARE_NO)
            assertTrue(d.allText().contains(context.getString(R.string.contrib_declare_question)))
            d.click(ReadingTestActivity.TAG_DECLARE_NO)
            d.awaitTag(ReadingTestActivity.TAG_DECLINED_CLOSE)
            assertTrue(d.allText().contains(context.getString(R.string.contrib_declined_title)))
            // No test, no recording, no way to start one.
            assertFalse(d.has(ReadingTestActivity.TAG_RECORD))
            assertFalse(d.has(ReadingTestActivity.TAG_ACCEPT_START))
            assertFalse(d.has(ReadingTestActivity.TAG_PARAGRAPH))
            d.click(ReadingTestActivity.TAG_DECLINED_CLOSE)
        }
        assertEquals(Declaration.NO, app.settings.quebecDeclaration)
        assertEquals("the microphone was never opened", 0, micOpened.get())
        assertEquals("no model was loaded", 0, models.created.get())
        val created = appFiles() - before
        assertTrue("files created: $created", created.isEmpty())

        // The card is no longer offered, on the main screen or in the settings.
        ActivityScenario.launch(SetupActivity::class.java).use { s -> assertFalse(driver(s).has(PARTICIPATION_CARD_TAG)) }
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { s ->
            val d = driver(s)
            assertFalse(d.has(PARTICIPATION_CARD_TAG))
            // The answer can be read and corrected there.
            assertTrue(d.has(SettingsActivity.PARTICIPATION_EXCLUDED_TAG))
            assertTrue(d.onViews { v -> (v.first { it.tag == SettingsActivity.PARTICIPATION_NO_TAG } as RadioButton).isChecked })
            d.click(SettingsActivity.PARTICIPATION_YES_TAG)
            d.awaitTag(PARTICIPATION_CARD_TAG)
            assertFalse(d.has(SettingsActivity.PARTICIPATION_EXCLUDED_TAG))
        }
        assertEquals(Declaration.YES, app.settings.quebecDeclaration)
        ActivityScenario.launch(SetupActivity::class.java).use { s -> assertTrue(driver(s).has(PARTICIPATION_CARD_TAG)) }
        // Corrected to yes: the test goes straight to the acceptance screen.
        ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
            driver(s).awaitTag(ReadingTestActivity.TAG_ACCEPT_START)
            assertTrue(driver(s).allText().contains(context.getString(R.string.contrib_accept_title)))
        }
        assertEquals(0, micOpened.get())
    }

    @Test
    fun noModelInstalledMeansTheTestCannotStart() {
        app.settings.quebecDeclaration = Declaration.YES
        useModels()
        ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
            val d = driver(s)
            d.awaitTag(ReadingTestActivity.TAG_NO_MODEL)
            assertFalse(d.onViews { v -> v.first { it.tag == ReadingTestActivity.TAG_ACCEPT_START }.isEnabled })
        }
        assertEquals(0, micOpened.get())
    }

    // ---- « oui »: the test, with one model ----

    @Test
    fun withOneModelTheTestShowsTheScoreAndHighlightsTheMisheardWords() {
        val target = paragraphs.indexOfFirst { it.contains("friperie") }
        val models = useModels(ModelCatalog.parakeet.id) { _, i, text ->
            if (i == target) text.replace("friperie", "boutique") else text
        }
        ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
            val d = driver(s)
            d.awaitTag(ReadingTestActivity.TAG_DECLARE_YES)
            d.click(ReadingTestActivity.TAG_DECLARE_YES)
            d.awaitTag(ReadingTestActivity.TAG_ACCEPT_START)
            // The acceptance says that nothing leaves the phone and names the model that will be used.
            assertTrue(d.allText().contains("Rien ne quitte le téléphone"))
            assertTrue(d.allText().contains(ModelCatalog.parakeet.displayName))
            assertEquals("no recording before the acceptance", 0, micOpened.get())
            d.click(ReadingTestActivity.TAG_ACCEPT_START)
            d.awaitTag(ReadingTestActivity.TAG_PARAGRAPH)
            d.readAllParagraphs(redoLabel)
            assertEquals("one opening of the clip per paragraph", paragraphs.size, micOpened.get())
            d.click(ReadingTestActivity.TAG_SCORE)

            val global = ReadingScoring.global(
                paragraphs.indices.map { i ->
                    ReadingScoring.scoreParagraph(material, i, if (i == target) paragraphs[i].replace("friperie", "boutique") else paragraphs[i])
                },
                paragraphs.size,
            )
            val tag = ReadingTestActivity.globalTag(ModelCatalog.parakeet.id)
            d.awaitTag(tag, 60_000)
            assertEquals(context.getString(R.string.reading_result_global, global.similarityPercent), d.text(tag))
            assertTrue("one error in about 224 words", global.similarityPercent in 98..99)
            // One model: no model chooser, no better-model card, nothing to share (far above 80 %).
            assertFalse(d.has(ReadingTestActivity.modelChoiceTag(ModelCatalog.parakeet.id)))
            assertFalse(d.has(ReadingTestActivity.TAG_BETTER))
            assertFalse(d.has(ReadingTestActivity.TAG_SHARE_SLOT))
            // The misheard word is highlighted (theme colours AND underline) in its paragraph, and only there.
            d.onViews { views ->
                for (i in paragraphs.indices) {
                    val tv = views.first { it.tag == ReadingTestActivity.paragraphTag(i) } as TextView
                    val spans = tv.text as Spanned
                    val backgrounds = spans.getSpans(0, spans.length, BackgroundColorSpan::class.java)
                    if (i != target) {
                        assertEquals("paragraph $i is fully understood", 0, backgrounds.size)
                        continue
                    }
                    assertEquals(1, backgrounds.size)
                    val bg = backgrounds.single()
                    assertEquals("friperie", spans.subSequence(spans.getSpanStart(bg), spans.getSpanEnd(bg)).toString())
                    assertEquals(themeColor(tv, com.google.android.material.R.attr.colorTertiaryContainer), bg.backgroundColor)
                    val fg = spans.getSpans(spans.getSpanStart(bg), spans.getSpanEnd(bg), ForegroundColorSpan::class.java).single()
                    assertEquals(themeColor(tv, com.google.android.material.R.attr.colorOnTertiaryContainer), fg.foregroundColor)
                    assertEquals("underlined too: never the colour alone", 1,
                        spans.getSpans(spans.getSpanStart(bg), spans.getSpanEnd(bg), UnderlineSpan::class.java).size)
                }
            }
            assertTrue(d.allText().contains(context.getString(R.string.reading_result_misheard, "friperie")))
            assertEquals(1, models.created.get())
            // Memory only: the result screen says so, and the recordings are dropped when the screen closes.
            assertTrue(d.allText().contains(context.getString(R.string.reading_result_kept)))
            var session: io.github.remtav.dictee.reading.ReadingSession? = null
            s.onActivity { session = ViewModelProvider(it)[ReadingTestModel::class.java].session }
            assertEquals(paragraphs.size, session!!.recordedCount)
            d.click(ReadingTestActivity.TAG_CLOSE)
            d.await("recordings dropped with the screen") { session!!.recordedCount == 0 && session!!.result == null }
        }
    }

    // ---- several models ----

    @Test
    fun withSeveralModelsEachIsScoredAndABetterOneIsSuggested() {
        val parakeet = ModelCatalog.parakeet.id
        val qwen = ModelCatalog.qwen3.id
        val whisper = ModelCatalog.whisperTurbo.id
        // Active model: Qwen3-ASR, which loses every fourth word; Parakeet (recommended) and Whisper hear everything.
        app.settings.activeModelId = qwen
        useModels(parakeet, qwen, whisper) { model, _, text ->
            if (model == qwen) ScriptedReadingModels.dropEveryFourthWord(text) else text
        }
        ActivityScenario.launch<ReadingTestActivity>(Intent(context, ReadingTestActivity::class.java)).use { s ->
            val d = driver(s)
            d.awaitTag(ReadingTestActivity.TAG_DECLARE_YES)
            d.click(ReadingTestActivity.TAG_DECLARE_YES)
            d.awaitTag(ReadingTestActivity.TAG_ACCEPT_START)
            val used = d.allText()
            for (id in listOf(parakeet, qwen, whisper)) assertTrue(used.contains(ModelCatalog.byId(id)!!.displayName))
            d.click(ReadingTestActivity.TAG_ACCEPT_START)
            d.awaitTag(ReadingTestActivity.TAG_PARAGRAPH)
            d.readAllParagraphs(redoLabel)
            d.click(ReadingTestActivity.TAG_SCORE)

            d.awaitTag(ReadingTestActivity.globalTag(qwen), 90_000)
            val qwenScores = paragraphs.indices.map { i ->
                ReadingScoring.scoreParagraph(material, i, ScriptedReadingModels.dropEveryFourthWord(paragraphs[i]))
            }
            val qwenPercent = ReadingScoring.global(qwenScores, paragraphs.size).similarityPercent
            assertTrue("a quarter of the words lost", qwenPercent < 80)
            // One chooser line per model, with its global percent; the active model's detail is shown first.
            for (id in listOf(parakeet, qwen, whisper)) assertTrue(d.has(ReadingTestActivity.modelChoiceTag(id)))
            assertEquals(
                context.getString(R.string.reading_result_model_line, ModelCatalog.qwen3.displayName, qwenPercent),
                d.text(ReadingTestActivity.modelChoiceTag(qwen)),
            )
            assertEquals(context.getString(R.string.reading_result_global, qwenPercent), d.text(ReadingTestActivity.globalTag(qwen)))
            val highlighted = { i: Int ->
                d.onViews { v ->
                    val sp = (v.first { it.tag == ReadingTestActivity.paragraphTag(i) } as TextView).text as Spanned
                    sp.getSpans(0, sp.length, BackgroundColorSpan::class.java).size
                }
            }
            assertTrue("Qwen3-ASR's detail has misheard words", highlighted(0) > 0)

            // The recommended model hears everything: no sharing; a better model is suggested and can be chosen.
            assertFalse(d.has(ReadingTestActivity.TAG_SHARE_SLOT))
            d.awaitTag(ReadingTestActivity.TAG_BETTER)
            assertTrue(d.allText().contains(context.getString(R.string.reading_better_title)))
            assertEquals(qwen, app.settings.activeModelId)
            d.click(ReadingTestActivity.TAG_BETTER_CHOOSE)
            d.awaitTag(ReadingTestActivity.TAG_BETTER_CHOSEN)
            val suggested = app.settings.activeModelId
            assertTrue("a model that heard everything is now active", suggested == parakeet || suggested == whisper)
            assertFalse(d.has(ReadingTestActivity.TAG_BETTER_CHOOSE))

            // Another model's detail: Parakeet's paragraph has no highlight.
            d.click(ReadingTestActivity.modelChoiceTag(parakeet))
            d.await("Parakeet's detail") { highlighted(0) == 0 }
        }
    }

    // The sharing offer above the threshold (step 4.3) is tested in ContributionInstrumentedTest.

    @Test
    fun stoppingAfterOneParagraphScoresOnlyWhatWasRead() {
        useModels(ModelCatalog.parakeet.id)
        app.settings.quebecDeclaration = Declaration.YES
        ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
            val d = driver(s)
            d.awaitTag(ReadingTestActivity.TAG_ACCEPT_START)
            d.click(ReadingTestActivity.TAG_ACCEPT_START)
            d.awaitTag(ReadingTestActivity.TAG_PARAGRAPH)
            assertFalse("no score before something is read", d.has(ReadingTestActivity.TAG_SCORE))
            d.click(ReadingTestActivity.TAG_RECORD)
            d.await("recorded") { d.text(ReadingTestActivity.TAG_RECORD) == redoLabel }
            d.click(ReadingTestActivity.TAG_SCORE)
            d.awaitTag(ReadingTestActivity.globalTag(ModelCatalog.parakeet.id), 60_000)
            assertTrue(d.allText().contains(context.getString(R.string.reading_result_partial, 1, paragraphs.size)))
            assertFalse(d.has(ReadingTestActivity.TAG_SHARE_SLOT))
        }
    }

    private fun themeColor(view: android.view.View, attr: Int): Int {
        val tv = TypedValue()
        assertTrue(view.context.theme.resolveAttribute(attr, tv, true))
        return tv.data
    }

    // ---- issue #20: « Arrêter » answers every tap ----

    @Test
    fun stopButtonStopsTheRecordingAtEveryTapAndTheSecondCounterStillShows() {
        useModels(ModelCatalog.parakeet.id)
        app.settings.quebecDeclaration = Declaration.YES
        // A clip paced in real time, longer than the test waits: only « Arrêter » can end the recording.
        TestHooks.audioSourceFactory = AudioSourceFactory {
            micOpened.incrementAndGet()
            PcmAudioSource(
                listOf(PcmAudio(FloatArray(15 * 16000) { 0.05f }, 16000)),
                realTime = true, leadSeconds = 0f, tailSeconds = 0f,
            )
        }
        val stopLabel = context.getString(R.string.reading_stop)
        ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
            val d = driver(s)
            d.awaitTag(ReadingTestActivity.TAG_ACCEPT_START)
            d.click(ReadingTestActivity.TAG_ACCEPT_START)
            d.awaitTag(ReadingTestActivity.TAG_RECORD)
            repeat(20) { i ->
                d.click(ReadingTestActivity.TAG_RECORD)
                d.await("recording $i started") { d.text(ReadingTestActivity.TAG_RECORD) == stopLabel }
                if (i == 0) {
                    // The counter reaches 2 s without the screen being rebuilt.
                    d.await("counter at 2 s", 6_000) {
                        d.text(ReadingTestActivity.TAG_RECORD_STATE) ==
                            context.getString(R.string.reading_recording, 2, io.github.remtav.dictee.reading.ParagraphRecorder.MAX_SECONDS)
                    }
                } else {
                    android.os.SystemClock.sleep(1_000)
                }
                val tapped = android.os.SystemClock.uptimeMillis()
                d.click(ReadingTestActivity.TAG_RECORD)
                d.await("recording $i stopped", 300) { d.text(ReadingTestActivity.TAG_RECORD) != stopLabel }
                assertTrue("tap $i took too long", android.os.SystemClock.uptimeMillis() - tapped < 600)
            }
        }
    }
}
