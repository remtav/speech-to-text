package io.github.remtav.dictee.dictation

import io.github.remtav.dictee.audio.VoiceActivityDetector
import io.github.remtav.dictee.stt.AudioBlock

/**
 * « Regrouper les énoncés (Whisper) » (issue #53): in a local dictation, the VAD's utterances are grouped, with the
 * pauses between them, before Whisper decodes them, instead of one decode per utterance. A group is closed:
 *  - at the first pause the VAD confirms once it holds [minSeconds] (as the server's blocks of #35, shorter);
 *  - after [idleSeconds] of silence following its last utterance, even if shorter: the text of a lone sentence
 *    still comes, [idleSeconds] later than without grouping (the silence itself is not decoded);
 *  - at [maxSeconds] (Whisper's 30 s window) at the latest, even mid-speech;
 *  - at the end of the dictation.
 * Cut by [BlockCutter], the class of the server's blocks; off (null) by default, see DECISIONS.md « Issue #53 ».
 */
data class LocalGrouping(
    val minSeconds: Float,
    val idleSeconds: Float = IDLE_SECONDS,
    val maxSeconds: Float = MAX_SECONDS,
) {
    /** The cutter of a dictation, with the controller's sizes: [CHUNK] under the cap, as the server's blocks. */
    internal fun cutter(history: (Int) -> FloatArray, emit: (AudioBlock) -> Unit) = BlockCutter(
        (minSeconds * DictationController.RATE).toInt(),
        (maxSeconds * DictationController.RATE).toInt() - DictationController.CHUNK,
        history,
        (idleSeconds * DictationController.RATE).toInt(),
        emit,
    )

    /** How the setting is written in the « Copier » report of the comparison screen. */
    fun describe(): String = "regroupement des énoncés Whisper ${minSeconds.toInt()} s " +
        "(groupe fermé à la 1re pause passé ce seuil, après ${fmt(idleSeconds)} s de silence, ${maxSeconds.toInt()} s au plus)"

    private fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString().replace('.', ',')

    companion object {
        /** Silence after the last utterance that closes a short group (on top of the VAD's own pause, 0.5 s by default). */
        const val IDLE_SECONDS = 1f
        /** Hard cap: Whisper's window. */
        const val MAX_SECONDS = 30f

        /** Report wording when the setting is off. */
        const val OFF_DESCRIPTION = "regroupement des énoncés Whisper : non"
    }
}

/**
 * One local decode: a VAD utterance (no grouping) or a group. [readySample] is where the capture handed it to the decode
 * thread; [utteranceEnds] the end of each VAD utterance it holds (for the latency of the bench).
 */
class LocalPiece(val audio: FloatArray, val startSample: Long, val readySample: Long, val utteranceEnds: List<Long>)

/**
 * The local cut of the dictation replayed on a recording, with the same calls as [DictationController]'s capture loop
 * (chunks of [DictationController.CHUNK], speech onset minus [DictationController.LOOKBACK], VAD flushed at the end):
 * the comparison screen and the host bench decode what a dictation would decode. [grouping] null: one piece per
 * utterance, today's local dictation.
 */
object LocalCut {
    fun cut(samples: FloatArray, vad: VoiceActivityDetector, grouping: LocalGrouping?): List<LocalPiece> {
        val chunk = DictationController.CHUNK
        val out = ArrayList<LocalPiece>()
        val ends = ArrayList<Long>() // utterance ends not yet assigned to a group
        var total = 0L
        fun history(n: Int): FloatArray {
            val from = (total - n).coerceAtLeast(0).toInt()
            return samples.copyOfRange(from, total.toInt())
        }
        fun takeEnds(block: AudioBlock): List<Long> {
            val stop = block.startSample + block.audio.size
            val mine = ends.filter { it <= stop }
            ends.removeAll(mine.toSet())
            return mine
        }
        val cutter = grouping?.cutter(::history) { b -> out += LocalPiece(b.audio, b.startSample, total, takeEnds(b)) }
        fun accept(seg: io.github.remtav.dictee.audio.SpeechChunk) {
            val end = seg.startSample + seg.samples.size
            if (cutter == null) out += LocalPiece(seg.samples, seg.startSample, total, listOf(end))
            else { ends += end; cutter.onSegment(seg) }
        }
        var wasSpeech = false
        var pos = 0
        while (pos < samples.size) {
            val n = minOf(chunk, samples.size - pos)
            val piece = samples.copyOfRange(pos, pos + n)
            pos += n
            total += n
            vad.accept(piece.copyOf())
            val speech = vad.isSpeech()
            val onset = if (speech && !wasSpeech) (total - DictationController.LOOKBACK).coerceAtLeast(0) else -1L
            wasSpeech = speech
            cutter?.onAudio(piece, n, total, onset)
            var popped = false
            while (true) {
                val seg = vad.poll() ?: break
                accept(seg)
                popped = true
            }
            cutter?.afterChunk(total, speech, popped)
        }
        vad.flush()
        while (true) accept(vad.poll() ?: break)
        cutter?.finish()?.let { b -> out += LocalPiece(b.audio, b.startSample, total, takeEnds(b) + ends.toList().also { ends.clear() }) }
        return out
    }
}
