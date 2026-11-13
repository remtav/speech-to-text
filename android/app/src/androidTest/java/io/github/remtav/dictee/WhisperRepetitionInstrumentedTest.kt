package io.github.remtav.dictee

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.remtav.dictee.text.WhisperRepetition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #49: the repetition filter runs on Android's own regex engine, which is stricter than the JVM's
 * (it rejects the `(?U)` flag). The JVM unit tests cannot see that, so the same examples run here on the device.
 */
@RunWith(AndroidJUnit4::class)
class WhisperRepetitionInstrumentedTest {
    @Test fun normalizeWorksOnTheDevice() {
        assertEquals(listOf("l'été", "peut", "être", "123"), WhisperRepetition.normalize("L’été, peut-être 123 !"))
        assertEquals(listOf("ça", "va"), WhisperRepetition.normalize("Ça va ?"))
    }

    @Test fun doubledSentenceIsDetectedAndCutOnTheDevice() {
        val text = "Je m'étonne également. Je m'étonne également."
        val ceiling = WhisperRepetition.tokenCeiling(samples = 5 * 16_000, sampleRate = 16_000)
        assertNotNull(WhisperRepetition.detect(text, tokens = 12, ceiling = ceiling))
        assertEquals("Je m'étonne également.", WhisperRepetition.cut(text))
    }

    @Test fun plainSentenceIsLeftAloneOnTheDevice() {
        val ceiling = WhisperRepetition.tokenCeiling(samples = 5 * 16_000, sampleRate = 16_000)
        assertNull(WhisperRepetition.detect("Vous vous lavez les mains.", tokens = 8, ceiling = ceiling))
    }
}
