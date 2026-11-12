package io.github.remtav.dictee

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.SherpaEngine
import io.github.remtav.dictee.text.Wer
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/** CPU time of the whole process: unlike wall time it does not grow when other jobs share the machine. */
private fun cpuNanos(): Long {
    // /proc/self/stat fields 14 and 15 (utime, stime) in clock ticks of 10 ms, summed over all threads.
    val f = File("/proc/self/stat").readText().substringAfterLast(")").trim().split(" ")
    return (f[11].toLong() + f[12].toLong()) * 10_000_000L
}

/**
 * Speed measurements for Whisper large-v3-turbo on the host (issue #15, request
 * "accélérer Whisper"). Skipped without -PmodelsDir (Host.ensureModel). Prints
 * `SPEED|...` lines collected in android/docs/ACCELERER_WHISPER.md.
 *
 * HOST x86-64 numbers: they show trends (fixed cost, effect of threads and of
 * the tail padding), not the latency of an arm64 phone.
 *
 * Run one part at a time, for example:
 *   ./gradlew testDebugUnitTest -PmodelsDir=/home/user/dictee-models \
 *       --tests '*WhisperSpeedHostTest.durationsAndThreads' --console=plain
 */
class WhisperSpeedHostTest {

    private class Rec(val spec: ModelSpec, threads: Int, language: String, tail: Int?) {
        val rec: OfflineRecognizer
        var lastCpuMs = 0.0
        init {
            val store = Host.ensureModel(spec)
            val cfg = SherpaEngine.buildConfig(spec, store.dirFor(spec.id), EngineOptions(numThreads = threads, language = language))
            if (tail != null) cfg.modelConfig.whisper.tailPaddings = tail
            rec = OfflineRecognizer(config = cfg)
        }

        /** Decode time in ms (create stream + decode + result, like SherpaEngine) and text. */
        fun run(samples: FloatArray): Pair<Double, String> {
            val c0 = cpuNanos()
            val t0 = System.nanoTime()
            val st = rec.createStream()
            try {
                st.acceptWaveform(samples, 16000)
                rec.decode(st)
                val text = rec.getResult(st).text.trim()
                lastCpuMs = (cpuNanos() - c0) / 1e6
                return (System.nanoTime() - t0) / 1e6 to text
            } finally {
                st.release()
            }
        }

        fun release() = rec.release()
    }

    // ---------------------------------------------------------------- audio

    private fun clip(name: String): FloatArray = Host.references.first { it.name == name }.audio.resampled(16000).samples

    /** ~1 s of speech: from just before the first speech onset of frqc2 (2-3 words, not a single word). */
    private fun oneSecond(): FloatArray {
        val s = clip("frqc2")
        val frame = 320
        val rms = DoubleArray(s.size / frame) { i ->
            var e = 0.0
            for (j in 0 until frame) e += s[i * frame + j].toDouble().let { it * it }
            sqrt(e / frame)
        }
        val peak = rms.max()
        val onset = rms.indexOfFirst { it > 0.15 * peak } * frame
        val from = (onset - 1600).coerceAtLeast(0)
        return s.copyOfRange(from, from + 16000)
    }

    private fun fiveSeconds(): FloatArray = clip("frqc1").copyOf(5 * 16000)

    private fun fifteenSeconds(): FloatArray {
        val pause = FloatArray(4800)
        return (clip("frqc1") + pause + clip("frqc2") + pause + clip("frqc3")).copyOf(15 * 16000)
    }

    private val audios: List<Pair<String, FloatArray>> by lazy {
        listOf("1" to oneSecond(), "5" to fiveSeconds(), "15" to fifteenSeconds())
    }

    private fun frames(samples: Int, tail: Int?): Int = minOf(((samples - 400) / 160 + 1) + (tail ?: 1000).let { if (it > 0) it else 1000 }, 3000)

    private fun load(): String = File("/proc/loadavg").readText().trim().split(" ").take(3).joinToString(" ")

