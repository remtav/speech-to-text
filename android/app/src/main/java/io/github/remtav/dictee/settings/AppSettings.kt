package io.github.remtav.dictee.settings

import android.content.Context
import android.content.SharedPreferences
import io.github.remtav.dictee.ime.LayoutDefault
import io.github.remtav.dictee.ime.LayoutKind
import io.github.remtav.dictee.reading.Declaration
import io.github.remtav.dictee.reading.Participation
import io.github.remtav.dictee.stt.Acceleration
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.DictationLanguages
import io.github.remtav.dictee.stt.EngineChoice
import io.github.remtav.dictee.stt.EngineRoute
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.ModelMigration
import io.github.remtav.dictee.stt.RemoteApi
import io.github.remtav.dictee.stt.RemoteConfig
import io.github.remtav.dictee.stt.RepetitionFix
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.TranscriptionMode
import io.github.remtav.dictee.stt.ServerTimeout
import io.github.remtav.dictee.stt.WhisperTuning
import io.github.remtav.dictee.text.Vocabulary

enum class CleanupMode(val label: String) {
    OFF("Désactivé"),
    RULES("Règles simples (hésitations)"),
    LLM("Petit LLM local + garde-fou");

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: OFF
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("Selon le système"), LIGHT("Clair"), DARK("Sombre");

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: SYSTEM
    }
}

/**
 * User preferences. Everything has a safe default: the CPU path, no optional feature.
 * [defaultServerUrl] is the build's server address (issue #23, `-PdefaultServerUrl`), empty by default.
 */
class AppSettings(context: Context, private val defaultServerUrl: String = io.github.remtav.dictee.BuildConfig.DEFAULT_SERVER_URL) {
    val prefs: SharedPreferences = context.getSharedPreferences("dictee", Context.MODE_PRIVATE)

    init {
        // Issue #42: the layout default now follows the languages, for new installs only (see [LayoutDefault]).
        if (LayoutDefault.CHECKED_KEY !in prefs.all) {
            prefs.edit().apply {
                LayoutDefault.pinnedFor(prefs.all)?.let { putString(LayoutDefault.LAYOUT_KEY, it.name) }
                putBoolean(LayoutDefault.CHECKED_KEY, true)
            }.apply()
        }
    }

    /**
     * The on-device model (#22): used when the server is not (see [route]), and as the server's fallback.
     * Never the server itself since issue #23 (an old "remote-server" value is migrated by [DicteeApp]).
     */
    var activeModelId: String
        get() = prefs.getString("active_model", ModelCatalog.default.id) ?: ModelCatalog.default.id
        set(v) = prefs.edit().putString("active_model", v).apply()

    /**
     * Languages the user dictates (never empty: an empty or missing choice reads
     * as [DictationLanguage.default], and the screens refuse to uncheck the last one).
     */
    var languages: Set<DictationLanguage>
        get() = DictationLanguages.parse(prefs.getStringSet("languages", null))
        set(v) = prefs.edit().putStringSet("languages", DictationLanguages.serialize(v)).apply()

    /** « Parlez-vous un français québécois ? » (issue #15, lot 4): self-declared, kept on the phone, editable in the settings. */
    var quebecDeclaration: Declaration
        get() = Declaration.fromName(prefs.getString("qc_declaration", null))
        set(v) = prefs.edit().putString("qc_declaration", v.name).apply()

    /** The participation card was closed on the main screen (it stays in the settings). */
    var participationCardDismissed: Boolean
        get() = prefs.getBoolean("participation_card_dismissed", false)
        set(v) = prefs.edit().putBoolean("participation_card_dismissed", v).apply()

    /** The three inputs of the reading-test offer, as the value the screens and the tests read. */
    val participation: Participation
        get() = Participation(languages, quebecDeclaration, participationCardDismissed)

    val activeModel: ModelSpec get() = ModelCatalog.byId(activeModelId)?.takeIf { it.family != ModelFamily.REMOTE } ?: ModelCatalog.default

    /**
     * Id of a model the app moved the user off (issue #22, see [io.github.remtav.dictee.stt.ModelMigration]),
     * whose notice is still to be read on the main screen; "" for none.
     */
    var retiredModelNotice: String
        get() = prefs.getString("retired_model_notice", "") ?: ""
        set(v) = prefs.edit().putString("retired_model_notice", v).apply()

    var acceleration: Acceleration
        get() = Acceleration.fromName(prefs.getString("acceleration", null))
        set(v) = prefs.edit().putString("acceleration", v.name).apply()

    var threads: Int
        get() = prefs.getInt("threads", EngineOptions.defaultThreads())
        set(v) = prefs.edit().putInt("threads", v.coerceIn(1, 8)).apply()

    var partials: Boolean
        get() = prefs.getBoolean("partials", true)
        set(v) = prefs.edit().putBoolean("partials", v).apply()

    /** Silence added to each Whisper segment, see [WhisperTuning]; only the allowed values, default 1000. */
    var whisperTailPaddings: Int
        get() = WhisperTuning.sanitizeTailPaddings(prefs.getInt("whisper_tail_paddings", WhisperTuning.DEFAULT_TAIL_PADDINGS))
        set(v) = prefs.edit().putInt("whisper_tail_paddings", WhisperTuning.sanitizeTailPaddings(v)).apply()

