package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramTableLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AssetInputProvider
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AtomicDictionaryStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DeviceProtectedDirectoryProvider
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedBigramTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedBigramTableCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageClock
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TestDurableFileOps
import com.sun.management.UnixOperatingSystemMXBean
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.util.concurrent.TimeUnit

class MappedDictionaryEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mapClosesChannelBeforeWorkerCreationAndLeaseLivesUntilTermination() {
        val fixture = dictionaryFixture(1)
        val events = mutableListOf<String>()
        var released = false
        val lease = lease(fixture, events) { released = true }
        val catalog = RecordingCatalog(lease, events) { released }
        val executor = ManualEngineExecutor()

        val engine = MappedDictionaryEngine.start(
            catalog,
            ResultHandoff {},
            executorFactory = {
                events += "executor-create"
                executor
            },
            mapper = DictionaryMapper { file, _ ->
                events += "channel-open"
                ByteBuffer.wrap(file.readBytes()).also { events += "channel-close" }
            },
        )

        assertNotNull(engine)
        assertEquals(listOf("channel-open", "channel-close", "executor-create"), events)
        requireNotNull(engine).request(1, "tt", utf8("а"))
        assertFalse(engine.destroy(0, TimeUnit.MILLISECONDS))
        assertFalse(released)
        assertFalse(events.contains("cleanup"))

