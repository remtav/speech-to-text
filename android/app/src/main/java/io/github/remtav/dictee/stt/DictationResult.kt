package io.github.remtav.dictee.stt

/** Outcome of a whole dictation. */
data class DictationResult(
    /**
     * Texts to insert in order: one for a server result (the polished text, or the
     * raw one), one per VAD segment for the local fallback.
     */
    val texts: List<String>,
    val source: Source,
    /** Raw server transcript (empty for the local fallback). */
    val rawText: String = "",
    /** Polished text when the server polish step succeeded. */
    val polishedText: String? = null,
    /** Server polish status: "applied", "not requested", or the server's fallback reason. */
    val polishStatus: String = "",
    val language: String = "",
    /** Time to send the audio (upload + job creation). */
    val uploadMillis: Long = 0,
    /** Time from the start of the request to the result. */
    val totalMillis: Long = 0,
    val audioSeconds: Float = 0f,
    /** "jobs", "sync", "blocks" or "local". */
    val transport: String = "",
    /** Why the server was not used, for the local fallback (whole or some blocks). */
    val fallbackReason: String? = null,
    /** Block dictation (issue #35): number of blocks, and how many of them the phone transcribed. */
    val blocks: Int = 0,
    val localBlocks: Int = 0,
    /** Blocks transcribed neither by the server nor by the phone (the others are inserted). */
    val lostBlocks: Int = 0,
    /** Time from the end of the dictation (stop, or the end of the audio) to the result, set by the controller. */
    val waitMillis: Long = 0,
) {
    enum class Source { SERVER_POLISHED, SERVER_RAW, LOCAL_FALLBACK }
}

/** An engine that can transcribe a whole dictation at once (the remote server). */
interface WholeDictationEngine {
    /**
     * Transcribes the whole dictation [audio]. [segments] are the VAD's speech
     * segments of the same audio, used by a local fallback. Blocking.
     */
    fun transcribeDictation(audio: FloatArray, segments: List<FloatArray>, control: WaitControl): DictationResult
}

/** Logger for the remote path; never receives a secret (messages are redacted). */
fun interface RemoteLog {
    fun log(message: String)

    companion object {
        val NONE = RemoteLog { }
    }
}
