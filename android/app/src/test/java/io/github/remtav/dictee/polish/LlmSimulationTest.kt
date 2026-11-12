package io.github.remtav.dictee.polish

import io.github.remtav.dictee.cleanup.eval.GuardedLlmPolisher
import io.github.remtav.dictee.cleanup.eval.LlmCall
import io.github.remtav.dictee.cleanup.eval.ProvisionalProbableCaseDetector
import io.github.remtav.dictee.cleanup.eval.RelaxedSafetyCheck
import io.github.remtav.dictee.cleanup.eval.RulesThenLlmPolisher
import io.github.remtav.dictee.cleanup.eval.SegmentClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer
import java.util.zip.CRC32

/**
 * Issue #15, lot 3, step 3.3: what can be said about the LLM approaches
 * WITHOUT running an LLM (MediaPipe is arm64 only). Everything here is a
 * SIMULATION; no number below is a measurement of Qwen or of any model.
 *
 *  1. Ceiling of the relaxed guard: share of the 79 cases to fix that the
 *     guard would accept if the LLM returned exactly the expected text.
 *  2. Protection: share of simulated faulty outputs (9 failure modes applied
 *     to the 53 control sentences, and to the expected text of the 79 cases)
 *     that the guard rejects.
 *  3. Simulated runs of approach 3 (wide prompt + relaxed guard) and
 *     approach 4 (rules, provisional detector, LLM, guard) with an "oracle"
 *     LLM (returns the expected text) and a "noisy" LLM (defined below).
 *     Their outputs are committed as `src/test/resources/polissage/sorties/simulation-*.tsv`
 *     so that the scorer (PolishEvaluationTest) reports them next to the
 *     other approaches, under names that say what they are.
 *
 *     ./gradlew testDebugUnitTest --tests '*LlmSimulationTest*'
 */
class LlmSimulationTest {
    private val cases = PolishEvalSet.load()
    private val targets = cases.filter { !it.isControl }
    private val controls = cases.filter { it.isControl }
    private val lexicon = SimulationLexicon.load()

    private val none = RelaxedSafetyCheck.Options()
    private val withLexicon = RelaxedSafetyCheck.Options(lexicon = lexicon)
    private val withNear = RelaxedSafetyCheck.Options(lexicon = lexicon, allowLexiconNear = true)
    private val configs = listOf("sans lexique" to none, "lexique" to withLexicon, "lexique + proximité" to withNear)

    // ----------------------------------------------------------- 1. ceiling

    @Test fun ceilingOfTheRelaxedGuard() {
        val report = StringBuilder("Plafond du garde-fou assoupli (SIMULATION : sortie du LLM = texte attendu)\n")
        val accepted = configs.associate { (label, opt) ->
            val ok = targets.filter { RelaxedSafetyCheck.check(it.raw, it.expected, opt).ok }
            report.append("  $label : ${ok.size}/${targets.size} cas à corriger acceptés\n")
            for (cat in PolishEvalSet.TARGET_CATEGORIES) {
                val inCat = targets.filter { it.category == cat }
                report.append("    $cat : ${inCat.count { it in ok }}/${inCat.size}\n")
            }
            label to ok.size
        }
        val refused = targets.filter { !RelaxedSafetyCheck.check(it.raw, it.expected, withNear).ok }
        report.append("  refusés même avec lexique + proximité (${refused.size}) :\n")
        refused.forEach { report.append("    ${it.id} : ${RelaxedSafetyCheck.check(it.raw, it.expected, withNear).reason}\n") }
        println(report)
        // Pinned so that android/DECISIONS.md (step 3.3) cannot drift from the code.
        assertEquals(CEILING_NO_LEXICON, accepted["sans lexique"])
        assertEquals(CEILING_LEXICON, accepted["lexique"])
        assertEquals(CEILING_NEAR, accepted["lexique + proximité"])
    }

