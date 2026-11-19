package io.github.remtav.dictee.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import io.github.remtav.dictee.BuildConfig
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.audio.MicAudioSource
import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.WavReader
import io.github.remtav.dictee.dictation.LocalCut
import io.github.remtav.dictee.dictation.LocalGrouping
import io.github.remtav.dictee.stt.BenchSample
import io.github.remtav.dictee.stt.Ct2Experiment
import io.github.remtav.dictee.stt.Benchmark
import io.github.remtav.dictee.stt.EngineChoice
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.ModelResult
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.asDownloadable
import io.github.remtav.dictee.stt.WhisperTuning
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Side-by-side comparison of the installed models on the same audio: text,
 * load time, real-time factor, peak memory, and WER on the bundled references.
 * This is the measuring tool for the human checkpoints between lots.
 */
class CompareActivity : AppCompatActivity() {
    private lateinit var app: DicteeApp
    private val main = Handler(Looper.getMainLooper())
    private val references = ArrayList<Pair<CheckBox, BenchSample>>()
    private var recording: PcmAudio? = null
    private lateinit var recordButton: MaterialButton
    private lateinit var recordInfo: TextView
    private lateinit var runButton: MaterialButton
    private lateinit var progress: TextView
    private lateinit var resultsBox: LinearLayout
    private var report = ""
    @Volatile private var recordingNow = false
    @Volatile private var busy = false
    private var ct2Box: LinearLayout? = null
    private val ct2Refresh = { renderCt2() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = DicteeApp.from(this)
        val col = scrollingColumn()
        col.title(getString(R.string.compare_title))
        col.body(getString(R.string.compare_intro), small = true)

        col.heading(getString(R.string.compare_audio))
        for (s in loadReferences(this)) {
            val cb = CheckBox(this).apply {
                text = "${s.name} (${s.language.uppercase()}, %.1f s) — « ${s.reference} »".format(Locale.ROOT, s.audio.seconds)
                isChecked = true
            }
            col.addView(cb)
            references += cb to s
        }
        recordInfo = col.body(getString(R.string.compare_no_recording), small = true)
        recordButton = col.button(getString(R.string.compare_record)) { toggleRecording() }

        // Issue #84: the CTranslate2 trial's model, downloadable here only when the APK carries the library.
        if (Ct2Experiment.libraryAvailable) {
            col.heading(getString(R.string.compare_ct2_heading))
            col.body(getString(R.string.compare_ct2_intro), small = true)
            ct2Box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(ct2Box)
            renderCt2()
            Downloads.addListener(ct2Refresh)
        }

        col.heading(getString(R.string.compare_run_heading))
        runButton = col.button(getString(R.string.compare_run)) { runComparison() }.apply { tag = TAG_RUN }
        progress = col.body("", small = true).apply { tag = TAG_PROGRESS }
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(resultsBox)
        col.row().apply {
            button(getString(R.string.compare_share), outlined = true) { share() }.spaced()
            button(getString(R.string.compare_copy), outlined = true) { copy() }
        }
    }

    override fun onDestroy() {
        Downloads.removeListener(ct2Refresh)
        super.onDestroy()
    }

    /** State of the trial's model: download progress, installed (with Delete), or the Download button. */
    private fun renderCt2() {
        val box = ct2Box ?: return
        box.removeAllViews()
        val spec = Ct2Experiment.spec
        val id = spec.id
        val installed = app.store.isInstalled(spec)
        val st = Downloads.status[id]
        box.body(
            when {
                Downloads.isBusy(id) && st != null ->
                    getString(R.string.setup_downloading, st.percent, SizeFormat.format(st.downloaded), SizeFormat.format(st.total))
                st?.error != null -> getString(R.string.setup_download_error, st.error)
                installed -> getString(R.string.setup_installed, SizeFormat.format(spec.totalBytes))
                else -> getString(R.string.setup_not_installed, SizeFormat.format(spec.totalBytes))
            },
            small = true,
        ).apply { if (!Downloads.isBusy(id) && st?.error != null) markError() }
        box.row().apply {
            when {
                Downloads.isBusy(id) -> button(getString(R.string.cancel), outlined = true) { Downloads.cancel(id) }
                installed -> button(getString(R.string.delete), outlined = true) {
                    if (!busy) { app.store.delete(spec); renderCt2() }
                }
                else -> button(getString(R.string.download)) {
                    Downloads.start(this@CompareActivity, spec.asDownloadable(),
                        getString(R.string.setup_downloading_label, spec.displayName))
                    renderCt2()
                }
            }.apply { tag = TAG_CT2_ACTION }
        }
    }

