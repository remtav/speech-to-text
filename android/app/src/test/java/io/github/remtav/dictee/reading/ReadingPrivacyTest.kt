package io.github.remtav.dictee.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #15, lot 4, step 4.2: the reading test makes no network request, writes no file and adds no
 * permission. A guard on the sources (like `NoHardcodedStyleTest`) and on the manifest: it cannot prove
 * the absence of every possible call, but it fails on the usual ways of reaching the network or the disk,
 * and the instrumented test checks the app's directories at run time.
 */
class ReadingPrivacyTest {
    private val java: File = ReadingTestSupport.mainDir.resolve("java/io/github/remtav/dictee")

    /** The code of the reading test: the pure package and the three classes of its screen. */
    private val sources: List<File> =
        java.resolve("reading").listFiles()!!.filter { it.extension == "kt" } +
            listOf("ReadingTestActivity", "ReadingTestModel", "ReadingHighlight").map { java.resolve("ui/$it.kt") }

    private fun code(f: File) = f.readText()
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines().joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }

    @Test
    fun noNetworkNoSharingNoFileInTheReadingTestCode() {
        val forbidden = listOf(
            "java.net", "HttpURLConnection", "okhttp", "Socket", "openStream", "WebView", "URL(",
            "ACTION_SEND", "ACTION_SENDTO", "EXTRA_STREAM", "FileProvider",
            "FileOutputStream", "FileWriter", "openFileOutput", "createTempFile", "writeBytes", "writeText",
            "filesDir", "cacheDir", "getExternal", "externalCacheDir", "MediaStore", "WavReader.encode",
            "RemoteEngine", "ModelFamily.REMOTE", "ModelCatalog.remote", "ModelCatalog.selectable",
        )
        val problems = ArrayList<String>()
        for (f in sources) {
            val text = code(f)
            for (token in forbidden) if (text.contains(token)) problems += "${f.name} : $token"
        }
        assertTrue("accès réseau, fichier ou partage dans le test de lecture :\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun theTestNeverRunsTheUsersOwnServer() {
        // Only the on-device catalogue is offered to the test: the server would take the voice over the network.
        val screen = code(java.resolve("ui/ReadingTestActivity.kt"))
        assertTrue(screen.contains("ModelCatalog.all.filter"))
    }

    @Test
    fun noPermissionWasAdded() {
        val manifest = ReadingTestSupport.mainDir.resolve("AndroidManifest.xml").readText()
        val permissions = Regex("<uses-permission\\s+android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "android.permission.RECORD_AUDIO", "android.permission.INTERNET", "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.POST_NOTIFICATIONS",
            ),
            permissions,
        )
    }
}
