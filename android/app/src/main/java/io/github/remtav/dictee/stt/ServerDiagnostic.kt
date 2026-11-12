package io.github.remtav.dictee.stt

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The checkpoint tool (issue #13): sends reference clips to the server as real
 * whole dictations (same request as the keyboard: job API, quick path, polish)
 * and records, per trial, the upload time, the total time to the result, the
 * polish status and the raw and polished texts. No local fallback here: a
 * failure is reported as such.
 */
class ServerDiagnostic(
    private val config: RemoteConfig,
    private val engineFactory: (RemoteConfig) -> WholeDictationEngine = { RemoteEngine(ModelCatalog.remote, it) },
) {
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

    fun run(sample: BenchSample, control: WaitControl = WaitControl()): Trial {
        val seconds = sample.audio.samples.size / sample.audio.sampleRate.toFloat()
        return try {
            val r = engineFactory(config).transcribeDictation(sample.audio.samples, listOf(sample.audio.samples), control)
            Trial(
                sample.name, seconds, r.uploadMillis, r.totalMillis, r.polishStatus, r.rawText, r.polishedText,
                r.transport, reference = sample.reference,
            )
        } catch (e: Exception) {
            Trial(sample.name, seconds, error = config.redact(e.message ?: e.javaClass.simpleName), reference = sample.reference)
        }
    }

    /** Plain-text report, safe to share: the address, never a token or a Cloudflare credential. */
    fun report(trials: List<Trial>, now: Date = Date()): String = buildString {
        appendLine("Diagnostic serveur — Dictée locale")
        appendLine("Date : " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(now))
        appendLine("Serveur : " + (config.normalizedBase() ?: "?") + " (" + config.api.name + ")")
        appendLine("Langue : " + config.language.ifBlank { "détection automatique" })
        appendLine("Jeton Bearer : " + if (config.apiKey.isBlank()) "non" else "oui")
        appendLine("Cloudflare Access : " + if (config.hasCfAccess) "service token configuré" else "non")
        for ((i, t) in trials.withIndex()) {
            appendLine()
            appendLine("Essai ${i + 1} — ${t.name} (%.1f s d'audio)".format(Locale.ROOT, t.audioSeconds))
            if (t.error != null) {
                appendLine("  Échec : ${t.error}")
                continue
            }
            appendLine("  Envoi : ${t.uploadMillis} ms · total jusqu'au résultat : ${t.totalMillis} ms · via ${t.transport}")
            appendLine("  Polissage : ${t.polishStatus}")
            appendLine("  Brut : ${t.rawText}")
            appendLine("  Poli : ${t.polishedText ?: "—"}")
            if (!t.reference.isNullOrBlank()) appendLine("  Référence : ${t.reference}")
        }
    }.let { config.redact(it) }
}
