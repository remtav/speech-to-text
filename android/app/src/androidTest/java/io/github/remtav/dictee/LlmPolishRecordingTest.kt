package io.github.remtav.dictee

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import io.github.remtav.dictee.cleanup.LlmCatalog
import io.github.remtav.dictee.cleanup.eval.GuardedLlmPolisher
import io.github.remtav.dictee.cleanup.eval.LlmCall
import io.github.remtav.dictee.cleanup.eval.RelaxedSafetyCheck
import io.github.remtav.dictee.cleanup.eval.ShippedLexicon
import io.github.remtav.dictee.cleanup.eval.WidePrompt
import io.github.remtav.dictee.cleanup.eval.wiredCombination
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #15, lot 3, steps 3.3 and 3.4: records the outputs, latency and memory of the
 * evaluation-only LLM variants ON A PHONE (MediaPipe runs on arm64 only).
 * Skipped unless the instrumentation argument `llmModel` is given, so the CI
 * emulator run never loads MediaPipe. Nothing here is reachable by a user.
 *
 * Arguments (`-e name value`):
 *  - `llmModel` (required): an LlmCatalog id installed through the app's
 *    settings (`llm-qwen2.5-0.5b-instruct-q8`, `llm-qwen2.5-1.5b-instruct-q8`),
 *    or the absolute path of a `.task` file readable by the app (for example
 *    pushed to /sdcard/Android/data/io.github.remtav.dictee/files/llm/).
 *  - `llmName`: short name used in the output file names (default: derived from the model).
 *  - `llmTemplate`: `chatml` (Qwen, default) or `gemma`.
 *  - `nearMatch`: `true` to allow the lexicon near match of the relaxed guard (default false).
 *
 * The relaxed guard uses the shipped lexicon (ShippedVocabulary, what would
 * ship), not the simulation lexicon.
 *
 * Writes into the app's external files, directory `polissage/`
 * (/sdcard/Android/data/io.github.remtav.dictee/files/polissage/), in the
 * format of RecordedApproach (`id`, `brut`, `sortie`, `latence_ms`):
 *  - `llm-<name>-prompt-large.tsv`: approach 3, output after the relaxed guard;
 *  - `llm-<name>-prompt-large-sans-gardefou.tsv`: the same LLM outputs before the guard;
 *  - `llm-<name>-combinaison.tsv`: approach 4 as wired in step 3.4 (PolishRules +
 *    ShippedVocabulary, LLM only where PolishRules.assess flags a probable case or
 *    an address), latency of the whole segment;
 *  - `memoire-<name>.txt`: PSS and peak RSS before loading, after loading, after the runs.
 * Procedure: android/docs/POLISSAGE_LOCAL.md (« Procédure de mesure sur le téléphone »).
 */
