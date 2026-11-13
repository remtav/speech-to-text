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

    /** How the Whisper padding is described in the « Copier » report of the comparison screen. */
    fun describe(tailPaddings: Int, fix: RepetitionFix?): String =
        if (fix == null) "silence ajouté Whisper $tailPaddings"
        else "silence ajouté Whisper automatique ($tailPaddings + correction des répétitions : ${fix.reportLabel})"

    /**
     * « Regrouper les énoncés (Whisper) » (issue #53): 0 (off, the default) or the minimum length of a group, in
     * seconds. Two values to try on the phone; see [io.github.remtav.dictee.dictation.LocalGrouping].
     */
    val GROUP_CHOICES = listOf(0, 5, 10)

    /** A stored value outside [GROUP_CHOICES] reads as off. */
    fun sanitizeGroupSeconds(value: Int): Int = if (value in GROUP_CHOICES) value else 0

    /**
     * The grouping of a local dictation with [spec]: only for Whisper (the issue's scope; Parakeet and Qwen3-ASR are
     * left as they are), null when the setting is off.
     */
    fun groupingFor(spec: ModelSpec, seconds: Int): io.github.remtav.dictee.dictation.LocalGrouping? {
        val v = sanitizeGroupSeconds(seconds)
        return if (spec.family == ModelFamily.WHISPER && v > 0) io.github.remtav.dictee.dictation.LocalGrouping(v.toFloat()) else null
    }

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
