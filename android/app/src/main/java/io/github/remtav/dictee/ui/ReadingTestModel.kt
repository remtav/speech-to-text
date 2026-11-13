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
import io.github.remtav.dictee.reading.ReadingProgress
import io.github.remtav.dictee.reading.ReadingSession
import io.github.remtav.dictee.reading.ReadingTranscriber
import io.github.remtav.dictee.stt.ModelCatalog
import kotlin.concurrent.thread

/**
 * Holds the reading test while the screen is rotated or recreated, and ends it with the screen:
 * [onCleared] runs only when the activity is really finished, and drops every recording
 * (issue #15, step 4.2: the audio lives in memory, in the [session], and nowhere else).
 *
 * The recording and the scoring run on worker threads; every change of what the screen shows is made on
 * the main thread, then [onChange] asks the screen to redraw (null while no screen is started).
 */
class ReadingTestModel(private val app: DicteeApp, val session: ReadingSession) : ViewModel() {
    private val main = Handler(Looper.getMainLooper())

    /** Set by the started screen; called on the main thread. */
    var onChange: (() -> Unit)? = null

    var recording = false
        private set
    var recordingSeconds = 0
        private set

    /** A message for the person (microphone refused, nothing heard, scoring failed), or null. */
    var notice: String? = null
        private set
    var progress: ReadingProgress? = null
        private set

    @Volatile private var stopRequested = false
    @Volatile private var cancelRequested = false
    @Volatile private var cleared = false

    private fun changed() { onChange?.invoke() }

    /** Starts the recording of the paragraph on screen, or stops the one in progress. */
    fun toggleRecording() {
        if (recording) {
            stopRequested = true
            return
        }
        if (!session.mayRecord) return
        // Tests replace the microphone by a clip: no permission is involved then.
        val micAllowed = TestHooks.audioSourceFactory != null ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micAllowed) {
            notice = app.getString(R.string.reading_mic_missing)
            changed()
            return
        }
        val index = session.current
        stopRequested = false
        recording = true
        recordingSeconds = 0
        notice = null
        changed()
        thread(name = "reading-record") {
            var recorded: PcmAudio? = null
            var error: String? = null
            try {
                recorded = ParagraphRecorder.record(
                    app.newAudioSource(),
                    shouldStop = { stopRequested || cleared },
                    onProgress = { s -> main.post { recordingSeconds = s.toInt(); changed() } },
                )
            } catch (e: AudioSourceException) {
                error = e.message ?: "?"
            } catch (e: SecurityException) {
                error = e.message ?: "?"
            }
            main.post {
                recording = false
                if (!cleared) {
                    when {
                        error != null -> notice = app.getString(R.string.reading_mic_error, error)
                        recorded == null -> notice = app.getString(R.string.reading_too_short)
                        else -> { session.store(index, recorded); notice = null }
                    }
                }
                changed()
            }
        }
    }

    /** The screen leaves the foreground: a recording in progress ends (the microphone is not kept in the background). */
    fun stopRecording() { stopRequested = true }

    fun answer(yes: Boolean) {
        session.answer(yes)
        changed()
    }

    fun accept() {
        session.accept()
        changed()
    }

    fun next() { session.next(); notice = null; changed() }
    fun previous() { session.previous(); notice = null; changed() }

    fun restart() { session.restart(); notice = null; changed() }

    /** Transcribes and scores what was read, with every installed on-device model, off the main thread. */
    fun startScoring() {
        if (!session.canScore) return
        session.beginScoring()
        cancelRequested = false
        notice = null
        progress = null
        // The comparison screen's guard: the keyboard must not load another model meanwhile.
        app.benchmarkRunning = true
        changed()
        thread(name = "reading-score") {
            try {
                session.score(
                    transcriber(),
                    progress = { p -> main.post { progress = p; changed() } },
                    cancelled = { cancelRequested || cleared },
                )
            } catch (e: Throwable) {
                main.post { notice = app.getString(R.string.reading_scoring_failed, e.message ?: e.javaClass.simpleName) }
            } finally {
                // Give the ~1 GB of the last model back: nothing else needs it now.
                app.engines.release()
                app.benchmarkRunning = false
                main.post { progress = null; changed() }
            }
        }
    }

    fun cancelScoring() { cancelRequested = true }

    private fun transcriber() = ReadingTranscriber { modelId, audio ->
        val spec = checkNotNull(ModelCatalog.byId(modelId)) { "modèle inconnu : $modelId" }
        // The same engine factory and options as the dictation (forced language, Whisper padding), never the server.
        app.engines.factory = app.engineFactory()
        app.engines.use(spec, app.engineOptions(spec)) { engine -> engine.transcribe(audio.samples, audio.sampleRate).text }
    }

    override fun onCleared() {
        cleared = true
        stopRequested = true
        cancelRequested = true
        onChange = null
        session.clear()
    }
}
