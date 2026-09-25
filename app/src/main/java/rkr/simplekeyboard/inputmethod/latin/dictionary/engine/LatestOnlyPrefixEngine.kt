package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import rkr.simplekeyboard.inputmethod.latin.glide.GlideComputer
import rkr.simplekeyboard.inputmethod.latin.glide.GlideGeometrySink
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
        internal fun copyOf(bytes: ByteArray) = ImmutableUtf8Prefix(bytes.copyOf())
    }
}

/**
 * PROPOSALS.md, "E5c. Вид запроса": the owner of state must know which kind of result it holds
 * before it may commit it — NEXT_WORD can never be committed through the PREFIX path or vice
 * versa. NOT introduced to make tokens of different requests unequal (`requestSerial` already
 * does that on every `request`/`requestNextWord` call).
 */
enum class LookupKind { PREFIX, NEXT_WORD, GLIDE }

data class LookupToken(
    val engineInstanceId: Long,
    val requestSerial: Long,
    val editorSessionId: Long,
    val subtypeId: String,
    /** Prefix bytes for [LookupKind.PREFIX], normalized context-word bytes for [LookupKind.NEXT_WORD]. */
    val normalizedQuery: ImmutableUtf8Prefix,
    val dictionary: DictionaryIdentity,
    val kind: LookupKind = LookupKind.PREFIX,
)

data class LookupResult(
    val token: LookupToken,
    val suggestions: List<String>,
    val kind: LookupKind = token.kind,
)

/**
 * Non-applying worker-thread result handoff.
 *
 * Implementations must dispatch to the serialized state owner (the UI thread in D1e). That owner
 * must call [LatestOnlyPrefixEngine.isCurrent] with the complete token immediately before it
 * applies suggestions. Receiving this callback is never permission to update UI directly.
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

    fun request(
        editorSessionId: Long,
        subtypeId: String,
        normalizedPrefixUtf8: ByteArray,
    ): LookupToken? = requestInternal(
        editorSessionId, subtypeId, normalizedPrefixUtf8,
        LookupKind.PREFIX, TdictPrefixIndex.MAX_PREFIX_BYTES,
    )

    /**
     * PROPOSALS.md, "E5c. Вид запроса": the NEXT_WORD sibling of [request]. Existing PREFIX
     * behaviour is untouched by this method's existence — empty input is still rejected and still
     * invalidates the generation for PREFIX, exactly as before; here an empty
     * [normalizedContextWordUtf8] is rejected the same way, because "no context" and "no prefix"
     * are the same kind of nothing to look up.
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
     * P7-3 (docs/GLIDE-PLAN.md): the GLIDE sibling of [request] — same token, serial and
     * staleness discipline, a different payload: the recorded path, snapshotted HERE because the
     * caller's [GlidePath] is the PointerTracker's live buffer. The token's [normalizedQuery]
     * carries an empty sentinel — identity lives in the serial, and the worker reads the payload
     * off the request, never the token. A degenerate path (fewer than two points — the decider
     * never arms on those, so this is a defensive guard) is rejected WITHOUT invalidating the
     * generation: unlike an empty prefix ("the user cleared the word"), a junk gesture says
     * nothing about the text.
     *
     * The glide decode never touches the per-keystroke lookup budgets: it is a separate call the
     * computer answers through its own seam ([GlideComputer]).
     */
    fun requestGlide(
        editorSessionId: Long,
        subtypeId: String,
        path: GlidePath,
    ): LookupToken? {
        if (path.size < 2) return null
        val snapshot = GlidePath(path.size)
        path.copyInto(snapshot)
        return requestInternal(
            editorSessionId, subtypeId, ByteArray(0),
            LookupKind.GLIDE, 0, snapshot,
        )
    }

    private fun requestInternal(
        editorSessionId: Long,
        subtypeId: String,
        normalizedBytes: ByteArray,
        kind: LookupKind,
        maxBytes: Int,
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
            val token = LookupToken(
                engineInstanceId,
                requestSerial,
                editorSessionId,
                subtypeId,
                immutableQuery,
                dictionaryIdentity,
                kind,
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
     * Pushes the key-neighbor table into the underlying computer, if it runs a fuzzy pass. Only a
     * @Volatile reference is swapped; the fuzzy scratch is still touched solely by the serialized
     * worker inside [PrefixComputer.lookup].
     */
    fun updateKeyNeighbors(table: KeyNeighborTable?) {
        synchronized(lock) {
            (computer as? KeyNeighborSink)?.updateKeyNeighbors(table)
        }
    }

    /**
     * P7-3: pushes the current layout's key geometry into the computer, if it decodes glides.
     * Same handoff shape as [updateKeyNeighbors]: a `@Volatile` reference is swapped; the
     * decoder's scratch is touched solely by the serialized worker inside [GlideComputer].
     */
    fun updateGlideGeometry(geometry: GlideKeyGeometry?) {
        synchronized(lock) {
            (computer as? GlideGeometrySink)?.updateGlideGeometry(geometry)
        }
    }

    /**
     * O2 (docs/OPTIMIZE-2026-09-25.md): the idle memory release of the glide word index. The
     * decoder state is worker-confined, so the drop is POSTED to the serialized executor and
     * runs between submissions, never mid-decode; the caller's thread (the UI thread, from
     * LatinIME's MSG_DEALLOCATE_MEMORY) only enqueues. A gone engine skips quietly — its
     * teardown frees the index anyway.
     */
    fun releaseGlideIndex() {
        val target = synchronized(lock) {
            if (state != State.ACTIVE) null else computer as? GlideIndexReleaser
        } ?: return
        try {
            executor.execute { target.releaseGlideIndex() }
        } catch (_: Throwable) {
            // The executor went away with the engine: the drop is best-effort by design.
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
                        LookupKind.PREFIX -> lookup?.lookup(request.token.normalizedQuery) ?: emptyList()
                        LookupKind.NEXT_WORD ->
                            (lookup as? NextWordComputer)?.predict(request.token.normalizedQuery)
                                ?: emptyList()
                        LookupKind.GLIDE ->
                            (lookup as? GlideComputer)?.decodeGlide(request.glidePath ?: GlidePath(0))
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
            // Suggestions fail closed and never affect ordinary input.
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
        private val NEXT_ENGINE_INSTANCE_ID = AtomicLong()

        private fun nextEngineInstanceId(): Long {
            val id = NEXT_ENGINE_INSTANCE_ID.incrementAndGet()
            check(id > 0L) { "engine instance id exhausted" }
            return id
        }
    }
}
