package io.github.remtav.dictee.stt

import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.WavReader
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.UUID

/**
 * Transcription on a server the user runs (opt-in, off by default). This is
 * the one place where audio leaves the device: only when the user configured
 * a server and picked "Serveur distant" as the model.
 *
 * Two APIs are spoken:
 * - [RemoteApi.DICTEE_SERVER]: the FastAPI/WhisperX server of this repository.
 *   A whole dictation goes through the job API (`POST /jobs` with `quick` and
 *   `polish`, then `GET /jobs/{id}` until the result), so a long request is
 *   never cut by a proxy's time limit (Cloudflare tunnel). Single segments
 *   (comparison screen) use `POST /transcribe/streaming`;
 * - [RemoteApi.OPENAI]: `POST /v1/audio/transcriptions`, spoken by most
 *   self-hosted servers (faster-whisper-server/speaches, whisper.cpp server,
 *   LocalAI, vLLM...) and by hosted APIs. No polish there.
 *
 * Audio is sent as a 16 kHz 16-bit mono WAV. Every request carries the
 * optional Bearer token and the optional Cloudflare Access service token.
 * Redirects are never followed: a 302 is Cloudflare Access sending an
 * unauthenticated client to its login page.
 */
enum class RemoteApi(val label: String) {
    DICTEE_SERVER("Serveur speech-to-text de ce dépôt (/jobs, avec polissage)"),
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
    /** Cloudflare Access service token: both headers are sent only when both are set. */
    val cfAccessClientId: String = "",
    val cfAccessClientSecret: String = "",
    val connectTimeoutMillis: Int = 4_000,
    val readTimeoutMillis: Int = 30_000,
    /** Whole dictation: total wait = base + 1 × audio length, capped (issue #13). */
    val waitBaseMillis: Long = 60_000,
    val waitCapMillis: Long = 360_000,
    val pollIntervalMillis: Long = 400,
) {
    val isConfigured: Boolean get() = normalizedBase() != null

    val hasCfAccess: Boolean get() = cfAccessClientId.isNotBlank() && cfAccessClientSecret.isNotBlank()

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

    fun jobsUrl(): String = normalizedBase()!! + "/jobs"

    fun healthUrl(): String = normalizedBase()!! + when (api) {
        RemoteApi.DICTEE_SERVER -> "/health"
        RemoteApi.OPENAI -> if (normalizedBase()!!.endsWith("/v1")) "/models" else "/v1/models"
    }

    /** Total time allowed for the result of a dictation of [audioSeconds]. */
    fun waitBudgetMillis(audioSeconds: Float): Long =
        (waitBaseMillis + (audioSeconds * 1000).toLong()).coerceAtMost(waitCapMillis)

    /** Replaces every secret of this config found in [text] (error bodies, messages) by "***". */
    fun redact(text: String): String {
        var out = text
        for (s in listOf(cfAccessClientSecret, apiKey, cfAccessClientId)) {
            val v = s.trim()
            if (v.length >= 4) out = out.replace(v, "***")
        }
        return out
    }

    /** Never prints the token or the Cloudflare credentials (configs end up in engine keys and logs). */
    override fun toString(): String =
        "RemoteConfig(baseUrl=$baseUrl, api=$api, apiKey=${if (apiKey.isBlank()) "" else "***"}, model=$model, " +
            "language=$language, cfAccess=${if (hasCfAccess) "***" else "none"})"
}

/** Why a request to the server failed; drives the connection test's message and the fallback. */
enum class RemoteFailure {
    /** HTTP 401 or 403: the server or Cloudflare Access refused the credentials. */
    ACCESS_DENIED,
    /** HTTP 3xx: Cloudflare Access sends unauthenticated clients to its login page. */
    ACCESS_REDIRECT,
    /** Connection failed (DNS, refused, network down, TLS). */
    UNREACHABLE,
    /** No result within the wait budget. */
    TIMEOUT,
    /** Any other HTTP error (5xx, 4xx). */
    HTTP_ERROR,
    /** Not the expected JSON (an HTML page from a proxy, for instance). */
    BAD_RESPONSE,
    /** The server job ended in error or was cancelled on the server. */
    JOB_FAILED,
    /** Cancelled by the user (Annuler) or because the dictation was dropped. */
    CANCELLED,
}

