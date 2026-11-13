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
import java.util.concurrent.Executors
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
    @Volatile private var cleared = false
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "quick-test-transcribe") }

    private fun changed() { onChange?.invoke() }

    /** The model a [ModelPick] stands for. */
    fun specOf(pick: ModelPick): ModelSpec = if (pick == ModelPick.LIGHT) choice.light ?: choice.best else choice.best

    fun toggleRecording() {
        if (phase == Phase.RECORDING) {
            stopRequested = true
            return
        }
        if (phase != Phase.READY) return
        val micAllowed = TestHooks.audioSourceFactory != null ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micAllowed) {
            notice = app.getString(R.string.reading_mic_missing)
            changed()
            return
        }
        stopRequested = false
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
                    error != null -> { phase = Phase.READY; notice = app.getString(R.string.reading_mic_error, error) }
                    recorded == null -> { phase = Phase.READY; notice = app.getString(R.string.reading_too_short) }
                    else -> { audio = recorded; score() }
                }
                changed()
            }
        }
    }

    /** The screen leaves the foreground: a recording in progress ends (the microphone is not kept in the background). */
    fun stopRecording() { stopRequested = true }

    private fun score() {
        val clip = audio ?: return
        phase = Phase.SCORING
        // The comparison screen's guard: the keyboard must not load another model meanwhile.
        app.benchmarkRunning = true
        thread(name = "quick-test-score") {
            val results = LinkedHashMap<String, ModeOutcome>()
            var spent = 0.0
            var stillRunning = false
            val budget = TestHooks.quickTestBudgetSeconds ?: QuickTestAdvice.TRANSCRIPTION_BUDGET_SECONDS.toDouble()
            // The light model first: it is quick, and leaves the best one the rest of the budget.
            for (spec in listOfNotNull(choice.light, choice.best)) {
                if (cleared) break
                val onDevice = onDevice(spec.id)
                if (onDevice == null || !app.isInstalled(onDevice)) { results[spec.id] = ModeOutcome.Unavailable; continue }
                main.post { scoringModel = onDevice; changed() }
                val left = (budget - spent).coerceAtLeast(0.0)
                try {
                    app.engines.factory = app.engineFactory()
                    val options = app.engineOptions(onDevice)
                    app.engines.load(onDevice, options).get() // loading does not count
                    val job = worker.submit<Pair<String, Double>> {
                        app.engines.use(onDevice, options) { engine ->
                            val t0 = System.nanoTime()
                            val text = engine.transcribe(clip.samples, clip.sampleRate).text
                            text to (System.nanoTime() - t0) / 1e9
                        }
                    }
                    val (text, delay) = job.get((left * 1000).toLong() + GRACE_MILLIS, TimeUnit.MILLISECONDS)
                    spent += delay
                    val similarity = ReadingScoring.global(listOf(ReadingScoring.scoreParagraph(material, 0, text)), 1).similarity
                    results[spec.id] = QuickTestAdvice.outcome(similarity, delay, left)
                } catch (_: TimeoutException) {
                    // Not waited for: the test ends within the minute; the engine finishes in the background.
                    results[spec.id] = ModeOutcome.TooSlow
                    spent = budget
                    stillRunning = true
                } catch (_: Exception) {
                    results[spec.id] = ModeOutcome.Unavailable
                }
            }
            // Nothing is kept: the reading goes as soon as the scores are known.
            audio = null
            val light = choice.light?.let { results[it.id] } ?: ModeOutcome.Unavailable
            val best = results[choice.best.id] ?: ModeOutcome.Unavailable
            val decided = if (choice.light == null) null else QuickTestAdvice.decide(light, best)
            if (stillRunning) {
                worker.execute { app.engines.release(); app.benchmarkRunning = false }
            } else {
                app.engines.release()
                app.benchmarkRunning = false
            }
            main.post {
                if (cleared) return@post
                outcomes = choice.candidates.associate { it.id to (results[it.id] ?: ModeOutcome.Unavailable) }
                verdict = decided
                scoringModel = null
                phase = Phase.RESULT
                changed()
            }
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
        worker.shutdown()
    }

    companion object {
        /** Margin over the budget before a model is given up (thread hand-over, scoring). */
        const val GRACE_MILLIS = 500L
    }
}
