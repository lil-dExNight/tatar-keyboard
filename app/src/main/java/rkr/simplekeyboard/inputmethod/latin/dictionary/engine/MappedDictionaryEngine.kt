package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramTableLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedBigramTableCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun interface DictionaryMapper {
    fun mapReadOnly(file: File, size: Long): ByteBuffer
}

class ExecutorServiceEngineExecutor private constructor(
    private val delegate: ExecutorService,
) : EngineExecutor {
    override fun execute(command: Runnable) = delegate.execute(command)

    override fun shutdown() = delegate.shutdown()

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
        delegate.awaitTermination(timeout, unit)

    companion object {
        fun singleThread(): EngineExecutor =
            ExecutorServiceEngineExecutor(Executors.newSingleThreadExecutor())
    }
}

class MappedDictionaryEngine private constructor(
    val identity: DictionaryIdentity,
    private val engine: LatestOnlyPrefixEngine,
    private val computer: CompositePrefixComputer,
    private val resources: Resources,
) {
    fun request(
        editorSessionId: Long,
        subtypeId: String,
        normalizedPrefixUtf8: ByteArray,
    ): LookupToken? = engine.request(editorSessionId, subtypeId, normalizedPrefixUtf8)

    /** E5c NEXT_WORD sibling of [request] — same engine, same token/executor, different kind. */
    fun requestNextWord(
        editorSessionId: Long,
        subtypeId: String,
        normalizedContextWordUtf8: ByteArray,
    ): LookupToken? = engine.requestNextWord(editorSessionId, subtypeId, normalizedContextWordUtf8)

    /** P7-3 (docs/GLIDE-PLAN.md): the GLIDE sibling of [request] — same engine, same discipline. */
    fun requestGlide(
        editorSessionId: Long,
        subtypeId: String,
        path: GlidePath,
    ): LookupToken? = engine.requestGlide(editorSessionId, subtypeId, path)

    /**
     * O2 (docs/OPTIMIZE-2026-09-25.md): the idle memory release of the glide word index —
     * forwarded to the engine, which posts the drop onto its serialized worker (the decoder is
     * worker-confined). Safe from any thread, no-op once the engine is gone.
     */
    fun releaseGlideIndex() = engine.releaseGlideIndex()

    /**
     * E5c two-stage readiness (PROPOSALS.md, "E5c. Готовность вычислителя двухступенчатая"):
     * acquires, maps and opens the bigram table, then wires it into the ALREADY-published
     * composite computer. Call off the UI thread — this performs the same class of I/O
     * [start] does. Returns false without side effects on this engine if the table is
     * unavailable, corrupted, or the engine was destroyed in the meantime (racing
     * [destroy] loses cleanly: whichever of the two reaches [Resources] first wins, and the
     * loser's lease is closed without ever being exposed to a lookup). A failure here leaves
     * [CompositePrefixComputer.predict] returning an empty list — 0 predictions, no effect on
     * prefix suggestions or ordinary input, exactly the fail-closed shape the contract requires
     * for a missing or invalid bigram file.
     */
    fun attachBigramSource(
        catalog: PublishedBigramTableCatalog,
        mapper: DictionaryMapper = FILE_MAPPER,
    ): Boolean {
        val lease = try {
            catalog.acquireLatestForActivation()
        } catch (_: Throwable) {
            null
        } ?: return false
        var mapped: ByteBuffer? = null
        return try {
            val table = lease.table
            val bigramIdentity = BigramTableIdentity(
                table.generation, table.fileLanguageTag, table.schemaId, table.formatVersion, table.rawSha256,
            )
            mapped = mapper.mapReadOnly(table.file, table.rawSize)
            // Schema 3 resolves its head/success indices through the linked dictionary and
            // refuses to open against any other (the header names the dictionary's raw SHA-256);
            // a missing dictionary index therefore fails closed, like any invalid table.
            val dictionaryIndex = resources.index
                ?: throw IllegalArgumentException("bigram attach before the dictionary is open")
            val index = TatBigrPrefixIndex.open(
                mapped, bigramIdentity, dictionaryIndex, table.headCount, table.rawSize,
            ) ?: throw IllegalArgumentException("validated bigram layout mismatch")
            if (!resources.attachBigram(lease, catalog, mapped, index)) {
                // The engine was destroyed while this was in flight: attachBigram left the lease
                // for us to close, exactly like the failure path below — do not publish a source
                // into a computer whose engine will never look anything up again.
                throw AttachRacedDestroy()
            }
            computer.attachBigramSource(index)
            true
        } catch (_: Throwable) {
            mapped = null
            try {
                lease.close()
            } catch (_: Throwable) {
                // Ownership was consumed; a failing release still must not escape this call.
            } finally {
                try {
                    catalog.cleanupReleasedVersions()
                } catch (_: Throwable) {
                    // Fail closed without logging paths or typed text.
                }
            }
            false
        }
    }

    /**
     * The D3 verdict of the newest completed lookup, or null when nothing may be replaced.
     *
     * A plain read of an immutable object behind a `@Volatile` reference: the UI thread never
     * touches the mapped buffer, the index scratch, or the engine lock through this. The reader must
     * still check [AutocorrectAdvice.typedWord] against the live word — that, not this getter, is
     * what makes a verdict left over from an older lookup harmless.
     */
    val autocorrectAdvice: AutocorrectAdvice?
        get() = computer.lastAutocorrectAdvice

    fun finishInput() {
        // Idling the engine invalidates the generation, so the verdict computed for it goes too.
        computer.clearAutocorrectAdvice()
        engine.finishInput()
    }

    /**
     * P1 of Phase 2 (docs/ROADMAP-P2.md): exact whole-word membership of [normalizedWord] in this
     * engine's dictionary, for the personal-bigram context gate. Safe to call from ANY thread —
     * the answer comes from a cache-free read of the read-only mapping that never touches the
     * lookup path's scratch (see [TdictPrefixIndex.containsWordCold]) — and deliberately answered
     * without checking engine liveness: a released mapping stays valid until the garbage collector
     * reclaims it (closing the channel does not unmap on the HotSpot VM), so the worst answer a
     * racing [destroy] can produce is membership in the PREVIOUS dictionary generation — a
     * fail-open-in-the-small answer a learn threshold and a personal dictionary both survive.
     */
    fun containsWord(normalizedWord: String): Boolean {
        val index = resources.index ?: return false
        return try {
            index.containsWordCold(normalizedWord)
        } catch (_: Throwable) {
            false
        }
    }

    fun updateKeyNeighbors(table: KeyNeighborTable?) = engine.updateKeyNeighbors(table)

    /** P7-3: pushes the live layout geometry into the glide decode side (null disables it). */
    fun updateGlideGeometry(geometry: GlideKeyGeometry?) = engine.updateGlideGeometry(geometry)

    fun isCurrent(token: LookupToken): Boolean = engine.isCurrent(token)

    fun destroy(timeout: Long, unit: TimeUnit): Boolean {
        computer.clearAutocorrectAdvice()
        return engine.destroy(timeout, unit)
    }

    val suppressedStaleResultCount: Long
        get() = engine.suppressedStaleResultCount

    /** Internal-only control-flow signal — never surfaces past [attachBigramSource]'s own catch. */
    private class AttachRacedDestroy : Exception()

    /**
     * Owns the dictionary lease/mapping from construction, and — after a successful
     * [attachBigram] — the bigram lease/mapping too. Both are released together, exactly once,
     * by [release]; [attachBigram] and [release] share [lock] so a racing [attachBigramSource]
     * and [destroy] resolve cleanly instead of leaking a lease or double-closing one.
     */
    private class Resources(
        private var lease: DictionaryFileLease?,
        private val catalog: PublishedDictionaryCatalog,
        var mappedBuffer: ByteBuffer?,
        index: TdictPrefixIndex?,
    ) {
        /**
         * Read by [MappedDictionaryEngine.containsWord] from ANY thread (the personal-bigram
         * store's worker), hence `@Volatile`; a stale read after [release] is benign there — the
         * released mapping stays valid until GC, so the answer is membership in the previous
         * dictionary generation at worst.
         */
        @Volatile
        var index: TdictPrefixIndex? = index

        private val lock = Any()
        private var released = false
        private var bigramLease: BigramTableLease? = null
        private var bigramCatalog: PublishedBigramTableCatalog? = null
        var bigramMappedBuffer: ByteBuffer? = null
            private set
        var bigramIndex: TatBigrPrefixIndex? = null
            private set

        /** False (and the passed-in [lease] is left for the CALLER to close) once already released. */
        fun attachBigram(
            lease: BigramTableLease,
            catalog: PublishedBigramTableCatalog,
            mapped: ByteBuffer,
            index: TatBigrPrefixIndex,
        ): Boolean = synchronized(lock) {
            if (released) return@synchronized false
            // Attach happens once per engine lifetime in E5c; replacing rather than accumulating
            // keeps that true even if a future phase calls this more than once.
            bigramLease?.let { stale -> try { stale.close() } catch (_: Throwable) {} }
            bigramLease = lease
            bigramCatalog = catalog
            bigramMappedBuffer = mapped
            bigramIndex = index
            true
        }

        fun release() {
            val heldBigramLease: BigramTableLease?
            val heldBigramCatalog: PublishedBigramTableCatalog?
            synchronized(lock) {
                if (released) return
                released = true
                heldBigramLease = bigramLease
                heldBigramCatalog = bigramCatalog
                bigramLease = null
                bigramCatalog = null
                bigramMappedBuffer = null
                bigramIndex = null
            }
            index = null
            mappedBuffer = null
            val heldLease = lease
            lease = null
            try {
                heldLease?.close()
            } catch (_: Throwable) {
                // The engine has already dropped all mapping references.
            } finally {
                try {
                    catalog.cleanupReleasedVersions()
                } catch (_: Throwable) {
                    // Cleanup is best-effort and cannot affect ordinary input.
                }
                try {
                    heldBigramLease?.close()
                } catch (_: Throwable) {
                    // Same fail-closed posture as the dictionary lease above.
                } finally {
                    try {
                        heldBigramCatalog?.cleanupReleasedVersions()
                    } catch (_: Throwable) {
                        // Best-effort, same as the dictionary catalog cleanup.
                    }
                }
            }
        }
    }

    companion object {
        internal val FILE_MAPPER = DictionaryMapper { file, size ->
            FileInputStream(file).use { stream ->
                stream.channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                }
            }
        }

        /**
         * Acquires and consumes a catalog lease. Call off the UI thread: catalog validation and
         * mmap both perform file I/O. On success the lease is owned exclusively by the returned
         * engine until destroy; on every failure it is closed here exactly once.
         *
         * [suffixTable]/[afterWordFormsFactory] are the P3 word-form wiring (docs/TT-SUGGESTIONS.md):
         * the Tatar engine is started with both, every other engine with nulls — a null table keeps
         * the exact pass byte-identical to the frozen D1 behavior and a null factory keeps
         * NEXT_WORD the pure bigram list. [fuzzyEditPolicy] is the TT-TYPO-NEXT Phase-B wiring of
         * the same kind (docs/TT-TYPO-NEXT.md): null is [FuzzyEditPolicy.DEFAULT] — class #1 only,
         * no same-length bonus, exactly the pre-Phase-B shipped behavior. [fallbackWordsFactory] is
         * the TT-NEXTWORD-FILL wiring (docs/TT-NEXTWORD-FILL.md): built per engine from that
         * engine's own dictionary, so both shipped languages fill their still-empty NEXT_WORD cells
         * with their own top-frequency words; null keeps the pre-fill behavior byte-identical.
         * [personalBigrams] is the P1 wiring (docs/ROADMAP-P2.md): the user's learned pairs of the
         * NEXT_WORD slot, ranked after the static successors and before the forms;
         * [PersonalBigramSource.EMPTY] keeps the pre-P1 behavior byte-identical.
         */
        fun start(
            catalog: PublishedDictionaryCatalog,
            resultHandoff: ResultHandoff,
            executorFactory: () -> EngineExecutor =
                ExecutorServiceEngineExecutor::singleThread,
            mapper: DictionaryMapper = FILE_MAPPER,
            personalCandidates: PersonalCandidateSource = PersonalCandidateSource.EMPTY,
            suffixTable: InflectedSuffixTable? = null,
            afterWordFormsFactory: AfterWordFormsFactory? = null,
            fuzzyEditPolicy: FuzzyEditPolicy? = null,
            fallbackWordsFactory: FallbackWordsFactory? = null,
            personalBigrams: PersonalBigramSource = PersonalBigramSource.EMPTY,
        ): MappedDictionaryEngine? {
            val lease = try {
                catalog.acquireLatestForActivation()
            } catch (_: Throwable) {
                null
            } ?: return null
            return startOwnedLease(
                lease, catalog, resultHandoff, executorFactory, mapper, personalCandidates,
                suffixTable, afterWordFormsFactory, fuzzyEditPolicy, fallbackWordsFactory,
                personalBigrams,
            )
        }

        private fun startOwnedLease(
            lease: DictionaryFileLease,
            catalog: PublishedDictionaryCatalog,
            resultHandoff: ResultHandoff,
            executorFactory: () -> EngineExecutor,
            mapper: DictionaryMapper,
            personalCandidates: PersonalCandidateSource,
            suffixTable: InflectedSuffixTable?,
            afterWordFormsFactory: AfterWordFormsFactory?,
            fuzzyEditPolicy: FuzzyEditPolicy?,
            fallbackWordsFactory: FallbackWordsFactory?,
            personalBigrams: PersonalBigramSource,
        ): MappedDictionaryEngine? {
            val dictionary = lease.dictionary
            val identity = DictionaryIdentity(
                dictionary.generation,
                dictionary.schemaId,
                dictionary.formatVersion,
                dictionary.rawSha256,
            )
            var mapped: ByteBuffer? = null
            var createdExecutor: EngineExecutor? = null
            try {
                mapped = mapper.mapReadOnly(dictionary.file, dictionary.rawSize)
                val index = TdictPrefixIndex.open(
                    mapped,
                    identity,
                    dictionary.entryCount,
                    dictionary.rawSize,
                    suffixTable,
                    fuzzyEditPolicy,
                ) ?: throw IllegalArgumentException("validated dictionary layout mismatch")
                val executor = executorFactory()
                createdExecutor = executor
                val resources = Resources(lease, catalog, mapped, index)
                // The three-class merge of E4b lives in the same computer as the E3 fuzzy pass,
                // because only there are both the candidate classes and the frequencies known. The
                // engine's public surface does not widen: what goes out through PrefixComputer.lookup
                // is still a List<String>, and there is no second request, token or isCurrent.
                //
                // The after-word forms are created against THIS engine's index: a schema-3 bigram
                // table links itself to one dictionary by raw SHA-256, and the forms must rank by
                // the frequencies of that same dictionary.
                // P7-3 (docs/GLIDE-PLAN.md): the glide decode side — the decoder's word inventory
                // is THIS engine's dictionary, extended with the personal one by the host
                // (docs/GLIDE-PERSONAL.md): the same [personalCandidates] seam the prefix merge
                // already reads, with the dictionary's cold exact-membership read as the
                // duplicate check. The geometry arrives later, pushed from the live layout
                // through updateGlideGeometry; until then decodeGlide answers empty.
                val computer = CompositePrefixComputer(
                    index, personalCandidates, afterWordFormsFactory?.createAfterWordForms(index),
                    // TT-NEXTWORD-FILL: the factory computes the top-frequency pool HERE — one
                    // linear scan of the freshly opened dictionary on this background startup
                    // thread, at most once per engine, before the engine's lookup worker exists.
                    fallbackWordsFactory?.createFallbackWords(index),
                    // P1: the learned pairs ride the same per-language seam as the personal source
                    // above — resolved by the caller from the subtype, never from a constant.
                    personalBigrams,
                    GlideDecoderHost(TdictGlideInventory(index), personalCandidates, index::containsWordCold),
                )
                val engine = LatestOnlyPrefixEngine(
                    identity,
                    computer,
                    executor,
                    resultHandoff,
                    resources::release,
                )
                return MappedDictionaryEngine(identity, engine, computer, resources)
            } catch (_: Throwable) {
                mapped = null
                try {
                    createdExecutor?.shutdown()
                } catch (_: Throwable) {
                    // Startup already failed; executor cleanup is best-effort.
                }
                try {
                    lease.close()
                } catch (_: Throwable) {
                    // Ownership was consumed; a failing release still must not escape startup.
                } finally {
                    try {
                        catalog.cleanupReleasedVersions()
                    } catch (_: Throwable) {
                        // Fail closed without logging dictionary paths or typed text.
                    }
                }
                return null
            }
        }
    }
}
