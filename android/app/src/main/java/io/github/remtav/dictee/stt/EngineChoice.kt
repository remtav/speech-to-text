package io.github.remtav.dictee.stt

/**
 * The user's answer to « l'audio est envoyé au serveur configuré » (issue #23). Asked once, at the first
 * dictation that would use the server; changeable in the settings.
 */
enum class ServerConsent {
    UNASKED, ACCEPTED, REFUSED;

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: UNASKED
    }
}

/** Where the next dictation goes. */
enum class EngineRoute {
    /** The configured server, the local model as fallback. */
    SERVER,
    /** The local model (#22). */
    LOCAL,
    /** A server is configured but the user has not answered yet: show the consent screen first. */
    ASK_CONSENT,
}

/**
 * Server first (issue #23): with a server address **and** the user's consent, dictations go to the server;
 * in every other case they stay on the phone. Never sends audio without a recorded "Accepter".
 */
object EngineChoice {
    fun route(
        serverConfigured: Boolean,
        consent: ServerConsent,
        alwaysLocal: Boolean,
        networkAvailable: Boolean = true,
    ): EngineRoute = when {
        !serverConfigured || alwaysLocal -> EngineRoute.LOCAL
        consent == ServerConsent.REFUSED -> EngineRoute.LOCAL
        consent == ServerConsent.UNASKED -> EngineRoute.ASK_CONSENT
        // No network: the server cannot answer, don't make the user wait for a connect timeout.
        !networkAvailable -> EngineRoute.LOCAL
        else -> EngineRoute.SERVER
    }

    /** The model a dictation on [route] uses: the server, or the local model (also for ASK_CONSENT: nothing is sent unasked). */
    fun spec(route: EngineRoute, local: ModelSpec): ModelSpec =
        if (route == EngineRoute.SERVER) ModelCatalog.remote else local
}

/**
 * How long the keyboard waits for the server before transcribing on the phone (issue #23, 8 s by default).
 * The budget runs from the end of the dictation: [seconds] plus a quarter of the audio length (upload of the
 * WAV and transcription of a long dictation), capped at [MAX_BUDGET_SECONDS].
 */
object ServerTimeout {
    const val DEFAULT_SECONDS = 8
    const val MIN_SECONDS = 2
    const val MAX_SECONDS = 60
    const val PER_AUDIO_SECOND_MILLIS = 250L
    const val MAX_BUDGET_SECONDS = 180

    fun sanitize(seconds: Int) = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    /** [config] with the fallback delay applied to its connect timeout and wait budget. */
    fun apply(config: RemoteConfig, seconds: Int): RemoteConfig {
        val millis = sanitize(seconds) * 1000L
        return config.copy(
            connectTimeoutMillis = millis.coerceAtMost(config.connectTimeoutMillis.toLong()).toInt(),
            waitBaseMillis = millis,
            waitPerAudioSecondMillis = PER_AUDIO_SECOND_MILLIS,
            waitCapMillis = maxOf(millis, MAX_BUDGET_SECONDS * 1000L),
        )
    }
}
