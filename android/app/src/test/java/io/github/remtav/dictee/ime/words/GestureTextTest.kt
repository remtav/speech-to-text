package io.github.remtav.dictee.ime.words

import io.github.remtav.dictee.ime.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #71, lot 2: the capitals and the space in front of a traced word. */
class GestureTextTest {

    @Test
    fun shiftGivesTheCapitals() {
        assertEquals("bonjour", GestureText.withCase("bonjour", ShiftState.OFF))
        assertEquals("Bonjour", GestureText.withCase("bonjour", ShiftState.ONCE))
        assertEquals("ÉCOLE", GestureText.withCase("école", ShiftState.LOCKED))
        // A capital the list already has stays.
        assertEquals("Paris", GestureText.withCase("Paris", ShiftState.OFF))
    }

    @Test
    fun aSpaceAfterAWordOrASentenceNotAtTheStartOrInsideAWord() {
        assertEquals("bonjour", GestureText.commit("", "bonjour"))
        assertEquals("merci", GestureText.commit("bonjour ", "merci"))
        assertEquals(" merci", GestureText.commit("bonjour", "merci"))
        assertEquals(" merci", GestureText.commit("bonjour,", "merci"))
        assertEquals(" Merci", GestureText.commit("Fini.", "Merci"))
        assertEquals(" ans", GestureText.commit("2", "ans"))
        assertEquals("ami", GestureText.commit("l'", "ami"))
        assertEquals("ami", GestureText.commit("l’", "ami"))
        assertEquals("être", GestureText.commit("peut-", "être"))
        assertEquals("oui", GestureText.commit("(", "oui"))
        assertEquals("oui", GestureText.commit("« ", "oui"))
        assertEquals("oui", GestureText.commit("ligne\n", "oui"))
    }
}
