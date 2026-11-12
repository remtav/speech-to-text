package io.github.remtav.dictee.polish

import java.io.File
import java.text.Normalizer
import kotlin.math.ceil

/*
 * Evaluation of on-device polishing (issue #15, lot 3, step 3.1): the
 * measuring instrument only. No polishing approach lives here except the
 * "identity" reference; steps 3.2 and 3.3 plug theirs into [PolishApproaches].
 * Test sources only: nothing here ships in the app (see DECISIONS.md).
 */

/** One line of the evaluation set (`src/test/resources/polissage/jeu-evaluation.tsv`). */
data class EvalCase(
    val id: String,
    /** "fr" or "en". */
    val language: String,
    val category: String,
    /** What the speaker says (and, for `tts+…` lines, the text given to the TTS). */
    val said: String,
    /** Transcript as the speech model produced it: the polisher's input. */
    val raw: String,
    /** What the polisher should return. Equal to [raw] for control lines. */
    val expected: String,
    /** `rédigé`, or `tts+<model>[+<model>…]` for real model outputs on synthetic audio. */
    val provenance: String,
) {
    /** A control line must come out unchanged; any change is damage. */
    val isControl: Boolean get() = PolishEvalSet.isControl(category)
}

object PolishEvalSet {
    const val RESOURCE = "/polissage/jeu-evaluation.tsv"
    val COLUMNS = listOf("id", "langue", "categorie", "dit", "brut", "attendu", "provenance")

    /** Categories that must be fixed. */
    val TARGET_CATEGORIES = listOf("noms-propres", "toponymes", "web", "courriel", "epellation")

    /** Categories that must come out unchanged (counted in the damage rate). */
    val CONTROL_CATEGORIES = listOf(
        /** Ordinary sentences, including traps ("un point commun", "le plan B", "arobase" as a word). */
        "controle",
        /** Correctly transcribed rare or Québec words a polisher might "correct" (friperie, poudrerie…). */
        "controle-mots-rares",
        /** Target words the model already got right (GitHub, Rimouski…): polishing must keep them. */
        "controle-deja-correct",
    )
    val LANGUAGES = listOf("fr", "en")
    val MODEL_TAGS = listOf("parakeet", "qwen3", "whisper")
    private val PROVENANCE = Regex("rédigé|tts(\\+(parakeet|qwen3|whisper))+")

    fun isControl(category: String) = category.startsWith("controle")

    /** "Parakeet" → "parakeet", "Qwen3-ASR" → "qwen3", "Whisper" → "whisper". */
    fun modelTag(shortName: String): String = shortName.lowercase().substringBefore('-')

    fun isValidProvenance(p: String) = PROVENANCE.matches(p)

    fun load(): List<EvalCase> {
        val text = checkNotNull(PolishEvalSet::class.java.getResource(RESOURCE)) { "missing $RESOURCE" }.readText()
        return parse(text)
    }

    /** `#` lines are comments; the first other line is the column header. Fields are tab-separated. */
    fun parse(text: String): List<EvalCase> {
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        require(lines.isNotEmpty()) { "empty evaluation set" }
        require(lines.first().split('\t') == COLUMNS) { "header must be: ${COLUMNS.joinToString("\\t")}" }
        return lines.drop(1).mapIndexed { i, line ->
            val f = line.split('\t')
            require(f.size == COLUMNS.size) { "line ${i + 2}: ${f.size} fields instead of ${COLUMNS.size}: $line" }
            EvalCase(f[0], f[1], f[2], f[3], f[4], f[5], f[6])
        }
    }
}

/**
 * A polishing approach under evaluation. Steps 3.2 and 3.3 implement this and
 * add their approach to [PolishApproaches.all]. [polish] receives one segment's
 * raw transcript and the segment language ("fr" or "en"), and returns the
 * polished text. An exception counts as an error (the app's TextCleaner must
 * never throw: it would keep the raw text).
 */
interface PolishApproach {
    val name: String
    /** True for approaches that need the local LLM (MediaPipe, arm64 only). */
    val needsLlm: Boolean
    val description: String get() = ""
    fun polish(raw: String, language: String): String

    companion object {
        fun of(name: String, needsLlm: Boolean, description: String = "", f: (String, String) -> String) =
            object : PolishApproach {
                override val name = name
                override val needsLlm = needsLlm
                override val description = description
                override fun polish(raw: String, language: String) = f(raw, language)
            }
    }
}

/** Reference approach: changes nothing. Must score 0 % corrected and 0 % damaged. */
object IdentityApproach : PolishApproach {
    override val name = "identite"
    override val needsLlm = false
    override val description = "référence : ne change rien (valide le dispositif)"
    override fun polish(raw: String, language: String) = raw
}

