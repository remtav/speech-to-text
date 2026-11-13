package io.github.remtav.dictee.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #15, lot 4: the reading test makes no network request and adds no permission (step 4.2), and,
 * since step 4.3, writes exactly one kind of file, the contribution archive, ONLY behind the consent.
 *
 * A guard on the sources (like `NoHardcodedStyleTest`) and on the manifest: it cannot prove the absence of
 * every possible call, but it fails on the usual ways of reaching the network, the disk or another app,
 * and pins each allowed one to a single file. The instrumented tests check the app's directories and its
 * network traffic at run time.
 */
class ReadingPrivacyTest {
    private val java: File = ReadingTestSupport.mainDir.resolve("java/io/github/remtav/dictee")

    /** The archive writer: the only file of the test allowed to write to the disk. */
    private val archiveWriter = java.resolve("reading/ContributionArchive.kt")

    /** The hand-off to the person's mail: the only file allowed to name the private folder, the provider and share intents. */
    private val handOff = java.resolve("ui/ContributionShare.kt")

    /** The code of the reading test and of its sharing: the pure package and the classes of its screen. */
    private val sources: List<File> =
        java.resolve("reading").listFiles()!!.filter { it.extension == "kt" } +
            listOf("ReadingTestActivity", "ReadingTestModel", "ReadingHighlight", "ReadingPlayer", "ContributionShare")
                .map { java.resolve("ui/$it.kt") }

