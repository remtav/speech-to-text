package io.github.remtav.dictee

import io.github.remtav.dictee.stt.Benchmark
import io.github.remtav.dictee.stt.Ct2Experiment
import io.github.remtav.dictee.stt.Ct2Native
import io.github.remtav.dictee.stt.Ct2WhisperEngine
import io.github.remtav.dictee.stt.DefaultEngineFactory
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.SttEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The CTranslate2 trial (issue #84) through the app's own code: JNI bridge, [Ct2WhisperEngine], [Benchmark].
 * Skipped unless both are given:
 *
 *   ./gradlew testDebugUnitTest --tests '*Ct2WhisperHostTest' \
 *       -Pct2HostLib=<sortie>/host-lib -Pct2Model=<dossier du modèle CTranslate2 int8> [-PmodelsDir=…]
 *
 * [againstTheAppWhisper] also needs -PmodelsDir (the app's Whisper, downloaded there by the app's own code).
 *
 * The host library is the same code as the arm64-v8a one; on x86-64 Ruy runs its portable kernels, so the
 * times printed here are slow and say nothing of the phone. What this checks is the text.
 */
class Ct2WhisperHostTest {

    private val modelDir: File? = System.getProperty("dictee.ct2Model")?.let { File(it) }

    private fun assumeReady() {
        assumeTrue("set -Pct2HostLib=... (libct2whisper.so for the host): ${Ct2Native.loadError}", Ct2Native.available)
        assumeTrue("set -Pct2Model=... (Whisper turbo converted to CTranslate2 int8)", Ct2Experiment.hasModel(modelDir))
    }

    @Test
    fun frenchReferencesThroughTheEngine() {
        assumeReady()
        println("CT2|${Ct2Native.buildInfo()}")
        assertTrue(Ct2Native.buildInfo().contains("ISA "))
        val samples = Host.references.filter { it.language == "fr" }
        val bench = Benchmark(DefaultEngineFactory { modelDir!! }, { modelDir!! }, EngineOptions(numThreads = 4, language = "fr"))
        val r = bench.runOne(Ct2Experiment.spec, samples)
        println(Benchmark.report(listOf(r)))
        assertEquals(null, r.error)
        assertEquals(samples.size, r.samples.size)
        // Same model and inputs as sherpa-onnx's Whisper: 9.6 % with the 1000-frame padding on the host
        // (android/docs/CTRANSLATE2_ANDROID.md); headroom for int8 kernel differences.
        val wer = r.totalWer!!
        assertTrue("WER ${"%.3f".format(wer)}", wer <= 0.15)
    }

    /** The comparison of the phone's « Comparer les modèles » screen, on the host: same samples, threads, padding. */
    @Test
    fun againstTheAppWhisper() {
        assumeReady()
        val store = Host.ensureModel(ModelCatalog.whisperTurbo)
        val samples = Host.references.filter { it.language == "fr" }
        val dirFor = { spec: ModelSpec ->
            if (spec.id == Ct2Experiment.MODEL_ID) modelDir!! else store.dirFor(spec.id)
        }
        val bench = Benchmark(DefaultEngineFactory(dirFor), dirFor, EngineOptions(numThreads = 4, language = "fr"))
        val reference = bench.runOne(ModelCatalog.whisperTurbo, samples)
        val trial = bench.runOne(Ct2Experiment.spec, samples)
        println(Benchmark.report(listOf(reference, trial)))
        val versus = Ct2Experiment.comparison(reference, trial)
        println(versus)
        assertTrue(versus != null && versus.contains("TOTAL ${samples.size} extrait(s)"))
        // Same model weights and encoder input: the trial must not lose accuracy beyond int8 noise.
        assertTrue("WER ${trial.totalWer} vs ${reference.totalWer}", trial.totalWer!! <= reference.totalWer!! + 0.05)
    }

    @Test
    fun detectsTheLanguageWhenNoneIsForced() {
        assumeReady()
        val engine = Ct2WhisperEngine(Ct2Experiment.spec, modelDir!!, EngineOptions(numThreads = 4, language = ""))
        try {
            for (name in listOf("en1", "fr1")) {
                val s = Host.references.first { it.name == name }
                val t = engine.transcribe(s.audio.resampled(SttEngine.SAMPLE_RATE).samples)
                println("CT2|$name|${t.language}|${t.text}")
                assertEquals(s.language, t.language)
                assertTrue(t.text.isNotBlank())
            }
        } finally {
            engine.release()
        }
    }

    @Test
    fun silenceGivesLittleOrNoText() {
        assumeReady()
        val engine = Ct2WhisperEngine(Ct2Experiment.spec, modelDir!!, EngineOptions(numThreads = 4, language = "fr"))
        try {
            val t = engine.transcribe(FloatArray(2 * SttEngine.SAMPLE_RATE))
            println("CT2|silence|« ${t.text} »")
            // 15 tokens per second of audio at most: a loop on 2 s of silence stops after ~38 tokens.
            assertTrue(t.text.length < 200)
        } finally {
            engine.release()
        }
    }
}
