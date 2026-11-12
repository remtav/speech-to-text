package io.github.remtav.dictee

import io.github.remtav.dictee.audio.PcmAudioSource
import io.github.remtav.dictee.audio.SileroVad
import io.github.remtav.dictee.audio.VadParams
import io.github.remtav.dictee.audio.WavReader
import io.github.remtav.dictee.dictation.DictationController
import io.github.remtav.dictee.dictation.DictationSession
import io.github.remtav.dictee.dictation.StringSink
import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.stt.BenchSample
import io.github.remtav.dictee.stt.Benchmark
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.ModelStore
import io.github.remtav.dictee.stt.SampleResult
import io.github.remtav.dictee.stt.SherpaEngine
import io.github.remtav.dictee.stt.asDownloadable
import io.github.remtav.dictee.text.Wer
import io.github.remtav.dictee.ui.CompareActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Runs the real sherpa-onnx library (same release as the AAR, built for Linux
 * x86-64) on the CI machine. The emulator cannot hold the models (see
 * DECISIONS.md), so model loading and accuracy are checked here, through the
 * app's own classes: ModelStore (download + SHA-256), SherpaEngine, SileroVad,
 * Benchmark and DictationController.
 */
object Host {
    val refDir = File(System.getProperty("dictee.refDir"))
    val assetsDir = File(System.getProperty("dictee.assetsDir"))
    val modelsDir: File? = System.getProperty("dictee.modelsDir")?.let { File(it) }

    val references: List<BenchSample> by lazy {
        CompareActivity.parseReferences(File(refDir, "references.tsv").readText()) { name ->
            File(refDir, name).inputStream().use { WavReader.read(it) }
        }
    }

    fun vad(params: VadParams = VadParams()) = SileroVad.fromFile(File(assetsDir, SileroVad.ASSET).absolutePath, params)

    /** Installs [spec] in [modelsDir] through the app's downloader, unless already verified there. */
    fun ensureModel(spec: ModelSpec): ModelStore {
        val dir = modelsDir
        assumeTrue("set -PmodelsDir=... (or DICTEE_MODELS_DIR) to run the model tests", dir != null)
        val store = ModelStore(dir!!)
        if (!store.isInstalled(spec)) {
            println("Downloading ${spec.displayName} into $dir …")
            store.download(spec.asDownloadable()) { _, _ -> }
        }
        assertTrue(store.isInstalled(spec))
        return store
    }

    /**
     * Word error rate ceilings on the bundled references, per model and language.
     * Measured on the CI host with these exact files (see DECISIONS.md), then
     * given headroom so a library update that degrades quality fails loudly.
     */
    val werCeiling = mapOf(
        ModelCatalog.parakeet.id to mapOf("en" to 0.12, "fr" to 0.15, "frqc" to 0.60),
        ModelCatalog.qwen3.id to mapOf("en" to 0.12, "fr" to 0.15, "frqc" to 0.60),
        ModelCatalog.whisperTurbo.id to mapOf("en" to 0.12, "fr" to 0.15, "frqc" to 0.60),
        // Fine-tuned on French only: English is measured and reported but not bounded.
        ModelCatalog.whisperQuebec.id to mapOf("en" to 1.0, "fr" to 0.15, "frqc" to 0.60),
    )
}

class SileroVadHostTest {
    @Test fun nativeLibraryLoadsAndVadSplitsAtPauses() {
        // Studio-trimmed clips only: Common Voice (frqc) clips carry leading and trailing silence,
        // so the VAD rightly keeps well under 80 % of their length.
        val clips = Host.references.filterNot { it.name.startsWith("frqc") }.map { it.audio }
        val source = PcmAudioSource(clips, realTime = false, gapSeconds = 1.5f)
        val vad = Host.vad()
        val buf = FloatArray(512)
        source.start()
        val segments = ArrayList<Float>()
        while (true) {
            val n = source.read(buf)
            if (n < 0) break
            vad.accept(buf.copyOf(n))
            while (true) segments += (vad.poll() ?: break).samples.size / 16000f
        }
        vad.flush()
        while (true) segments += (vad.poll() ?: break).samples.size / 16000f
        vad.release()
        println("VAD segments (s): ${segments.joinToString { "%.2f".format(Locale.ROOT, it) }}")
        // At least one segment per clip, none spanning two clips, all speech kept.
        assertTrue(segments.size >= clips.size)
        val speech = clips.sumOf { it.seconds.toDouble() }
        assertTrue(segments.sum() in (speech * 0.8).toFloat()..(speech * 1.2).toFloat())
        assertTrue(segments.all { it <= 20.5f })
    }
}

