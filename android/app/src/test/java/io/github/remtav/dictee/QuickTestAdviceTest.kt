package io.github.remtav.dictee

import io.github.remtav.dictee.stt.ModeOutcome
import io.github.remtav.dictee.stt.ModeOutcome.Scored
import io.github.remtav.dictee.stt.QuickTestAdvice
import io.github.remtav.dictee.stt.TranscriptionMode.PHONE
import io.github.remtav.dictee.stt.TranscriptionMode.SERVER
import io.github.remtav.dictee.stt.VerdictReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #36a: the automatic recommendation after the quick test, on both sides of each threshold. */
class QuickTestAdviceTest {
    private fun decide(phoneSim: Double, phoneSec: Double, serverSim: Double, serverSec: Double = 2.0) =
        QuickTestAdvice.decide(Scored(phoneSim, phoneSec), Scored(serverSim, serverSec))

    @Test fun phoneRecommendedWhenAllThresholdsAreMet() {
        val v = decide(0.95, 3.0, 0.975)
        assertEquals(PHONE, v.mode)
        assertEquals(VerdictReason.PHONE_GOOD_ENOUGH, v.reason)
    }

    @Test fun similarityThresholdIs90Percent() {
        // 36 words out of 40 = 0.9 exactly (1 − 4/40): recommended; 35 out of 40 = 0.875: not.
        assertEquals(PHONE, decide(1.0 - 4.0 / 40, 3.0, 0.925).mode)
        assertEquals(PHONE, decide(0.90, 3.0, 0.90).mode)
        val below = decide(1.0 - 5.0 / 40, 3.0, 0.875)
        assertEquals(SERVER, below.mode)
        assertEquals(VerdictReason.PHONE_TOO_INACCURATE, below.reason)
        assertEquals(SERVER, decide(0.899, 3.0, 0.899).mode)
    }

    @Test fun gapThresholdIs5Points() {
        // Server 100 %, phone 95 % (2 words of 40): exactly 5 points, still the phone.
        assertEquals(PHONE, decide(1.0 - 2.0 / 40, 3.0, 1.0).mode)
        assertEquals(PHONE, decide(0.95, 3.0, 1.0).mode)
        // Phone 92.5 %, server 100 %: 7.5 points, server.
        val far = decide(1.0 - 3.0 / 40, 3.0, 1.0)
        assertEquals(SERVER, far.mode)
        assertEquals(VerdictReason.PHONE_FAR_BEHIND_SERVER, far.reason)
        assertEquals(SERVER, decide(0.94, 3.0, 1.0).mode)
    }

    @Test fun phoneBetterThanServerIsStillThePhone() {
        assertEquals(PHONE, decide(0.975, 3.0, 0.925).mode)
    }

    @Test fun delayThresholdIs5Seconds() {
        assertEquals(PHONE, decide(0.95, 5.0, 0.975).mode)
        assertEquals(PHONE, decide(0.95, 0.4, 0.975).mode)
        val slow = decide(0.95, 5.01, 0.975)
        assertEquals(SERVER, slow.mode)
        assertEquals(VerdictReason.PHONE_TOO_SLOW, slow.reason)
        assertEquals(SERVER, decide(0.95, 12.0, 0.975).mode)
    }

    @Test fun serverDelayDoesNotChangeTheVerdict() {
        assertEquals(PHONE, decide(0.95, 3.0, 0.975, serverSec = 29.0).mode)
        assertEquals(SERVER, decide(0.85, 3.0, 0.975, serverSec = 0.2).mode)
    }

    @Test fun unreachableServerMeansPhone() {
        for (phone in listOf(Scored(0.5, 20.0), Scored(0.99, 1.0), ModeOutcome.TooSlow, ModeOutcome.Unavailable)) {
            val v = QuickTestAdvice.decide(phone, ModeOutcome.Unavailable)
            assertEquals(phone.toString(), PHONE, v.mode)
            assertEquals(VerdictReason.SERVER_UNAVAILABLE, v.reason)
        }
    }

    @Test fun serverOverBudgetIsTreatedAsUnavailable() {
        assertEquals(PHONE, QuickTestAdvice.decide(Scored(0.7, 4.0), ModeOutcome.TooSlow).mode)
    }

    @Test fun phoneOverBudgetOrNotInstalledMeansServer() {
        val server = Scored(0.97, 2.0)
        assertEquals(VerdictReason.PHONE_TOO_SLOW, QuickTestAdvice.decide(ModeOutcome.TooSlow, server).reason)
        assertEquals(SERVER, QuickTestAdvice.decide(ModeOutcome.TooSlow, server).mode)
        val notTested = QuickTestAdvice.decide(ModeOutcome.Unavailable, server)
        assertEquals(SERVER, notTested.mode)
        assertEquals(VerdictReason.PHONE_NOT_TESTED, notTested.reason)
    }

    @Test fun bothFailedKeepsTheVoiceOnThePhone() {
        assertEquals(PHONE, QuickTestAdvice.decide(ModeOutcome.TooSlow, ModeOutcome.TooSlow).mode)
        assertEquals(PHONE, QuickTestAdvice.decide(ModeOutcome.Unavailable, ModeOutcome.Unavailable).mode)
    }

    @Test fun budgetIs30SecondsOfTranscription() {
        assertEquals(30, QuickTestAdvice.TRANSCRIPTION_BUDGET_SECONDS)
        assertEquals(25, QuickTestAdvice.READING_BUDGET_SECONDS)
        assertTrue(QuickTestAdvice.outcome(0.9, 30.0) is Scored)
        assertTrue(QuickTestAdvice.outcome(0.9, 29.9) is Scored)
        assertEquals(ModeOutcome.TooSlow, QuickTestAdvice.outcome(0.99, 30.01))
        assertEquals(ModeOutcome.TooSlow, QuickTestAdvice.outcome(0.99, 90.0))
        assertEquals(Scored(0.8, 4.0), QuickTestAdvice.outcome(0.8, 4.0))
    }
}
