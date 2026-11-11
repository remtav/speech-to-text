package io.github.remtav.dictee.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.asDownloadable

/**
 * Launcher screen: enable the keyboard, grant the microphone (an IME cannot
 * ask for it itself), and manage the models (download, delete, choose).
 */
class SetupActivity : AppCompatActivity() {
    private lateinit var app: DicteeApp
    private lateinit var keyboardStatus: TextView
    private lateinit var micStatus: TextView
    private lateinit var modelsBox: LinearLayout
    private val refresh: () -> Unit = { renderModels() }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderSteps() }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = DicteeApp.from(this)
        val col = scrollingColumn()
        col.title(getString(R.string.app_name))
        col.body(getString(R.string.setup_intro))

        col.heading(getString(R.string.setup_step_keyboard))
        keyboardStatus = col.body()
        col.row().apply {
            button(getString(R.string.setup_open_ime_settings)) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }.spaced()
            button(getString(R.string.setup_pick_ime), outlined = true) {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
        }

        col.heading(getString(R.string.setup_step_mic))
        micStatus = col.body()
        col.button(getString(R.string.setup_grant_mic)) { askMic.launch(Manifest.permission.RECORD_AUDIO) }

        col.heading(getString(R.string.setup_models))
        col.body(getString(R.string.setup_models_note), small = true)
        modelsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(modelsBox)

        col.heading(getString(R.string.setup_more))
        col.row().apply {
            button(getString(R.string.setup_compare)) { startActivity(Intent(this@SetupActivity, CompareActivity::class.java)) }.spaced()
            button(getString(R.string.setup_settings), outlined = true) {
                startActivity(Intent(this@SetupActivity, SettingsActivity::class.java))
            }
        }

        col.heading(getString(R.string.setup_try))
        col.addView(EditText(this).apply {
            hint = getString(R.string.setup_try_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        })
    }

    override fun onResume() {
        super.onResume()
        Downloads.addListener(refresh)
        renderSteps()
        renderModels()
    }

    override fun onPause() {
        Downloads.removeListener(refresh)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) renderSteps() // back from the IME picker
    }

    private fun renderSteps() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        keyboardStatus.text = when {
            current.startsWith("$packageName/") -> getString(R.string.setup_keyboard_current)
            enabled -> getString(R.string.setup_keyboard_enabled)
            else -> getString(R.string.setup_keyboard_disabled)
        }
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus.text = getString(if (mic) R.string.setup_mic_ok else R.string.setup_mic_missing)
    }

    private fun renderModels() {
        modelsBox.removeAllViews()
        for (spec in ModelCatalog.all) renderModel(spec)
    }

    private fun renderModel(spec: ModelSpec) {
        val card = modelsBox.card()
        val installed = app.store.isInstalled(spec)
        val active = app.settings.activeModelId == spec.id
        card.addView(RadioButton(this).apply {
            text = buildString {
                append(spec.displayName)
                if (spec == ModelCatalog.default) append(getString(R.string.setup_default_suffix))
            }
            isChecked = active
            isEnabled = installed
            setOnClickListener {
                app.settings.activeModelId = spec.id
                renderModels()
            }
        })
        card.body("${spec.publisher} · ${spec.description}", small = true)
        val status = Downloads.status[spec.id]
        card.body(
            when {
                Downloads.isBusy(spec.id) && status != null ->
                    getString(R.string.setup_downloading, status.percent, size(status.downloaded), size(status.total))
                status?.error != null -> getString(R.string.setup_download_error, status.error)
                installed -> getString(R.string.setup_installed, size(app.store.installedBytes(spec.asDownloadable())))
                else -> getString(R.string.setup_not_installed, size(spec.totalBytes))
            },
            small = true,
        )
        card.row().apply {
            when {
                Downloads.isBusy(spec.id) -> button(getString(R.string.cancel), outlined = true) { Downloads.cancel(spec.id) }
                installed -> button(getString(R.string.delete), outlined = true) { confirmDelete(spec) }
                else -> button(getString(R.string.download)) { startDownload(spec) }
            }
        }
    }

    private fun startDownload(spec: ModelSpec) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Downloads.start(this, spec.asDownloadable(), getString(R.string.setup_downloading_label, spec.displayName))
        renderModels()
    }

    private fun confirmDelete(spec: ModelSpec) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title, spec.displayName))
            .setMessage(getString(R.string.delete_message))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (app.engines.current()?.spec?.id == spec.id) app.engines.release()
                app.store.delete(spec)
                renderModels()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