    private fun code(f: File) = f.readText()
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines().joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }

    private fun offenders(tokens: List<String>, allowedIn: Set<File>): List<String> =
        sources.filter { it !in allowedIn }.flatMap { f -> val t = code(f); tokens.filter { t.contains(it) }.map { "${f.name} : $it" } }

    @Test
    fun theFilesExist() {
        for (f in sources) assertTrue("${f.path} absent", f.isFile)
    }

    @Test
    fun noNetworkAnywhereInTheReadingTestNorItsSharing() {
        val network = listOf(
            "java.net", "HttpURLConnection", "HttpsURLConnection", "okhttp", "Socket", "openStream", "openConnection",
            "WebView", "URL(", "DownloadManager", "javax.net", "Volley", "Retrofit",
            "RemoteEngine", "ModelFamily.REMOTE", "ModelCatalog.remote", "ModelCatalog.selectable",
            // byId also finds the user's server (review 4.R): the test looks models up in ModelCatalog.all only.
            "ModelCatalog.byId",
        )
        val problems = offenders(network, allowedIn = emptySet())
        assertTrue("accès réseau dans le test de lecture ou son partage :\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun onlyTheArchiveWriterWritesToTheDisk() {
        val writes = listOf(
            "FileOutputStream", "FileWriter", "openFileOutput", "createTempFile", "writeBytes", "writeText",
            "outputStream()", "ZipOutputStream", "RandomAccessFile", "WavReader.encode", "MediaStore",
            "getExternal", "externalCacheDir", "cacheDir", "SharedPreferences", "getSharedPreferences",
        )
        val problems = offenders(writes, allowedIn = setOf(archiveWriter))
        assertTrue("écriture hors de l'archive :\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun onlyTheHandOffNamesThePrivateFolderTheProviderAndShareIntents() {
        val share = listOf(
            "filesDir", "FileProvider", "ACTION_SEND", "EXTRA_STREAM", "EXTRA_EMAIL", "createChooser",
            "FLAG_GRANT_", "content://", "mailto:",
        )
        val problems = offenders(share, allowedIn = setOf(handOff))
        assertTrue("partage hors de ContributionShare :\n" + problems.joinToString("\n"), problems.isEmpty())
        // Read permission only, never write, never persistable.
        val handOffCode = code(handOff)
        assertTrue(handOffCode.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        for (t in listOf("FLAG_GRANT_WRITE_URI_PERMISSION", "FLAG_GRANT_PERSISTABLE_URI_PERMISSION", "FLAG_GRANT_PREFIX_URI_PERMISSION")) {
            assertTrue("$t dans ContributionShare", !handOffCode.contains(t))
        }
    }

    @Test
    fun anArchiveIsCreatedOnlyBehindTheConsent() {
        // ContributionArchive.write is internal and called in one place: ShareOffer.createArchive, which
        // returns before writing unless the offer stands and the consent was given (ContributionArchiveTest).
        val all = sources.associateWith { code(it) }
        val writeCalls = all.mapValues { (_, t) -> Regex("ContributionArchive\\.write\\(").findAll(t).count() }.filterValues { it > 0 }
        assertEquals(mapOf(archiveWriter to 1), writeCalls)
        val writer = all.getValue(archiveWriter)
        val createArchive = writer.substringAfter("fun createArchive(").substringBefore("\n    }")
        assertTrue(createArchive, createArchive.contains("if (!offered || !consent) return null"))
        assertTrue(createArchive.indexOf("!consent") < createArchive.indexOf("ContributionArchive.write("))
        assertTrue(writer.contains("internal fun write("))
        // createArchive is called by the screen model only, from the « Préparer le courriel » action.
        val createCalls = all.filterValues { it.contains(".createArchive(") }.keys.map { it.name }
        assertEquals(listOf("ReadingTestModel.kt"), createCalls)
        val prepare = all.getValue(java.resolve("ui/ReadingTestModel.kt")).substringAfter("fun prepareArchive()").substringBefore("\n    }")
        assertTrue(prepare.contains(".createArchive("))
        // The consent box is unticked by default and only set by the person's tap.
        assertTrue(writer.contains("var consent: Boolean = false"))
        val screen = all.getValue(java.resolve("ui/ReadingTestActivity.kt"))
        assertEquals(1, Regex("model\\.setConsent\\(").findAll(screen).count())
        assertTrue(screen.contains("setOnCheckedChangeListener { _, checked ->\n                    model.setConsent(checked)"))
        assertEquals(1, Regex("model\\.prepareArchive\\(\\)").findAll(screen).count())
    }

    @Test
    fun theTestNeverRunsTheUsersOwnServer() {
        // Only the on-device catalogue is offered to the test: the server would take the voice over the network.
        val screen = code(java.resolve("ui/ReadingTestActivity.kt"))
        assertTrue(screen.contains("ModelCatalog.offered(settings.languages).filter"))
        // Review 4.R: and that list really holds no server (the server is `ModelCatalog.remote`, outside it).
        assertTrue(io.github.remtav.dictee.stt.ModelCatalog.all.none { it.family == io.github.remtav.dictee.stt.ModelFamily.REMOTE })
        for (l in io.github.remtav.dictee.stt.DictationLanguage.entries) {
            val offered = io.github.remtav.dictee.stt.ModelCatalog.offered(setOf(l))
            assertTrue(offered.none { it.family == io.github.remtav.dictee.stt.ModelFamily.REMOTE })
        }
        assertTrue(io.github.remtav.dictee.stt.ModelCatalog.remote !in io.github.remtav.dictee.stt.ModelCatalog.all)
        // The transcriber of the screen model looks the id up in that list too, never with byId.
        val model = code(java.resolve("ui/ReadingTestModel.kt"))
        assertTrue(model.contains("ModelCatalog.all.firstOrNull { it.id == modelId }"))
    }

    @Test
    fun noPermissionWasAddedAndTheProviderServesOnlyTheArchiveFolder() {
        val manifest = ReadingTestSupport.mainDir.resolve("AndroidManifest.xml").readText()
        val permissions = Regex("<uses-permission\\s+android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "android.permission.RECORD_AUDIO", "android.permission.INTERNET", "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.POST_NOTIFICATIONS",
            ),
            permissions,
        )
        // One provider: the FileProvider of the contribution, not exported, per-intent grants, its own paths.
        val providers = Regex("<provider[\\s\\S]*?</provider>").findAll(manifest).map { it.value }.toList()
        assertEquals(1, providers.size)
        val provider = providers.single()
        for (t in listOf(
            "android:name=\"androidx.core.content.FileProvider\"", "android:authorities=\"\${applicationId}.contribution\"",
            "android:exported=\"false\"", "android:grantUriPermissions=\"true\"", "android:resource=\"@xml/contribution_paths\"",
        )) assertTrue("fournisseur : $t absent", provider.contains(t))
        val paths = ReadingTestSupport.mainDir.resolve("res/xml/contribution_paths.xml").readText()
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
        val entries = Regex("<([a-z-]+-path)\\s[^>]*>").findAll(paths).map { it.value }.toList()
        assertEquals(listOf("<files-path name=\"contribution\" path=\"contribution/\" />"), entries)
    }

    @Test
    fun theArchiveIsInNoBackupNorDeviceTransfer() {
        // Review 4.R: allowBackup="false" stops the cloud backup but, from Android 12, not the transfer to a
        // new phone; the extraction rules exclude the archive folder from both.
        val manifest = ReadingTestSupport.mainDir.resolve("AndroidManifest.xml").readText()
        val application = Regex("<application[\\s\\S]*?>").find(manifest)!!.value
        assertTrue(application.contains("android:allowBackup=\"false\""))
        assertTrue(application.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val rules = ReadingTestSupport.mainDir.resolve("res/xml/data_extraction_rules.xml").readText()
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = Regex("<$section>([\\s\\S]*?)</$section>").find(rules)?.groupValues?.get(1)
            assertTrue("section $section absente", body != null)
            assertTrue("$section : dossier des archives non exclu", body!!.contains("<exclude domain=\"file\" path=\"contribution/\" />"))
            assertTrue("$section : aucune inclusion attendue", !body.contains("<include"))
        }
    }
}
