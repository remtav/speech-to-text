package io.github.remtav.dictee.ime.words

import io.github.remtav.dictee.ime.FakeInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #71: the word before the cursor, picking a suggestion, the automatic correction and its undo. */
class WordTypingTest {

    @Test
    fun wordBeforeTheCursor() {
        assertEquals("maison", WordTyping.wordBefore("Une maison"))
        assertEquals("aujourd'hui", WordTyping.wordBefore("C'est aujourd'hui"))
        assertEquals("l'ami", WordTyping.wordBefore("avec l'ami"))
        assertEquals("peut-ê", WordTyping.wordBefore("peut-ê"))
        assertEquals("", WordTyping.wordBefore("Une maison "))
        assertEquals("", WordTyping.wordBefore("fin."))
        assertEquals("", WordTyping.wordBefore(""))
        // Glued to a digit or a sign of an address: no suggestion.
        assertEquals("", WordTyping.wordBefore("abc123"))
        assertEquals("", WordTyping.wordBefore("123abc"))
        assertEquals("", WordTyping.wordBefore("@remi"))
        assertEquals("", WordTyping.wordBefore("site.com/pa"))
        // A quote or a parenthesis before is fine.
        assertEquals("bonj", WordTyping.wordBefore("«bonj"))
        assertEquals("bonj", WordTyping.wordBefore("(bonj"))
    }

    @Test
    fun pickReplacesTheWordAndAddsASpace() {
        val ic = FakeInputConnection("Je dis bonj")
        WordTyping().pick(ic, "bonj", "bonjour")
        assertEquals("Je dis bonjour ", ic.text)
        assertEquals(ic.text.length, ic.selStart)
    }

    @Test
    fun pickKeepsTheTextAfterTheCursor() {
        val ic = FakeInputConnection("un bonj à tous", cursor = 7)
        WordTyping().pick(ic, "bonj", "bonjour")
        assertEquals("un bonjour  à tous", ic.text)
        assertEquals("un bonjour ".length, ic.selStart)
    }

    @Test
    fun separatorCorrectsThenBackspaceUndoes() {
        val ic = FakeInputConnection("une ecole")
        val typing = WordTyping()
        assertTrue(typing.typeSeparator(ic, " ", "ecole", "école"))
        assertEquals("une école ", ic.text)
        assertTrue(typing.undoCorrection(ic))
        assertEquals("une ecole ", ic.text)
        // Only once: the next Backspace is an ordinary one.
        assertFalse(typing.undoCorrection(ic))
    }

    @Test
    fun noCorrectionJustTypesTheSeparator() {
        val ic = FakeInputConnection("une maison")
        val typing = WordTyping()
        assertFalse(typing.typeSeparator(ic, ",", "maison", null))
        assertEquals("une maison,", ic.text)
        assertFalse(typing.undoCorrection(ic))
    }

    @Test
    fun aStaleCorrectionIsNotApplied() {
        // The suggestions were computed for "ecol"; the field now ends with "ecole".
        val ic = FakeInputConnection("une ecole")
        assertFalse(WordTyping().typeSeparator(ic, " ", "ecol", "école"))
        assertEquals("une ecole ", ic.text)
    }

    @Test
    fun undoOnlyRightAfterTheCorrection() {
        val ic = FakeInputConnection("une ecole")
        val typing = WordTyping()
        typing.typeSeparator(ic, " ", "ecole", "école")
        ic.commitText("x", 1)
        assertFalse(typing.undoCorrection(ic))
        assertEquals("une école x", ic.text)
        typing.typeSeparator(ic, " ", "x", null)
        typing.forget()
        assertFalse(typing.undoCorrection(ic))
    }
}
