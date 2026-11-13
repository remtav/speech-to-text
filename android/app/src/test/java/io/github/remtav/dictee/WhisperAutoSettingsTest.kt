package io.github.remtav.dictee

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.remtav.dictee.settings.AppSettings
import io.github.remtav.dictee.stt.Benchmark
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.ModelResult
import io.github.remtav.dictee.stt.RepetitionFix
import io.github.remtav.dictee.stt.RepetitionOutcome
import io.github.remtav.dictee.stt.SampleResult
import io.github.remtav.dictee.stt.SherpaEngine
import io.github.remtav.dictee.stt.WhisperTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** In-memory [SharedPreferences], enough for [AppSettings] on the JVM. */
internal class MemoryPrefs(val values: MutableMap<String, Any?> = HashMap()) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { values[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { values[key] = value }
        override fun putInt(key: String, value: Int) = apply { values[key] = value }
        override fun putLong(key: String, value: Long) = apply { values[key] = value }
        override fun putFloat(key: String, value: Float) = apply { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
        override fun remove(key: String) = apply { values.remove(key) }
        override fun clear() = apply { values.clear() }
        override fun commit() = true
        override fun apply() = Unit
    }
}

/**
 * Issue #49: « Automatique » choice of « Silence ajouté pour Whisper » and « Correction des répétitions
 * (Whisper) ». Defaults unchanged (1 000, no filter), stored values kept, correction inert outside « Automatique ».
 */
class WhisperAutoSettingsTest {
    private fun settings(prefs: MemoryPrefs = MemoryPrefs()): AppSettings {
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        return AppSettings(context, defaultServerUrl = "")
    }

    private val whisperModels = ModelCatalog.all.filter { it.family == ModelFamily.WHISPER }
    private val otherModels = ModelCatalog.all.filter { it.family != ModelFamily.WHISPER && it.family != ModelFamily.REMOTE }

    @Test fun defaultsAreUnchanged() {
        val s = settings()
        assertEquals(1000, s.whisperTailPaddings)
        assertFalse(s.whisperTailAuto)
        assertEquals(RepetitionFix.REDECODE, s.whisperRepetitionFix)
        for (spec in whisperModels) {
            val o = s.engineOptions(spec)
            assertEquals(spec.id, 1000, o.whisperTailPaddings)
            assertNull(spec.id, o.whisperRepetitionFix)
        }
        // The engine options of a fresh install are exactly those before issue #49.
        assertNull(EngineOptions().whisperRepetitionFix)
    }

    @Test fun storedFixedPaddingsAreKept() {
        for (stored in listOf(300, 600, 1000)) {
            val s = settings(MemoryPrefs(mutableMapOf("whisper_tail_paddings" to stored)))
            assertEquals(stored, s.whisperTailPaddings)
            assertFalse(s.whisperTailAuto)
            val o = s.engineOptions(ModelCatalog.whisperTurbo)
            assertEquals(stored, o.whisperTailPaddings)
            assertNull("a fixed padding has no filter, as before #49", o.whisperRepetitionFix)
        }
    }

    @Test fun automaticIsShortPaddingPlusFilter() {
        val s = settings(MemoryPrefs(mutableMapOf("whisper_tail_paddings" to 600)))
        s.whisperTailAuto = true
        for (spec in whisperModels) {
            val o = s.engineOptions(spec)
            assertEquals(spec.id, 300, o.whisperTailPaddings)
            assertEquals(spec.id, RepetitionFix.REDECODE, o.whisperRepetitionFix)
        }
        s.whisperRepetitionFix = RepetitionFix.CUT
        assertEquals(RepetitionFix.CUT, s.engineOptions(ModelCatalog.whisperTurbo).whisperRepetitionFix)
        // Leaving « Automatique » brings the stored fixed value back.
        s.whisperTailAuto = false
        assertEquals(600, s.engineOptions(ModelCatalog.whisperTurbo).whisperTailPaddings)
    }

    @Test fun correctionHasNoEffectOutsideAutomatic() {
        val s = settings()
        for (fix in RepetitionFix.entries) {
            s.whisperRepetitionFix = fix
            for (tail in WhisperTuning.TAIL_PADDING_CHOICES) {
                s.whisperTailPaddings = tail
                val o = s.engineOptions(ModelCatalog.whisperTurbo)
                assertEquals(EngineOptions(numThreads = o.numThreads, language = o.language, whisperTailPaddings = tail), o)
                assertNull(o.whisperRepetitionFix)
            }
        }
    }

    @Test fun otherModelsNeverGetTheFilter() {
        val s = settings()
        s.whisperTailAuto = true
        s.whisperRepetitionFix = RepetitionFix.CUT
        for (spec in otherModels) {
            val o = s.engineOptions(spec)
            assertEquals(spec.id, 1000, o.whisperTailPaddings)
            assertNull(spec.id, o.whisperRepetitionFix)
        }
    }

