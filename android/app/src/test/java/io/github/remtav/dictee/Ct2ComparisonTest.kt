package io.github.remtav.dictee

import io.github.remtav.dictee.stt.Ct2Experiment
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelResult
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.SampleResult
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #84: the trial's line against the app's Whisper in « Comparer les modèles » (no native code needed). */
class Ct2ComparisonTest {

    private fun sample(name: String, ms: Long, wer: Double, text: String = "bonjour") =
        SampleResult(name, "fr", text, ms, 5f, wer)

    private fun result(spec: ModelSpec, vararg s: SampleResult, error: String? = null) =
        ModelResult(spec, "cpu", 4000, 1200, 200, s.toList(), error)

    @Test
    fun ratiosAndWerSampleBySample() {
        val reference = result(ModelCatalog.whisperTurbo, sample("fr1", 4000, 0.10), sample("frqc1", 2000, 0.0))
        val trial = result(Ct2Experiment.spec, sample("fr1", 2000, 0.10), sample("frqc1", 1000, 0.20, "bonjour là"))
        val text = Ct2Experiment.comparison(reference, trial)!!
        println(text)
        assertTrue(text, text.contains("[fr1] 5.0 s audio · sherpa 4.00 s · CTranslate2 2.00 s · vitesse ×2.00 · WER 10.0 % → 10.0 %"))
        assertTrue(text, text.contains("[frqc1]") && text.contains("textes différents"))
        assertTrue(text, text.contains("TOTAL 2 extrait(s) · sherpa 6.00 s · CTranslate2 3.00 s · vitesse ×2.00 · WER moyen 5.0 % → 15.0 %"))
    }

    @Test
    fun nothingWhenARunFailedOrNoSampleIsShared() {
        val reference = result(ModelCatalog.whisperTurbo, sample("fr1", 4000, 0.1))
        assertNull(Ct2Experiment.comparison(reference, result(Ct2Experiment.spec, error = "libct2whisper.so absente")))
        assertNull(Ct2Experiment.comparison(reference, result(Ct2Experiment.spec, sample("en1", 1000, 0.0))))
    }
}
