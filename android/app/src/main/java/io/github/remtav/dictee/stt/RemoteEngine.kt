package io.github.remtav.dictee.stt

import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.WavReader
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/**
 * Transcription on a server the user runs (opt-in, off by default). This is
 * the one place where audio leaves the device: only when the user configured
 * a server and picked "Serveur distant" as the model.
 *
 * Two APIs are spoken:
 * - [RemoteApi.DICTEE_SERVER]: the FastAPI/WhisperX server of this repository,
 *   `POST /transcribe/streaming` (fast path: no alignment, no diarization);
 * - [RemoteApi.OPENAI]: `POST /v1/audio/transcriptions`, spoken by most
 *   self-hosted servers (faster-whisper-server/speaches, whisper.cpp server,
 *   LocalAI, vLLM...) and by hosted APIs.
 *
 * Each VAD segment is sent as a 16 kHz 16-bit mono WAV.
 */
enum class RemoteApi(val label: String) {
    DICTEE_SERVER("Serveur speech-to-text de ce dépôt (/transcribe/streaming)"),
    OPENAI("Compatible OpenAI (/v1/audio/transcriptions)");

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: DICTEE_SERVER
    }
}

data class RemoteConfig(
    val baseUrl: String,
    val api: RemoteApi = RemoteApi.DICTEE_SERVER,
    /** Sent as `Authorization: Bearer ...` when not blank (reverse proxy, hosted API). */
    val apiKey: String = "",
    /** Model name for OpenAI-compatible servers (e.g. "whisper-1", "Systran/faster-whisper-large-v3"). */
    val model: String = "",
    /** "fr", "en", or blank for automatic detection. */
    val language: String = "",
    val connectTimeoutMillis: Int = 4_000,
    val readTimeoutMillis: Int = 30_000,
) {
    val isConfigured: Boolean get() = normalizedBase() != null

    fun normalizedBase(): String? {
        val u = baseUrl.trim().trimEnd('/')
        if (u.isEmpty()) return null
        return try {
            val uri = URI(u)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty()) null else u
        } catch (_: Exception) {
            null
        }
    }

    /** True for plain HTTP to a host that is not on the local network: audio would cross the internet unencrypted. */
    val isInsecurePublic: Boolean get() {
        val base = normalizedBase() ?: return false
        val uri = URI(base)
        if (uri.scheme == "https") return false
        val h = uri.host.lowercase()
        val private = h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") ||
            h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("127.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h) || Regex("^100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.").containsMatchIn(h) ||
            !h.contains('.') // single-label LAN name
        return !private
    }

    fun transcriptionUrl(): String = normalizedBase()!! + when (api) {
        RemoteApi.DICTEE_SERVER -> "/transcribe/streaming"
        RemoteApi.OPENAI -> if (normalizedBase()!!.endsWith("/v1")) "/audio/transcriptions" else "/v1/audio/transcriptions"
    }

    fun healthUrl(): String = normalizedBase()!! + when (api) {
        RemoteApi.DICTEE_SERVER -> "/health"
        RemoteApi.OPENAI -> if (normalizedBase()!!.endsWith("/v1")) "/models" else "/v1/models"
    }
}

class RemoteException(message: String, cause: Throwable? = null) : IOException(message, cause)

