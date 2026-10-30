package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import rkr.simplekeyboard.inputmethod.latin.glide.GlideComputer
import rkr.simplekeyboard.inputmethod.latin.glide.GlideGeometrySink
import rkr.simplekeyboard.inputmethod.latin.glide.GlideIndexPrewarmer
import rkr.simplekeyboard.inputmethod.latin.glide.GlideIndexReleaser
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath

interface EngineExecutor : Executor {
    fun shutdown()
    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean
}

class ImmutableUtf8Prefix private constructor(private val value: ByteArray) {
    internal val byteCount: Int
        get() = value.size

    internal fun byteAt(index: Int): Int = value[index].toInt() and 0xff

    internal fun decodeUtf8(): String = String(value, Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is ImmutableUtf8Prefix && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    companion object {
        /** The shared empty value: no request context, or no context a request could prove. */
        internal val EMPTY = ImmutableUtf8Prefix(ByteArray(0))

        internal fun copyOf(bytes: ByteArray) = ImmutableUtf8Prefix(bytes.copyOf())
    }
}

/**
 * The kind of result a token belongs to. The state owner must know it before committing, so a
 * NEXT_WORD result is never committed through the PREFIX path or vice versa. Token inequality
 * between requests comes from `requestSerial`, not from this.
 */
enum class LookupKind { PREFIX, NEXT_WORD, GLIDE }

data class LookupToken(
    val engineInstanceId: Long,
    val requestSerial: Long,
    val editorSessionId: Long,
    val subtypeId: String,
    /** Prefix bytes for [LookupKind.PREFIX], context-word bytes for [LookupKind.NEXT_WORD] and
     * [LookupKind.GLIDE] (empty there at a field start). */
    val normalizedQuery: ImmutableUtf8Prefix,
    val dictionary: DictionaryIdentity,
    val kind: LookupKind = LookupKind.PREFIX,
    /**
     * The previous committed word of a [LookupKind.PREFIX] request (the context boost's
     * condition), empty when there is none; NEXT_WORD and GLIDE carry their context in
     * [normalizedQuery] instead. The token carries it so a result computed against a stale
     * context fails the currency check like any other superseded request.
     */
    val normalizedContext: ImmutableUtf8Prefix = ImmutableUtf8Prefix.EMPTY,
)

data class LookupResult(
    val token: LookupToken,
    val suggestions: List<String>,
    val kind: LookupKind = token.kind,
)

/**
 * Delivers worker-thread results without applying them.
 *
 * Implementations must dispatch to the serialized state owner (the UI thread). That owner must
 * call [LatestOnlyPrefixEngine.isCurrent] with the complete token immediately before it applies
 * suggestions. Receiving this callback is never permission to update UI directly.
 */
fun interface ResultHandoff {
    fun handOff(result: LookupResult)
}

class LatestOnlyPrefixEngine internal constructor(
    val dictionaryIdentity: DictionaryIdentity,
    computer: PrefixComputer,
    private val executor: EngineExecutor,
    private val resultHandoff: ResultHandoff,
    private val releaseResources: () -> Unit = {},
) {
    private enum class State { ACTIVE, DESTROYING, DESTROYED }

    /** [glidePath] is the decode payload of a [LookupKind.GLIDE] request; null otherwise. */
    private data class Request(val token: LookupToken, val glidePath: GlidePath? = null)

    private val lock = Any()
    private val destroyLock = Any()
    private val engineInstanceId = nextEngineInstanceId()
    private var state = State.ACTIVE
    private var computer: PrefixComputer? = computer
    private var requestSerial = 0L
    private var currentToken: LookupToken? = null
    private var pendingRequest: Request? = null
    private var activeWorkerId = 0L
    private var nextWorkerId = 0L
    private var readerCount = 0
    private var resourcesReleased = false
    private var suppressedStaleResultCountValue = 0L
    private var handoffCountValue = 0L

    val suppressedStaleResultCount: Long
        get() = synchronized(lock) { suppressedStaleResultCountValue }

    val handoffCount: Long
        get() = synchronized(lock) { handoffCountValue }

    /** Must be checked by the serialized owner immediately before applying asynchronous results. */
    fun isCurrent(token: LookupToken): Boolean = synchronized(lock) {
        state == State.ACTIVE &&
            token == currentToken &&
            token.engineInstanceId == engineInstanceId &&
            token.dictionary == dictionaryIdentity
    }

    /**
     * PREFIX lookup. [normalizedContextWordUtf8] is the normalized previous committed word (the
     * exact pass's context boost condition); empty at a sentence start or when the editor cache
     * could not prove one, which keeps the pre-boost ranking byte-identical. An unusable context
     * (over-long or invalid UTF-8) is dropped, never the request: word completion must survive a
     * broken context.
     */
    fun request(
        editorSessionId: Long,
        subtypeId: String,
        normalizedPrefixUtf8: ByteArray,
        normalizedContextWordUtf8: ByteArray = EMPTY_CONTEXT_BYTES,
    ): LookupToken? {
        val context =
            if (normalizedContextWordUtf8.isEmpty() ||
                normalizedContextWordUtf8.size > TatBigrPrefixIndex.MAX_WORD_BYTES ||
                !isValidUtf8Scalar(normalizedContextWordUtf8)
            ) {
                EMPTY_CONTEXT_BYTES
            } else {
                normalizedContextWordUtf8
            }
        return requestInternal(
            editorSessionId, subtypeId, normalizedPrefixUtf8,
            LookupKind.PREFIX, TdictPrefixIndex.MAX_PREFIX_BYTES, context,
        )
    }

    /**
     * NEXT_WORD counterpart of [request]. An empty [normalizedContextWordUtf8] is rejected and
     * invalidates the generation, exactly like an empty prefix.
     */
    fun requestNextWord(
        editorSessionId: Long,
        subtypeId: String,
        normalizedContextWordUtf8: ByteArray,
    ): LookupToken? = requestInternal(
        editorSessionId, subtypeId, normalizedContextWordUtf8,
        LookupKind.NEXT_WORD, TatBigrPrefixIndex.MAX_WORD_BYTES,
    )

    /**
     * GLIDE counterpart of [request]: same token, serial and staleness rules, but the payload is
     * the recorded path, copied here because the caller's [GlidePath] is PointerTracker's live
     * buffer. [contextWordUtf8] is the normalized previous committed word (the bigram channel's
     * condition); the token's query carries it. The worker reads the path off the request.
     *
     * A path with fewer than two points is rejected without invalidating the generation: unlike
     * an empty prefix (the user cleared the word), a junk gesture says nothing about the text.
     * The decode goes through [GlideComputer], separate from the per-keystroke lookup.
     */
    fun requestGlide(
        editorSessionId: Long,
        subtypeId: String,
        path: GlidePath,
        contextWordUtf8: ByteArray,
    ): LookupToken? {
        if (path.size < 2) return null
        val snapshot = GlidePath(path.size)
        path.copyInto(snapshot)
        return requestInternal(
            editorSessionId, subtypeId, contextWordUtf8,
            LookupKind.GLIDE, 0, glidePath = snapshot,
        )
    }

    private fun requestInternal(
        editorSessionId: Long,
        subtypeId: String,
        normalizedBytes: ByteArray,
        kind: LookupKind,
        maxBytes: Int,
        normalizedContextBytes: ByteArray = EMPTY_CONTEXT_BYTES,
        glidePath: GlidePath? = null,
    ): LookupToken? {
        // Reject before constructing the immutable token or making the single owned copy.
        // GLIDE carries its payload in the request and validates nothing here (its guard lives
        // in requestGlide); the empty sentinel query is never read by the worker.
        if (kind != LookupKind.GLIDE &&
            (normalizedBytes.isEmpty() ||
                normalizedBytes.size > maxBytes ||
                !isValidUtf8Scalar(normalizedBytes))
        ) {
            synchronized(lock) {
                if (state == State.ACTIVE) invalidateGenerationLocked()
            }
            return null
        }
        var submission: Submission? = null
        val token = synchronized(lock) {
            if (state != State.ACTIVE) return@synchronized null
            requestSerial = nextSerial(requestSerial)
            val immutableQuery = ImmutableUtf8Prefix.copyOf(normalizedBytes)
            val immutableContext =
                if (normalizedContextBytes.isEmpty()) {
                    ImmutableUtf8Prefix.EMPTY
                } else {
                    ImmutableUtf8Prefix.copyOf(normalizedContextBytes)
                }
            val token = LookupToken(
                engineInstanceId,
                requestSerial,
                editorSessionId,
                subtypeId,
                immutableQuery,
                dictionaryIdentity,
                kind,
                immutableContext,
            )
            val request = Request(token, glidePath)
            currentToken = token
            if (activeWorkerId != 0L) {
                pendingRequest = request
            } else {
                nextWorkerId = nextSerial(nextWorkerId)
                activeWorkerId = nextWorkerId
                readerCount = 1
                submission = Submission(activeWorkerId, request)
            }
            token
        }
        val work = submission ?: return token
        try {
            executor.execute { drain(work) }
        } catch (_: RejectedExecutionException) {
            failSubmission(work)
        } catch (_: Throwable) {
            failSubmission(work)
        }
        return token
    }

    fun finishInput() = synchronized(lock) {
        if (state != State.ACTIVE) return@synchronized
        invalidateGenerationLocked()
    }

    /**
     * Pushes the key-neighbor table into the computer, if it runs a typo-recovery pass. Only a
     * @Volatile reference is swapped; the scratch is touched solely by the serialized worker inside
     * [PrefixComputer.lookup].
     */
    fun updateKeyNeighbors(table: KeyNeighborTable?) {
        synchronized(lock) {
            (computer as? KeyNeighborSink)?.updateKeyNeighbors(table)
        }
    }

    /**
     * Pushes the current layout's key geometry into the computer, if it decodes glides. As in
     * [updateKeyNeighbors], only a `@Volatile` reference is swapped.
     */
    fun updateGlideGeometry(geometry: GlideKeyGeometry?) {
        synchronized(lock) {
            (computer as? GlideGeometrySink)?.updateGlideGeometry(geometry)
        }
    }

    /**
     * Enqueues the deferred empty-prefix probe of a cleanly completed word (see
     * [EmptyPrefixProber]) onto the serialized worker, behind every request issued so far — the
     * separator's NEXT_WORD lookup in particular, which the caller always issues first and which
     * the drain loop still consumes ahead of this task. The probe neither carries a token nor
     * touches the generation: it answers a question about a word that is already complete, and
     * the caller matches the answer against its own parked state. [callback] fires on the worker
     * thread, once, with the probe's answer. Returns false — no callback will fire — when the
     * engine has no prober, is no longer active, or the executor rejects the task.
     */
    fun requestEmptyPrefixProbe(word: String, callback: (Int) -> Unit): Boolean {
        val prober = synchronized(lock) {
            if (state != State.ACTIVE) null else computer as? EmptyPrefixProber
        } ?: return false
        return try {
            executor.execute {
                val length = try {
                    prober.shortestEmptyProperPrefixLength(word)
                } catch (_: Throwable) {
                    EmptyPrefixProber.NO_EMPTY_PREFIX
                }
                try {
                    callback(length)
                } catch (_: Throwable) {
                    // A failing callback drops the observation; ordinary input is unaffected.
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Prepares the glide word index after a gesture arms. This tokenless worker task never
     * changes the current lookup generation and emits no result; a later decode remains
     * authoritative if preparation fails.
     */
    fun prewarmGlideIndex() {
        val target = synchronized(lock) {
            if (state != State.ACTIVE) null else computer as? GlideIndexPrewarmer
        } ?: return
        try {
            executor.execute {
                try {
                    target.prewarmGlideIndex()
                } catch (_: Throwable) {
                    // The lift-time decode retries preparation on demand.
                }
            }
        } catch (_: Throwable) {
            // The executor went away with the engine; preparation is best-effort.
        }
    }

    /**
     * Idle memory release of the glide word index. The decoder is worker-confined, so the drop is
     * posted to the serialized executor and runs between submissions, never mid-decode; the caller
     * (UI thread, LatinIME's MSG_DEALLOCATE_MEMORY) only enqueues. A destroyed engine skips it.
     */
    fun releaseGlideIndex() {
        val target = synchronized(lock) {
            if (state != State.ACTIVE) null else computer as? GlideIndexReleaser
        } ?: return
        try {
            executor.execute { target.releaseGlideIndex() }
        } catch (_: Throwable) {
            // The executor went away with the engine; the drop is best-effort.
        }
    }

    fun destroy(timeout: Long, unit: TimeUnit): Boolean = synchronized(destroyLock) {
        synchronized(lock) {
            if (state == State.DESTROYED) return true
            state = State.DESTROYING
            invalidateGenerationLocked()
        }
        try {
            executor.shutdown()
        } catch (_: Throwable) {
            return false
        }
        val terminated = try {
            executor.awaitTermination(timeout, unit)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Throwable) {
            false
        }
        if (!terminated) return false

        val release = synchronized(lock) {
            if (activeWorkerId != 0L || readerCount != 0) return false
            computer = null
            if (!resourcesReleased) {
                resourcesReleased = true
                true
            } else {
                false
            }
        }
        if (release) {
            try {
                releaseResources()
            } catch (_: Throwable) {
                // State and strong references are already cleared; teardown remains idempotent.
            }
        }
        synchronized(lock) { state = State.DESTROYED }
        return true
    }

    internal fun readerCountForTest(): Int = synchronized(lock) { readerCount }

    internal fun hasPendingForTest(): Boolean = synchronized(lock) { pendingRequest != null }

    private fun drain(submission: Submission) {
        var request = submission.request
        try {
            while (true) {
                val lookup = synchronized(lock) { computer }
                val suggestions = try {
                    when (request.token.kind) {
                        LookupKind.PREFIX -> {
                            val query = request.token.normalizedQuery
                            val context = request.token.normalizedContext
                            when {
                                lookup == null -> emptyList()
                                // An empty context is the pre-boost path, byte-identical.
                                context.byteCount == 0 -> lookup.lookup(query)
                                lookup is ContextAwarePrefixComputer -> lookup.lookup(query, context)
                                else -> lookup.lookup(query)
                            }
                        }
                        LookupKind.NEXT_WORD ->
                            (lookup as? NextWordComputer)?.predict(request.token.normalizedQuery)
                                ?: emptyList()
                        LookupKind.GLIDE ->
                            (lookup as? GlideComputer)?.decodeGlide(
                                request.glidePath ?: GlidePath(0),
                                request.token.normalizedQuery.let {
                                    if (it.byteCount == 0) null else it.decodeUtf8()
                                },
                            )
                                ?: emptyList()
                    }
                } catch (_: Throwable) {
                    emptyList()
                }

                val completion = synchronized(lock) {
                    val handoff = prepareHandoffLocked(request, suggestions)
                    val next = if (state == State.ACTIVE) pendingRequest else null
                    pendingRequest = null

                    // Taking the pending slot and transitioning to idle is one atomic action.
                    if (next == null && activeWorkerId == submission.workerId) {
                        activeWorkerId = 0L
                        readerCount = 0
                    }
                    Completion(handoff, next)
                }
                handOff(completion.handoff)
                request = completion.next ?: return
            }
        } finally {
            recoverUnexpectedWorkerExit(submission.workerId, request)
        }
    }

    /** Worker-side validation filters obsolete compute; final apply still requires isCurrent. */
    private fun prepareHandoffLocked(
        request: Request,
        suggestions: List<String>,
    ): LookupResult? {
        if (!isCurrent(request.token)) {
            suppressedStaleResultCountValue++
            return null
        }
        handoffCountValue++
        return LookupResult(request.token, suggestions)
    }

    private fun handOff(result: LookupResult?) {
        if (result == null) return
        try {
            resultHandoff.handOff(result)
        } catch (_: Throwable) {
            // A failing callback drops the suggestions; ordinary input is unaffected.
        }
    }

    private fun failSubmission(submission: Submission) {
        val handoff = synchronized(lock) {
            if (activeWorkerId != submission.workerId) return@synchronized null
            val latest = pendingRequest ?: submission.request
            pendingRequest = null
            activeWorkerId = 0L
            readerCount = 0
            prepareHandoffLocked(latest, emptyList())
        }
        handOff(handoff)
    }

    private fun recoverUnexpectedWorkerExit(workerId: Long, request: Request) {
        val handoff = synchronized(lock) {
            if (activeWorkerId != workerId) return@synchronized null
            val latest = pendingRequest ?: request
            pendingRequest = null
            activeWorkerId = 0L
            readerCount = 0
            prepareHandoffLocked(latest, emptyList())
        }
        handOff(handoff)
    }

    private fun invalidateGenerationLocked() {
        requestSerial = nextSerial(requestSerial)
        currentToken = null
        pendingRequest = null
    }

    private fun nextSerial(value: Long): Long = if (value == Long.MAX_VALUE) 1L else value + 1L

    private data class Submission(val workerId: Long, val request: Request)

    private data class Completion(val handoff: LookupResult?, val next: Request?)

    companion object {
        /** The no-context sentinel of a PREFIX request; only ever read, never mutated. */
        private val EMPTY_CONTEXT_BYTES = ByteArray(0)

        private val NEXT_ENGINE_INSTANCE_ID = AtomicLong()

        private fun nextEngineInstanceId(): Long {
            val id = NEXT_ENGINE_INSTANCE_ID.incrementAndGet()
            check(id > 0L) { "engine instance id exhausted" }
            return id
        }
    }
}
