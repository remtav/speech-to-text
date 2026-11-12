package io.github.remtav.dictee

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import io.github.remtav.dictee.audio.AudioSourceFactory
import io.github.remtav.dictee.audio.MicAudioSource
import io.github.remtav.dictee.audio.SileroVad
import io.github.remtav.dictee.audio.VadParams
import io.github.remtav.dictee.audio.VoiceActivityDetector
import io.github.remtav.dictee.cleanup.LlmCatalog
import io.github.remtav.dictee.cleanup.LlmCleaner
import io.github.remtav.dictee.dictation.DictationSession
import io.github.remtav.dictee.dictation.TextCleaner
import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.settings.AppSettings
import io.github.remtav.dictee.settings.CleanupMode
import io.github.remtav.dictee.settings.ThemeMode
import io.github.remtav.dictee.stt.EngineManager
import io.github.remtav.dictee.stt.ModelStore
import io.github.remtav.dictee.stt.DefaultEngineFactory
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.SttEngineFactory
import io.github.remtav.dictee.stt.RemoteEngine
import io.github.remtav.dictee.stt.RemoteLog
import io.github.remtav.dictee.stt.DictationResult
import io.github.remtav.dictee.stt.WholeDictationEngine
import io.github.remtav.dictee.text.RuleCleaner
import java.io.File

/**
 * Seams for instrumented tests: the test runs in the app process and swaps the
 * microphone for a WAV file, or the model for a scripted engine. Null in normal use.
 */
object TestHooks {
    @Volatile var audioSourceFactory: AudioSourceFactory? = null
    @Volatile var engineFactory: SttEngineFactory? = null
    @Volatile var vadParams: VadParams? = null
    /** Treat every catalog model as installed (scripted engines need no files). */
    @Volatile var assumeInstalled: Boolean = false
}

class DicteeApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var store: ModelStore
        private set
    lateinit var engines: EngineManager
        private set

    private var llmCleaner: LlmCleaner? = null
    private var llmKey: String? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        store = ModelStore(File(filesDir, "models"))
        engines = EngineManager(store, isInstalled = { isInstalled(it) })
        // Messages are redacted by RemoteEngine: no token or Cloudflare secret reaches logcat.
        RemoteEngine.defaultLog = RemoteLog { android.util.Log.i("DicteeRemote", it) }
        applyTheme()
    }

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (settings.theme) {
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
    }

    fun isInstalled(spec: ModelSpec): Boolean = when {
        spec.family == ModelFamily.REMOTE -> settings.remoteConfig().isConfigured
        else -> TestHooks.assumeInstalled || store.isInstalled(spec)
    }

    /** Engine factory in effect (scripted in tests). */
    fun engineFactory(): SttEngineFactory =
        TestHooks.engineFactory ?: DefaultEngineFactory { store.dirFor(it.id) }

    fun engineOptions(spec: ModelSpec = settings.activeModel): EngineOptions =
        settings.engineOptions(spec) { isInstalled(it) }

    fun newVad(): VoiceActivityDetector {
        val params = TestHooks.vadParams ?: VadParams(minSilenceSeconds = settings.pauseSeconds)
        return SileroVad.fromAssets(assets, params)
    }

    fun newAudioSource() = TestHooks.audioSourceFactory?.create() ?: MicAudioSource(this)

    /** Text clean-up per settings. The LLM is loaded once and reused. */
    @Synchronized
    fun cleaner(): TextCleaner = when (settings.cleanupMode) {
        CleanupMode.OFF -> TextCleaner.NONE
        CleanupMode.RULES -> TextCleaner { RuleCleaner.clean(it) }
        CleanupMode.LLM -> {
            val spec = LlmCatalog.byId(settings.llmModelId) ?: LlmCatalog.qwen15
            val key = spec.id + settings.vocabularyText.hashCode()
            if (llmKey != key) {
                llmCleaner?.close()
                llmCleaner = LlmCleaner.create(this, store, spec, settings.vocabulary.favoured)
                llmKey = key
            }
            llmCleaner ?: TextCleaner { RuleCleaner.clean(it) }
        }
    }

    fun releaseCleaner() = synchronized(this) {
        llmCleaner?.close()
        llmCleaner = null
        llmKey = null
    }

    /** A dictation session for the active model, or null with a reason. */
    fun newSession(): Pair<DictationSession?, String?> {
        val spec = settings.activeModel
        if (benchmarkRunning) return null to "Comparaison des modèles en cours"
        if (!isInstalled(spec)) return null to "Modèle ${spec.shortName} non installé : ouvrez les réglages"
        engines.factory = engineFactory()
        val options = engineOptions(spec)
        engines.load(spec, options)
        val vad = try {
            newVad()
        } catch (e: Throwable) {
            return null to "Détection de la voix indisponible : ${e.message}"
        }
        val pipeline = TextPipeline(
            commandsEnabled = settings.voiceCommands,
            vocabulary = settings.vocabulary,
            cleaner = if (settings.cleanupMode == CleanupMode.OFF) TextCleaner.NONE else LazyCleaner { cleaner() },
        )
        return DictationSession(
            spec = spec,
            audio = newAudioSource(),
            vad = vad,
            pipeline = pipeline,
            transcribe = { samples -> engines.use(spec, options) { it.transcribe(samples) } },
            // A provisional decode per 0.7 s would be one HTTP request each: off for the server.
            partials = settings.partials && spec.family != ModelFamily.REMOTE,
            // The server gets the whole dictation in one request, polished (issue #13).
            whole = if (spec.family != ModelFamily.REMOTE) null else { audio, segments, control ->
                engines.use(spec, options) { engine ->
                    (engine as? WholeDictationEngine)?.transcribeDictation(audio, segments, control)
                        ?: DictationResult(segments.map { engine.transcribe(it).text }, DictationResult.Source.SERVER_RAW, transport = "segments")
                }
            },
        ) to null
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The IME keeps the model while visible; when hidden, memory pressure frees it.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // Nor while a dictation still has segments to transcribe.
            if (!keyboardVisible && !dictationActive) {
                engines.release()
                releaseCleaner()
            }
        }
    }

    @Volatile var keyboardVisible = false
    @Volatile var dictationActive = false
    /** The comparison screen loads models itself: the keyboard must not load another meanwhile. */
    @Volatile var benchmarkRunning = false

    companion object {
        fun from(context: Context) = context.applicationContext as DicteeApp
    }
}

/** Loads the (possibly slow) cleaner on the decode thread, at first use. */
private class LazyCleaner(private val provider: () -> TextCleaner) : TextCleaner {
    private val delegate by lazy { provider() }
    override fun clean(text: String): String = try { delegate.clean(text) } catch (_: Throwable) { text }
}