/**
 * Outputs recorded elsewhere (on a phone, for the LLM approaches of step 3.3),
 * replayed by the scorer: quality is scored, host latency is not measured
 * (an LLM cannot run on the CI host). File format, tab-separated:
 *
 *     # approche: llm-qwen05-prompt-large        (optional, defaults to the file name)
 *     # llm: oui                                 (oui | non, defaults to oui)
 *     # description: …                           (optional)
 *     id	brut	sortie	latence_ms             (latence_ms: optional column)
 *
 * `brut` must equal the current raw text of that id: a line recorded against
 * an older version of the set is stale and counted as missing. `latence_ms`
 * (measured on the phone) may be empty; when present it is reported as
 * device latency, never as host latency.
 */
class RecordedApproach(
    override val name: String,
    override val needsLlm: Boolean,
    override val description: String,
    /** id → (raw it was recorded on, output, device latency in ms or null). */
    val recorded: Map<String, Recorded>,
) : PolishApproach {
    data class Recorded(val raw: String, val output: String, val deviceMillis: Double?)

    class MissingOutput(id: String) : RuntimeException("no recorded output for $id")

    private var byRaw: Map<Pair<String, String>, String> = emptyMap()

    /** Binds to the set: raw text and language identify a case (unique, see PolishEvalSetTest). */
    fun bind(cases: List<EvalCase>): RecordedApproach {
        byRaw = cases.associate { (it.language to it.raw) to it.id }
        return this
    }

    fun outputFor(case: EvalCase): String {
        val r = recorded[case.id]
        if (r == null || r.raw != case.raw) throw MissingOutput(case.id)
        return r.output
    }

    fun deviceMillis(cases: List<EvalCase>): List<Double> =
        cases.mapNotNull { c -> recorded[c.id]?.takeIf { it.raw == c.raw }?.deviceMillis }

    override fun polish(raw: String, language: String): String {
        val id = byRaw[language to raw] ?: throw MissingOutput("($language) $raw")
        return recorded[id]?.takeIf { it.raw == raw }?.output ?: throw MissingOutput(id)
    }

    companion object {
        val COLUMNS = listOf("id", "brut", "sortie", "latence_ms")

        fun parse(defaultName: String, text: String): RecordedApproach {
            val meta = HashMap<String, String>()
            val rows = ArrayList<List<String>>()
            for (line in text.lines()) {
                if (line.isBlank()) continue
                if (line.startsWith("#")) {
                    val m = Regex("#\\s*([\\p{L}]+)\\s*:\\s*(.*)").matchEntire(line)
                    if (m != null) meta[m.groupValues[1].lowercase()] = m.groupValues[2].trim()
                    continue
                }
                rows += line.split('\t')
            }
            require(rows.isNotEmpty() && rows.first() in listOf(COLUMNS, COLUMNS.take(3))) {
                "header must be: ${COLUMNS.joinToString("\\t")} (latence_ms optional)"
            }
            val recorded = rows.drop(1).associate { f ->
                require(f.size in 3..4) { "bad recorded line: $f" }
                f[0] to Recorded(f[1], f[2], f.getOrNull(3)?.takeIf { it.isNotBlank() }?.toDouble())
            }
            val llm = when (meta["llm"]?.lowercase()) { null, "oui", "yes", "true" -> true; else -> false }
            return RecordedApproach(meta["approche"] ?: defaultName, llm, meta["description"] ?: "", recorded)
        }

        fun load(file: File) = parse(file.nameWithoutExtension, file.readText())
    }
}

/**
 * How an output is compared with the expected text. Deliberately strict: case,
 * punctuation, accents, hyphens and spaces inside words all count
 * ("GitHub" ≠ "Github", "coifferimouski.com" ≠ "coifferimouski. com").
 * Only these differences are forgiven, because they carry no meaning here and
 * the keyboard rewrites them anyway:
 *  - Unicode composition (NFC);
 *  - typographic vs straight apostrophe (’ → ');
 *  - runs of ordinary spaces or tabs (one space), and spaces at both ends.
 * Non-breaking spaces are NOT folded: typography is the keyboard's job
 * (TextAssembler), a polisher must not add them.
 */
object PolishNormalization {
    private val SPACES = Regex("[ \\t]+")

    fun normalize(s: String): String =
        SPACES.replace(Normalizer.normalize(s, Normalizer.Form.NFC).replace('’', '\''), " ").trim()

    fun same(a: String, b: String) = normalize(a) == normalize(b)
}

