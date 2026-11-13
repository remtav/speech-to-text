package io.github.remtav.dictee.dictation

import io.github.remtav.dictee.EnergyVad
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.WhisperTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #53: the local cut ([LocalCut], the controller's calls replayed) with and without « Regrouper les énoncés ».
 * Energy VAD with the app's default pause (0.5 s); lookback 0.6 s; idle 1 s.
 */
class LocalGroupingTest {
    private val rate = 16000

    /** Speech (noise) and silence, in seconds: positive = speech, negative = silence. Each speech sample is unique. */
    private fun stream(vararg parts: Float): FloatArray {
        val out = ArrayList<Float>()
        var k = 0
        for (p in parts) repeat((kotlin.math.abs(p) * rate).toInt()) {
            out += if (p > 0) 0.02f + (k++) * 1e-6f else 0f
        }
        return out.toFloatArray()
    }

    private fun vad(maxSpeech: Float = 20f) = EnergyVad(minSilence = rate / 2, maxSpeech = (rate * maxSpeech).toInt())

    private fun cut(audio: FloatArray, grouping: LocalGrouping?, maxSpeech: Float = 20f) = LocalCut.cut(audio, vad(maxSpeech), grouping)

    /** Every speech sample is decoded exactly once: nothing lost, nothing twice. */
    private fun assertEverySpeechSampleOnce(audio: FloatArray, pieces: List<LocalPiece>) {
        val seen = HashSet<Float>()
        for (p in pieces) for (v in p.audio) if (v != 0f) assertTrue("sample decoded twice", seen.add(v))
        assertEquals("speech samples lost", audio.count { it != 0f }, seen.size)
    }

    @Test fun offIsOnePiecePerUtteranceAsToday() {
        val audio = stream(-1f, 0.8f, -0.8f, 0.8f, -0.8f, 0.8f, -1f)
        val pieces = cut(audio, null)
        // Exactly what the VAD returns, one decode each.
        val v = vad()
        v.accept(audio.copyOf()); v.flush()
        val segments = generateSequence { v.poll() }.toList()
        assertEquals(3, pieces.size)
        assertEquals(segments.map { it.samples.toList() }, pieces.map { it.audio.toList() })
        assertEverySpeechSampleOnce(audio, pieces)
    }

    @Test fun shortUtterancesAreGroupedWithTheirPauses() {
        // Pauses of 0.8 s: the VAD sees each one (0.5 s), speech resumes 0.3 s later, before the 1 s of idle.
        // Minimum of 10 s, not reached: the group is closed by the silence that follows.
        val audio = stream(-1f, 0.8f, -0.8f, 0.8f, -0.8f, 0.8f, -3f)
        val pieces = cut(audio, LocalGrouping(10f))
        assertEquals(1, pieces.size)
        val p = pieces.single()
        assertEquals(3, p.utteranceEnds.size)
        // Lookback + 3 × 0.8 s + 2 pauses of 0.8 s, and the 0.5 s the VAD needs to see the last pause: no more.
        val expected = 0.6f + 2.4f + 1.6f + 0.5f
        assertTrue("group of ${p.audio.size / rate.toFloat()} s", kotlin.math.abs(p.audio.size / rate.toFloat() - expected) < 0.1f)
        // Handed to the decoder 1 s after the VAD saw the last pause, not at the end of the dictation.
        val lastEnd = p.utteranceEnds.last()
        assertTrue("ready ${(p.readySample - lastEnd) / rate.toFloat()} s after the end", (p.readySample - lastEnd) / rate.toFloat() in 0.9f..1.1f)
        assertEverySpeechSampleOnce(audio, pieces)
    }

    @Test fun aGroupClosesAtTheFirstPausePastTheMinimum() {
        val audio = stream(-1f, 2f, -0.8f, 2f, -0.8f, 2f, -0.8f, 2f, -1f)
        val pieces = cut(audio, LocalGrouping(5f))
        // 0.6 + 2 + 0.8 + 2 + 0.5 = 5.9 s at the second pause: closed there; the same again for the last two.
        assertEquals(listOf(2, 2), pieces.map { it.utteranceEnds.size })
        pieces.forEach { assertTrue(it.audio.size >= 5 * rate) }
        assertEquals(1, cut(audio, LocalGrouping(10f)).size)
        assertEverySpeechSampleOnce(audio, pieces)
    }