    @Test fun unknownStoredCorrectionReadsAsRedecode() {
        assertEquals(RepetitionFix.REDECODE, settings(MemoryPrefs(mutableMapOf("whisper_repetition_fix" to "AUTRE"))).whisperRepetitionFix)
        assertEquals(RepetitionFix.CUT, settings(MemoryPrefs(mutableMapOf("whisper_repetition_fix" to "CUT"))).whisperRepetitionFix)
    }

    @Test fun automaticModeIsPartOfTheEngineKey() {
        // EngineManager reloads when the options change: switching to « Automatique » or changing its correction reloads.
        val s = settings()
        val fixed300 = s.apply { whisperTailPaddings = 300 }.engineOptions(ModelCatalog.whisperTurbo)
        s.whisperTailAuto = true
        val auto = s.engineOptions(ModelCatalog.whisperTurbo)
        assertNotEquals(fixed300, auto)
        s.whisperRepetitionFix = RepetitionFix.CUT
        assertNotEquals(auto, s.engineOptions(ModelCatalog.whisperTurbo))
    }

    @Test fun engineConfigUsesTheShortPaddingInAutomatic() {
        val cfg = SherpaEngine.buildConfig(ModelCatalog.whisperTurbo, File("/x"),
            EngineOptions(whisperTailPaddings = WhisperTuning.AUTO_TAIL_PADDINGS, whisperRepetitionFix = RepetitionFix.REDECODE))
        assertEquals(300, cfg.modelConfig.whisper.tailPaddings)
        assertEquals(1000, WhisperTuning.REDECODE_TAIL_PADDINGS)
    }

    @Test fun tuningHelpers() {
        for (spec in whisperModels) {
            assertEquals(300, WhisperTuning.tailPaddingsFor(spec, 1000, auto = true))
            assertEquals(600, WhisperTuning.tailPaddingsFor(spec, 600, auto = false))
            assertEquals(RepetitionFix.CUT, WhisperTuning.repetitionFixFor(spec, true, RepetitionFix.CUT))
            assertNull(WhisperTuning.repetitionFixFor(spec, false, RepetitionFix.CUT))
        }
        for (spec in otherModels) {
            assertEquals(1000, WhisperTuning.tailPaddingsFor(spec, 300, auto = true))
            assertNull(WhisperTuning.repetitionFixFor(spec, true, RepetitionFix.REDECODE))
        }
    }

    // ------------------------------------------------------------ « Comparer les modèles » report

    @Test fun reportStatesTheModeAndCountsCorrections() {
        assertEquals("silence ajouté Whisper 600", WhisperTuning.describe(600, null))
        assertEquals("silence ajouté Whisper automatique (300 + correction des répétitions : redécoder à 1 000)",
            WhisperTuning.describe(300, RepetitionFix.REDECODE))
        assertEquals("silence ajouté Whisper automatique (300 + correction des répétitions : couper le texte répété)",
            WhisperTuning.describe(300, RepetitionFix.CUT))

        fun sample(name: String, o: RepetitionOutcome) = SampleResult(name, "fr", "texte", 100, 2f, null, o)
        val r = ModelResult(ModelCatalog.whisperTurbo, "cpu", 1000, 1200, 100, listOf(
            sample("a", RepetitionOutcome.NONE), sample("b", RepetitionOutcome.REDECODED),
            sample("c", RepetitionOutcome.REDECODED), sample("d", RepetitionOutcome.NONE),
        ))
        val report = Benchmark.report(listOf(r), "en-tête") { true }
        assertTrue(report, "répétitions corrigées : 2 redécodé(s) sur 4 segment(s)" in report)
        assertTrue(report, "[b fr]" in report && "répétition redécodée" in report)
        // Outside automatic mode, nothing is added.
        val plain = Benchmark.report(listOf(r.copy(samples = r.samples.map { it.copy(repetition = RepetitionOutcome.NONE) })), "")
        assertFalse(plain, "répétition" in plain)

        val cut = r.copy(samples = listOf(sample("a", RepetitionOutcome.CUT), sample("b", RepetitionOutcome.DETECTED), sample("c", RepetitionOutcome.NONE)))
        assertEquals("   répétitions corrigées : 1 coupé(s), 1 détecté(s) sans coupure sur 3 segment(s)", Benchmark.repetitionSummary(cut, true))
        assertEquals("   répétitions corrigées : aucune sur 4 segment(s)",
            Benchmark.repetitionSummary(r.copy(samples = r.samples.map { it.copy(repetition = RepetitionOutcome.NONE) }), true))
    }
}
