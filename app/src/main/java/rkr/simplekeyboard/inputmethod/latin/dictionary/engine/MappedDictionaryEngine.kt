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
    private val mapper: DictionaryMapper,
) {
    fun request(
        editorSessionId: Long,
        subtypeId: String,
        normalizedPrefixUtf8: ByteArray,
    ): LookupToken? = engine.request(editorSessionId, subtypeId, normalizedPrefixUtf8)

    /** NEXT_WORD counterpart of [request]: same engine, token and executor, different kind. */
    fun requestNextWord(
        editorSessionId: Long,
        subtypeId: String,
        normalizedContextWordUtf8: ByteArray,
    ): LookupToken? = engine.requestNextWord(editorSessionId, subtypeId, normalizedContextWordUtf8)

    /** GLIDE counterpart of [request]: same engine, same rules. */
    fun requestGlide(
        editorSessionId: Long,
        subtypeId: String,
        path: GlidePath,
    ): LookupToken? = engine.requestGlide(editorSessionId, subtypeId, path)

    /**
     * Idle memory release of the glide word index. The engine posts the drop onto its serialized
     * worker (the decoder is worker-confined). Safe from any thread; no-op once destroyed.
     */
    fun releaseGlideIndex() = engine.releaseGlideIndex()

    /**
     * Second stage of readiness: acquires, maps and opens the bigram table, then wires it into the
     * already-published composite computer. Call off the UI thread (file I/O, like [start]).
     *
     * Returns false, with no effect on this engine, if the table is missing or corrupt or the
     * engine was destroyed meanwhile (whichever of this and [destroy] reaches [Resources] first
     * wins; the loser's lease is closed unused). On failure [CompositePrefixComputer.predict] keeps
     * returning an empty list; word completion and typing are unaffected.
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
            // Schema 3 resolves its head/successor indices through the linked dictionary and
            // refuses to open against any other (the header names the dictionary's raw SHA-256),
            // so a missing dictionary index is treated like an invalid table.
            val dictionaryIndex = resources.index
                ?: throw IllegalArgumentException("bigram attach before the dictionary is open")
            val index = TatBigrPrefixIndex.open(
                mapped, bigramIdentity, dictionaryIndex, table.headCount, table.rawSize,
            ) ?: throw IllegalArgumentException("validated bigram layout mismatch")
            if (!resources.attachBigram(lease, catalog, mapped, index)) {
                // The engine was destroyed meanwhile: attachBigram left the lease for us to
                // close, as in the failure path below; do not publish into a dead computer.
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
                    // Best-effort; never log paths or typed text.
                }
            }
            false
        }
    }

    /**
     * Hands the published bigram catalog over without opening the table: the mapping and the open
     * walk run lazily on the engine worker at the first NEXT_WORD lookup. A missing, corrupt or
     * vanished table leaves NEXT_WORD answering empty, exactly as with [attachBigramSource].
     */
    fun deferBigramAttach(catalog: PublishedBigramTableCatalog) {
        computer.deferBigramAttach { attachBigramSource(catalog, mapper) }
    }

    /**
     * Autocorrect verdict of the newest completed lookup, or null when nothing may be replaced.
     * A `@Volatile` read of an immutable object; the UI thread touches no mapped buffer or lock.
     * The reader must still check [AutocorrectAdvice.typedWord] against the live word.
     */
    val autocorrectAdvice: AutocorrectAdvice?
        get() = computer.lastAutocorrectAdvice

    /**
     * The normalized prefix of the newest completed lookup whose exact pass was empty, or null.
     * The reader must check it against its own prefix, as with [autocorrectAdvice].
     */
    val exactMissPrefix: String?
        get() = computer.lastExactMissPrefix

    fun finishInput() {
        // Idling the engine invalidates the generation, so the verdicts computed for it go too.
        computer.clearLookupVerdicts()
        engine.finishInput()
    }

    /**
     * Exact whole-word membership of [normalizedWord] in this engine's dictionary, used to decide
     * whether a word pair may be learned. Safe from any thread: a cache-free read of the mapping
     * that never touches the lookup scratch (see [TdictPrefixIndex.containsWordCold]).
     *
     * Engine liveness is not checked: a released mapping stays valid until GC (closing the channel
     * does not unmap it), so a racing [destroy] can at worst answer for the previous dictionary
     * generation, which is harmless for learning.
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

    /** Pushes the live layout geometry into the glide decode side (null disables glide typing). */
    fun updateGlideGeometry(geometry: GlideKeyGeometry?) = engine.updateGlideGeometry(geometry)

    fun isCurrent(token: LookupToken): Boolean = engine.isCurrent(token)

    fun destroy(timeout: Long, unit: TimeUnit): Boolean {
        computer.clearLookupVerdicts()
        return engine.destroy(timeout, unit)
    }

    val suppressedStaleResultCount: Long
        get() = engine.suppressedStaleResultCount

    /** Internal control-flow signal; never escapes [attachBigramSource]. */
    private class AttachRacedDestroy : Exception()

    /**
     * Owns the dictionary lease and mapping from construction and, after a successful
     * [attachBigram], the bigram lease and mapping too. [release] frees both exactly once;
     * [attachBigram] and [release] share [lock], so a racing [attachBigramSource] and [destroy]
     * neither leak nor double-close a lease.
     */
    private class Resources(
        private var lease: DictionaryFileLease?,
        private val catalog: PublishedDictionaryCatalog,
        var mappedBuffer: ByteBuffer?,
        index: TdictPrefixIndex?,
    ) {
        /**
         * Read by [MappedDictionaryEngine.containsWord] from any thread, hence `@Volatile`. A
         * stale read after [release] is harmless (see [MappedDictionaryEngine.containsWord]).
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

        /** False once released; the caller then still owns [lease] and must close it. */
        fun attachBigram(
            lease: BigramTableLease,
            catalog: PublishedBigramTableCatalog,
            mapped: ByteBuffer,
            index: TatBigrPrefixIndex,
        ): Boolean = synchronized(lock) {
            if (released) return@synchronized false
            // Attach happens once per engine lifetime; replacing rather than accumulating keeps
            // a repeated call safe.
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
                    // Best-effort, as for the dictionary lease above.
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
         * Optional per-language wiring; the Tatar engine gets all of it:
         *  - [suffixTable], [afterWordFormsFactory]: word forms in the exact pass and in NEXT_WORD
         *    answers; null means none;
         *  - [fuzzyEditPolicy]: typo-recovery classes; null is [FuzzyEditPolicy.DEFAULT];
         *  - [fallbackWordsFactory]: top-frequency fill of empty NEXT_WORD cells; null means none;
         *  - [personalBigrams]: learned word pairs, ranked after the bigram successors and before
         *    the forms; [PersonalBigramSource.EMPTY] means none.
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
                // The personal merge lives in the same computer as the typo-recovery pass,
                // because only there are both the candidate classes and the frequencies known.
                // PrefixComputer.lookup still returns a plain List<String>.
                //
                // The after-word forms are created against this engine's index: a schema-3 bigram
                // table is linked to one dictionary by raw SHA-256, and the forms must rank by the
                // frequencies of that same dictionary.
                //
                // Glide: the decoder's word inventory is this engine's dictionary, extended by the
                // host with the same [personalCandidates] the prefix merge reads, with the
                // dictionary's cold exact-membership read as the duplicate check. Geometry arrives
                // later through updateGlideGeometry; until then decodeGlide returns nothing.
                val computer = CompositePrefixComputer(
                    index, personalCandidates, afterWordFormsFactory?.createAfterWordForms(index),
                    // The factory computes the top-frequency pool here: one linear scan of the
                    // dictionary on this background startup thread, once per engine.
                    fallbackWordsFactory?.createFallbackWords(index),
                    // Learned pairs are resolved per language by the caller, like the personal
                    // source above.
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
                return MappedDictionaryEngine(identity, engine, computer, resources, mapper)
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
                        // Best-effort; never log dictionary paths or typed text.
                    }
                }
                return null
            }
        }
    }
}
