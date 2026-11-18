package io.github.remtav.dictee

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.remtav.dictee.ime.emoji.Emoji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Issue #80: the emoji panel, from the microphone view and from the text keys. */
@RunWith(AndroidJUnit4::class)
class EmojiInstrumentedTest : ImeTestBase() {

    private val flagFr = "🇫🇷" // 🇫🇷
    private val family = "👨‍👩‍👧‍👦" // 👨‍👩‍👧‍👦
    private val rainbowFlag = "🏳️‍🌈" // 🏳️‍🌈

    private fun res(id: String): BySelector = By.res(BuildConfig.APPLICATION_ID, id)

    private fun obj(selector: BySelector, what: String): UiObject2 {
        device.wait(Until.findObject(selector), 5_000)?.let { return it }
        dismissNotRespondingDialogs()
        return device.wait(Until.findObject(selector), 5_000) ?: throw AssertionError("$what not found\n${windowFocus()}")
    }

    private fun clickFresh(find: () -> UiObject2) {
        repeat(2) {
            try { find().click(); return } catch (_: StaleObjectException) { device.waitForIdle(500) }
        }
        find().click()
    }

    private fun gone(selector: BySelector, what: String) =
        assertTrue("$what still shown", device.wait(Until.gone(selector), 5_000))

    /** An emoji of the grid, found by what it shows. The tabs show other emojis (🕒 😀 👋 🐻 🍔 🚗 ⚽ 💡 ❤ 🏁): never look for those. */
    private fun cell(glyph: String): UiObject2 = obj(By.text(glyph), "emoji $glyph in the grid")

    private fun typeInTheGrid(glyph: String) = clickFresh { cell(glyph) }

    private fun openFromDictation() {
        clickFresh { obj(res("open_emoji"), "emoji button") }
        obj(res("emoji_picker"), "emoji panel")
    }

    @Test
    fun theDictationViewOpensThePanelAnEmojiIsInsertedAndTheBackKeyBringsTheDictationBack() {
        app.settings.recentEmojis = emptyList()
        launchFields()
        assertNotNull("the microphone view first", device.wait(Until.findObject(res("mic")), 5_000))
        openFromDictation()
        assertNull("the dictation view is replaced by the panel", device.findObject(res("mic")))
        // Without recents the panel opens on the smileys: the second one is 😃.
        typeInTheGrid("😃")
        assertEquals("😃", field1())
        assertNotNull("the panel stays open", device.findObject(res("emoji_picker")))
        typeInTheGrid("😃")
        assertEquals("😃😃", field1())
        clickFresh { obj(res("emoji_back"), "back key") }
        assertNotNull("the dictation is back", device.wait(Until.findObject(res("mic")), 5_000))
        gone(res("emoji_picker"), "emoji panel")
        assertEquals(listOf("😃"), app.settings.recentEmojis)
    }

    @Test
    fun theRecentsOpenFirstAndKeepTheOrderOfUse() {
        app.settings.recentEmojis = listOf("😃", "😄")
        launchFields()
        openFromDictation()
        waitFor("the recents tab is shown", 5_000) { onMain { service.emojiPickerForTest()?.currentCategory } == null }
        typeInTheGrid("😄")
        assertEquals(listOf("😄", "😃"), app.settings.recentEmojis)
    }

    @Test
    fun anEmojiOfSeveralCharactersIsInsertedWholeAndBackspaceRemovesItInOneTap() {
        // The recents are the shortest way to a family and a flag in the grid.
        app.settings.recentEmojis = listOf(rainbowFlag, flagFr, family)
        launchFields()
        openFromDictation()
        typeInTheGrid(family)
        assertEquals(family, field1())
        typeInTheGrid(flagFr)
        assertEquals(family + flagFr, field1())
        typeInTheGrid(rainbowFlag)
        assertEquals(family + flagFr + rainbowFlag, field1())
        clickFresh { obj(res("emoji_backspace"), "backspace") }
        waitFor("rainbow flag removed whole", 5_000, details = { field1().map { it.code.toString(16) }.toString() }) { field1() == family + flagFr }
        clickFresh { obj(res("emoji_backspace"), "backspace") }
        waitFor("flag removed whole", 5_000) { field1() == family }
        clickFresh { obj(res("emoji_backspace"), "backspace") }
        waitFor("family removed whole", 5_000) { field1() == "" }
    }

