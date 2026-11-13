package io.github.remtav.dictee.stt

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The checkpoint tool (issues #13 and #35): sends reference clips to the server
 * as real dictations and records, per trial, the time from the end of the
 * dictation to the result, the polish status and the raw and polished texts.
 * Two modes per clip: [runBlocks] plays it in real time through the keyboard's
 * own controller (blocks sent while it "speaks", issue #35), [run] sends it
 * whole in one request (issue #13), for comparison on the same audio. No local
 * fallback here: a failure is reported as such.
 */
class ServerDiagnostic(
    private val config: RemoteConfig,
    private val engineFactory: (RemoteConfig) -> WholeDictationEngine = { RemoteEngine(ModelCatalog.remote, it) },
    private val blockEngineFactory: (RemoteConfig) -> RemoteEngine = { RemoteEngine(ModelCatalog.remote, it) },
) {
    companion object {
        const val MODE_BLOCKS = "par blocs"
        const val MODE_WHOLE = "entière"
        val LONG_TARGETS = listOf("30 s" to 30f, "2 min" to 120f, "5 min" to 290f)
    }

    data class Trial(
        val name: String,
        val audioSeconds: Float,
        val uploadMillis: Long = 0,
        val totalMillis: Long = 0,
        val polishStatus: String = "",
        val rawText: String = "",
        val polishedText: String? = null,
        val transport: String = "",
        val error: String? = null,
        val reference: String? = null,
        /** [MODE_BLOCKS] or [MODE_WHOLE]. */
        val mode: String = MODE_WHOLE,
        val blocks: Int = 0,
        /** Time from the end of the dictation to the result: what the user waits for. */
        val waitMillis: Long = 0,
    )

    /**
     * The trials of one run: each clip on its own, then all of them chained with
     * one second of silence (about the 30 s dictation of the latency target).
     */
    fun plan(samples: List<BenchSample>): List<BenchSample> {
        if (samples.size < 2) return samples
        val gap = FloatArray(SttEngine.SAMPLE_RATE)
        val parts = samples.flatMapIndexed { i, s -> if (i == 0) listOf(s.audio.samples) else listOf(gap, s.audio.samples) }
        val all = FloatArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { System.arraycopy(p, 0, all, pos, p.size); pos += p.size }
        val chained = BenchSample(
            "enchaînement", "", io.github.remtav.dictee.audio.PcmAudio(all, SttEngine.SAMPLE_RATE),
            samples.mapNotNull { it.reference }.joinToString(" "),
        )
        return samples + chained
    }

    /**
     * The trials of the checkpoint of issue #35: the clips chained (1 s of silence between them), repeated as
     * needed and cut at 30 s, 2 min and 4 min 50 s (just under the 5 min limit of a dictation).
     */
    fun longPlan(samples: List<BenchSample>, targets: List<Pair<String, Float>> = LONG_TARGETS): List<BenchSample> {
        if (samples.isEmpty()) return emptyList()
        val rate = SttEngine.SAMPLE_RATE
        val gap = FloatArray(rate)
        return targets.map { (name, seconds) ->
            val parts = ArrayList<FloatArray>()
            var len = 0
            var i = 0
            while (len < seconds * rate) {
                val clip = samples[i % samples.size].audio.samples
                if (len > 0) { parts += gap; len += gap.size }
                parts += clip
                len += clip.size
                i++
            }
            val all = FloatArray(minOf(len, (seconds * rate).toInt()))
            var pos = 0
            for (p in parts) {
                val n = minOf(p.size, all.size - pos)
                if (n <= 0) break
                System.arraycopy(p, 0, all, pos, n)
                pos += n
            }
            BenchSample(name, "", io.github.remtav.dictee.audio.PcmAudio(all, rate), null)
        }
    }

    /**
     * Block mode, as on the keyboard: [sample] is played (in real time when [realTime]) through a
     * [io.github.remtav.dictee.dictation.DictationController] with the given [vad]; blocks go to the server
     * while it plays, and the time from the end of the audio to the result is recorded. [cancelled] stops it.
     */
    fun runBlocks(
        sample: BenchSample, vad: io.github.remtav.dictee.audio.VoiceActivityDetector, realTime: Boolean = true,
        cancelled: () -> Boolean = { false },
    ): Trial {
        val seconds = sample.audio.samples.size / sample.audio.sampleRate.toFloat()
        val engine = blockEngineFactory(config)
        var outcome: DictationResult? = null
        var error: String? = null
        val done = java.util.concurrent.CountDownLatch(1)
        val listener = object : io.github.remtav.dictee.dictation.DictationListener {
            override fun onDictationResult(result: DictationResult, inserted: String) { outcome = result }
            override fun onState(state: io.github.remtav.dictee.dictation.DictationState, message: String?) {
                if (state == io.github.remtav.dictee.dictation.DictationState.ERROR) error = message
                if (state == io.github.remtav.dictee.dictation.DictationState.IDLE ||
                    state == io.github.remtav.dictee.dictation.DictationState.ERROR) done.countDown()
            }
        }
        val sink = io.github.remtav.dictee.dictation.StringSink()
        val controller = io.github.remtav.dictee.dictation.DictationController({ sink }, { it.run() }, listener)
        controller.start(
            io.github.remtav.dictee.dictation.DictationSession(
                ModelCatalog.remote,
                io.github.remtav.dictee.audio.PcmAudioSource(listOf(sample.audio), realTime = realTime, gapSeconds = 0f, leadSeconds = 0.5f, tailSeconds = 1f),
                vad, io.github.remtav.dictee.dictation.TextPipeline(),
                transcribe = { throw IllegalStateException("pas de transcription par segment") },
                whole = { audio, segments, control -> engine.transcribeDictation(audio, segments, control) },
                blocks = { control -> BlockDictation(engine, control) },
                canFallBack = false,
            ),
        )
        while (!done.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (cancelled()) {
                controller.cancel()
                return Trial(sample.name, seconds, error = "interrompu", reference = sample.reference, mode = MODE_BLOCKS)
            }
        }
        val r = outcome
        if (r == null) {
            return Trial(
                sample.name, seconds, error = config.redact(error ?: "aucun texte"), reference = sample.reference, mode = MODE_BLOCKS,
            )
        }
        return Trial(
            sample.name, seconds, r.uploadMillis, r.waitMillis, r.polishStatus, r.rawText, r.polishedText,
            r.transport, reference = sample.reference, mode = if (r.transport == "blocks") MODE_BLOCKS else MODE_WHOLE,
            blocks = r.blocks, waitMillis = r.waitMillis,
        )
    }

    fun run(sample: BenchSample, control: WaitControl = WaitControl()): Trial {
        val seconds = sample.audio.samples.size / sample.audio.sampleRate.toFloat()
        return try {
            val r = engineFactory(config).transcribeDictation(sample.audio.samples, listOf(sample.audio.samples), control)
            Trial(
                sample.name, seconds, r.uploadMillis, r.totalMillis, r.polishStatus, r.rawText, r.polishedText,
                r.transport, reference = sample.reference, mode = MODE_WHOLE, waitMillis = r.totalMillis,
            )
        } catch (e: Exception) {
            Trial(sample.name, seconds, error = config.redact(e.message ?: e.javaClass.simpleName), reference = sample.reference)
        }
    }

    /** Plain-text report, safe to share: the address, never a token or a Cloudflare credential. */
    fun report(trials: List<Trial>, now: Date = Date()): String = buildString {
        appendLine("Diagnostic serveur — Kalmia Blabla")
        appendLine("Date : " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(now))
        appendLine("Serveur : " + (config.normalizedBase() ?: "?") + " (" + config.api.name + ")")
        appendLine("Langue : " + config.language.ifBlank { "détection automatique" })
        appendLine("Jeton Bearer : " + if (config.apiKey.isBlank()) "non" else "oui")
        appendLine("Cloudflare Access : " + if (config.hasCfAccess) "service token configuré" else "non")
        for ((i, t) in trials.withIndex()) {
            appendLine()
            appendLine("Essai ${i + 1} — ${t.name} (%.1f s d'audio) — dictée ${t.mode}".format(Locale.ROOT, t.audioSeconds))
            if (t.error != null) {
                appendLine("  Échec : ${t.error}")
                continue
            }
            appendLine("  Attente après l'arrêt : ${t.waitMillis} ms" + if (t.mode == MODE_BLOCKS) " · blocs : ${t.blocks}" else "")
            if (t.mode == MODE_WHOLE) appendLine("  Envoi : ${t.uploadMillis} ms · total jusqu'au résultat : ${t.totalMillis} ms · via ${t.transport}")
            appendLine("  Polissage : ${t.polishStatus}")
            appendLine("  Brut : ${t.rawText}")
            appendLine("  Poli : ${t.polishedText ?: "—"}")
            if (!t.reference.isNullOrBlank()) appendLine("  Référence : ${t.reference}")
        }
    }.let { config.redact(it) }
}