    /** (min wall ms, cpu ms of that run, text). The host is shared: min is the least disturbed run. */
    private fun stats(r: Rec, samples: FloatArray, reps: Int = 2): Triple<Double, Double, String> {
        r.run(samples) // warm-up (allocations, first-run optimisations)
        var best = Double.MAX_VALUE; var cpu = 0.0
        var text = ""
        repeat(reps) { val (t, x) = r.run(samples); if (t < best) { best = t; cpu = r.lastCpuMs }; text = x }
        return Triple(best, cpu, text)
    }

    /** Prints a result line and, when DICTEE_SPEED_OUT is set, appends it to that file (survives a lost Gradle log). */
    private fun emit(line: String) {
        println(line)
        System.getenv("DICTEE_SPEED_OUT")?.let { File(it).appendText(line + "\n") }
    }

    private fun line(part: String, spec: ModelSpec, audioS: String, threads: Int, lang: String, tail: Int?, fr: Int, st: Triple<Double, Double, String>) =
        emit(
            "SPEED|$part|${spec.shortName}|audio=${audioS}s|threads=$threads|lang=${lang.ifEmpty { "auto" }}|tail=${tail ?: "default(1000)"}|frames=$fr|" +
                "wallMin=${"%.0f".format(Locale.ROOT, st.first)}ms|cpu=${"%.0f".format(Locale.ROOT, st.second)}ms|load=${load()}|text=${st.third.take(60)}",
        )

    // ---------------------------------------------------------------- parts

    private fun audio(d: String) = audios.first { it.first == d }.second

    /** Part A/B: Whisper, decode time by audio duration, threads and forced language (default tail padding). */
    @Test fun durationsAndThreads() {
        println("SPEED|host|cpu=${File("/proc/cpuinfo").readLines().first { it.startsWith("model name") }.substringAfter(": ")}|cores=${Runtime.getRuntime().availableProcessors()}|load=${load()}")
        val plan = listOf(
            Triple(4, "fr", listOf("1", "5", "15")),
            Triple(4, "", listOf("1", "5")),
            Triple(2, "fr", listOf("1", "5")),
            Triple(1, "fr", listOf("1", "5")),
        )
        for ((threads, lang, ds) in plan) {
            val r = Rec(ModelCatalog.whisperTurbo, threads, lang, null)
            try {
                for (d in ds) line("A", ModelCatalog.whisperTurbo, d, threads, lang, null, frames(audio(d).size, null), stats(r, audio(d)))
            } finally { r.release() }
        }
    }

    /** Part C: tail padding (encoder length) at 4 threads, forced fr; 3000 = the full 30 s window. */
    @Test fun tailPadding() {
        for (tail in listOf(600, 300, 100, 3000)) {
            val r = Rec(ModelCatalog.whisperTurbo, 4, "fr", tail)
            try {
                for (d in listOf("1", "5") + if (tail == 300 || tail == 600) listOf("15") else emptyList())
                    line("C", ModelCatalog.whisperTurbo, d, 4, "fr", tail, frames(audio(d).size, tail), stats(r, audio(d)))
            } finally { r.release() }
        }
    }

    /** Part C2: does a shorter tail padding change the text? WER on the bundled references. */
    @Test fun tailPaddingQuality() {
        for (tail in listOf(null, 600, 300)) {
            for (lang in listOf("", "fr")) {
                val r = Rec(ModelCatalog.whisperTurbo, 4, lang, tail)
                try {
                    var errors = 0; var words = 0; var empty = 0; var n = 0; var ms = 0.0
                    for (s in Host.references) {
                        if (lang == "fr" && s.language != "fr") continue
                        val (t, text) = r.run(s.audio.resampled(16000).samples)
                        val w = Wer.compute(s.reference!!, text)
                        errors += w.errors; words += w.referenceWords; n++; ms += t
                        if (text.isBlank()) empty++
                        if (w.errors > 0) emit("SPEED|C2-diff|tail=${tail ?: "default(1000)"}|lang=${lang.ifEmpty { "auto" }}|${s.name}|ref=${s.reference}|got=$text")
                    }
                    emit(
                        "SPEED|C2|tail=${tail ?: "default(1000)"}|lang=${lang.ifEmpty { "auto" }}|clips=$n|WER=${"%.1f".format(Locale.ROOT, 100.0 * errors / words)}%|errors=$errors/$words|empty=$empty|total=${"%.0f".format(Locale.ROOT, ms)}ms|load=${load()}",
                    )
                } finally { r.release() }
            }
        }
    }