    @Test
    fun anEmojiGoesAtTheCursorNotAtTheEnd() {
        app.settings.recentEmojis = listOf("😃")
        launchFields()
        scenario.onActivity { it.field1.setText("ab"); it.field1.setSelection(1) }
        openFromDictation()
        typeInTheGrid("😃")
        assertEquals("a😃b", field1())
    }

    @Test
    fun theTextKeysOpenThePanelAndTheBackKeyLeadsBackToThem() {
        app.settings.recentEmojis = listOf("😃")
        launchFields()
        onMain { service.setTextKeyboardShown(true) }
        obj(res("letters"), "letters")
        clickFresh { obj(res("open_emoji_header"), "emoji button of the text keys") }
        obj(res("emoji_picker"), "emoji panel")
        typeInTheGrid("😃")
        assertEquals("😃", field1())
        clickFresh { obj(res("emoji_back"), "back key") }
        obj(res("letters"), "the text keys are back")
        assertNull("no emoji panel", device.findObject(res("emoji_picker")))
    }

    @Test
    fun theEmojiButtonIsNotTheKeyboardButton() {
        launchFields()
        val emoji = obj(res("open_emoji"), "emoji button")
        val keys = obj(res("open_keys"), "keys button")
        assertTrue("different places", emoji.visibleBounds != keys.visibleBounds)
        assertTrue("different names", emoji.contentDescription != keys.contentDescription)
        // each on its own side of the microphone
        val mic = obj(res("mic"), "microphone").visibleBounds
        assertTrue(emoji.visibleBounds.right <= mic.left)
        assertTrue(keys.visibleBounds.left >= mic.right)
    }

    @Test
    fun everyEmojiIsReadAloudByItsName() {
        app.settings.recentEmojis = listOf(flagFr, family)
        launchFields()
        openFromDictation()
        val lang = Locale.getDefault()
        val flag = Emoji(flagFr, "flag: France", "drapeau : France")
        val fam = Emoji(family, "family: man, woman, girl, boy", "famille : homme, femme, fille et garçon")
        assertNotNull("flag read as ${flag.name(lang)}", cell(flagFr).also { assertEquals(flag.name(lang), it.contentDescription) })
        assertEquals(fam.name(lang), cell(family).contentDescription)
    }

    /** No key of the bottom row is smaller than another: the four punctuation keys are equal, the others at least as wide. */
    private fun assertBottomRowKeysEqual() {
        val ids = listOf("key_comma", "key_period", "key_question", "key_exclaim")
        val punctuation = ids.map { obj(res(it), it).visibleBounds }
        val others = listOf("key_space", "key_backspace", "key_enter").map { obj(res(it), it).visibleBounds }
        val all = punctuation + others
        assertTrue("same height: ${all.map { it.height() }}", all.map { it.height() }.distinct().size == 1)
        assertTrue("equal punctuation keys: ${punctuation.map { it.width() }}", punctuation.map { it.width() }.let { w -> w.max() - w.min() <= 1 })
        for (o in others) assertTrue("a key smaller than the others: ${o.width()} < ${punctuation[0].width()}", o.width() >= punctuation[0].width() - 1)
    }

    @Test
    fun noKeyOfTheBottomRowIsSmallerInPortrait() {
        launchFields()
        obj(res("open_emoji"), "emoji button")
        assertBottomRowKeysEqual()
    }

    @Test
    fun noKeyOfTheBottomRowIsSmallerInLandscape() {
        launchFields()
        try {
            device.setOrientationLeft()
            device.waitForIdle()
            ensureKeyboardShown()
            waitFor("keyboard shown in landscape", 10_000) { onMain { service.isInputViewShown } }
            obj(res("open_emoji"), "emoji button")
            assertBottomRowKeysEqual()
        } finally {
            // Back to portrait before the activity closes: see RotationInstrumentedTest (issue #117).
            device.setOrientationNatural()
            device.waitForIdle()
        }
    }
}
