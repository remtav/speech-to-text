package io.github.remtav.dictee

import io.github.remtav.dictee.audio.VadParams
import io.github.remtav.dictee.audio.WavReader
import io.github.remtav.dictee.dictation.LocalCut
import io.github.remtav.dictee.dictation.LocalGrouping
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.SherpaEngine
import io.github.remtav.dictee.text.WhisperRepetition
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Issue #53, banc du regroupement des énoncés sur les 172 extraits québécois (-PquebecSetDir, -PmodelsDir), avec le vrai
 * VAD Silero et le vrai Whisper turbo (marge 1 000, le défaut), `SherpaEngine` de l'app.
 *
 * Les extraits de Common Voice sont des phrases isolées : pour simuler une dictée, ils sont pris trois par trois, dans
 * l'ordre du fichier, débarrassés de leur silence de début et de fin (VAD, 0,3 s gardées de chaque côté), et mis bout à
 * bout avec 0,7 s de silence entre eux (pause entre deux phrases d'environ 1,3 s), 0,5 s avant et 1 s après. Chaque
 * dictée passe par `LocalCut`, la découpe du contrôleur rejouée (même `BlockCutter`, mêmes appels) :
 *  - « actuel » : un décodage par énoncé du VAD, ce que fait la dictée locale aujourd'hui ;
 *  - « groupes 5 s » et « groupes 10 s » : le réglage de l'issue.
 * Les variantes alternent dictée par dictée dans la même exécution ; turbo sur l'extrait entier (le banc de #49) est
 * remesuré aussi, comme repère.
 *
 * Délai d'insertion (estimé) : l'audio est supposé arriver en temps réel ; un morceau est décodé dès que la capture le
 * rend et que le décodage précédent est fini, avec le temps de décodage mesuré ici ; le délai d'un énoncé va de sa fin
 * à l'insertion du morceau qui le contient. Les temps sont ceux de l'hôte, pas du téléphone.
 *
 *   ./gradlew testDebugUnitTest -PmodelsDir=$HOME/dictee-models -PquebecSetDir=$HOME/quebec-set \
 *       --tests '*LocalGroupingHostTest*' --console=plain
 */
class LocalGroupingHostTest {
    private val rate = 16000
    private val pauseSeconds = 0.5f // réglage « pause » par défaut (AppSettings.pauseSeconds)

    private class Dictation(val clips: List<String>, val reference: String, val audio: FloatArray)

    private class Tally {
        var seconds = 0.0
        var errors = 0
        var decodes = 0
        val delays = ArrayList<Double>()
    }

    @Test fun quebecSetBench() {
        val setDir = System.getProperty("dictee.quebecSetDir")?.let { File(it) }
        assumeTrue("set -PquebecSetDir=... (see tools/jeu-quebecois/preparer.py)", setDir != null)
        val spec = ModelCatalog.whisperTurbo
        val dir = Host.ensureModel(spec).dirFor(spec.id)
        val set = QuebecWer.parse(File(setDir!!, "references.tsv").readText())
        val audio = set.map { File(setDir, it.file).inputStream().use { s -> WavReader.read(s) }.resampled(rate).samples }
        val dictations = set.indices.chunked(3).map { idx ->
            val parts = ArrayList<FloatArray>()
            parts += FloatArray(rate / 2)
            idx.forEachIndexed { k, i ->
                if (k > 0) parts += FloatArray(rate * 7 / 10)
                parts += trim(audio[i])
            }
            parts += FloatArray(rate)
            Dictation(idx.map { set[it].file }, idx.joinToString(" ") { set[it].text }, concat(parts))
        }
        val variants = listOf<Pair<String, LocalGrouping?>>("actuel" to null, "groupes5s" to LocalGrouping(5f), "groupes10s" to LocalGrouping(10f))
        val engine = SherpaEngine(spec, dir, EngineOptions(numThreads = 4, language = "fr"))
        val tallies = variants.map { Tally() }
        val whole = Tally()
        var words = 0
        val out = File(System.getProperty("dictee.polishReportDir")!!).parentFile.resolve("regroupement172.tsv")
        out.parentFile.mkdirs()
        try {
            engine.transcribe(audio[0]) // chauffe, non mesurée
            out.bufferedWriter().use { w ->
                w.write("dictee\tvariante\tsecondes\taudio_s\tdecodages\tdelais_s\treference\thypothese\n")
                dictations.forEachIndexed { d, dict ->
                    words += WhisperRepetition.normalize(dict.reference).size
                    // Repère : chaque extrait entier, comme le banc de #49.
                    val wholeTexts = dict.clips.map { f ->
                        val a = audio[set.indexOfFirst { it.file == f }]
                        val t0 = System.nanoTime()
                        val t = engine.transcribe(a)
                        whole.seconds += (System.nanoTime() - t0) / 1e9
                        whole.decodes++
                        t.text
                    }
                    whole.errors += wordErrors(dict.reference, wholeTexts.joinToString(" "))
                    variants.forEachIndexed { v, (name, grouping) ->
                        val vad = Host.vad(VadParams(minSilenceSeconds = pauseSeconds))
                        val pieces = try { LocalCut.cut(dict.audio, vad, grouping) } finally { vad.release() }
                        val texts = ArrayList<String>()
                        var busyUntil = 0.0
                        var seconds = 0.0
                        val delays = ArrayList<Double>()
                        for (p in pieces) {
                            val t0 = System.nanoTime()
                            val t = engine.transcribe(p.audio)
                            val s = (System.nanoTime() - t0) / 1e9
                            seconds += s
                            texts += t.text
                            busyUntil = maxOf(busyUntil, p.readySample / rate.toDouble()) + s
                            for (e in p.utteranceEnds) delays += busyUntil - e / rate.toDouble()
                        }
                        val hyp = texts.filter { it.isNotEmpty() }.joinToString(" ")
                        val tally = tallies[v]
                        tally.seconds += seconds
                        tally.errors += wordErrors(dict.reference, hyp)
                        tally.decodes += pieces.size
                        tally.delays += delays
                        w.write("$d\t$name\t${f4(seconds)}\t${f4(dict.audio.size / rate.toDouble())}\t${pieces.size}\t" +
                            "${delays.joinToString(",") { f4(it) }}\t${dict.reference}\t$hyp\n")
                    }
                }
            }
        } finally {
            engine.release()
        }
        val audioSeconds = dictations.sumOf { it.audio.size } / rate.toDouble()
        println("BANC|${set.size} extraits en ${dictations.size} dictées|$words mots|${"%.1f".format(Locale.ROOT, audioSeconds)} s d'audio|${out.absolutePath}")
        println("BANC|turbo extrait entier (repère #49)|temps ${"%.1f".format(Locale.ROOT, whole.seconds)} s|${whole.decodes} décodages|" +
            "WER ${whole.errors}/$words = ${pct(whole.errors, words)}")
        val base = tallies[0].seconds
        variants.forEachIndexed { i, (name, _) ->
            val t = tallies[i]
            val d = t.delays.sorted()
            println("BANC|$name|temps ${"%.1f".format(Locale.ROOT, t.seconds)} s (${"%+.1f".format(Locale.ROOT, 100 * (t.seconds / base - 1))} %)|" +
                "${t.decodes} décodages|WER ${t.errors}/$words = ${pct(t.errors, words)}|" +
                "délai fin d'énoncé → insertion (hôte) : moyenne ${f2(d.average())} s, médiane ${f2(q(d, 0.5))} s, " +
                "90e centile ${f2(q(d, 0.9))} s, max ${f2(d.last())} s (${d.size} énoncés)")
        }
    }

    /** The clip without its leading and trailing silence (VAD), 0.3 s kept on each side; whole when no speech. */
    private fun trim(a: FloatArray): FloatArray {
        val vad = Host.vad(VadParams(minSilenceSeconds = pauseSeconds))
        try {
            var first = -1L
            var last = -1L
            fun drain() {
                while (true) {
                    val s = vad.poll() ?: break
                    if (first < 0) first = s.startSample
                    last = s.startSample + s.samples.size
                }
            }
            // In chunks of 512, as the controller: one accept of the whole clip returns a wrong segment (sherpa-onnx 2.0.1).
            var i = 0
            while (i < a.size) {
                val n = minOf(512, a.size - i)
                vad.accept(a.copyOfRange(i, i + n))
                i += n
                drain()
            }
            vad.flush()
            drain()
            if (first < 0) return a
            val margin = rate * 3 / 10
            return a.copyOfRange((first - margin).coerceAtLeast(0).toInt(), (last + margin).coerceAtMost(a.size.toLong()).toInt())
        } finally {
            vad.release()
        }
    }

    private fun concat(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private fun q(sorted: List<Double>, p: Double): Double = sorted[((sorted.size - 1) * p).toInt()]
    private fun f2(v: Double) = "%.2f".format(Locale.ROOT, v)
    private fun f4(v: Double) = "%.4f".format(Locale.ROOT, v)
    private fun pct(e: Int, n: Int) = "%.1f %%".format(Locale.ROOT, 100.0 * e / n)

    /** Word edit distance after the normalisation of `wer.py`, as the bench of #49. */
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
