package io.github.remtav.dictee

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.StrictMode
import android.os.strictmode.Violation
import android.widget.CheckBox
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.remtav.dictee.audio.AudioSourceFactory
import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.PcmAudioSource
import io.github.remtav.dictee.reading.ArchiveField
import io.github.remtav.dictee.reading.Contribution
import io.github.remtav.dictee.reading.ContributionContent
import io.github.remtav.dictee.reading.Declaration
import io.github.remtav.dictee.reading.ReadingMaterial
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.ui.ContributionShare
import io.github.remtav.dictee.ui.ReadingTestActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Issue #15, lot 4, step 4.3: the sharing of a reading, with scripted models and a clip supplied by the
 * test. Below the threshold nothing is offered; above it, without consent no archive exists; with consent
 * the archive holds exactly the elements listed on screen and the mail intent carries the right recipient
 * and the attachment; the archive is deleted from the screen, by « Refaire le test » and with the screen.
 *
 * No network, ever: every test of this class runs under [NetworkGuard] (the app's traffic counters and a
 * StrictMode socket detector, see there for the limits). Intents to other apps are caught by a monitor
 * and never started, so no mail app is opened.
 */
@RunWith(AndroidJUnit4::class)
class ContributionInstrumentedTest : ImeTestBase() {
    private val material: ReadingMaterial by lazy {
        ReadingMaterial.load { path -> context.assets.open(path).bufferedReader().use { it.readText() } }
    }
    private val paragraphs get() = material.text.paragraphs
    private val redoLabel get() = context.getString(R.string.reading_redo)
    private val archiveDir get() = ContributionShare.archiveDir(context)
    private val parakeet = ModelCatalog.parakeet.id
    private val qwen = ModelCatalog.qwen3.id
    private lateinit var network: NetworkGuard

    /**
     * The monitor that blocks the mail and the share sheet, removed in [cleanUp] whatever happens: left
     * behind by a failed assertion, it would block EVERY later activity start of the run (review 4.R).
     */
    private val catcher = IntentCatcher()
    private var catching = false

    /** Instrumentation.removeMonitor throws when no monitor was ever added in the process: only remove ours. */
    private fun startCatching() { instrumentation.addMonitor(catcher); catching = true }
    private fun stopCatching() { if (catching) instrumentation.removeMonitor(catcher); catching = false }

