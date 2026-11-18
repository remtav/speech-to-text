package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DefaultEngineFactory
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.ModelRecommendations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Issue #123, lot 1: Kyutai STT 1B is in the catalogue, from Hugging Face, but not offered until its engine exists. */
class KyutaiCatalogTest {
    private val kyutai = ModelCatalog.kyutaiStt

    @Test fun filesComeFromTheOfficialHuggingFaceRepository() {
        assertEquals(ModelFamily.KYUTAI_STT, kyutai.family)
        assertEquals("CC-BY-4.0", kyutai.license)
        assertEquals(
            listOf("model.safetensors", "mimi-pytorch-e351c8d8@125.safetensors", "tokenizer_en_fr_audio_8000.model"),
            kyutai.files.map { it.path },
        )
        for (f in kyutai.files) {
            assertEquals("https://huggingface.co/kyutai/stt-1b-en_fr/resolve/main/${f.path}", f.url)
            assertTrue(f.sha256, Regex("[0-9a-f]{64}").matches(f.sha256))
            assertTrue(f.size > 0)
        }
        assertEquals(2_363_188_798L, kyutai.totalBytes)
    }

    @Test fun notOfferedNorSelectableBeforeItsEngine() {
        assertTrue(kyutai in ModelCatalog.upcoming)
        assertFalse(kyutai in ModelCatalog.all)
        assertFalse(kyutai in ModelCatalog.selectable)
        assertNull(ModelCatalog.byId(kyutai.id))
        assertFalse(kyutai in ModelCatalog.offered(setOf(DictationLanguage.FRENCH_QUEBEC)))
    }

    @Test fun coversFrenchAndEnglishOnly() {
        assertEquals(
            listOf(DictationLanguage.SPANISH, DictationLanguage.PORTUGUESE),
            ModelRecommendations.uncovered(kyutai, DictationLanguage.entries.toSet()),
        )
    }

    @Test fun factoryRefusesClearlyInsteadOfHandingItToSherpa() {
        val e = assertThrows(UnsupportedOperationException::class.java) {
            DefaultEngineFactory { File("unused") }.create(kyutai, File("unused"), EngineOptions())
        }
        assertTrue(e.message!!, e.message!!.contains("#123"))
    }
}