    @Test fun everyRawSentenceIsAcceptedUnchanged() {
        for ((_, opt) in configs) for (c in cases) {
            val v = RelaxedSafetyCheck.check(c.raw, c.raw, opt)
            assertTrue("${c.id}: ${v.reason}", v.ok && v.changes.isEmpty())
        }
    }

    // -------------------------------------------------------- 2. protection

    @Test fun faultyOutputsOnControlSentencesAreAllRejected() {
        val report = StringBuilder("Protection des 53 contrôles (SIMULATION : sorties fautives fabriquées)\n")
        val leaks = ArrayList<String>()
        var tried = 0
        for (mode in Corruption.MODES) {
            val applicable = controls.mapNotNull { c -> mode.apply(c.raw, c.language)?.let { c to it } }
            tried += applicable.size
            val line = configs.joinToString("  ") { (label, opt) ->
                val rejected = applicable.count { (c, bad) -> !RelaxedSafetyCheck.check(c.raw, bad, opt).ok }
                applicable.filter { (c, bad) -> RelaxedSafetyCheck.check(c.raw, bad, opt).ok }
                    .forEach { (c, bad) -> leaks += "${mode.name} [$label] ${c.id}: $bad" }
                "$label ${rejected}/${applicable.size}"
            }
            report.append("  ${mode.name.padEnd(22)} $line\n")
        }
        report.append("  total : $tried sorties fautives essayées par configuration\n")
        println(report)
        assertTrue("faulty outputs accepted:\n" + leaks.joinToString("\n"), leaks.isEmpty())
        assertEquals(CONTROL_FAULTS_TRIED, tried)
    }

    @Test fun faultyFixesOfTargetCasesAreAllRejected() {
        // The LLM fixes the name or address but damages something else in the sentence.
        val leaks = ArrayList<String>()
        var tried = 0
        for (mode in Corruption.MODES) for (c in targets) {
            val bad = mode.apply(c.expected, c.language) ?: continue
            if ("${mode.name}|${c.id}" in HARMLESS) continue
            tried++
            for ((label, opt) in configs) {
                if (RelaxedSafetyCheck.check(c.raw, bad, opt).ok) leaks += "${mode.name} [$label] ${c.id}: $bad"
            }
        }
        println("Corrections fautives des cas à corriger (SIMULATION) : $tried essayées, ${leaks.size} acceptées")
        assertTrue("faulty fixes accepted:\n" + leaks.joinToString("\n"), leaks.isEmpty())
    }

    // ------------------------------------------------- 3. simulated runs

    @Test fun simulatedOutputFilesAreUpToDate() {
        val files = simulations()
        val outDir = File(System.getProperty("dictee.polishReportDir") ?: "build/reports/polissage", "simulations")
        outDir.mkdirs()
        val stale = ArrayList<String>()
        for ((name, content) in files) {
            File(outDir, "$name.tsv").writeText(content)
            val committed = javaClass.getResource("${PolishApproaches.RECORDED_RESOURCE}/$name.tsv")?.readText()
            if (committed != content) stale += name
        }
        assertTrue(
            "simulated outputs changed: ${stale.joinToString()}. Copy ${outDir.absolutePath}/*.tsv to " +
                "app/src/test/resources/polissage/sorties/ (they are simulations, see LlmSimulationTest).",
            stale.isEmpty(),
        )
    }

    @Test fun combinationCallsTheLlmOnlyOnFlaggedSegments() {
        val detector = ProvisionalProbableCaseDetector(lexicon)
        val flagged = cases.filter { detector.classify(it.raw, it.raw, it.language) == SegmentClass.PROBABLE_CASE }
        val flaggedTargets = flagged.count { !it.isControl }
        val flaggedControls = flagged.count { it.isControl }
        println(
            "Détecteur provisoire (règles = identité, lexique de simulation) : LLM appelé sur ${flagged.size}/${cases.size} " +
                "segments ($flaggedTargets/${targets.size} cas à corriger, $flaggedControls/${controls.size} contrôles)",
        )
        assertEquals(DETECTOR_FLAGGED_TARGETS, flaggedTargets)
        assertEquals(DETECTOR_FLAGGED_CONTROLS, flaggedControls)
        // Not flagged → the LLM is never called and the text after the rules is kept as is.
        var calls = 0
        val polisher = RulesThenLlmPolisher(
            detector = detector,
            llm = GuardedLlmPolisher(LlmCall { calls++; "n'importe quoi" }, withLexicon),
        )
        for (c in cases) {
            val r = polisher.polish(c.raw, c.language)
            if (c !in flagged) assertEquals(c.raw, r.text)
            assertEquals(c.raw, r.text) // the absurd LLM output is always rejected by the guard
        }
        assertEquals(flagged.size, calls)
    }

