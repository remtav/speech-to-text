package io.github.remtav.dictee.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper

/**
 * Microphone at 16 kHz mono, VOICE_RECOGNITION preset (no AGC/noise
 * processing tuned for calls). Watches the recording configuration so a call
 * or another app taking the microphone shows up as [silenced].
 */
class MicAudioSource(private val context: Context) : AudioSource {
    private var record: AudioRecord? = null
    private var shorts = ShortArray(0)
    @Volatile private var silencedFlag = false
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val session = record?.audioSessionId ?: return
            val mine = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
            silencedFlag = mine.isClientSilenced
        }
    }

    override val silenced: Boolean get() = silencedFlag

    @SuppressLint("MissingPermission") // checked by the caller (IME shows a message instead)
    override fun start() {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw AudioSourceException("Micro indisponible")
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, rate / 2 * 2), // ~0.5 s
            )
        } catch (e: SecurityException) {
            throw AudioSourceException("Permission micro refusée", e)
        } catch (e: IllegalArgumentException) {
            throw AudioSourceException("Micro indisponible", e)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw AudioSourceException("Micro indisponible (déjà utilisé ?)")
        }
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release()
            throw AudioSourceException("Impossible de démarrer le micro", e)
        }
        record = rec
        silencedFlag = false
        audioManager.registerAudioRecordingCallback(recordingCallback, Handler(Looper.getMainLooper()))
    }

    override fun read(buffer: FloatArray): Int {
        val rec = record ?: return -1
        if (shorts.size < buffer.size) shorts = ShortArray(buffer.size)
        val n = rec.read(shorts, 0, buffer.size)
        if (n < 0) return -1
        for (i in 0 until n) buffer[i] = shorts[i] / 32768f
        return n
    }

    override fun stop() {
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        record?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        record = null
    }
}
