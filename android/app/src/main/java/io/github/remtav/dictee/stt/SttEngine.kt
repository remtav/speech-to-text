package io.github.remtav.dictee.stt

import java.io.File

/** Result of transcribing one segment of audio. */
data class Transcription(
    val text: String,
    /** Language reported by the model, empty when the model does not report it. */
    val language: String = "",
    val decodeMillis: Long = 0,
    val audioSeconds: Float = 0f,
) {
    val realTimeFactor: Float
        get() = if (audioSeconds > 0f) decodeMillis / 1000f / audioSeconds else 0f
}

/**
 * Speech-to-text engine: one loaded model. Implementations are not thread-safe;
 * callers serialize [transcribe] calls (the dictation worker does).
 */
interface SttEngine {
    val spec: ModelSpec

    /** Execution provider actually in use ("cpu", "xnnpack", "nnapi"). */
    val provider: String

    /** 16 kHz mono float samples in [-1, 1]. */
    fun transcribe(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): Transcription

    fun release()

    companion object {
        const val SAMPLE_RATE = 16000
    }
}

/** Options that shape how an engine is built. */
data class EngineOptions(
    val numThreads: Int = defaultThreads(),
    /** Language forced on the model ("" for automatic detection); used by Whisper only. */
    val language: String = "",
    /** Requested execution provider, see [Acceleration]. */
    val acceleration: Acceleration = Acceleration.CPU,
    /** Words to favour, for models that accept them (Qwen3-ASR, OpenAI-compatible servers). */
    val hotwords: List<String> = emptyList(),
    /** Server settings, used only by the remote model. */
    val remote: RemoteConfig? = null,
    /** Local model used when the server cannot be reached (null: no fallback). */
    val remoteFallback: ModelSpec? = null,
) {
    companion object {
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}

/**
 * Hardware acceleration choices (lot 3). CPU is the default. The others are
 * onnxruntime execution providers bundled in the sherpa-onnx AAR; when a
 * provider is missing or refuses the graph, sherpa-onnx falls back to the CPU
 * on its own, and [EngineManager] retries on the CPU if building fails.
 */
enum class Acceleration(val provider: String, val label: String) {
    CPU("cpu", "CPU (par défaut)"),
    XNNPACK("xnnpack", "XNNPACK (CPU optimisé)"),
    NNAPI("nnapi", "NNAPI (NPU / GPU via Android)");

    companion object {
        fun fromName(name: String?): Acceleration = entries.firstOrNull { it.name == name } ?: CPU
    }
}

fun interface SttEngineFactory {
    fun create(spec: ModelSpec, modelDir: File, options: EngineOptions): SttEngine
}

/**
 * The app's engine factory: sherpa-onnx for on-device models, [RemoteEngine]
 * (optionally behind a [FallbackEngine]) for the user's server.
 */
class DefaultEngineFactory(private val dirFor: (ModelSpec) -> File) : SttEngineFactory {
    override fun create(spec: ModelSpec, modelDir: File, options: EngineOptions): SttEngine {
        if (spec.family != ModelFamily.REMOTE) return SherpaEngine(spec, modelDir, options)
        val config = options.remote ?: throw IllegalStateException("Serveur distant non configuré")
        val remote = RemoteEngine(spec, config, options.hotwords)
        val fallback = options.remoteFallback?.takeIf { it.family != ModelFamily.REMOTE } ?: return remote
        val localOptions = options.copy(remote = null, remoteFallback = null)
        return FallbackEngine(remote, { SherpaEngine(fallback, dirFor(fallback), localOptions) })
    }
}