open class RemoteException(
    message: String,
    cause: Throwable? = null,
    val failure: RemoteFailure = RemoteFailure.HTTP_ERROR,
    val httpCode: Int = 0,
) : IOException(message, cause)

/** Lets the keyboard stop a whole-dictation request: "Annuler" (go local) or the dictation was dropped. */
class WaitControl {
    enum class Mode { NONE, TO_LOCAL, ABANDON }

    @Volatile var mode: Mode = Mode.NONE
        private set
    private val hooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    val cancelled: Boolean get() = mode != Mode.NONE

    /** User pressed "Annuler": stop waiting for the server and transcribe on the phone. */
    fun toLocal() = cancel(Mode.TO_LOCAL)

    /** The dictation is dropped (field changed): stop everything, insert nothing. */
    fun abandon() = cancel(Mode.ABANDON)

    private fun cancel(m: Mode) {
        synchronized(this) {
            if (mode != Mode.NONE) return
            mode = m
        }
        hooks.forEach { runCatching { it() } }
    }

    /** Runs [hook] at cancellation (at once if already cancelled). */
    fun onCancel(hook: () -> Unit) {
        hooks += hook
        if (cancelled) runCatching { hook() }
    }
}

/** Outcome of a whole dictation. */
data class DictationResult(
    /**
     * Texts to insert in order: one for a server result (the polished text, or the
     * raw one), one per VAD segment for the local fallback.
     */
    val texts: List<String>,
    val source: Source,
    /** Raw server transcript (empty for the local fallback). */
    val rawText: String = "",
    /** Polished text when the server polish step succeeded. */
    val polishedText: String? = null,
    /** Server polish status: "applied", "not requested", or the server's fallback reason. */
    val polishStatus: String = "",
    val language: String = "",
    /** Time to send the audio (upload + job creation). */
    val uploadMillis: Long = 0,
    /** Time from the start of the request to the result. */
    val totalMillis: Long = 0,
    val audioSeconds: Float = 0f,
    /** "jobs", "sync" or "local". */
    val transport: String = "",
    /** Why the server was not used, for the local fallback. */
    val fallbackReason: String? = null,
) {
    enum class Source { SERVER_POLISHED, SERVER_RAW, LOCAL_FALLBACK }
}

/** An engine that can transcribe a whole dictation at once (the remote server). */
interface WholeDictationEngine {
    /**
     * Transcribes the whole dictation [audio]. [segments] are the VAD's speech
     * segments of the same audio, used by a local fallback. Blocking.
     */
    fun transcribeDictation(audio: FloatArray, segments: List<FloatArray>, control: WaitControl): DictationResult
}

/** Logger for the remote path; never receives a secret (messages are redacted). */
fun interface RemoteLog {
    fun log(message: String)

    companion object {
        val NONE = RemoteLog { }
    }
}

