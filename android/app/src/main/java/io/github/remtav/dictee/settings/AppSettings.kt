package io.github.remtav.dictee.settings

import android.content.Context
import android.content.SharedPreferences
import io.github.remtav.dictee.stt.Acceleration
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.RemoteApi
import io.github.remtav.dictee.stt.RemoteConfig
import io.github.remtav.dictee.stt.ModelSpec
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

/** User preferences. Everything has a safe default: the CPU path, no optional feature. */
class AppSettings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("dictee", Context.MODE_PRIVATE)

    var activeModelId: String
        get() = prefs.getString("active_model", ModelCatalog.default.id) ?: ModelCatalog.default.id
        set(v) = prefs.edit().putString("active_model", v).apply()

    val activeModel: ModelSpec get() = ModelCatalog.byId(activeModelId) ?: ModelCatalog.default

    var acceleration: Acceleration
        get() = Acceleration.fromName(prefs.getString("acceleration", null))
        set(v) = prefs.edit().putString("acceleration", v.name).apply()

    var threads: Int
        get() = prefs.getInt("threads", EngineOptions.defaultThreads())
        set(v) = prefs.edit().putInt("threads", v.coerceIn(1, 8)).apply()

    var partials: Boolean
        get() = prefs.getBoolean("partials", true)
        set(v) = prefs.edit().putBoolean("partials", v).apply()

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

    // ---- Remote server (opt-in) ----
    var remoteUrl: String
        get() = prefs.getString("remote_url", "") ?: ""
        set(v) = prefs.edit().putString("remote_url", v.trim()).apply()

    var remoteApi: RemoteApi
        get() = RemoteApi.fromName(prefs.getString("remote_api", null))
        set(v) = prefs.edit().putString("remote_api", v.name).apply()

    var remoteApiKey: String
        get() = prefs.getString("remote_key", "") ?: ""
        set(v) = prefs.edit().putString("remote_key", v.trim()).apply()

    var remoteModel: String
        get() = prefs.getString("remote_model", "") ?: ""
        set(v) = prefs.edit().putString("remote_model", v.trim()).apply()

    /** "", "fr" or "en". */
    var remoteLanguage: String
        get() = prefs.getString("remote_language", "") ?: ""
        set(v) = prefs.edit().putString("remote_language", v).apply()

    /** Local model id used when the server cannot be reached; "" for none. */
    var remoteFallbackId: String
        get() = prefs.getString("remote_fallback", ModelCatalog.default.id) ?: ""
        set(v) = prefs.edit().putString("remote_fallback", v).apply()

    fun remoteConfig() = RemoteConfig(
        baseUrl = remoteUrl,
        api = remoteApi,
        apiKey = remoteApiKey,
        model = remoteModel,
        language = remoteLanguage,
    )

    /**
     * Options for [spec]. Remote settings are only attached to the remote model,
     * so changing them never reloads an on-device model.
     */
    fun engineOptions(spec: ModelSpec = activeModel, fallbackInstalled: (ModelSpec) -> Boolean = { true }): EngineOptions {
        val remote = spec.family == ModelFamily.REMOTE
        return EngineOptions(
            numThreads = threads,
            acceleration = acceleration,
            // Only these use hotwords: keeping them out of the other models' options means a
            // vocabulary edit does not reload an on-device model that ignores them.
            hotwords = if (spec.family == ModelFamily.QWEN3_ASR || remote) vocabulary.favoured else emptyList(),
            remote = if (remote) remoteConfig() else null,
            remoteFallback = if (remote) ModelCatalog.byId(remoteFallbackId)?.takeIf(fallbackInstalled) else null,
        )
    }
}
