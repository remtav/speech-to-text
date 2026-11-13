package io.github.remtav.dictee.cleanup.eval

/*
 * EVALUATION ONLY (issue #15, lot 3, step 3.3). Nothing here is wired into the
 * app: no setting, no call from DicteeApp or TextPipeline, CleanupMode
 * unchanged. These classes are driven by the JVM simulations
 * (src/test/.../polish/LlmSimulationTest.kt) and by the phone recording test
 * (src/androidTest/.../LlmPolishRecordingTest.kt).
 */

/** One generation by a local LLM. Returns null (or throws) when no output is available. */
fun interface LlmCall {
    fun generate(prompt: String): String?
}

/** How one segment came out of the rules (approach 4). */
enum class SegmentClass {
    /** The rules changed nothing and nothing suggests a case they missed. */
    UNCHANGED,
    /** The rules changed something and nothing suggests a case left over. */
    CORRECTED,
    /** A name, spelled word or spoken address probably remains to be fixed: worth an LLM call. */
    PROBABLE_CASE,
}

/**
 * Classifies a segment after the deterministic rules. The wired detector is
 * [RuleSignalDetector] (step 3.4, from step 3.2's `PolishRules.assess`);
 * [ProvisionalProbableCaseDetector] is the wider step 3.3 stand-in, kept for
 * comparison.
 */
fun interface ProbableCaseDetector {
    fun classify(raw: String, afterRules: String, language: String): SegmentClass
}

/**
 * Step 3.3 stand-in detector, wider than [RuleSignalDetector] (also flags
 * spans close to a lexicon name). Not used by the wired combination. It looks
 * only at the text after the rules and flags a probable case when it sees:
 *  - a spoken address: "point|dot" followed by com, ca, org, net, qc, gouv;
 *    "arobase"; "at … dot|point"; "barre oblique"; "slash";
 *  - three or more spelled capital letters (G A G N O N, T-R-E-M-B-L-A-Y);
 *  - a postal code not yet written "A1A 1A1" (G5L, 3A1 / G5L3A1);
 *  - a span of 1 to 3 words that spells a lexicon name differently
 *    (case, accents, spaces, hyphens), or sounds close to one (edit distance
 *    within the budget of [RelaxedSafetyCheck.nameBudget]).
 * Otherwise UNCHANGED or CORRECTED depending on whether the rules changed the
 * text. A false alarm costs an LLM call (latency), never a wrong word: the
 * relaxed guard still judges the output.
 */
class ProvisionalProbableCaseDetector(lexicon: Collection<String> = emptyList()) : ProbableCaseDetector {
    private val names = lexicon.map { it.trim() }.filter { it.isNotEmpty() && !RelaxedSafetyCheck.Address.isAddress(it) }
        .distinct().map { it to RelaxedSafetyCheck.squash(it) }.filter { it.second.length >= 3 }

    override fun classify(raw: String, afterRules: String, language: String): SegmentClass = when {
        hasSignal(afterRules) -> SegmentClass.PROBABLE_CASE
        afterRules != raw -> SegmentClass.CORRECTED
        else -> SegmentClass.UNCHANGED
    }

    internal fun hasSignal(text: String): Boolean {
        if (SPOKEN_ADDRESS.containsMatchIn(text) || SPELLED.containsMatchIn(text) || SPELLED_HYPHEN.containsMatchIn(text)) return true
        if (POSTAL.findAll(text).any { !WELL_FORMED_POSTAL.matches(it.value) }) return true
        return names.isNotEmpty() && nearName(text)
    }

    private fun nearName(text: String): Boolean {
        val words = RelaxedSafetyCheck.tokenize(RelaxedSafetyCheck.normalizeText(text))
            .filter { it.kind == RelaxedSafetyCheck.Kind.WORD }
        for (i in words.indices) for (len in 1..3) {
            if (i + len > words.size) break
            val span = words.subList(i, i + len)
            val surface = span.joinToString(" ") { it.text }
            val s = RelaxedSafetyCheck.squash(surface)
            for ((name, squashed) in names) {
                if (s == squashed) {
                    // Same spelling: a probable case only if the text does not already contain the name as written.
                    if (!text.contains(name)) return true
                } else if (s.length >= 3) {
                    val d = RelaxedSafetyCheck.levenshtein(s, squashed)
                    if (d in 1..RelaxedSafetyCheck.nameBudget(squashed.length)) return true
                }
            }
        }
        return false
    }

