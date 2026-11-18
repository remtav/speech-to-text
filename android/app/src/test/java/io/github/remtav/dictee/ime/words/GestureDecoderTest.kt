package io.github.remtav.dictee.ime.words

import io.github.remtav.dictee.ime.LayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Issue #71, lot 2: gesture typing on the real word lists, with traces drawn by a program (straight lines from
 * key to key, aimed a little off the centre, and rounded corners). A real finger is sloppier: these numbers are
 * an upper bound, the floor that must not drop.
 */
class GestureDecoderTest {

    private val qwerty = KeyGeometry.of(LayoutKind.QWERTY)
    private val azerty = KeyGeometry.of(LayoutKind.AZERTY)

    private fun decoder(geometry: KeyGeometry, vararg codes: String) =
        GestureDecoder(codes.map { WordListsFixture.real(it) }, geometry)

    /** The trace of [word]: through the centre of each key, each aimed up to [aim] key widths off. */
    private fun trace(word: String, geometry: KeyGeometry, random: Random = Random(1), aim: Float = 0f, rounded: Boolean = false): List<KeyPoint> {
        val keys = ArrayList<KeyPoint>()
        for (c in WordFold.fold(word)) {
            val k = geometry.centre(c) ?: continue
            val p = KeyPoint(k.x + (random.nextFloat() * 2 - 1) * aim, k.y + (random.nextFloat() * 2 - 1) * aim)
            if (keys.isEmpty() || keys.last().distanceTo(p) > 0.3f) keys += p
        }
        var points: List<KeyPoint> = GestureDecoder.resample(keys, 0.1f, 1000)
        if (rounded) repeat(3) { points = chaikin(points) }
        return points
    }

    /** One pass of corner cutting: the finger turns in a curve, not at a point. */
    private fun chaikin(p: List<KeyPoint>): List<KeyPoint> {
        if (p.size < 3) return p
        val out = ArrayList<KeyPoint>(p.size * 2)
        out += p.first()
        for (i in 0 until p.size - 1) {
            val a = p[i]
            val b = p[i + 1]
            out += KeyPoint(0.75f * a.x + 0.25f * b.x, 0.75f * a.y + 0.25f * b.y)
            out += KeyPoint(0.25f * a.x + 0.75f * b.x, 0.25f * a.y + 0.75f * b.y)
        }
        out += p.last()
        return out
    }

    @Test
    fun geometryMatchesTheKeyboardView() {
        // QWERTY: a is half a key in, z a key and a half plus half a key.
        assertEquals(0.5f, qwerty.centre('q')!!.x, 1e-4f)
        assertEquals(1.0f, qwerty.centre('a')!!.x, 1e-4f)
        assertEquals(2.0f, qwerty.centre('z')!!.x, 1e-4f)
        assertEquals(qwerty.centre('a')!!.y + KeyGeometry.ROW_HEIGHT, qwerty.centre('z')!!.y, 1e-4f)
        // AZERTY: the middle row is full, the last one has wider keys (9 weights for 10 key widths).
        assertEquals(0.5f, azerty.centre('q')!!.x, 1e-4f)
        assertEquals(9.5f, azerty.centre('m')!!.x, 1e-4f)
        assertEquals(2.0f * 10f / 9f, azerty.centre('w')!!.x, 1e-4f)
    }

    @Test
    fun fromBoundsDividesByTheKeyWidth() {
        val g = KeyGeometry.fromBounds(mapOf('e' to floatArrayOf(200f, 0f, 300f, 120f), 'a' to floatArrayOf(50f, 120f, 150f, 240f)))!!
        assertEquals(KeyPoint(2.5f, 0.6f), g.centre('e'))
        assertEquals(KeyPoint(1.0f, 1.8f), g.centre('a'))
    }

    @Test
    fun commonWordsFromACleanTrace() {
        val fr = decoder(qwerty, "fr")
        for (w in listOf("bonjour", "merci", "maison", "demain", "pour", "avec", "rien")) {
            assertEquals(w, fr.decode(trace(w, qwerty)).firstOrNull())
        }
        val en = decoder(qwerty, "en")
        for (w in listOf("hello", "the", "thanks", "tomorrow", "people")) {
            assertEquals(w, en.decode(trace(w, qwerty)).firstOrNull())
        }
    }

    @Test
    fun azertyTracesAreReadOnTheAzertyKeys() {
        val fr = decoder(azerty, "fr")
        for (w in listOf("bonjour", "maison", "quand", "aimer")) {
            assertEquals(w, fr.decode(trace(w, azerty)).firstOrNull())
        }
    }

    @Test
    fun accentsComeBackFromTheList() {
        val fr = decoder(qwerty, "fr")
        assertEquals("école", fr.decode(trace("ecole", qwerty)).firstOrNull())
        assertEquals("déjà", fr.decode(trace("deja", qwerty)).firstOrNull())
    }

