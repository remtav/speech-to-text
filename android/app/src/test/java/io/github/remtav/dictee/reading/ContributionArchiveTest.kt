package io.github.remtav.dictee.reading

import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.WavReader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.util.Calendar
import java.util.zip.ZipFile

/**
 * Issue #15, lot 4, step 4.3: the contribution archive contains exactly what the screen announces (one
 * description, [ArchiveField]), no identifier, valid 16 kHz WAV files, and exists only after consent.
 */
class ContributionArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val material = ReadingTestSupport.material
    private val paragraphs = material.text.paragraphs
    private val facts = DeviceFacts("1.2.3", "Google Pixel 7", LocalDate.of(2026, 11, 13))

    /** A complete test where the recommended model loses every fourth word (WER above 20 %), a second model hears all. */
    private fun result(sampleRate: Int = 16000, recommendedFar: Boolean = true): ReadingResult {
        val session = ReadingSession(material, listOf(ReadingModel("rec", "Recommandé"), ReadingModel("other", "Autre")), "rec", "rec", Declaration.YES)
        session.accept()
        for (i in paragraphs.indices) {
            session.store(i, PcmAudio(FloatArray(sampleRate * 2 + i) { ((it % 50) - 25) / 100f }, sampleRate))
            session.next()
        }
        val r = session.score({ model, audio ->
            val i = audio.samples.size - sampleRate * 2
            if (model == "rec" && recommendedFar) paragraphs[i].split(' ').filterIndexed { n, _ -> n % 4 != 3 }.joinToString(" ")
            else paragraphs[i]
        })
        return checkNotNull(r)
    }

    private fun offer(r: ReadingResult = result(), declaration: Declaration = Declaration.YES) =
        ShareOffer(r, declaration, paragraphs, "Recommandé")

    /** The top-level keys of the metadata, in the order written (org.json on the JVM does not keep it). */
    private fun keysOf(text: String): List<String> =
        Regex("^  \"([^\"]+)\": ", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()

    private fun metadata(file: File): String =
        ZipFile(file).use { z -> z.getInputStream(z.getEntry("contribution.json")).readBytes().toString(Charsets.UTF_8) }

    @Test
    fun theArchiveContainsExactlyTheAnnouncedElements() {
        val o = offer().apply { consent = true; region = "  Bas-Saint-Laurent " }
        val announced = checkNotNull(o.preview(facts))
        val file = checkNotNull(o.createArchive(tmp.root, facts))
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            // Files: the metadata and one WAV per paragraph read, nothing else.
            assertEquals(announced.entryNames, names)
            assertEquals(listOf("contribution.json") + paragraphs.indices.map { "paragraphe-${it + 1}.wav" }, names)
            val text = zip.getInputStream(zip.getEntry("contribution.json")).readBytes().toString(Charsets.UTF_8)
            val json = JSONObject(text)
            // Metadata: exactly the elements of the screen's list, in order, neither more nor less.
            assertEquals(announced.fields.map { it.field.key }, keysOf(text))
            assertEquals(ArchiveField.entries.map { it.key }, keysOf(text))
            assertEquals(ArchiveField.entries.map { it.key }.toSet(), json.keys().asSequence().toSet())
            assertEquals(material.version, json.getString("version_du_texte"))
            assertEquals("2026-11-13", json.getString("date"))
            assertEquals("1.2.3", json.getString("version_de_l_application"))
            assertEquals("Google Pixel 7", json.getString("modele_du_telephone"))
            assertEquals("je parle un français québécois", json.getString("declaration"))
            assertEquals("Bas-Saint-Laurent", json.getString("region"))
            assertEquals("rec", json.getJSONObject("modele").getString("id"))
            assertEquals("Recommandé", json.getJSONObject("modele").getString("nom"))
            val texts = json.getJSONArray("texte_lu")
            assertEquals(paragraphs.size, texts.length())
            for (i in paragraphs.indices) assertEquals(paragraphs[i], texts.getJSONObject(i).getString("texte"))
            // The recommended model's transcription only (not the other model's, which heard everything).
            val r = o.result
            val tr = json.getJSONArray("transcription")
            for (i in paragraphs.indices) assertEquals(r.paragraphs[i].transcriptions.getValue("rec"), tr.getJSONObject(i).getString("texte"))
            assertFalse(json.toString().contains("\"other\""))
            val score = json.getJSONObject("score")
            val global = r.globalScores.getValue("rec")
            assertEquals(global.similarityPercent, score.getInt("ressemblance_pourcent"))
            assertEquals(global.errors, score.getInt("erreurs"))
            assertEquals(global.referenceWords, score.getInt("mots"))
            assertEquals(paragraphs.size, score.getJSONArray("par_paragraphe").length())
            // The audio element names exactly the WAV files of the archive.
            val audio = json.getJSONObject("audio")
            assertEquals(ContributionContent.WAV_FORMAT, audio.getString("format"))
            val listed = (0 until audio.getJSONArray("fichiers").length()).map { audio.getJSONArray("fichiers").getJSONObject(it).getString("fichier") }
            assertEquals(names.drop(1), listed)
        }
        // The metadata written is the one the screen was built from.
        assertEquals(announced.metadataJson(), ZipFile(file).use { z -> z.getInputStream(z.getEntry("contribution.json")).readBytes().toString(Charsets.UTF_8) })
    }

    @Test
    fun withoutRegionTheElementIsThereAndEmpty() {
        val o = offer().apply { consent = true }
        val file = checkNotNull(o.createArchive(tmp.root, facts))
        val text = metadata(file)
        val json = JSONObject(text)
        assertEquals(ArchiveField.entries.map { it.key }, keysOf(text))
        assertTrue(json.isNull("region"))
    }

    @Test
    fun noIdentifierAndNothingButTheKnownKeys() {
        val o = offer().apply { consent = true; region = "Abitibi" }
        val file = checkNotNull(o.createArchive(tmp.root, facts))
        val text = ZipFile(file).use { z -> z.getInputStream(z.getEntry("contribution.json")).bufferedReader().readText() }
        val allowed = ArchiveField.entries.map { it.key }.toSet() + setOf(
            "format", "fichiers", "paragraphe", "fichier", "duree_secondes", "texte",
            "ressemblance_pourcent", "erreurs", "mots", "par_paragraphe", "id", "nom",
        )
        val seen = mutableSetOf<String>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> for (k in v.keys()) { seen += k; walk(v.get(k)) }
                is JSONArray -> for (i in 0 until v.length()) walk(v.get(i))
            }
        }
        walk(JSONObject(text))
        assertTrue("clés imprévues : ${seen - allowed}", (seen - allowed).isEmpty())
        val lower = text.lowercase()
        for (t in listOf("android_id", "androidid", "serial", "imei", "uuid", "installation", "account", "@",
            "vocabulaire", "réglage", "settings", "fuseau", "timezone")) {
            assertFalse("« $t » dans l'archive", lower.contains(t))
        }
        // The date is the day only: no time of day anywhere.
        assertFalse(Regex("\\d{1,2}:\\d{2}").containsMatchIn(text))
        assertFalse(Regex("\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(text))
        // Same time on every zip entry (1980), so the archive does not carry the hour of the test either.
        ZipFile(file).use { z ->
            for (e in z.entries()) {
                val c = Calendar.getInstance().apply { timeInMillis = e.time }
                assertEquals(e.name, 1980, c.get(Calendar.YEAR))
                assertEquals(e.name, 0, c.get(Calendar.HOUR_OF_DAY))
                assertTrue("${e.name} : champ supplémentaire", e.extra == null || e.extra.isEmpty())
                assertNull(e.comment)
            }
            assertNull(z.comment)
        }
        // The file name carries the text version and the day, nothing else.
        assertEquals("kalmia-blabla-${material.version}-2026-11-13.zip", file.name)
    }

    @Test
    fun theWavFilesAreValid16kHzMono16Bit() {
        // Recorded at 48 kHz on purpose: the archive is 16 kHz whatever the recording.
        val o = offer(result(sampleRate = 48000)).apply { consent = true }
        val file = checkNotNull(o.createArchive(tmp.root, facts))
        ZipFile(file).use { z ->
            for (i in paragraphs.indices) {
                val bytes = z.getInputStream(z.getEntry("paragraphe-${i + 1}.wav")).readBytes()
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals("RIFF", String(bytes, 0, 4))
                assertEquals(bytes.size - 8, b.getInt(4))
                assertEquals("WAVE", String(bytes, 8, 4))
                assertEquals("fmt ", String(bytes, 12, 4))
                assertEquals(1, b.getShort(20).toInt()) // PCM
                assertEquals(1, b.getShort(22).toInt()) // mono
                assertEquals(16000, b.getInt(24))
                assertEquals(32000, b.getInt(28))
                assertEquals(16, b.getShort(34).toInt())
                assertEquals("data", String(bytes, 36, 4))
                assertEquals(bytes.size - 44, b.getInt(40))
                val back = WavReader.read(bytes.inputStream())
                assertEquals(16000, back.sampleRate)
                // 2 s (+ i samples at 48 kHz) of audio, resampled: about 32 000 samples.
                assertTrue(back.samples.size in 31_990..32_010)
            }
        }
        val audio = ZipFile(file).use { z -> JSONObject(z.getInputStream(z.getEntry("contribution.json")).bufferedReader().readText()).getJSONObject("audio") }
        assertEquals(2.0, audio.getJSONArray("fichiers").getJSONObject(0).getDouble("duree_secondes"), 0.0)
    }

    @Test
    fun noArchiveWithoutConsent() {
        val dir = File(tmp.root, "contribution")
        val o = offer()
        assertTrue(o.offered)
        assertFalse("unticked by default", o.consent)
        assertNotNull("the list is shown before consent", o.preview(facts))
        assertNull(o.createArchive(dir, facts))
        assertFalse("nothing written, not even the folder", dir.exists())
        o.consent = true
        o.consent = false
        assertNull(o.createArchive(dir, facts))
        assertFalse(dir.exists())
    }

    @Test
    fun belowTheThresholdNothingIsOfferedNorWrittenEvenWithConsent() {
        val dir = File(tmp.root, "contribution")
        val o = offer(result(recommendedFar = false)).apply { consent = true }
        assertFalse(o.result.offerSharing)
        assertFalse(o.offered)
        assertNull(o.preview(facts))
        assertNull(o.createArchive(dir, facts))
        assertFalse(dir.exists())
        // Nor without the declaration (defensive: the session never scores after « non »).
        val notDeclared = offer(declaration = Declaration.NO).apply { consent = true }
        assertFalse(notDeclared.offered)
        assertNull(notDeclared.createArchive(dir, facts))
        assertFalse(dir.exists())
    }

    @Test
    fun deletionRemovesTheArchiveAndItsFolder() {
        val dir = File(tmp.root, "contribution")
        val o = offer().apply { consent = true }
        val first = checkNotNull(o.createArchive(dir, facts))
        assertEquals(listOf(first), ContributionArchive.list(dir))
        // A new archive replaces the previous one: never two.
        o.region = "Gaspésie"
        val second = checkNotNull(o.createArchive(dir, facts.copy(date = LocalDate.of(2026, 11, 14))))
        assertEquals(listOf(second), ContributionArchive.list(dir))
        assertFalse(first.exists())
        assertTrue(ContributionArchive.deleteAll(dir))
        assertFalse(second.exists())
        assertFalse(dir.exists())
        assertTrue("deleting nothing is fine", ContributionArchive.deleteAll(dir))
    }

    @Test
    fun aLeftoverOlderThanADayIsSweptAYoungerOneIsKept() {
        val dir = File(tmp.root, "contribution")
        val file = checkNotNull(offer().apply { consent = true }.createArchive(dir, facts))
        val now = file.lastModified()
        ContributionArchive.deleteOlderThan(dir, now = now + ContributionArchive.MAX_AGE_MILLIS - 1000)
        assertTrue(file.exists())
        ContributionArchive.deleteOlderThan(dir, now = now + ContributionArchive.MAX_AGE_MILLIS + 1000)
        assertFalse(file.exists())
        assertFalse(dir.exists())
    }

    @Test
    fun theRegionIsShortOnOneLineAndOptional() {
        assertNull(ContributionContent.normalizeRegion(null))
        assertNull(ContributionContent.normalizeRegion("   \n\t "))
        assertEquals("Saguenay–Lac-Saint-Jean", ContributionContent.normalizeRegion(" Saguenay–Lac-Saint-Jean\n"))
        assertEquals("Côte Nord", ContributionContent.normalizeRegion("Côte\u0000​ Nord"))
        val long = ContributionContent.normalizeRegion("x".repeat(500))!!
        assertEquals(ContributionContent.REGION_MAX_CHARS, long.length)
    }

    @Test
    fun theJsonIsEscapedAndDeterministic() {
        val o = offer().apply { region = "Rue \"A\" \\ B" }
        val a = checkNotNull(o.preview(facts)).metadataJson()
        val b = checkNotNull(o.preview(facts)).metadataJson()
        assertEquals(a, b)
        assertEquals("Rue \"A\" \\ B", JSONObject(a).getString("region"))
        assertArrayEquals(a.toByteArray(), b.toByteArray())
    }
}
