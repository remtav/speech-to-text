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
import io.github.remtav.dictee.stt.Benchmark
import io.github.remtav.dictee.stt.EngineChoice
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.ModelResult
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

        col.heading(getString(R.string.compare_run_heading))
        runButton = col.button(getString(R.string.compare_run)) { runComparison() }
        progress = col.body("", small = true)
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(resultsBox)
        col.row().apply {
            button(getString(R.string.compare_share), outlined = true) { share() }.spaced()
            button(getString(R.string.compare_copy), outlined = true) { copy() }
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
        // Issue #22: the models offered for the languages, the experimental ones, and the user's server only when
        // dictations go to it (address and "server" mode, issue #57): an address alone sends nothing.
        val models = EngineChoice.comparedModels(ModelCatalog.listed(app.settings.languages), app.settings.serverInUse)
            .filter { app.isInstalled(it) }
        if (models.isEmpty() || samples.isEmpty()) {
            Toast.makeText(this, R.string.compare_nothing, Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        app.benchmarkRunning = true
        runButton.isEnabled = false
        resultsBox.removeAllViews()
        // Free the keyboard's model: measurements start from a clean slate.
        app.engines.release()
        // The same options as the dictation (forced language when one language is chosen, Whisper
        // padding), so the screen measures what the keyboard does; the server never falls back here.
        val bench = Benchmark(app.engineFactory(), { app.store.dirFor(it.id) }, optionsFor = { spec ->
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
                } ?: "")
            report = Benchmark.report(results, header) { spec -> app.engineOptions(spec).whisperRepetitionFix != null }
            main.post {
                progress.text = getString(R.string.compare_done)
                runButton.isEnabled = true
                busy = false
                app.benchmarkRunning = false
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