    @Before
    fun prepare() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC) // Parakeet is the recommended model
        app.settings.activeModelId = parakeet
        app.settings.quebecDeclaration = Declaration.YES
        TestHooks.audioSourceFactory = AudioSourceFactory {
            PcmAudioSource(
                listOf(PcmAudio(FloatArray(3 * 16000) { if (it % 40 < 20) 0.05f else -0.05f }, 16000)),
                realTime = false, leadSeconds = 0f, tailSeconds = 0f,
            )
        }
        File(archiveDir.path).deleteRecursively()
        network = NetworkGuard().also { it.start() }
    }

    @After
    fun cleanUp() {
        stopCatching()
        TestHooks.installedModelIds = null
        try {
            network.assertNoTraffic()
        } finally {
            network.stop()
            File(archiveDir.path).deleteRecursively()
        }
    }

    /**
     * No network request: the app's UID traffic counters must not move, and StrictMode must not see any
     * socket opened by the app process. Limits: the counters may be unsupported on a device (then only
     * StrictMode is checked, and the test says so in the log); StrictMode sees the sockets opened from
     * Java, not from native code (the counters, kernel-side, see those). Both together cover the app's code
     * paths of the test and the sharing; the mail app, which would send, is never started here.
     */
    private class NetworkGuard {
        private val uid = Process.myUid()
        private var tx = 0L
        private var rx = 0L
        private val violations = Collections.synchronizedList(ArrayList<Violation>())
        private var previous: StrictMode.VmPolicy? = null
        private val executor = Executors.newSingleThreadExecutor()

        fun start() {
            tx = TrafficStats.getUidTxBytes(uid)
            rx = TrafficStats.getUidRxBytes(uid)
            previous = StrictMode.getVmPolicy()
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectUntaggedSockets()
                    .detectCleartextNetwork()
                    .penaltyListener(executor) { v -> violations += v }
                    .build(),
            )
        }

        fun assertNoTraffic() {
            val txNow = TrafficStats.getUidTxBytes(uid)
            val rxNow = TrafficStats.getUidRxBytes(uid)
            if (tx == TrafficStats.UNSUPPORTED.toLong() || txNow == TrafficStats.UNSUPPORTED.toLong()) {
                android.util.Log.w("ContributionTest", "UID traffic counters unsupported: StrictMode only")
            } else {
                assertEquals("bytes sent by the app during the test", 0L, txNow - tx)
                assertEquals("bytes received by the app during the test", 0L, rxNow - rx)
            }
            assertTrue("sockets seen by StrictMode: $violations", violations.isEmpty())
        }

        fun stop() {
            previous?.let { StrictMode.setVmPolicy(it) }
            executor.shutdown()
        }
    }

    /** Catches every activity the screen starts (mail, share sheet) and blocks it: nothing is opened. */
    private class IntentCatcher : Instrumentation.ActivityMonitor() {
        val caught: MutableList<Intent> = Collections.synchronizedList(ArrayList())
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
            caught += Intent(intent)
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    /** Every file the app owns, minus the known library marker files (see [AppFiles]). */
    private fun appFiles(): Set<String> = AppFiles.list(context)

    private fun archives(): List<File> = archiveDir.listFiles()?.filter { it.isFile }.orEmpty()

    private fun dropEveryNth(text: String, n: Int) = text.split(' ').filterIndexed { i, _ -> i % n != n - 1 }.joinToString(" ")

    /** Reads the whole text with the scripted models and waits for the result of the recommended model. */
    private fun readAndScore(d: ReadingDriver) {
        d.awaitTag(ReadingTestActivity.TAG_ACCEPT_START)
        d.click(ReadingTestActivity.TAG_ACCEPT_START)
        d.awaitTag(ReadingTestActivity.TAG_PARAGRAPH)
        d.readAllParagraphs(redoLabel)
        d.click(ReadingTestActivity.TAG_SCORE)
        d.await("result", 90_000) { d.has(ReadingTestActivity.TAG_REDO) }
    }

    private fun useModels(vararg ids: String, transcript: (String, Int, String) -> String) {
        TestHooks.installedModelIds = ids.toSet()
        TestHooks.engineFactory = ScriptedReadingModels(material, transcript)
    }

    private fun consentBox(d: ReadingDriver): CheckBox =
        d.onViews { v -> v.first { it.tag == ReadingTestActivity.TAG_SHARE_CONSENT } as CheckBox }

    private fun prepareEnabled(d: ReadingDriver): Boolean =
        d.onViews { v -> v.first { it.tag == ReadingTestActivity.TAG_SHARE_PREPARE }.isEnabled }

    /** The archive elements listed on screen, in order (the tag of each label names the element). */
    private fun listedKeys(d: ReadingDriver): List<String> = d.onViews { v ->
        v.mapNotNull { it.tag as? String }.filter { it.startsWith("reading_share_field_") }
            .map { it.removePrefix("reading_share_field_") }.toList()
    }

    private fun metadataKeys(text: String): List<String> =
        Regex("^  \"([^\"]+)\": ", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()

    // ---- below the threshold ----

    @Test
    fun belowTheThresholdNothingIsOffered() {
        // Parakeet (recommended) misses one word in ten: about 90 %, above the 80 % line.
        useModels(parakeet) { _, _, text -> dropEveryNth(text, 10) }
        val before = appFiles()
        ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
            val d = ReadingDriver(instrumentation, s, material)
            readAndScore(d)
            assertFalse(d.has(ReadingTestActivity.TAG_SHARE_SLOT))
            assertFalse(d.has(ReadingTestActivity.TAG_SHARE_CONSENT))
            assertFalse(d.allText().contains(context.getString(R.string.contrib_share_title)))
            assertTrue(d.allText().contains(context.getString(R.string.reading_result_kept)))
        }
        assertTrue("files created: ${appFiles() - before}", (appFiles() - before).isEmpty())
        assertFalse(archiveDir.exists())
    }

    // ---- above the threshold, without consent ----

    @Test
    fun aboveTheThresholdTheListIsShownButWithoutConsentNoArchiveIsCreated() {
        useModels(parakeet) { _, _, text -> ScriptedReadingModels.dropEveryFourthWord(text) }
        val before = appFiles()
        ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
            val d = ReadingDriver(instrumentation, s, material)
            readAndScore(d)
            d.awaitTag(ReadingTestActivity.TAG_SHARE_CONSENT)
            // Exactly what would leave: one line per element of the archive, in the archive's order.
            assertEquals(ArchiveField.entries.map { it.key }, listedKeys(d))
            val shown = d.allText()
            for (item in listOf(
                material.version, ContributionContent.DECLARATION_TEXT, LocalDate.now().toString(),
                BuildConfig.VERSION_NAME, "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                ScriptedReadingModels.dropEveryFourthWord(paragraphs[0]),
                context.getString(R.string.contrib_value_region_none),
                context.getString(R.string.contrib_share_recipient, Contribution.RECEIVING_ADDRESS),
                context.getString(R.string.contrib_share_email_reveals),
            )) assertTrue("« $item » not shown", shown.contains(item))
            assertTrue(d.has(ReadingTestActivity.TAG_SHARE_LISTEN))
            assertTrue(d.has(ReadingTestActivity.TAG_SHARE_DECLINE))
            assertTrue(d.has(ReadingTestActivity.TAG_REDO))
            // The box is unticked and the button disabled until the person ticks it.
            assertFalse(consentBox(d).isChecked)
            assertFalse(prepareEnabled(d))
            // Typing a region, ticking then unticking the box: still nothing on disk.
            s.onActivity { a -> a.window.decorView.findViewWithTag<EditText>(ReadingTestActivity.TAG_SHARE_REGION).setText("Gaspésie") }
            instrumentation.waitForIdleSync()
            assertTrue(d.allText().contains("Gaspésie"))
            d.click(ReadingTestActivity.TAG_SHARE_CONSENT)
            assertTrue(prepareEnabled(d))
            d.click(ReadingTestActivity.TAG_SHARE_CONSENT)
            assertFalse(prepareEnabled(d))
            assertFalse("no archive without consent", archiveDir.exists())
            // « Ne pas partager »: the offer closes, nothing is created.
            d.click(ReadingTestActivity.TAG_SHARE_DECLINE)
            d.awaitTag(ReadingTestActivity.TAG_SHARE_DECLINED)
            assertFalse(d.has(ReadingTestActivity.TAG_SHARE_PREPARE))
            assertFalse(archiveDir.exists())
            d.click(ReadingTestActivity.TAG_CLOSE)
        }
        assertTrue("files created: ${appFiles() - before}", (appFiles() - before).isEmpty())
    }

    // ---- with consent ----

    @Test
    fun withConsentTheArchiveHoldsExactlyTheListedElementsAndTheMailIntentIsRight() {
        // Parakeet (recommended) loses a quarter of the words; Qwen3-ASR hears everything (its results stay here).
        useModels(parakeet, qwen) { model, _, text -> if (model == parakeet) ScriptedReadingModels.dropEveryFourthWord(text) else text }
        ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
            val d = ReadingDriver(instrumentation, s, material)
            readAndScore(d)
            d.awaitTag(ReadingTestActivity.TAG_SHARE_CONSENT)
            assertTrue(d.allText().contains(context.getString(R.string.contrib_share_only_recommended)))
            val listed = listedKeys(d)
            s.onActivity { a ->
                a.window.decorView.findViewWithTag<EditText>(ReadingTestActivity.TAG_SHARE_REGION).setText(" Bas-Saint-Laurent ")
            }
            d.click(ReadingTestActivity.TAG_SHARE_CONSENT)
            d.click(ReadingTestActivity.TAG_SHARE_PREPARE)
            d.awaitTag(ReadingTestActivity.TAG_SHARE_READY)

            // One archive, in the private folder, holding exactly the elements listed on screen.
            val archive = archives().single()
            assertEquals(File(context.filesDir, "contribution"), archive.parentFile)
            val bytes = archive.readBytes()
            ZipFile(archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(listOf(ContributionContent.METADATA_NAME) + paragraphs.indices.map { "paragraphe-${it + 1}.wav" }, names)
                val text = zip.getInputStream(zip.getEntry(ContributionContent.METADATA_NAME)).readBytes().toString(Charsets.UTF_8)
                assertEquals("the archive = the list shown", listed, metadataKeys(text))
                assertEquals(ArchiveField.entries.map { it.key }, metadataKeys(text))
                val json = JSONObject(text)
                assertEquals(material.version, json.getString("version_du_texte"))
                assertEquals(LocalDate.now().toString(), json.getString("date"))
                assertEquals(BuildConfig.VERSION_NAME, json.getString("version_de_l_application"))
                assertEquals("${Build.MANUFACTURER} ${Build.MODEL}".trim(), json.getString("modele_du_telephone"))
                assertEquals(ContributionContent.DECLARATION_TEXT, json.getString("declaration"))
                assertEquals("Bas-Saint-Laurent", json.getString("region"))
                assertEquals(parakeet, json.getJSONObject("modele").getString("id"))
                val tr = json.getJSONArray("transcription")
                for (i in paragraphs.indices) {
                    assertEquals(ScriptedReadingModels.dropEveryFourthWord(paragraphs[i]), tr.getJSONObject(i).getString("texte"))
                }
                assertFalse("no other model's result", text.contains(qwen))
                for (i in paragraphs.indices) {
                    val wav = zip.getInputStream(zip.getEntry("paragraphe-${i + 1}.wav")).readBytes()
                    val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals("RIFF", String(wav, 0, 4))
                    assertEquals(1, b.getShort(22).toInt())
                    assertEquals(16000, b.getInt(24))
                    assertEquals(16, b.getShort(34).toInt())
                    assertEquals(wav.size - 44, b.getInt(40))
                    assertTrue("audio in paragraph ${i + 1}", b.getInt(40) > 16000)
                }
            }

            // « Envoyer par courriel »: caught, never started.
            startCatching()
            d.click(ReadingTestActivity.TAG_SHARE_EMAIL)
            d.await("mail intent") { catcher.caught.size == 1 }
            val mail = catcher.caught.single()
            assertEquals(Intent.ACTION_SEND, mail.action)
            assertEquals(ContributionShare.MIME_TYPE, mail.type)
            assertArrayEquals(arrayOf(Contribution.RECEIVING_ADDRESS), mail.getStringArrayExtra(Intent.EXTRA_EMAIL))
            assertEquals(Uri.parse("mailto:"), mail.selector?.data)
            assertEquals(context.getString(R.string.contrib_email_subject, material.version), mail.getStringExtra(Intent.EXTRA_SUBJECT))
            val body = mail.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            assertTrue(body.contains(archive.name))
            assertTrue(body.contains(Contribution.POLICY_URL))
            @Suppress("DEPRECATION")
            val stream = mail.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertNotNull(stream)
            assertEquals(ContributionShare.AUTHORITY, stream!!.authority)
            assertEquals(stream, mail.clipData?.getItemAt(0)?.uri)
            assertTrue(mail.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, mail.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(0, mail.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            // The attachment is the archive itself, served by the FileProvider.
            val served = context.contentResolver.openInputStream(stream)!!.use { it.readBytes() }
            assertArrayEquals(bytes, served)
            d.awaitTag(ReadingTestActivity.TAG_SHARE_MAIL_OPENED)

            // « Partager le fichier autrement »: the system share sheet with the same attachment.
            d.click(ReadingTestActivity.TAG_SHARE_OTHER)
            d.await("share sheet") { catcher.caught.size == 2 }
            val chooser = catcher.caught[1]
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, inner.action)
            @Suppress("DEPRECATION")
            assertEquals(stream, inner.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

            // « Supprimer le fichier »: gone, and sharing again needs the box again.
            d.click(ReadingTestActivity.TAG_SHARE_DELETE)
            d.awaitTag(ReadingTestActivity.TAG_SHARE_DELETED)
            assertFalse(archive.exists())
            assertFalse(archiveDir.exists())
            assertFalse(consentBox(d).isChecked)
            assertFalse(prepareEnabled(d))
            stopCatching()
        }
        assertEquals("no other activity was started", 2, catcher.caught.size)
    }

    @Test
    fun theArchiveGoesWithRedoAndWithTheScreen() {
        useModels(parakeet) { _, _, text -> ScriptedReadingModels.dropEveryFourthWord(text) }
        val before = appFiles()
        ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
            val d = ReadingDriver(instrumentation, s, material)
            readAndScore(d)
            d.click(ReadingTestActivity.TAG_SHARE_CONSENT)
            d.click(ReadingTestActivity.TAG_SHARE_PREPARE)
            d.awaitTag(ReadingTestActivity.TAG_SHARE_READY)
            assertEquals(1, archives().size)
            assertFalse("the screen no longer says « kept nowhere »", d.allText().contains(context.getString(R.string.reading_result_kept)))
            // « Refaire le test »: the archive is deleted with the recordings.
            d.click(ReadingTestActivity.TAG_REDO)
            d.awaitTag(ReadingTestActivity.TAG_PARAGRAPH)
            assertFalse(archiveDir.exists())
            // Again, then the screen is closed: the archive goes with it.
            d.readAllParagraphs(redoLabel)
            d.click(ReadingTestActivity.TAG_SCORE)
            d.awaitTag(ReadingTestActivity.TAG_SHARE_CONSENT, 90_000)
            assertFalse("the box is unticked again", consentBox(d).isChecked)
            d.click(ReadingTestActivity.TAG_SHARE_CONSENT)
            d.click(ReadingTestActivity.TAG_SHARE_PREPARE)
            d.awaitTag(ReadingTestActivity.TAG_SHARE_READY)
            assertEquals(1, archives().size)
            d.click(ReadingTestActivity.TAG_CLOSE)
            d.await("archive deleted with the screen") { !archiveDir.exists() }
        }
        assertTrue("files left: ${appFiles() - before}", (appFiles() - before).isEmpty())
    }

    @Test
    fun aLeftoverArchiveIsDeletedWhenANewTestOpens() {
        // An archive left by a killed process: the next reading test deletes it before anything else.
        archiveDir.mkdirs()
        File(archiveDir, "kalmia-blabla-lecture-qc-1-2026-01-01.zip").writeBytes(byteArrayOf(1, 2, 3))
        useModels(parakeet) { _, _, text -> text }
        ActivityScenario.launch(ReadingTestActivity::class.java).use { s ->
            ReadingDriver(instrumentation, s, material).awaitTag(ReadingTestActivity.TAG_ACCEPT_START)
            assertFalse(archiveDir.exists())
        }
    }
}
