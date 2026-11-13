package io.github.remtav.dictee.stt

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Owns the one loaded model. Loading a model releases the previous one first:
 * two ~1 GB models never sit in memory together.
 *
 * Lifecycle (decided in DECISIONS.md): the IME asks for the model when it
 * becomes visible, keeps it while visible, and releases it under memory
 * pressure or after an idle delay once hidden.
 */
class EngineManager(
    private val store: ModelStore,
    @Volatile var factory: SttEngineFactory = SherpaEngine.factory,
    private val isInstalled: (ModelSpec) -> Boolean = { store.isInstalled(it) },
) {
    sealed interface State {
        data object Empty : State
        data class Loading(val spec: ModelSpec) : State
        data class Ready(val spec: ModelSpec, val loadMillis: Long, val provider: String, val note: String?) : State
        data class Failed(val spec: ModelSpec, val message: String) : State
    }

    fun interface Listener {
        fun onState(state: State)
    }

    private val loader: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "engine-loader").apply { priority = Thread.NORM_PRIORITY }
    }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    private val lock = Object()
    private val useLock = ReentrantReadWriteLock()

    private var engine: SttEngine? = null
    private var engineKey: Pair<String, EngineOptions>? = null
    private var pending: CompletableFuture<SttEngine>? = null
    private var pendingKey: Pair<String, EngineOptions>? = null

    @Volatile var state: State = State.Empty
        private set

    fun addListener(l: Listener) { listeners += l; l.onState(state) }
    fun removeListener(l: Listener) { listeners -= l }

    private fun publish(s: State) {
        state = s
        listeners.forEach { it.onState(s) }
    }

    /** Starts loading [spec] if it is not loaded or loading already. Returns the future engine. */
    fun load(spec: ModelSpec, options: EngineOptions): CompletableFuture<SttEngine> = synchronized(lock) {
        val key = spec.id to options
        engine?.let { if (engineKey == key) return CompletableFuture.completedFuture(it) }
        pending?.let { if (pendingKey == key && !it.isDone) return it }
        val future = CompletableFuture<SttEngine>()
        pending = future
        pendingKey = key
        loader.execute { doLoad(spec, options, key, future) }
        future
    }

    private fun doLoad(spec: ModelSpec, options: EngineOptions, key: Pair<String, EngineOptions>, future: CompletableFuture<SttEngine>) {
        val old: SttEngine?
        synchronized(lock) {
            if (pending !== future) { future.cancel(false); return }
            old = engine
            engine = null
            engineKey = null
        }
        if (old != null) useLock.writeLock().withLock { old.release() }
        if (!isInstalled(spec)) {
            val msg = "${ModelPlainNames.inSentence(spec).replaceFirstChar { it.uppercase() }} n'est pas installé"
            publish(State.Failed(spec, msg))
            future.completeExceptionally(IllegalStateException(msg))
            return
        }
        publish(State.Loading(spec))
        // Give the previous model's native memory back before allocating the next.
        System.gc()
        val start = System.nanoTime()
        var note: String? = null
        val created: SttEngine = try {
            factory.create(spec, store.dirFor(spec.id), options)
        } catch (e: Throwable) {
            if (options.acceleration != Acceleration.CPU && e !is OutOfMemoryError) {
                note = "${options.acceleration.label} indisponible, repli sur le CPU"
                try {
                    factory.create(spec, store.dirFor(spec.id), options.copy(acceleration = Acceleration.CPU))
                } catch (e2: Throwable) {
                    fail(spec, future, e2); return
                }
            } else {
                fail(spec, future, e); return
            }
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        synchronized(lock) {
            if (pending !== future) {
                // Superseded while loading (another model, or release()).
                created.release()
                future.cancel(false)
                if (pending == null && engine == null) publish(State.Empty)
                return
            }
            engine = created
            engineKey = key
            pending = null
            pendingKey = null
        }
        publish(State.Ready(spec, millis, created.provider, note))
        future.complete(created)
    }

    private fun fail(spec: ModelSpec, future: CompletableFuture<SttEngine>, e: Throwable) {
        synchronized(lock) {
            if (pending === future) { pending = null; pendingKey = null }
        }
        val msg = when (e) {
            is OutOfMemoryError -> "Mémoire insuffisante pour ${ModelPlainNames.inSentence(spec)}"
            // Issue #37: no raw exception text in the keyboard (it can name files); the cause stays on the future.
            else -> "Échec du chargement (${ModelPlainNames.inSentence(spec)}). Réessayez, ou téléchargez le modèle à nouveau."
        }
        publish(State.Failed(spec, msg))
        future.completeExceptionally(e)
    }

    /** Blocks until [spec] is loaded (loading it if needed). */
    fun await(spec: ModelSpec, options: EngineOptions, timeoutSeconds: Long = 120): SttEngine =
        load(spec, options).get(timeoutSeconds, TimeUnit.SECONDS)

    fun current(): SttEngine? = synchronized(lock) { engine }

    /**
     * Runs [block] with [spec] loaded, holding the engine so [release] or a
     * model switch cannot free it mid-decode.
     */
    fun <T> use(spec: ModelSpec, options: EngineOptions, block: (SttEngine) -> T): T {
        repeat(3) {
            val e = await(spec, options)
            useLock.readLock().withLock {
                if (current() === e) return block(e)
            }
        }
        throw IllegalStateException("Modèle remplacé pendant l'utilisation")
    }

    /** Frees the loaded model (memory pressure, idle). Safe to call any time. */
    fun release() {
        val toRelease: SttEngine?
        val hadPending: Boolean
        synchronized(lock) {
            toRelease = engine
            engine = null
            engineKey = null
            hadPending = pending != null
            pending?.cancel(false)
            pending = null
            pendingKey = null
        }
        if (toRelease != null) loader.execute { useLock.writeLock().withLock { toRelease.release() } }
        // Also when only a load was cancelled, or the keyboard would show "Loading…" forever.
        if (toRelease != null || hadPending) publish(State.Empty)
    }
}