    private companion object {
        const val B = "(?<![\\p{L}\\p{N}])"
        const val E = "(?![\\p{L}\\p{N}])"
        val SPOKEN_ADDRESS = Regex(
            "(?i)$B(point|dot)\\s+(com|ca|org|net|qc|gouv)$E|${B}arobase$E|${B}at\\s+\\S+\\s+(dot|point)$E|${B}barre\\s+oblique$E|${B}slash$E",
        )
        val SPELLED = Regex("$B\\p{Lu}(?:[\\s,]+\\p{Lu}){2,}$E")
        val SPELLED_HYPHEN = Regex("$B\\p{Lu}(?:-\\p{Lu}){2,}")
        val POSTAL = Regex("(?i)$B[a-z]\\d[a-z][\\s,]*\\d[a-z]\\d$E")
        val WELL_FORMED_POSTAL = Regex("[A-Z]\\d[A-Z] \\d[A-Z]\\d")
    }
}

/**
 * Approach 3: the wide prompt on every segment, output judged by
 * [RelaxedSafetyCheck] against the segment given to the model. Never throws:
 * any failure (exception, no output, rejected output) returns the input.
 * Unlike LlmCleaner, short segments are not skipped ("Va voir coiffe
 * rimouski point com" has few words) and RuleCleaner is not applied first
 * (hesitations are the production prompt's job, not this variant's).
 */
class GuardedLlmPolisher(
    private val llm: LlmCall,
    val options: RelaxedSafetyCheck.Options = RelaxedSafetyCheck.Options(),
    private val template: WidePrompt.Template = WidePrompt.Template.CHATML,
) {
    data class Result(
        /** What the keyboard would insert: the LLM output if accepted, else the input. */
        val text: String,
        /** The LLM output after [tidy], before the guard (null when none). */
        val llmOutput: String?,
        val verdict: RelaxedSafetyCheck.Verdict?,
        val error: String? = null,
    )

    fun prompt(input: String, language: String) = WidePrompt.build(input, language, options.lexicon, template)

    fun polish(input: String, language: String): Result {
        if (input.isBlank()) return Result(input, null, null)
        val generated = try {
            llm.generate(prompt(input, language))
        } catch (e: Throwable) {
            return Result(input, null, null, e.javaClass.simpleName)
        } ?: return Result(input, null, null, "aucune sortie")
        val out = tidy(input, generated)
        val verdict = try {
            RelaxedSafetyCheck.check(input, out, options)
        } catch (e: Throwable) {
            RelaxedSafetyCheck.Verdict(false, "garde-fou : ${e.javaClass.simpleName}")
        }
        return Result(if (verdict.ok) out else input, out, verdict)
    }

    companion object {
        private val PAIRS = listOf('"' to '"', '«' to '»', '“' to '”')

        /** Trims, and removes one pair of quotes wrapping the whole output when the input was not quoted. */
        fun tidy(input: String, output: String): String {
            var s = output.trim()
            val inputStart = input.trim().firstOrNull()
            for ((open, close) in PAIRS) {
                if (s.length >= 2 && s.first() == open && s.last() == close && inputStart != open) {
                    s = s.substring(1, s.length - 1).trim()
                    break
                }
            }
            return s
        }
    }
}

/**
 * Approach 4: rules first, then the LLM only on the segments the detector
 * flags as a probable case left over; the relaxed guard judges the LLM output
 * against the text after the rules (the rules are deterministic and gated by
 * their own CI test, step 3.2). Never throws.
 */
class RulesThenLlmPolisher(
    private val rules: (String, String) -> String = { text, _ -> text },
    private val detector: ProbableCaseDetector,
    private val llm: GuardedLlmPolisher,
) {
    data class Result(
        val text: String,
        val afterRules: String,
        val segmentClass: SegmentClass,
        /** Null when the LLM was not called. */
        val llm: GuardedLlmPolisher.Result?,
    )

    fun polish(raw: String, language: String): Result {
        val afterRules = try { rules(raw, language) } catch (_: Throwable) { raw }
        val cls = try {
            detector.classify(raw, afterRules, language)
        } catch (_: Throwable) {
            if (afterRules == raw) SegmentClass.UNCHANGED else SegmentClass.CORRECTED
        }
        if (cls != SegmentClass.PROBABLE_CASE) return Result(afterRules, afterRules, cls, null)
        val r = llm.polish(afterRules, language)
        return Result(r.text, afterRules, cls, r)
    }
}
