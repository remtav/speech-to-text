package io.github.remtav.dictee.reading

import io.github.remtav.dictee.audio.PcmAudio

/**
 * One paragraph that was read: the recording ([audio], 16 kHz mono, in memory only) and, for each model
 * that transcribed it (by catalogue id), the transcription and its score.
 */
data class ParagraphResult(
    val index: Int,
    val audio: PcmAudio,
    val transcriptions: Map<String, String>,
    val scores: Map<String, ParagraphScore>,
)

/**
 * What the reading test produced, kept in memory by the screen until it closes. This is the structure
 * step 4.3 (consent, archive, e-mail) reads: it holds everything the archive announces except what the
 * phone adds itself (application version, phone model, date).
 *
 * - [textVersion]: version of the text that was read (`lecture-qc-1`).
 * - [paragraphs]: the paragraphs that were read, in order, with audio, transcription and score per model.
 * - [globalScores]: score of the text per model (models that failed are in [modelErrors] instead).
 * - [recommendedModelId]: the model recommended for the person's languages (the one the share threshold uses).
 * - [activeModelId]: the model the keyboard used when the test ran (what [betterModelId] is compared with).
 * - [betterModelId]: an installed model that understood the person clearly better, or null.
 * - [offerSharing]: [ReadingThresholds.offerSharing] on this result. Step 4.3 plugs the sharing offer in
 *   where the result screen calls `renderShareSlot` when this is true.
 * - [region]: optional region, never asked in step 4.2 (null); step 4.3 may fill it.
 */
data class ReadingResult(
    val textVersion: String,
    val paragraphs: List<ParagraphResult>,
    val globalScores: Map<String, GlobalScore>,
    val modelErrors: Map<String, String>,
    val recommendedModelId: String,
    val activeModelId: String,
    val betterModelId: String?,
    val offerSharing: Boolean,
    val region: String? = null,
)

/**
 * The percentage shown for a resemblance: whole percent rounded DOWN, in integers (no floating point),
 * so « 79 % » is always below the 80 % line of the share threshold and 20 % of errors shows « 80 % ».
 */
fun similarityPercent(referenceWords: Int, errors: Int): Int =
    if (referenceWords <= 0) 100 else (maxOf(0, referenceWords - errors) * 100) / referenceWords

val ParagraphScore.similarityPercent: Int get() = similarityPercent(referenceWords, errors)
val GlobalScore.similarityPercent: Int get() = similarityPercent(referenceWords, errors)
