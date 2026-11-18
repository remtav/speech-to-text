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
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.TestHooks
import io.github.remtav.dictee.dictation.DictationController
import io.github.remtav.dictee.dictation.DictationListener
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.dictation.TextSink
import io.github.remtav.dictee.ime.emoji.EmojiCatalogue
import io.github.remtav.dictee.ime.emoji.EmojiPickerView
import io.github.remtav.dictee.ime.emoji.RecentEmojis
import io.github.remtav.dictee.settings.ThemeMode
import io.github.remtav.dictee.stt.EngineManager
import io.github.remtav.dictee.stt.ModelPlainNames
import io.github.remtav.dictee.stt.TranscriptionMode
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.DictationResult
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.RemoteApi
import io.github.remtav.dictee.stt.Transcription
import io.github.remtav.dictee.ui.HowToActivity
import io.github.remtav.dictee.ui.ProActivity
import io.github.remtav.dictee.ui.SetupActivity
import io.github.remtav.dictee.ime.words.Suggestions
import io.github.remtav.dictee.ime.words.WordTyping
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
    private var header: View? = null
    private var pad: NumberPadView? = null
    private var openKeys: View? = null
    private var strip: SuggestionStripView? = null
    private var emojiGroup: View? = null
    private var emojiPicker: EmojiPickerView? = null
    private var emojiBack: MaterialButton? = null
    private var openEmojiHeader: View? = null
    /** The emoji panel is shown (issue #80), over the dictation or the text view it was opened from. */
    private var emojiOn = false
    private var emojiCatalogue: EmojiCatalogue? = null
    /** Issue #71: the word before the cursor, its replacement by a suggestion, the automatic correction. */
    private val wordTyping = WordTyping()
    /** The suggestions shown, and a counter that drops results computed for an older word. */
    private var suggestions: Suggestions = Suggestions.NONE
    private var suggestionRequest = 0
    /** The current field takes suggestions (plain text, see [suggestionsWanted]). */
    private var suggestionField = false
    /** Page the current field asked for (text views or a number pad), from its `inputType`. */
    private var inputPage: InputPage = InputPage.Text
    /** Decimal separators to try in the current pad, preferred first (see [typeDecimal]). */
    private var decimalOrder: List<String> = DecimalSeparator.order(java.util.Locale.getDefault())

    private var sink: ImeTextSink? = null
    /** Sink set aside while a placeholder input has no editor: it holds where the dictated text ends (#81). */
    private var parkedSink: ImeTextSink? = null
    /** Field the current dictation types into: package + field id. */
    private var dictationField: String? = null
    private var currentField: String? = null
    private var passwordField = false
    /** When the keyboard was last hidden (uptime ms), 0 while shown. */
    private var hiddenAt = 0L
    /**
     * The keyboard shows the text keys rather than the microphone (issue #90). Kept in memory only: each time
     * the keyboard opens again it goes back to the microphone ([onWindowShown]), a rotation excepted.
     */
    private var textKeysOn = false
    /** The window was hidden and has not been shown again: the next [onWindowShown] is a reopening. */
    private var windowHidden = false
    /** Field and moment (uptime ms) of the last [onWindowHidden], to tell a rotation from a real closing. */
    private var windowHiddenField: String? = null
    private var windowHiddenAt = 0L

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
        keepAboveNavigationBar(v)
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
        v.findViewById<View>(R.id.key_space).setOnClickListener { haptic(it); typeText(" ") }
        v.findViewById<View>(R.id.key_enter).setOnClickListener { haptic(it); pressEnter() }
        for ((id, text) in listOf(R.id.key_comma to ",", R.id.key_period to ".", R.id.key_question to "?", R.id.key_exclaim to "!")) {
            v.findViewById<View>(id).setOnClickListener { haptic(it); typePunctuation(text) }
        }
        val backspace = v.findViewById<View>(R.id.key_backspace)
        backspace.setOnTouchListener(RepeatListener(main) { haptic(backspace); deleteBackward() })
        render()
        return v
    }

    /**
     * Issue #70: from Android 15 an input method draws under the navigation bar (three buttons or
     * gesture bar), whatever its mode. The bar's size is added to the keyboard's own padding, once per
     * inset change; when the system already keeps the keyboard clear of the bar it delivers none, so
     * nothing is doubled.
     */
    private fun keepAboveNavigationBar(v: View) {
        val left = v.paddingLeft
        val top = v.paddingTop
        val right = v.paddingRight
        val bottom = v.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val bar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(left + bar.left, top, right + bar.right, bottom + bar.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(v)
    }

    /** The text keyboard (issue #21): letters, symbols, and a bottom row that keeps the microphone. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindTextKeyboard(v: View) {
        dictationGroup = v.findViewById(R.id.dictation_group)
        textGroup = v.findViewById(R.id.text_group)
        header = v.findViewById(R.id.header)
        // Text view -> microphone (the header button) and microphone -> text keys (the labelled button by the mic).
        toggleView = v.findViewById<ImageButton>(R.id.toggle_view).apply {
            setOnClickListener { haptic(it); setTextKeyboardShown(false) }
        }
        openKeys = v.findViewById<View>(R.id.open_keys).apply {
            setOnClickListener { haptic(it); setTextKeyboardShown(true) }
        }
        bindPad(v.findViewById(R.id.pad))
        bindEmojiPanel(v)
        micSmall = v.findViewById<MaterialButton>(R.id.mic_small).apply { setOnClickListener { onMicTapped() } }
        val keys = v.findViewById<LetterKeyboardView>(R.id.letters)
        letters = keys
        keys.kind = app.settings.keyboardLayout
        keys.languages = KeyLanguage.forDictation(app.settings.languages)
        keys.onKeyPressed = { haptic(it) }
        keys.listener = object : LetterKeyboardView.Listener {
            override fun onText(text: String) = typeKey(text)
        }
        keys.backspaceTouchListener = RepeatListener(main) { typedBackspace() }
        symbolsButton = v.findViewById<MaterialButton>(R.id.kb_symbols).apply {
            setOnClickListener {
                haptic(it)
                val k = letters ?: return@setOnClickListener
                k.symbols = !k.symbols
                k.moreSymbols = false
                updateSymbolsButton()
            }
        }
        v.findViewById<View>(R.id.kb_space).setOnClickListener { haptic(it); typeKey(" ") }
        v.findViewById<View>(R.id.kb_comma).setOnClickListener { haptic(it); typeKey(",") }
        // Long press on the period: the ellipsis. The same long-press window as the keys of the symbols page.
        v.findViewById<View>(R.id.kb_period).also { keys.installKeyTouch(it, ".") { haptic(it); typeKey(".") } }
        v.findViewById<View>(R.id.kb_enter).setOnClickListener { haptic(it); stopDictationForTyping(); wordTyping.forget(); pressEnter() }
        strip = v.findViewById<SuggestionStripView>(R.id.suggestions).apply {
            onPick = { word -> pickSuggestion(word) }
        }
        applyView()
    }

    /** The emoji panel (issue #80): from the microphone view and from the text keys; a tap inserts and the panel stays open. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindEmojiPanel(v: View) {
        emojiGroup = v.findViewById(R.id.emoji_group)
        v.findViewById<View>(R.id.open_emoji).setOnClickListener { haptic(it); setEmojiShown(true) }
        openEmojiHeader = v.findViewById<View>(R.id.open_emoji_header).apply {
            setOnClickListener { haptic(it); setEmojiShown(true) }
        }
        emojiBack = v.findViewById<MaterialButton>(R.id.emoji_back).apply {
            setOnClickListener { haptic(it); setEmojiShown(false) }
        }
        v.findViewById<View>(R.id.emoji_space).setOnClickListener { haptic(it); typeKey(" ") }
        val backspace = v.findViewById<View>(R.id.emoji_backspace)
        backspace.setOnTouchListener(RepeatListener(main) { haptic(backspace); typedBackspace() })
        emojiPicker = v.findViewById<EmojiPickerView>(R.id.emoji_picker).apply {
            recents = { app.settings.recentEmojis }
            onEmoji = { glyph -> haptic(this); typeEmoji(glyph) }
        }
        // The list is read in the background: the first opening of the panel does not wait for it.
        val known = emojiCatalogue
        if (known != null) {
            emojiPicker?.setCatalogue(known)
        } else {
            val picker = emojiPicker
            Thread({
                val loaded = EmojiCatalogue.load(applicationContext)
                main.post { emojiCatalogue = loaded; (if (emojiPicker === picker) picker else emojiPicker)?.setCatalogue(loaded) }
            }, "emoji-catalogue").start()
        }
    }

    /** The panel's picker: for the tests. */
    internal fun emojiPickerForTest(): EmojiPickerView? = emojiPicker

    fun setEmojiShown(shown: Boolean) {
        emojiOn = shown
        // The panel hides the microphone and the header: nothing on screen would say the recording goes on. What was
        // said is inserted before the panel opens.
        if (shown) {
            stopDictationForTyping()
            emojiPicker?.open()
        }
        applyView()
        render()
    }

    /** Inserts [glyph] at the cursor, whole, and puts it first in the recents. */
    private fun typeEmoji(glyph: String) {
        stopDictationForTyping()
        val ic = currentInputConnection ?: return
        wordTyping.forget()
        ic.commitText(glyph, 1)
        app.settings.recentEmojis = RecentEmojis.add(app.settings.recentEmojis, glyph)
    }

    private fun updateSymbolsButton() {
        val symbols = letters?.symbols == true
        symbolsButton?.text = if (symbols) "ABC" else "123"
        symbolsButton?.contentDescription = getString(if (symbols) R.string.cd_letters else R.string.cd_symbols)
    }

    /** Text keys are shown in a password field (no dictation there) or when the person switched to them since the keyboard opened. */
    private fun textViewShown(): Boolean = passwordField || textKeysOn

    fun setTextKeyboardShown(shown: Boolean) {
        textKeysOn = shown
        applyView()
        render()
    }

    /** Shows the dictation view or the text view, as [textViewShown] says. */
    private fun applyView() {
        val padPage = inputPage as? InputPage.Pad
        val emoji = padPage == null && emojiOn
        val text = padPage == null && textViewShown()
        header?.visibility = if (padPage != null || emoji) View.GONE else View.VISIBLE
        pad?.visibility = if (padPage != null) View.VISIBLE else View.GONE
        dictationGroup?.visibility = if (text || emoji || padPage != null) View.GONE else View.VISIBLE
        textGroup?.visibility = if (text && !emoji) View.VISIBLE else View.GONE
        emojiGroup?.visibility = if (emoji) View.VISIBLE else View.GONE
        openEmojiHeader?.visibility = if (text && !emoji) View.VISIBLE else View.GONE
        // The way back leads to the view the panel was opened from.
        emojiBack?.apply {
            setIconResource(if (text) R.drawable.ic_keyboard else R.drawable.ic_mic)
            contentDescription = getString(if (text) R.string.cd_emoji_to_keys else R.string.cd_emoji_to_dictation)
        }
        toggleView?.apply {
            // Only in the text view, to go back to the microphone; in a password field the microphone is refused.
            visibility = if (text && !passwordField) View.VISIBLE else View.GONE
            contentDescription = getString(R.string.cd_to_dictation)
        }
        updateSymbolsButton()
        // The bar keeps its place whatever the field, so the keys do not move from one field to the next.
        strip?.visibility = if (text && app.settings.wordSuggestions) View.VISIBLE else View.GONE
        refreshSuggestions()
    }

    // ------------------------------------------------------------ suggestions (issue #71)

    private fun suggestionsOn(): Boolean =
        strip?.visibility == View.VISIBLE && suggestionField && !passwordField && !controller.isActive

    /** Asks for the suggestions of the word before the cursor; they are shown when they come back, if still current. */
    private fun refreshSuggestions() {
        val bar = strip ?: return
        val request = ++suggestionRequest
        val ic = currentInputConnection
        val word = if (suggestionsOn() && ic != null) wordTyping.currentWord(ic) else ""
        if (word.isEmpty()) {
            showSuggestions(Suggestions.NONE)
            return
        }
        if (word == suggestions.typed) return
        val languages = KeyLanguage.forDictation(app.settings.languages)
        app.wordLists.suggest(languages, app.settings.keyboardLayout, word) { result ->
            main.post { if (request == suggestionRequest && strip === bar) showSuggestions(result) }
        }
    }

    private fun showSuggestions(s: Suggestions) {
        // Without the automatic correction, nothing is shown in bold and nothing replaces the typed word.
        suggestions = if (app.settings.autoCorrect || s.autoCorrect == null) s else s.copy(autoCorrect = null)
        strip?.show(suggestions)
    }

    /** A tap on a word of the bar: it replaces the word before the cursor, followed by a space. */
    private fun pickSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        strip?.let { haptic(it) }
        wordTyping.pick(ic, suggestions.typed, word)
    }

    /**
     * A space or a punctuation mark from the text keys: with the automatic correction on, the word before the
     * cursor is replaced first by the correction shown in bold. Computed on the spot when the bar is behind
     * (fast typing); skipped while the word lists are still being read.
     */
    private fun typeSeparator(ic: android.view.inputmethod.InputConnection, text: String) {
        val word = if (suggestionsOn() && app.settings.autoCorrect) wordTyping.currentWord(ic) else ""
        val correction = when {
            word.isEmpty() -> null
            suggestions.typed == word -> suggestions.autoCorrect
            else -> app.wordLists.suggestNow(KeyLanguage.forDictation(app.settings.languages), app.settings.keyboardLayout, word)?.autoCorrect
        }
        wordTyping.typeSeparator(ic, text, word, correction)
    }

    /** The number pad (issue #42): digits go to the field, Enter acts as in the other views. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindPad(p: NumberPadView) {
        pad = p
        p.onKeyPressed = { haptic(it) }
        p.backspaceTouchListener = RepeatListener(main) { deleteBackward() }
        p.listener = object : NumberPadView.Listener {
            override fun onText(text: String) = typeText(text)
            override fun onDecimal() = typeDecimal()
            override fun onEnter() = pressEnter()
        }
        // A pad built after the field started (first input of the service, theme change rebuilding the view)
        // must show the keys the current field asked for, not wait for the next onStartInput.
        applyPadPage()
    }

    /** Gives the pad the keys and the decimal label of the current field, when it is a digit field. */
    private fun applyPadPage() {
        val p = inputPage as? InputPage.Pad ?: return
        pad?.decimalLabel = decimalOrder.first()
        pad?.page = p
    }

    /**
     * Types the decimal separator the field accepts. A field filters what it takes and many accept only
     * the point, so the phone's separator is tried first and, when the field leaves the text unchanged,
     * the other one; the key then shows the one that worked. Nothing is retried when the field already
     * holds a separator: that refusal is the field's own rule.
     */
    private fun typeDecimal() {
        val ic = currentInputConnection ?: return
        // A numeric password is never read back: the preferred separator is typed as is.
        if (passwordField) { ic.commitText(decimalOrder.first(), 1); return }
        val around = (ic.getTextBeforeCursor(64, 0)?.toString() ?: "") + (ic.getTextAfterCursor(64, 0)?.toString() ?: "")
        val hadSeparator = '.' in around || ',' in around
        for (sep in decimalOrder) {
            val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
            ic.commitText(sep, 1)
            // Accepted only if the text before the cursor grew by exactly this separator: a field already
            // ending with it ("12.") that refuses a second one leaves the text unchanged.
            if ((ic.getTextBeforeCursor(64, 0)?.toString() ?: "") == before + sep) {
                if (sep != decimalOrder.first()) decimalOrder = decimalOrder.reversed()
                pad?.decimalLabel = sep
                return
            }
            if (hadSeparator) return
        }
    }

    /** What was already said is inserted first, so the typed character lands after it. */
    private fun stopDictationForTyping() {
        if (controller.isActive) controller.stop()
    }

    private fun typeKey(text: String) {
        stopDictationForTyping()
        val ic = currentInputConnection ?: return
        if (WordTyping.isSeparator(text)) {
            typeSeparator(ic, text)
        } else {
            wordTyping.forget()
            ic.commitText(text, 1)
        }
    }

    private fun typedBackspace() {
        stopDictationForTyping()
        val ic = currentInputConnection ?: return
        // Right after an automatic correction, Backspace puts back the word as typed (issue #71).
        if (!wordTyping.undoCorrection(ic)) ic.deleteBackward()
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
        Log.i(TAG, "startInput field=${attribute?.fieldKey()} restarting=$restarting dictation=$dictationField")
        if (!realEditor) {
            // Placeholder input with no editor (during an activity recreation, or a screen
            // without text field): not a field change. Results wait until a field is back.
            currentField = null
            suggestionField = false
            sink?.let { parkedSink = it }
            sink = null
            return
        }
        val field = attribute!!.fieldKey()
        val sameField = field == currentField
        currentField = field
        passwordField = attribute.isPassword()
        suggestionField = suggestionsWanted(attribute.inputType)
        wordTyping.forget()
        suggestions = Suggestions.NONE
        inputPage = InputPages.forInputType(attribute.inputType)
        // The separator learned in this field stays through a restart of the same field (rotation).
        if (!sameField) decimalOrder = DecimalSeparator.order(java.util.Locale.getDefault())
        applyPadPage()
        letters?.apply {
            kind = app.settings.keyboardLayout
            languages = KeyLanguage.forDictation(app.settings.languages)
            reset()
        }
        applyView()
        // A new connection to the field of the dictation (rotation, restart) keeps where the dictated text ends.
        val previous = (sink ?: parkedSink).takeIf { field == dictationField }
        parkedSink = null
        sink = currentInputConnection?.let { ImeTextSink(it, attribute.initialSelStart, attribute.initialSelEnd, previous) }
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
        // Issue #71: the word lists are read in the background before the first key; the setting may have changed.
        if (app.settings.wordSuggestions && suggestionField) app.wordLists.preload(KeyLanguage.forDictation(app.settings.languages))
        applyView()
        // Preload the model so the first tap is fast.
        val spec = app.dictationSpec()
        if (!passwordField && inputPage is InputPage.Text && !app.benchmarkRunning && app.isInstalled(spec)) {
            app.engines.factory = app.engineFactory()
            app.engines.load(spec, app.engineOptions(spec))
        }
        render()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        windowHidden = true
        windowHiddenField = currentField
        windowHiddenAt = SystemClock.uptimeMillis()
    }

    /**
     * The keyboard is back after being hidden: the microphone view again (issue #90). Moving to another field
     * without the keyboard hiding never comes here, so the view in use is kept ("Next" in a form). A rotation
     * hides the keyboard and shows it on the same field a moment later: the view is kept then too, or a person
     * typing would lose the keys by turning the phone.
     */
    override fun onWindowShown() {
        super.onWindowShown()
        if (!windowHidden) return
        windowHidden = false
        val rotation = currentField != null && currentField == windowHiddenField &&
            SystemClock.uptimeMillis() - windowHiddenAt < HIDE_GRACE_MS
        if (!rotation && (textKeysOn || emojiOn)) {
            textKeysOn = false
            emojiOn = false
            applyView()
            render()
        }
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

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        sink?.onUpdateSelection(newSelStart, newSelEnd)
        refreshSuggestions()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        sink?.clearComposing()
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
            TestHooks.audioSourceFactory == null
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
        sink?.beginDictation()
        lastMessage = null
        controller.start(session)
    }

    override fun onState(state: DictationState, message: String?) {
        app.dictationActive = state == DictationState.LISTENING || state == DictationState.FINISHING
        Log.i(TAG, "dictation $state ${message ?: ""}")
        when (state) {
            DictationState.LISTENING -> { lastMessage = null; blocksDone = 0; blocksTotal = 0 }
            DictationState.IDLE, DictationState.ERROR -> {
                abandonAudioFocus()
                speaking = false
                if (message != null) showMessage(message, error = state == DictationState.ERROR, rerender = false)
            }
            DictationState.FINISHING -> if (message != null) showMessage(message, error = true, rerender = false)
        }
        if (state == DictationState.ERROR) root?.let { haptic(it, strong = true) }
        // No suggestion while the dictation types; back once it is done (issue #71).
        refreshSuggestions()
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

    /** Block mode (issue #35): progress shown while the user waits. */
    private var blocksDone = 0
    private var blocksTotal = 0

    override fun onBlocks(done: Int, total: Int) {
        blocksDone = done
        blocksTotal = total
        render()
    }

    override fun onInfo(message: String) {
        showMessage(message, error = false)
    }

    override fun onDictationResult(result: DictationResult, inserted: String) {
        lastDictation = result
        Log.i(
            TAG,
            "dictation result: ${result.source} via ${result.transport}, polish ${result.polishStatus}, ${result.totalMillis} ms, " +
                "wait after stop ${result.waitMillis} ms, blocks ${result.blocks} (${result.localBlocks} local)",
        )
        blocksDone = 0
        blocksTotal = 0
        val polishReason = if (result.polishStatus == "unsupported") getString(R.string.polish_too_long) else result.polishStatus
        if (result.lostBlocks > 0) {
            showMessage(
                getString(R.string.msg_blocks_lost, result.lostBlocks, result.blocks, (result.fallbackReason ?: "?").take(120)),
                error = true, rerender = false,
            )
            cancelledWait = false
            return
        }
        if (result.localBlocks > 0 && result.source != DictationResult.Source.LOCAL_FALLBACK) {
            // Some blocks went to the phone: say so, whether or not the whole text was polished afterwards.
            showMessage(
                getString(R.string.msg_blocks_local, result.localBlocks, result.blocks, (result.fallbackReason ?: "?").take(120)),
                error = false, rerender = false,
            )
            cancelledWait = false
            return
        }
        when (result.source) {
            DictationResult.Source.LOCAL_FALLBACK -> showMessage(
                if (cancelledWait) getString(R.string.msg_server_cancelled)
                else getString(R.string.msg_server_fallback, (result.fallbackReason ?: "?").take(120)),
                error = false, rerender = false,
            )
            DictationResult.Source.SERVER_RAW -> if (result.polishStatus != "not requested" && result.transport != "segments") {
                showMessage(getString(R.string.msg_polish_failed, polishReason), error = false, rerender = false)
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

    private fun typePunctuation(p: String) { currentInputConnection?.typePunctuation(p) }

    private fun deleteBackward() { currentInputConnection?.deleteBackward() }

    private fun pressEnter() { currentInputConnection?.pressEnter(currentInputEditorInfo) }

    /**
     * What the model button's menu lists (issue #89), in this order: Parakeet, Whisper, Kalmia Pro. A phone model that
     * is not downloaded is listed all the same (it leads to the main screen); Kalmia Pro is always listed.
     */
    private fun modelChoices(): List<ModelSpec> = ModelCatalog.offered(app.settings.languages) + ModelCatalog.remote

    private fun showModelMenu(anchor: View) {
        val menu = PopupMenu(anchor.context, anchor)
        val choices = modelChoices()
        val shown = app.shownSpec()
        choices.forEachIndexed { i, spec ->
            // « Parakeet · Rapide », « Whisper · Précis », « Kalmia Pro · Pro »; the plain name only for Pro.
            val label = if (spec.family == ModelFamily.REMOTE) ModelPlainNames.of(spec)
            else "${ModelPlainNames.of(spec)} · ${ModelPlainNames.short(spec)}"
            menu.menu.add(0, i, i, label).apply {
                isCheckable = true
                isChecked = spec.id == shown.id
            }
        }
        menu.setOnMenuItemClickListener { item ->
            val spec = choices[item.itemId]
            when {
                // Not downloaded: the main screen is where it is downloaded.
                spec.family != ModelFamily.REMOTE && !app.isInstalled(spec) -> {
                    showMessage(getString(R.string.msg_model_to_download, ModelPlainNames.inSentence(spec)), error = false)
                    openSettings()
                }
                spec.id != shown.id -> switchModel(spec.id)
            }
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
        val s = app.settings
        if (spec.family == ModelFamily.REMOTE) {
            // Issue #23: the "server" mode is the consent to send the audio; it is only recorded on the Kalmia Pro
            // screen, next to its text (issue #89), never by a tap in this menu. A tap here opens that screen
            // unless Pro is already the mode (then there is nothing to change).
            if (s.transcriptionMode != TranscriptionMode.SERVER || !s.remoteConfig().isConfigured) {
                openProScreen()
                return
            }
        } else {
            s.activeModelId = spec.id
            // Picking a phone model while the server is the mode means "on the phone" from now on.
            if (s.transcriptionMode == TranscriptionMode.SERVER) s.transcriptionMode = TranscriptionMode.PHONE
        }
        val next = app.dictationSpec()
        app.engines.factory = app.engineFactory()
        app.engines.load(next, app.engineOptions(next))
        render()
    }

    /** The "Comment dicter" screen; from a service the activity needs its own task. */
    private fun openHowTo() {
        startActivity(Intent(this, HowToActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The « Kalmia Pro » screen (issue #89): information, and activation under the consent text. */
    private fun openProScreen() {
        startActivity(Intent(this, ProActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        val spec = app.shownSpec()
        modelButton?.text = ModelPlainNames.short(spec)
        // Issue #89: three entries, always (Parakeet, Whisper, Kalmia Pro): the button is never alone with one.
        modelButton?.visibility = View.VISIBLE
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
            ).let { if (blocksTotal > 1) getString(R.string.status_server_blocks, it.trimEnd('…'), blocksDone, blocksTotal) else it } to false
            lastMessage != null && !listening -> lastMessage!! to lastMessageIsError
            state == DictationState.FINISHING -> getString(R.string.status_finishing) to false
            listening && es is EngineManager.State.Loading -> getString(R.string.status_listening_loading, ModelPlainNames.inSentence(es.spec)) to false
            listening && speaking -> getString(R.string.status_speaking) to false
            listening -> getString(R.string.status_listening) to false
            es is EngineManager.State.Loading -> getString(R.string.status_loading, ModelPlainNames.inSentence(es.spec)) to false
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

    companion object {
        /** A rotation recreates the activity: the keyboard comes back on the same field within this delay. */
        const val HIDE_GRACE_MS = 4000L
        const val TAG = "DicteeIme"
        @Volatile var instance: WeakReference<DicteeImeService>? = null
    }
}
