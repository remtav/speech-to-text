package io.github.remtav.dictee.polish

import java.io.File

/**
 * Registry of the polishing approaches scored by [PolishEvaluationTest].
 *
 * To plug in an approach (steps 3.2 and 3.3):
 *  - live approach (runs on the JVM: rules, vocabulary…): add it to [live],
 *    e.g. `PolishApproach.of("regles", needsLlm = false) { raw, lang -> Rules.polish(raw, lang) }`.
 *    With `needsLlm = false` it must damage no control sentence and never
 *    throw: [PolishEvaluationTest] fails otherwise.
 *  - LLM approach (MediaPipe, arm64 only, cannot run here): record its outputs
 *    on a phone into `src/test/resources/polissage/sorties/<name>.tsv` (format
 *    in [RecordedApproach]); it is picked up automatically and its quality is
 *    scored by replay, without host latency.
 */
object PolishApproaches {
    const val RECORDED_RESOURCE = "/polissage/sorties"

    val live: List<PolishApproach> = listOf(
        IdentityApproach,
        RulesApproach,
        VocabularyApproach,
        GeneralVocabularyApproach,
        RulesAndVocabularyApproach,
        RulesAndGeneralVocabularyApproach,
    )

    fun recorded(cases: List<EvalCase>): List<RecordedApproach> {
        val url = PolishApproaches::class.java.getResource(RECORDED_RESOURCE) ?: return emptyList()
        val dir = File(url.toURI())
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".tsv") }.orEmpty()
            .sortedBy { it.name }
            .map { RecordedApproach.load(it).bind(cases) }
    }

    fun all(cases: List<EvalCase>): List<PolishApproach> = live + recorded(cases)
}
