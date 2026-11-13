package io.github.remtav.dictee.reading

import io.github.remtav.dictee.text.Levenshtein

/**
 * A reference word that the transcription did not give (issue #15, step 4.1), for the highlight:
 * [range] is in the paragraph text, [word] the text shown there, [heard] what the transcription
 * had instead (null when the word is missing).
 */
data class MisheardWord(val referenceIndex: Int, val range: IntRange, val word: String, val heard: String?)

/** A word of the transcription that matches nothing in the text, placed before reference token [beforeReferenceIndex]. */
data class ExtraWord(val beforeReferenceIndex: Int, val heard: String)

/** Score of one paragraph read with one model. */
data class ParagraphScore(
    val paragraph: Int,
    /** Normalised reference tokens (the WER denominator). */
    val referenceWords: Int,
    /** Substitutions + deletions + insertions, the WER numerator. */
    val errors: Int,
    val misheard: List<MisheardWord>,
    val extra: List<ExtraWord>,
) {
    val wer: Double get() = if (referenceWords == 0) 0.0 else errors.toDouble() / referenceWords

    /** Resemblance shown to the user: 1 − WER, kept between 0 and 1 (insertions can push WER above 1). */
    val similarity: Double get() = (1.0 - wer).coerceIn(0.0, 1.0)
}

/** Score of the whole text with one model: errors over words of the paragraphs read, not a mean of rates. */
data class GlobalScore(val referenceWords: Int, val errors: Int, val paragraphsScored: Int, val paragraphsTotal: Int) {
    val wer: Double get() = if (referenceWords == 0) 0.0 else errors.toDouble() / referenceWords
    val similarity: Double get() = (1.0 - wer).coerceIn(0.0, 1.0)

    /** Every paragraph of the text has been read and scored. */
    val complete: Boolean get() = paragraphsTotal > 0 && paragraphsScored == paragraphsTotal
}

object ReadingScoring {

    /** Scores the transcription of paragraph [index] against the text of [material]. */
    fun scoreParagraph(material: ReadingMaterial, index: Int, transcription: String): ParagraphScore {
        val paragraph = material.text.paragraphs[index]
        val ref = material.normalizer.tokens(paragraph)
        val hyp = material.normalizer.tokens(transcription)
        val steps = Levenshtein.align(ref.map { it.text }, hyp.map { it.text })
        val misheard = mutableListOf<MisheardWord>()
        val extra = mutableListOf<ExtraWord>()
        var nextRef = 0
        for (s in steps) {
            when (s.op) {
                Levenshtein.Op.MATCH -> nextRef = s.ai + 1
                Levenshtein.Op.SUBSTITUTE, Levenshtein.Op.DELETE -> {
                    val r = ref[s.ai]
                    val heard = if (s.op == Levenshtein.Op.DELETE) null else transcription.substring(hyp[s.bi].range)
                    // Two tokens can share a range (a number spelled in several words): highlight it once.
                    if (misheard.lastOrNull()?.range != r.range) {
                        misheard += MisheardWord(s.ai, r.range, paragraph.substring(r.range), heard)
                    }
                    nextRef = s.ai + 1
                }
                Levenshtein.Op.INSERT -> extra += ExtraWord(nextRef, transcription.substring(hyp[s.bi].range))
            }
        }
        val errors = steps.count { it.op != Levenshtein.Op.MATCH }
        return ParagraphScore(index, ref.size, errors, misheard, extra)
    }

    /** Sums the paragraph scores of one model; [paragraphsTotal] is the number of paragraphs of the text. */
    fun global(scores: Collection<ParagraphScore>, paragraphsTotal: Int): GlobalScore {
        val distinct = scores.distinctBy { it.paragraph }
        require(distinct.size == scores.size) { "un paragraphe compté deux fois" }
        return GlobalScore(distinct.sumOf { it.referenceWords }, distinct.sumOf { it.errors }, distinct.size, paragraphsTotal)
    }
}

/**
 * The two rules the result screen applies (issue #15, lot 4, "Décisions"). Integer comparisons, so a
 * rate exactly at a threshold is never on the wrong side because of floating point.
 */
object ReadingThresholds {

    /** "Très éloigné": sharing is offered when the WER of the recommended model is ABOVE this percentage. */
    const val SHARE_WER_THRESHOLD_PERCENT = 20
    const val SHARE_WER_THRESHOLD = SHARE_WER_THRESHOLD_PERCENT / 100.0

    /**
     * "Nettement mieux", both conditions together, on the complete text read once (same recordings):
     * at least [BETTER_MODEL_MIN_GAIN_POINTS] points of WER less (10 words on the 200 of the text: more
     * than the few words a hesitation or a noise can cost), AND at least [BETTER_MODEL_MIN_RELATIVE_GAIN_PERCENT] %
     * fewer errors (so 2 % against 8 % qualifies, 40 % against 45 % does not).
     */
    const val BETTER_MODEL_MIN_GAIN_POINTS = 5
    const val BETTER_MODEL_MIN_RELATIVE_GAIN_PERCENT = 30

    /**
     * Whether the screen offers to share the recording: only with the score of the recommended model
     * ([recommended], null when that model is not installed or was not run), only on the complete
     * text, and only when its WER is strictly above [SHARE_WER_THRESHOLD_PERCENT]. Otherwise nothing is offered.
     */
    fun offerSharing(recommended: GlobalScore?): Boolean =
        recommended != null && recommended.complete &&
            recommended.errors.toLong() * 100 > SHARE_WER_THRESHOLD_PERCENT.toLong() * recommended.referenceWords

    /** Same rule, from the scores of every model run (by model id) and the id of the recommended model. */
    fun offerSharing(scores: Map<String, GlobalScore>, recommendedModelId: String): Boolean =
        offerSharing(scores[recommendedModelId])

    /** Whether [candidate] understands the reader clearly better than [current] (see the constants). */
    fun clearlyBetter(candidate: GlobalScore, current: GlobalScore): Boolean {
        if (!candidate.complete || !current.complete) return false
        if (candidate.referenceWords != current.referenceWords || current.referenceWords == 0) return false
        val words = current.referenceWords.toLong()
        val gain = current.errors.toLong() - candidate.errors.toLong()
        return gain * 100 >= BETTER_MODEL_MIN_GAIN_POINTS * words &&
            gain * 100 >= BETTER_MODEL_MIN_RELATIVE_GAIN_PERCENT.toLong() * current.errors
    }

    /**
     * The installed model to suggest instead of [currentModelId], or null: the one with the fewest
     * errors among those [clearlyBetter] than the current model (ties: first in [scores] order).
     * Null too when the current model has no complete score.
     */
    fun betterModel(scores: Map<String, GlobalScore>, currentModelId: String): String? {
        val current = scores[currentModelId] ?: return null
        return scores.entries
            .filter { it.key != currentModelId && clearlyBetter(it.value, current) }
            .minByOrNull { it.value.errors }
            ?.key
    }
}