    @Test
    fun doubledLettersAndApostrophes() {
        val fr = decoder(qwerty, "fr")
        assertTrue(fr.decode(trace("belle", qwerty)).toString(), "belle" in fr.decode(trace("belle", qwerty)).take(2))
        val words = fr.decode(trace("aujourd'hui", qwerty))
        assertEquals(words.toString(), "aujourd'hui", words.firstOrNull())
    }

    /**
     * Moves sampled every 5 ms, as the emulator or a phone sends them, with the move on each corner lost (input
     * resampling can drop it): a trace this dense still reads right.
     */
    @Test
    fun aLostMoveOnEachCornerStillReadsTheWord() {
        val fr = decoder(azerty, "fr")
        val keys = "maison".map { azerty.centre(it)!! }
        val steps = 40
        val points = ArrayList<KeyPoint>()
        points += keys[0]
        for (i in 0 until keys.size - 1) for (j in 1..steps) {
            if (j == steps && i < keys.size - 2) continue
            val a = keys[i]
            val b = keys[i + 1]
            points += KeyPoint(a.x + (b.x - a.x) * j / steps, a.y + (b.y - a.y) * j / steps)
        }
        assertEquals("maison", fr.decode(points).firstOrNull())
    }

    @Test
    fun aTapThatMovedIsNoWord() {
        val fr = decoder(qwerty, "fr")
        val a = qwerty.centre('g')!!
        assertTrue(fr.decode(listOf(a, KeyPoint(a.x + 0.3f, a.y))).isEmpty())
        assertTrue(fr.decode(listOf(a)).isEmpty())
    }

    @Test
    fun theOtherWordsAreOfferedToo() {
        val fr = decoder(qwerty, "fr")
        val words = fr.decode(trace("tout", qwerty), limit = 4)
        assertTrue(words.toString(), words.size in 2..4)
        assertEquals(words.size, words.distinct().size)
    }

    @Test
    fun offensiveWordsAreNeverGuessed() {
        val lex = WordListsFixture.small("merde!" to 200, "merle" to 50)
        val words = GestureDecoder(listOf(lex), qwerty).decode(trace("merde", qwerty))
        assertTrue(words.toString(), "merde" !in words)
    }

    /** Share of the [count] most frequent words of [code] (2 letters or more) found first, and among the first four. */
    private fun accuracy(code: String, geometry: KeyGeometry, count: Int, aim: Float, rounded: Boolean): Pair<Float, Float> {
        val lex = WordListsFixture.real(code)
        val top = (0 until lex.size).filter { !lex.isOffensive(it) && lex.keyLength(it) >= 2 }
            .sortedByDescending { lex.frequency(it) }.take(count).map { lex.word(it) }
        val d = GestureDecoder(listOf(lex), geometry)
        val random = Random(71)
        var first = 0
        var four = 0
        for (w in top) {
            val got = d.decode(trace(w, geometry, random, aim, rounded))
            // Words written the same on the keys ("ou" / "où") count as found when either comes first.
            val same = got.map { WordFold.fold(it) }
            if (same.firstOrNull() == WordFold.fold(w)) first++
            if (WordFold.fold(w) in same) four++
        }
        val result = first.toFloat() / top.size to four.toFloat() / top.size
        println("$code ${if (geometry === azerty) "AZERTY" else "QWERTY"} aim=$aim rounded=$rounded: first ${"%.1f".format(result.first * 100)} %, four ${"%.1f".format(result.second * 100)} %")
        return result
    }

    @Test
    fun accuracyOnTheMostFrequentWords() {
        // Measured on 2026-11-18 (aim 0.3 key, 2 000 words): fr 94 % first, 99.8 % among four; en 96 %, 99.8 %.
        val cases = listOf("fr" to qwerty, "fr" to azerty, "en" to qwerty, "es" to qwerty, "pt" to qwerty)
        for ((code, geometry) in cases) {
            val (first, four) = accuracy(code, geometry, 2000, aim = 0.3f, rounded = true)
            assertTrue("$code first: $first", first >= 0.85f)
            assertTrue("$code four: $four", four >= 0.95f)
        }
    }

    @Test
    fun decodingIsFastEnoughForAKeyboard() {
        val d = decoder(qwerty, "fr", "en", "es", "pt")
        val traces = listOf("bonjour", "maintenant", "tomorrow", "gracias", "obrigado", "ce").map { trace(it, qwerty) }
        traces.forEach { d.decode(it) }
        val started = System.nanoTime()
        repeat(5) { traces.forEach { d.decode(it) } }
        val ms = (System.nanoTime() - started) / 1_000_000.0 / (5 * traces.size)
        println("gesture decode, four languages: ${"%.1f".format(ms)} ms per word")
        assertTrue("$ms ms", ms < 150)
    }
}