class RemoteEngine(
    override val spec: ModelSpec,
    private val config: RemoteConfig,
    /** Words to favour: sent as the `prompt` of OpenAI-compatible requests. */
    private val hotwords: List<String> = emptyList(),
) : SttEngine {
    override val provider: String = "remote"

    init {
        require(config.isConfigured) { "Adresse du serveur invalide" }
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): Transcription {
        val seconds = samples.size / sampleRate.toFloat()
        if (samples.isEmpty()) return Transcription("", audioSeconds = 0f)
        val wav = WavReader.encode(PcmAudio(samples, sampleRate))
        val fields = LinkedHashMap<String, String>()
        when (config.api) {
            RemoteApi.DICTEE_SERVER -> {
                if (config.language.isNotBlank()) fields["language"] = config.language
            }
            RemoteApi.OPENAI -> {
                fields["model"] = config.model.ifBlank { "whisper-1" }
                fields["response_format"] = "json"
                if (config.language.isNotBlank()) fields["language"] = config.language
                if (hotwords.isNotEmpty()) fields["prompt"] = hotwords.take(50).joinToString(", ")
            }
        }
        val start = System.nanoTime()
        val body = post(config.transcriptionUrl(), fields, wav)
        val millis = (System.nanoTime() - start) / 1_000_000
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw RemoteException("Réponse du serveur illisible", e)
        }
        return Transcription(
            text = json.optString("text", "").trim(),
            language = json.optString("language", ""),
            decodeMillis = millis,
            audioSeconds = seconds,
        )
    }

    private fun post(url: String, fields: Map<String, String>, wav: ByteArray): String {
        val boundary = "dictee-" + UUID.randomUUID()
        val payload = ByteArrayOutputStream().apply {
            for ((k, v) in fields) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
            }
            write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"segment.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n").toByteArray(),
            )
            write(wav)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = config.connectTimeoutMillis
            readTimeout = config.readTimeoutMillis
            setFixedLengthStreamingMode(payload.size)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            if (config.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
        }
        try {
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200).orEmpty()
                throw RemoteException("Serveur : HTTP $code ${detail.replace('\n', ' ')}".trim())
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: RemoteException) {
            throw e
        } catch (e: IOException) {
            throw RemoteException("Serveur injoignable : ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            conn.disconnect()
        }
    }

    override fun release() {}

    companion object {
        /** Checks that the server answers; returns a short human-readable status. Blocking. */
        fun check(config: RemoteConfig): Result<String> = runCatching {
            if (!config.isConfigured) throw RemoteException("Adresse du serveur invalide")
            val conn = (URL(config.healthUrl()).openConnection() as HttpURLConnection).apply {
                connectTimeout = config.connectTimeoutMillis
                readTimeout = 10_000
                if (config.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw RemoteException("HTTP $code")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                when (config.api) {
                    RemoteApi.DICTEE_SERVER -> {
                        val j = JSONObject(body)
                        listOfNotNull(
                            j.optString("model").takeIf { it.isNotEmpty() }?.let { "modèle $it" },
                            j.optString("device").takeIf { it.isNotEmpty() },
                        ).joinToString(" · ").ifEmpty { "serveur joignable" }
                    }
                    RemoteApi.OPENAI -> "serveur joignable"
                }
            } catch (e: RemoteException) {
                throw e
            } catch (e: IOException) {
                throw RemoteException("injoignable : ${e.message ?: e.javaClass.simpleName}", e)
            } finally {
                conn.disconnect()
            }
        }
    }
}

/**
 * Remote first, local model when the server cannot be reached. After a
 * failure the server is skipped for [retryAfterMillis], so a dead server
 * does not cost a connect timeout on every segment. The local model is
 * loaded only on the first failure.
 */
class FallbackEngine(
    private val remote: SttEngine,
    private val localFactory: () -> SttEngine,
    private val retryAfterMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : SttEngine {
    override val spec: ModelSpec get() = remote.spec
    @Volatile private var local: SttEngine? = null
    @Volatile private var skipUntil = 0L
    @Volatile var lastUsedLocal = false
        private set

    override val provider: String get() = if (lastUsedLocal) "remote→${local?.provider ?: "local"}" else remote.provider

    @Synchronized
    override fun transcribe(samples: FloatArray, sampleRate: Int): Transcription {
        if (clock() >= skipUntil) {
            try {
                return remote.transcribe(samples, sampleRate).also { lastUsedLocal = false }
            } catch (e: RemoteException) {
                skipUntil = clock() + retryAfterMillis
            }
        }
        val engine = local ?: localFactory().also { local = it }
        lastUsedLocal = true
        return engine.transcribe(samples, sampleRate)
    }

    @Synchronized
    override fun release() {
        remote.release()
        local?.release()
        local = null
    }
}
