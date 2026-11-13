package io.github.remtav.dictee.reading

import io.github.remtav.dictee.audio.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Issue #15, lot 4, step 4.2: the steps of the reading test, driven with a scripted transcriber (the
 * same seam the instrumented test uses with the scripted engine). The audio of paragraph i is a clip
 * of `16000 + i` samples, so the scripted models know which paragraph they are given.
 */
class ReadingSessionTest {
    private val material = ReadingTestSupport.material
    private val paragraphs = material.text.paragraphs
    private val a = ReadingModel("model-a", "Modèle A")
    private val b = ReadingModel("model-b", "Modèle B")
    private val c = ReadingModel("model-c", "Modèle C")

    private fun audio(i: Int) = PcmAudio(FloatArray(16000 + i), 16000)
    private fun paragraphOf(audio: PcmAudio) = audio.samples.size - 16000

    private fun session(
        models: List<ReadingModel>,
        declaration: Declaration = Declaration.YES,
        recommended: String = a.id,
        active: String = a.id,
        saved: MutableList<Declaration> = mutableListOf(),
    ) = ReadingSession(material, models, recommended, active, declaration) { saved += it }

    /** Starts a reading with every paragraph recorded (or the first [count] ones). */
    private fun reading(models: List<ReadingModel>, count: Int = paragraphs.size, recommended: String = a.id, active: String = a.id): ReadingSession {
        val s = session(models, recommended = recommended, active = active)
        assertTrue(s.accept())
        for (i in 0 until count) {
            assertTrue(s.store(i, audio(i)))
            s.next()
        }
        return s
    }

    /** Reads the paragraph perfectly. */
    private val perfect = ReadingTranscriber { _, audio -> paragraphs[paragraphOf(audio)] }

    // ---- declaration and exclusion ----

    @Test
    fun theFirstStepFollowsTheStoredAnswer() {
        assertEquals(ReadingPhase.DECLARE, session(listOf(a), Declaration.UNANSWERED).phase)
        assertEquals(ReadingPhase.ACCEPT, session(listOf(a), Declaration.YES).phase)
        assertEquals(ReadingPhase.DECLINED, session(listOf(a), Declaration.NO).phase)
    }

    @Test
    fun answeringNoStopsEverythingNothingIsRecordedAndTheAnswerIsKept() {
        val saved = mutableListOf<Declaration>()
        val s = session(listOf(a), Declaration.UNANSWERED, saved = saved)
        s.answer(yes = false)
        assertEquals(ReadingPhase.DECLINED, s.phase)
        assertEquals(listOf(Declaration.NO), saved)
        assertFalse("no microphone", s.mayRecord)
        assertFalse("no recording is accepted", s.store(0, audio(0)))
        assertNull(s.audioOf(0))
        assertEquals(0, s.recordedCount)
        assertFalse(s.canScore)
        try {
            s.accept()
            fail("pas d'acceptation après « non »")
        } catch (_: IllegalStateException) {
        }
        try {
            s.beginScoring()
            fail("pas de score après « non »")
        } catch (_: IllegalStateException) {
        }
        assertNull(s.result)
    }

    @Test
    fun answeringYesLeadsToTheAcceptanceAndNothingIsRecordedBeforeIt() {
        val saved = mutableListOf<Declaration>()
        val s = session(listOf(a), Declaration.UNANSWERED, saved = saved)
        s.answer(yes = true)
        assertEquals(listOf(Declaration.YES), saved)
        assertEquals(ReadingPhase.ACCEPT, s.phase)
        assertFalse("before the acceptance", s.mayRecord)
        assertFalse(s.store(0, audio(0)))
        assertTrue(s.accept())
        assertEquals(ReadingPhase.READING, s.phase)
        assertTrue(s.mayRecord)
    }

    @Test
    fun theQuestionIsAnsweredOnlyOnce() {
        val s = session(listOf(a), Declaration.YES)
        try {
            s.answer(yes = false)
            fail("la question n'est posée qu'avant l'acceptation")
        } catch (_: IllegalStateException) {
        }
        assertEquals(Declaration.YES, s.declaration)
    }

    @Test
    fun withoutAnInstalledModelTheTestCannotStart() {
        val s = session(emptyList())
        assertFalse(s.canStart)
        assertFalse(s.accept())
        assertEquals(ReadingPhase.ACCEPT, s.phase)
        assertFalse(s.store(0, audio(0)))
    }

    // ---- reading, paragraph by paragraph ----

    @Test
    fun theParagraphsAreReadOneByOneAndOneCanBeRedone() {
        val s = session(listOf(a))
        s.accept()
        assertEquals(0, s.current)
        assertFalse("cannot skip an unread paragraph", s.canGoNext)
        assertFalse(s.canGoPrevious)
        s.store(0, audio(0))
        assertTrue(s.canGoNext)
        s.next()
        assertEquals(1, s.current)
        assertTrue(s.canGoPrevious)
        // Redo paragraph 0: the new recording replaces the old one.
        s.previous()
        val again = PcmAudio(FloatArray(20000), 16000)
        s.store(0, again)
        assertEquals(again, s.audioOf(0))
        assertEquals(1, s.recordedCount)
        assertFalse(s.allRecorded)
        s.store(0, null)
        assertEquals(0, s.recordedCount)
        assertFalse("nothing read: no score", s.canScore)
    }

