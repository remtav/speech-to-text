package io.github.remtav.dictee.reading

import io.github.remtav.dictee.audio.PcmAudio

/** Steps of the reading test, in order; DECLINED is the end of the road for a person who answered no. */
enum class ReadingPhase { DECLARE, DECLINED, ACCEPT, READING, SCORING, RESULT }

/** An installed on-device model the test runs, by catalogue id. */
data class ReadingModel(val id: String, val label: String)

/** Transcribes a recording with one model; the app's own implementation uses the engines of the dictation. */
fun interface ReadingTranscriber {
    fun transcribe(modelId: String, audio: PcmAudio): String
}

/** Where the scoring is, for the progress line. */
data class ReadingProgress(val model: ReadingModel, val modelIndex: Int, val modelCount: Int, val paragraph: Int, val paragraphCount: Int)

/**
 * The steps of the reading test as a state machine (issue #15, lot 4, step 4.2), without any Android
 * class: the activity only draws it, the JVM tests drive it with a scripted transcriber.
 *
 * Privacy rules enforced here, not only in the screens:
 * - nothing is recorded before the acceptance: [store] works only in [ReadingPhase.READING];
 * - a negative answer ends the session in [ReadingPhase.DECLINED] and no recording can be stored;
 * - the audio stays in this object (memory) until [clear] (the screen closing); no file is ever written.
 *
 * [models] are the installed on-device models to run (a single one works as several; the user's own
 * server is never in the list: it would send the voice over the network).
 */
class ReadingSession(
    val material: ReadingMaterial,
    val models: List<ReadingModel>,
    val recommendedModelId: String,
    val activeModelId: String,
    declaration: Declaration,
    private val saveDeclaration: (Declaration) -> Unit = {},
) {
    val paragraphCount: Int = material.text.paragraphs.size
    private val audio = arrayOfNulls<PcmAudio>(paragraphCount)

    var declaration: Declaration = declaration
        private set

    // Read by the screen while a worker thread runs the scoring.
    @Volatile
    var phase: ReadingPhase = when (declaration) {
        Declaration.UNANSWERED -> ReadingPhase.DECLARE
        Declaration.YES -> ReadingPhase.ACCEPT
        Declaration.NO -> ReadingPhase.DECLINED
    }
        private set

    /** Index of the paragraph on screen. */
    var current: Int = 0
        private set

    @Volatile
    var result: ReadingResult? = null
        private set

    /** The test can start only with at least one model to transcribe with. */
    val canStart: Boolean get() = models.isNotEmpty()

    fun audioOf(index: Int): PcmAudio? = audio[index]

    val recordedCount: Int get() = audio.count { it != null }

    val allRecorded: Boolean get() = recordedCount == paragraphCount

    /** The question is answered (and remembered): yes goes on to the acceptance, no ends the session. */
    fun answer(yes: Boolean) {
        check(phase == ReadingPhase.DECLARE) { "question déjà répondue" }
        declaration = if (yes) Declaration.YES else Declaration.NO
        saveDeclaration(declaration)
        phase = if (yes) ReadingPhase.ACCEPT else ReadingPhase.DECLINED
    }

    /** The test is accepted (nothing leaves the phone): reading starts. False when no model is installed. */
    fun accept(): Boolean {
        check(phase == ReadingPhase.ACCEPT) { "acceptation hors de son étape" }
        if (!canStart) return false
        phase = ReadingPhase.READING
        current = 0
        return true
    }

    /** Whether the microphone may be opened now: only while reading. */
    val mayRecord: Boolean get() = phase == ReadingPhase.READING

    /** Keeps (or, with null, drops) the recording of paragraph [index]; refused outside the reading step. */
    fun store(index: Int, recording: PcmAudio?): Boolean {
        if (!mayRecord) return false
        audio[index] = recording
        return true
    }

    val canGoNext: Boolean get() = mayRecord && audio[current] != null && current < paragraphCount - 1
    val canGoPrevious: Boolean get() = mayRecord && current > 0

    fun next() { if (canGoNext) current++ }
    fun previous() { if (canGoPrevious) current-- }

    /** Scoring is possible as soon as one paragraph has been read (the person may stop whenever they want). */
    val canScore: Boolean get() = phase == ReadingPhase.READING && recordedCount > 0

    /** Enters the scoring step (the screen shows it at once; [score] then runs on a worker thread). */
    fun beginScoring() {
        check(canScore) { "rien à noter" }
        phase = ReadingPhase.SCORING
    }

    /**
     * Transcribes every recorded paragraph with every model, one model at a time (a model is loaded once),
     * and scores them. Returns null, back in the reading step, when [cancelled] turns true. A model that
     * fails is reported in [ReadingResult.modelErrors] and left out of the scores; the others go on.
     */
    fun score(
        transcriber: ReadingTranscriber,
        progress: (ReadingProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): ReadingResult? {
        if (phase == ReadingPhase.READING) beginScoring()
        check(phase == ReadingPhase.SCORING) { "pas en cours de notation" }
        return try {
            run(transcriber, progress, cancelled)
        } catch (e: Throwable) {
            phase = ReadingPhase.READING
            throw e
        }
    }

    private fun run(
        transcriber: ReadingTranscriber,
        progress: (ReadingProgress) -> Unit,
        cancelled: () -> Boolean,
    ): ReadingResult? {
        val read = audio.indices.filter { audio[it] != null }
        val texts = LinkedHashMap<String, Map<Int, String>>()
        val errors = LinkedHashMap<String, String>()
        for ((mi, model) in models.withIndex()) {
            val perParagraph = LinkedHashMap<Int, String>()
            try {
                for (p in read) {
                    if (cancelled()) {
                        phase = ReadingPhase.READING
                        return null
                    }
                    progress(ReadingProgress(model, mi, models.size, p, paragraphCount))
                    perParagraph[p] = transcriber.transcribe(model.id, audio[p]!!)
                }
                texts[model.id] = perParagraph
            } catch (e: Exception) {
                errors[model.id] = e.message ?: e.javaClass.simpleName
            }
        }
        val scores = texts.mapValues { (_, perParagraph) ->
            perParagraph.mapValues { (p, text) -> ReadingScoring.scoreParagraph(material, p, text) }
        }
        val paragraphs = read.map { p ->
            ParagraphResult(
                index = p,
                audio = audio[p]!!,
                transcriptions = texts.mapNotNull { (id, per) -> per[p]?.let { id to it } }.toMap(),
                scores = scores.mapNotNull { (id, per) -> per[p]?.let { id to it } }.toMap(),
            )
        }
        val global = scores.mapValues { (_, per) -> ReadingScoring.global(per.values, paragraphCount) }
        val built = ReadingResult(
            textVersion = material.version,
            paragraphs = paragraphs,
            globalScores = global,
            modelErrors = errors,
            recommendedModelId = recommendedModelId,
            activeModelId = activeModelId,
            betterModelId = ReadingThresholds.betterModel(global, activeModelId),
            offerSharing = ReadingThresholds.offerSharing(global, recommendedModelId),
        )
        result = built
        phase = ReadingPhase.RESULT
        return built
    }

    /** "Refaire le test": drops every recording and the result, back to the first paragraph. */
    fun restart() {
        check(phase == ReadingPhase.RESULT) { "pas de résultat à refaire" }
        clear()
        phase = ReadingPhase.READING
        current = 0
    }

    /** Drops every recording and the result (the screen closing). Safe at any time. */
    fun clear() {
        audio.fill(null)
        result = null
    }
}
