package io.github.remtav.dictee

import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.SherpaEngine
import io.github.remtav.dictee.stt.WhisperTuning
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #15, user request: Whisper speed. No provisional decodes with Whisper; advanced tail padding. */
class WhisperTuningTest {
    private val whisperModels = ModelCatalog.all.filter { it.family == ModelFamily.WHISPER }

    @Test fun whisperModelsAreTurboQuebecAndLite() {
        assertEquals(listOf(ModelCatalog.whisperTurbo, ModelCatalog.whisperQuebec, ModelCatalog.liteWhisperTurbo), whisperModels)
    }

    @Test fun noProvisionalDecodesWithWhisperWhateverTheSetting() {
        for (spec in whisperModels) {
            assertFalse(spec.id, WhisperTuning.provisionalAllowed(spec, true))
            assertFalse(spec.id, WhisperTuning.provisionalAllowed(spec, false))
        }
    }

    @Test fun otherModelsKeepTheSetting() {
        for (spec in ModelCatalog.all.filter { it.family != ModelFamily.WHISPER && it.family != ModelFamily.REMOTE }) {
            assertTrue(spec.id, WhisperTuning.provisionalAllowed(spec, true))
            assertFalse(spec.id, WhisperTuning.provisionalAllowed(spec, false))
        }
    }

    @Test fun serverNeverGetsProvisionalDecodes() {
        assertFalse(WhisperTuning.provisionalAllowed(ModelCatalog.remote, true))
    }

    @Test fun defaultIsTheOriginalThousandAndChoicesAreThree() {
        assertEquals(1000, WhisperTuning.DEFAULT_TAIL_PADDINGS)
        assertEquals(1000, EngineOptions().whisperTailPaddings)
        assertEquals(listOf(1000, 600, 300), WhisperTuning.TAIL_PADDING_CHOICES)
    }

    @Test fun onlyAllowedValuesAreAccepted() {
        for (v in listOf(1000, 600, 300)) assertEquals(v, WhisperTuning.sanitizeTailPaddings(v))
        for (v in listOf(-1, 0, 100, 500, 1001, 5000)) assertEquals(1000, WhisperTuning.sanitizeTailPaddings(v))
    }

    @Test fun valueIsForWhisperOnly() {
        for (spec in whisperModels) assertEquals(300, WhisperTuning.tailPaddingsFor(spec, 300))
        for (spec in ModelCatalog.all.filter { it.family != ModelFamily.WHISPER }) {
            assertEquals(spec.id, 1000, WhisperTuning.tailPaddingsFor(spec, 300))
        }
    }

    @Test fun engineConfigCarriesTheValueForWhisperOnly() {
        for (v in listOf(1000, 600, 300)) {
            val cfg = SherpaEngine.buildConfig(ModelCatalog.whisperTurbo, File("/x"), EngineOptions(whisperTailPaddings = v))
            assertEquals(v, cfg.modelConfig.whisper.tailPaddings)
        }
        // Not passed to the other models: their config keeps the Whisper block's own default (1000, unused).
        val parakeet = SherpaEngine.buildConfig(ModelCatalog.parakeet, File("/x"), EngineOptions(whisperTailPaddings = 300))
        assertEquals(1000, parakeet.modelConfig.whisper.tailPaddings)
        assertEquals("", parakeet.modelConfig.whisper.encoder)
    }

    @Test fun valueIsPartOfTheEngineKeyOfWhisperOnly() {
        // EngineManager reloads when (model id, options) changes: a different padding is a different key...
        val whisper = { v: Int -> EngineOptions(whisperTailPaddings = WhisperTuning.tailPaddingsFor(ModelCatalog.whisperTurbo, v)) }
        assertNotEquals(whisper(1000), whisper(600))
        // ...but not for a model that ignores it.
        val parakeet = { v: Int -> EngineOptions(whisperTailPaddings = WhisperTuning.tailPaddingsFor(ModelCatalog.parakeet, v)) }
        assertEquals(parakeet(1000), parakeet(300))
    }
}