    @Test fun polishersNeverThrowAndKeepTheInputOnFailure() {
        val failing = GuardedLlmPolisher(LlmCall { throw IllegalStateException("boom") }, withLexicon)
        val silent = GuardedLlmPolisher(LlmCall { null }, withLexicon)
        for (c in cases.take(20)) {
            assertEquals(c.raw, failing.polish(c.raw, c.language).text)
            assertEquals(c.raw, silent.polish(c.raw, c.language).text)
        }
        assertEquals("Va voir kalmia.ca", GuardedLlmPolisher.tidy("Va voir kalmia.ca", "  « Va voir kalmia.ca »\n"))
        assertEquals("« Oui »", GuardedLlmPolisher.tidy("« Oui »", "« Oui »"))
    }

    private fun simulations(): Map<String, String> {
        fun oracle(c: EvalCase) = c.expected
        fun noisy(c: EvalCase) = NoisyLlm.output(c)

        fun approach3(opt: RelaxedSafetyCheck.Options, llmOut: (EvalCase) -> String) = cases.map { c ->
            GuardedLlmPolisher(LlmCall { llmOut(c) }, opt).polish(c.raw, c.language).text
        }
        fun approach4(llmOut: (EvalCase) -> String) = cases.map { c ->
            RulesThenLlmPolisher(
                detector = ProvisionalProbableCaseDetector(lexicon),
                llm = GuardedLlmPolisher(LlmCall { llmOut(c) }, withLexicon),
            ).polish(c.raw, c.language).text
        }
        val common = "SIMULATION, aucun LLM n'a tourné (MediaPipe : arm64 seulement). Lexique : lexique-simulation.txt (plafond optimiste)."
        val oracleText = "LLM « oracle » simulé : rend exactement le texte attendu."
        val noisyText = "LLM « bruyant » simulé (NoisyLlm, LlmSimulationTest) : cas à corriger 55 % attendu, 15 % attendu abîmé, " +
            "15 % brut, 15 % brut abîmé ; contrôles 70 % intacts, 30 % abîmés (9 modes : hypercorrection, mot retiré, mot ajouté, " +
            "ponctuation, majuscule, préambule, adresse inventée, accent retiré, synonyme)."
        return linkedMapOf(
            "simulation-llm-oracle-gardefou" to tsv(
                "simulation-llm-oracle-gardefou",
                "$common Approche 3 : $oracleText Garde-fou assoupli avec lexique, sans proximité. = plafond de l'approche 3.",
                approach3(withLexicon, ::oracle),
            ),
            "simulation-llm-oracle-gardefou-proximite" to tsv(
                "simulation-llm-oracle-gardefou-proximite",
                "$common Approche 3 : $oracleText Garde-fou assoupli avec lexique ET proximité (remplacement par un nom proche).",
                approach3(withNear, ::oracle),
            ),
            "simulation-llm-bruyant-gardefou" to tsv(
                "simulation-llm-bruyant-gardefou",
                "$common Approche 3 : $noisyText Garde-fou assoupli avec lexique, sans proximité.",
                approach3(withLexicon, ::noisy),
            ),
            "simulation-llm-bruyant-sans-gardefou" to tsv(
                "simulation-llm-bruyant-sans-gardefou",
                "$common Approche 3 SANS garde-fou : $noisyText Montre ce que le garde-fou empêche.",
                cases.map(::noisy),
            ),
            "simulation-combinaison-oracle" to tsv(
                "simulation-combinaison-oracle",
                "$common Approche 4 : règles = identité (celles de l'étape 3.2 ne sont pas encore raccordées), détecteur provisoire, " +
                    "$oracleText Garde-fou assoupli avec lexique.",
                approach4(::oracle),
            ),
            "simulation-combinaison-bruyant" to tsv(
                "simulation-combinaison-bruyant",
                "$common Approche 4 : règles = identité, détecteur provisoire, $noisyText Garde-fou assoupli avec lexique.",
                approach4(::noisy),
            ),
        )
    }

