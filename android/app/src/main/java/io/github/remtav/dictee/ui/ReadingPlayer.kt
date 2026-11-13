package io.github.remtav.dictee.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.remtav.dictee.audio.PcmAudio
import kotlin.concurrent.thread

/**
 * Plays the recordings of the reading test back (issue #15, step 4.3: « Réécouter » before sharing), from
 * memory, one paragraph after the other. No file, no network. [stop] ends it at once; [onEnd] runs on the
 * player thread when the playback ends, stopped or not.
 */
class ReadingPlayer {
    @Volatile private var stopRequested = false
    @Volatile var playing = false
        private set

    fun play(clips: List<PcmAudio>, onEnd: () -> Unit) {
        if (playing || clips.isEmpty()) return
        stopRequested = false
        playing = true
        thread(name = "reading-playback") {
            val rate = clips.first().sampleRate
            val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, rate / 5 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                null
            }
            try {
                if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                    track.play()
                    val chunk = ShortArray(rate / 10)
                    loop@ for (clip in clips) {
                        val audio = clip.resampled(rate)
                        var pos = 0
                        while (pos < audio.samples.size) {
                            if (stopRequested) break@loop
                            val n = minOf(chunk.size, audio.samples.size - pos)
                            for (i in 0 until n) chunk[i] = (audio.samples[pos + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            track.write(chunk, 0, n)
                            pos += n
                        }
                    }
                    if (!stopRequested) {
                        track.stop() // plays what is queued, then stops
                        Thread.sleep(300)
                    } else {
                        track.pause()
                    }
                }
            } finally {
                track?.release()
                playing = false
                onEnd()
            }
        }
    }

    fun stop() { stopRequested = true }
}