class ModelAccuracyHostTest {
    /**
     * Reference groups. Engines only know the language code (`fr`); the Québec
     * clips (Common Voice, accent « Français du Canada ») are told apart by the
     * `frqc` file-name prefix in assets/reference/references.tsv.
     */
    private fun group(s: SampleResult): String = when {
        s.sample.startsWith("frqc") -> "frqc"
        else -> s.language
    }

    private fun check(spec: ModelSpec) {
        val store = Host.ensureModel(spec)
        val bench = Benchmark(SherpaEngine.factory, { store.dirFor(it.id) }, EngineOptions(numThreads = 4))
        val result = bench.runOne(spec, Host.references) { println(it) }
        println(Benchmark.report(listOf(result), "Host (Linux x86-64), sherpa-onnx 2.0.1"))
        assertNull(result.error, result.error)
        val ceilings = Host.werCeiling.getValue(spec.id)
        for (g in listOf("en", "fr", "frqc")) {
            val scored = result.samples.filter { group(it) == g }
            assertTrue("no $g reference", scored.isNotEmpty())
            var errors = 0
            var words = 0
            for (s in scored) {
                val ref = Host.references.first { it.name == s.sample }.reference!!
                val w = Wer.compute(ref, s.text)
                errors += w.errors
                words += w.referenceWords
            }
            val wer = errors.toDouble() / words
            // Machine-readable line, collected in DECISIONS.md (issue #15, step 1.2).
            println(
                "WER ${spec.shortName} $g = ${"%.1f".format(Locale.ROOT, wer * 100)} % " +
                    "($errors errors / $words words, ${scored.size} clips)",
            )
            // Québec clips: report, with a deliberately wide sanity ceiling (a handful of
            // short clips from different speakers is too noisy for a tight bound).
            val ceiling = ceilings.getValue(g)
            assertTrue("${spec.shortName} $g WER $wer above $ceiling", wer <= ceiling)
        }
    }

    @Test fun parakeet() = check(ModelCatalog.parakeet)

    @Test fun qwen3() = check(ModelCatalog.qwen3)

    @Test fun whisperTurbo() = check(ModelCatalog.whisperTurbo)

    /** Skipped until the converted files are published (ModelCatalog.whisperQuebecHosted). */
    @Test fun whisperQuebec() {
        assumeTrue("Whisper QC is not hosted yet", ModelCatalog.whisperQuebecHosted)
        check(ModelCatalog.whisperQuebec)
    }
}

/** The whole dictation path with the default model: audio stream → Silero VAD → Parakeet → text. */
class EndToEndHostTest {
    @Test fun dictateReferenceClipsWithParakeet() {
        val spec = ModelCatalog.parakeet
        val store = Host.ensureModel(spec)
        val engine = SherpaEngine(spec, store.dirFor(spec.id), EngineOptions(numThreads = 4))
        try {
            val en = Host.references.first { it.name == "en1" }
            val fr = Host.references.first { it.name == "fr1" }
            val sink = StringSink()
            val listener = RecordingListener()
            val controller = DictationController({ sink }, { it.run() }, listener)
            controller.start(
                DictationSession(
                    spec, PcmAudioSource(listOf(en.audio, fr.audio), realTime = false, gapSeconds = 1.5f),
                    Host.vad(), TextPipeline(), { engine.transcribe(it) }, partials = false,
                ),
            )
            listener.awaitEnd()
            println("Dictated: ${sink.text}")
            val wer = Wer.compute(en.reference + " " + fr.reference, sink.text).rate
            assertTrue("end-to-end WER $wer", wer <= 0.15)
            assertTrue(sink.text.first().isUpperCase())
            assertEquals(-1, sink.text.indexOf("  "))
        } finally {
            engine.release()
        }
    }
}