@RunWith(AndroidJUnit4::class)
class LlmPolishRecordingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext

    private data class Case(val id: String, val language: String, val raw: String)

    @Test
    fun recordLlmVariants() {
        val model = args.getString("llmModel")
        assumeTrue("no llmModel argument: phone-only recording, skipped", !model.isNullOrBlank())
        val path = resolveModel(model!!)
        val name = args.getString("llmName") ?: defaultName(model)
        val template = if (args.getString("llmTemplate") == "gemma") WidePrompt.Template.GEMMA else WidePrompt.Template.CHATML
        val options = RelaxedSafetyCheck.Options(
            lexicon = ShippedLexicon.names,
            allowLexiconNear = args.getString("nearMatch") == "true",
        )
        val cases = readCases()
        val outDir = File(context.getExternalFilesDir(null), "polissage").apply { mkdirs() }
        val memory = StringBuilder("# modèle: $path\n# gabarit: $template\n# proximité: ${options.allowLexiconNear}\n")
        memory.append(snapshot("avant chargement"))

        val loadStart = SystemClock.elapsedRealtime()
        val llm = LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder().setModelPath(path).setMaxTokens(512).setMaxTopK(1).build(),
        )
        memory.append("# chargement_ms: ${SystemClock.elapsedRealtime() - loadStart}\n")
        memory.append(snapshot("après chargement"))
        try {
            val call = LlmCall { prompt -> generate(llm, prompt) }
            val guarded = GuardedLlmPolisher(call, options, template)
            val combined = wiredCombination(call, options.allowLexiconNear, template)

            // Warm-up (first generation pays one-off costs), not recorded.
            guarded.polish("Mon code est sur git hub.", "fr")

            val wide = StringBuilder()
            val unguarded = StringBuilder()
            val combo = StringBuilder()
            var llmCalls = 0
            for (c in cases) {
                var t = SystemClock.elapsedRealtimeNanos()
                val r = guarded.polish(c.raw, c.language)
                val wideMs = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
                wide.row(c, r.text, wideMs)
                unguarded.row(c, r.llmOutput?.let(::oneLine) ?: c.raw, wideMs)

                t = SystemClock.elapsedRealtimeNanos()
                val r4 = combined.polish(c.raw, c.language)
                val comboMs = (SystemClock.elapsedRealtimeNanos() - t) / 1e6
                if (r4.llm != null) llmCalls++
                combo.row(c, r4.text, comboMs)
                Log.i(TAG, "${c.id}: ${"%.0f".format(wideMs)} ms, guard ${r.verdict?.ok} ${r.verdict?.reason ?: ""}")
            }
            memory.append(snapshot("après les passages"))
            memory.append("# appels_llm_combinaison: $llmCalls/${cases.size}\n")

            val base = "llm-$name"
            write(outDir, "$base-prompt-large", "Approche 3 mesurée sur téléphone : prompt élargi, garde-fou assoupli, lexique livré ($path).", wide)
            write(outDir, "$base-prompt-large-sans-gardefou", "Sorties brutes du LLM, AVANT garde-fou (même passage que $base-prompt-large).", unguarded)
            write(
                outDir, "$base-combinaison",
                "Approche 4 mesurée sur téléphone : règles + vocabulaire livré, LLM si PolishRules.assess signale un cas probable " +
                    "ou une adresse, garde-fou assoupli, lexique livré ($path).",
                combo,
            )
            File(outDir, "memoire-$name.txt").writeText(memory.toString())
            Log.i(TAG, "written to ${outDir.absolutePath}")
        } finally {
            llm.close()
        }
    }

    private fun generate(llm: LlmInference, prompt: String): String {
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(0f).setTopK(1).setRandomSeed(0).build()
        LlmInferenceSession.createFromOptions(llm, options).use { session ->
            session.addQueryChunk(prompt)
            return session.generateResponse()
        }
    }

    private fun resolveModel(model: String): String {
        val spec = LlmCatalog.byId(model)
        val file = if (spec != null) {
            val app = context.applicationContext as DicteeApp
            File(app.store.dirFor(spec.id), spec.file.path)
        } else {
            File(model)
        }
        check(file.isFile) { "model not found: ${file.absolutePath} (install it from the app settings, or push the .task file)" }
        return file.absolutePath
    }

    private fun defaultName(model: String): String = when {
        model.contains("0.5b", ignoreCase = true) -> "qwen05"
        model.contains("1.5b", ignoreCase = true) -> "qwen15"
        else -> File(model).nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(30)
    }

    private fun readAsset(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }

    /** The evaluation set, packaged in the test APK's assets (build.gradle.kts, androidTest sourceSet). */
    private fun readCases(): List<Case> {
        val lines = readAsset("jeu-evaluation.tsv").lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val header = lines.first().split('\t')
        val id = header.indexOf("id")
        val lang = header.indexOf("langue")
        val raw = header.indexOf("brut")
        return lines.drop(1).map { val f = it.split('\t'); Case(f[id], f[lang], f[raw]) }
    }

    private fun oneLine(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun StringBuilder.row(c: Case, out: String, ms: Double) {
        append(c.id).append('\t').append(c.raw).append('\t').append(oneLine(out)).append('\t').append("%.1f".format(java.util.Locale.ROOT, ms)).append('\n')
    }

    private fun write(dir: File, approach: String, description: String, rows: StringBuilder) {
        File(dir, "$approach.tsv").writeText(
            "# approche: $approach\n# llm: oui\n# description: $description\nid\tbrut\tsortie\tlatence_ms\n$rows",
        )
    }

    /** PSS (Debug) and VmRSS / VmHWM (peak resident set) of this process, in KiB. */
    private fun snapshot(label: String): String {
        val status = File("/proc/self/status").readLines()
        fun field(key: String) = status.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim() ?: "?"
        return "# mémoire ($label): pss_ko=${Debug.getPss()} VmRSS=${field("VmRSS")} VmHWM=${field("VmHWM")} " +
            "natif_alloue_ko=${Debug.getNativeHeapAllocatedSize() / 1024}\n"
    }

    private companion object {
        const val TAG = "LlmPolishRecording"
    }
}