    @Test
    fun theLastParagraphHasNoNext() {
        val s = reading(listOf(a))
        assertEquals(paragraphs.size - 1, s.current)
        assertFalse(s.canGoNext)
        assertTrue(s.allRecorded)
    }

    // ---- score ----

    @Test
    fun aPerfectReadingScoresOneHundredPercentWithOneModelAndNoOffer() {
        val s = reading(listOf(a))
        val r = s.score(perfect)!!
        assertEquals(ReadingPhase.RESULT, s.phase)
        assertEquals(material.version, r.textVersion)
        assertEquals(setOf(a.id), r.globalScores.keys)
        val g = r.globalScores.getValue(a.id)
        assertTrue(g.complete)
        assertEquals(100, g.similarityPercent)
        assertEquals(paragraphs.size, r.paragraphs.size)
        for (p in r.paragraphs) {
            assertEquals(100, p.scores.getValue(a.id).similarityPercent)
            assertTrue(p.scores.getValue(a.id).misheard.isEmpty())
            assertEquals(paragraphs[p.index], p.transcriptions.getValue(a.id))
            assertEquals(16000, p.audio.sampleRate)
        }
        assertFalse(r.offerSharing)
        assertNull(r.betterModelId)
        assertNull("the region is never asked in step 4.2", r.region)
        assertEquals(a.id, r.recommendedModelId)
        assertEquals(a.id, r.activeModelId)
    }

    @Test
    fun severalModelsAreEachScoredOnEveryParagraphAndOneModelAtATime() {
        val s = reading(listOf(a, b, c))
        val order = mutableListOf<String>()
        val r = s.score({ model, audio -> order += model; paragraphs[paragraphOf(audio)] })!!
        assertEquals(listOf(a.id, b.id, c.id), r.globalScores.keys.toList())
        // Model by model, so that a model is loaded once.
        assertEquals(paragraphs.size * 3, order.size)
        assertEquals(order.sortedBy { listOf(a.id, b.id, c.id).indexOf(it) }, order)
        for (p in r.paragraphs) {
            assertEquals(setOf(a.id, b.id, c.id), p.transcriptions.keys)
            assertEquals(setOf(a.id, b.id, c.id), p.scores.keys)
        }
        assertNull("nobody is clearly better than a perfect model", r.betterModelId)
    }

    @Test
    fun theScoreAndTheMisheardWordsComeFromTheTranscription() {
        val s = reading(listOf(a))
        val target = paragraphs.indexOfFirst { it.contains("friperie") }
        val r = s.score(ReadingTranscriber { _, audio ->
            val i = paragraphOf(audio)
            if (i == target) paragraphs[i].replace("friperie", "boutique") else paragraphs[i]
        })!!
        val p = r.paragraphs.single { it.index == target }.scores.getValue(a.id)
        assertEquals(listOf("friperie"), p.misheard.map { it.word })
        assertEquals("boutique", p.misheard.single().heard)
        assertEquals(1, p.errors)
        assertEquals(similarityPercent(p.referenceWords, 1), p.similarityPercent)
        val g = r.globalScores.getValue(a.id)
        assertEquals(1, g.errors)
        assertEquals(99, g.similarityPercent)
        assertFalse(r.offerSharing)
    }

    @Test
    fun theDisplayedPercentIsRoundedDownInIntegers() {
        assertEquals(100, similarityPercent(0, 0))
        assertEquals(80, similarityPercent(10, 2))
        assertEquals(70, similarityPercent(10, 3))
        assertEquals(71, similarityPercent(100, 29))
        assertEquals(79, similarityPercent(1000, 205))
        assertEquals(0, similarityPercent(10, 25))
        assertEquals(66, similarityPercent(3, 1))
    }

    @Test
    fun sharingIsOfferedOnlyAboveTwentyPercentWithTheRecommendedModel() {
        // Model A (recommended) hears nothing of the three longest paragraphs: far above 20 %.
        val longest = paragraphs.indices.sortedByDescending { material.normalizer.tokens(paragraphs[it]).size }.take(3).toSet()
        val bad = ReadingTranscriber { model, audio ->
            val i = paragraphOf(audio)
            if (model == a.id && i in longest) "" else paragraphs[i]
        }
        val r = reading(listOf(a, b)).score(bad)!!
        val ga = r.globalScores.getValue(a.id)
        assertTrue(ga.errors * 100 > 20 * ga.referenceWords)
        assertTrue(r.offerSharing)
        assertTrue("79 % or less is shown", ga.similarityPercent < 80)
        // B understood everything: clearly better than the active model A.
        assertEquals(b.id, r.betterModelId)
        assertEquals(ReadingThresholds.offerSharing(r.globalScores, a.id), r.offerSharing)

        // Only one paragraph lost: below the threshold, nothing offered and nobody clearly better.
        val one = ReadingTranscriber { model, audio ->
            val i = paragraphOf(audio)
            if (model == a.id && i == longest.first()) "" else paragraphs[i]
        }
        val r2 = reading(listOf(a, b)).score(one)!!
        val g2 = r2.globalScores.getValue(a.id)
        assertTrue(g2.errors * 100 <= 20 * g2.referenceWords)
        assertFalse(r2.offerSharing)
    }

