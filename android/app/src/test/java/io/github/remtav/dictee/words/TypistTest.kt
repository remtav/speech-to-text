package io.github.remtav.dictee.words

import io.github.remtav.dictee.ime.words.StripItem
import io.github.remtav.dictee.ime.words.Suggester
import io.github.remtav.dictee.ime.words.Typist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypistTest {
    private val learned = HashSet<String>()
    private val typist = Typist(Suggester(listOf(Shipped.lexicon("fr")), learned = { learned }), learn = { learned += it })

    private fun type(field: FakeField, text: String) { for (c in text) typist.type(field, c.toString()) }

    @Test
    fun correctsAWordWhenItEnds() {
        val field = FakeField()
        type(field, "je suis deja la. ")
        assertEquals("je suis déjà la. ", field.before)
        val other = FakeField()
        type(other, "bonjuor, ca va?")
        assertEquals("bonjour, ça va?", other.before)
    }

    @Test
    fun withoutCorrectionTheTextStaysAsTyped() {
        typist.autocorrect = false
        val field = FakeField()
        type(field, "bonjuor, ca va ")
        assertEquals("bonjuor, ca va ", field.before)
        // The bar still offers the word.
        val bar = FakeField("bonjuor")
        assertEquals(listOf(StripItem("bonjour", StripItem.Kind.WORD)), typist.strip(bar).take(1))
    }

    @Test
    fun backspaceGivesBackWhatWasTypedAndKeepsIt() {
        val field = FakeField()
        type(field, "bonkour ")
        assertEquals("bonjour ", field.before)
        assertEquals(listOf(StripItem("bonkour", StripItem.Kind.TYPED)), typist.strip(field))
        assertTrue(typist.backspace(field))
        assertEquals("bonkour ", field.before)
        assertEquals(setOf("bonkour"), learned)
        // Not corrected again, here or later.
        type(field, "bonkour ")
        assertEquals("bonkour bonkour ", field.before)
        // Nothing left to undo: the caller deletes one character.
        assertFalse(typist.backspace(field))
    }

    @Test
    fun theBarShowsTheTypedWordTheCorrectionAndAnotherWord() {
        val field = FakeField("il fait bo")
        val items = typist.strip(field)
        assertTrue(items.toString(), items.isNotEmpty() && items.all { it.kind == StripItem.Kind.WORD })
        val slip = FakeField("bonjuor")
        val bar = typist.strip(slip)
        assertEquals(StripItem("bonjuor", StripItem.Kind.TYPED), bar[0])
        assertEquals(StripItem("bonjour", StripItem.Kind.CORRECTION), bar[1])
        // Nothing while the cursor is inside a word, or after a space.
        assertEquals(emptyList<StripItem>(), typist.strip(FakeField("bon", after = "jour")))
        assertEquals(emptyList<StripItem>(), typist.strip(FakeField("bonjour ")))
    }

    @Test
    fun choosingAWordReplacesWhatIsTypedAndOwesASpace() {
        val field = FakeField("il fait bea")
        typist.pick(field, StripItem("beau", StripItem.Kind.WORD))
        assertEquals("il fait beau", field.before)
        type(field, "et")
        assertEquals("il fait beau et", field.before)
        // No space before a punctuation mark, and only one when Space is typed.
        val other = FakeField("bonj")
        typist.pick(other, StripItem("bonjour", StripItem.Kind.WORD))
        type(other, ", ")
        assertEquals("bonjour, ", other.before)
        val third = FakeField("bonj")
        typist.pick(third, StripItem("bonjour", StripItem.Kind.WORD))
        type(third, " a")
        assertEquals("bonjour a", third.before)
    }

    @Test
    fun choosingTheTypedWordKeepsIt() {
        val field = FakeField("bonkour")
        typist.pick(field, StripItem("bonkour", StripItem.Kind.TYPED))
        type(field, " ")
        assertEquals("bonkour ", field.before)
        assertEquals(setOf("bonkour"), learned)
    }

    @Test
    fun aSlidWordGetsItsSpaceAndCanBeChangedOrRemoved() {
        val field = FakeField("il")
        typist.slide(field, listOf("fait", "fut", "fit"), capital = false)
        assertEquals("il fait", field.before)
        assertEquals(listOf("fut", "fit"), typist.strip(field).map { it.text })
        typist.pick(field, StripItem("fut", StripItem.Kind.WORD))
        assertEquals("il fut", field.before)
        assertTrue("fait" in typist.strip(field).map { it.text })
        typist.slide(field, listOf("beau"), capital = false)
        assertEquals("il fut beau", field.before)
        assertTrue(typist.backspace(field))
        assertEquals("il fut", field.before)
        // At the start of a field: no space; with Shift: a capital.
        val start = FakeField()
        typist.slide(start, listOf("bonjour"), capital = true)
        assertEquals("Bonjour", start.before)
        type(start, "a")
        assertEquals("Bonjour a", start.before)
    }

    @Test
    fun enterCorrectsTheLastWord() {
        val field = FakeField("bonjuor")
        typist.beforeEnter(field)
        assertEquals("bonjour", field.before)
    }

    @Test
    fun aCursorMovedElsewhereDropsWhatWasPending() {
        val field = FakeField()
        type(field, "bonkour ")
        field.before = "autre chose "
        assertFalse(typist.backspace(field))
        assertEquals("autre chose ", field.before)
        val other = FakeField("bonj")
        typist.pick(other, StripItem("bonjour", StripItem.Kind.WORD))
        other.before = "ailleurs"
        type(other, "s")
        assertEquals("ailleurss", other.before)
    }
}
