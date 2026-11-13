package io.github.remtav.dictee

import android.Manifest
import android.app.Instrumentation
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelRecommendations
import io.github.remtav.dictee.text.CommandTable
import io.github.remtav.dictee.ui.HowToActivity
import io.github.remtav.dictee.ui.HowToExamples
import io.github.remtav.dictee.ui.READY_CARD_TAG
import io.github.remtav.dictee.ui.READY_MORE_TAG
import io.github.remtav.dictee.ui.SetupActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #15, step 2.3, and issue #56: the "Comment dicter" screen never opens by itself (the setup screen
 * says « C'est prêt » and invites to it), opens from that invitation, the main screen and the keyboard's help
 * button; its first screen (rule, at most three examples on two lines, command and sign marked, try field)
 * and its closed full list are generated from the command table.
 *
 * Scenarios follow one another, never nested: `ActivityScenario.launch` starts each activity in a
 * cleared task and destroys the previous one (the cause of a CI failure in lot 1). An activity
 * monitor tells when the screen is started, whoever starts it (a click, the setup screen, the keyboard service).
 */
@RunWith(AndroidJUnit4::class)
class HowToInstrumentedTest : ImeTestBase() {
    private val howTo = HowToActivity::class.java.name

    @Before
    fun allowTheMicrophone() {
        // The keyboard is enabled by ImeTestBase; the microphone cannot be asked for by the test, so it is granted.
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
    }

    @After
    fun removeMonitors() {
        monitors.forEach { instrumentation.removeMonitor(it) }
        monitors.clear()
    }

    private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()

    private fun monitorHowTo(): Instrumentation.ActivityMonitor =
        instrumentation.addMonitor(howTo, null, false).also { monitors += it }

    private fun View.allViews(): Sequence<View> = sequence {
        yield(this@allViews)
        // `this` alone would be the SequenceScope here, never a ViewGroup: the tree was never walked (review 2.R).
        val view = this@allViews
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(view.getChildAt(i).allViews())
    }

    private fun ViewGroup.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    private fun ActivityScenario<*>.viewsTagged(tag: String): List<View> {
        var found = emptyList<View>()
        onActivity { a -> found = a.window.decorView.allViews().filter { it.tag == tag }.toList() }
        return found
    }

    private fun ActivityScenario<*>.textsTagged(tag: String): List<String> =
        viewsTagged(tag).filterIsInstance<TextView>().map { it.text.toString() }

    private fun ActivityScenario<*>.allText(): String {
        var out = ""
        onActivity { a -> out = a.window.decorView.allViews().filterIsInstance<TextView>().joinToString("\n") { it.text } }
        return out
    }

    @Test
    fun nothingOpensByItselfAndTheReadyCardInvitesToTheTutorial() {
        // 1. Keyboard enabled and microphone allowed, but no model: not complete, no card, nothing opens.
        TestHooks.assumeInstalled = false
        val notYet = monitorHowTo()
        ActivityScenario.launch(SetupActivity::class.java).use { setup ->
            assertNull("no model: not opened", notYet.waitForActivityWithTimeout(2_000))
            assertTrue("no model: not ready", setup.viewsTagged(READY_CARD_TAG).isEmpty())
        }
        instrumentation.removeMonitor(notYet)

        // 2. A model is ready: the setup is complete. « C'est prêt » and the invitation show; no screen opens by itself.
        TestHooks.assumeInstalled = true
        val monitor = monitorHowTo()
        ActivityScenario.launch(SetupActivity::class.java).use { setup ->
            assertNull("the tutorial is a choice: nothing opens", monitor.waitForActivityWithTimeout(3_000))
            assertEquals("no tutorial instance created", 0, monitor.hits)
            val card = setup.viewsTagged(READY_CARD_TAG).single()
            setup.onActivity { assertTrue("visible card", card.isShown) }
            assertTrue(setup.allText().contains(context.getString(R.string.setup_ready_title)))
            val more = setup.viewsTagged(READY_MORE_TAG).single() as TextView
            assertEquals(context.getString(R.string.setup_ready_more), more.text.toString())
            // The invitation opens the tutorial.
            setup.onActivity { more.performClick() }
            assertNotNull("opened by the invitation", monitor.waitForActivityWithTimeout(10_000))
        }
    }

    @Test
    fun reopensFromTheMainScreen() {
        val monitor = monitorHowTo()
        ActivityScenario.launch(SetupActivity::class.java).use { setup ->
            val button = setup.viewsTagged(SetupActivity.HOWTO_BUTTON_TAG).single()
            setup.onActivity { assertTrue("visible button", button.isShown) }
            setup.onActivity { button.performClick() }
            val opened = monitor.waitForActivityWithTimeout(10_000)
            assertNotNull("opened from the main screen", opened)
        }
    }