    /** Part F: the other two models, same clips, 4 threads, automatic language. */
    @Test fun otherModels() {
        for (spec in listOf(ModelCatalog.parakeet, ModelCatalog.qwen3)) {
            val r = Rec(spec, 4, "", null)
            try {
                for (d in listOf("1", "5", "15")) line("F", spec, d, 4, "", null, 0, stats(r, audio(d)))
            } finally { r.release() }
        }
    }

    /**
     * Part E: cost of the provisional decodes on a 5 s segment, replayed on a virtual clock
     * with REAL decodes (DictationController rules: first partial at 0.8 s, then one whenever
     * the decode thread is idle and 700 ms have passed since the previous one; the VAD closes
     * the segment 0.5 s after speech ends; the decode thread is serial).
     */
    @Test fun provisionalDecodes() {
        val speech = fiveSeconds()
        val variants = listOf(
            Triple(ModelCatalog.whisperTurbo, "fr", null as Int?),
            Triple(ModelCatalog.whisperTurbo, "fr", 300),
            Triple(ModelCatalog.parakeet, "", null),
            Triple(ModelCatalog.qwen3, "", null),
        )
        for ((spec, lang, tail) in variants) {
            val r = Rec(spec, 4, lang, tail)
            try {
                r.run(speech) // warm-up
                val total = speech.size / 16000.0
                // Without provisional decodes: final decode only.
                val (tFinalAlone, _) = r.run(speech)
                // With provisional decodes.
                var w = 0.0            // virtual wall clock, seconds since speech start
                var busyUntil = 0.0
                var lastStart = -10.0
                var partials = 0
                var partialMs = 0.0
                while (w < total) {
                    if (w >= busyUntil && w >= 0.8 && w - lastStart >= 0.7) {
                        val len = (w * 16000).toInt().coerceAtMost(speech.size)
                        val (d, _) = r.run(speech.copyOf(len))
                        lastStart = w; partials++; partialMs += d
                        busyUntil = w + d / 1000.0
                        w = busyUntil
                    } else w += 0.032
                }
                val finalQueued = total + 0.5 // pause that closes the segment
                val finalStart = maxOf(finalQueued, busyUntil)
                val (tFinal, text) = r.run(speech)
                val doneWith = finalStart + tFinal / 1000.0
                val doneWithout = finalQueued + tFinalAlone / 1000.0
                emit(
                    "SPEED|E|${spec.shortName}|tail=${tail ?: "default"}|partials=$partials|partialCompute=${"%.0f".format(Locale.ROOT, partialMs)}ms|" +
                        "finalAlone=${"%.0f".format(Locale.ROOT, tFinalAlone)}ms|final=${"%.0f".format(Locale.ROOT, tFinal)}ms|" +
                        "finalWaitsForPartial=${"%.0f".format(Locale.ROOT, (finalStart - finalQueued) * 1000)}ms|" +
                        "textAfterSpeechEnd_with=${"%.2f".format(Locale.ROOT, doneWith - total)}s|textAfterSpeechEnd_without=${"%.2f".format(Locale.ROOT, doneWithout - total)}s|load=${load()}|text=${text.take(50)}",
                )
            } finally { r.release() }
        }
    }
    /**
     * Part G: Whisper small (int8, csukuangfj/sherpa-onnx-whisper-small) against turbo in the same run,
     * alternating the two to share the machine load. Needs DICTEE_WHISPER_SMALL_DIR (3 files).
     */
    @Test fun whisperSmallVsTurbo() {
        val dir = System.getenv("DICTEE_WHISPER_SMALL_DIR")?.let { File(it) }
        org.junit.Assume.assumeTrue("set DICTEE_WHISPER_SMALL_DIR", dir != null && dir.isDirectory)
        val cfg = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(
                    encoder = File(dir, "small-encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "small-decoder.int8.onnx").absolutePath,
                    language = "fr", task = "transcribe",
                ),
                tokens = File(dir, "small-tokens.txt").absolutePath,
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        )
        cfg.modelConfig.numThreads = 4
        val small = OfflineRecognizer(config = cfg)
        val turbo = Rec(ModelCatalog.whisperTurbo, 4, "fr", null)
        fun time(f: (FloatArray) -> Pair<Double, String>, a: FloatArray): Pair<Double, String> {
            f(a)
            val ts = ArrayList<Double>(); var text = ""
            repeat(2) { val (t, x) = f(a); ts += t; text = x }
            return ts.min() to text
        }
        val smallFn = { a: FloatArray ->
            val t0 = System.nanoTime(); val st = small.createStream()
            try { st.acceptWaveform(a, 16000); small.decode(st); (System.nanoTime() - t0) / 1e6 to small.getResult(st).text.trim() } finally { st.release() }
        }
        try {
            for ((d, a) in audios.filter { it.first != "15" }) {
                val (ts, tx) = time(smallFn, a)
                val (tt, ty) = time({ x -> turbo.run(x) }, a)
                emit("SPEED|G|audio=${d}s|threads=4|lang=fr|small_min=${"%.0f".format(Locale.ROOT, ts)}ms|turbo_min=${"%.0f".format(Locale.ROOT, tt)}ms|ratio_turbo_over_small=${"%.1f".format(Locale.ROOT, tt / ts)}|load=${load()}|small=${tx.take(40)}|turbo=${ty.take(40)}")
            }
            var es = 0; var et = 0; var words = 0
            for (s in Host.references.filter { it.language == "fr" }) {
                val a = s.audio.resampled(16000).samples
                es += Wer.compute(s.reference!!, smallFn(a).second).errors
                et += Wer.compute(s.reference!!, turbo.run(a).second).errors
                words += Wer.compute(s.reference!!, "").referenceWords
            }
            emit("SPEED|G-WER|fr clips (7)|small errors=$es/$words (${"%.1f".format(Locale.ROOT, 100.0 * es / words)}%)|turbo errors=$et/$words (${"%.1f".format(Locale.ROOT, 100.0 * et / words)}%)")
        } finally { small.release(); turbo.release() }
    }
    /** Part H: first decode after loading (cold) against the next ones. */
    @Test fun coldStart() {
        val a = audio("1")
        val t0 = System.nanoTime()
        val r = Rec(ModelCatalog.whisperTurbo, 4, "fr", null)
        val load = (System.nanoTime() - t0) / 1e6
        try {
            val runs = (1..4).map { r.run(a).first }
            emit("SPEED|H|load=${"%.0f".format(Locale.ROOT, load)}ms|decodes(1s)=${runs.joinToString { "%.0f".format(Locale.ROOT, it) }}ms|load=${load()}")
        } finally { r.release() }
    }
    /** Part C1: tail padding on ONE thread: CPU time is then the pure compute, whatever else runs on the host. */
    @Test fun tailPaddingOneThread() {
        for (tail in listOf(null, 600, 300, 100, 3000)) {
            val r = Rec(ModelCatalog.whisperTurbo, 1, "fr", tail)
            try {
                for (d in listOf("1", "5")) line("C1", ModelCatalog.whisperTurbo, d, 1, "fr", tail, frames(audio(d).size, tail), stats(r, audio(d)))
            } finally { r.release() }
        }
    }
}