    private fun tsv(name: String, description: String, outputs: List<String>) = buildString {
        append("# approche: $name\n")
        append("# llm: oui\n")
        append("# description: $description\n")
        append("# Fichier généré par LlmSimulationTest : ne pas modifier à la main. Ce n'est PAS une mesure.\n")
        append("id\tbrut\tsortie\n")
        cases.zip(outputs).forEach { (c, out) ->
            require('\t' !in out && '\n' !in out) { "bad simulated output for ${c.id}" }
            append("${c.id}\t${c.raw}\t$out\n")
        }
    }

    companion object {
        /**
         * Simulated "faulty fixes" that are not faults: the damage only undoes part of the fix and
         * leaves an allowed rewrite of the raw text. "accent retiré" on en-to-r01 gives
         * "Trois-Rivieres", the raw "Trois Rivieres" joined by a hyphen: no letter of the raw changed.
         */
        val HARMLESS = setOf("accent retiré|en-to-r01")

        // Pinned results (see android/DECISIONS.md, step 3.3).
        const val CEILING_NO_LEXICON = 28
        const val CEILING_LEXICON = 36
        const val CEILING_NEAR = 44
        const val CONTROL_FAULTS_TRIED = 399
        const val DETECTOR_FLAGGED_TARGETS = 56
        const val DETECTOR_FLAGGED_CONTROLS = 19
    }
}

/** The simulation lexicon (`src/test/resources/polissage/lexique-simulation.txt`). */
object SimulationLexicon {
    const val RESOURCE = "/polissage/lexique-simulation.txt"

    fun parse(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    fun load(): List<String> =
        parse(checkNotNull(SimulationLexicon::class.java.getResource(RESOURCE)) { "missing $RESOURCE" }.readText())
}

/**
 * Faulty outputs a small LLM plausibly produces. Each mode returns null when
 * it does not apply to the sentence (or would not change it).
 */
object Corruption {
    class Mode(val name: String, val apply: (String, String) -> String?)

    private val WORD = Regex("[\\p{L}]+")
    private const val VOWELS = "aeiouyéèêàâîôûAEIOUY"

    private fun changed(before: String, after: String?) = after?.takeIf { it != before }

    /** "friperie" → "fripperie": doubles the first consonant between two vowels of the longest word. */
    fun hypercorrect(s: String): String? {
        val w = WORD.findAll(s).filter { it.value.length >= 5 }.maxByOrNull { it.value.length } ?: return null
        val t = w.value
        for (k in 1 until t.length - 1) {
            if (t[k] !in VOWELS && t[k - 1] in VOWELS && t[k + 1] in VOWELS && t[k].isLetter()) {
                return s.replaceRange(w.range, t.substring(0, k + 1) + t[k] + t.substring(k + 1))
            }
        }
        return null
    }

    private val SYNONYMS = linkedMapOf(
        "friperie" to "boutique", "poudrerie" to "neige", "dépanneur" to "magasin", "char" to "voiture", "chum" to "copain",
        "frette" to "froid", "tuque" to "bonnet", "pantoute" to "du tout", "jasé" to "discuté", "jasait" to "discutait",
        "courriel" to "e-mail", "magasiner" to "faire les magasins", "achaler" to "embêter", "cégep" to "collège",
        "tabagie" to "dépanneur", "pourvoirie" to "réserve", "clavardage" to "chat", "achalandage" to "affluence",
        "fin de semaine" to "week-end", "projets" to "dossiers", "company" to "firm", "winter" to "cold season",
        "bus" to "coach", "fine" to "okay", "carbon" to "coal", "dates" to "days", "poutine" to "fries",
    )

