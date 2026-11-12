package io.github.remtav.dictee.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.stt.ServerDiagnostic
import io.github.remtav.dictee.stt.WaitControl
import java.util.Locale

/**
 * "Diagnostic serveur" (issue #13, checkpoint tool): reference dictations sent
 * to the configured server, with timings and the raw and polished texts side
 * by side. The report can be exported; it holds no secret.
 */
class DiagnosticActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private val trials = ArrayList<ServerDiagnostic.Trial>()
    @Volatile private var control: WaitControl? = null
    @Volatile private var destroyed = false
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = scrollingColumn()
        col.title(getString(R.string.diag_title))
        col.body(getString(R.string.diag_help), small = true)
        col.row().apply {
            button(getString(R.string.diag_run)) { start() }.spaced()
            button(getString(R.string.diag_export), outlined = true) { export() }.spaced()
        }
        status = col.body("", small = false)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(results)
    }

    override fun onDestroy() {
        destroyed = true
        control?.abandon()
        super.onDestroy()
    }

    private fun diagnostic() = ServerDiagnostic(DicteeApp.from(this).settings.remoteConfig())

    private fun start() {
        if (running) return
        val config = DicteeApp.from(this).settings.remoteConfig()
        if (!config.isConfigured) {
            status.text = getString(R.string.diag_not_configured)
            return
        }
        running = true
        val diag = diagnostic()
        val plan = diag.plan(CompareActivity.loadReferences(this))
        kotlin.concurrent.thread(name = "server-diagnostic") {
            for ((i, sample) in plan.withIndex()) {
                if (destroyed) break
                runOnUiThread { status.text = getString(R.string.diag_running, i + 1, plan.size) }
                val c = WaitControl().also { control = it }
                val trial = diag.run(sample, c)
                if (c.cancelled || destroyed) break
                runOnUiThread { trials += trial; render(trial, trials.size) }
            }
            if (!destroyed) runOnUiThread { running = false; status.text = getString(R.string.diag_done) }
        }
    }

    private fun render(t: ServerDiagnostic.Trial, n: Int) {
        val card = results.card()
        card.body("Essai $n — ${t.name} (%.1f s)".format(Locale.ROOT, t.audioSeconds))
        if (t.error != null) {
            card.body("Échec : ${t.error}", small = true).apply {
                setTextColor(attrColor(androidx.appcompat.R.attr.colorError))
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_error, 0, 0, 0)
                compoundDrawablePadding = dp(8)
            }
            return
        }
        card.value("Envoi ${t.uploadMillis} ms · total ${t.totalMillis} ms · ${t.transport} · polissage : ${t.polishStatus}")
        // Raw and polished side by side.
        val row = card.row()
        fun column(heading: String, text: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            label(heading)
            body(text, small = true).setTextIsSelectable(true)
        }.also { row.addView(it) }
        column("Brut", t.rawText)
        column("Poli", t.polishedText ?: "—")
    }

    private fun export() {
        val report = diagnostic().report(trials)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diag_title))
            putExtra(Intent.EXTRA_TEXT, report)
        }, getString(R.string.diag_export)))
    }
}
