package io.github.remtav.dictee.stt

/**
 * Server dictation transcribed block by block while the user speaks (issue #35, lot 1).
 *
 * The controller cuts the audio into blocks of 15 to 30 s at pauses and hands each one to [add] on its decode
 * thread, in order, as soon as it is complete. Each block goes to the server (`POST /jobs`, `quick`, no polish,
 * with the end of the previous text as `prompt` and the language of the first block); the texts are kept in
 * order. At the end, [finish] polishes the whole text once (`POST /polish`) and returns it, to be inserted in one
 * go like a whole dictation.
 *
 * Nothing is lost: a block the server does not transcribe (network, 5xx, 429, timeout, "Annuler") is transcribed
 * by the local fallback model from its kept audio, and the texts already received are kept; a polish that fails
 * or is not applied leaves the raw text. A dropped dictation ([WaitControl.abandon]) sends nothing more and
 * returns nothing.
 *
 * Not thread-safe: one decode thread calls [prepare], [add], [endPhase] and [finish], in that order.
 */
class BlockDictation(
    private val backend: BlockBackend,
    private val control: WaitControl,
    private val clock: () -> Long = ::monotonicMillis,
) {
    /** Texts per block, in order: one for a server block, one per VAD segment for a block done on the phone. */
    private val texts = ArrayList<List<String>>()
    private var serverBlocks = 0
    private var localBlocks = 0
    private var audioSamples = 0L
    /** Language detected on the first server block, imposed on the next ones (when none is set). */
    private var language = ""
    /** Why some block did not go to the server (the first reason). */
    private var reason: String? = null
    /** Blocks neither the server nor the phone transcribed yet: tried once more at the end. */
    private val retry = ArrayList<Pair<Int, AudioBlock>>()
    /** Blocks lost after every try: the others are still inserted. */
    private var lostBlocks = 0
    private var endDeadline = Long.MAX_VALUE
    private var ended = false
    /** The phone took over after the end of the dictation: the wait is over, no polish request after it. */
    private var localAfterEnd = false
    /** A dictation of a single block is polished in the same job (no separate `POST /polish`). */
    private var singlePolish: BlockText? = null

    val blocks: Int get() = texts.size

    /**
     * Before the first block: false when the server has no `POST /polish` (404/405, an older server), the
     * dictation then goes whole, as before. Asked only when the final polish is wanted; any other answer
     * (failure, timeout) keeps the blocks, whose own fallback covers a dead server.
     */
    fun prepare(): Boolean {
        val server = backend.blockServer ?: return false
        if (!server.polishWanted || !backend.serverAvailable()) return true
        val child = control.child()
        return try {
            server.supportsBlocks(child)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: RemoteException) {
            if (e.failure != RemoteFailure.CANCELLED) {
                backend.serverFailed(e)
                note(e.message)
            }
            true
        } catch (e: Exception) {
            true
        } finally {
            child.close()
        }
    }

    /**
     * The dictation ended (stop, maximum duration, end of the audio): blocks not sent yet share one wait budget
     * from now, for [pendingSeconds] of audio (the "délai avant repli" of issue #23); past it, they go local.
     */
    fun endPhase(pendingSeconds: Float) {
        if (ended) return
        ended = true
        backend.blockServer?.let { endDeadline = clock() + it.budgetMillis(pendingSeconds) }
    }

    /** Transcribes [block] (blocking). [last]: the final block of the dictation. Never throws for a server failure. */
    fun add(block: AudioBlock, last: Boolean = false) {
        val i = texts.size
        texts += emptyList<String>()
        audioSamples += block.audio.size
        if (control.mode == WaitControl.Mode.ABANDON) return
        val server = backend.blockServer
        val why = when {
            server == null -> "pas de serveur"
            control.mode == WaitControl.Mode.TO_LOCAL -> "attente annulée"
            !backend.serverAvailable() -> "serveur en échec récent"
            clock() >= endDeadline -> "délai dépassé"
            else -> null
        }
        if (why == null && server != null) {
            val single = last && i == 0 && server.polishWanted
            val child = control.child()
            try {
                val r = server.transcribeBlock(block.audio, language, previousText(i), single, endDeadline, child)
                texts[i] = listOfNotNull(r.text.takeIf { it.isNotBlank() })
                serverBlocks++
                if (language.isEmpty()) language = r.language
                if (single) singlePolish = r
                return
            } catch (e: InterruptedException) {
                throw e
            } catch (e: RemoteException) {
                // A user cancel says nothing about the server.
                if (e.failure != RemoteFailure.CANCELLED) backend.serverFailed(e)
                note(e.message)
            } catch (e: Exception) {
                backend.serverFailed(e)
                note("erreur : ${e.javaClass.simpleName}")
            } catch (e: OutOfMemoryError) {
                note("mémoire insuffisante pour l'envoi")
            } finally {
                child.close()
            }
        } else {
            note(why)
        }
        if (control.mode == WaitControl.Mode.ABANDON) return
        // No local model, or it failed: keep the audio, the block gets one more try at the end.
        if (!backend.canFallBack || !transcribeLocally(i, block)) retry += i to block
    }

    /** False when the local model failed (not loaded, out of memory...): the block is kept for a retry. */
    private fun transcribeLocally(i: Int, block: AudioBlock): Boolean {
        if (ended) localAfterEnd = true
        control.localStarted()
        val out = ArrayList<String>()
        try {
            for (piece in localPieces(block)) {
                if (control.mode == WaitControl.Mode.ABANDON || Thread.currentThread().isInterrupted) return true
                backend.transcribeLocally(piece).trim().takeIf { it.isNotEmpty() }?.let { out += it }
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Throwable) {
            note("modèle du téléphone : ${e.javaClass.simpleName}")
            return false
        }
        texts[i] = out
        localBlocks++
        return true
    }

    /** Phone models take segments of up to ~20 s: the block's VAD segments, or its audio in 20 s pieces. */
    private fun localPieces(block: AudioBlock): List<FloatArray> {
        if (block.segments.isNotEmpty()) return block.segments
        val step = LOCAL_PIECE_SECONDS * SttEngine.SAMPLE_RATE
        return (0 until block.audio.size step step).map { block.audio.copyOfRange(it, minOf(it + step, block.audio.size)) }
    }

    /** The text before block [i], whose end goes to Whisper as context. */
    private fun previousText(i: Int): String {
        for (j in i - 1 downTo 0) if (texts[j].isNotEmpty()) return texts[j].joinToString(" ")
        return ""
    }

    private fun note(why: String?) {
        if (reason == null && !why.isNullOrBlank()) reason = why
    }

    /**
     * After the last block: the raw text of every block, polished once on the server when that is wanted and
     * still sensible. A block that failed everywhere gets one more try (server, then phone); if that fails
     * too, it is counted in [DictationResult.lostBlocks] and the other blocks are still returned. Throws only
     * when no block at all could be transcribed (nothing to insert: the controller shows the error).
     */
    fun finish(): DictationResult {
        val seconds = audioSamples / SttEngine.SAMPLE_RATE.toFloat()
        val server = backend.blockServer
        if (control.mode == WaitControl.Mode.ABANDON) return empty(seconds)
        var lastError: Exception? = null
        for ((i, block) in retry) {
            if (control.mode == WaitControl.Mode.ABANDON) return empty(seconds)
            var done = false
            if (server != null && control.mode == WaitControl.Mode.NONE) {
                val child = control.child()
                try {
                    val r = server.transcribeBlock(block.audio, language, previousText(i), false, Long.MAX_VALUE, child)
                    texts[i] = listOfNotNull(r.text.takeIf { it.isNotBlank() })
                    serverBlocks++
                    if (language.isEmpty()) language = r.language
                    done = true
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    note(e.message)
                } finally {
                    child.close()
                }
            }
            if (!done && backend.canFallBack && control.mode != WaitControl.Mode.ABANDON) done = transcribeLocally(i, block)
            if (!done) lostBlocks++
        }
        retry.clear()
        if (control.mode == WaitControl.Mode.ABANDON) return empty(seconds)
        if (lostBlocks > 0 && lostBlocks == texts.size) {
            throw lastError as? RemoteException ?: RemoteException(reason ?: "aucun bloc transcrit", lastError)
        }
        val raw = texts.flatten()
        val joined = raw.joinToString(" ")
        var polished: String? = null
        val status: String = when {
            joined.isBlank() -> ""
            server == null || !server.polishWanted -> "not requested"
            singlePolish != null -> singlePolish!!.polishStatus.also { polished = singlePolish!!.polished }
            control.mode != WaitControl.Mode.NONE -> "attente annulée"
            localAfterEnd || !backend.serverAvailable() -> "serveur non utilisé"
            else -> {
                val child = control.child()
                try {
                    val p = server.polish(joined, language, seconds, child)
                    polished = p.polished
                    p.status
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: RemoteException) {
                    when {
                        e.httpCode == 404 || e.httpCode == 405 -> "polissage absent du serveur"
                        e.failure == RemoteFailure.CANCELLED -> "attente annulée"
                        else -> { backend.serverFailed(e); e.message ?: "échec" }
                    }
                } catch (e: Exception) {
                    "erreur : ${e.javaClass.simpleName}"
                } finally {
                    child.close()
                }
            }
        }
        if (control.mode == WaitControl.Mode.ABANDON) return empty(seconds)
        val p = polished?.takeIf { it.isNotBlank() }
        return DictationResult(
            texts = if (p != null) listOf(p) else raw,
            source = when {
                p != null -> DictationResult.Source.SERVER_POLISHED
                serverBlocks == 0 && localBlocks > 0 -> DictationResult.Source.LOCAL_FALLBACK
                else -> DictationResult.Source.SERVER_RAW
            },
            rawText = joined, polishedText = p, polishStatus = status, language = language,
            audioSeconds = seconds, transport = "blocks",
            fallbackReason = if (localBlocks > 0 || lostBlocks > 0) reason else null,
            blocks = texts.size, localBlocks = localBlocks, lostBlocks = lostBlocks,
        )
    }

    private fun empty(seconds: Float) = DictationResult(
        emptyList(), DictationResult.Source.SERVER_RAW, audioSeconds = seconds, transport = "blocks",
        blocks = texts.size, localBlocks = localBlocks,
    )

    companion object {
        const val LOCAL_PIECE_SECONDS = 20
    }
}

/** One block of a dictation: its audio, and the VAD segments inside it, for a local fallback. */
class AudioBlock(val index: Int, val audio: FloatArray, val segments: List<FloatArray>) {
    val seconds: Float get() = audio.size / SttEngine.SAMPLE_RATE.toFloat()
}

/** Text of one block, and for a single-block dictation the polish done in the same job. */
data class BlockText(val text: String, val language: String, val polished: String? = null, val polishStatus: String = "")

/** Result of `POST /polish`: the polished text when applied, and the status ("applied" or the server's reason). */
data class PolishOutcome(val polished: String?, val status: String)

/** The server's side of a block dictation ([RemoteEngine]). Blocking calls; failures are [RemoteException]s. */
interface BlockServer {
    /** The final polish is wanted (setting on, and this repository's server). */
    val polishWanted: Boolean

    /** False when the server has no `POST /polish` (older server); throws when it cannot tell. */
    fun supportsBlocks(control: WaitControl): Boolean

    /**
     * Transcribes one block: [language] when none is set ("" to detect), [prompt] the previous text (its end
     * goes to Whisper), [polish] to polish it in the same job. Gives up at [notAfter] at the latest
     * ([monotonicMillis] clock).
     */
    fun transcribeBlock(audio: FloatArray, language: String, prompt: String, polish: Boolean, notAfter: Long, control: WaitControl): BlockText

    /** Polishes [text] (`POST /polish`, then `GET /jobs/{id}`). */
    fun polish(text: String, language: String, audioSeconds: Float, control: WaitControl): PolishOutcome

    /** Wait allowed for [audioSeconds] of audio (the fallback delay of issue #23). */
    fun budgetMillis(audioSeconds: Float): Long
}

/** What a block dictation needs from the engine: the server, and the local fallback ([FallbackEngine]). */
interface BlockBackend {
    /** Null when this engine cannot transcribe by blocks. */
    val blockServer: BlockServer?
    /** False while the server is skipped after a failure. */
    fun serverAvailable(): Boolean
    /** Records a server failure (skips the server for a while, or until a 429's `Retry-After`). */
    fun serverFailed(e: Exception)
    /** True when a local model can take over a block. */
    val canFallBack: Boolean
    fun transcribeLocally(samples: FloatArray): String
}

/**
 * The [BlockBackend] of the engine that [use] lends for each call (`EngineManager.use`): a released or replaced
 * engine is never touched, as for a whole dictation.
 */
class LentBlockBackend(private val use: ((SttEngine) -> Any?) -> Any?) : BlockBackend {
    @Suppress("UNCHECKED_CAST")
    private fun <T> on(op: (BlockBackend) -> T, orElse: () -> T): T =
        use { engine -> (engine as? BlockBackend)?.let(op) ?: orElse() } as T

    private fun noServer(): Nothing = throw IllegalStateException("moteur sans transcription par blocs")

    private val server = object : BlockServer {
        override val polishWanted: Boolean get() = on({ it.blockServer?.polishWanted == true }, { false })
        override fun supportsBlocks(control: WaitControl) =
            on({ (it.blockServer ?: noServer()).supportsBlocks(control) }, { false })
        override fun transcribeBlock(audio: FloatArray, language: String, prompt: String, polish: Boolean, notAfter: Long, control: WaitControl) =
            on({ (it.blockServer ?: noServer()).transcribeBlock(audio, language, prompt, polish, notAfter, control) }, { noServer() })
        override fun polish(text: String, language: String, audioSeconds: Float, control: WaitControl) =
            on({ (it.blockServer ?: noServer()).polish(text, language, audioSeconds, control) }, { noServer() })
        override fun budgetMillis(audioSeconds: Float) = on({ it.blockServer?.budgetMillis(audioSeconds) ?: 0L }, { 0L })
    }

    override val blockServer: BlockServer? get() = if (on({ it.blockServer != null }, { false })) server else null
    override fun serverAvailable() = on({ it.serverAvailable() }, { false })
    override fun serverFailed(e: Exception) = on({ it.serverFailed(e) }, { })
    override val canFallBack: Boolean get() = on({ it.canFallBack }, { false })
    override fun transcribeLocally(samples: FloatArray): String = on({ it.transcribeLocally(samples) }, { noServer() })
}

/** Monotonic clock in milliseconds, shared by deadlines of the remote path. */
fun monotonicMillis(): Long = System.nanoTime() / 1_000_000
