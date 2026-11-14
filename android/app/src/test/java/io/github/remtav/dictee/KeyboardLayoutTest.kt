package io.github.remtav.dictee

import io.github.remtav.dictee.ime.KeyLanguage
import io.github.remtav.dictee.ime.KeyboardLayout
import io.github.remtav.dictee.ime.LayoutKind
import io.github.remtav.dictee.ime.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #21: the layouts, the accents by long press, Shift and its lock. */
class KeyboardLayoutTest {

    private val fr = setOf(KeyLanguage.FRENCH)
    private val accentedLower = "éèêëàâæçîïôœùûüÿ"
    private val accentedUpper = accentedLower.uppercase()

    @Test
    fun everyFrenchAccentIsReachableInBothLayouts() {
        for (kind in LayoutKind.entries) {
            val reachable = KeyboardLayout.reachable(kind, setOf(KeyLanguage.FRENCH))
            for (c in accentedLower + accentedUpper) {
                assertTrue("$c missing in $kind", c.toString() in reachable)
            }
        }
    }

    @Test
    fun accentsOfEachKeyMatchTheIssue() {
        assertEquals(listOf("é", "è", "ê", "ë"), KeyboardLayout.accentsFor("e", languages = fr))
        assertEquals(listOf("à", "â", "æ"), KeyboardLayout.accentsFor("a", languages = fr))
        assertEquals(listOf("ç"), KeyboardLayout.accentsFor("c", languages = fr))
        assertEquals(listOf("î", "ï"), KeyboardLayout.accentsFor("i", languages = fr))
        assertEquals(listOf("ô", "œ"), KeyboardLayout.accentsFor("o", languages = fr))
        assertEquals(listOf("ù", "û", "ü"), KeyboardLayout.accentsFor("u", languages = fr))
        assertEquals(listOf("ÿ"), KeyboardLayout.accentsFor("y", languages = fr))
        assertEquals(listOf("É", "È", "Ê", "Ë"), KeyboardLayout.accentsFor("e", upper = true, languages = fr))
        assertEquals(listOf("Œ"), KeyboardLayout.accentsFor("o", upper = true, languages = fr).drop(1))
        assertTrue(KeyboardLayout.accentsFor("z", languages = fr).isEmpty())
    }

    @Test
    fun layoutsHaveAllTwentySixLettersOnce() {
        for (kind in LayoutKind.entries) {
            val letters = KeyboardLayout.letterRows(kind).flatten()
            assertEquals(('a'..'z').map { it.toString() }.toSet(), letters.toSet())
            assertEquals(26, letters.size)
        }
    }

    @Test
    fun qwertyIsTheDefaultAndAzertyDiffers() {
        assertEquals(LayoutKind.QWERTY, LayoutKind.fromName(null))
        assertEquals(LayoutKind.QWERTY, LayoutKind.fromName("nope"))
        assertEquals(LayoutKind.AZERTY, LayoutKind.fromName("AZERTY"))
        assertEquals("qwertyuiop".map { it.toString() }, KeyboardLayout.letterRows(LayoutKind.QWERTY)[0])
        assertEquals("azertyuiop".map { it.toString() }, KeyboardLayout.letterRows(LayoutKind.AZERTY)[0])
    }

    @Test
    fun symbolsPageHasDigitsAndTheListedSymbols() {
        val all = KeyboardLayout.symbolRows().flatten()
        for (c in "1234567890'\"-()@:;$%/&!?,.") assertTrue("$c missing", c.toString() in all)
    }

    @Test
    fun shiftCapitalisesOneLetterThenGoesBack() {
        var s = ShiftState.OFF.tapped(doubleTap = false)
        assertEquals(ShiftState.ONCE, s)
        assertEquals("E", KeyboardLayout.label("e", s))
        s = s.afterLetter()
        assertEquals(ShiftState.OFF, s)
        assertEquals("e", KeyboardLayout.label("e", s))
    }

    @Test
    fun doubleTapLocksAndStaysUntilTappedAgain() {
        var s = ShiftState.OFF.tapped(false).tapped(doubleTap = true)
        assertEquals(ShiftState.LOCKED, s)
        s = s.afterLetter().afterLetter()
        assertEquals(ShiftState.LOCKED, s)
        assertEquals(ShiftState.OFF, s.tapped(false))
    }

    @Test
    fun slowSecondTapCancelsShift() {
        assertEquals(ShiftState.OFF, ShiftState.ONCE.tapped(doubleTap = false))
    }
}