        executor.runAll()
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        assertTrue(released)
        assertEquals("lease-close", events[events.lastIndex - 1])
        assertEquals("cleanup", events.last())
        assertEquals(1, events.count { it == "lease-close" })
    }

    @Test
    fun productionMapperReadsFileAfterItsChannelHasBeenClosed() {
        val fixture = dictionaryFixture(1)
        val events = mutableListOf<String>()
        var released = false
        val executor = ManualEngineExecutor()
        val catalog = RecordingCatalog(
            lease(fixture, events) { released = true },
            events,
        ) { released }
        val engine = requireNotNull(
            MappedDictionaryEngine.start(
                catalog,
                ResultHandoff {},
                executorFactory = { executor },
            ),
        )

        val renamed = File(fixture.file.parentFile, "renamed.tdict")
        assertTrue(fixture.file.renameTo(renamed))
        engine.request(1, "tt", utf8("а"))
        executor.runAll()
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        assertTrue(released)
    }

    @Test
    fun repeatedReadOnlyMmapLifecycleDoesNotRetainFileDescriptorsOrLeases() {
        val initialOpenFileDescriptors = requireNotNull(openFileDescriptorCount())
        var releases = 0

        repeat(100) { generation ->
            val fixture = dictionaryFixture(generation + 1)
            assertTrue(fixture.file.setReadOnly())
            val executor = ManualEngineExecutor()
            val published = mutableListOf<LookupResult>()
            val catalog = SingleLeaseCatalog(DictionaryFileLease(fixture) { releases++ })
            val engine = requireNotNull(
                MappedDictionaryEngine.start(
                    catalog,
                    ResultHandoff { published += it },
                    executorFactory = { executor },
                    mapper = DictionaryMapper { file, size ->
                        MappedDictionaryEngine.FILE_MAPPER.mapReadOnly(file, size).also {
                            assertTrue("production mapper must use mmap", it is MappedByteBuffer)
                            assertTrue("production mapping must be read-only", it.isReadOnly)
                        }
                    },
                ),
            )
            engine.request(generation.toLong(), "tt", utf8("а"))
            executor.runAll()
            assertEquals(listOf("аб", "аба"), published.single().suggestions)
            assertTrue(engine.destroy(1, TimeUnit.SECONDS))
            assertEquals(generation + 1, releases)
        }

        val finalOpenFileDescriptors = requireNotNull(openFileDescriptorCount())
        assertTrue(
            "open FD grew from $initialOpenFileDescriptors to $finalOpenFileDescriptors",
            finalOpenFileDescriptors <= initialOpenFileDescriptors + 3,
        )
    }

    @Test
    fun finishClearsPendingButMappingAndLeaseRemainUntilWorkerStopsAndDestroyCompletes() {
        val fixture = dictionaryFixture(1)
        val events = mutableListOf<String>()
        var released = false
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val catalog = RecordingCatalog(
            lease(fixture, events) { released = true },
            events,
        ) { released }
        val engine = requireNotNull(
            MappedDictionaryEngine.start(
                catalog,
                ResultHandoff { published += it },
                executorFactory = { executor },
                mapper = DictionaryMapper { file, _ -> ByteBuffer.wrap(file.readBytes()) },
            ),
        )
        engine.request(1, "tt", utf8("а"))
        engine.request(1, "tt", utf8("ә"))

        engine.finishInput()
        assertFalse(engine.destroy(0, TimeUnit.MILLISECONDS))
        assertFalse(released)
        executor.runAll()
        assertTrue(published.isEmpty())

        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        assertTrue(released)
        assertEquals(listOf("lease-close", "cleanup"), events)
    }

    @Test
    fun corruptMappingFailsClosedReleasesLeaseAndNeverCreatesExecutor() {
        val fixture = dictionaryFixture(1)
        val events = mutableListOf<String>()
        var released = false
        var executorCreated = false
        val catalog = RecordingCatalog(
            lease(fixture, events) { released = true },
            events,
        ) { released }

        val engine = MappedDictionaryEngine.start(
            catalog,
            ResultHandoff {},
            executorFactory = {
                executorCreated = true
                ManualEngineExecutor()
            },
            mapper = DictionaryMapper { _, _ -> ByteBuffer.wrap(byteArrayOf(1, 2, 3)) },
        )

        assertNull(engine)
        assertFalse(executorCreated)
        assertTrue(released)
        assertEquals(listOf("lease-close", "cleanup"), events)
    }

    @Test
    fun throwableAtEveryStartupStageClosesConsumedLeaseExactlyOnce() {
        val failures = listOf("mapper", "index", "executor")
        for ((caseIndex, failure) in failures.withIndex()) {
            val fixture = dictionaryFixture(caseIndex + 1)
            var releases = 0
            var cleanups = 0
            val catalog = object : PublishedDictionaryCatalog {
                private var lease: DictionaryFileLease? = DictionaryFileLease(fixture) {
                    releases++
                    if (failure == "mapper") throw AssertionError("release")
                }

                override fun acquireLatestForActivation(): DictionaryFileLease? =
                    lease.also { lease = null }

                override fun cleanupReleasedVersions() {
                    cleanups++
                }
            }
            val engine = MappedDictionaryEngine.start(
                catalog,
                ResultHandoff {},
                executorFactory = {
                    if (failure == "executor") throw AssertionError("executor")
                    ManualEngineExecutor()
                },
                mapper = DictionaryMapper { file, _ ->
                    when (failure) {
                        "mapper" -> throw AssertionError("mapper")
                        "index" -> ByteBuffer.wrap(byteArrayOf(1, 2, 3))
                        else -> ByteBuffer.wrap(file.readBytes())
                    }
                },
            )

            assertNull(failure, engine)
            assertEquals(failure, 1, releases)
            assertEquals(failure, 1, cleanups)
        }
    }

    @Test
    fun publicStartupConsumesCatalogLeaseWithoutExposingPrematureCloseParameter() {
        val publicMethods = MappedDictionaryEngine.Companion::class.java.methods
            .filter { it.declaringClass == MappedDictionaryEngine.Companion::class.java }

        assertTrue(publicMethods.any { it.name == "start" })
        assertTrue(
            publicMethods.none { method ->
                method.parameterTypes.any { it == DictionaryFileLease::class.java }
            },
        )
    }

    @Test
    fun realStoreBlocksV2UntilV1EngineDestroyThenActivatesWithoutHotSwap() {
        val v1 = DictionaryTestFixtures.artifact(1)
        val v2 = DictionaryTestFixtures.artifact(
            2,
            listOf("аб" to 31, "аба" to 20, "әби" to 10),
        )
        val directory = temporaryFolder.newFolder("real-store-lifecycle")
        val compressed = mapOf(1 to v1.compressed, 2 to v2.compressed)
        val store = AtomicDictionaryStore(
            DeviceProtectedDirectoryProvider { directory },
            AssetInputProvider { spec ->
                ByteArrayInputStream(requireNotNull(compressed[spec.generation]))
            },
            StorageClock { 1L },
            SpaceProbe { Long.MAX_VALUE },
            TestDurableFileOps(),
            listOf(v1.spec, v2.spec),
        )
        assertTrue(store.ensurePublished(v1.spec) is rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult.Published)
        val v1Executor = ManualEngineExecutor()
        val v1Engine = requireNotNull(
            MappedDictionaryEngine.start(
                store,
                ResultHandoff {},
                executorFactory = { v1Executor },
            ),
        )
        assertEquals(1, v1Engine.identity.generation)

        assertTrue(store.ensurePublished(v2.spec) is rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult.Published)
        assertNull(store.acquireLatestForActivation())
        assertEquals(1, v1Engine.identity.generation)
        assertTrue(v1Engine.destroy(1, TimeUnit.SECONDS))

        val v2Engine = requireNotNull(
            MappedDictionaryEngine.start(
                store,
                ResultHandoff {},
                executorFactory = { ManualEngineExecutor() },
            ),
        )
        assertEquals(2, v2Engine.identity.generation)
        assertTrue(v2Engine.destroy(1, TimeUnit.SECONDS))
        assertTrue(directory.listFiles().orEmpty().count { it.name.endsWith(".tdict") } <= 2)
    }

    @Test
    fun sequentialGenerationsNeverHotSwapAndRetentionStaysAtCurrentPlusOne() {
        val retained = mutableListOf<Int>()
        val active = mutableSetOf<Int>()
        val catalog = object : PublishedDictionaryCatalog {
            var nextLease: DictionaryFileLease? = null

            override fun acquireLatestForActivation(): DictionaryFileLease? =
                nextLease.also { nextLease = null }

            override fun cleanupReleasedVersions() {
                while (retained.size > 2) {
                    val removable = retained.firstOrNull { it !in active && it != retained.maxOrNull() }
                    if (removable == null) return
                    retained.remove(removable)
                }
            }
        }

        for (generation in 1..3) {
            val fixture = dictionaryFixture(generation)
            retained += generation
            active += generation
            val executor = ManualEngineExecutor()
            val lease = DictionaryFileLease(fixture) { active -= generation }
            catalog.nextLease = lease
            val engine = requireNotNull(
                MappedDictionaryEngine.start(
                    catalog,
                    ResultHandoff {},
                    executorFactory = { executor },
                    mapper = DictionaryMapper { file, _ -> ByteBuffer.wrap(file.readBytes()) },
                ),
            )
            assertEquals(generation, engine.identity.generation)
            assertTrue(engine.destroy(1, TimeUnit.SECONDS))
            assertTrue(retained.size <= 2)
            assertTrue(active.isEmpty())
        }
        assertEquals(listOf(2, 3), retained)
    }

    // --- two-stage readiness: attachBigramSource after an already-published engine ------------

    @Test
    fun beforeAttachRequestNextWordReturnsEmptyWithoutBlockingStart() {
        val executor = ManualEngineExecutor()
        val engine = requireNotNull(startWithDictionaryOnly(executor))

        val token = requireNotNull(engine.requestNextWord(1, "tt", utf8("аб")))
        executor.runAll()

        assertEquals(LookupKind.NEXT_WORD, token.kind)
        // No assertion needed on WHAT is published here beyond "no crash, no block" — the
        // ResultHandoff in startWithDictionaryOnly discards results; the empty-before-attach
        // behavior itself is covered end to end by the next test.
        engine.destroy(1, TimeUnit.SECONDS)
    }

    @Test
    fun attachBigramSourceWiresPredictionsIntoTheAlreadyPublishedEngine() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val engine = requireNotNull(startWithDictionaryOnly(executor, published))
        val bigramCatalog = bigramCatalog(bigramFixture(listOf("аб" to listOf("аба"))))

        val attached = engine.attachBigramSource(
            bigramCatalog,
            mapper = DictionaryMapper { file, _ -> ByteBuffer.wrap(file.readBytes()) },
        )
        assertTrue(attached)

        engine.requestNextWord(1, "tt", utf8("аб"))
        executor.runAll()

        assertEquals(listOf("аба"), published.single().suggestions)
        assertEquals(LookupKind.NEXT_WORD, published.single().kind)
        engine.destroy(1, TimeUnit.SECONDS)
    }

    @Test
    fun attachBigramSourceFailsClosedOnCorruptTableAndLeavesPrefixUnaffected() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val engine = requireNotNull(startWithDictionaryOnly(executor, published))
        val corruptFile = temporaryFolder.newFile("corrupt.tatbigr").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val corruptTable = PublishedBigramTable(1, "tt", corruptFile, 3, 1, 1, 1, 3, 1, "0".repeat(64))
        var closed = false
        val catalog = bigramCatalog(corruptTable) { closed = true }

        val attached = engine.attachBigramSource(
            catalog,
            mapper = DictionaryMapper { file, _ -> ByteBuffer.wrap(file.readBytes()) },
        )

        assertFalse(attached)
        assertTrue("a failed attach must close the lease it acquired", closed)

        // PREFIX lookups are completely unaffected by a bigram attach failure.
        engine.request(1, "tt", utf8("а"))
        executor.runAll()
        assertEquals(1, published.size)
        engine.destroy(1, TimeUnit.SECONDS)
    }

    @Test
    fun attachBigramSourceFailsClosedWhenNoTableIsPublishedAndLeavesPrefixUnaffected() {
        // The "missing file" branch of attachBigramSource — acquireLatestForActivation() returns
        // null (nothing published, or the only version is staged behind a live lease) rather than
        // throwing or handing back a corrupt table. This is the ?: return false path, distinct
        // from the corrupt-table and racing-destroy paths already covered above.
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val engine = requireNotNull(startWithDictionaryOnly(executor, published))
        val catalog = object : PublishedBigramTableCatalog {
            override fun acquireLatestForActivation(): BigramTableLease? = null
            override fun cleanupReleasedVersions() = Unit
        }

        val attached = engine.attachBigramSource(
            catalog,
            mapper = DictionaryMapper { file, _ -> ByteBuffer.wrap(file.readBytes()) },
        )

        assertFalse(attached)

        // PREFIX lookups are completely unaffected by a missing bigram table.
        engine.request(1, "tt", utf8("а"))
        executor.runAll()
        assertEquals(1, published.size)
        engine.destroy(1, TimeUnit.SECONDS)
    }

    @Test
    fun destroyClosesBothDictionaryAndBigramLeasesExactlyOnce() {
        val executor = ManualEngineExecutor()
        var dictionaryClosed = false
        val dictionaryFixture = dictionaryFixture(1)
        val dictionaryLease = DictionaryFileLease(dictionaryFixture) { dictionaryClosed = true }
        val engine = requireNotNull(
            MappedDictionaryEngine.start(
                SingleLeaseCatalog(dictionaryLease),
                ResultHandoff {},
                executorFactory = { executor },
                mapper = DictionaryMapper { file, _ -> ByteBuffer.wrap(file.readBytes()) },
            ),
        )
        var bigramClosed = 0
        val bigramCatalog = bigramCatalog(bigramFixture(listOf("аб" to listOf("аба")))) { bigramClosed++ }
        assertTrue(
            engine.attachBigramSource(
                bigramCatalog,
                mapper = DictionaryMapper { file, _ -> ByteBuffer.wrap(file.readBytes()) },
            ),
        )

        assertTrue(engine.destroy(1, TimeUnit.SECONDS))

        assertTrue(dictionaryClosed)
        assertEquals(1, bigramClosed)
        // Idempotent: a second destroy must not double-close either lease.
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        assertEquals(1, bigramClosed)
    }

    @Test
    fun attachAfterDestroyClosesTheAcquiredBigramLeaseWithoutPublishingIt() {
        val executor = ManualEngineExecutor()
        val engine = requireNotNull(startWithDictionaryOnly(executor))
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        var closed = false
        val catalog = bigramCatalog(bigramFixture(listOf("аб" to listOf("аба")))) { closed = true }

        val attached = engine.attachBigramSource(
            catalog,
            mapper = DictionaryMapper { file, _ -> ByteBuffer.wrap(file.readBytes()) },
        )

        assertFalse("attach must lose the race against a prior destroy", attached)
        assertTrue("the racing lease must not be leaked", closed)
    }

    private fun startWithDictionaryOnly(
        executor: ManualEngineExecutor,
        published: MutableList<LookupResult> = mutableListOf(),
    ): MappedDictionaryEngine? = MappedDictionaryEngine.start(
        SingleLeaseCatalog(DictionaryFileLease(dictionaryFixture(1)) {}),
        ResultHandoff { published += it },
        executorFactory = { executor },
        mapper = DictionaryMapper { file, _ -> ByteBuffer.wrap(file.readBytes()) },
    )

    private fun bigramFixture(headsToSuccesses: List<Pair<String, List<String>>>): PublishedBigramTable {
        // Schema 3 cross-references the very fixture dictionary the engine opens: the same word
        // list (so the indices agree) and the header names that dictionary's raw SHA-256.
        val dictionaryWords = listOf("аб", "аба", "әби")
        val dictionarySha = DictionaryTestFixtures.artifact(
            1,
            listOf("аб" to 31L, "аба" to 20L, "әби" to 10L),
        ).spec.expectedRawSha256
        val raw = BigramTestFixtures.raw(headsToSuccesses, dictionaryWords, dictionarySha)
        val file = temporaryFolder.newFile("bigrams-${System.nanoTime()}.tatbigr")
        file.writeBytes(raw)
        return PublishedBigramTable(
            1, "tt", file, raw.size.toLong(), headsToSuccesses.size.toLong(),
            headsToSuccesses.sumOf { it.second.size }.toLong(),
            headsToSuccesses.flatMap { it.second }.toSet().size.toLong(),
            3, 1,
            BigramTestFixtures.sha256(raw),
        )
    }

    private fun bigramCatalog(
        table: PublishedBigramTable,
        onClose: () -> Unit = {},
    ): PublishedBigramTableCatalog {
        var lease: BigramTableLease? =
            BigramTableLease(table, onClose)
        return object : PublishedBigramTableCatalog {
            override fun acquireLatestForActivation() = lease.also { lease = null }
            override fun cleanupReleasedVersions() = Unit
        }
    }

    private fun dictionaryFixture(generation: Int): PublishedDictionary {
        val artifact = DictionaryTestFixtures.artifact(
            generation,
            listOf("аб" to (30L + generation), "аба" to 20, "әби" to 10),
        )
        val file = temporaryFolder.newFile("dictionary-$generation.tdict")
        file.writeBytes(artifact.raw)
        return PublishedDictionary(
            generation,
            file,
            artifact.spec.expectedRawSize,
            artifact.spec.expectedEntryCount,
            artifact.spec.schemaId,
            artifact.spec.formatVersion,
            artifact.spec.expectedRawSha256,
        )
    }

    private fun lease(
        dictionary: PublishedDictionary,
        events: MutableList<String>,
        release: () -> Unit,
    ) = DictionaryFileLease(dictionary) {
        events += "lease-close"
        release()
    }

    private class RecordingCatalog(
        private var lease: DictionaryFileLease?,
        private val events: MutableList<String>,
        private val released: () -> Boolean,
    ) : PublishedDictionaryCatalog {
        override fun acquireLatestForActivation(): DictionaryFileLease? =
            lease.also { lease = null }

        override fun cleanupReleasedVersions() {
            assertTrue("cleanup ran before lease release", released())
            events += "cleanup"
        }
    }

    private class SingleLeaseCatalog(
        private var lease: DictionaryFileLease?,
    ) : PublishedDictionaryCatalog {
        override fun acquireLatestForActivation(): DictionaryFileLease? =
            lease.also { lease = null }

        override fun cleanupReleasedVersions() = Unit
    }

    private fun openFileDescriptorCount(): Long? =
        (ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean)
            ?.openFileDescriptorCount

    private fun utf8(value: String) = value.toByteArray(Charsets.UTF_8)
}