    @Test
    fun reopensFromTheKeyboardHelpButton() {
        val monitor = monitorHowTo()
        useScript("Bonjour")
        launchFields()
        val help = device.wait(Until.findObject(By.res(BuildConfig.APPLICATION_ID, "help")), 10_000)
        assertNotNull("help button on the keyboard", help)
        val minPx = 48 * context.resources.displayMetrics.density
        assertTrue("touch target of at least 48 dp: ${help.visibleBounds}", help.visibleBounds.width() >= minPx - 1 && help.visibleBounds.height() >= minPx - 1)
        assertEquals(context.getString(R.string.cd_help), help.contentDescription)
        help.click()
        assertNotNull("opened from the keyboard", monitor.waitForActivityWithTimeout(15_000))
    }

    @Test
    fun theFirstScreenShowsTheRuleAtMostThreeExamplesOnTwoLinesAndTheTryFieldRightAfter() {
        for (languages in listOf(
            setOf(DictationLanguage.FRENCH_QUEBEC),
            setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.ENGLISH),
        )) {
            app.settings.languages = languages
            ActivityScenario.launch(HowToActivity::class.java).use { s ->
                assertEquals(context.getString(R.string.howto_rule), s.textsTagged(HowToActivity.RULE_TAG).single())
                val quick = HowToExamples.firstScreen(languages)
                val cards = (0 until 3).flatMap { s.viewsTagged(HowToActivity.exampleTag(it)) }
                assertEquals("$languages: one card per first-screen example", quick.size, cards.size)
                assertTrue("$languages: at most three", cards.size in 1..3)
                for (card in cards) {
                    val lines = (card as ViewGroup).children()
                    assertEquals("two lines only", 2, lines.size)
                    assertTrue(lines.all { it is TextView })
                }
                // what is said and what is obtained come from the table through the pure logic
                assertEquals(quick.map { it.said }, s.textsTagged(HowToActivity.SAID_TAG).map { it.substringAfter("« ").removeSuffix(" »") })
                assertEquals(quick.map { it.shown }, s.textsTagged(HowToActivity.RESULT_TAG).map { it.substringAfter("« ").removeSuffix(" »") })
                // order: rule, examples, try field, then the closed list
                var order = emptyList<String>()
                s.onActivity { a ->
                    order = a.window.decorView.allViews().mapNotNull { v ->
                        when (v.tag) {
                            HowToActivity.RULE_TAG -> "rule"
                            HowToActivity.exampleTag(0) -> "example"
                            HowToActivity.TRY_TAG -> "try"
                            HowToActivity.ALL_TOGGLE_TAG -> "all"
                            else -> null
                        }
                    }.toList()
                }
                assertEquals(listOf("rule", "example", "try", "all"), order)
                // no longer on the first screen: the model section, "Si le champ contient", the "Commandes :" line
                var shownText = ""
                s.onActivity { a -> shownText = a.window.decorView.allViews().filterIsInstance<TextView>().filter { it.isShown }.joinToString("\n") { it.text } }
                assertFalse(shownText.contains("Votre modèle"))
                assertFalse(shownText.contains("Si le champ contient"))
                assertFalse(shownText.contains("Commandes :"))
            }
        }
    }

    @Test
    fun theCommandAndTheSignHaveADistinctStyleNeverTheColourAlone() {
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.ENGLISH)
        ActivityScenario.launch(HowToActivity::class.java).use { s ->
            val quick = HowToExamples.firstScreen(app.settings.languages)
            val saidViews = s.viewsTagged(HowToActivity.SAID_TAG).filterIsInstance<TextView>()
            val resultViews = s.viewsTagged(HowToActivity.RESULT_TAG).filterIsInstance<TextView>()
            assertEquals(quick.size, saidViews.size)
            var primary = 0
            var onContainer = 0
            var container = 0
            s.onActivity { a ->
                val tv = android.util.TypedValue()
                fun attr(id: Int): Int { a.theme.resolveAttribute(id, tv, true); return tv.data }
                primary = attr(androidx.appcompat.R.attr.colorPrimary)
                onContainer = attr(com.google.android.material.R.attr.colorOnSecondaryContainer)
                container = attr(com.google.android.material.R.attr.colorSecondaryContainer)
            }
            quick.forEachIndexed { i, ex ->
                // --- the command, in what is said ---
                val said = saidViews[i].text as Spanned
                val spans = said.getSpans(0, said.length, Any::class.java).filter { said.getSpanStart(it) >= 0 }
                val bold = spans.filterIsInstance<StyleSpan>().single { it.style == Typeface.BOLD }
                val from = said.getSpanStart(bold)
                val to = said.getSpanEnd(bold)
                val marked = said.substring(from, to)
                assertTrue("« $marked » is a wording of ${ex.entry.command}", ex.entry.forms.any { it.text.equals(marked, ignoreCase = true) })
                assertEquals(ex.said.substring(ex.commandRange!!), marked)
                // bold + underline + colours, all on exactly that range, and nothing marked elsewhere
                assertTrue(spans.all { said.getSpanStart(it) == from && said.getSpanEnd(it) == to })
                assertTrue("underline", spans.any { it is UnderlineSpan })
                assertEquals(container, spans.filterIsInstance<BackgroundColorSpan>().single().backgroundColor)
                assertEquals(onContainer, spans.filterIsInstance<ForegroundColorSpan>().single().foregroundColor)
                // --- the sign, in what is obtained ---
                val result = resultViews[i].text as Spanned
                val rspans = result.getSpans(0, result.length, Any::class.java).filter { result.getSpanStart(it) >= 0 }
                val rbold = rspans.filterIsInstance<StyleSpan>().single { it.style == Typeface.BOLD }
                val rfrom = result.getSpanStart(rbold)
                val rto = result.getSpanEnd(rbold)
                val sign = result.substring(rfrom, rto)
                assertEquals(ex.shown.substring(ex.signRange!!), sign)
                assertTrue(rspans.all { result.getSpanStart(it) == rfrom && result.getSpanEnd(it) == rto })
                assertEquals(primary, rspans.filterIsInstance<ForegroundColorSpan>().single().foregroundColor)
                // the two styles differ from each other and from the plain text
                assertNotEquals(spans.filterIsInstance<ForegroundColorSpan>().single().foregroundColor, primary)
            }
        }
    }

    @Test
    fun theFullListIsClosedAtFirstAndOpensWithATouchAndHoldsEveryCommand() {
        for (languages in listOf(
            setOf(DictationLanguage.FRENCH_QUEBEC),
            setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.ENGLISH),
        )) {
            app.settings.languages = languages
            ActivityScenario.launch(HowToActivity::class.java).use { s ->
                val table = CommandTable.active(languages)
                val list = s.viewsTagged(HowToActivity.ALL_LIST_TAG).single()
                val toggle = s.viewsTagged(HowToActivity.ALL_TOGGLE_TAG).single() as TextView
                s.onActivity {
                    assertFalse("closed at first", list.isShown)
                    assertEquals(context.getString(R.string.howto_all_show), toggle.text.toString())
                    assertTrue(toggle.isShown)
                }
                // the other ways of saying are not on screen while the list is closed
                val also = context.getString(R.string.howto_also, "").trim()
                var shownBefore = -1
                s.onActivity { a -> shownBefore = a.window.decorView.allViews().filterIsInstance<TextView>().count { it.isShown && it.text.contains(also) } }
                assertEquals("no « Aussi » before opening", 0, shownBefore)
                // the full list: one line per command of the table (present in the view tree, hidden)
                assertEquals(table.map { it.primary }, s.textsTagged(HowToActivity.COMMAND_TAG))
                s.onActivity { toggle.performClick() }
                s.onActivity {
                    assertTrue("open after a touch", list.isShown)
                    assertEquals(context.getString(R.string.howto_all_hide), toggle.text.toString())
                    assertTrue(
                        "every wording is shown",
                        table.all { e -> e.forms.drop(1).isEmpty() || list.allViews().filterIsInstance<TextView>().any { it.isShown && it.text.contains(e.forms[1].text) } },
                    )
                    assertEquals(table.size, list.allViews().filter { it.tag == HowToActivity.COMMAND_TAG }.count { it.isShown })
                }
                // it closes again
                s.onActivity { toggle.performClick() }
                s.onActivity { assertFalse(list.isShown) }
            }
        }
    }

    @Test
    fun theScreenCarriesTheReminderTheVocabularyLinkAndATryFieldButNoModelSection() {
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
        ActivityScenario.launch(HowToActivity::class.java).use { s ->
            val privacy = s.textsTagged(HowToActivity.PRIVACY_TAG).single()
            assertTrue(privacy.contains("sur votre téléphone"))
            assertFalse("no mention of a server", privacy.contains("serveur", ignoreCase = true))
            assertFalse(s.allText().contains("Votre modèle"))
            assertFalse(s.allText().contains(ModelRecommendations.recommend(app.settings.languages).reason))
            // the vocabulary link is in the folded part
            val vocabulary = s.viewsTagged(HowToActivity.VOCABULARY_BUTTON_TAG).single()
            val list = s.viewsTagged(HowToActivity.ALL_LIST_TAG).single()
            assertTrue(generateSequence(vocabulary.parent) { it.parent }.any { it === list })
            val field = s.viewsTagged(HowToActivity.TRY_TAG).single() as EditText
            s.onActivity { assertTrue("a field that brings the keyboard up", field.isFocusableInTouchMode && field.isEnabled) }
            // voice commands are on: no warning
            assertTrue(s.viewsTagged(HowToActivity.COMMANDS_OFF_TAG).isEmpty())
        }
    }

    @Test
    fun saysWhenVoiceCommandsAreOff() {
        app.settings.voiceCommands = false
        ActivityScenario.launch(HowToActivity::class.java).use { s ->
            assertEquals(1, s.viewsTagged(HowToActivity.COMMANDS_OFF_TAG).size)
            assertTrue(s.textsTagged(HowToActivity.COMMANDS_OFF_TAG).single().contains("désactivées"))
        }
    }
}
