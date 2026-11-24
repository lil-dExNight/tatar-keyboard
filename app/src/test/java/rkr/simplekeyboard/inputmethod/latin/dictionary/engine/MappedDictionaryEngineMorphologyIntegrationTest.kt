package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StemLexiconTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatStemFormat
import rkr.simplekeyboard.inputmethod.latin.morphology.TatarMorphologicalAcceptor

class MappedDictionaryEngineMorphologyIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun loadRunsOnItsOwnThreadAndPublishesOneStableInstance() {
        val dictionary = dictionaryFixture("worker")
        val expected = acceptor(dictionary.rawSha256)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val mappingThread = AtomicReference<Thread>()
        val loadingThread = AtomicReference<Thread>()
        val probeThread = AtomicReference<Thread>()
        val loadCount = AtomicInteger()
        val engine = start(
            dictionary,
            mapper = DictionaryMapper { file, _ ->
                mappingThread.set(Thread.currentThread())
                events += "map"
                ByteBuffer.wrap(file.readBytes())
            },
            wordformAcceptorLoader = MorphologicalAcceptorLoader {
                loadingThread.set(Thread.currentThread())
                events += "load"
                loadCount.incrementAndGet()
                expected
            },
        )
        try {
            val initialized = CountDownLatch(1)
            assertTrue(
                engine.requestEmptyPrefixProbe("аб") {
                    probeThread.set(Thread.currentThread())
                    initialized.countDown()
                },
            )
            assertTrue(initialized.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (engine.wordformAcceptor == null && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }

            assertEquals(listOf("map", "load"), events)
            assertNotSame(mappingThread.get(), loadingThread.get())
            assertNotSame(Thread.currentThread(), loadingThread.get())
            assertNotSame(loadingThread.get(), probeThread.get())
            assertEquals(1, loadCount.get())
            assertSame(expected, engine.wordformAcceptor)
            assertSame(expected, engine.wordformAcceptor)

            engine.finishInput()

            assertSame(expected, engine.wordformAcceptor)
            assertEquals(1, loadCount.get())
        } finally {
            assertTrue(engine.destroy(5, TimeUnit.SECONDS))
        }
        assertNull(engine.wordformAcceptor)
    }

    @Test
    fun firstLookupDoesNotWaitForAcceptorAndLateLoadPublishesIt() {
        val dictionary = dictionaryFixture("independent-loader")
        val expected = acceptor(dictionary.rawSha256)
        val loaderStarted = CountDownLatch(1)
        val finishLoader = CountDownLatch(1)
        val resultReady = CountDownLatch(1)
        val published = mutableListOf<LookupResult>()
        val engine = start(
            dictionary,
            resultHandoff = ResultHandoff {
                published += it
                resultReady.countDown()
            },
            wordformAcceptorLoader = MorphologicalAcceptorLoader {
                loaderStarted.countDown()
                finishLoader.await(5, TimeUnit.SECONDS)
                expected
            },
        )
        try {
            engine.request(1, "tt", utf8("аб"))
            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS))
            val answeredBeforeLoadFinished = resultReady.await(500, TimeUnit.MILLISECONDS)
            finishLoader.countDown()

