package io.github.remtav.dictee.cleanup.eval

import io.github.remtav.dictee.text.polish.PolishRules
import io.github.remtav.dictee.text.polish.RuleSignal
import io.github.remtav.dictee.text.polish.ShippedVocabulary

/*
 * EVALUATION ONLY (issue #15, lot 3, step 3.4): approach 4 wired to the real
 * rules and shipped vocabulary of step 3.2. Like the rest of this package,
 * nothing here is reachable from the app (no setting, no call from DicteeApp,
 * TextPipeline or SettingsActivity; DefaultPathUnchangedTest checks it). Used
 * by the JVM simulations (LlmSimulationTest) and the phone recording test
 * (LlmPolishRecordingTest).
 */

/**
 * The deterministic stage of approach 4: [PolishRules] (spoken addresses,
 * spelled letters, postal codes), then [ShippedVocabulary] (names). This is the
 * best approach without an LLM (`regles+vocabulaire`, 0 control damaged), so
 * the combination measures what an LLM adds on top of it.
 */
object WiredRules {
    fun apply(text: String, language: String): String =
        ShippedVocabulary.apply(PolishRules.polish(text, language), language)
}

/**
 * Sends a segment to the LLM when step 3.2's rules say so:
 * `PolishRules.assess(raw).signal == PROBABLE_CASE || hasAddress` (an address
 * or spelling the rules could not resolve, or an address whose name they cannot
 * check). Names are left to the vocabulary: a segment that only contains a
 * badly transcribed name is NOT sent, which is the issue's definition of
 * approach 4 ("LLM only where a rule flags a probable case").
 */
object RuleSignalDetector : ProbableCaseDetector {
    override fun classify(raw: String, afterRules: String, language: String): SegmentClass {
        val r = PolishRules.assess(raw, language)
        return when {
            r.signal == RuleSignal.PROBABLE_CASE || r.hasAddress -> SegmentClass.PROBABLE_CASE
            afterRules != raw -> SegmentClass.CORRECTED
            else -> SegmentClass.UNCHANGED
        }
    }
}

/** The lexicon the relaxed guard would use if shipped: the correct forms of [ShippedVocabulary]. */
object ShippedLexicon {
    val names: List<String> by lazy { ShippedVocabulary.entries.map { it.correct }.distinct() }
}

/** Approach 4 as it would ship: rules + vocabulary, rule-signal detector, wide prompt, relaxed guard with the shipped lexicon. */
fun wiredCombination(
    llm: LlmCall,
    allowLexiconNear: Boolean = false,
    template: WidePrompt.Template = WidePrompt.Template.CHATML,
): RulesThenLlmPolisher = RulesThenLlmPolisher(
    rules = WiredRules::apply,
    detector = RuleSignalDetector,
    llm = GuardedLlmPolisher(llm, RelaxedSafetyCheck.Options(ShippedLexicon.names, allowLexiconNear), template),
)
