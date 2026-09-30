package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicDictionaryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun firstRunFsyncsValidatesAndAtomicallyPublishesWithoutActivating() {
        val artifact = DictionaryTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("first-run")
        val operations = TestDurableFileOps()
        val harness = harness(directory, listOf(artifact), operations = operations)

        val result = harness.store.ensurePublished(artifact.spec)

        assertPublished(result, generation = 1, alreadyPresent = false)
        assertTrue(File(directory, artifact.spec.finalFileName).isFile)
        assertEquals(1, harness.assets.opens)
        assertTrue(operations.events.indexOf("file-fsync") < operations.events.indexOf("rename"))
        assertTrue(operations.events.indexOf("rename") < operations.events.lastIndexOf("directory-fsync"))
        assertEquals(1, managedFinals(directory).size)
    }

    @Test
    fun activationRightAfterPublicationReusesThePublicationValidation() {
        val artifact = DictionaryTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("reuse-new")
        val harness = harness(directory, listOf(artifact))
        assertPublished(harness.store.ensurePublished(artifact.spec), 1, false)
        assertEquals(1, harness.store.validationCount)

        val lease = requireNotNull(harness.store.acquireLatestForActivation())

        assertEquals(1, harness.store.validationCount)
        assertEquals(artifact.raw.size.toLong(), lease.dictionary.rawSize)
        assertEquals(DictionaryTestFixtures.sha256(artifact.raw), lease.dictionary.rawSha256)
        lease.close()
    }

    @Test
    fun activationAfterAnExistingFileCheckReusesThatValidation() {
        val artifact = DictionaryTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("reuse-existing")
        assertPublished(harness(directory, listOf(artifact)).store.ensurePublished(artifact.spec), 1, false)
        val harness = harness(directory, listOf(artifact))
        assertPublished(harness.store.ensurePublished(artifact.spec), 1, true)

        requireNotNull(harness.store.acquireLatestForActivation()).close()

        assertEquals(1, harness.store.validationCount)
    }

    @Test
    fun everyLaterActivationValidatesAgain() {
        val artifact = DictionaryTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("reuse-once")
        val harness = harness(directory, listOf(artifact))
        assertPublished(harness.store.ensurePublished(artifact.spec), 1, false)
        requireNotNull(harness.store.acquireLatestForActivation()).close()

        requireNotNull(harness.store.acquireLatestForActivation()).close()

        assertEquals(2, harness.store.validationCount)
    }

    @Test
    fun aFileWithAnotherLengthAfterPublicationIsValidatedAndRejected() {
        val artifact = DictionaryTestFixtures.artifact()
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
        val artifact = DictionaryTestFixtures.artifact()
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
        val artifact = DictionaryTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("rerun")
        val harness = harness(directory, listOf(artifact))
        assertPublished(harness.store.ensurePublished(artifact.spec), 1, false)
        val final = File(directory, artifact.spec.finalFileName)
        val bytes = final.readBytes()
        val timestamp = final.lastModified()
        harness.assets.opens = 0

        val result = harness.store.ensurePublished(artifact.spec)

        assertPublished(result, 1, true)
        assertEquals(0, harness.assets.opens)
        assertTrue(bytes.contentEquals(final.readBytes()))
        assertEquals(timestamp, final.lastModified())
    }

    @Test
    fun updateStaysStagedUntilOldLeaseClosesAtSafeLifecycleBoundary() {
        val v1 = DictionaryTestFixtures.artifact(1)
        val v2 = DictionaryTestFixtures.artifact(2, listOf("аб" to 31, "аба" to 20, "әби" to 10))
        val directory = temporaryFolder.newFolder("update")
        val operations = TestDurableFileOps()
        val harness = harness(directory, listOf(v1, v2), operations = operations)
        assertPublished(harness.store.ensurePublished(v1.spec), 1, false)
        val activeV1 = requireNotNull(harness.store.acquireLatestForActivation())

        assertPublished(harness.store.ensurePublished(v2.spec), 2, false)

        assertEquals(1, activeV1.dictionary.generation)
        assertTrue(activeV1.dictionary.file.exists())
        assertEquals(2, managedFinals(directory).size)
        assertNull(harness.store.acquireLatestForActivation())

        activeV1.close()
        val activatedV2 = requireNotNull(harness.store.acquireLatestForActivation())
        assertEquals(2, activatedV2.dictionary.generation)
        activatedV2.close()
    }

    @Test
    fun retentionNeverDeletesLeasedFileAndNeverExceedsTwoFinals() {
        val v1 = DictionaryTestFixtures.artifact(1)
        val v2 = DictionaryTestFixtures.artifact(2, listOf("аб" to 31, "аба" to 20, "әби" to 10))
        val v3 = DictionaryTestFixtures.artifact(3, listOf("аб" to 32, "аба" to 20, "әби" to 10))
        val directory = temporaryFolder.newFolder("retention")
        val operations = TestDurableFileOps()
        val harness = harness(directory, listOf(v1, v2, v3), operations = operations)
        assertPublished(harness.store.ensurePublished(v1.spec), 1, false)
        val v1Lease = requireNotNull(harness.store.acquireLatestForActivation())
        assertPublished(harness.store.ensurePublished(v2.spec), 2, false)

        assertPublished(harness.store.ensurePublished(v3.spec), 3, false)

        assertEquals(2, managedFinals(directory).size)
        assertTrue(v1Lease.dictionary.file.exists())
        assertFalse(File(directory, v2.spec.finalFileName).exists())
        assertTrue(File(directory, v3.spec.finalFileName).exists())
        assertNull(harness.store.acquireLatestForActivation())

        v1Lease.close()
        val v3Lease = requireNotNull(harness.store.acquireLatestForActivation())
        assertEquals(3, v3Lease.dictionary.generation)
        v3Lease.close()
        assertFalse(operations.deleted.any { it.name == v1.spec.finalFileName })
    }

    @Test
    fun corruptUpdateIsRejectedAndPriorActiveFileIsKept() {
        val v1 = DictionaryTestFixtures.artifact(1)
        val v2 = DictionaryTestFixtures.artifact(2, listOf("аб" to 31, "аба" to 20, "әби" to 10))
        val directory = temporaryFolder.newFolder("corruption")
        val harness = harness(directory, listOf(v1, v2))
        assertPublished(harness.store.ensurePublished(v1.spec), 1, false)
        val active = requireNotNull(harness.store.acquireLatestForActivation())
        harness.assetBytes[2] = v2.compressed.copyOf().also {
            it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 1).toByte()
        }

        val result = harness.store.ensurePublished(v2.spec)

        assertEquals(PreparationResult.Unavailable(StorageFailure.INVALID_ASSET), result)
        assertTrue(active.dictionary.file.exists())
        assertFalse(File(directory, v2.spec.finalFileName).exists())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        active.close()
    }

    @Test
    fun crashBeforeRenameLeavesOnlyTempAndRestartDiscardsIt() {
        val artifact = DictionaryTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("torn-first")
        val crashingOps = object : TestDurableFileOps() {
            override fun atomicRename(source: File, destination: File) {
                throw SimulatedCrash()
            }
        }
        val crashed = harness(directory, listOf(artifact), operations = crashingOps)
        try {
            crashed.store.ensurePublished(artifact.spec)
            fail("expected simulated process death")
        } catch (expected: SimulatedCrash) {
            // Error intentionally bypasses normal cleanup, like process death.
        }
        assertFalse(File(directory, artifact.spec.finalFileName).exists())
        assertEquals(1, directory.listFiles().orEmpty().count { it.name.endsWith(".tmp") })

        val restarted = harness(directory, listOf(artifact))
        assertPublished(restarted.store.ensurePublished(artifact.spec), 1, false)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        assertEquals(1, managedFinals(directory).size)
    }

    @Test
    fun crashBeforeRenameNeverDamagesPriorFinal() {
        val v1 = DictionaryTestFixtures.artifact(1)
        val v2 = DictionaryTestFixtures.artifact(2, listOf("аб" to 31, "аба" to 20, "әби" to 10))
        val directory = temporaryFolder.newFolder("torn-update")
        val initial = harness(directory, listOf(v1, v2))
        assertPublished(initial.store.ensurePublished(v1.spec), 1, false)
        val priorBytes = File(directory, v1.spec.finalFileName).readBytes()
        val crashing = harness(
            directory,
            listOf(v1, v2),
            operations = object : TestDurableFileOps() {
                override fun atomicRename(source: File, destination: File) {
                    throw SimulatedCrash()
                }
            },
        )

        try {
            crashing.store.ensurePublished(v2.spec)
            fail("expected simulated process death")
        } catch (expected: SimulatedCrash) {
            // Expected.
        }

        assertTrue(priorBytes.contentEquals(File(directory, v1.spec.finalFileName).readBytes()))
        assertFalse(File(directory, v2.spec.finalFileName).exists())
    }

    @Test
    fun directoryFsyncFailureAfterRenameReportsUnavailableButLeavesOnlyValidFinals() {
        val v1 = DictionaryTestFixtures.artifact(1)
        val v2 = DictionaryTestFixtures.artifact(2, listOf("аб" to 31, "аба" to 20, "әби" to 10))
        val directory = temporaryFolder.newFolder("post-rename-fsync")
        val initial = harness(directory, listOf(v1, v2))
        assertPublished(initial.store.ensurePublished(v1.spec), 1, false)
        val operations = object : TestDurableFileOps() {
            private var sawRename = false

            override fun atomicRename(source: File, destination: File) {
                super.atomicRename(source, destination)
                sawRename = true
            }

            override fun syncDirectory(directory: File) {
                if (sawRename) throw IOException("directory fsync failed")
                super.syncDirectory(directory)
            }
        }
        val updating = harness(directory, listOf(v1, v2), operations = operations)

        val result = updating.store.ensurePublished(v2.spec)

        assertEquals(PreparationResult.Unavailable(StorageFailure.IO), result)
        assertTrue(File(directory, v1.spec.finalFileName).isFile)
        val v2File = File(directory, v2.spec.finalFileName)
        assertTrue(v2File.isFile)
        assertEquals(2, managedFinals(directory).size)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        assertEquals(3, TdictValidator().validateRaw(v2File, v2.spec).entryCount)
    }

    @Test
    fun noFreeSpaceFailsBeforeAssetOpenAndLeavesInputIndependent() {
        val artifact = DictionaryTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("no-space")
        val harness = harness(directory, listOf(artifact), usableBytes = 0)
        var normalInputCalls = 0

        val result = harness.store.ensurePublished(artifact.spec)
        normalInputCalls++

        assertEquals(PreparationResult.Unavailable(StorageFailure.NO_SPACE), result)
        assertEquals(0, harness.assets.opens)
        assertEquals(1, normalInputCalls)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun writeEnospcFailsCleanlyAndKeepsPriorVersion() {
        val v1 = DictionaryTestFixtures.artifact(1)
        val v2 = DictionaryTestFixtures.artifact(2, listOf("аб" to 31, "аба" to 20, "әби" to 10))
        val directory = temporaryFolder.newFolder("write-enospc")
        val initial = harness(directory, listOf(v1, v2))
        assertPublished(initial.store.ensurePublished(v1.spec), 1, false)
        val provider = AssetInputProvider { spec ->
            if (spec.generation == 2) throw IOException("ENOSPC: no space left on device")
            v1.compressed.inputStream()
        }
        val failingStore = store(
            directory,
            listOf(v1, v2),
            provider,
            TestDurableFileOps(),
            Long.MAX_VALUE,
        )

        val result = failingStore.ensurePublished(v2.spec)

        assertEquals(PreparationResult.Unavailable(StorageFailure.NO_SPACE), result)
        assertTrue(File(directory, v1.spec.finalFileName).isFile)
        assertFalse(File(directory, v2.spec.finalFileName).exists())
    }

    @Test
    fun tempCreationEnospcIsClassifiedAsNoSpaceBeforeAssetOpen() {
        val artifact = DictionaryTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("temp-create-enospc")
        val operations = object : TestDurableFileOps() {
            override fun createNewFile(file: File): Boolean {
                throw IOException("ENOSPC: no space left while creating temp")
            }
        }
        val harness = harness(directory, listOf(artifact), operations = operations)

        val result = harness.store.ensurePublished(artifact.spec)

        assertEquals(PreparationResult.Unavailable(StorageFailure.NO_SPACE), result)
        assertEquals(0, harness.assets.opens)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun deviceProtectedDirectorySeamNeverUsesCredentialProtectedDirectory() {
        val artifact = DictionaryTestFixtures.artifact()
        val credential = temporaryFolder.newFolder("credential-protected")
        val device = temporaryFolder.newFolder("device-protected")
        val harness = harness(device, listOf(artifact))

        assertPublished(harness.store.ensurePublished(artifact.spec), 1, false)

        assertTrue(File(device, artifact.spec.finalFileName).isFile)
        assertTrue(credential.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun corruptLeasedCurrentIsNeverDeleted() {
        val artifact = DictionaryTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("leased-corruption")
        val operations = TestDurableFileOps()
        val harness = harness(directory, listOf(artifact), operations = operations)
        assertPublished(harness.store.ensurePublished(artifact.spec), 1, false)
        val lease = requireNotNull(harness.store.acquireLatestForActivation())
        lease.dictionary.file.writeBytes(byteArrayOf(1, 2, 3))

        val result = harness.store.ensurePublished(artifact.spec)

        assertEquals(PreparationResult.Unavailable(StorageFailure.INVALID_ASSET), result)
        assertTrue(lease.dictionary.file.exists())
        assertFalse(operations.deleted.any { it.name == artifact.spec.finalFileName })
        lease.close()
    }

    @Test
    fun separateStoresShareLeaseStateAndBlockNewVersionUntilEveryReaderCloses() {
        val v1 = DictionaryTestFixtures.artifact(1)
        val v2 = DictionaryTestFixtures.artifact(2, listOf("аб" to 31, "аба" to 20, "әби" to 10))
        val directory = temporaryFolder.newFolder("shared-leases")
        val first = harness(directory, listOf(v1, v2))
        val second = harness(directory, listOf(v1, v2))
        assertPublished(first.store.ensurePublished(v1.spec), 1, false)
        val firstReader = requireNotNull(first.store.acquireLatestForActivation())
        val secondReader = requireNotNull(second.store.acquireLatestForActivation())

        assertPublished(second.store.ensurePublished(v2.spec), 2, false)
        assertNull(first.store.acquireLatestForActivation())
        firstReader.close()
        assertNull(second.store.acquireLatestForActivation())
        assertTrue(secondReader.dictionary.file.exists())

        secondReader.close()
        val activated = requireNotNull(first.store.acquireLatestForActivation())
        assertEquals(2, activated.dictionary.generation)
        activated.close()
    }

    @Test
    fun separateStoreCannotDeleteCorruptFileLeasedByFirstStore() {
        val artifact = DictionaryTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("shared-corrupt-lease")
        val first = harness(directory, listOf(artifact))
        val secondOperations = TestDurableFileOps()
        val second = harness(directory, listOf(artifact), operations = secondOperations)
        assertPublished(first.store.ensurePublished(artifact.spec), 1, false)
        val lease = requireNotNull(first.store.acquireLatestForActivation())
        lease.dictionary.file.writeBytes(byteArrayOf(1, 2, 3))

        val result = second.store.ensurePublished(artifact.spec)

        assertEquals(PreparationResult.Unavailable(StorageFailure.INVALID_ASSET), result)
        assertTrue(lease.dictionary.file.exists())
        assertFalse(secondOperations.deleted.any { it.name == artifact.spec.finalFileName })
        lease.close()
    }

    @Test
    fun separateStoresSerializePublicationAndNeverCleanAnInFlightTemp() {
        val artifact = DictionaryTestFixtures.artifact()
        val directory = temporaryFolder.newFolder("shared-publication")
        val firstAssetOpened = CountDownLatch(1)
        val releaseFirstAsset = CountDownLatch(1)
        val secondSpaceProbeReached = CountDownLatch(1)
        val firstProvider = AssetInputProvider {
            firstAssetOpened.countDown()
            check(releaseFirstAsset.await(5, TimeUnit.SECONDS))
            artifact.compressed.inputStream()
        }
        val secondProvider = CountingAssetProvider(mutableMapOf(1 to artifact.compressed))
        val firstOperations = TestDurableFileOps()
        val secondOperations = TestDurableFileOps()
        val firstStore = store(
            directory,
            listOf(artifact),
            firstProvider,
            firstOperations,
            Long.MAX_VALUE,
        )
        val secondStore = AtomicDictionaryStore(
            directoryProvider = DeviceProtectedDirectoryProvider { directory },
            assetInputProvider = secondProvider,
            clock = StorageClock { 1234L },
            spaceProbe = SpaceProbe {
                secondSpaceProbeReached.countDown()
                Long.MAX_VALUE
            },
            fileOps = secondOperations,
            supportedArtifacts = listOf(artifact.spec),
        )
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<PreparationResult> {
                firstStore.ensurePublished(artifact.spec)
            }
            assertTrue(firstAssetOpened.await(5, TimeUnit.SECONDS))
            val second = workers.submit<PreparationResult> {
                secondStore.ensurePublished(artifact.spec)
            }

            assertFalse(secondSpaceProbeReached.await(200, TimeUnit.MILLISECONDS))
            assertEquals(1, directory.listFiles().orEmpty().count { it.name.endsWith(".tmp") })
            assertTrue(secondOperations.deleted.isEmpty())

            releaseFirstAsset.countDown()
            assertPublished(first.get(5, TimeUnit.SECONDS), 1, false)
            assertPublished(second.get(5, TimeUnit.SECONDS), 1, true)
            assertEquals(0, secondProvider.opens)
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            releaseFirstAsset.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun noSpaceInSeparateStoreDropsOnlyStagedVersionAndKeepsSharedActiveLease() {
        val v1 = DictionaryTestFixtures.artifact(1)
        val v2 = DictionaryTestFixtures.artifact(2, listOf("аб" to 31, "аба" to 20, "әби" to 10))
        val v3 = DictionaryTestFixtures.artifact(3, listOf("аб" to 32, "аба" to 20, "әби" to 10))
        val directory = temporaryFolder.newFolder("shared-no-space")
        val first = harness(directory, listOf(v1, v2, v3))
        assertPublished(first.store.ensurePublished(v1.spec), 1, false)
        val active = requireNotNull(first.store.acquireLatestForActivation())
        assertPublished(first.store.ensurePublished(v2.spec), 2, false)
        val second = harness(directory, listOf(v1, v2, v3), usableBytes = 0)

        val result = second.store.ensurePublished(v3.spec)

        assertEquals(PreparationResult.Unavailable(StorageFailure.NO_SPACE), result)
        assertTrue(active.dictionary.file.exists())
        assertFalse(File(directory, v2.spec.finalFileName).exists())
        assertFalse(File(directory, v3.spec.finalFileName).exists())
        assertEquals(1, managedFinals(directory).size)
        active.close()
    }

    @Test
    fun checkedPlatformFailureBeforePublicationReturnsUnavailable() {
        val artifact = DictionaryTestFixtures.artifact()
        val root = temporaryFolder.newFolder("platform-io")
        val directory = File(root, "new-dictionary-directory")
        val operations = object : TestDurableFileOps() {
            override fun syncDirectory(directory: File) {
                throw SyntheticPlatformIoException()
            }
        }
        val harness = harness(directory, listOf(artifact), operations = operations)

        val result = harness.store.ensurePublished(artifact.spec)

        assertEquals(PreparationResult.Unavailable(StorageFailure.IO), result)
        assertEquals(0, harness.assets.opens)
        assertFalse(File(directory, artifact.spec.finalFileName).exists())
    }

    @Test
    fun checkedDirectoryProviderFailureIsFailClosedAtEveryPublicBoundary() {
        val artifact = DictionaryTestFixtures.artifact()
        val store = AtomicDictionaryStore(
            directoryProvider = DeviceProtectedDirectoryProvider {
                throw SyntheticPlatformIoException()
            },
            assetInputProvider = AssetInputProvider { artifact.compressed.inputStream() },
            clock = StorageClock { 1234L },
            spaceProbe = SpaceProbe { Long.MAX_VALUE },
            fileOps = TestDurableFileOps(),
            supportedArtifacts = listOf(artifact.spec),
        )

        assertEquals(
            PreparationResult.Unavailable(StorageFailure.IO),
            store.ensurePublished(artifact.spec),
        )
        assertNull(store.acquireLatestForActivation())
        store.cleanupReleasedVersions()
    }

    private fun harness(
        directory: File,
        artifacts: List<TestArtifact>,
        operations: TestDurableFileOps = TestDurableFileOps(),
        usableBytes: Long = Long.MAX_VALUE,
    ): Harness {
        val bytes = artifacts.associate { it.spec.generation to it.compressed }.toMutableMap()
        val provider = CountingAssetProvider(bytes)
        return Harness(
            store(directory, artifacts, provider, operations, usableBytes),
            provider,
            bytes,
        )
    }

    private fun store(
        directory: File,
        artifacts: List<TestArtifact>,
        provider: AssetInputProvider,
        operations: DurableFileOps,
        usableBytes: Long,
    ) = AtomicDictionaryStore(
        directoryProvider = DeviceProtectedDirectoryProvider { directory },
        assetInputProvider = provider,
        clock = StorageClock { 1234L },
        spaceProbe = SpaceProbe { usableBytes },
        fileOps = operations,
        supportedArtifacts = artifacts.map { it.spec },
    )

    private fun assertPublished(
        result: PreparationResult,
        generation: Int,
        alreadyPresent: Boolean,
    ) {
        assertTrue(result is PreparationResult.Published)
        result as PreparationResult.Published
        assertEquals(generation, result.dictionary.generation)
        assertEquals(alreadyPresent, result.alreadyPresent)
    }

    private fun managedFinals(directory: File): List<File> =
        directory.listFiles().orEmpty().filter { it.name.endsWith(".tdict") }

    private data class Harness(
        val store: AtomicDictionaryStore,
        val assets: CountingAssetProvider,
        val assetBytes: MutableMap<Int, ByteArray>,
    )

    private class SimulatedCrash : Error()

    private class SyntheticPlatformIoException : Exception()
}
