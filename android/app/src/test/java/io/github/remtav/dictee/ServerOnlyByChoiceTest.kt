package io.github.remtav.dictee

import io.github.remtav.dictee.stt.EngineChoice
import io.github.remtav.dictee.stt.EngineRoute
import io.github.remtav.dictee.stt.TranscriptionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #57, the rule that admits no compromise: no dictation goes to a server unless the person set it up
 * themselves in the advanced settings. Two halves:
 *
 * 1. the route: a dictation goes to the server only with an address **and** the "server" mode (every
 *    combination, here; on the device, `ServerModeInstrumentedTest`);
 * 2. the consent: the "server" mode is written in exactly one place of the app, the choice of
 *    `SettingsActivity` (inside the folded « Serveur distant, pour essais (développeurs) » section of « Avancé »).
 *    No installation step, no quick test, no migration records it. A build with a preconfigured address
 *    (`-PdefaultServerUrl`) therefore still dictates on the phone until that choice is made.
 */
class ServerOnlyByChoiceTest {
    private val main = File(System.getProperty("dictee.assetsDir")!!).parentFile

    @Test
    fun theComparisonIncludesTheServerOnlyWhenItIsInUse() {
        val local = listOf(io.github.remtav.dictee.stt.ModelCatalog.whisperTurbo)
        assertEquals(local, EngineChoice.comparedModels(local, serverInUse = false))
        assertEquals(local + io.github.remtav.dictee.stt.ModelCatalog.remote, EngineChoice.comparedModels(local, serverInUse = true))
    }

    @Test
    fun aDictationGoesToTheServerOnlyWithAnAddressAndTheServerMode() {
        for (configured in listOf(false, true)) for (mode in TranscriptionMode.entries) for (network in listOf(false, true)) {
            val expected = if (configured && mode == TranscriptionMode.SERVER && network) EngineRoute.SERVER else EngineRoute.LOCAL
            assertEquals("configured=$configured mode=$mode network=$network", expected, EngineChoice.route(configured, mode, network))
        }
    }

    /**
     * A search of the source text, not a proof: it catches an assignment that names `SERVER` or a direct write of
     * the preference, but would miss a write through `TranscriptionMode.valueOf(…)` or a stored `.name`. Its name
     * says what it checks.
     */
    @Test
    fun noSourceFileButTheSettingsAssignsTheServerMode() {
        val writers = main.resolve("java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val text = f.readText()
                // An assignment of the mode whose right-hand side can be SERVER (a plain `== SERVER` test is fine).
                Regex("transcriptionMode\\s*=(?!=)[^\\n]*SERVER").containsMatchIn(text) ||
                    // Or a direct write of the preference.
                    (f.name != "AppSettings.kt" && text.contains("\"transcription_mode\""))
            }
            .map { it.name }
            .toList()
        assertEquals(listOf("SettingsActivity.kt"), writers)
        // The comparison screen sends audio to the server only through EngineChoice.comparedModels.
        val compare = main.resolve("java/io/github/remtav/dictee/ui/CompareActivity.kt").readText()
        assertTrue(compare.contains("EngineChoice.comparedModels("))
        assertTrue(!compare.contains("+ ModelCatalog.remote"))
    }

    @Test
    fun theStoredDefaultIsNotTheServer() {
        assertEquals(TranscriptionMode.UNSET, TranscriptionMode.fromName(null))
        assertEquals(TranscriptionMode.UNSET, TranscriptionMode.fromName("anything else"))
        val settings = main.resolve("java/io/github/remtav/dictee/settings/AppSettings.kt").readText()
        assertTrue(settings.contains("TranscriptionMode.fromName(prefs.getString(\"transcription_mode\", null))"))
    }
}
