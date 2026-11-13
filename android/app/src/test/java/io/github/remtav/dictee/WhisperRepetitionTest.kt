package io.github.remtav.dictee

import io.github.remtav.dictee.text.WhisperRepetition
import io.github.remtav.dictee.text.WhisperRepetition.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #49: repetition filter for Whisper with a short tail padding (300). */
class WhisperRepetitionTest {
    /** Detection without the token ceiling (0 = unknown). */
    private fun rule(text: String) = WhisperRepetition.detect(text, tokens = 0, ceiling = 0)

    private fun assertKept(text: String) {
        assertNull("detected: $text", rule(text))
        assertNull("cut: $text", WhisperRepetition.cut(text))
    }

    // ------------------------------------------------------------ examples of the issue (#40, section 7.3)

    @Test fun sentenceSaidTwice() {
        val t = "Je m'étonne également. Je m'étonne également."
        assertEquals(Rule.DOUBLED, rule(t))
        assertEquals("Je m'étonne également.", WhisperRepetition.cut(t))
    }

    @Test fun sentenceRestartedAtTheCeiling() {
        val t = "Elle s'est divisée en deux parties. Elle s'est divisée en deux parties. Elle s"
        assertEquals(Rule.REPEATED_BLOCK, rule(t))
        // At the ceiling the ceiling rule fires first; the cut is the same.
        assertEquals(Rule.CEILING, WhisperRepetition.detect(t, tokens = 18, ceiling = 18))
        assertEquals("Elle s'est divisée en deux parties.", WhisperRepetition.cut(t))
    }

    @Test fun endRepeatsTheStart() {
        val t = "Du reste sa mère. Du"
        assertEquals(Rule.END_REPEATS_START, rule(t))
        assertEquals("Du reste sa mère.", WhisperRepetition.cut(t))
        assertEquals("Du reste sa mère.", WhisperRepetition.cut("Du reste sa mère. Du reste"))
    }

    @Test fun repeatedBlockInsideTheTextKeepsTheRest() {
        val t = "Les rebelles. La sorcière décide La sorcière décide de rentrer."
        assertEquals(Rule.REPEATED_BLOCK, rule(t))
        assertEquals("Les rebelles. La sorcière décide de rentrer.", WhisperRepetition.cut(t))
    }

    @Test fun blockRepeatedThreeTimesThenCutOff() {
        val t = "On part demain matin. On part demain matin. On part demain matin. On part"
        assertEquals("On part demain matin.", WhisperRepetition.cut(t))
    }

    @Test fun restartStoppedInsideAWordIsCutAtTheCeiling() {
        // Two outputs at the ceiling in the 172-clip bench (#49): the restart stops inside its last word.
        val t = "Le mode de paiement des impôts varie selon les localités. Le mode de paiement des impôts varie selon les local"
        assertEquals(Rule.CEILING, WhisperRepetition.detect(t, tokens = 32, ceiling = 32))
        assertNull("detection stays the reference one", rule(t))
        assertEquals("Le mode de paiement des impôts varie selon les localités.", WhisperRepetition.cut(t))
        assertEquals("Le parc se situe au cœur de la réserve des biosphères du Cap Horn.",
            WhisperRepetition.cut("Le parc se situe au cœur de la réserve des biosphères du Cap Horn. Le parc se situe au cœur de la réserve des biosph"))
    }

    @Test fun ceilingWithoutStructureIsDetectedButNotCut() {
        val t = "Les rebelles. La sorcière décide de rentrer au"
        assertEquals(Rule.CEILING, WhisperRepetition.detect(t, tokens = 29, ceiling = 29))
        assertEquals(Rule.CEILING, WhisperRepetition.detect(t, tokens = 28, ceiling = 29))
        assertNull(WhisperRepetition.detect(t, tokens = 27, ceiling = 29))
        assertNull(WhisperRepetition.cut(t))
    }