class RemoteEngine(
    override val spec: ModelSpec,
    private val config: RemoteConfig,
    /** Words to favour: sent as the `prompt` of OpenAI-compatible requests. */
    private val hotwords: List<String> = emptyList(),
    private val log: RemoteLog = defaultLog,
) : SttEngine, WholeDictationEngine {
    override val provider: String = "remote"

    init {
        require(config.isConfigured) { "Adresse du serveur invalide" }
    }

    private fun log(msg: String) = log.log(config.redact(msg))

    // ------------------------------------------------------------- one segment

    /** One segment, synchronous (comparison screen). No polish. */
    override fun transcribe(samples: FloatArray, sampleRate: Int): Transcription {
        val seconds = samples.size / sampleRate.toFloat()
        if (samples.isEmpty()) return Transcription("", audioSeconds = 0f)
        val wav = WavReader.encode(PcmAudio(samples, sampleRate))
        val start = System.nanoTime()
        val body = post(config.transcriptionUrl(), syncFields(polish = false), wav, config.readTimeoutMillis)
        val millis = (System.nanoTime() - start) / 1_000_000
        val json = parse(body)
        return Transcription(
            text = json.optString("text", "").trim(),
            language = json.optString("language", ""),
            decodeMillis = millis,
            audioSeconds = seconds,
        )
    }

    private fun syncFields(polish: Boolean): LinkedHashMap<String, String> {
        val fields = LinkedHashMap<String, String>()
        when (config.api) {
            RemoteApi.DICTEE_SERVER -> {
                if (config.language.isNotBlank()) fields["language"] = config.language
                if (polish) fields["polish"] = "true"
            }
            RemoteApi.OPENAI -> {
                fields["model"] = config.model.ifBlank { "whisper-1" }
                fields["response_format"] = "json"
                if (config.language.isNotBlank()) fields["language"] = config.language
                if (hotwords.isNotEmpty()) fields["prompt"] = hotwords.take(50).joinToString(", ")
            }
        }
        return fields
    }

    // --------------------------------------------------------- whole dictation

    /**
     * The whole dictation in one request: the job API with polish for this
     * repository's server (synchronous `/transcribe/streaming` if the server has
     * no job API), one `/v1/audio/transcriptions` call for OpenAI-compatible
     * servers. Throws [RemoteException] on any failure, including a timeout and
     * a cancellation; never falls back by itself (see [FallbackEngine]).
     */
    override fun transcribeDictation(audio: FloatArray, segments: List<FloatArray>, control: WaitControl): DictationResult {
        val seconds = audio.size / SttEngine.SAMPLE_RATE.toFloat()
        if (audio.isEmpty()) return DictationResult(emptyList(), DictationResult.Source.SERVER_RAW, transport = "none")
        val deadline = System.nanoTime() / 1_000_000 + config.waitBudgetMillis(seconds)
        val wav = WavReader.encode(PcmAudio(audio, SttEngine.SAMPLE_RATE))
        log("dictation: %.1f s, %d bytes, api %s".format(java.util.Locale.ROOT, seconds, wav.size, config.api))
        val start = System.nanoTime()
        return when (config.api) {
            RemoteApi.OPENAI -> {
                val body = postCancellable(config.transcriptionUrl(), syncFields(polish = false), wav, deadline, control)
                val upload = elapsed(start)
                val json = parse(body)
                val text = json.optString("text", "").trim()
                DictationResult(
                    texts = listOf(text), source = DictationResult.Source.SERVER_RAW, rawText = text,
                    polishStatus = "not requested", language = json.optString("language", ""),
                    uploadMillis = upload, totalMillis = elapsed(start), audioSeconds = seconds, transport = "sync",
                )
            }
            RemoteApi.DICTEE_SERVER -> {
                val fields = LinkedHashMap<String, String>().apply {
                    put("quick", "true")
                    put("polish", "true")
                    if (config.language.isNotBlank()) put("language", config.language)
                }
                val created = try {
                    postCancellable(config.jobsUrl(), fields, wav, deadline, control)
                } catch (e: RemoteException) {
                    if (e.httpCode != 404 && e.httpCode != 405) throw e
                    // A server without the job API: the synchronous route, same options.
                    log("job API unavailable (HTTP ${e.httpCode}): synchronous request")
                    val body = postCancellable(config.transcriptionUrl(), syncFields(polish = true), wav, deadline, control)
                    val upload = elapsed(start)
                    return serverResult(parse(body), upload, elapsed(start), seconds, "sync")
                }
                val upload = elapsed(start)
                val id = parse(created).optString("id", "")
                if (id.isEmpty()) throw RemoteException("Réponse du serveur sans identifiant de tâche", failure = RemoteFailure.BAD_RESPONSE)
                log("job $id created in $upload ms")
                val cancelHook = { cancelJobAsync(id) }
                control.onCancel(cancelHook)
                val result = pollJob(id, deadline, control)
                serverResult(result, upload, elapsed(start), seconds, "jobs")
            }
        }
    }

    private fun serverResult(json: JSONObject, upload: Long, total: Long, seconds: Float, transport: String): DictationResult {
        val raw = json.optString("text", "").trim()
        val polishedRaw = if (json.isNull("polished_text")) "" else json.optString("polished_text", "").trim()
        val polish = json.optJSONObject("polish")
        val applied = polish?.optBoolean("applied", false) == true
        val polished = polishedRaw.takeIf { applied && it.isNotEmpty() }
        val status = when {
            polished != null -> "applied"
            polish == null -> "not returned"
            else -> polish.optString("fallback_reason", "").takeIf { it.isNotEmpty() && it != "null" } ?: "failed"
        }
        log("result: polish $status, ${raw.length} chars raw, ${polished?.length ?: 0} chars polished, $total ms")
        return DictationResult(
            texts = listOf(polished ?: raw),
            source = if (polished != null) DictationResult.Source.SERVER_POLISHED else DictationResult.Source.SERVER_RAW,
            rawText = raw, polishedText = polished, polishStatus = status,
            language = json.optString("language", ""),
            uploadMillis = upload, totalMillis = total, audioSeconds = seconds, transport = transport,
        )
    }

    /** Polls `GET /jobs/{id}` until the job is done; returns its result. */
    private fun pollJob(id: String, deadline: Long, control: WaitControl): JSONObject {
        val url = config.jobsUrl() + "/" + id
        while (true) {
            checkCancelled(control)
            val remaining = deadline - now()
            if (remaining <= 0) {
                cancelJobAsync(id)
                throw RemoteException("Délai dépassé en attendant le serveur", failure = RemoteFailure.TIMEOUT)
            }
            val body = request("GET", url, null, null, readTimeout = remaining.coerceIn(1, 15_000).toInt())
            val job = parse(body)
            when (val status = job.optString("status")) {
                "done" -> return job.optJSONObject("result")
                    ?: throw RemoteException("Tâche terminée sans résultat", failure = RemoteFailure.BAD_RESPONSE)
                "error" -> {
                    val msg = job.optJSONObject("error")?.optString("message").orEmpty()
                    throw RemoteException("Le serveur a échoué : ${msg.take(160)}".trim(), failure = RemoteFailure.JOB_FAILED)
                }
                "cancelled" -> throw RemoteException("Tâche annulée sur le serveur", failure = RemoteFailure.JOB_FAILED)
                "queued", "running" -> {}
                else -> throw RemoteException("Statut de tâche inconnu : ${status.take(40)}", failure = RemoteFailure.BAD_RESPONSE)
            }
            try {
                Thread.sleep(config.pollIntervalMillis.coerceAtMost((deadline - now()).coerceAtLeast(1)))
            } catch (e: InterruptedException) {
                cancelJobAsync(id)
                throw e
            }
        }
    }

    private fun checkCancelled(control: WaitControl) {
        if (control.cancelled) throw RemoteException("Attente du serveur annulée", failure = RemoteFailure.CANCELLED)
    }

    /** Best effort, on its own thread: the caller may be interrupted or about to fall back. */
    private fun cancelJobAsync(id: String) {
        Thread({
            try {
                request("POST", config.jobsUrl() + "/" + id + "/cancel", null, null, readTimeout = 5_000)
                log("job $id cancelled")
            } catch (e: Exception) {
                log("job $id: cancel failed (${e.message})")
            }
        }, "remote-cancel").apply { isDaemon = true }.start()
    }

    /**
     * A POST that a cancellation interrupts: it runs on a helper thread and the
     * caller gives up as soon as [control] is cancelled or the deadline passes.
     */
    private fun postCancellable(url: String, fields: Map<String, String>, wav: ByteArray, deadline: Long, control: WaitControl): String {
        checkCancelled(control)
        val remaining = deadline - now()
        if (remaining <= 0) throw RemoteException("Délai dépassé en attendant le serveur", failure = RemoteFailure.TIMEOUT)
        val task = java.util.concurrent.FutureTask { post(url, fields, wav, remaining.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()) }
        val worker = Thread(task, "remote-post").apply { isDaemon = true }
        val cancelHook = { task.cancel(true); Unit }
        control.onCancel(cancelHook)
        worker.start()
        try {
            return task.get(remaining + 1_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.CancellationException) {
            throw RemoteException("Attente du serveur annulée", failure = RemoteFailure.CANCELLED)
        } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause
            if (control.cancelled) throw RemoteException("Attente du serveur annulée", failure = RemoteFailure.CANCELLED)
            throw c as? RemoteException ?: RemoteException(c?.message ?: "Erreur", c)
        } catch (_: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            throw RemoteException("Délai dépassé en attendant le serveur", failure = RemoteFailure.TIMEOUT)
        } catch (e: InterruptedException) {
            task.cancel(true)
            throw e
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun post(url: String, fields: Map<String, String>, wav: ByteArray, readTimeout: Int): String {
        val boundary = "dictee-" + UUID.randomUUID()
        val payload = ByteArrayOutputStream().apply {
            for ((k, v) in fields) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
            }
            write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"dictation.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n").toByteArray(),
            )
            write(wav)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        return request("POST", url, "multipart/form-data; boundary=$boundary", payload, readTimeout)
    }

    private fun request(method: String, url: String, contentType: String?, payload: ByteArray?, readTimeout: Int): String {
        val conn = open(config, url, readTimeout).apply {
            requestMethod = method
            if (payload != null) {
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Content-Type", contentType)
            }
        }
        try {
            if (payload != null) conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            if (code !in 200..299) throw httpError(config, conn, code)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: RemoteException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw RemoteException("Délai dépassé en attendant le serveur", e, RemoteFailure.TIMEOUT)
        } catch (e: IOException) {
            throw RemoteException(config.redact("Serveur injoignable : ${e.message ?: e.javaClass.simpleName}"), e, RemoteFailure.UNREACHABLE)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: Exception) {
        throw RemoteException("Réponse du serveur illisible (pas du JSON)", e, RemoteFailure.BAD_RESPONSE)
    }

    private fun now() = System.nanoTime() / 1_000_000
    private fun elapsed(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    override fun release() {}

    companion object {
        /** Set by the app to Android's log; tests pass their own. */
        @Volatile var defaultLog: RemoteLog = RemoteLog.NONE

        /** A connection with the timeouts, no redirect following, and every configured credential. */
        internal fun open(config: RemoteConfig, url: String, readTimeout: Int): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = config.connectTimeoutMillis
                this.readTimeout = readTimeout
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                if (config.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
                if (config.hasCfAccess) {
                    setRequestProperty("CF-Access-Client-Id", config.cfAccessClientId.trim())
                    setRequestProperty("CF-Access-Client-Secret", config.cfAccessClientSecret.trim())
                }
            }

        internal fun httpError(config: RemoteConfig, conn: HttpURLConnection, code: Int): RemoteException = when (code) {
            in 300..399 -> RemoteException(
                "Redirection (HTTP $code) vers la page de connexion de Cloudflare Access : " +
                    "vérifiez le service token et que la politique de l'application est « Service Auth »",
                failure = RemoteFailure.ACCESS_REDIRECT, httpCode = code,
            )
            401, 403 -> RemoteException(
                "Accès refusé (HTTP $code) : vérifiez le jeton et le service token Cloudflare Access",
                failure = RemoteFailure.ACCESS_DENIED, httpCode = code,
            )
            else -> {
                val detail = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200).orEmpty()
                } catch (_: IOException) { "" }
                RemoteException(
                    config.redact("Serveur : HTTP $code ${detail.replace('\n', ' ')}").trim(),
                    failure = RemoteFailure.HTTP_ERROR, httpCode = code,
                )
            }
        }

        /** Checks that the server answers; returns a short human-readable status. Blocking. */
        fun check(config: RemoteConfig): Result<String> = runCatching {
            if (!config.isConfigured) throw RemoteException("Adresse du serveur invalide", failure = RemoteFailure.BAD_RESPONSE)
            val conn = open(config, config.healthUrl(), 10_000)
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw httpError(config, conn, code)
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val access = if (config.hasCfAccess) " · Cloudflare Access accepté" else ""
                when (config.api) {
                    RemoteApi.DICTEE_SERVER -> {
                        val j = try {
                            JSONObject(body)
                        } catch (e: Exception) {
                            throw RemoteException("Réponse illisible (pas du JSON) : est-ce bien le serveur speech-to-text ?", e, RemoteFailure.BAD_RESPONSE)
                        }
                        listOfNotNull(
                            j.optString("model").takeIf { it.isNotEmpty() }?.let { "modèle $it" },
                            j.optString("device").takeIf { it.isNotEmpty() },
                            if (j.has("polish_available")) {
                                if (j.optBoolean("polish_available")) "polissage disponible" else "polissage indisponible"
                            } else null,
                        ).joinToString(" · ").ifEmpty { "serveur joignable" } + access
                    }
                    RemoteApi.OPENAI -> "serveur joignable$access"
                }
            } catch (e: RemoteException) {
                throw e
            } catch (e: IOException) {
                throw RemoteException(config.redact("injoignable : ${e.message ?: e.javaClass.simpleName}"), e, RemoteFailure.UNREACHABLE)
            } finally {
                conn.disconnect()
            }
        }
    }
}

