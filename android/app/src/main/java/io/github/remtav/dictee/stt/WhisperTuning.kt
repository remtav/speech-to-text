package io.github.remtav.dictee.stt

/**
 * Speed settings specific to Whisper (issue #15, user request: Whisper turbo is too slow
 * on the phone). Pure functions, so the rules are unit-tested without a model.
 * See docs/ACCELERER_WHISPER.md.
 */
object WhisperTuning {
    /** sherpa-onnx's own default (`tail_paddings` -1 means 1000 frames, 10 s of silence). Unchanged behaviour. */
    const val DEFAULT_TAIL_PADDINGS = 1000

    /** Allowed values of the "silence added for Whisper" setting, slowest and safest first. */
    val TAIL_PADDING_CHOICES = listOf(1000, 600, 300)

    /** A stored value outside [TAIL_PADDING_CHOICES] reads as the default. */
    fun sanitizeTailPaddings(value: Int): Int = if (value in TAIL_PADDING_CHOICES) value else DEFAULT_TAIL_PADDINGS

    /**
     * The value handed to the engine of [spec]: the setting for Whisper ([AUTO_TAIL_PADDINGS] in automatic
     * mode), the default (so no reload) otherwise.
     */
    fun tailPaddingsFor(spec: ModelSpec, setting: Int, auto: Boolean = false): Int = when {
        spec.family != ModelFamily.WHISPER -> DEFAULT_TAIL_PADDINGS
        auto -> AUTO_TAIL_PADDINGS
        else -> sanitizeTailPaddings(setting)
    }

    /**
     * « Automatique » choice of « Silence ajouté pour Whisper » (issue #49): a short padding of 300 frames
     * plus the repetition filter ([io.github.remtav.dictee.text.WhisperRepetition]).
     */
    const val AUTO_TAIL_PADDINGS = 300

    /** Padding of the second decode when [RepetitionFix.REDECODE] fires: the default, the reference quality. */
    const val REDECODE_TAIL_PADDINGS = DEFAULT_TAIL_PADDINGS

    /** The correction handed to the engine of [spec]: only for Whisper in automatic mode, null (no filter) otherwise. */
    fun repetitionFixFor(spec: ModelSpec, auto: Boolean, fix: RepetitionFix): RepetitionFix? =
        if (spec.family == ModelFamily.WHISPER && auto) fix else null

    /**
     * sherpa-onnx 2.0.1 stops Whisper after this many tokens: 6 per second of the samples given, tail padding
     * not counted (`offline-whisper-greedy-search-decoder.cc`). Same formula as
     * [io.github.remtav.dictee.text.WhisperRepetition.tokenCeiling].
     */
    fun tokenCeiling(samples: Int, sampleRate: Int): Int =
        io.github.remtav.dictee.text.WhisperRepetition.tokenCeiling(samples, sampleRate)

    /** The decode stopped at the ceiling (within one token: the frame count of sherpa-onnx can be one lower). */
    fun atTokenCeiling(tokens: Int, samples: Int, sampleRate: Int): Boolean {
        val ceiling = tokenCeiling(samples, sampleRate)
        return ceiling > 0 && tokens >= ceiling - 1
    }

    /**
     * Silence (in samples) added after a segment that ran into the token ceiling: as long as the segment, which
     * doubles the ceiling, within the 30 s sherpa-onnx decodes ([MAX_DECODE_SECONDS]); 0 when there is no room.
     */
    fun ceilingSilence(samples: Int, sampleRate: Int): Int {
        val room = (MAX_DECODE_SECONDS * sampleRate).toInt() - samples
        return minOf(samples, room).coerceAtLeast(0)
    }

    /** sherpa-onnx keeps at most 3 000 - 50 frames (`offline-recognizer-whisper-impl.h`), a little margin taken. */
    const val MAX_DECODE_SECONDS = 29.4f

    /** How the Whisper padding is described in the « Copier » report of the comparison screen. */
    fun describe(tailPaddings: Int, fix: RepetitionFix?): String =
        if (fix == null) "silence ajouté Whisper $tailPaddings"
        else "silence ajouté Whisper automatique ($tailPaddings + correction des répétitions : ${fix.reportLabel})"

    /**
     * Whether provisional (underlined) decodes may run. Never with the server (one HTTP request
     * per 0.7 s), never with Whisper: they cost 2.8 times the final decode and delay it by
     * 1.5 s on average (measured on the host); the "provisional text" setting applies to the others.
     */
    fun provisionalAllowed(spec: ModelSpec, setting: Boolean): Boolean =
        setting && spec.family != ModelFamily.REMOTE && spec.family != ModelFamily.WHISPER
}

/**
 * What [io.github.remtav.dictee.stt.SherpaEngine] does with a Whisper segment in which
 * [io.github.remtav.dictee.text.WhisperRepetition] finds a repetition (issue #49, « Correction des
 * répétitions (Whisper) »). Both are kept so the user can compare them on the phone.
 */
enum class RepetitionFix(val reportLabel: String) {
    /** Decode the segment again with the default padding (1 000): best measured quality, costs a second decode. */
    REDECODE("redécoder à 1 000"),
    /** Keep the first occurrence and drop the repeated text: no second decode. */
    CUT("couper le texte répété");

    companion object {
        /** Proposed by default: the better quality on the host bench (issue #49). */
        val DEFAULT = REDECODE
        fun fromName(name: String?): RepetitionFix = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