    /**
     * « Automatique » choice of « Silence ajouté pour Whisper » (issue #49): padding 300 + repetition filter.
     * Its own preference, off by default, so a stored 1 000, 600 or 300 ([whisperTailPaddings]) is kept as it is
     * and comes back if the user leaves « Automatique ».
     */
    var whisperTailAuto: Boolean
        get() = prefs.getBoolean("whisper_tail_auto", false)
        set(v) = prefs.edit().putBoolean("whisper_tail_auto", v).apply()

    /** « Correction des répétitions (Whisper) »; used only with [whisperTailAuto]. */
    var whisperRepetitionFix: RepetitionFix
        get() = RepetitionFix.fromName(prefs.getString("whisper_repetition_fix", null))
        set(v) = prefs.edit().putString("whisper_repetition_fix", v.name).apply()

    /** The "Comment dicter" screen has been opened (by itself at the end of the first setup, or by the user): it opens by itself only once. */
    var howToShown: Boolean
        get() = prefs.getBoolean("howto_shown", false)
        set(v) = prefs.edit().putBoolean("howto_shown", v).apply()

    /** The installation screen has been left once (issue #55): coming back, it starts on a missing keyboard step. */
    var setupSeen: Boolean
        get() = prefs.getBoolean("setup_seen", false)
        set(v) = prefs.edit().putBoolean("setup_seen", v).apply()

    /** Kalmia Blabla has been seen as the current keyboard once (issue #55: step 2 stays done afterwards). */
    var keyboardChosenOnce: Boolean
        get() = prefs.getBoolean("keyboard_chosen_once", false)
        set(v) = prefs.edit().putBoolean("keyboard_chosen_once", v).apply()

    var voiceCommands: Boolean
        get() = prefs.getBoolean("voice_commands", true)
        set(v) = prefs.edit().putBoolean("voice_commands", v).apply()

    var vocabularyText: String
        get() = prefs.getString("vocabulary", "") ?: ""
        set(v) = prefs.edit().putString("vocabulary", v).apply()

    val vocabulary: Vocabulary get() = Vocabulary.parse(vocabularyText)

    var cleanupMode: CleanupMode
        get() = CleanupMode.fromName(prefs.getString("cleanup", null))
        set(v) = prefs.edit().putString("cleanup", v.name).apply()

    var llmModelId: String
        get() = prefs.getString("llm_model", "") ?: ""
        set(v) = prefs.edit().putString("llm_model", v).apply()

    var theme: ThemeMode
        get() = ThemeMode.fromName(prefs.getString("theme", null))
        set(v) = prefs.edit().putString("theme", v.name).apply()

    /**
     * Letter arrangement of the text keyboard (issue #21): the user's pick, else deduced from the languages
     * chosen at setup (issue #54): AZERTY with "Français (France)", QWERTY otherwise.
     */
    var keyboardLayout: LayoutKind
        get() = prefs.getString("keyboard_layout", null)?.let { LayoutKind.fromName(it) }
            ?: LayoutKind.defaultFor(languages)
        set(v) = prefs.edit().putString("keyboard_layout", v.name).apply()

