package io.github.remtav.dictee.reading

import io.github.remtav.dictee.stt.DictationLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #15, lot 4, step 4.2: who is offered the reading test, where, and what « non » changes. */
class ParticipationTest {
    private val quebec = setOf(DictationLanguage.FRENCH_QUEBEC)

    @Test
    fun noCardWithoutQuebecFrenchAmongTheChosenLanguages() {
        val others = listOf(
            setOf(DictationLanguage.FRENCH_FRANCE),
            setOf(DictationLanguage.ENGLISH),
            setOf(DictationLanguage.FRENCH_FRANCE, DictationLanguage.ENGLISH, DictationLanguage.SPANISH),
        )
        for (languages in others) {
            val p = Participation(languages)
            assertFalse("$languages", p.cardOnSetup(setupComplete = true))
            assertFalse("$languages", p.cardInSettings)
            // Nothing to answer either: the question belongs to the offer.
            assertFalse("$languages", p.declarationInSettings)
        }
    }

    @Test
    fun withQuebecFrenchTheCardIsOnTheMainScreenOnlyAtTheEndOfTheSetup() {
        val p = Participation(quebec + DictationLanguage.ENGLISH)
        assertTrue(p.cardOnSetup(setupComplete = true))
        assertFalse("setup not finished: no card yet", p.cardOnSetup(setupComplete = false))
        assertTrue(p.cardInSettings)
        assertTrue(p.declarationInSettings)
    }

    @Test
    fun closingTheCardHidesItOnTheMainScreenButNotInTheSettings() {
        val p = Participation(quebec).dismissed()
        assertFalse(p.cardOnSetup(setupComplete = true))
        assertTrue(p.cardInSettings)
        // Closing changes nothing else: the person is not excluded and can still take the test.
        assertFalse(p.excluded)
        assertEquals(ReadingEntry.DECLARE, p.entry)
    }

    @Test
    fun theQuestionComesFirstUnlessItIsAnswered() {
        assertEquals(ReadingEntry.DECLARE, Participation(quebec).entry)
        assertEquals(ReadingEntry.ACCEPT, Participation(quebec, Declaration.YES).entry)
        assertEquals(ReadingEntry.DECLINED, Participation(quebec, Declaration.NO).entry)
    }

    @Test
    fun answeringNoExcludesThePersonEverywhere() {
        val p = Participation(quebec).answered(yes = false)
        assertEquals(Declaration.NO, p.declaration)
        assertTrue(p.excluded)
        assertFalse(p.cardOnSetup(setupComplete = true))
        assertFalse(p.cardInSettings)
        // The answer itself stays visible, so that it can be corrected.
        assertTrue(p.declarationInSettings)
        assertEquals(ReadingEntry.DECLINED, p.entry)
    }

    @Test
    fun theAnswerCanBeCorrectedInTheSettings() {
        val excluded = Participation(quebec).answered(yes = false)
        val corrected = excluded.answered(yes = true)
        assertEquals(Declaration.YES, corrected.declaration)
        assertFalse(corrected.excluded)
        assertTrue("the card is back in the settings", corrected.cardInSettings)
        assertTrue("and on the main screen", corrected.cardOnSetup(setupComplete = true))
        assertEquals(ReadingEntry.ACCEPT, corrected.entry)
        // And the other way round.
        assertTrue(corrected.answered(yes = false).excluded)
    }

    @Test
    fun aCorrectedAnswerKeepsTheClosedCardClosedOnTheMainScreen() {
        val p = Participation(quebec).dismissed().answered(yes = false).answered(yes = true)
        assertFalse(p.cardOnSetup(setupComplete = true))
        assertTrue(p.cardInSettings)
    }

    @Test
    fun anAnswerStaysEditableAfterQuebecFrenchIsDropped() {
        val p = Participation(setOf(DictationLanguage.ENGLISH), Declaration.NO)
        assertTrue(p.declarationInSettings)
        assertFalse(p.cardInSettings)
        // Quebec French chosen again: still excluded until the answer is corrected.
        assertFalse(p.copy(languages = quebec).cardInSettings)
        assertTrue(p.copy(languages = quebec).answered(yes = true).cardInSettings)
    }

    @Test
    fun theStoredNameReadsBackAndUnknownMeansUnanswered() {
        for (d in Declaration.entries) assertEquals(d, Declaration.fromName(d.name))
        assertEquals(Declaration.UNANSWERED, Declaration.fromName(null))
        assertEquals(Declaration.UNANSWERED, Declaration.fromName("peut-être"))
    }
}
