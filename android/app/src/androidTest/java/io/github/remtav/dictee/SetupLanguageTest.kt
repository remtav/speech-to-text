package io.github.remtav.dictee

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.ui.SettingsActivity
import io.github.remtav.dictee.ui.SetupActivity
import io.github.remtav.dictee.ui.languageTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #15, step 1.3, and issue #22: first launch -> welcome -> language choice ->
 * only the models offered for the languages are shown (Whisper, alone since #72); changing the languages in the settings changes the offer. Reads the view tree (stable tags and ids), needs neither
 * network nor an installed model.
 */
@RunWith(AndroidJUnit4::class)
class SetupLanguageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app: DicteeApp get() = context.applicationContext as DicteeApp

    @Before
    fun firstLaunch() {
        app.settings.prefs.edit().clear().commit()
    }

    @After
    fun cleanUp() {
        app.settings.prefs.edit().clear().commit()
    }

    private fun View.allViews(): Sequence<View> = sequence {
        yield(this@allViews)
        if (this@allViews is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).allViews())
    }

    private fun ActivityScenario<*>.text(): String {
        var out = ""
        onActivity { a -> out = a.window.decorView.allViews().filterIsInstance<android.widget.TextView>().joinToString("\n") { it.text } }
        return out
    }

    /** Ids of the models that have a card on the setup screen, in screen order. */
    private fun ActivityScenario<*>.shownModels(): List<String> {
        val ids = mutableListOf<String>()
        onActivity { a ->
            for (spec in ModelCatalog.all) {
                if (a.window.decorView.findViewWithTag<View>(SetupActivity.modelCardTag(spec)) != null) ids += spec.id
            }
        }
        return ids
    }

    /** Ids of the shown models whose card carries the "recommended" badge. */
    private fun ActivityScenario<*>.recommendedModels(): List<String> {
        val ids = mutableListOf<String>()
        onActivity { a ->
            for (spec in ModelCatalog.all) {
                val card = a.window.decorView.findViewWithTag<View>(SetupActivity.modelCardTag(spec)) ?: continue
                if (card.findViewById<View>(R.id.recommended_badge) != null) ids += spec.id
            }
        }
        return ids
    }

    private fun ActivityScenario<*>.hasTag(tag: String): Boolean {
        var found = false
        onActivity { a -> found = a.window.decorView.findViewWithTag<View>(tag) != null }
        return found
    }

    private fun ActivityScenario<*>.box(language: DictationLanguage): CheckBox {
        var box: CheckBox? = null
        onActivity { box = it.window.decorView.findViewWithTag(languageTag(language)) }
        return checkNotNull(box) { "no check box for $language" }
    }

    private fun ActivityScenario<*>.click(language: DictationLanguage) {
        onActivity { (it.window.decorView.findViewWithTag<View>(languageTag(language))).performClick() }
        instrumentation.waitForIdleSync()
    }

    private val whisper = ModelCatalog.whisperTurbo.id
    private val parakeet = ModelCatalog.parakeet.id

    @Test
    fun firstLaunchShowsWelcomeAndOffersOnlyWhisperTurboForQuebecFrench() {
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            val text = s.text()
            assertTrue("welcome text", text.contains("développé au Québec"))
            // Issue #37: « code ouvert » in plain words, code public and dictations private; no model name.
            assertTrue(text.contains("Code ouvert"))
            assertTrue(text.contains("vos dictées, elles, restent privées"))
            for (word in io.github.remtav.dictee.stt.ModelPlainNames.technicalWords) {
                assertTrue("« $word » on the installation screen", !text.contains(word, ignoreCase = true))
            }
            assertTrue("Whisper is named on its card", text.contains("Whisper"))
            assertTrue("old name gone", !text.contains("Plus précis"))
            assertTrue("the quick test is not offered (#72)", !s.hasTag(SetupActivity.QUICK_TEST_BUTTON_TAG))
            assertTrue(text.contains("n'est envoyée à personne") || text.contains("n’est envoyée à personne"))
            assertTrue("no Google claim", !text.contains("Google"))
            assertTrue("old name gone", !text.contains("Dictée locale"))
            // Quebec French is preselected and the only one checked.
            var checked = emptyList<DictationLanguage>()
            s.onActivity { a ->
                checked = DictationLanguage.entries.filter {
                    a.window.decorView.findViewWithTag<CheckBox>(languageTag(it)).isChecked
                }
            }
            assertEquals(listOf(DictationLanguage.FRENCH_QUEBEC), checked)
            // Issue #22: only Whisper turbo is offered (and recommended) with Quebec French.
            assertEquals(listOf(whisper), s.shownModels())
            assertEquals(listOf(whisper), s.recommendedModels())
            assertTrue(text.contains("Recommandé pour vos langues"))
            // Publisher and licence on the card, and the "runs on the phone" reminder.
            assertTrue(text.contains(ModelCatalog.whisperTurbo.publisher))
            assertTrue(text.contains(ModelCatalog.whisperTurbo.license))
            assertTrue(text.contains("s'exécute sur votre téléphone"))
            assertTrue(!text.contains(ModelCatalog.WHISPER_QC_NOTE))
        }
    }

    /** Issue #72: one model card, whatever the languages; Parakeet is in « Avancé » only. */
    @Test
    fun parakeetIsNeverOnTheSetupScreen() {
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            s.click(DictationLanguage.ENGLISH)
            assertEquals(listOf(whisper), s.shownModels())
            s.click(DictationLanguage.FRENCH_QUEBEC) // unchecked: English alone
            assertEquals(setOf(DictationLanguage.ENGLISH), app.settings.languages)
            assertEquals(listOf(whisper), s.shownModels())
            assertEquals(listOf(whisper), s.recommendedModels())
            assertTrue(!s.hasTag(SetupActivity.QUICK_TEST_BUTTON_TAG))
        }
    }

    @Test
    fun hiddenAndAdvancedModelsAreNotOnTheSetupScreen() {
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            for (language in listOf(DictationLanguage.ENGLISH, DictationLanguage.MANDARIN, DictationLanguage.BENGALI)) {
                s.click(language)
                val shown = s.shownModels()
                assertTrue(shown.toString(), ModelCatalog.qwen3.id !in shown)
                assertTrue(shown.toString(), ModelCatalog.whisperQuebec.id !in shown)
                assertTrue(shown.toString(), ModelCatalog.liteWhisperTurbo.id !in shown)
                assertTrue(shown.toString(), parakeet !in shown)
            }
        }
    }

    /** Issue #72: an active Parakeet stays, with no notice, even when Quebec French is chosen. */
    @Test
    fun parakeetStaysActiveWithoutNotice() {
        app.settings.languages = setOf(DictationLanguage.ENGLISH)
        app.settings.activeModelId = parakeet
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertTrue(!s.hasTag(SetupActivity.RETIRED_NOTICE_TAG))
            s.click(DictationLanguage.FRENCH_QUEBEC)
            assertEquals(parakeet, app.settings.activeModelId)
            assertTrue(!s.hasTag(SetupActivity.RETIRED_NOTICE_TAG))
        }
    }

    /** Issue #72: the withdrawn Lite-Whisper, if active, gives way to Whisper with a one-time notice. */
    @Test
    fun liteWhisperGivesWayToWhisperWithANotice() {
        app.settings.activeModelId = ModelCatalog.liteWhisperTurbo.id
        app.migrateModels()
        assertEquals(whisper, app.settings.activeModelId)
        assertEquals(ModelCatalog.liteWhisperTurbo.id, app.settings.retiredModelNotice)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertTrue(s.hasTag(SetupActivity.RETIRED_NOTICE_TAG))
            // Whisper is not installed here: the notice invites to download it.
            assertTrue(s.hasTag(SetupActivity.RETIRED_DOWNLOAD_TAG))
            s.onActivity { a -> a.window.decorView.findViewWithTag<View>(SetupActivity.RETIRED_OK_TAG).performClick() }
            instrumentation.waitForIdleSync()
            assertTrue(!s.hasTag(SetupActivity.RETIRED_NOTICE_TAG))
            assertEquals("", app.settings.retiredModelNotice)
        }
    }

    @Test
    fun hiddenActiveModelIsReplacedAtStartUp() {
        app.settings.activeModelId = ModelCatalog.whisperQuebec.id
        app.migrateModels()
        assertEquals(whisper, app.settings.activeModelId)
        assertEquals(ModelCatalog.whisperQuebec.id, app.settings.retiredModelNotice)
        // The server's local fallback is never a hidden model either.
        app.settings.remoteFallbackId = ModelCatalog.qwen3.id
        assertEquals(whisper, app.settings.remoteFallbackId)
    }

    @Test
    fun lastLanguageCannotBeUnchecked() {
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            s.click(DictationLanguage.FRENCH_QUEBEC)
            assertTrue(s.box(DictationLanguage.FRENCH_QUEBEC).isChecked)
            assertEquals(setOf(DictationLanguage.FRENCH_QUEBEC), app.settings.languages)
        }
    }

    /**
     * Never two scenarios at once: ActivityScenario starts each activity in a cleared task,
     * so launching the settings while holding the setup scenario destroyed the setup
     * activity (its scenario then failed with a NullPointerException in CI).
     */
    @Test
    fun changingTheLanguagesInTheSettingsChangesTheOffer() {
        ActivityScenario.launch(SetupActivity::class.java).use { setup ->
            assertEquals(listOf(whisper), setup.shownModels())
        }
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { settings ->
            // Same list, same default as on the setup screen.
            assertTrue(settings.box(DictationLanguage.FRENCH_QUEBEC).isChecked)
            // Issue #72: Parakeet is listed in « Avancé » (the settings), Lite-Whisper nowhere.
            assertTrue(settings.hasTag(SettingsActivity.advancedCardTag(ModelCatalog.parakeet)))
            assertTrue(!settings.hasTag(SettingsActivity.advancedCardTag(ModelCatalog.liteWhisperTurbo)))
            settings.click(DictationLanguage.ENGLISH)
            settings.click(DictationLanguage.FRENCH_QUEBEC)
        }
        assertEquals(setOf(DictationLanguage.ENGLISH), app.settings.languages)
        ActivityScenario.launch(SetupActivity::class.java).use { setup ->
            assertEquals(listOf(whisper), setup.shownModels())
            assertEquals(listOf(whisper), setup.recommendedModels())
            assertTrue(setup.box(DictationLanguage.ENGLISH).isChecked)
        }
    }

    /** The setup screen stays below the settings: coming back (onResume) redraws languages and cards. */
    @Test
    fun setupRedrawsOnResumeAfterTheLanguagesChanged() {
        ActivityScenario.launch(SetupActivity::class.java).use { setup ->
            assertEquals(listOf(whisper), setup.shownModels())
            setup.moveToState(Lifecycle.State.CREATED) // covered, as by the settings screen
            app.settings.languages = setOf(DictationLanguage.ENGLISH)
            setup.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            assertEquals(listOf(whisper), setup.shownModels())
            assertTrue(setup.box(DictationLanguage.ENGLISH).isChecked)
        }
    }

    @Test
    fun remoteLanguageFollowsTheChoiceUntilSetExplicitly() {
        val s = app.settings
        assertEquals("fr", s.remoteLanguage)
        s.languages = setOf(DictationLanguage.ENGLISH)
        assertEquals("en", s.remoteLanguage)
        s.languages = setOf(DictationLanguage.MANDARIN)
        assertEquals("", s.remoteLanguage)
        s.remoteLanguage = "fr" // explicit
        s.languages = setOf(DictationLanguage.ENGLISH)
        assertEquals("fr", s.remoteLanguage)
        s.remoteLanguage = "" // explicit automatic detection is kept too
        s.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
        assertEquals("", s.remoteLanguage)
        // Whisper and Qwen3-ASR are forced only with a single code; Parakeet takes no language.
        s.languages = setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.FRENCH_FRANCE)
        assertEquals("fr", s.engineOptions(ModelCatalog.whisperTurbo).language)
        assertEquals("French", s.engineOptions(ModelCatalog.qwen3).language)
        assertEquals("", s.engineOptions(ModelCatalog.parakeet).language)
        s.languages = setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.ENGLISH)
        assertEquals("", s.engineOptions(ModelCatalog.whisperTurbo).language)
        assertEquals("", s.engineOptions(ModelCatalog.qwen3).language)
    }
}