object Percentiles {
    /** Nearest-rank percentile ([p] in 0..100) of [values]; null when empty. */
    fun of(values: List<Double>, p: Double): Double? {
        require(p in 0.0..100.0)
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}

enum class Verdict(val label: String) {
    /** Target case: output equals the expected text. */
    CORRECTED("corrigé"),
    /** Target case: output equals the raw text (nothing done). */
    UNCHANGED("inchangé"),
    /** Target case: changed, but not into the expected text. */
    WRONG("modifié à tort"),
    /** Control case: unchanged, as required. */
    KEPT("conservé"),
    /** Control case: changed. Must never happen. */
    DAMAGED("abîmé"),
    /** The approach threw, or has no recorded output for this case. */
    ERROR("erreur"),
}

data class CaseResult(val case: EvalCase, val output: String?, val verdict: Verdict, val error: String? = null)

/** Counts over a group of cases (all, a category or a language). */
data class Tally(val targets: Int, val corrected: Int, val wrong: Int, val controls: Int, val damaged: Int, val errors: Int) {
    val correctedRate: Double? get() = if (targets == 0) null else corrected.toDouble() / targets
    val damagedRate: Double? get() = if (controls == 0) null else damaged.toDouble() / controls
    val wrongRate: Double? get() = if (targets == 0) null else wrong.toDouble() / targets

    companion object {
        fun of(results: List<CaseResult>) = Tally(
            targets = results.count { !it.case.isControl },
            corrected = results.count { it.verdict == Verdict.CORRECTED },
            wrong = results.count { it.verdict == Verdict.WRONG },
            controls = results.count { it.case.isControl },
            damaged = results.count { it.verdict == Verdict.DAMAGED },
            errors = results.count { it.verdict == Verdict.ERROR },
        )
    }
}

/**
 * Latency in milliseconds; [where] says what was measured ("hôte" or "appareil").
 * [overBudget]: device measurements above [BUDGET_MS], the delay after which
 * LlmCleaner gives up on a segment (null for host timings).
 */
data class Latency(val where: String, val p50: Double, val p95: Double, val samples: Int, val overBudget: Int? = null) {
    companion object {
        const val BUDGET_MS = 4000.0
    }
}

data class ApproachScore(
    val approach: PolishApproach,
    val results: List<CaseResult>,
    val latency: Latency?,
) {
    val all: Tally get() = Tally.of(results)
    fun byCategory(): Map<String, Tally> = results.groupBy { it.case.category }.mapValues { Tally.of(it.value) }
    fun byLanguage(): Map<String, Tally> = results.groupBy { it.case.language }.mapValues { Tally.of(it.value) }
    val damagedIds: List<String> get() = results.filter { it.verdict == Verdict.DAMAGED }.map { it.case.id }
    val errorIds: List<String> get() = results.filter { it.verdict == Verdict.ERROR }.map { it.case.id }
}

object PolishScorer {
    fun verdict(case: EvalCase, output: String): Verdict = when {
        case.isControl -> if (PolishNormalization.same(output, case.raw)) Verdict.KEPT else Verdict.DAMAGED
        PolishNormalization.same(output, case.expected) -> Verdict.CORRECTED
        PolishNormalization.same(output, case.raw) -> Verdict.UNCHANGED
        else -> Verdict.WRONG
    }

    /**
     * Scores [approach] on [cases]. Live approaches are timed on this machine:
     * one warm-up pass, then [repeats] timed passes over every case (the
     * verdicts come from the first timed pass). Recorded approaches are
     * replayed: no host timing, device latency from the file when present.
     */
    fun score(approach: PolishApproach, cases: List<EvalCase>, repeats: Int = 5): ApproachScore {
        if (approach is RecordedApproach) {
            val results = cases.map { c ->
                try {
                    val out = approach.outputFor(c)
                    CaseResult(c, out, verdict(c, out))
                } catch (e: Exception) {
                    CaseResult(c, null, Verdict.ERROR, e.message)
                }
            }
            val device = approach.deviceMillis(cases)
            val latency = if (device.isEmpty()) null
            else Latency(
                "appareil", Percentiles.of(device, 50.0)!!, Percentiles.of(device, 95.0)!!, device.size,
                overBudget = device.count { it > Latency.BUDGET_MS },
            )
            return ApproachScore(approach, results, latency)
        }
        require(repeats >= 1)
        val timings = ArrayList<Double>()
        var results: List<CaseResult> = emptyList()
        for (pass in 0..repeats) {
            val passResults = cases.map { c ->
                val start = System.nanoTime()
                val r = try {
                    val out = approach.polish(c.raw, c.language)
                    CaseResult(c, out, verdict(c, out))
                } catch (e: Exception) {
                    CaseResult(c, null, Verdict.ERROR, "${e.javaClass.simpleName}: ${e.message}")
                }
                if (pass > 0) timings += (System.nanoTime() - start) / 1e6
                r
            }
            if (pass == 1) results = passResults
        }
        val latency = Latency("hôte", Percentiles.of(timings, 50.0)!!, Percentiles.of(timings, 95.0)!!, timings.size)
        return ApproachScore(approach, results, latency)
    }
}