    val MODES = listOf(
        Mode("hypercorrection") { s, _ -> changed(s, hypercorrect(s)) },
        Mode("mot retiré") { s, _ ->
            val words = WORD.findAll(s).toList()
            if (words.size < 3) null else changed(s, s.removeRange(words[words.size - 2].range).replace(Regex("\\s{2,}"), " "))
        },
        Mode("mot ajouté") { s, lang ->
            val first = WORD.find(s) ?: return@Mode null
            changed(s, s.replaceRange(first.range, first.value + if (lang == "en") " really" else " vraiment"))
        },
        Mode("ponctuation") { s, _ ->
            changed(s, if (s.contains(',')) s.replaceFirst(",", "") else if (s.endsWith(".")) s.dropLast(1) + " !" else "$s.")
        },
        Mode("majuscule") { s, _ ->
            val w = WORD.findAll(s).drop(1).firstOrNull { it.value.length >= 4 && it.value.all { ch -> ch.isLowerCase() } }
                ?: return@Mode null
            changed(s, s.replaceRange(w.range, w.value.replaceFirstChar { it.uppercase() }))
        },
        Mode("préambule") { s, lang -> (if (lang == "en") "Here is the corrected text: " else "Voici le texte corrigé : ") + s },
        Mode("adresse inventée") { s, _ ->
            val addr = Regex("(?<![\\w.@/])([a-z0-9-]+\\.)+(com|ca)(?![\\w])").find(s)
            when {
                addr != null && !addr.value.startsWith("www.") -> s.replaceRange(addr.range, "www." + addr.value)
                addr != null -> s.replaceRange(addr.range, addr.value.replace(Regex("\\.(com|ca)$"), ".org"))
                else -> s.trimEnd() + " Voir kalmia.ca."
            }
        },
        Mode("accent retiré") { s, _ ->
            val w = WORD.findAll(s).firstOrNull { m -> m.value.any { it in "éèêàâîôûçÉÈÊÀÂÎÔÛÇ" } } ?: return@Mode null
            val folded = Normalizer.normalize(w.value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            changed(s, s.replaceRange(w.range, folded))
        },
        Mode("synonyme") { s, _ ->
            val hit = SYNONYMS.entries.firstNotNullOfOrNull { (from, to) ->
                Regex("(?<![\\p{L}])${Regex.escape(from)}(?![\\p{L}])").find(s)?.let { it to to }
            } ?: return@Mode null
            changed(s, s.replaceRange(hit.first.range, hit.second))
        },
    )
}

/**
 * The simulated "noisy" LLM: deterministic (CRC32 of the case id), no
 * randomness between runs. On cases to fix: 55 % the expected text, 15 % the
 * expected text damaged by one failure mode, 15 % the raw text unchanged
 * (the model does not fix it), 15 % the raw text damaged. On controls: 70 %
 * unchanged, 30 % damaged. A pure invention, not a model of Qwen: it only
 * shows how the guard and the combination behave when the LLM errs.
 */
object NoisyLlm {
    fun bucket(id: String): Int = CRC32().apply { update(id.toByteArray()) }.value.rem(100).toInt()

    private fun damage(text: String, language: String, h: Int): String {
        val modes = Corruption.MODES
        for (k in modes.indices) {
            val mode = modes[(h + k) % modes.size]
            mode.apply(text, language)?.let { return it }
        }
        return text
    }

    fun output(c: EvalCase): String {
        val h = bucket(c.id)
        return if (!c.isControl) when {
            h < 55 -> c.expected
            h < 70 -> damage(c.expected, c.language, h)
            h < 85 -> c.raw
            else -> damage(c.raw, c.language, h)
        } else {
            if (h < 70) c.raw else damage(c.raw, c.language, h)
        }
    }
}
