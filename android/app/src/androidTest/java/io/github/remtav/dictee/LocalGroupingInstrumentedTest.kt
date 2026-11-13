package io.github.remtav.dictee

import android.widget.RadioGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.remtav.dictee.audio.SileroVad
import io.github.remtav.dictee.audio.WavReader
import io.github.remtav.dictee.dictation.LocalCut
import io.github.remtav.dictee.dictation.LocalGrouping
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.WhisperTuning
import io.github.remtav.dictee.ui.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #53 on the device (lesson of PR #52: what passes on the JVM can fail on Android): the local cut with the real
 * Silero VAD of the APK, the report wording, and « Regrouper les énoncés (Whisper) » in « Avancé », off by default.
 */
@RunWith(AndroidJUnit4::class)
class LocalGroupingInstrumentedTest {
    private val app get() = DicteeApp.from(ApplicationProvider.getApplicationContext())
    private var saved: Any? = null

    @Before fun save() {
        saved = app.settings.prefs.all["whisper_group_seconds"]
        app.settings.prefs.edit().remove("whisper_group_seconds").commit()
    }

    @After fun restore() {
        app.settings.prefs.edit().apply {
            (saved as? Int)?.let { putInt("whisper_group_seconds", it) } ?: remove("whisper_group_seconds")
        }.commit()
    }

    /** The bundled French clips one after the other, 0.7 s apart, cut as a dictation with and without groups. */
    @Test fun localCutRunsWithTheSileroVadOfTheApk() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val clips = listOf("fr1", "frqc1", "frqc2", "frqc3").map { name ->
            ctx.assets.open("reference/$name.wav").use { WavReader.read(it) }.resampled(16000).samples
        }
        val gap = FloatArray(16000 * 7 / 10)
        val audio = clips.flatMap { it.toList() + gap.toList() }.toFloatArray()
        fun cut(g: LocalGrouping?) = SileroVad.fromAssets(ctx.assets).let { vad ->
            try { LocalCut.cut(audio, vad, g) } finally { vad.release() }
        }
        val each = cut(null)
        val grouped = cut(LocalGrouping(10f))
        assertTrue("utterances: ${each.size}", each.size >= clips.size)
        // These clips have their own long silences: on the host, each one stays its own group (4 for 4); never more.
        assertTrue("groups: ${grouped.size} for ${each.size} utterances", grouped.isNotEmpty() && grouped.size <= each.size)
        grouped.forEach { assertTrue(it.audio.size <= 30 * 16000) }
        // Every utterance is in a group.
        assertEquals(each.size, grouped.sumOf { it.utteranceEnds.size })
    }

    @Test fun reportWordingOnTheDevice() {
        assertEquals(
            "regroupement des énoncés Whisper 10 s (groupe fermé à la 1re pause passé ce seuil, après 1 s de silence, 30 s au plus)",
            LocalGrouping(10f).describe(),
        )
    }

    @Test fun settingIsInAdvancedAndOffByDefault() {
        assertEquals(0, app.settings.whisperGroupSeconds)
        assertNull(WhisperTuning.groupingFor(ModelCatalog.whisperTurbo, app.settings.whisperGroupSeconds))
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            lateinit var group: RadioGroup
            s.onActivity { a ->
                val advanced = a.window.decorView.findViewWithTag<android.view.ViewGroup>(SettingsActivity.ADVANCED_TAG)
                group = advanced.findViewWithTag(SettingsActivity.WHISPER_GROUP_TAG)
                assertNotNull("« Regrouper les énoncés » is in « Avancé »", group)
                assertEquals(3, group.childCount)
                assertEquals(group.getChildAt(0).id, group.checkedRadioButtonId)
                group.getChildAt(1).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(5, app.settings.whisperGroupSeconds)
            assertEquals(LocalGrouping(5f), WhisperTuning.groupingFor(ModelCatalog.whisperTurbo, app.settings.whisperGroupSeconds))
            s.onActivity { group.getChildAt(2).performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(10, app.settings.whisperGroupSeconds)
            s.onActivity { group.getChildAt(0).performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(0, app.settings.whisperGroupSeconds)
        }
    }
}
