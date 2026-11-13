package io.github.remtav.dictee.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.dictation.DictationController
import io.github.remtav.dictee.dictation.DictationListener
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.dictation.TextSink
import io.github.remtav.dictee.settings.ThemeMode
import io.github.remtav.dictee.stt.EngineManager
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.DictationResult
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.RemoteApi
import io.github.remtav.dictee.stt.Transcription
import io.github.remtav.dictee.ui.HowToActivity
import io.github.remtav.dictee.ui.SetupActivity
import java.lang.ref.WeakReference

/**
 * The voice keyboard: a big microphone button and a few keys. Tap to start,
 * tap to stop; text goes into the focused field at every pause.
 */
class DicteeImeService : InputMethodService(), DictationListener, EngineManager.Listener {

    private lateinit var app: DicteeApp
    private val main = Handler(Looper.getMainLooper())
    lateinit var controller: DictationController
        private set

    private var root: View? = null
    private var micButton: MaterialButton? = null
    private var statusText: TextView? = null
    private var modelButton: MaterialButton? = null
    private var meter: LevelMeterView? = null
    private var cancelWaitButton: MaterialButton? = null
    private var micSmall: MaterialButton? = null
    private var dictationGroup: View? = null
    private var textGroup: View? = null
    private var toggleView: ImageButton? = null
    private var letters: LetterKeyboardView? = null
    private var symbolsButton: MaterialButton? = null

    private var sink: ImeTextSink? = null
    /** Field the current dictation types into: package + field id. */
    private var dictationField: String? = null
    private var currentField: String? = null
    private var passwordField = false
    /** When the keyboard was last hidden (uptime ms), 0 while shown. */
    private var hiddenAt = 0L

    private var lastMessage: String? = null
    private var lastMessageIsError = false
    private var speaking = false
    private var engineState: EngineManager.State = EngineManager.State.Empty

    private var audioFocus: AudioFocusRequest? = null
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val modeListener = AudioManager.OnModeChangedListener { mode -> onAudioModeChanged(mode) }

    private val idleRelease = Runnable {
        if (!app.keyboardVisible && !controller.isActive) {
            app.engines.release()
            app.releaseCleaner()
        }
    }

