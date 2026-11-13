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
import io.github.remtav.dictee.reading.ContributionArchive
import io.github.remtav.dictee.reading.DeviceFacts
import io.github.remtav.dictee.reading.ParagraphRecorder
import io.github.remtav.dictee.reading.ReadingProgress
import io.github.remtav.dictee.reading.ReadingSession
import io.github.remtav.dictee.reading.ReadingTranscriber
import io.github.remtav.dictee.reading.ShareOffer
import io.github.remtav.dictee.stt.ModelCatalog
import java.io.File
import kotlin.concurrent.thread

/**
 * Holds the reading test while the screen is rotated or recreated, and ends it with the screen:
 * [onCleared] runs only when the activity is really finished, and drops every recording
 * (issue #15, step 4.2: the audio lives in memory, in the [session], and nowhere else).
 *
 * Step 4.3: it also holds the sharing offer and the contribution archive, which exists only after the
 * person's explicit consent and never outlives the screen: deleted by « Supprimer le fichier », « Ne pas
 * partager », « Refaire le test », when the screen is finished ([onCleared]) and, for a leftover of a killed
 * process, when a new reading test opens ([init]) or the app starts with one older than a day (`DicteeApp`).
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

    private val archiveDir: File = ContributionShare.archiveDir(app)
    private val player = ReadingPlayer()

    init {
        // A new reading test: an archive left by an earlier one (process killed meanwhile) goes now.
        ContributionArchive.deleteAll(archiveDir)
    }

    private var offer: ShareOffer? = null

    /**
     * What the phone adds to the contribution of the current result (application version, phone model, day),
     * taken ONCE with the offer: the list shown before consent and the archive written after it use these same
     * values, even if the day changes in between (review 4.R).
     */
    var shareFacts: DeviceFacts = ContributionShare.deviceFacts()
        private set

    /** The contribution archive, once created with consent; null otherwise. */
    var archive: File? = null
        private set
    /** « Ne pas partager » was chosen for this result. */
    var shareDeclined = false
        private set
    /** The archive of this result was deleted from the screen. */
    var archiveDeleted = false
        private set
    /** The person's mail was opened with the archive (nothing is known of what they did there). */
    var mailOpened = false
    /** A message about the sharing (no mail app, archive not written), or null. */
    var shareNotice: String? = null

    val playing: Boolean get() = player.playing

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
    fun stopRecording() {
        stopRequested = true
        player.stop()
    }

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

    fun restart() {
        player.stop()
        dropArchive()
        offer = null
        resetShareState()
        session.restart()
        notice = null
        changed()
    }

    // ---- sharing (step 4.3) ----

    private fun resetShareState() {
        shareDeclined = false
        archiveDeleted = false
        mailOpened = false
        shareNotice = null
    }

    /** Display name of a model of the test. */
    fun labelOf(modelId: String): String =
        session.models.firstOrNull { it.id == modelId }?.label ?: onDevice(modelId)?.displayName ?: modelId

    /** The sharing offer of the current result, or null when nothing is offered (below the threshold). */
    fun shareOffer(): ShareOffer? {
        val result = session.result ?: return null
        if (offer?.result !== result) {
            offer = ShareOffer(result, session.declaration, session.material.text.paragraphs, labelOf(result.recommendedModelId))
            shareFacts = ContributionShare.deviceFacts()
            resetShareState()
        }
        return offer?.takeIf { it.offered }
    }

    /** The consent box (no redraw: the screen updates the button itself). */
    fun setConsent(checked: Boolean) { offer?.consent = checked }

    /** The optional region, as typed (no redraw: the field keeps its focus). */
    fun setRegion(text: String) { offer?.region = text }

    /** « Préparer le courriel »: writes the archive, only if the consent box is ticked (checked again by [ShareOffer]). */
    fun prepareArchive() {
        val o = shareOffer() ?: return
        shareNotice = null
        archive = try {
            o.createArchive(archiveDir, shareFacts)
        } catch (e: Exception) {
            ContributionArchive.deleteAll(archiveDir)
            shareNotice = app.getString(R.string.contrib_share_write_failed, e.message ?: e.javaClass.simpleName)
            null
        }
        if (archive != null) archiveDeleted = false
        changed()
    }

    private fun dropArchive() {
        ContributionArchive.deleteAll(archiveDir)
        archive = null
        offer?.consent = false
        mailOpened = false
    }

    /** « Supprimer le fichier »: the archive is gone; sharing again needs the consent box again. */
    fun deleteArchive() {
        val had = archive != null
        dropArchive()
        if (had) archiveDeleted = true
        shareNotice = null
        changed()
    }

    /** « Ne pas partager »: no archive (any existing one is deleted), and the offer is closed. */
    fun declineSharing() {
        player.stop()
        dropArchive()
        shareDeclined = true
        shareNotice = null
        changed()
    }

    /** « Réécouter » / stop: plays the recordings of the result back, from memory. */
    fun togglePlayback() {
        if (player.playing) {
            player.stop()
        } else {
            val clips = session.result?.paragraphs?.map { it.audio }.orEmpty()
            player.play(clips) { main.post { changed() } }
        }
        changed()
    }

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
        // Looked up among the on-device models only (`ModelCatalog.all`), never `byId`, which also knows the
        // user's server: even a wrong id can never make the reading test send the voice over the network.
        val spec = checkNotNull(onDevice(modelId)) { "modèle inconnu ou hors du téléphone : $modelId" }
        // The same engine factory and options as the dictation (forced language, Whisper padding), never the server.
        app.engines.factory = app.engineFactory()
        app.engines.use(spec, app.engineOptions(spec)) { engine -> engine.transcribe(audio.samples, audio.sampleRate).text }
    }

    private fun onDevice(modelId: String) = ModelCatalog.all.firstOrNull { it.id == modelId }

    override fun onCleared() {
        cleared = true
        stopRequested = true
        cancelRequested = true
        onChange = null
        player.stop()
        // The screen is closed for good: the contribution archive goes with it (sent or not).
        dropArchive()
        session.clear()
    }
}