    override fun onStop() {
        recordingNow = false
        super.onStop()
    }

    private fun toggleRecording() {
        if (recordingNow) {
            recordingNow = false
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.msg_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        recordingNow = true
        recordButton.text = getString(R.string.compare_stop_recording)
        thread(name = "compare-record") {
            val mic = MicAudioSource(this)
            val chunks = ArrayList<FloatArray>()
            var total = 0
            try {
                mic.start()
                val buf = FloatArray(1600)
                while (recordingNow && total < 30 * 16000) {
                    val n = mic.read(buf)
                    if (n < 0) break
                    chunks += buf.copyOf(n)
                    total += n
                }
            } catch (e: Exception) {
                main.post { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
            } finally {
                mic.stop()
            }
            val all = FloatArray(total)
            var o = 0
            for (c in chunks) { c.copyInto(all, o); o += c.size }
            main.post {
                recordingNow = false
                recordButton.text = getString(R.string.compare_record)
                if (total > 16000 / 2) {
                    recording = PcmAudio(all, 16000)
                    recordInfo.text = getString(R.string.compare_recording_ready, total / 16000f)
                }
            }
        }
    }

    private fun runComparison() {
        if (busy) return
        val samples = references.filter { it.first.isChecked }.map { it.second }.toMutableList()
        recording?.let { samples += BenchSample("enregistrement", "?", it, null) }
        // Issue #22: the models offered for the languages (Parakeet and Whisper), and the user's server only when
        // dictations go to it (address and "server" mode, issue #57): an address alone sends nothing.
        // Issue #84: the CTranslate2 trial joins only when its library is in the APK and its model was downloaded
        // (section above) or copied by hand (adb); it never shows up otherwise.
        val ct2Dir = Ct2Experiment.modelDir(app.store, getExternalFilesDir(null))
        val ct2 = Ct2Experiment.spec.takeIf { Ct2Experiment.isReady(ct2Dir) }
        // The trial is measured against the app's Whisper (sherpa-onnx) right before it, whatever the languages,
        // so the two run on the same samples with the same options.
        val offered = EngineChoice.comparedModels(ModelCatalog.offered(app.settings.languages), app.settings.serverInUse)
            .filter { app.isInstalled(it) }
        val models = if (ct2 == null) offered else
            offered.filter { it != ModelCatalog.whisperTurbo } +
                listOfNotNull(ModelCatalog.whisperTurbo.takeIf { app.isInstalled(it) }) + ct2
        if (models.isEmpty() || samples.isEmpty()) {
            Toast.makeText(this, R.string.compare_nothing, Toast.LENGTH_LONG).show()
            return
        }
        // Issue #66, point 3: one measuring screen at a time, and none while a quick-test decode lingers (two big
        // models in memory). Only this run's claim gives the keyboard back.
        val claim = app.benchmark.tryClaim(HOLDER)
        if (claim == null) {
            progress.text = getString(R.string.measure_busy)
            return
        }
        busy = true
        runButton.isEnabled = false
        resultsBox.removeAllViews()
        // Free the keyboard's model: measurements start from a clean slate.
        app.engines.release()
        // The same options as the dictation (forced language when one language is chosen, Whisper
        // padding), so the screen measures what the keyboard does; the server never falls back here.
        val dirFor = { spec: ModelSpec -> if (spec.id == Ct2Experiment.MODEL_ID) checkNotNull(ct2Dir) else app.store.dirFor(spec.id) }
        val bench = Benchmark(app.engineFactory(), dirFor, optionsFor = { spec ->
            app.engineOptions(spec).copy(remote = app.settings.remoteConfig().takeIf { it.isConfigured }, remoteFallback = null)
        }, cutFor = { spec ->
            // Issue #53: with « Regrouper les énoncés » on, each sample is cut as the dictation cuts it (VAD + groups).
            WhisperTuning.groupingFor(spec, app.settings.whisperGroupSeconds)?.let { grouping ->
                { samples: FloatArray ->
                    val vad = app.newVad()
                    try { LocalCut.cut(samples, vad, grouping).map { it.audio } } finally { vad.release() }
                }
            }
        })
        thread(name = "compare-run") {
            try {
                val results = ArrayList<ModelResult>()
                for (spec in models) {
                    val r = bench.runOne(spec, samples) { msg -> main.post { progress.text = msg } }
                    results += r
                    main.post { renderResult(r) }
                }
                val header = "Kalmia Blabla ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" +
                    " · ${app.settings.threads} threads" +
                    (models.firstOrNull { it.family == ModelFamily.WHISPER }?.let { whisper ->
                        // The options the engine actually got (issue #49: automatic mode and its correction).
                        val o = app.engineOptions(whisper)
                        " · ${WhisperTuning.describe(o.whisperTailPaddings, o.whisperRepetitionFix)} · langue Whisper " +
                            o.language.ifEmpty { "automatique" } +
                            // Issue #53: the grouping setting, and that the samples are then cut as a dictation.
                            " · " + (WhisperTuning.groupingFor(whisper, app.settings.whisperGroupSeconds)
                                ?.let { "${it.describe()}, extraits découpés comme une dictée" } ?: LocalGrouping.OFF_DESCRIPTION)
                    } ?: "") +
                    (ct2?.let { " · ${Ct2Experiment.describe(ct2Dir)}" } ?: "")
                report = Benchmark.report(results, header) { spec -> app.engineOptions(spec).whisperRepetitionFix != null }
                if (ct2 != null) {
                    val reference = results.firstOrNull { it.spec == ModelCatalog.whisperTurbo }
                    val trial = results.first { it.spec == ct2 }
                    val versus = reference?.let { Ct2Experiment.comparison(it, trial) }
                        ?: "== CTranslate2 : pas de comparaison, ${
                            if (reference == null) "le Whisper de l'application n'est pas installé" else "une des deux mesures a échoué"
                        }"
                    report += versus + "\n"
                    main.post { resultsBox.card().body(versus) }
                }
                main.post {
                    progress.text = getString(R.string.compare_done)
                    runButton.isEnabled = true
                    busy = false
                }
            } finally {
                app.benchmark.release(claim)
            }
        }
    }

    private fun renderResult(r: ModelResult) {
        val card = resultsBox.card()
        card.heading(r.spec.displayName).setPadding(0, 0, 0, dp(4))
        if (r.error != null) {
            card.body(getString(R.string.compare_error, r.error)).markError()
            return
        }
        card.value(
            getString(
                R.string.compare_summary,
                r.loadMillis / 1000f, r.meanRtf, r.peakRssMb, r.totalWer?.let { "%.1f %%".format(Locale.ROOT, it * 100) } ?: "—",
            ),
        )
        for (s in r.samples) {
            card.value(
                "${s.sample} · RTF %.3f%s".format(Locale.ROOT, s.rtf, s.wer?.let { " · WER %.1f %%".format(Locale.ROOT, it * 100) } ?: "") +
                    Benchmark.repetitionLabel(s.repetition).let { if (it.isEmpty()) "" else " · $it" } +
                    (s.groups?.let { " · $it groupe(s)" } ?: ""),
            )
            card.body("« ${s.text} »")
        }
    }

    private fun share() {
        if (report.isEmpty()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.compare_title))
            putExtra(Intent.EXTRA_TEXT, report)
        }, getString(R.string.compare_share)))
    }

    private fun copy() {
        if (report.isEmpty()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("comparaison", report))
        Toast.makeText(this, R.string.compare_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Who holds `DicteeApp.benchmark` during a comparison. */
        const val HOLDER = "comparer"
        /** Download / Cancel / Delete of the CTranslate2 trial's model (issue #84). */
        const val TAG_CT2_ACTION = "compare_ct2_action"
        /** « Lancer la comparaison ». */
        const val TAG_RUN = "compare_run"
        /** The progress line (also where a refused run says why). */
        const val TAG_PROGRESS = "compare_progress"

        /** Bundled reference clips with their transcripts (assets/reference/references.tsv). */
        fun loadReferences(context: Context): List<BenchSample> {
            val tsv = context.assets.open("reference/references.tsv").bufferedReader().readText()
            return parseReferences(tsv) { name -> context.assets.open("reference/$name").use { WavReader.read(it) } }
        }

        fun parseReferences(tsv: String, open: (String) -> PcmAudio): List<BenchSample> =
            tsv.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                val (file, lang, text) = line.split('\t')
                BenchSample(file.removeSuffix(".wav"), lang, open(file), text)
            }
    }
}
