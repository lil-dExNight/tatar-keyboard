package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicBigramStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun firstRunFsyncsValidatesAndAtomicallyPublishesWithoutActivating() {
        val artifact = BigramTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("first-run")
        val operations = TestDurableFileOps()
        val harness = harness(directory, listOf(artifact), operations = operations)

        val result = harness.store.ensurePublished(artifact.spec)

        assertPublished(result, generation = 1, alreadyPresent = false)
        assertTrue(File(directory, artifact.spec.finalFileName).isFile)
        assertEquals(1, harness.assets.opens)
        assertTrue(operations.events.indexOf("file-fsync") < operations.events.indexOf("rename"))
        assertEquals(1, managedFinals(directory).size)
    }

    @Test
    fun activationRightAfterPublicationReusesThePublicationValidation() {
        val artifact = BigramTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("reuse-new")
        val harness = harness(directory, listOf(artifact))
        assertPublished(harness.store.ensurePublished(artifact.spec), 1, false)
        assertEquals(1, harness.store.validationCount)

        val lease = requireNotNull(harness.store.acquireLatestForActivation())

        assertEquals(1, harness.store.validationCount)
        assertEquals(artifact.raw.size.toLong(), lease.table.rawSize)
        assertEquals(BigramTestFixtures.sha256(artifact.raw), lease.table.rawSha256)
        lease.close()
    }

    @Test
    fun activationAfterAnExistingFileCheckReusesThatValidation() {
        val artifact = BigramTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("reuse-existing")
        assertPublished(harness(directory, listOf(artifact)).store.ensurePublished(artifact.spec), 1, false)
        val harness = harness(directory, listOf(artifact))
        assertPublished(harness.store.ensurePublished(artifact.spec), 1, true)

        requireNotNull(harness.store.acquireLatestForActivation()).close()

        assertEquals(1, harness.store.validationCount)
    }

    @Test
    fun everyLaterActivationValidatesAgain() {
        val artifact = BigramTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("reuse-once")
        val harness = harness(directory, listOf(artifact))
        assertPublished(harness.store.ensurePublished(artifact.spec), 1, false)
        requireNotNull(harness.store.acquireLatestForActivation()).close()

        requireNotNull(harness.store.acquireLatestForActivation()).close()

        assertEquals(2, harness.store.validationCount)
    }

    @Test
    fun aFileWithAnotherLengthAfterPublicationIsValidatedAndRejected() {
        val artifact = BigramTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("reuse-length")
        val harness = harness(directory, listOf(artifact))
        assertPublished(harness.store.ensurePublished(artifact.spec), 1, false)
        val final = File(directory, artifact.spec.finalFileName)
        final.writeBytes(artifact.raw + byteArrayOf(0))

        assertEquals(null, harness.store.acquireLatestForActivation())
        assertEquals(2, harness.store.validationCount)
    }

    @Test
    fun aSameLengthRewriteWithANewTimeIsValidatedAndRejected() {
        val artifact = BigramTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("reuse-time")
        val harness = harness(directory, listOf(artifact))
        assertPublished(harness.store.ensurePublished(artifact.spec), 1, false)
        val final = File(directory, artifact.spec.finalFileName)
        val published = final.lastModified()
        val corrupt = artifact.raw.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        final.writeBytes(corrupt)
        assertTrue(final.setLastModified(published + 2_000))

        assertEquals(null, harness.store.acquireLatestForActivation())
        assertEquals(2, harness.store.validationCount)
    }

    @Test
    fun rerunValidatesExistingFinalWithoutOpeningOrRewritingAsset() {
        val artifact = BigramTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("rerun")
        val harness = harness(directory, listOf(artifact))
        assertPublished(harness.store.ensurePublished(artifact.spec), 1, false)
        val final = File(directory, artifact.spec.finalFileName)
        val bytes = final.readBytes()
        harness.assets.opens = 0

        val result = harness.store.ensurePublished(artifact.spec)

        assertPublished(result, 1, true)
        assertEquals(0, harness.assets.opens)
        assertTrue(bytes.contentEquals(final.readBytes()))
    }

    @Test
    fun retentionNeverDeletesLeasedFileAndNeverExceedsTwoFinals() {
        val v1 = BigramTestFixtures.artifact(1)
        val v2 = BigramTestFixtures.artifact(2, headsToSuccesses = listOf("аб" to listOf("аба", "юл")))
        val v3 = BigramTestFixtures.artifact(3, headsToSuccesses = listOf("аб" to listOf("юл")))
        val directory = temporaryFolder.newFolder("retention")
        val harness = harness(directory, listOf(v1, v2, v3))
        assertPublished(harness.store.ensurePublished(v1.spec), 1, false)
        val v1Lease = requireNotNull(harness.store.acquireLatestForActivation())
        assertPublished(harness.store.ensurePublished(v2.spec), 2, false)

        assertPublished(harness.store.ensurePublished(v3.spec), 3, false)

        assertEquals(2, managedFinals(directory).size)
        assertTrue(v1Lease.table.file.exists())
        assertFalse(File(directory, v2.spec.finalFileName).exists())
        assertTrue(File(directory, v3.spec.finalFileName).exists())

        v1Lease.close()
        val v3Lease = requireNotNull(harness.store.acquireLatestForActivation())
        assertEquals(3, v3Lease.table.generation)
        v3Lease.close()
    }

    @Test
    fun corruptUpdateIsRejectedAndPriorActiveFileIsKept() {
        val v1 = BigramTestFixtures.artifact(1)
        val v2 = BigramTestFixtures.artifact(2, headsToSuccesses = listOf("аб" to listOf("аба", "юл")))
        val directory = temporaryFolder.newFolder("corruption")
        val harness = harness(directory, listOf(v1, v2))
        assertPublished(harness.store.ensurePublished(v1.spec), 1, false)
        val active = requireNotNull(harness.store.acquireLatestForActivation())
        harness.assetBytes[2] = v2.compressed.copyOf().also {
            it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 1).toByte()
        }

        val result = harness.store.ensurePublished(v2.spec)

        assertEquals(BigramPreparationResult.Unavailable(StorageFailure.INVALID_ASSET), result)
        assertTrue(active.table.file.exists())
        assertFalse(File(directory, v2.spec.finalFileName).exists())
        active.close()
    }

    @Test
    fun finalFileNamingIsOwnPatternDistinctFromDictionaryStore() {
        val bigram = BigramTestFixtures.artifact()
        val dictionary = DictionaryTestFixtures.artifact()

        assertTrue(bigram.spec.finalFileName.endsWith(".tatbigr"))
        assertTrue(bigram.spec.finalFileName.startsWith("tatar_bigrams-tt-"))
        assertTrue(dictionary.spec.finalFileName.endsWith(".tdict"))
        assertFalse(bigram.spec.finalFileName == dictionary.spec.finalFileName)
    }

    @Test
    fun sharingADirectoryWithAForeignDictionaryFinalDoesNotTouchIt() {
        // Not the real deployment layout (the two artifacts get separate subdirectories): this
        // proves the bigram store's own file pattern and retention leave an unrelated .tdict
        // file alone even if one ended up in the same directory.
        val bigram = BigramTestFixtures.artifact()
        val foreignDictionary = DictionaryTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("shared-directory")
        File(directory, foreignDictionary.spec.finalFileName).writeBytes(foreignDictionary.raw)
        val harness = harness(directory, listOf(bigram))

        assertPublished(harness.store.ensurePublished(bigram.spec), 1, false)

        assertTrue(File(directory, foreignDictionary.spec.finalFileName).isFile)
        assertTrue(
            foreignDictionary.raw.contentEquals(
                File(directory, foreignDictionary.spec.finalFileName).readBytes(),
            ),
        )
    }

    @Test
    fun aLiveDictionaryLeaseNeverBlocksOrIsBlockedByABigramLease() {
        // Each artifact lives in its own device-protected subdirectory, and a live lease on the
        // main dictionary must not block bigram activation or vice versa. The two stores below
        // use DIFFERENT directories (ProcessDictionaryStorageOwner / ProcessBigramStorageOwner
        // key their shared state by canonical directory path, and the registries are separate
        // objects), so this exercises the deployment layout, not just type separation.
        val dictionaryDirectory = temporaryFolder.newFolder("dictionaries")
        val bigramDirectory = temporaryFolder.newFolder("bigrams")
        val dictionaryArtifact = DictionaryTestFixtures.artifact()
        val bigramArtifact = BigramTestFixtures.artifact()

        val dictionaryStore = AtomicDictionaryStore(
            directoryProvider = DeviceProtectedDirectoryProvider { dictionaryDirectory },
            assetInputProvider = CountingAssetProvider(
                mutableMapOf(dictionaryArtifact.spec.generation to dictionaryArtifact.compressed),
            ),
            clock = StorageClock { 1234L },
            spaceProbe = SpaceProbe { Long.MAX_VALUE },
            fileOps = TestDurableFileOps(),
            supportedArtifacts = listOf(dictionaryArtifact.spec),
        )
        val bigramStore = bigramStore(bigramDirectory, listOf(bigramArtifact))

        assertTrue(dictionaryStore.ensurePublished(dictionaryArtifact.spec) is PreparationResult.Published)
        assertTrue(bigramStore.ensurePublished(bigramArtifact.spec) is BigramPreparationResult.Published)
        val dictionaryLease = requireNotNull(dictionaryStore.acquireLatestForActivation())
        val bigramLease = requireNotNull(bigramStore.acquireLatestForActivation())

        // Each catalog activates independently of whether the OTHER kind of artifact has a live
        // lease at all — neither store's retention/activation logic ever inspects the other's
        // directory or shared state.
        assertEquals(1, dictionaryLease.dictionary.generation)
        assertEquals(1, bigramLease.table.generation)
        assertTrue(dictionaryLease.dictionary.file.exists())
        assertTrue(bigramLease.table.file.exists())

        dictionaryLease.close()
        bigramLease.close()
    }

    private fun harness(
        directory: File,
        artifacts: List<TestBigramArtifact>,
        operations: TestDurableFileOps = TestDurableFileOps(),
        usableBytes: Long = Long.MAX_VALUE,
    ): Harness {
        val bytes = artifacts.associate { it.spec.generation to it.compressed }.toMutableMap()
        val provider = CountingBigramAssetProvider(bytes)
        return Harness(
            AtomicBigramStore(
                directoryProvider = DeviceProtectedDirectoryProvider { directory },
                assetInputProvider = provider,
                clock = StorageClock { 1234L },
                spaceProbe = SpaceProbe { usableBytes },
                fileOps = operations,
                supportedArtifacts = artifacts.map { it.spec },
            ),
            provider,
            bytes,
        )
    }

    private fun bigramStore(directory: File, artifacts: List<TestBigramArtifact>): AtomicBigramStore =
        AtomicBigramStore(
            directoryProvider = DeviceProtectedDirectoryProvider { directory },
            assetInputProvider = CountingBigramAssetProvider(
                artifacts.associate { it.spec.generation to it.compressed }.toMutableMap(),
            ),
            clock = StorageClock { 1234L },
            spaceProbe = SpaceProbe { Long.MAX_VALUE },
            fileOps = TestDurableFileOps(),
            supportedArtifacts = artifacts.map { it.spec },
        )

    private fun assertPublished(
        result: BigramPreparationResult,
        generation: Int,
        alreadyPresent: Boolean,
    ) {
        assertTrue(result is BigramPreparationResult.Published)
        result as BigramPreparationResult.Published
        assertEquals(generation, result.table.generation)
        assertEquals(alreadyPresent, result.alreadyPresent)
    }

    private fun managedFinals(directory: File): List<File> =
        directory.listFiles().orEmpty().filter { it.name.endsWith(".tatbigr") }

    private data class Harness(
        val store: AtomicBigramStore,
        val assets: CountingBigramAssetProvider,
        val assetBytes: MutableMap<Int, ByteArray>,
    )
}