    private val stopIfHidden = Runnable {
        if (!app.keyboardVisible) {
            Log.i(TAG, "keyboard still hidden: stop")
            controller.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = DicteeApp.from(this)
        controller = DictationController(
            sinkProvider = { currentSink() },
            mainExecutor = { r -> if (Looper.myLooper() == Looper.getMainLooper()) r.run() else main.post(r) },
            listener = this,
        )
        app.engines.addListener(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnModeChangedListener(ContextCompat.getMainExecutor(this), modeListener)
        }
        instance = WeakReference(this)
    }

    override fun onDestroy() {
        controller.cancel()
        app.engines.removeListener(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.removeOnModeChangedListener(modeListener)
        abandonAudioFocus()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ views

    /** Theme setting the current keyboard view was inflated with (null before the first one). */
    private var inflatedTheme: ThemeMode? = null

    private fun themedInflater(): LayoutInflater {
        inflatedTheme = app.settings.theme
        val night = when (app.settings.theme) {
            ThemeMode.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
        }
        val config = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        }
        val ctx = ContextThemeWrapper(createConfigurationContext(config), R.style.Theme_Dictee_Keyboard)
        return LayoutInflater.from(ctx)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val v = themedInflater().inflate(R.layout.keyboard_view, null)
        root = v
        micButton = v.findViewById<MaterialButton>(R.id.mic).apply { setOnClickListener { onMicTapped() } }
        statusText = v.findViewById(R.id.status)
        meter = v.findViewById(R.id.level)
        modelButton = v.findViewById<MaterialButton>(R.id.model).apply { setOnClickListener { showModelMenu(it) } }
        cancelWaitButton = v.findViewById<MaterialButton>(R.id.cancel_wait).apply {
            setOnClickListener { haptic(it); onCancelWaitTapped() }
        }
        bindTextKeyboard(v)
        v.findViewById<ImageButton>(R.id.settings).setOnClickListener { openSettings() }
        v.findViewById<ImageButton>(R.id.help).setOnClickListener { haptic(it); openHowTo() }
        v.findViewById<View>(R.id.key_switch).setOnClickListener {
            haptic(it)
            if (!switchToPreviousInputMethod()) {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
        }
        v.findViewById<View>(R.id.key_space).setOnClickListener { haptic(it); typeText(" ") }
        v.findViewById<View>(R.id.key_enter).setOnClickListener { haptic(it); pressEnter() }
        for ((id, text) in listOf(R.id.key_comma to ",", R.id.key_period to ".", R.id.key_question to "?", R.id.key_exclaim to "!")) {
            v.findViewById<View>(id).setOnClickListener { haptic(it); typePunctuation(text) }
        }
        val backspace = v.findViewById<View>(R.id.key_backspace)
        backspace.setOnTouchListener(RepeatListener { haptic(backspace); deleteBackward() })
        render()
        return v
    }

    /** The text keyboard (issue #21): letters, symbols, and a bottom row that keeps the microphone. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindTextKeyboard(v: View) {
        dictationGroup = v.findViewById(R.id.dictation_group)
        textGroup = v.findViewById(R.id.text_group)
        toggleView = v.findViewById<ImageButton>(R.id.toggle_view).apply {
            setOnClickListener { haptic(it); setTextKeyboardShown(!app.settings.textKeyboardShown) }
        }
        micSmall = v.findViewById<MaterialButton>(R.id.mic_small).apply { setOnClickListener { onMicTapped() } }
        val keys = v.findViewById<LetterKeyboardView>(R.id.letters)
        letters = keys
        keys.kind = app.settings.keyboardLayout
        keys.onKeyPressed = { haptic(it) }
        keys.listener = object : LetterKeyboardView.Listener {
            override fun onText(text: String) = typeKey(text)
        }
        keys.backspaceTouchListener = RepeatListener { typedBackspace() }
        symbolsButton = v.findViewById<MaterialButton>(R.id.kb_symbols).apply {
            setOnClickListener {
                haptic(it)
                val k = letters ?: return@setOnClickListener
                k.symbols = !k.symbols
                updateSymbolsButton()
            }
        }
        v.findViewById<View>(R.id.kb_switch).setOnClickListener {
            haptic(it)
            if (!switchToPreviousInputMethod()) {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
        }
        v.findViewById<View>(R.id.kb_space).setOnClickListener { haptic(it); typeKey(" ") }
        v.findViewById<View>(R.id.kb_comma).setOnClickListener { haptic(it); typeKey(",") }
        v.findViewById<View>(R.id.kb_period).setOnClickListener { haptic(it); typeKey(".") }
        v.findViewById<View>(R.id.kb_enter).setOnClickListener { haptic(it); stopDictationForTyping(); pressEnter() }
        applyView()
    }

    private fun updateSymbolsButton() {
        val symbols = letters?.symbols == true
        symbolsButton?.text = if (symbols) "ABC" else "123"
        symbolsButton?.contentDescription = getString(if (symbols) R.string.cd_letters else R.string.cd_symbols)
    }

    /** Text keys are shown in a password field (no dictation there) or when the user last left them open. */
    private fun textViewShown(): Boolean = passwordField || app.settings.textKeyboardShown

    fun setTextKeyboardShown(shown: Boolean) {
        app.settings.textKeyboardShown = shown
        applyView()
        render()
    }

    /** Shows the dictation view or the text view, as [textViewShown] says. */
    private fun applyView() {
        val text = textViewShown()
        dictationGroup?.visibility = if (text) View.GONE else View.VISIBLE
        textGroup?.visibility = if (text) View.VISIBLE else View.GONE
        toggleView?.apply {
            // In a password field the microphone is refused: nothing to go back to.
            visibility = if (passwordField) View.GONE else View.VISIBLE
            setImageResource(if (text) R.drawable.ic_mic else R.drawable.ic_text_fields)
            contentDescription = getString(if (text) R.string.cd_to_dictation else R.string.cd_to_keyboard)
        }
        updateSymbolsButton()
    }

    /** What was already said is inserted first, so the typed character lands after it. */
    private fun stopDictationForTyping() {
        if (controller.isActive) controller.stop()
    }

    private fun typeKey(text: String) {
        stopDictationForTyping()
        typeText(text)
    }

    private fun typedBackspace() {
        stopDictationForTyping()
        deleteBackward()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Rebuilds the keyboard view, e.g. after the theme setting changed. */
    fun rebuildInputView() {
        setInputView(onCreateInputView())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Rotation: the dictation keeps running (it lives in the service, not the view).
        super.onConfigurationChanged(newConfig)
    }

    // -------------------------------------------------------------- lifecycle

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val realEditor = attribute != null && attribute.inputType != InputType.TYPE_NULL
        Log.i(TAG, "startInput field=${attribute?.let { fieldKey(it) }} restarting=$restarting dictation=$dictationField")
        if (!realEditor) {
            // Placeholder input with no editor (during an activity recreation, or a screen
            // without text field): not a field change. Results wait until a field is back.
            currentField = null
            sink = null
            return
        }
        val field = fieldKey(attribute!!)
        currentField = field
        passwordField = isPassword(attribute)
        letters?.apply { kind = app.settings.keyboardLayout; reset() }
        applyView()
        currentInputConnection?.let { sink = ImeTextSink(it) } ?: run { sink = null }
        if (controller.isActive && !restarting && !isSameFieldAfterRotation(attribute, field)) {
            // Focus moved to another field mid-dictation: never type into the wrong one.
            // The key alone cannot tell fields apart (WebView inputs and fields without an
            // id share it), so any new input is a change, except the one case where the
            // keyboard was hidden a moment ago and comes back on the same field (rotation).
            Log.i(TAG, "field changed: cancel")
            controller.cancel()
            showMessage(getString(R.string.msg_field_changed), error = false)
        } else if (field == dictationField) {
            controller.deliverPending()
        }
    }

    private fun fieldKey(info: EditorInfo) =
        "${info.packageName}#${info.fieldId}#${info.fieldName}#${info.inputType}#${info.hintText}"

    private fun isSameFieldAfterRotation(info: EditorInfo, field: String): Boolean =
        field == dictationField && info.fieldId != View.NO_ID &&
            hiddenAt > 0 && SystemClock.uptimeMillis() - hiddenAt < HIDE_GRACE_MS

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        app.keyboardVisible = true
        hiddenAt = 0
        Log.i(TAG, "startInputView")
        main.removeCallbacks(idleRelease)
        main.removeCallbacks(stopIfHidden)
        // The System / Light / Dark setting was changed in the app since this view was built:
        // rebuild it, so the keyboard follows the setting like the screens do.
        if (inflatedTheme != null && inflatedTheme != app.settings.theme) rebuildInputView()
        if (passwordField && controller.isActive) controller.cancel()
        // Preload the model so the first tap is fast.
        val spec = app.settings.activeModel
        if (!passwordField && !app.benchmarkRunning && app.isInstalled(spec)) {
            app.engines.factory = app.engineFactory()
            app.engines.load(spec, app.engineOptions(spec))
        }
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        app.keyboardVisible = false
        hiddenAt = SystemClock.uptimeMillis()
        Log.i(TAG, "finishInputView finishing=$finishingInput")
        // Hidden keyboard: stop listening (what was said is still inserted if the field stays).
        // Delayed, because a rotation hides and re-shows the keyboard on the same field.
        main.removeCallbacks(stopIfHidden)
        main.postDelayed(stopIfHidden, HIDE_GRACE_MS)
        val minutes = app.settings.keepLoadedMinutes
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, minutes * 60_000L)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        sink?.clearComposing()
    }

    private fun isPassword(info: EditorInfo): Boolean {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun currentSink(): TextSink? {
        // Results of a dictation only go to the field it started in.
        if (dictationField == null || dictationField != currentField) return null
        return sink
    }

    // -------------------------------------------------------------- dictation

    fun onMicTapped() {
        micButton?.let { haptic(it, strong = true) }
        if (controller.isActive) {
            controller.stop()
            return
        }
        if (passwordField) {
            showMessage(getString(R.string.msg_password), error = true)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            io.github.remtav.dictee.TestHooks.audioSourceFactory == null
        ) {
            showMessage(getString(R.string.msg_no_permission), error = true)
            openSettings()
            return
        }
        val (session, reason) = app.newSession()
        if (session == null) {
            showMessage(reason ?: getString(R.string.msg_unavailable), error = true)
            return
        }
        if (!requestAudioFocus()) {
            showMessage(getString(R.string.msg_audio_busy), error = true)
            return
        }
        dictationField = currentField
        lastMessage = null
        controller.start(session)
    }

    override fun onState(state: DictationState, message: String?) {
        app.dictationActive = state == DictationState.LISTENING || state == DictationState.FINISHING
        Log.i(TAG, "dictation $state ${message ?: ""}")
        when (state) {
            DictationState.LISTENING -> lastMessage = null
            DictationState.IDLE, DictationState.ERROR -> {
                abandonAudioFocus()
                speaking = false
                if (message != null) showMessage(message, error = state == DictationState.ERROR, rerender = false)
            }
            DictationState.FINISHING -> if (message != null) showMessage(message, error = true, rerender = false)
        }
        if (state == DictationState.ERROR) root?.let { haptic(it, strong = true) }
        render()
    }

    override fun onLevel(level: Float) {
        meter?.setLevel(level)
    }

    override fun onSpeech(active: Boolean) {
        speaking = active
        render()
    }

    override fun onSegment(transcription: Transcription, inserted: String) {
        lastSegment = transcription
    }

    /** "Annuler" while the server works: its job is cancelled and the phone transcribes the kept audio. */
    fun onCancelWaitTapped() {
        if (!controller.waitingServer) return
        cancelledWait = true
        controller.cancelServerWait()
    }

    private var cancelledWait = false

    override fun onServerWait(waiting: Boolean) {
        if (waiting) cancelledWait = false
        render()
    }

    override fun onInfo(message: String) {
        showMessage(message, error = false)
    }

    override fun onDictationResult(result: DictationResult, inserted: String) {
        lastDictation = result
        Log.i(TAG, "dictation result: ${result.source} via ${result.transport}, polish ${result.polishStatus}, ${result.totalMillis} ms")
        when (result.source) {
            DictationResult.Source.LOCAL_FALLBACK -> showMessage(
                if (cancelledWait) getString(R.string.msg_server_cancelled)
                else getString(R.string.msg_server_fallback, (result.fallbackReason ?: "?").take(120)),
                error = false, rerender = false,
            )
            DictationResult.Source.SERVER_RAW -> if (result.polishStatus != "not requested" && result.transport != "segments") {
                showMessage(getString(R.string.msg_polish_failed, result.polishStatus), error = false, rerender = false)
            }
            DictationResult.Source.SERVER_POLISHED -> {}
        }
        cancelledWait = false
    }

    /** Last whole-dictation result, for the instrumented tests. */
    @Volatile var lastDictation: DictationResult? = null
        private set

    /** Last transcription, for the instrumented tests and the status line. */
    @Volatile var lastSegment: Transcription? = null
        private set

    override fun onState(state: EngineManager.State) {
        main.post {
            engineState = state
            render()
        }
    }

    // ------------------------------------------------------------ audio focus

    /** False when the system refuses the audio focus (a call is in progress). */
    private fun requestAudioFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change -> onAudioFocusChange(change) }, main)
            .build()
        audioFocus = req
        val granted = audioManager.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
        if (!granted) audioFocus = null
        return granted
    }

    private fun abandonAudioFocus() {
        audioFocus?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocus = null
    }

    /** Another app (a call, an alarm, a voice assistant) took the audio: stop and keep what was said. */
    fun onAudioFocusChange(change: Int) {
        Log.i(TAG, "audio focus $change")
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (controller.state == DictationState.LISTENING) {
                controller.stop()
                showMessage(getString(R.string.msg_audio_focus), error = false)
            }
        }
    }

    /** Telephony audio mode (incoming or ongoing call): stop and keep what was said. */
    fun onAudioModeChanged(mode: Int) {
        Log.i(TAG, "audio mode $mode")
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_CALL_SCREENING
        ) {
            if (controller.state == DictationState.LISTENING) {
                controller.stop()
                showMessage(getString(R.string.msg_call), error = false)
            }
        }
    }

    // ------------------------------------------------------------------- keys

    private fun typeText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun typePunctuation(p: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if ((p == "," || p == ".") && before == " ") ic.deleteSurroundingText(1, 0)
        ic.commitText(p, 1)
    }

    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    private fun pressEnter() {
        val info = currentInputEditorInfo
        val ic = currentInputConnection ?: return
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val multiLine = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    private fun showModelMenu(anchor: View) {
        val menu = PopupMenu(anchor.context, anchor)
        // Issue #22: offered for the languages, then the experimental ones, then the user's server.
        val installed = (ModelCatalog.listed(app.settings.languages) + ModelCatalog.remote).filter { app.isInstalled(it) }
        if (installed.isEmpty()) {
            showMessage(getString(R.string.msg_no_model), error = true)
            return
        }
        installed.forEachIndexed { i, spec ->
            menu.menu.add(0, i, i, spec.displayName).apply {
                isCheckable = true
                isChecked = spec.id == app.settings.activeModelId
            }
        }
        menu.setOnMenuItemClickListener { item ->
            val spec = installed[item.itemId]
            if (spec.id != app.settings.activeModelId) switchModel(spec.id)
            true
        }
        menu.show()
    }

    /** Quick model switch from the keyboard itself. */
    fun switchModel(id: String) {
        val spec = ModelCatalog.byId(id) ?: return
        if (controller.isActive) {
            // Queued segments still need the current model: switching now would make the
            // two models load in turn. Ask to stop first.
            showMessage(getString(R.string.msg_stop_to_switch), error = false)
            return
        }
        app.settings.activeModelId = spec.id
        app.engines.factory = app.engineFactory()
        app.engines.load(spec, app.engineOptions(spec))
        render()
    }

    /** The "Comment dicter" screen; from a service the activity needs its own task. */
    private fun openHowTo() {
        startActivity(Intent(this, HowToActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openSettings() {
        startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ------------------------------------------------------------------ render

    private fun showMessage(text: String, error: Boolean, rerender: Boolean = true) {
        lastMessage = text
        lastMessageIsError = error
        if (rerender) render()
    }

    private fun render() {
        val mic = micButton ?: return
        val status = statusText ?: return
        val spec = app.settings.activeModel
        modelButton?.text = spec.shortName
        val state = controller.state
        val listening = state == DictationState.LISTENING
        mic.setIconResource(if (listening) R.drawable.ic_stop else R.drawable.ic_mic)
        mic.isEnabled = !passwordField && state != DictationState.FINISHING
        mic.contentDescription = getString(if (listening) R.string.cd_stop else R.string.cd_start)
        mic.isActivated = listening
        micSmall?.apply {
            setIconResource(if (listening) R.drawable.ic_stop else R.drawable.ic_mic)
            isEnabled = !passwordField && state != DictationState.FINISHING
            contentDescription = getString(if (listening) R.string.cd_stop else R.string.cd_start)
            isActivated = listening
        }

        val es = engineState
        // Read from the controller, per session: never a stale flag from a dropped dictation.
        val waiting = controller.waitingServer && state == DictationState.FINISHING
        cancelWaitButton?.visibility = if (waiting && controller.canCancelServerWait) View.VISIBLE else View.GONE
        val (text, isError) = when {
            passwordField -> getString(R.string.msg_password) to false
            waiting -> getString(
                if (spec.family == ModelFamily.REMOTE && app.settings.remoteApi == RemoteApi.OPENAI) R.string.status_server
                else R.string.status_server_polish,
            ) to false
            lastMessage != null && !listening -> lastMessage!! to lastMessageIsError
            state == DictationState.FINISHING -> getString(R.string.status_finishing) to false
            listening && es is EngineManager.State.Loading -> getString(R.string.status_listening_loading, es.spec.shortName) to false
            listening && speaking -> getString(R.string.status_speaking) to false
            listening -> getString(R.string.status_listening) to false
            es is EngineManager.State.Loading -> getString(R.string.status_loading, es.spec.shortName) to false
            es is EngineManager.State.Failed -> es.message to true
            es is EngineManager.State.Ready && es.note != null -> es.note to false
            !app.isInstalled(spec) -> getString(R.string.msg_no_model) to true
            else -> getString(R.string.status_idle) to false
        }
        status.text = text
        val tv = android.util.TypedValue()
        val attr = if (isError) androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant
        status.context.theme.resolveAttribute(attr, tv, true)
        status.setTextColor(tv.data)
        // An error is never only red: an icon goes with the text.
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(if (isError) R.drawable.ic_error else 0, 0, 0, 0)
        if (!listening) meter?.setLevel(0f)
    }

    private fun haptic(v: View, strong: Boolean = false) {
        if (!app.settings.haptics) return
        val c = if (strong && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.KEYBOARD_TAP
        v.performHapticFeedback(c)
    }

    /** Repeats an action while the key is held (backspace). */
    private inner class RepeatListener(private val action: () -> Unit) : View.OnTouchListener {
        private val repeat = object : Runnable {
            override fun run() { action(); main.postDelayed(this, 60) }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; action(); main.postDelayed(repeat, 400) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; main.removeCallbacks(repeat) }
            }
            return true
        }
    }

    companion object {
        /** A rotation recreates the activity: the keyboard comes back on the same field within this delay. */
        const val HIDE_GRACE_MS = 4000L
        const val TAG = "DicteeIme"
        @Volatile var instance: WeakReference<DicteeImeService>? = null
    }
}
