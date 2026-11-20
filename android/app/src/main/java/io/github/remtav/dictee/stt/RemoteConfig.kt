package io.github.remtav.dictee.stt

import java.net.URI

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
    /**
     * With automatic detection ([language] blank): the user's languages, sent as `languages=fr,en` so this
     * repository's server only chooses among them (issue #86). Empty: the server chooses among all.
     */
    val detectLanguages: List<String> = emptyList(),
    /** Cloudflare Access service token: both headers are sent only when both are set. */
    val cfAccessClientId: String = "",
    val cfAccessClientSecret: String = "",
    val connectTimeoutMillis: Int = 4_000,
    val readTimeoutMillis: Int = 30_000,
    /**
     * Whole dictation: total wait = base + [waitPerAudioSecondMillis] per second of audio, capped (issue #13).
     * The keyboard sets them from the "délai avant repli" setting (issue #23, see [ServerTimeout]).
     */
    val waitBaseMillis: Long = 60_000,
    val waitCapMillis: Long = 360_000,
    val waitPerAudioSecondMillis: Long = 1_000,
    val pollIntervalMillis: Long = 400,
    /**
     * Ask the server to polish the whole dictation with its LLM (issue #15). When false the
     * client sends `polish=false` explicitly (a server started with WHISPER_POLISH=on would
     * otherwise polish anyway) and inserts the raw transcript.
     */
    val polish: Boolean = true,
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

    /** `GET /ws/stream` (issue #130), only for [RemoteApi.DICTEE_SERVER]: `http` becomes `ws`, `https` becomes `wss`. */
    fun streamUrl(): String {
        val base = normalizedBase()!!
        val ws = if (base.startsWith("https://")) "wss://" + base.removePrefix("https://") else "ws://" + base.removePrefix("http://")
        return "$ws/ws/stream"
    }

    /** `POST /polish` (issue #35, PR #46): polish of a text without audio, through the job queue. */
    fun polishUrl(): String = normalizedBase()!! + "/polish"

    fun healthUrl(): String = normalizedBase()!! + when (api) {
        RemoteApi.DICTEE_SERVER -> "/health"
        RemoteApi.OPENAI -> if (normalizedBase()!!.endsWith("/v1")) "/models" else "/v1/models"
    }

    /** Total time allowed for the result of a dictation of [audioSeconds]. */
    fun waitBudgetMillis(audioSeconds: Float): Long =
        (waitBaseMillis + (audioSeconds * waitPerAudioSecondMillis).toLong()).coerceAtMost(waitCapMillis)

    companion object {
        /**
         * Keeps only the characters an HTTP header value may hold (printable ASCII,
         * no spaces). A zero-width space pasted with the secret would otherwise make
         * the HTTP stack throw an error whose message quotes the whole value.
         */
        fun cleanHeaderValue(v: String): String = v.filter { it.code in 0x21..0x7E }
    }

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
