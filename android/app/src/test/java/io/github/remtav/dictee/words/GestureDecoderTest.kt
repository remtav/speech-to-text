package io.github.remtav.dictee.words

import io.github.remtav.dictee.ime.LayoutKind
import io.github.remtav.dictee.ime.words.Fold
import io.github.remtav.dictee.ime.words.GestureDecoder
import io.github.remtav.dictee.ime.words.Lexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GestureDecoderTest {
    /** The values the keyboard uses on a phone of density 2.75 (1080 pixels wide). */
    private fun decoder(kind: LayoutKind, vararg langs: String) = GestureDecoder(
        keys = Geometry.keys(kind),
        lexicons = langs.map { Shipped.lexicon(it) },
        frequencyFactor = 2.5 * 2.75,
        minPointDistance = (5 * 2.75).toInt(),
        startKeyProximityThreshold = (60 * 2.75).toInt(),
    )

    /**
     * A finger sliding over [word]: straight from one letter's key to the next, a point every 20 pixels
     * or so. The finger aims at each key up to [noise] pixels off its centre (seeded: the same path at
     * every run). A real finger also rounds its turns and overshoots; this does not.
     */
    private fun slide(decoder: GestureDecoder, keys: List<GestureDecoder.Key>, word: String, noise: Int, random: Random): List<String> {
        decoder.clearGesture()
        fun jitter() = if (noise == 0) 0 else random.nextInt(2 * noise + 1) - noise
        val targets = Fold.of(word).mapNotNull { c -> keys.firstOrNull { it.char == c } }
            .fold(ArrayList<GestureDecoder.Key>()) { acc, k -> if (acc.lastOrNull() !== k) acc += k; acc }
            .map { (it.centerX + jitter()) to (it.centerY + jitter()) }
        for (i in targets.indices) {
            val (x0, y0) = targets[i]
            if (i == targets.lastIndex) { decoder.addPoint(x0, y0); break }
            val (x1, y1) = targets[i + 1]
            val steps = maxOf(1, (Math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()) / 20).toInt())
            for (s in 0 until steps) decoder.addPoint(x0 + (x1 - x0) * s / steps, y0 + (y1 - y0) * s / steps)
        }
        return decoder.candidates()
    }

    @Test
    fun aCleanSlideGivesTheWord() {
        val keys = Geometry.keys(LayoutKind.QWERTY)
        val d = decoder(LayoutKind.QWERTY, "fr")
        val random = Random(1)
        for (word in listOf("bonjour", "merci", "maison", "demain", "toujours", "déjà", "français")) {
            assertEquals(word, slide(d, keys, word, 0, random).firstOrNull())
        }
    }

    @Test
    fun cornersKeepTheEndsAndTheTurns() {
        val xs = intArrayOf(0, 50, 100, 100, 100)
        val ys = intArrayOf(0, 0, 0, 50, 100)
        val out = ShortArray(10)
        val n = GestureDecoder.pathCorners(xs, ys, 5, out, 0)
        assertEquals(listOf<Short>(0, 0, 100, 0, 100, 100), out.take(n))
    }

    @Test
    fun aWordWithoutSlideGivesNothing() {
        val d = GestureDecoder(Geometry.keys(LayoutKind.QWERTY), listOf(Lexicon.of(listOf("bonjour" to 150))), 6.9, 13, 165)
        assertEquals(emptyList<String>(), d.candidates())
        d.addPoint(10, 10)
        assertEquals(emptyList<String>(), d.candidates())
    }

    /**
     * How often the word meant comes first, and among the first four, for the 1,500 most frequent words
     * of three letters or more, slid with a finger off by up to a fifth of a key. Printed for DECISIONS.md;
     * the thresholds only guard against a regression.
     */
    @Test
    fun accuracyOnSimulatedSlides() {
        for ((lang, kind) in listOf("fr" to LayoutKind.QWERTY, "fr" to LayoutKind.AZERTY, "en" to LayoutKind.QWERTY, "es" to LayoutKind.QWERTY, "pt" to LayoutKind.QWERTY)) {
            val lexicon = Shipped.lexicon(lang)
            val keys = Geometry.keys(kind)
            val start = System.nanoTime()
            val d = decoder(kind, lang)
            val buildMs = (System.nanoTime() - start) / 1e6
            val words = (0 until lexicon.size).filter { lexicon.length(it) >= 3 && !lexicon.isOffensive(it) && lexicon.word(it).all { c -> c.isLetter() } }
                .sortedByDescending { lexicon.frequency(it) }.take(1500).map { lexicon.word(it) }
            val random = Random(42)
            var first = 0
            var top4 = 0
            val t0 = System.nanoTime()
            for (word in words) {
                val found = slide(d, keys, word, Geometry.KEY_W / 5, random)
                if (found.firstOrNull() == word) first++
                if (word in found.take(4)) top4++
            }
            val perSlide = (System.nanoTime() - t0) / 1e6 / words.size
            println("slide %s %s: %d words offered, built in %.0f ms, first %.1f %%, first four %.1f %%, %.2f ms per slide".format(
                lang, kind, d.wordCount, buildMs, 100.0 * first / words.size, 100.0 * top4 / words.size, perSlide))
            assertTrue("$lang $kind first=$first", first >= words.size * 60 / 100)
            assertTrue("$lang $kind top4=$top4", top4 >= words.size * 80 / 100)
        }
    }
}