    @Test fun ceilingIsSherpasSixTokensPerSecondOfAudio() {
        assertEquals(30, WhisperRepetition.tokenCeiling(5 * 16000, 16000))
        assertEquals(6, WhisperRepetition.tokenCeiling(16000, 16000))
        assertEquals(3, WhisperRepetition.tokenCeiling(8000, 16000))
        assertEquals(0, WhisperRepetition.tokenCeiling(0, 16000))
        assertEquals(30, WhisperRepetition.tokenCeiling(5 * 48000, 48000))
        // Unknown ceiling: the rule is off.
        assertNull(WhisperRepetition.detect("Bonjour.", tokens = 3, ceiling = 0))
    }

    // ------------------------------------------------------------ wanted repetitions: never cut

    @Test fun wantedRepetitionsAreKept() {
        for (t in listOf(
            "oui oui", "Oui, oui.", "Oui oui.", "Oui, oui", "non non non",
            "cinq cinq cinq", "Cinq, cinq, cinq.", "Cinq cinq cinq",
            "vous vous lavez", "Vous vous lavez les mains.", "Nous nous sommes vus.",
            "C'est ça, c'est ça.", "C'est ça c'est ça", "Merci, merci.", "Très très bien.",
            "Il faut faire faire les devoirs.", "peut-être peut-être",
        )) assertKept(t)
    }

    @Test fun sameWordAtStartAndEndWithFinalPunctuationIsKept() {
        for (t in listOf(
            "Demain, on verra demain.", "Paris est belle, Paris.", "Merci pour tout, merci !",
            "Bonjour à tous. Bonjour.", "Non, c'est non.", "Encore une fois, encore ?", "Toujours plus, toujours…",
        )) assertKept(t)
    }

    @Test fun endRepeatsStartNeedsASentenceEndBeforeTheRestart() {
        // Same word at both ends, no final punctuation, but no sentence end before it: speech, not a restart.
        assertKept("Merci beaucoup pour tout merci")
        assertKept("Bonjour à tous, bonjour")
    }

    // ------------------------------------------------------------ one- and two-word texts (risk named in the issue)

    @Test fun oneOrTwoWordsAreNeverCutByTheEndRule() {
        for (t in listOf("Friperie", "Friperie.", "friperie", "oui", "Oui.", "Bonjour", "Oui merci", "Oui. Merci",
            "Merci. Merci beaucoup", "Allô allô", "Allô. Allô", "Bon. Bon voyage")) {
            assertEquals("end rule on « $t »", true, rule(t) != Rule.END_REPEATS_START)
        }
        assertKept("Friperie")
        assertKept("Friperie.")
        assertKept("Oui merci")
        assertKept("Allô allô")
        assertKept("Allô, allô")
    }

    @Test fun endRuleIsOffBelowFourWords() {
        // Three words: one matching word out of three is no evidence (the rule would cut a third of the text).
        assertKept("Oui. Non. Oui")
        assertKept("Allô. Oui allô")
        assertKept("Merci. Bon. Merci")
        // Four words: on, with every other guard.
        assertEquals(Rule.END_REPEATS_START, rule("Merci. C'est bon. Merci"))
        assertEquals("Merci. C'est bon.", WhisperRepetition.cut("Merci. C'est bon. Merci"))
    }

    @Test fun singleWordSentenceSaidTwiceIsDoubled() {
        // A one-word dictation that Whisper repeats after a full stop (the phone test « friperie »).
        assertEquals(Rule.DOUBLED, rule("Friperie. Friperie."))
        assertEquals("Friperie.", WhisperRepetition.cut("Friperie. Friperie."))
        assertEquals("Friperie !", WhisperRepetition.cut("Friperie ! Friperie !"))
    }

    @Test fun emptyAndPunctuationOnly() {
        assertKept("")
        assertKept("   ")
        assertKept("…")
        assertKept(". .")
    }

    // ------------------------------------------------------------ text kept as written

    @Test fun cutKeepsSpellingAndPunctuation() {
        assertEquals("« Peut-être », dit-il.", WhisperRepetition.cut("« Peut-être », dit-il. « Peut-être », dit-il."))
        assertEquals("L’été arrive.", WhisperRepetition.cut("L’été arrive. L'été arrive."))
    }

    @Test fun normalisationIsTheBenchOne() {
        assertEquals(listOf("l'été", "peut", "être", "123"), WhisperRepetition.normalize("L’été, peut-être 123 !"))
        assertEquals(listOf("ça", "va"), WhisperRepetition.normalize("Ça va ?"))
    }
}
