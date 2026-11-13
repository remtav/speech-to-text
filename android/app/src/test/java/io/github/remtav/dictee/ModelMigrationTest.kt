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

    @Test fun parakeetWithQuebecFrenchMovesToWhisperTurbo() {
        val n = ModelMigration.plan(ModelCatalog.parakeet.id, qc, installed(ModelCatalog.parakeet, ModelCatalog.whisperTurbo))
        assertNotNull(n)
        assertEquals(ModelCatalog.parakeet, n!!.retired)
        assertEquals(ModelCatalog.whisperTurbo, n.replacement)
        assertFalse(n.needsDownload)
        // Parakeet is not hidden (still offered for English alone): its files are not offered for deletion.
        assertFalse(n.offerDelete)
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
        assertNull(ModelMigration.plan(ModelCatalog.parakeet.id, english, installed()))
        assertNull(ModelMigration.plan(ModelCatalog.liteWhisperTurbo.id, qc, installed()))
        assertNull(ModelMigration.plan(ModelCatalog.remote.id, qc, installed()))
        // Unknown id: AppSettings.activeModel already reads it as the default.
        assertNull(ModelMigration.plan("gone", qc, installed()))
    }

    @Test fun parakeetLeavesOnceAnotherLanguageIsAddedToEnglish() {
        assertNotNull(ModelMigration.plan(ModelCatalog.parakeet.id, english + DictationLanguage.SPANISH, installed()))
    }

    @Test fun serverFallbackIsNeverHidden() {
        val turbo = ModelCatalog.whisperTurbo.id
        assertEquals(turbo, ModelMigration.fallbackId(ModelCatalog.qwen3.id, qc))
        assertEquals(turbo, ModelMigration.fallbackId(ModelCatalog.whisperQuebec.id, qc))
        assertEquals(turbo, ModelMigration.fallbackId(ModelCatalog.parakeet.id, qc))
        assertEquals(ModelCatalog.parakeet.id, ModelMigration.fallbackId(ModelCatalog.parakeet.id, english))
        assertEquals(ModelCatalog.liteWhisperTurbo.id, ModelMigration.fallbackId(ModelCatalog.liteWhisperTurbo.id, qc))
        assertEquals("", ModelMigration.fallbackId("", qc))
        // The server is never its own fallback.
        assertEquals(turbo, ModelMigration.fallbackId(ModelCatalog.remote.id, qc))
    }
}
