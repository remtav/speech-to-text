package io.github.remtav.dictee.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.TestHooks
import io.github.remtav.dictee.audio.AudioSourceException
import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.reading.ParagraphRecorder
import io.github.remtav.dictee.reading.ReadingMaterial
import io.github.remtav.dictee.reading.ReadingScoring
import io.github.remtav.dictee.reading.SecondTicker
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModeOutcome
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelPick
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.PhoneModelChoice
import io.github.remtav.dictee.stt.QuickTestAdvice
import io.github.remtav.dictee.stt.QuickTestVerdict
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * The quick test of the installation (issue #36, recentred on the phone): the person reads about 40 words, the
 * app transcribes the reading with the phone models that make sense for their languages (the light one, then the
 * best one), and recommends one in a sentence.
 *
 * - **Nothing leaves the phone**: the models are looked up among the on-device models only
 *   (`ModelCatalog.all`), never the server, whatever the settings (`QuickTestInstrumentedTest`).
 * - **Nothing is kept**: the recording lives in memory, and is dropped as soon as the scores are known (and when
 *   the screen is closed). Nothing is written to a file, nothing is offered for sharing.
 * - **One minute**: reading ≤ 25 s ([QuickTestAdvice.READING_BUDGET_SECONDS]), transcription ≤ 30 s for the two
 *   models together. A model over what is left of the budget is marked « trop lent » and the test ends without
 *   waiting for it. Neither a download nor the loading of a model counts.
 * - **A decode cannot be interrupted** (sherpa-onnx has no cancel): a model over the budget keeps computing on its
 *   own thread. The test does not wait for it, tries no other model after it (that model could not load until the
 *   decode ends, it would hold the engine's lock), gives the keyboard back at once (`benchmarkRunning` false), and
 *   refuses a new test until that decode has ended ([lingering]). The engine is then left to the keyboard's own
 *   idle release: releasing it here could free a model the keyboard has started to use.
 */
class QuickTestModel(
    private val app: DicteeApp,
    val language: DictationLanguage,
    val material: ReadingMaterial,
    val choice: PhoneModelChoice,
) : ViewModel() {
    enum class Phase { READY, RECORDING, SCORING, RESULT }

    private val main = Handler(Looper.getMainLooper())

    /** Set by the started screen; called on the main thread. */
    var onChange: (() -> Unit)? = null
    /** Called on the main thread at each new second of the recording (the screen updates one text only). */
    var onProgress: ((Int) -> Unit)? = null

    var phase = Phase.READY
        private set
    var recordingSeconds = 0
        private set
    var notice: String? = null
        private set
    /** What each candidate gave, by model id, in [PhoneModelChoice.candidates] order. */
    var outcomes: Map<String, ModeOutcome> = emptyMap()
        private set
    var verdict: QuickTestVerdict? = null
        private set
    /** The model being transcribed with, during [Phase.SCORING]. */
    var scoringModel: ModelSpec? = null
        private set

    /** The reading, in memory, between the end of the recording and the end of the scoring; null otherwise. */
    @Volatile private var audio: PcmAudio? = null
    /** Whether a recording is held in memory now (for the tests: it must be gone once the result is shown). */
    val holdsRecording: Boolean get() = audio != null

    @Volatile private var stopRequested = false
    /** The recording was cut by the screen going to the background: it is dropped, never scored. */
    @Volatile private var interrupted = false
    @Volatile private var cleared = false

    private fun changed() { onChange?.invoke() }

    /** The model a [ModelPick] stands for. */
    fun specOf(pick: ModelPick): ModelSpec = if (pick == ModelPick.LIGHT) choice.light ?: choice.best else choice.best

    fun toggleRecording() {
        if (phase == Phase.RECORDING) {
            stopRequested = true
            return
        }
        if (phase != Phase.READY) return
        if (lingering) {
            notice = app.getString(R.string.quick_previous_running)
            changed()
            return
        }
        val micAllowed = TestHooks.audioSourceFactory != null ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micAllowed) {
            notice = app.getString(R.string.reading_mic_missing)
            changed()
            return
        }
        stopRequested = false
        interrupted = false
        phase = Phase.RECORDING
        recordingSeconds = 0
        notice = null
        changed()
        val ticker = SecondTicker { sec -> recordingSeconds = sec; onProgress?.invoke(sec) }
        thread(name = "quick-test-record") {
            var recorded: PcmAudio? = null
            var error: String? = null
            try {
                recorded = ParagraphRecorder.record(
                    app.newAudioSource(),
                    maxSeconds = QuickTestAdvice.READING_BUDGET_SECONDS,
                    shouldStop = { stopRequested || cleared },
                    onProgress = { s -> main.post { if (phase == Phase.RECORDING) ticker.update(s) } },
                )
            } catch (e: AudioSourceException) {
                error = e.message ?: "?"
            } catch (e: SecurityException) {
                error = e.message ?: "?"
            }
            main.post {
                if (cleared) return@post
                when {
                    interrupted -> { phase = Phase.READY; notice = app.getString(R.string.quick_interrupted) }
                    error != null -> { phase = Phase.READY; notice = app.getString(R.string.reading_mic_error, error) }
                    recorded == null -> { phase = Phase.READY; notice = app.getString(R.string.reading_too_short) }
                    else -> { audio = recorded; score() }
                }
                changed()
            }
        }
    }

    /**
     * The screen leaves the foreground (not a rotation): a recording in progress ends, since the microphone is not
     * kept in the background, and the cut reading is dropped rather than scored as if it were complete.
     */
    fun interruptRecording() {
        if (phase != Phase.RECORDING) return
        interrupted = true
        stopRequested = true
    }

    private fun score() {
        val clip = audio ?: return
        phase = Phase.SCORING
        val token = Any()
        claimBenchmark(token)
        thread(name = "quick-test-score") {
            val results = LinkedHashMap<String, ModeOutcome>()
            try {
                var spent = 0.0
                val budget = TestHooks.quickTestBudgetSeconds ?: QuickTestAdvice.TRANSCRIPTION_BUDGET_SECONDS.toDouble()
                var stopped = false
                // The light model first: it is quick, and leaves the best one the rest of the budget.
                for (spec in listOfNotNull(choice.light, choice.best)) {
                    if (cleared) break
                    if (stopped) { results[spec.id] = ModeOutcome.Skipped; continue }
                    val onDevice = onDevice(spec.id)
                    if (onDevice == null || !app.isInstalled(onDevice)) { results[spec.id] = ModeOutcome.NotInstalled; continue }
                    main.post { if (!cleared) { scoringModel = onDevice; changed() } }
                    val left = (budget - spent).coerceAtLeast(0.0)
                    val outcome = try {
                        transcribe(onDevice, clip, left)
                    } catch (_: Exception) {
                        results[spec.id] = ModeOutcome.Failed
                        continue
                    }
                    if (outcome == null) {
                        results[spec.id] = ModeOutcome.TooSlow
                        stopped = true
                    } else {
                        val (text, delay) = outcome
                        spent += delay
                        val similarity = ReadingScoring.global(listOf(ReadingScoring.scoreParagraph(material, 0, text)), 1).similarity
                        results[spec.id] = QuickTestAdvice.outcome(similarity, delay, left)
                    }
                }
                // A model stopped on the way (a decode still running) keeps the engine: nothing is released here.
                if (!stopped) app.engines.release()
            } catch (_: Throwable) {
                // Never let this thread take the process (and the keyboard) down: whatever is missing is « échec ».
            } finally {
                // Nothing is kept: the reading goes as soon as the scores are known.
                audio = null
                releaseBenchmark(token)
            }
            val light = choice.light?.let { results[it.id] } ?: ModeOutcome.NotInstalled
            val best = results[choice.best.id] ?: ModeOutcome.Failed
            val decided = if (choice.light == null) null else QuickTestAdvice.decide(light, best)
            main.post {
                if (cleared) return@post
                outcomes = choice.candidates.associate { it.id to (results[it.id] ?: ModeOutcome.Failed) }
                verdict = decided
                scoringModel = null
                phase = Phase.RESULT
                changed()
            }
        }
    }

    /**
     * Loads [spec] (untimed) and transcribes [clip] on a thread of its own, waiting at most [leftSeconds] (plus a
     * small grace) for the transcription. Returns the text and the transcription delay, null when it went over
     * (the decode then goes on alone: [lingering] until it ends), or throws on a failure.
     */
    private fun transcribe(spec: ModelSpec, clip: PcmAudio, leftSeconds: Double): Pair<String, Double>? {
        app.engines.factory = app.engineFactory()
        val options = app.engineOptions(spec)
        app.engines.load(spec, options).get() // loading does not count
        val job = CompletableFuture<Pair<String, Double>>()
        lingering = true
        thread(name = "quick-test-transcribe") {
            try {
                job.complete(
                    app.engines.use(spec, options) { engine ->
                        val t0 = System.nanoTime()
                        val text = engine.transcribe(clip.samples, clip.sampleRate).text
                        text to (System.nanoTime() - t0) / 1e9
                    },
                )
            } catch (e: Throwable) {
                job.completeExceptionally(e)
            } finally {
                lingering = false
                main.post { onLingeringEnded?.invoke() }
            }
        }
        return try {
            job.get((leftSeconds * 1000).toLong() + GRACE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** « Utiliser … »: the recommended phone model becomes the active one. Never the server. */
    fun adopt(spec: ModelSpec) {
        val onDevice = onDevice(spec.id) ?: return
        if (!app.isInstalled(onDevice)) return
        app.settings.activeModelId = onDevice.id
        changed()
    }

    /** « Refaire le test »: a new reading (after a download, in another language…). */
    fun restart() {
        if (phase == Phase.RECORDING || phase == Phase.SCORING) return
        audio = null
        outcomes = emptyMap()
        verdict = null
        notice = null
        phase = Phase.READY
        changed()
    }

    private fun onDevice(id: String) = ModelCatalog.all.firstOrNull { it.id == id }

    override fun onCleared() {
        cleared = true
        stopRequested = true
        onChange = null
        audio = null
    }

    /** `benchmarkRunning` belongs to this test from now: the keyboard does not load another model meanwhile. */
    private fun claimBenchmark(token: Any) {
        synchronized(Companion) {
            owner = token
            app.benchmarkRunning = true
        }
    }

    /** Gives the keyboard back, unless a newer test (or nothing) owns it already. */
    private fun releaseBenchmark(token: Any) {
        synchronized(Companion) {
            if (owner === token) {
                owner = null
                app.benchmarkRunning = false
            }
        }
    }

    companion object {
        /** Margin over the budget before a model is given up (thread hand-over, scoring). */
        const val GRACE_MILLIS = 500L

        /**
         * A transcription of an earlier quick test is still running (over its budget, it cannot be interrupted).
         * Process-wide: a new screen refuses to start a test until it ends, so two decodes never fight for the engine.
         */
        @Volatile var lingering = false
            private set

        /** The screen to tell when [lingering] ends (main thread), so it can let the person start again. */
        @Volatile var onLingeringEnded: (() -> Unit)? = null

        /** Which quick test set `benchmarkRunning` (guarded by the companion's monitor). */
        private var owner: Any? = null
    }
}