/**
 * Remote first, local model when the server cannot be reached. After a
 * failure the server is skipped for [retryAfterMillis], so a dead server
 * does not cost a connect timeout on every dictation. The local model is
 * loaded only on the first failure.
 */
class FallbackEngine(
    private val remote: SttEngine,
    private val localFactory: () -> SttEngine,
    private val retryAfterMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : SttEngine, WholeDictationEngine {
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
        return localEngine().transcribe(samples, sampleRate)
    }

    private fun localEngine(): SttEngine {
        val engine = local ?: localFactory().also { local = it }
        lastUsedLocal = true
        return engine
    }

    /**
     * The whole dictation on the server; on any failure (or "Annuler"), the
     * kept audio is transcribed on the phone, segment by segment, so nothing
     * is lost. A dropped dictation ([WaitControl.abandon]) transcribes nothing.
     */
    @Synchronized
    override fun transcribeDictation(audio: FloatArray, segments: List<FloatArray>, control: WaitControl): DictationResult {
        var reason: String? = null
        val whole = remote as? WholeDictationEngine
        if (whole == null) {
            reason = "pas de mode dictée entière"
        } else if (clock() < skipUntil) {
            reason = "serveur en échec il y a moins de ${retryAfterMillis / 1000} s"
        } else {
            try {
                return whole.transcribeDictation(audio, segments, control).also { lastUsedLocal = false }
            } catch (e: RemoteException) {
                // A user cancel says nothing about the server: no retry delay for it.
                if (e.failure != RemoteFailure.CANCELLED) skipUntil = clock() + retryAfterMillis
                reason = e.message
            }
        }
        if (control.mode == WaitControl.Mode.ABANDON) {
            return DictationResult(emptyList(), DictationResult.Source.LOCAL_FALLBACK, transport = "none", fallbackReason = reason)
        }
        val start = System.nanoTime()
        val engine = localEngine()
        val texts = ArrayList<String>()
        for (seg in segments) {
            if (control.mode == WaitControl.Mode.ABANDON || Thread.currentThread().isInterrupted) break
            texts += engine.transcribe(seg, SttEngine.SAMPLE_RATE).text
        }
        return DictationResult(
            texts = texts, source = DictationResult.Source.LOCAL_FALLBACK,
            totalMillis = (System.nanoTime() - start) / 1_000_000,
            audioSeconds = audio.size / SttEngine.SAMPLE_RATE.toFloat(), transport = "local",
            fallbackReason = reason,
        )
    }

    @Synchronized
    override fun release() {
        remote.release()
        local?.release()
        local = null
    }
}
