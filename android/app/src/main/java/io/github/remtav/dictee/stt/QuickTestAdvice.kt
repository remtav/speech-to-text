package io.github.remtav.dictee.stt

/**
 * What the quick test (issue #36, step 5) learned about one mode. Pure data, built by the screen.
 */
sealed interface ModeOutcome {
    /** The mode answered within the budget: [similarity] is 1 − WER (0..1, `ReadingScore`), [delaySeconds] the transcription delay. */
    data class Scored(val similarity: Double, val delaySeconds: Double) : ModeOutcome

    /** The mode did not answer within [QuickTestAdvice.TRANSCRIPTION_BUDGET_SECONDS]: shown as « trop lent ». */
    data object TooSlow : ModeOutcome

    /** The mode could not be tested: server unreachable or not configured, or phone model not installed. */
    data object Unavailable : ModeOutcome
}

/** Why [QuickTestAdvice.decide] answered as it did. */
enum class VerdictReason {
    /** Phone is good enough: similarity, gap to the server and delay all within the thresholds. */
    PHONE_GOOD_ENOUGH,
    /** Phone scored, but below [QuickTestAdvice.PHONE_MIN_SIMILARITY]. */
    PHONE_TOO_INACCURATE,
    /** Phone is more than [QuickTestAdvice.MAX_GAP] behind the server. */
    PHONE_FAR_BEHIND_SERVER,
    /** Phone is slower than [QuickTestAdvice.PHONE_MAX_DELAY_SECONDS], or exceeded the budget. */
    PHONE_TOO_SLOW,
    /** The phone model was not tested (not installed) and the server answered. */
    PHONE_NOT_TESTED,
    /** The server is unreachable, over budget or not configured: the phone is the only option left. */
    SERVER_UNAVAILABLE,
}

data class QuickTestVerdict(val mode: TranscriptionMode, val reason: VerdictReason)

/**
 * The automatic recommendation after the quick test: a pure function, tested on the JVM (issue #36a).
 *
 * The thresholds are the starting values of the issue, **estimates**: nothing has been measured on a phone, and
 * on 40 words one missed word is worth 2.5 points, so the score is a hint, not a verdict.
 */
object QuickTestAdvice {
    /** Phone is recommended from this similarity (90 %) up. */
    const val PHONE_MIN_SIMILARITY = 0.90

    /** … if it is at most this far (5 points) below the server's similarity. */
    const val MAX_GAP = 0.05

    /** … and if its transcription delay is at most this (seconds). */
    const val PHONE_MAX_DELAY_SECONDS = 5.0

    /** The minute: reading ≤ 25 s, transcription ≤ 30 s. */
    const val READING_BUDGET_SECONDS = 25
    const val TRANSCRIPTION_BUDGET_SECONDS = 30

    /** Float noise guard for comparisons made on values like 1 − 4/40. */
    private const val EPSILON = 1e-9

    /** A mode that answered after [delaySeconds]: [ModeOutcome.TooSlow] past the budget, else scored. */
    fun outcome(similarity: Double, delaySeconds: Double): ModeOutcome =
        if (delaySeconds > TRANSCRIPTION_BUDGET_SECONDS + EPSILON) ModeOutcome.TooSlow
        else ModeOutcome.Scored(similarity, delaySeconds)

    /**
     * - server not [ModeOutcome.Scored] (unreachable, no address, over budget) → phone;
     * - phone not scored (not installed, over budget) → server, unless both failed → phone (the voice stays here);
     * - both scored → phone if similarity ≥ 90 %, within 5 points of the server, and delay ≤ 5 s; otherwise server.
     */
    fun decide(phone: ModeOutcome, server: ModeOutcome): QuickTestVerdict {
        if (server !is ModeOutcome.Scored) return QuickTestVerdict(TranscriptionMode.PHONE, VerdictReason.SERVER_UNAVAILABLE)
        if (phone is ModeOutcome.TooSlow) return QuickTestVerdict(TranscriptionMode.SERVER, VerdictReason.PHONE_TOO_SLOW)
        if (phone !is ModeOutcome.Scored) return QuickTestVerdict(TranscriptionMode.SERVER, VerdictReason.PHONE_NOT_TESTED)
        return when {
            phone.similarity < PHONE_MIN_SIMILARITY - EPSILON ->
                QuickTestVerdict(TranscriptionMode.SERVER, VerdictReason.PHONE_TOO_INACCURATE)
            server.similarity - phone.similarity > MAX_GAP + EPSILON ->
                QuickTestVerdict(TranscriptionMode.SERVER, VerdictReason.PHONE_FAR_BEHIND_SERVER)
            phone.delaySeconds > PHONE_MAX_DELAY_SECONDS + EPSILON ->
                QuickTestVerdict(TranscriptionMode.SERVER, VerdictReason.PHONE_TOO_SLOW)
            else -> QuickTestVerdict(TranscriptionMode.PHONE, VerdictReason.PHONE_GOOD_ENOUGH)
        }
    }
}
