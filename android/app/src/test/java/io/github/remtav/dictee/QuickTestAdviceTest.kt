package io.github.remtav.dictee

import io.github.remtav.dictee.stt.ModeOutcome
import io.github.remtav.dictee.stt.ModeOutcome.Scored
import io.github.remtav.dictee.stt.ModeOutcome.TooSlow
import io.github.remtav.dictee.stt.ModeOutcome.Unavailable
import io.github.remtav.dictee.stt.ModelPick
import io.github.remtav.dictee.stt.QuickTestAdvice
import io.github.remtav.dictee.stt.QuickTestVerdict
import io.github.remtav.dictee.stt.VerdictReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The automatic recommendation after the quick test (issue #36), light phone model against best phone model,
 * on either side of each threshold. The thresholds are estimates (see [QuickTestAdvice]).
 */
class QuickTestAdviceTest {
    private fun verdict(pick: ModelPick, reason: VerdictReason) = QuickTestVerdict(pick, reason)

    /** On 40 words: one missed word = 2.5 points. */
    private fun sim(missed: Int) = 1.0 - missed / 40.0

    @Test fun lightGoodEnoughAndBestSlowRecommendsTheLightOne() {
        assertEquals(
            verdict(ModelPick.LIGHT, VerdictReason.LIGHT_GOOD_ENOUGH_AND_FASTER),
            QuickTestAdvice.decide(light = Scored(sim(2), 1.5), best = Scored(sim(1), 9.0)),
        )
    }

    @Test fun bestQuickEnoughStays() {
        assertEquals(
            verdict(ModelPick.BEST, VerdictReason.BEST_PRECISE_AND_QUICK),
            QuickTestAdvice.decide(light = Scored(1.0, 1.0), best = Scored(1.0, 5.0)),
        )
        // Just over 5 s: the light one.
        assertEquals(ModelPick.LIGHT, QuickTestAdvice.decide(Scored(1.0, 1.0), Scored(1.0, 5.01))!!.pick)
    }

    @Test fun lightSimilarityThreshold() {
        // 4 missed words of 40 = exactly 90 %: still good enough (float noise guarded).
        assertEquals(ModelPick.LIGHT, QuickTestAdvice.decide(Scored(sim(4), 1.0), Scored(sim(3), 10.0))!!.pick)
        assertEquals(
            verdict(ModelPick.BEST, VerdictReason.LIGHT_TOO_INACCURATE),
            QuickTestAdvice.decide(Scored(sim(5), 1.0), Scored(sim(4), 10.0)),
        )
    }

    @Test fun gapThreshold() {
        // 2 words (5 points) behind: within the gap.
        assertEquals(ModelPick.LIGHT, QuickTestAdvice.decide(Scored(sim(2), 1.0), Scored(1.0, 10.0))!!.pick)
        // 3 words (7.5 points) behind: too far.
        assertEquals(
            verdict(ModelPick.BEST, VerdictReason.LIGHT_FAR_BEHIND),
            QuickTestAdvice.decide(Scored(sim(3), 1.0), Scored(1.0, 10.0)),
        )
    }

    @Test fun onlyOneScoredWins() {
        for (missing in listOf<ModeOutcome>(TooSlow, Unavailable)) {
            assertEquals(verdict(ModelPick.BEST, VerdictReason.LIGHT_NOT_TESTED), QuickTestAdvice.decide(missing, Scored(0.5, 20.0)))
            assertEquals(verdict(ModelPick.LIGHT, VerdictReason.BEST_NOT_TESTED), QuickTestAdvice.decide(Scored(0.5, 2.0), missing))
        }
    }

    @Test fun nothingScoredNoRecommendation() {
        for (a in listOf<ModeOutcome>(TooSlow, Unavailable)) for (b in listOf<ModeOutcome>(TooSlow, Unavailable)) {
            assertNull(QuickTestAdvice.decide(a, b))
        }
    }

    @Test fun overTheBudgetIsTooSlow() {
        assertEquals(Scored(0.9, 30.0), QuickTestAdvice.outcome(0.9, 30.0))
        assertEquals(TooSlow, QuickTestAdvice.outcome(0.9, 30.5))
    }

    @Test fun theMinuteHoldsTogether() {
        assertEquals(55, QuickTestAdvice.READING_BUDGET_SECONDS + QuickTestAdvice.TRANSCRIPTION_BUDGET_SECONDS)
    }
}