    @Test
    fun noOfferWhenTheRecommendedModelDidNotRun() {
        val allWrong = ReadingTranscriber { _, _ -> "" }
        val r = reading(listOf(b), recommended = a.id, active = b.id).score(allWrong)!!
        assertEquals(0, r.globalScores.getValue(b.id).similarityPercent)
        assertFalse("not on the word of another model", r.offerSharing)
    }

    @Test
    fun aClearlyBetterInstalledModelIsSuggestedInsteadOfTheActiveOne() {
        val mediocre = ReadingTranscriber { model, audio ->
            val i = paragraphOf(audio)
            if (model == a.id) paragraphs[i].split(' ').filterIndexed { n, _ -> n % 4 != 3 }.joinToString(" ") else paragraphs[i]
        }
        val r = reading(listOf(a, b, c), active = a.id).score(mediocre)!!
        assertEquals(b.id, r.betterModelId)
        // The active model is B: nobody is clearly better than it.
        val r2 = reading(listOf(a, b, c), active = b.id).score(mediocre)!!
        assertNull(r2.betterModelId)
    }

    @Test
    fun stoppingEarlyScoresWhatWasReadButOffersNothing() {
        val s = reading(listOf(a, b), count = 2)
        assertTrue(s.canScore)
        val allWrongForA = ReadingTranscriber { model, audio -> if (model == a.id) "" else paragraphs[paragraphOf(audio)] }
        val r = s.score(allWrongForA)!!
        assertEquals(2, r.paragraphs.size)
        assertFalse(r.globalScores.getValue(a.id).complete)
        assertEquals(2, r.globalScores.getValue(a.id).paragraphsScored)
        assertFalse("only the complete text counts for the offer", r.offerSharing)
        assertNull("nor for a model suggestion", r.betterModelId)
    }

    @Test
    fun aModelThatFailsIsReportedAndTheOthersCarryOn() {
        val flaky = ReadingTranscriber { model, audio ->
            if (model == b.id) throw IllegalStateException("mémoire insuffisante")
            paragraphs[paragraphOf(audio)]
        }
        val r = reading(listOf(a, b, c)).score(flaky)!!
        assertEquals(setOf(a.id, c.id), r.globalScores.keys)
        assertEquals("mémoire insuffisante", r.modelErrors[b.id])
        assertTrue(r.paragraphs.all { b.id !in it.scores })
    }

    @Test
    fun cancellingGoesBackToReadingWithTheRecordingsKept() {
        val s = reading(listOf(a, b))
        var calls = 0
        val r = s.score({ _, audio -> calls++; paragraphs[paragraphOf(audio)] }, cancelled = { calls >= 3 })
        assertNull(r)
        assertEquals(ReadingPhase.READING, s.phase)
        assertEquals(paragraphs.size, s.recordedCount)
        assertNull(s.result)
        // And it can be started again.
        assertNotNull(s.score(perfect))
    }

    @Test
    fun anUnexpectedErrorLeavesTheTestReadable() {
        val s = reading(listOf(a))
        try {
            s.score(ReadingTranscriber { _, _ -> throw Error("boum") })
            fail("l'erreur doit remonter")
        } catch (e: Error) {
            assertEquals("boum", e.message)
        }
        assertEquals(ReadingPhase.READING, s.phase)
        assertEquals(paragraphs.size, s.recordedCount)
    }

    @Test
    fun progressNamesTheModelAndTheParagraph() {
        val seen = mutableListOf<ReadingProgress>()
        reading(listOf(a, b), count = 2).score(perfect, progress = { seen += it })
        assertEquals(4, seen.size)
        assertEquals(listOf(a.id, a.id, b.id, b.id), seen.map { it.model.id })
        assertEquals(listOf(0, 1, 0, 1), seen.map { it.paragraph })
        assertTrue(seen.all { it.modelCount == 2 && it.paragraphCount == paragraphs.size })
    }

    // ---- the audio does not outlive the screen ----

    @Test
    fun clearingDropsEveryRecordingAndTheResult() {
        val s = reading(listOf(a))
        s.score(perfect)
        assertNotNull(s.result)
        s.clear()
        assertNull(s.result)
        assertEquals(0, s.recordedCount)
        assertTrue((0 until paragraphs.size).all { s.audioOf(it) == null })
    }

    @Test
    fun redoingTheTestStartsAgainFromTheFirstParagraphWithoutAudio() {
        val s = reading(listOf(a))
        s.score(perfect)
        s.restart()
        assertEquals(ReadingPhase.READING, s.phase)
        assertEquals(0, s.current)
        assertEquals(0, s.recordedCount)
        assertNull(s.result)
    }
}
