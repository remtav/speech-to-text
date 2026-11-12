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
import io.github.remtav.dictee.stt.ModelRecommendations
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
 * Issue #15, step 1.3: first launch -> welcome -> language choice -> the
 * recommended model is marked; changing the languages in the settings changes
 * the recommendation. Reads the view tree (stable tags and ids), needs neither
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
        // "Comment dicter" opens by itself once the setup is complete (HowToInstrumentedTest): not here.
        app.settings.howToShown = true
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

    /** Ids of the models whose card carries the "recommended" badge. */
    private fun ActivityScenario<*>.recommendedModels(): List<String> {
        val ids = mutableListOf<String>()
        onActivity { a ->
            for (spec in ModelCatalog.all) {
                val card = a.window.decorView.findViewWithTag<View>(SetupActivity.modelCardTag(spec))
                assertNotNull("card of ${spec.id}", card)
                if (card.findViewById<View>(R.id.recommended_badge) != null) ids += spec.id
            }
        }
        return ids
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

    @Test
    fun firstLaunchShowsWelcomeAndMarksParakeetForQuebecFrench() {
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            val text = s.text()
            assertTrue("welcome text", text.contains("conçu au Québec"))
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
            assertEquals(listOf(ModelCatalog.parakeet.id), s.recommendedModels())
            assertTrue(text.contains("Recommandé pour vos langues"))
            // Publisher and licence on the cards, and the "runs on the phone" reminder.
            assertTrue(text.contains(ModelCatalog.parakeet.publisher))
            assertTrue(text.contains(ModelCatalog.parakeet.license))
            assertTrue(text.contains("s'exécute sur votre téléphone"))
        }
    }

    @Test
    fun choosingALanguageParakeetLacksMovesTheBadgeAndWarns() {
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            s.click(DictationLanguage.MANDARIN)
            assertEquals(ModelRecommendations.recommend(app.settings.languages).model.id, s.recommendedModels().single())
            assertEquals(ModelCatalog.qwen3.id, s.recommendedModels().single())
            // Parakeet is not blocked, but carries a written warning.
            var warned = false
            var parakeetEnabledToDownload = false
            s.onActivity { a ->
                val card = a.window.decorView.findViewWithTag<View>(SetupActivity.modelCardTag(ModelCatalog.parakeet))
                warned = card.findViewWithTag<android.widget.TextView>(SetupActivity.WARNING_TAG)?.text?.contains("mandarin") == true
                parakeetEnabledToDownload = card.allViews().filterIsInstance<android.widget.Button>().any { it.isEnabled }
            }
            assertTrue("warning text on Parakeet", warned)
            assertTrue("nothing is blocked", parakeetEnabledToDownload)
            // Add Bengali: only Whisper covers everything.
            s.click(DictationLanguage.BENGALI)
            assertEquals(listOf(ModelCatalog.whisperTurbo.id), s.recommendedModels())
        }
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
    fun changingTheLanguagesInTheSettingsChangesTheRecommendation() {
        ActivityScenario.launch(SetupActivity::class.java).use { setup ->
            assertEquals(listOf(ModelCatalog.parakeet.id), setup.recommendedModels())
        }
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { settings ->
            // Same list, same default as on the setup screen.
            assertTrue(settings.box(DictationLanguage.FRENCH_QUEBEC).isChecked)
            settings.click(DictationLanguage.ARABIC)
            settings.click(DictationLanguage.HINDI)
        }
        assertEquals(
            setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.HINDI, DictationLanguage.ARABIC),
            app.settings.languages,
        )
        ActivityScenario.launch(SetupActivity::class.java).use { setup ->
            assertEquals(listOf(ModelCatalog.qwen3.id), setup.recommendedModels())
            assertTrue(setup.box(DictationLanguage.ARABIC).isChecked)
        }
    }

    /** The setup screen stays below the settings: coming back (onResume) redraws languages and cards. */
    @Test
    fun setupRedrawsOnResumeAfterTheLanguagesChanged() {
        ActivityScenario.launch(SetupActivity::class.java).use { setup ->
            assertEquals(listOf(ModelCatalog.parakeet.id), setup.recommendedModels())
            setup.moveToState(Lifecycle.State.CREATED) // covered, as by the settings screen
            app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.BENGALI)
            setup.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            assertEquals(listOf(ModelCatalog.whisperTurbo.id), setup.recommendedModels())
            assertTrue(setup.box(DictationLanguage.BENGALI).isChecked)
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
