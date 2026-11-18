package io.github.remtav.dictee.dictation

import io.github.remtav.dictee.audio.AudioSource
import io.github.remtav.dictee.audio.VoiceActivityDetector
import io.github.remtav.dictee.stt.BlockDictation
import io.github.remtav.dictee.stt.DictationResult
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.Transcription
import io.github.remtav.dictee.stt.WaitControl

enum class DictationState { IDLE, LISTENING, FINISHING, ERROR }

/** Everything a dictation session needs, snapshotted when it starts. */
class DictationSession(
    val spec: ModelSpec,
    val audio: AudioSource,
    val vad: VoiceActivityDetector,
    val pipeline: TextPipeline,
    /** Transcribes one chunk with the session's model; blocks while the model loads. */
    val transcribe: (FloatArray) -> Transcription,
    val partials: Boolean = true,
    /**
     * Whole-dictation mode (remote server, issue #13): when set, nothing is
     * transcribed while the user speaks. The whole audio (and the VAD segments,
     * kept for a local fallback) goes to this function once the dictation ends.
     */
    val whole: ((audio: FloatArray, segments: List<FloatArray>, control: WaitControl) -> DictationResult)? = null,
    /**
     * Block mode (remote server, issue #35): when set, the audio is cut into blocks at pauses while the user
     * speaks, and each block goes to the server at once (see [BlockDictation]); at the end only the last block
     * and the polish remain. [whole] stays the way for a server that cannot do blocks (no `POST /polish`).
     */
    val blocks: ((control: WaitControl) -> BlockDictation)? = null,
    /** Block size: closed at the first pause past [minBlockSeconds], at [maxBlockSeconds] at the latest. */
    val minBlockSeconds: Float = 15f,
    val maxBlockSeconds: Float = 30f,
    /** Longest whole (or block) dictation; recording stops there and what was said is sent. */
    val maxWholeSeconds: Int = 300,
    /** False when no local model can take over: "Annuler" would only drop the dictation, so it is hidden. */
    val canFallBack: Boolean = true,
    /**
     * Local dictation (issue #53, « Regrouper les énoncés (Whisper) »): the VAD's utterances are grouped before being
     * transcribed, see [LocalGrouping]. Null (the default): one transcription per utterance, as before.
     */
    val localGrouping: LocalGrouping? = null,
)

interface DictationListener {
    fun onState(state: DictationState, message: String? = null) {}
    /** Input level, 0..1, a few times per second while listening. */
    fun onLevel(level: Float) {}
    fun onSpeech(active: Boolean) {}
    /** A final segment was transcribed and inserted. */
    fun onSegment(transcription: Transcription, inserted: String) {}
    /** Whole-dictation mode: waiting for the server (true), or the wait is over (false). */
    fun onServerWait(waiting: Boolean) {}
    /** Information that is not an error (maximum duration reached). */
    fun onInfo(message: String) {}
    /** Whole-dictation mode: the result was inserted ([inserted] is what was typed). */
    fun onDictationResult(result: DictationResult, inserted: String) {}
    /** Block mode: [done] of the [total] blocks known so far are transcribed. */
    fun onBlocks(done: Int, total: Int) {}
}
