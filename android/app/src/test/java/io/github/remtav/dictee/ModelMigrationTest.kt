package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelMigration
import io.github.remtav.dictee.stt.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #22, step 5: moving off a hidden model or one that no longer suits the languages. */
class ModelMigrationTest {
    private val qc = setOf(DictationLanguage.FRENCH_QUEBEC)
    private val english = setOf(DictationLanguage.ENGLISH)
    private fun installed(vararg specs: ModelSpec): (ModelSpec) -> Boolean = { it in specs }

    /** Issue #72: Parakeet stays active, without notice, whatever the languages (it is allowed in « Avancé »). */
    @Test fun anActiveParakeetStaysWithoutNoticeForEveryLanguage() {
        for (l in DictationLanguage.entries) {
            assertNull(l.toString(), ModelMigration.plan(ModelCatalog.parakeet.id, setOf(l), installed(ModelCatalog.parakeet)))
            assertNull(l.toString(), ModelMigration.plan(ModelCatalog.parakeet.id, setOf(l), installed()))
        }
        assertNull(ModelMigration.plan(ModelCatalog.parakeet.id, qc, installed(ModelCatalog.parakeet, ModelCatalog.whisperTurbo)))
    }

    /** Issue #72: the withdrawn Lite-Whisper moves to Whisper; the notice is shown, its files are offered for deletion. */
    @Test fun anActiveLiteWhisperMovesToWhisperTurboAndOffersToDeleteItsFiles() {
        for (l in DictationLanguage.entries) {
            val n = ModelMigration.plan(ModelCatalog.liteWhisperTurbo.id, setOf(l), installed(ModelCatalog.liteWhisperTurbo, ModelCatalog.whisperTurbo))
            assertNotNull(l.toString(), n)
            assertEquals(ModelCatalog.liteWhisperTurbo, n!!.retired)
            assertEquals(ModelCatalog.whisperTurbo, n.replacement)
            assertFalse(n.needsDownload)
            assertTrue(n.offerDelete)
        }
    }

    @Test fun liteWhisperToWhisperNotYetDownloadedInvitesToDownloadIt() {
        val n = ModelMigration.plan(ModelCatalog.liteWhisperTurbo.id, qc, installed(ModelCatalog.liteWhisperTurbo))!!
        assertEquals(ModelCatalog.whisperTurbo, n.replacement)
        assertTrue(n.needsDownload)
        assertTrue(n.offerDelete)
    }

    @Test fun liteWhisperFilesAlreadyGoneAreNotOfferedForDeletion() {
        val n = ModelMigration.plan(ModelCatalog.liteWhisperTurbo.id, english, installed(ModelCatalog.whisperTurbo))!!
        assertFalse(n.offerDelete)
        assertFalse(n.needsDownload)
    }

    @Test fun quebecWhisperMovesToWhisperTurboAndOffersToDeleteItsFiles() {
        val n = ModelMigration.plan(ModelCatalog.whisperQuebec.id, qc, installed(ModelCatalog.whisperQuebec))!!
        assertEquals(ModelCatalog.whisperTurbo, n.replacement)
        assertTrue(n.needsDownload)
        assertTrue(n.offerDelete)
    }

    @Test fun qwenIsHiddenForEveryLanguage() {
        for (l in DictationLanguage.entries) {
            val n = ModelMigration.plan(ModelCatalog.qwen3.id, setOf(l), installed())
            assertNotNull(l.toString(), n)
            assertFalse(n!!.offerDelete) // not installed: nothing to delete
        }
    }

    @Test fun suitableModelsStay() {
        assertNull(ModelMigration.plan(ModelCatalog.whisperTurbo.id, qc, installed()))
        assertNull(ModelMigration.plan(ModelCatalog.parakeet.id, qc, installed()))
        assertNull(ModelMigration.plan(ModelCatalog.remote.id, qc, installed()))
        // Unknown id: AppSettings.activeModel already reads it as the default.
        assertNull(ModelMigration.plan("gone", qc, installed()))
    }

    @Test fun serverFallbackIsNeverHidden() {
        val turbo = ModelCatalog.whisperTurbo.id
        assertEquals(turbo, ModelMigration.fallbackId(ModelCatalog.qwen3.id, qc))
        assertEquals(turbo, ModelMigration.fallbackId(ModelCatalog.whisperQuebec.id, qc))
        assertEquals(turbo, ModelMigration.fallbackId(ModelCatalog.liteWhisperTurbo.id, qc))
        // Parakeet is allowed in « Avancé »: it may stay the server's fallback.
        assertEquals(ModelCatalog.parakeet.id, ModelMigration.fallbackId(ModelCatalog.parakeet.id, qc))
        assertEquals("", ModelMigration.fallbackId("", qc))
        // The server is never its own fallback.
        assertEquals(turbo, ModelMigration.fallbackId(ModelCatalog.remote.id, qc))
    }
}