    @Test fun silenceClosesAShortGroupWithoutDecodingTheSilence() {
        val audio = stream(-0.5f, 1f, -3f, 1f, -0.5f)
        val pieces = cut(audio, LocalGrouping(5f))
        assertEquals(2, pieces.size)
        // The 3 s of silence are in neither group.
        pieces.forEach { assertTrue("group of ${it.audio.size / rate.toFloat()} s", it.audio.size < 2.2f * rate) }
        assertEverySpeechSampleOnce(audio, pieces)
    }

    @Test fun thePauseToleratedIsTheVadPausePlusTheIdleTime() {
        // VAD pause 0.5 s + idle 1 s: a 1.2 s pause stays inside the group, a 1.8 s pause closes it.
        assertEquals(1, cut(stream(-0.5f, 1f, -1.2f, 1f, -0.5f), LocalGrouping(5f)).size)
        assertEquals(2, cut(stream(-0.5f, 1f, -1.8f, 1f, -0.5f), LocalGrouping(5f)).size)
        // A longer idle time keeps it.
        assertEquals(1, cut(stream(-0.5f, 1f, -1.8f, 1f, -0.5f), LocalGrouping(5f, idleSeconds = 2f)).size)
    }

    @Test fun groupsNeverExceedWhispersWindow() {
        // 50 s without a pause (the VAD cuts at 20 s), then short utterances: 30 s at most, nothing lost.
        val audio = stream(-0.5f, 50f, -0.8f, 1f, -0.8f, 1f, -1f)
        val pieces = cut(audio, LocalGrouping(10f))
        pieces.forEach { assertTrue("group of ${it.audio.size / rate.toFloat()} s", it.audio.size <= 30 * rate) }
        assertTrue(pieces.size >= 2)
        assertEverySpeechSampleOnce(audio, pieces)
    }

    @Test fun theEndOfTheDictationFlushesTheOpenGroup() {
        // Stop right after speaking: no silence for the VAD or the idle time.
        val audio = stream(-0.5f, 1f, -0.6f, 1.5f)
        val pieces = cut(audio, LocalGrouping(5f))
        assertEquals(1, pieces.size)
        assertEquals(2, pieces.single().utteranceEnds.size)
        assertEverySpeechSampleOnce(audio, pieces)
    }

    @Test fun settingValuesAndScope() {
        assertEquals(listOf(0, 5, 10), WhisperTuning.GROUP_CHOICES)
        assertNull("off by default", WhisperTuning.groupingFor(ModelCatalog.whisperTurbo, 0))
        assertEquals(LocalGrouping(5f), WhisperTuning.groupingFor(ModelCatalog.whisperTurbo, 5))
        assertEquals(LocalGrouping(10f), WhisperTuning.groupingFor(ModelCatalog.whisperQuebec, 10))
        // Only Whisper (issue scope): Parakeet, Qwen3-ASR and the server are left as they are.
        assertNull(WhisperTuning.groupingFor(ModelCatalog.parakeet, 5))
        assertNull(WhisperTuning.groupingFor(ModelCatalog.qwen3, 5))
        assertNull(WhisperTuning.groupingFor(ModelCatalog.remote, 5))
        // A stored value outside the choices reads as off.
        assertEquals(0, WhisperTuning.sanitizeGroupSeconds(7))
        assertNull(WhisperTuning.groupingFor(ModelCatalog.whisperTurbo, 7))
    }

    @Test fun reportWording() {
        assertEquals(
            "regroupement des énoncés Whisper 5 s (groupe fermé à la 1re pause passé ce seuil, après 1 s de silence, 30 s au plus)",
            LocalGrouping(5f).describe(),
        )
        assertEquals("regroupement des énoncés Whisper : non", LocalGrouping.OFF_DESCRIPTION)
    }
}
