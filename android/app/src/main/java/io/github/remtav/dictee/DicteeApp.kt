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
import io.github.remtav.dictee.stt.EngineChoice
import io.github.remtav.dictee.stt.EngineRoute
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.ModelMigration
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.ModelPlainNames
import io.github.remtav.dictee.stt.SttEngineFactory
import io.github.remtav.dictee.stt.RemoteEngine
import io.github.remtav.dictee.stt.RemoteLog
import io.github.remtav.dictee.stt.BlockDictation
import io.github.remtav.dictee.stt.DictationResult
import io.github.remtav.dictee.stt.LentBlockBackend
import io.github.remtav.dictee.stt.WholeDictationEngine
import io.github.remtav.dictee.stt.WhisperTuning
import io.github.remtav.dictee.text.RuleCleaner
import java.io.File
import kotlin.concurrent.thread

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
    /**
     * When set, exactly these on-device catalogue models are "installed" (scripted engines need no
     * files): lets a test run the reading test with one model, then with several. Wins over [assumeInstalled].
     */
    @Volatile var installedModelIds: Set<String>? = null
    /** Forces the network state seen by the engine choice (issue #23); null = ask the system. */
    @Volatile var networkAvailable: Boolean? = null
    /**
     * Force what the installation screen reads from the system (issue #55): keyboard enabled, keyboard current
     * ([keyboardChosen]: the current input method right now; "chosen once" is a preference),
     * microphone allowed. null = ask the system. Lets a test draw the three states without touching the
     * emulator's input methods or revoking a permission (which kills the process).
     */
    @Volatile var keyboardEnabled: Boolean? = null
    @Volatile var keyboardChosen: Boolean? = null
    @Volatile var micGranted: Boolean? = null
    /** The quick test's transcription budget in seconds (issue #36), shortened by a test; null = 30 s. */
    @Volatile var quickTestBudgetSeconds: Double? = null
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
        migrateModels()
        // Reading-test contribution (issue #15, step 4.3): an archive is deleted with its screen; one that
        // survived a killed process for more than a day goes now (not younger: the person's mail may still
        // be reading it, and this process also starts for the keyboard).
        thread(name = "contribution-sweep") {
            io.github.remtav.dictee.reading.ContributionArchive.deleteOlderThan(
                io.github.remtav.dictee.ui.ContributionShare.archiveDir(this),
            )
        }
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

    /**
     * Issue #22: an active model that is hidden or no longer suits the chosen languages
     * is replaced by Whisper turbo (issue #72: that is what happens to Parakeet, now hidden), and the main screen tells the user once
     * (inviting to download Whisper turbo if it is missing, offering to delete the hidden model's
     * files: nothing is deleted without a click). Run at start-up and when the languages change.
     */
    fun migrateModels() {
        migrateServerChoice()
        val notice = ModelMigration.plan(settings.activeModelId, settings.languages) { isInstalled(it) } ?: return
        settings.activeModelId = notice.replacement.id
        settings.retiredModelNotice = notice.retired.id
        if (engines.current()?.spec?.id == notice.retired.id) engines.release()
    }

    /**
     * Issue #23: the server is no longer a model to pick but a mode (server / phone). A user whose active model
     * was the server gets their old fallback as the on-device model; the mode stays "not chosen" (so: phone)
     * until they choose "server" again (#36 or the settings), whose text says what happens to the audio,
     * which the old choice never did.
     */
    private fun migrateServerChoice() {
        if (settings.activeModelId != ModelCatalog.remote.id) return
        settings.activeModelId = settings.remoteFallbackId.ifEmpty { ModelCatalog.default.id }
    }

    /** Whether the phone has a network (any: a LAN server needs no internet). */
    fun networkAvailable(): Boolean {
        TestHooks.networkAvailable?.let { return it }
        return try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true // unknown: let the server request try, the fallback covers a failure
        }
    }

    /** Where the next dictation goes (issue #23): server or phone. */
    fun route(): EngineRoute = settings.route(networkAvailable())

    /**
     * The model the next dictation uses: the server only with an address, the "server" mode and a network;
     * the on-device model otherwise.
     */
    fun dictationSpec(): ModelSpec = EngineChoice.spec(route(), settings.activeModel)

    /** The model the keyboard shows: the server when it is the chosen mode (even while offline: the fallback covers it). */
    fun shownSpec(): ModelSpec = EngineChoice.spec(settings.route(networkAvailable = true), settings.activeModel)

    fun isInstalled(spec: ModelSpec): Boolean = when {
        spec.family == ModelFamily.REMOTE -> settings.remoteConfig().isConfigured
        else -> TestHooks.installedModelIds?.contains(spec.id) ?: (TestHooks.assumeInstalled || store.isInstalled(spec))
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
            val key = spec.id // the guard no longer takes the vocabulary: no reload when it changes
            if (llmKey != key) {
                llmCleaner?.close()
                llmCleaner = LlmCleaner.create(this, store, spec)
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
        val spec = dictationSpec()
        if (benchmarkRunning) return null to "Comparaison des modèles en cours"
        // Issue #72: « Whisper n'est pas installé » (the model is named; the dictation does not start, it does not crash).
        if (!isInstalled(spec)) return null to "${ModelPlainNames.inSentence(spec).replaceFirstChar { it.uppercase() }} n'est pas installé : ouvrez Kalmia Blabla"
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
            languages = settings.languages,
        )
        return DictationSession(
            spec = spec,
            audio = newAudioSource(),
            vad = vad,
            pipeline = pipeline,
            transcribe = { samples -> engines.use(spec, options) { it.transcribe(samples) } },
            // A provisional decode per 0.7 s would be one HTTP request each: off for the server. Off for Whisper too (too slow, see WhisperTuning).
            partials = WhisperTuning.provisionalAllowed(spec, settings.partials),
            // The server gets the whole dictation in one request, polished (issue #13).
            whole = if (spec.family != ModelFamily.REMOTE) null else { audio, segments, control ->
                engines.use(spec, options) { engine ->
                    (engine as? WholeDictationEngine)?.transcribeDictation(audio, segments, control)
                        ?: DictationResult(segments.map { engine.transcribe(it).text }, DictationResult.Source.SERVER_RAW, transport = "segments")
                }
            },
            // Issue #35: blocks sent while the user speaks; whole dictation above for a server without POST /polish.
            blocks = if (spec.family != ModelFamily.REMOTE) null else { control ->
                BlockDictation(LentBlockBackend { op -> engines.use(spec, options) { op(it) } }, control)
            },
            canFallBack = options.remoteFallback != null,
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
