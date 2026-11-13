package io.github.remtav.dictee.stt

/**
 * What the quick test (issue #36, step 5) learned about one phone model. Pure data, built by the screen.
 */
sealed interface ModeOutcome {
    /** The model answered within the budget: [similarity] is 1 − WER (0..1, `ReadingScore`), [delaySeconds] the transcription delay. */
    data class Scored(val similarity: Double, val delaySeconds: Double) : ModeOutcome

    /** The model did not answer within [QuickTestAdvice.TRANSCRIPTION_BUDGET_SECONDS]: shown as « trop lent ». */
    data object TooSlow : ModeOutcome

    /** The model could not be tested: not installed, or it failed. */
    data object Unavailable : ModeOutcome
}

/** Which of the two phone models the quick test recommends. */
enum class ModelPick { BEST, LIGHT }

/** Why [QuickTestAdvice.decide] answered as it did. The screen picks its one sentence from it. */
enum class VerdictReason {
    /** Both answered well: the most precise one is quick enough, so it stays. */
    BEST_PRECISE_AND_QUICK,
    /** The light model is nearly as good and the best one is slow: the light one is recommended. */
    LIGHT_GOOD_ENOUGH_AND_FASTER,
    /** The light model scored below [QuickTestAdvice.LIGHT_MIN_SIMILARITY]. */
    LIGHT_TOO_INACCURATE,
    /** The light model is more than [QuickTestAdvice.MAX_GAP] behind the best one. */
    LIGHT_FAR_BEHIND,
    /** The light model was not tested (not installed), went over the budget or failed: the best one. */
    LIGHT_NOT_TESTED,
    /** The best model was not tested (not installed), went over the budget or failed: the light one. */
    BEST_NOT_TESTED,
}

data class QuickTestVerdict(val pick: ModelPick, val reason: VerdictReason)

/**
 * The automatic recommendation after the quick test: a pure function, tested on the JVM. Written for « phone
 * against server » (issue #36a), adapted to « light phone model against best phone model » on 2026-11-13 when
 * the server stopped being a product option (issue #57).
 *
 * The thresholds are **estimates**, accepted as such by the user: nothing has been measured on a phone, and on
 * 40 words one missed word is worth 2.5 points, so the score is a hint, not a verdict.
 */
object QuickTestAdvice {
    /** The light model is good enough from this similarity (90 %) up… */
    const val LIGHT_MIN_SIMILARITY = 0.90

    /** … if it is at most this far (5 points) below the best model. */
    const val MAX_GAP = 0.05

    /** The best model is "quick enough" up to this transcription delay (seconds) for a reading of about 20 s. */
    const val BEST_MAX_DELAY_SECONDS = 5.0

    /**
     * The minute: reading ≤ 25 s, transcription ≤ 30 s **for the two models together** (the light one first, the
     * best one gets what is left). Neither a download nor the loading of a model counts.
     */
    const val READING_BUDGET_SECONDS = 25
    const val TRANSCRIPTION_BUDGET_SECONDS = 30

    /** Float noise guard for comparisons made on values like 1 − 4/40. */
    private const val EPSILON = 1e-9

    /** A model that answered after [delaySeconds]: [ModeOutcome.TooSlow] past [budgetSeconds], else scored. */
    fun outcome(similarity: Double, delaySeconds: Double, budgetSeconds: Double = TRANSCRIPTION_BUDGET_SECONDS.toDouble()): ModeOutcome =
        if (delaySeconds > budgetSeconds + EPSILON) ModeOutcome.TooSlow
        else ModeOutcome.Scored(similarity, delaySeconds)

    /** What is left of the transcription budget for the next model, after [spentSeconds] (never negative). */
    fun remainingBudget(spentSeconds: Double): Double = (TRANSCRIPTION_BUDGET_SECONDS - spentSeconds).coerceAtLeast(0.0)

    /**
     * - neither model scored → no recommendation (null);
     * - only one scored → that one;
     * - both scored → the light one is recommended only if it is good enough (similarity ≥ 90 % and within
     *   5 points of the best) **and** the best one is slow (delay > 5 s); otherwise the best one, the default.
     */
    fun decide(light: ModeOutcome, best: ModeOutcome): QuickTestVerdict? {
        if (best !is ModeOutcome.Scored && light !is ModeOutcome.Scored) return null
        if (light !is ModeOutcome.Scored) return QuickTestVerdict(ModelPick.BEST, VerdictReason.LIGHT_NOT_TESTED)
        if (best !is ModeOutcome.Scored) return QuickTestVerdict(ModelPick.LIGHT, VerdictReason.BEST_NOT_TESTED)
        return when {
            light.similarity < LIGHT_MIN_SIMILARITY - EPSILON ->
                QuickTestVerdict(ModelPick.BEST, VerdictReason.LIGHT_TOO_INACCURATE)
            best.similarity - light.similarity > MAX_GAP + EPSILON ->
                QuickTestVerdict(ModelPick.BEST, VerdictReason.LIGHT_FAR_BEHIND)
            best.delaySeconds > BEST_MAX_DELAY_SECONDS + EPSILON ->
                QuickTestVerdict(ModelPick.LIGHT, VerdictReason.LIGHT_GOOD_ENOUGH_AND_FASTER)
            else -> QuickTestVerdict(ModelPick.BEST, VerdictReason.BEST_PRECISE_AND_QUICK)
        }
    }
}
