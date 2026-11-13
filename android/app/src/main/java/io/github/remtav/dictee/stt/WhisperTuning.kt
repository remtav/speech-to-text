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

    /** The value handed to the engine of [spec]: the setting for Whisper, the default (so no reload) otherwise. */
    fun tailPaddingsFor(spec: ModelSpec, setting: Int): Int =
        if (spec.family == ModelFamily.WHISPER) sanitizeTailPaddings(setting) else DEFAULT_TAIL_PADDINGS

    /**
     * Whether provisional (underlined) decodes may run. Never with the server (one HTTP request
     * per 0.7 s), never with Whisper: they cost 2.8 times the final decode and delay it by
     * 1.5 s on average (measured on the host); the "provisional text" setting applies to the others.
     */
    fun provisionalAllowed(spec: ModelSpec, setting: Boolean): Boolean =
        setting && spec.family != ModelFamily.REMOTE && spec.family != ModelFamily.WHISPER
}
