package io.github.remtav.dictee.ui

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.stt.ProOffer
import io.github.remtav.dictee.stt.ProState
import io.github.remtav.dictee.stt.TranscriptionMode

/**
 * « Kalmia Pro » (issue #89): what the option brings, what happens to the audio, and « Abonnement bientôt
 * disponible ». No payment, no form. Opened from the card of the main screen and from the keyboard's menu.
 *
 * Without a server address nothing can be turned on here. With one (typed in « Avancé », or compiled in with
 * `-PdefaultServerUrl`), Pro is turned on **only** by the button under the consent text of issue #23, on this
 * screen: nothing else (card, menu entry, opening this screen) records the "server" mode, so no audio is sent
 * before the person has read and accepted it.
 */
class ProActivity : AppCompatActivity() {
    private lateinit var app: DicteeApp
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = DicteeApp.from(this)
        box = scrollingColumn()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        box.removeAllViews()
        val settings = app.settings
        val state = ProOffer.state(settings.remoteConfig().isConfigured, settings.transcriptionMode)
        box.title(getString(R.string.pro_title)).tag = TITLE_TAG
        box.badge(getString(R.string.pro_badge))
        box.body(getString(R.string.pro_brings)).tag = BRINGS_TAG
        box.body(getString(R.string.pro_audio_sent)).tag = AUDIO_TAG
        box.body(getString(R.string.pro_subscription_soon)).tag = SOON_TAG
        when (state) {
            ProState.UNAVAILABLE -> box.body(getString(R.string.pro_unavailable), small = true).tag = UNAVAILABLE_TAG
            ProState.READY -> {
                // The consent text (issue #23), at the very place of the button that records it.
                box.heading(getString(R.string.pro_consent_title))
                box.body(getString(R.string.pro_consent)).tag = CONSENT_TAG
                box.button(getString(R.string.pro_activate)) {
                    ProOffer.modeAfterConsent(serverConfigured = settings.remoteConfig().isConfigured, consentGiven = true)
                        ?.let { settings.transcriptionMode = it }
                    render()
                }.tag = ACTIVATE_TAG
            }
            ProState.ACTIVE -> {
                box.value(getString(R.string.pro_active)).tag = ACTIVE_TAG
                box.button(getString(R.string.pro_back_to_phone), outlined = true) {
                    settings.transcriptionMode = TranscriptionMode.PHONE
                    render()
                }.tag = DEACTIVATE_TAG
            }
        }
        box.button(getString(R.string.pro_close), outlined = state != ProState.UNAVAILABLE) { finish() }.tag = CLOSE_TAG
    }

    companion object {
        const val TITLE_TAG = "pro_title"
        const val BRINGS_TAG = "pro_brings"
        const val AUDIO_TAG = "pro_audio_sent"
        const val SOON_TAG = "pro_subscription_soon"
        const val UNAVAILABLE_TAG = "pro_unavailable"
        const val CONSENT_TAG = "pro_consent"
        const val ACTIVATE_TAG = "pro_activate"
        const val ACTIVE_TAG = "pro_active"
        const val DEACTIVATE_TAG = "pro_deactivate"
        const val CLOSE_TAG = "pro_close"
        /** The card of the main screen (see [SetupActivity]). */
        const val CARD_TAG = "pro_card"
        const val CARD_BUTTON_TAG = "pro_card_button"
    }
}
