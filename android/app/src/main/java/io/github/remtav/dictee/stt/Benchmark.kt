package io.github.remtav.dictee.stt

import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.text.Wer
import java.io.File
import java.util.Locale

/** One audio sample to transcribe, with its expected text when known. */
class BenchSample(val name: String, val language: String, val audio: PcmAudio, val reference: String?)

data class SampleResult(
    val sample: String,
    val language: String,
    val text: String,
    val decodeMillis: Long,
    val audioSeconds: Float,
    val wer: Double?,
    /** What the Whisper repetition filter did (issue #49). */
    val repetition: RepetitionOutcome = RepetitionOutcome.NONE,
) {
    val rtf: Float get() = if (audioSeconds > 0) decodeMillis / 1000f / audioSeconds else 0f
}

data class ModelResult(
    val spec: ModelSpec,
    val provider: String,
    val loadMillis: Long,
    /** Peak resident memory of the process during load + decodes, in MB. */
    val peakRssMb: Long,
    /** Resident memory before loading, in MB. */
    val baselineRssMb: Long,
    val samples: List<SampleResult>,
    val error: String? = null,
) {
    val meanRtf: Float get() = samples.map { it.rtf }.average().toFloat()
    val totalWer: Double? get() {
        val scored = samples.filter { it.wer != null }
        if (scored.isEmpty()) return null
        return scored.sumOf { it.wer!! } / scored.size
    }

    /** Samples per repetition-filter outcome other than [RepetitionOutcome.NONE] (issue #49). */
    val repetitionCounts: Map<RepetitionOutcome, Int>
        get() = samples.groupingBy { it.repetition }.eachCount() - RepetitionOutcome.NONE
}

/**
 * Loads each model in turn, transcribes every sample, and measures load time,
 * real-time factor and peak memory. The comparison screen and the host tests
 * both use it, so the numbers in CI logs and on the phone come from one code path.
 */
class Benchmark(
    private val factory: SttEngineFactory,
    private val dirFor: (ModelSpec) -> File,
    private val options: EngineOptions = EngineOptions(),
    /** Options per model; the comparison screen passes the dictation's own (forced language, Whisper padding). */
    private val optionsFor: (ModelSpec) -> EngineOptions = { options },
) {
    fun interface Progress {
        fun onProgress(message: String)
    }

    fun run(models: List<ModelSpec>, samples: List<BenchSample>, progress: Progress = Progress { }): List<ModelResult> =
        models.map { runOne(it, samples, progress) }

    fun runOne(spec: ModelSpec, samples: List<BenchSample>, progress: Progress = Progress { }): ModelResult {
        System.gc()
        val baseline = MemorySampler.rssMb()
        val sampler = MemorySampler().apply { start() }
        var engine: SttEngine? = null
        try {
            progress.onProgress("Chargement de ${spec.shortName}…")
            val t0 = System.nanoTime()
            engine = factory.create(spec, dirFor(spec), optionsFor(spec))
            val loadMillis = (System.nanoTime() - t0) / 1_000_000
            val results = samples.map { s ->
                progress.onProgress("${spec.shortName} : ${s.name}")
                val audio = s.audio.resampled(SttEngine.SAMPLE_RATE)
                val t = engine.transcribe(audio.samples, SttEngine.SAMPLE_RATE)
                SampleResult(
                    sample = s.name,
                    language = s.language,
                    text = t.text,
                    decodeMillis = t.decodeMillis,
                    audioSeconds = audio.seconds,
                    wer = s.reference?.let { Wer.compute(it, t.text).rate },
                    repetition = t.repetition,
                )
            }
            return ModelResult(spec, engine.provider, loadMillis, sampler.stop(), baseline, results)
        } catch (e: Throwable) {
            return ModelResult(spec, optionsFor(spec).acceleration.provider, 0, sampler.stop(), baseline, emptyList(),
                error = e.message ?: e.javaClass.simpleName)
        } finally {
            engine?.release()
        }
    }

    companion object {
        /** Report wording of a repetition-filter outcome, "" for none. */
        fun repetitionLabel(o: RepetitionOutcome): String = when (o) {
            RepetitionOutcome.NONE -> ""
            RepetitionOutcome.REDECODED -> "répétition redécodée"
            RepetitionOutcome.CUT -> "répétition coupée"
            RepetitionOutcome.DETECTED -> "répétition détectée, texte gardé"
        }

        /**
         * « répétitions : 1 redécodée sur 9 segments » for a Whisper model in automatic mode
         * ([automatic]: the filter was on), "" otherwise.
         */
        fun repetitionSummary(r: ModelResult, automatic: Boolean): String {
            if (!automatic) return ""
            val c = r.repetitionCounts
            val parts = listOfNotNull(
                c[RepetitionOutcome.REDECODED]?.let { "$it redécodé(s)" },
                c[RepetitionOutcome.CUT]?.let { "$it coupé(s)" },
                c[RepetitionOutcome.DETECTED]?.let { "$it détecté(s) sans coupure" },
            ).ifEmpty { listOf("aucune") }
            return "   répétitions corrigées : ${parts.joinToString(", ")} sur ${r.samples.size} segment(s)"
        }

        fun report(results: List<ModelResult>, header: String = "", automatic: (ModelSpec) -> Boolean = { false }): String = buildString {
            if (header.isNotEmpty()) appendLine(header)
            for (r in results) {
                appendLine("== ${r.spec.displayName} (${r.spec.publisher}) · provider ${r.provider}")
                if (r.error != null) {
                    appendLine("   ERREUR : ${r.error}")
                    continue
                }
                appendLine(
                    String.format(
                        Locale.ROOT, "   chargement %.2f s · RTF moyen %.3f · mémoire en pointe %d Mo (base %d Mo)%s",
                        r.loadMillis / 1000f, r.meanRtf, r.peakRssMb, r.baselineRssMb,
                        r.totalWer?.let { String.format(Locale.ROOT, " · WER moyen %.1f %%", it * 100) } ?: "",
                    ),
                )
                repetitionSummary(r, automatic(r.spec)).takeIf { it.isNotEmpty() }?.let { appendLine(it) }
                for (s in r.samples) {
                    appendLine(
                        String.format(
                            Locale.ROOT, "   [%s %s] %.1f s audio · %.2f s · RTF %.3f%s",
                            s.sample, s.language, s.audioSeconds, s.decodeMillis / 1000f, s.rtf,
                            s.wer?.let { String.format(Locale.ROOT, " · WER %.1f %%", it * 100) } ?: "",
                        ) + repetitionLabel(s.repetition).let { if (it.isEmpty()) "" else " · $it" },
                    )
                    appendLine("      « ${s.text} »")
                }
            }
        }
    }
}

/** Samples the process resident set size (VmRSS) on a background thread. */
class MemorySampler(private val intervalMillis: Long = 50) {
    @Volatile private var running = false
    @Volatile private var peak = 0L
    private var thread: Thread? = null

    fun start() {
        running = true
        peak = rssMb()
        thread = Thread({
            while (running) {
                peak = maxOf(peak, rssMb())
                try { Thread.sleep(intervalMillis) } catch (_: InterruptedException) { break }
            }
        }, "rss-sampler").apply { isDaemon = true; start() }
    }

    /** Stops sampling; returns the peak in MB. */
    fun stop(): Long {
        running = false
        thread?.interrupt()
        thread?.join(500)
        return maxOf(peak, rssMb())
    }

    companion object {
        fun rssMb(): Long = try {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.div(1024) ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
