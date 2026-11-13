package io.github.remtav.dictee

import io.github.remtav.dictee.audio.WavReader
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.SherpaEngine
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** One reference clip of the Québec set: WAV file name and reference text. */
internal data class QuebecClip(val file: String, val text: String)

/** WER normalisation shared by all models (issue #24); digits are NOT spelled out. */
internal object QuebecWer {
    fun tokens(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .replace('’', '\'').replace("œ", "oe")
            .replace(Regex("[^\\p{L}\\p{N}' ]+"), " ") // hyphens and punctuation become spaces
            .split(Regex("\\s+")).filter { it.isNotBlank() }

    fun errors(reference: String, hypothesis: String): Pair<Int, Int> {
        val r = tokens(reference)
        val h = tokens(hypothesis)
        var prev = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val cur = IntArray(h.size + 1)
            cur[0] = i
            for (j in 1..h.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[h.size] to r.size
    }

    fun parse(tsv: String): List<QuebecClip> =
        tsv.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }.map { QuebecClip(it[0], it[2]) }.toList()
}

/**
 * Larger Québec set (issue #24): Common Voice 22.0 fr, accent « Français du Canada ».
 * Not versioned: produced by `android/tools/jeu-quebecois/preparer.py --sortie DIR`.
 * Skipped without `-PquebecSetDir=DIR` (and without `-PmodelsDir`, see Host.ensureModel), so CI is unchanged.
 * Prints one `QCSET|model|WER|time` line per downloadable model; see android/docs/JEU_QUEBECOIS.md.
 *
 *   ./gradlew testDebugUnitTest -PmodelsDir=$HOME/dictee-models -PquebecSetDir=$HOME/jeu-quebecois \
 *       --tests '*QuebecSetHostTest*' --console=plain
 *   (add -PquebecSetModels=whisper-large-v3-turbo-int8,... to restrict the models by id)
 */
class QuebecSetHostTest {
    private val dir: File? = System.getProperty("dictee.quebecSetDir")?.let { File(it) }

    @Test fun normalisationIsTheSameForEveryModel() {
        assertEquals(listOf("l'oeuvre", "d'art", "avant", "garde", "42"), QuebecWer.tokens("L’Œuvre d’art, avant-garde ! 42"))
        assertEquals(0 to 3, QuebecWer.errors("Un deux trois", "un, deux trois."))
        assertEquals(2 to 3, QuebecWer.errors("un deux trois", "un quatre"))
    }

    @Test fun wholeSetForEveryDownloadableModel() {
        assumeTrue("set -PquebecSetDir=... (see tools/jeu-quebecois/preparer.py)", dir != null)
        val clips = QuebecWer.parse(File(dir!!, "references.tsv").readText())
        assumeTrue("empty set", clips.isNotEmpty())
        val only = System.getProperty("dictee.quebecSetModels")?.split(',')?.map { it.trim() }
        val audios = clips.map { File(dir, it.file).inputStream().use { s -> WavReader.read(s) }.resampled(16000).samples }
        println("QCSET|jeu|clips=${clips.size}|audio=${"%.0f".format(Locale.ROOT, audios.sumOf { it.size } / 16000.0)}s")
        for (spec in ModelCatalog.all.filter { only == null || it.id in only }) {
            val store = Host.ensureModel(spec)
            val engine = SherpaEngine(spec, store.dirFor(spec.id), EngineOptions(numThreads = 4, language = "fr"))
            try {
                engine.transcribe(audios[0]) // warm-up, not timed
                var errors = 0; var words = 0; var nanos = 0L
                for ((c, a) in clips.zip(audios)) {
                    val t0 = System.nanoTime()
                    val text = engine.transcribe(a).text
                    nanos += System.nanoTime() - t0
                    val (e, n) = QuebecWer.errors(c.text, text)
                    errors += e; words += n
                    println("QCSET-CLIP|${spec.shortName}|${c.file}|$e/$n|$text")
                }
                println("QCSET|${spec.shortName}|WER=${"%.1f".format(Locale.ROOT, 100.0 * errors / words)}% ($errors/$words)|time=${"%.1f".format(Locale.ROOT, nanos / 1e9)}s")
            } finally {
                engine.release()
            }
        }
    }
}
