package io.github.remtav.dictee

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import io.github.remtav.dictee.audio.WavReader
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.MemorySampler
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.RepetitionFix
import io.github.remtav.dictee.stt.RepetitionOutcome
import io.github.remtav.dictee.stt.SherpaEngine
import io.github.remtav.dictee.stt.WhisperTuning
import io.github.remtav.dictee.text.WhisperRepetition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Issue #49 on the host, with the real Whisper large-v3-turbo (skipped without -PmodelsDir, see Host.ensureModel):
 *  - `OfflineRecognizer.setConfig` really changes the padding of the loaded model (no second model);
 *  - the « Automatique » mode of SherpaEngine, both corrections;
 *  - the bench of the issue on the 172 Québec clips (also needs -PquebecSetDir): turbo at 1 000 measured in the
 *    same run, alternating clip by clip with the two options. WER with the normalisation of `wer.py` (#40).
 *
 *   ./gradlew testDebugUnitTest -PmodelsDir=$HOME/dictee-models --tests '*WhisperRepetitionHostTest*' --console=plain
 *   (add -PquebecSetDir=DIR, produced by tools/jeu-quebecois/preparer.py, for the 172-clip bench)
 */
class WhisperRepetitionHostTest {
    private val spec = ModelCatalog.whisperTurbo
    private val fr = EngineOptions(numThreads = 4, language = "fr")

    private fun config(dir: File, tail: Int): OfflineRecognizerConfig =
        SherpaEngine.buildConfig(spec, dir, fr.copy(whisperTailPaddings = tail))

    private data class Out(val text: String, val tokens: Int, val millis: Double)

    private fun decode(rec: OfflineRecognizer, samples: FloatArray): Out {
        val t0 = System.nanoTime()
        val st = rec.createStream()
        try {
            st.acceptWaveform(samples, 16000)
            rec.decode(st)
            val r = rec.getResult(st)
            return Out(r.text.trim(), r.tokens.size, (System.nanoTime() - t0) / 1e6)
        } finally {
            st.release()
        }
    }

    private val clips by lazy { Host.references.filter { it.language != "en" }.map { it.name to it.audio.resampled(16000).samples } }

    /** The redecode option rests on this: one loaded model, padding swapped by setConfig, read again at each decode. */
    @Test fun setConfigChangesThePaddingOfTheLoadedModel() {
        val dir = Host.ensureModel(spec).dirFor(spec.id)
        val c300 = config(dir, 300)
        val c1000 = config(dir, 1000)
        val fresh300 = OfflineRecognizer(config = c300)
        val fresh1000 = OfflineRecognizer(config = c1000)
        val before = MemorySampler.rssMb()
        val switched = OfflineRecognizer(config = c300)
        val oneModelMb = MemorySampler.rssMb() - before
        try {
            var differing = 0
            for ((name, a) in clips) {
                val a300 = decode(fresh300, a)
                val a1000 = decode(fresh1000, a)
                val s300 = decode(switched, a)
                switched.setConfig(c1000)
                val s1000 = decode(switched, a)
                switched.setConfig(c300)
                val back300 = decode(switched, a)
                println("SETCONFIG|$name|300 ${"%.0f".format(Locale.ROOT, a300.millis)} ms ${a300.tokens} jetons|" +
                    "1000 ${"%.0f".format(Locale.ROOT, a1000.millis)} ms|après setConfig(1000) ${"%.0f".format(Locale.ROOT, s1000.millis)} ms|" +
                    "300: ${a300.text}|1000: ${a1000.text}")
                assertEquals("$name: fresh 300 vs switched (before any setConfig)", a300.text, s300.text)
                assertEquals("$name: setConfig(1000) gives the text of a recognizer built at 1000", a1000.text, s1000.text)
                assertEquals("$name: setConfig(300) gives back the 300 text", a300.text, back300.text)
                if (a300.text != a1000.text) differing++
            }
            println("SETCONFIG|résumé|${clips.size} extraits, $differing dont le texte change entre 300 et 1 000|" +
                "mémoire d'un recognizer de plus : $oneModelMb Mo (aucun second chargement par setConfig)")
            // The check is only meaningful if the padding changes some output: frqc5 repeats at 300 (#40, section 7.3).
            assertTrue("no clip changes between 300 and 1000: setConfig would be untested", differing > 0)
        } finally {
            fresh300.release(); fresh1000.release(); switched.release()
        }
    }

    /** SherpaEngine in « Automatique »: a repetition is redecoded at 1 000 (same text as 1 000) or cut. */
    @Test fun automaticModeOfTheEngine() {
        val dir = Host.ensureModel(spec).dirFor(spec.id)
        val plain = SherpaEngine(spec, dir, fr)
        val redecode = SherpaEngine(spec, dir, fr.copy(whisperTailPaddings = 300, whisperRepetitionFix = RepetitionFix.REDECODE))
        val cut = SherpaEngine(spec, dir, fr.copy(whisperTailPaddings = 300, whisperRepetitionFix = RepetitionFix.CUT))
        try {
            val outcomes = HashMap<RepetitionOutcome, Int>()
            var extended = 0
            for ((name, a) in clips) {
                val p = plain.transcribe(a)
                val r = redecode.transcribe(a)
                val c = cut.transcribe(a)
                outcomes.merge(r.repetition, 1, Int::plus)
                if (r.ceilingExtended) extended++
                println("AUTO|$name|1000: ${p.text} (${p.decodeMillis} ms)|redécoder ${r.repetition}: ${r.text} (${r.decodeMillis} ms)|" +
                    "couper ${c.repetition}: ${c.text} (${c.decodeMillis} ms)")
                if (r.repetition == RepetitionOutcome.REDECODED) assertEquals("$name: redecoded text is the 1000 text", p.text, r.text)
                assertTrue(r.repetition in setOf(RepetitionOutcome.NONE, RepetitionOutcome.REDECODED))
                assertTrue(c.repetition != RepetitionOutcome.REDECODED)
                // Both options see the same first decode: they fire on the same clips.
                assertEquals(name, r.repetition == RepetitionOutcome.NONE, c.repetition == RepetitionOutcome.NONE)
            }
            // frqc5 runs into the token ceiling at 300 (#40). Decoded again with silence added (SherpaEngine.decode),
            // it now ends by itself, measured on the host with the Python package of the same sherpa-onnx; otherwise
            // the filter fires. Either way something must have handled it.
            assertTrue("neither the ceiling silence nor the filter fired on the bundled clips: $outcomes",
                extended > 0 || (outcomes[RepetitionOutcome.REDECODED] ?: 0) > 0)
        } finally {
            plain.release(); redecode.release(); cut.release()
        }
    }

    /**
     * Bench of the issue (#49 criteria): 172 Québec clips, turbo at 1 000 and the two options of « Automatique »,
     * alternating clip by clip in one run; time of a clip = wall time of SherpaEngine.transcribe (both decodes
     * when redecoded). Writes build/reports/repetition172.tsv and prints `BANC|...` lines.
     */
    @Test fun quebecSetBench() {
        val setDir = System.getProperty("dictee.quebecSetDir")?.let { File(it) }
        assumeTrue("set -PquebecSetDir=... (see tools/jeu-quebecois/preparer.py)", setDir != null)
        val dir = Host.ensureModel(spec).dirFor(spec.id)
        val set = QuebecWer.parse(File(setDir!!, "references.tsv").readText())
        val audio = set.map { File(setDir, it.file).inputStream().use { s -> WavReader.read(s) }.resampled(16000).samples }
        val variants = listOf(
            "turbo1000" to fr,
            "auto-redecoder" to fr.copy(whisperTailPaddings = WhisperTuning.AUTO_TAIL_PADDINGS, whisperRepetitionFix = RepetitionFix.REDECODE),
            "auto-couper" to fr.copy(whisperTailPaddings = WhisperTuning.AUTO_TAIL_PADDINGS, whisperRepetitionFix = RepetitionFix.CUT),
        )
        val engines = variants.map { (_, o) -> SherpaEngine(spec, dir, o) }
        val seconds = DoubleArray(variants.size)
        val errors = IntArray(variants.size)
        val outcomes = variants.map { HashMap<RepetitionOutcome, Int>() }
        var words = 0
        val out = File(System.getProperty("dictee.polishReportDir")!!).parentFile.resolve("repetition172.tsv")
        out.parentFile.mkdirs()
        try {
            engines.forEach { it.transcribe(audio[0]) } // warm-up, not timed
            out.bufferedWriter().use { w ->
                w.write("clip\tvariante\tsecondes\taudio_s\tissue\treference\thypothese\n")
                for ((clip, a) in set.zip(audio)) {
                    val n = WhisperRepetition.normalize(clip.text).size
                    words += n
                    engines.forEachIndexed { i, e ->
                        val t0 = System.nanoTime()
                        val t = e.transcribe(a)
                        val s = (System.nanoTime() - t0) / 1e9
                        seconds[i] += s
                        val err = wordErrors(clip.text, t.text)
                        errors[i] += err
                        outcomes[i].merge(t.repetition, 1, Int::plus)
                        w.write("${clip.file}\t${variants[i].first}\t${"%.4f".format(Locale.ROOT, s)}\t" +
                            "${"%.3f".format(Locale.ROOT, a.size / 16000.0)}\t${t.repetition}\t${clip.text}\t${t.text}\n")
                    }
                }
            }
        } finally {
            engines.forEach { it.release() }
        }
        println("BANC|${set.size} extraits|$words mots|${out.absolutePath}")
        variants.forEachIndexed { i, (name, _) ->
            println("BANC|$name|temps ${"%.1f".format(Locale.ROOT, seconds[i])} s (${"%+.1f".format(Locale.ROOT, 100 * (seconds[i] / seconds[0] - 1))} %)|" +
                "WER ${errors[i]}/$words = ${"%.1f".format(Locale.ROOT, 100.0 * errors[i] / words)} %|" +
                "issues ${outcomes[i].filterKeys { it != RepetitionOutcome.NONE }}")
        }
    }

    /**
     * Replays a TSV of `banc_rapide.py` (#40: variants `turbo` at 1 000 and `m300` at 300, same run) through the
     * app's filter: simulated times and WER of both options, and every clip where the filter fires or where the
     * 300 text differs from the 1 000 one. No model needed: `-PrepetitionTsv=FILE`.
     */
    @Test fun replayBenchTsv() {
        val tsv = System.getProperty("dictee.repetitionTsv")?.let { File(it) }
        assumeTrue("set -PrepetitionTsv=... (TSV of banc_rapide.py with variants turbo and m300)", tsv != null)
        data class Row(val seconds: Double, val tokens: Int, val ceiling: Int, val ref: String, val hyp: String)
        val rows = HashMap<String, HashMap<String, Row>>()
        tsv!!.readLines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }.forEach { c ->
            rows.getOrPut(c[0]) { HashMap() }[c[1]] = Row(c[3].toDouble(), c[5].toInt(), c[6].toInt(), c[7], c[8])
        }
        val clips = rows.keys.sorted().filter { "turbo" in rows[it]!! && "m300" in rows[it]!! }
        var t1000 = 0.0; var t300 = 0.0; var tRedecode = 0.0
        var e1000 = 0; var e300 = 0; var eRedecode = 0; var eCut = 0; var words = 0
        val fired = HashMap<String, Int>(); var cuts = 0
        for (clip in clips) {
            val ref = rows[clip]!!["turbo"]!!; val short = rows[clip]!!["m300"]!!
            words += WhisperRepetition.normalize(ref.ref).size
            t1000 += ref.seconds; t300 += short.seconds
            e1000 += wordErrors(ref.ref, ref.hyp); e300 += wordErrors(ref.ref, short.hyp)
            val rule = WhisperRepetition.detect(short.hyp, short.tokens, short.ceiling)
            val cut = if (rule != null) WhisperRepetition.cut(short.hyp) else null
            if (rule != null) fired.merge(rule.label, 1, Int::plus)
            if (cut != null) cuts++
            tRedecode += short.seconds + if (rule != null) ref.seconds else 0.0
            eRedecode += wordErrors(ref.ref, if (rule != null) ref.hyp else short.hyp)
            eCut += wordErrors(ref.ref, cut ?: short.hyp)
            if (rule != null || WhisperRepetition.normalize(short.hyp) != WhisperRepetition.normalize(ref.hyp)) {
                println("REJEU|$clip|${rule?.label ?: "-"}|${short.tokens}/${short.ceiling}|300: ${short.hyp}|coupé: ${cut ?: "-"}|1000: ${ref.hyp}")
            }
        }
        fun pct(e: Int) = "%.1f %%".format(Locale.ROOT, 100.0 * e / words)
        fun dt(t: Double) = "%.1f s (%+.1f %%)".format(Locale.ROOT, t, 100 * (t / t1000 - 1))
        println("REJEU|${clips.size} extraits, $words mots|déclenchements $fired, coupes $cuts")
        println("REJEU|turbo 1000|${dt(t1000)}|WER ${pct(e1000)}")
        println("REJEU|300 seule|${dt(t300)}|WER ${pct(e300)}")
        println("REJEU|auto + redécoder|${dt(tRedecode)}|WER ${pct(eRedecode)}")
        println("REJEU|auto + couper|${dt(t300)}|WER ${pct(eCut)}")
    }

    /** Word edit distance after the normalisation of `wer.py` (WhisperRepetition.normalize is that function). */
    private fun wordErrors(reference: String, hypothesis: String): Int {
        val r = WhisperRepetition.normalize(reference)
        val h = WhisperRepetition.normalize(hypothesis)
        var prev = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val cur = IntArray(h.size + 1)
            cur[0] = i
            for (j in 1..h.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[h.size]
    }
}
