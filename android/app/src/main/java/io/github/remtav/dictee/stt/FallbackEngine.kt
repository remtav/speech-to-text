package io.github.remtav.dictee.stt

/**
 * Remote first, local model when the server cannot be reached. After a
 * failure the server is skipped for [retryAfterMillis], so a dead server
 * does not cost a connect timeout on every dictation. The local model is
 * loaded only on the first failure.
 */
class FallbackEngine(
    private val remote: SttEngine,
    private val localFactory: () -> SttEngine,
    private val retryAfterMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : SttEngine, WholeDictationEngine, BlockBackend {
    override val spec: ModelSpec get() = remote.spec
    @Volatile private var local: SttEngine? = null
    @Volatile private var skipUntil = 0L
    @Volatile var lastUsedLocal = false
        private set

    override val provider: String get() = if (lastUsedLocal) "remote→${local?.provider ?: "local"}" else remote.provider

    @Synchronized
    override fun transcribe(samples: FloatArray, sampleRate: Int): Transcription {
        if (clock() >= skipUntil) {
            try {
                return remote.transcribe(samples, sampleRate).also { lastUsedLocal = false }
            } catch (e: RemoteException) {
                skipUntil = clock() + retryAfterMillis
            }
        }
        return localEngine().transcribe(samples, sampleRate)
    }

    private fun localEngine(): SttEngine {
        val engine = local ?: localFactory().also { local = it }
        lastUsedLocal = true
        return engine
    }

    /**
     * The whole dictation on the server; on any failure (or "Annuler"), the
     * kept audio is transcribed on the phone, segment by segment, so nothing
     * is lost. A dropped dictation ([WaitControl.abandon]) transcribes nothing.
     */
    @Synchronized
    override fun transcribeDictation(audio: FloatArray, segments: List<FloatArray>, control: WaitControl): DictationResult {
        var reason: String? = null
        val whole = remote as? WholeDictationEngine
        if (whole == null) {
            reason = "pas de mode dictée entière"
        } else if (audio.isEmpty() && segments.isNotEmpty()) {
            reason = "audio complet indisponible (mémoire)"
        } else if (clock() < skipUntil) {
            reason = "serveur en échec il y a moins de ${retryAfterMillis / 1000} s"
        } else {
            try {
                return whole.transcribeDictation(audio, segments, control).also { lastUsedLocal = false }
            } catch (e: RemoteException) {
                // A user cancel says nothing about the server: no retry delay for it.
                if (e.failure != RemoteFailure.CANCELLED) skipUntil = clock() + retryAfterMillis
                reason = e.message
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                // Anything else (a bug, a refused header): the kept audio still goes local.
                skipUntil = clock() + retryAfterMillis
                reason = "erreur : ${e.javaClass.simpleName}"
            } catch (e: OutOfMemoryError) {
                reason = "mémoire insuffisante pour l'envoi"
            }
        }
        if (control.mode == WaitControl.Mode.ABANDON) {
            return DictationResult(emptyList(), DictationResult.Source.LOCAL_FALLBACK, transport = "none", fallbackReason = reason)
        }
        control.localStarted()
        val start = System.nanoTime()
        val engine = localEngine()
        val texts = ArrayList<String>()
        for (seg in segments) {
            if (control.mode == WaitControl.Mode.ABANDON || Thread.currentThread().isInterrupted) break
            texts += engine.transcribe(seg, SttEngine.SAMPLE_RATE).text
        }
        return DictationResult(
            texts = texts, source = DictationResult.Source.LOCAL_FALLBACK,
            totalMillis = (System.nanoTime() - start) / 1_000_000,
            audioSeconds = audio.size / SttEngine.SAMPLE_RATE.toFloat(), transport = "local",
            fallbackReason = reason,
        )
    }

    // ------------------------------------------------- block dictation (issue #35)

    override val blockServer: BlockServer? get() = remote as? BlockServer

    /** False while the server is skipped after a failure (or until a 429's `Retry-After`). */
    override fun serverAvailable(): Boolean = clock() >= skipUntil

    override fun serverFailed(e: Exception) {
        val retry = (e as? RemoteException)?.retryAfterSeconds?.takeIf { it > 0 }?.let { it * 1000L } ?: retryAfterMillis
        skipUntil = clock() + retry
    }

    override val canFallBack: Boolean get() = true

    @Synchronized
    override fun transcribeLocally(samples: FloatArray): String = localEngine().transcribe(samples, SttEngine.SAMPLE_RATE).text

    @Synchronized
    override fun release() {
        remote.release()
        local?.release()
        local = null
    }
}