    /** The keyboard shows the text keys rather than the microphone (last view used, kept between fields). */
    var textKeyboardShown: Boolean
        get() = prefs.getBoolean("text_keyboard_shown", false)
        set(v) = prefs.edit().putBoolean("text_keyboard_shown", v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()

    /** Pause (seconds) that ends a segment. */
    var pauseSeconds: Float
        get() = prefs.getFloat("pause_seconds", 0.5f)
        set(v) = prefs.edit().putFloat("pause_seconds", v.coerceIn(0.25f, 2f)).apply()

    /** Minutes the model stays loaded once the keyboard is hidden. */
    var keepLoadedMinutes: Int
        get() = prefs.getInt("keep_loaded_minutes", 5)
        set(v) = prefs.edit().putInt("keep_loaded_minutes", v.coerceIn(0, 60)).apply()

    // ---- Remote server (issue #23: the default once an address is set and the user agreed) ----
    /** The user's address, or the build's default one until the user sets theirs (even to ""). */
    var remoteUrl: String
        get() = prefs.getString("remote_url", null) ?: defaultServerUrl
        set(v) = prefs.edit().putString("remote_url", v.trim()).apply()

    /**
     * Server or phone (issue #23), chosen only in the advanced settings (issue #57); recording [TranscriptionMode.SERVER]
     * is the consent to send the audio. [TranscriptionMode.PHONE] is « Toujours transcrire sur le téléphone ».
     * Not chosen yet: everything stays on the phone.
     */
    var transcriptionMode: TranscriptionMode
        get() = TranscriptionMode.fromName(prefs.getString("transcription_mode", null))
        set(v) = prefs.edit().putString("transcription_mode", v.name).apply()

    /** Seconds the keyboard waits for the server before transcribing on the phone (advanced setting, 8 by default). */
    var serverTimeoutSeconds: Int
        get() = ServerTimeout.sanitize(prefs.getInt("server_timeout", ServerTimeout.DEFAULT_SECONDS))
        set(v) = prefs.edit().putInt("server_timeout", ServerTimeout.sanitize(v)).apply()

    /** Where the next dictation goes: server or phone (see [EngineChoice]). */
    fun route(networkAvailable: Boolean = true): EngineRoute =
        EngineChoice.route(remoteConfig().isConfigured, transcriptionMode, networkAvailable)

    /**
     * Issue #57: dictations go to a server the person configured in the advanced settings (an address **and**
     * the "server" mode), whatever the network. The screens then say so instead of « sent to no one ».
     */
    val serverInUse: Boolean get() = route(networkAvailable = true) == EngineRoute.SERVER

    var remoteApi: RemoteApi
        get() = RemoteApi.fromName(prefs.getString("remote_api", null))
        set(v) = prefs.edit().putString("remote_api", v.name).apply()

    var remoteApiKey: String
        get() = prefs.getString("remote_key", "") ?: ""
        set(v) = prefs.edit().putString("remote_key", v.trim()).apply()

    var remoteModel: String
        get() = prefs.getString("remote_model", "") ?: ""
        set(v) = prefs.edit().putString("remote_model", v.trim()).apply()

    /**
     * "", "fr" or "en". Until the user sets it explicitly (even to "" for automatic
     * detection) it follows the chosen languages; an explicit value is never overwritten.
     */
    var remoteLanguage: String
        get() = prefs.getString("remote_language", null) ?: DictationLanguages.remoteLanguage(languages)
        set(v) = prefs.edit().putString("remote_language", v).apply()

    /** Cloudflare Access service token (Client ID). Sent only when the secret is set too. */
    var cfAccessClientId: String
        get() = prefs.getString("cf_access_id", "") ?: ""
        set(v) = prefs.edit().putString("cf_access_id", RemoteConfig.cleanHeaderValue(v)).apply()

    /**
     * Cloudflare Access service token (Client Secret). Kept in the app's private
     * preferences like the Bearer token; never logged, never shown in clear.
     */
    var cfAccessClientSecret: String
        get() = prefs.getString("cf_access_secret", "") ?: ""
        set(v) = prefs.edit().putString("cf_access_secret", RemoteConfig.cleanHeaderValue(v)).apply()

    /**
     * Before issue #23, the local model used when the server could not be reached ("" for none); read
     * once to migrate a user whose active model was the server. Since #23 the fallback is [activeModel].
     * Never a hidden model nor one that no longer suits the languages (issue #22): such a value reads
     * as Whisper turbo.
     */
    var remoteFallbackId: String
        get() = ModelMigration.fallbackId(prefs.getString("remote_fallback", ModelCatalog.default.id) ?: "", languages)
        set(v) = prefs.edit().putString("remote_fallback", v).apply()

    /** Server-side LLM polish of a whole dictation (issue #15). On by default; off = raw transcript. */
    var remotePolish: Boolean
        get() = prefs.getBoolean("remote_polish", true)
        set(v) = prefs.edit().putBoolean("remote_polish", v).apply()

    fun remoteConfig() = ServerTimeout.apply(
        RemoteConfig(
            baseUrl = remoteUrl,
            api = remoteApi,
            apiKey = remoteApiKey,
            model = remoteModel,
            language = remoteLanguage,
            cfAccessClientId = cfAccessClientId,
            cfAccessClientSecret = cfAccessClientSecret,
            polish = remotePolish,
        ),
        serverTimeoutSeconds,
    )

    /**
     * Options for [spec]. Remote settings are only attached to the remote model,
     * so changing them never reloads an on-device model.
     */
    fun engineOptions(spec: ModelSpec = activeModel, fallbackInstalled: (ModelSpec) -> Boolean = { true }): EngineOptions {
        val remote = spec.family == ModelFamily.REMOTE
        return EngineOptions(
            numThreads = threads,
            // Forced only when a single language is chosen, on the models that take one:
            // Whisper (code) and Qwen3-ASR (name). Parakeet takes none (sherpa-onnx 2.0.1).
            language = when (spec.family) {
                ModelFamily.WHISPER -> DictationLanguages.whisperLanguage(languages)
                ModelFamily.QWEN3_ASR -> DictationLanguages.qwen3Language(languages)
                else -> ""
            },
            acceleration = acceleration,
            whisperTailPaddings = WhisperTuning.tailPaddingsFor(spec, whisperTailPaddings, whisperTailAuto),
            whisperRepetitionFix = WhisperTuning.repetitionFixFor(spec, whisperTailAuto, whisperRepetitionFix),
            // Only these use hotwords: keeping them out of the other models' options means a
            // vocabulary edit does not reload an on-device model that ignores them.
            hotwords = if (spec.family == ModelFamily.QWEN3_ASR || remote) vocabulary.favoured else emptyList(),
            remote = if (remote) remoteConfig() else null,
            // The server's fallback is the on-device model (issue #23), when it is installed.
            remoteFallback = if (remote) activeModel.takeIf { it.family != ModelFamily.REMOTE }?.takeIf(fallbackInstalled) else null,
        )
    }
}