            assertTrue("the first lookup must not queue behind morphology", answeredBeforeLoadFinished)
            assertEquals(listOf("аба"), published.single().suggestions)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (engine.wordformAcceptor == null && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertSame(expected, engine.wordformAcceptor)
        } finally {
            finishLoader.countDown()
            assertTrue(engine.destroy(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun corruptOrThrowingLoaderPublishesNullAndLeavesPrefixLookupUsable() {
        val corruptArtifact = StemLexiconTestFixtures.artifact(
            StemLexiconTestFixtures.defaultEntries(8),
        )
        val loaderFactories = listOf<(CountDownLatch) -> MorphologicalAcceptorLoader>(
            { finished ->
                MorphologicalAcceptorLoader {
                    try {
                        TatarMorphologicalAcceptor.open(
                            ByteArrayInputStream(byteArrayOf(1, 2, 3)), corruptArtifact.spec,
                        )
                    } finally {
                        finished.countDown()
                    }
                }
            },
            { finished ->
                MorphologicalAcceptorLoader {
                    try {
                        throw IOException("asset open failed")
                    } finally {
                        finished.countDown()
                    }
                }
            },
        )
        for ((index, loaderFactory) in loaderFactories.withIndex()) {
            val loaderFinished = CountDownLatch(1)
            val executor = ManualEngineExecutor()
            val published = mutableListOf<LookupResult>()
            val engine = start(
                dictionaryFixture("failure-$index"),
                executorFactory = { executor },
                resultHandoff = ResultHandoff { published += it },
                wordformAcceptorLoader = loaderFactory(loaderFinished),
            )
            assertEquals(0, executor.queueDepth)
            engine.request(1, "tt", utf8("аб"))

            executor.runAll()
            assertTrue(loaderFinished.await(5, TimeUnit.SECONDS))

            assertNull(engine.wordformAcceptor)
            assertEquals(listOf("аба"), published.single().suggestions)
            assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun dictionaryShaMismatchRejectsTheAcceptorWithoutAffectingLookup() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val loaderFinished = CountDownLatch(1)
        val engine = start(
            dictionaryFixture("mismatch"),
            executorFactory = { executor },
            resultHandoff = ResultHandoff { published += it },
            wordformAcceptorLoader = MorphologicalAcceptorLoader {
                try {
                    acceptor("0".repeat(64))
                } finally {
                    loaderFinished.countDown()
                }
            },
        )
        engine.request(1, "tt", utf8("аб"))

        executor.runAll()
        assertTrue(loaderFinished.await(5, TimeUnit.SECONDS))

        assertNull(engine.wordformAcceptor)
        assertEquals(listOf("аба"), published.single().suggestions)
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
    }

    @Test
    fun destroyDoesNotWaitForTheLoaderAndItsLateResultCannotPublish() {
        val loaderStarted = CountDownLatch(1)
        val finishLoader = CountDownLatch(1)
        val loadingThread = AtomicReference<Thread>()
        val loaderReleaseObserved = AtomicBoolean()
        val loadCount = AtomicInteger()
        val released = AtomicBoolean()
        val dictionary = dictionaryFixture("destroy")
        val expected = acceptor(dictionary.rawSha256)
        val engine = start(
            dictionary,
            leaseReleased = { released.set(true) },
            wordformAcceptorLoader = MorphologicalAcceptorLoader {
                loadCount.incrementAndGet()
                loadingThread.set(Thread.currentThread())
                loaderStarted.countDown()
                loaderReleaseObserved.set(finishLoader.await(5, TimeUnit.SECONDS))
                expected
            },
        )
        assertTrue(loaderStarted.await(5, TimeUnit.SECONDS))

        // The blocked loader is on its own thread, so teardown neither waits for it nor fails.
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        assertTrue(released.get())
        assertFalse(loaderReleaseObserved.get())

        finishLoader.countDown()
        loadingThread.get().join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(loadingThread.get().isAlive)
        assertEquals(1, loadCount.get())
        // The late result reaches an already-released engine and must not publish.
        assertNull(engine.wordformAcceptor)
    }

    private fun start(
        dictionary: PublishedDictionary,
        executorFactory: () -> EngineExecutor = ExecutorServiceEngineExecutor::singleThread,
        mapper: DictionaryMapper = DictionaryMapper { file, _ -> ByteBuffer.wrap(file.readBytes()) },
        resultHandoff: ResultHandoff = ResultHandoff {},
        leaseReleased: () -> Unit = {},
        wordformAcceptorLoader: MorphologicalAcceptorLoader,
    ): MappedDictionaryEngine = requireNotNull(
        MappedDictionaryEngine.start(
            SingleLeaseCatalog(DictionaryFileLease(dictionary, release = leaseReleased)),
            resultHandoff,
            executorFactory = executorFactory,
            mapper = mapper,
            wordformAcceptorLoader = wordformAcceptorLoader,
        ),
    )

    private fun dictionaryFixture(name: String): PublishedDictionary {
        val artifact = DictionaryTestFixtures.artifact(
            1,
            listOf("аб" to 31L, "аба" to 20L, "әби" to 10L),
        )
        val file = temporaryFolder.newFile("dictionary-$name.tdict")
        file.writeBytes(artifact.raw)
        return PublishedDictionary(
            1,
            file,
            artifact.spec.expectedRawSize,
            artifact.spec.expectedEntryCount,
            artifact.spec.schemaId,
            artifact.spec.formatVersion,
            artifact.spec.expectedRawSha256,
        )
    }

    private fun acceptor(dictionarySha256: String): TatarMorphologicalAcceptor {
        val fixture = StemLexiconTestFixtures.artifact(StemLexiconTestFixtures.defaultEntries(8))
        val identity = fixture.raw.copyOf()
        val sha = ByteArray(32) {
            dictionarySha256.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        sha.copyInto(identity, TatStemFormat.DICTIONARY_SHA_OFFSET)
        val raw = StemLexiconTestFixtures.refreshEmbeddedChecksum(identity)
        val compressed = DictionaryTestFixtures.compress(raw)
        val spec = StemLexiconTestFixtures.spec(raw, compressed).copy(
            expectedDictionaryRawSha256 = dictionarySha256,
        )
        return TatarMorphologicalAcceptor.open(ByteArrayInputStream(compressed), spec)
    }

    private class SingleLeaseCatalog(
        private var lease: DictionaryFileLease?,
    ) : PublishedDictionaryCatalog {
        override fun acquireLatestForActivation(): DictionaryFileLease? = lease.also { lease = null }

        override fun cleanupReleasedVersions() = Unit
    }

    private fun utf8(value: String) = value.toByteArray(Charsets.UTF_8)
}
