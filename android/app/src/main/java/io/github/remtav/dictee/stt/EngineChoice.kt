package io.github.remtav.dictee.stt

/**
 * Where dictations are transcribed (issue #23). Chosen at installation (#36) or in the settings, whose text says
 * that the audio is sent to the configured server and deleted after transcription: choosing [SERVER] is the
 * consent. Until a mode is recorded ([UNSET]), everything stays on the phone.
 */
enum class TranscriptionMode {
    UNSET, SERVER, PHONE;

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: UNSET
    }
}

/** Where the next dictation goes. */
enum class EngineRoute {
    /** The configured server, the local model as fallback. */
    SERVER,
    /** The local model (#22). */
    LOCAL,
}

/**
 * Server first (issue #23): with a server address **and** the "server" mode recorded, dictations go to the
 * server; in every other case (no address, mode "phone" or not chosen yet, no network) they stay on the phone.
 */
object EngineChoice {
    fun route(
        serverConfigured: Boolean,
        mode: TranscriptionMode,
        networkAvailable: Boolean = true,
    ): EngineRoute = when {
        !serverConfigured || mode != TranscriptionMode.SERVER -> EngineRoute.LOCAL
        // No network: the server cannot answer, don't make the user wait for a connect timeout.
        !networkAvailable -> EngineRoute.LOCAL
        else -> EngineRoute.SERVER
    }

    /**
     * The models the comparison screen runs: the on-device ones, plus the server only when dictations go to it
     * ([serverInUse]: an address **and** the "server" mode). An address alone never sends the comparison's audio.
     */
    fun comparedModels(onDevice: List<ModelSpec>, serverInUse: Boolean): List<ModelSpec> =
        if (serverInUse) onDevice + ModelCatalog.remote else onDevice

    /** The model a dictation on [route] uses: the server, or the local model. */
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

/** What the « Kalmia Pro » card and its screen can do (issue #89). */
enum class ProState {
    /** No server address (typed in « Avancé » or compiled in): the card informs, nothing can be turned on. */
    UNAVAILABLE,
    /** An address is set, dictations stay on the phone until the person agrees on the Pro screen. */
    READY,
    /** An address is set and the "server" mode is recorded: dictations go to the server. */
    ACTIVE,
}

/**
 * Kalmia Pro in the normal path (issue #89). Pure Kotlin. The rule that matters: **no audio leaves the phone
 * without the consent of issue #23**, i.e. the "server" mode recorded where the consent text is shown. A card, a
 * menu entry or a tap alone never records it: only [modeAfterConsent] does, from the Pro screen's activation
 * button, whose text sits right above it.
 */
object ProOffer {
    fun state(serverConfigured: Boolean, mode: TranscriptionMode): ProState = when {
        !serverConfigured -> ProState.UNAVAILABLE
        mode == TranscriptionMode.SERVER -> ProState.ACTIVE
        else -> ProState.READY
    }

    /**
     * The mode to record when the person asks to activate Pro: [TranscriptionMode.SERVER] only with a server **and**
     * the consent given (the button pressed under the consent text); null (change nothing) otherwise.
     */
    fun modeAfterConsent(serverConfigured: Boolean, consentGiven: Boolean): TranscriptionMode? =
        if (serverConfigured && consentGiven) TranscriptionMode.SERVER else null
}
