package io.github.remtav.dictee.settings

import android.content.Context
import android.content.SharedPreferences
import io.github.remtav.dictee.stt.Acceleration
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
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

    fun engineOptions(): EngineOptions = EngineOptions(
        numThreads = threads,
        acceleration = acceleration,
        hotwords = vocabulary.favoured,
    )
}
