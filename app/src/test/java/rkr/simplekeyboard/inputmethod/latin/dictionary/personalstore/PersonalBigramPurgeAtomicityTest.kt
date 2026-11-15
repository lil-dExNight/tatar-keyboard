/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/**
 * The bigram twin of [PersonalDictionaryPurgeAtomicityTest]: the quarantine half of `forget` in
 * [PersonalBigramStore] fails closed over an unread copy, never swallows a post-delete/replace
 * fsync failure, and keeps memory consistent with the disk when the active rollback itself fails.
 * Pairs on disk are ordered by context, so the cut tail always holds (сүз, дөст). Plain JVM.
 */
class PersonalBigramPurgeAtomicityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    @Test
    fun forgettingOverATruncatedCopyFailsClosedAndLeavesEveryByte() {
        val directory = quarantinedDirectory(damage = { truncate(it, dropTailBytes = 4) })
        val store = store(directory)
        store.restoreQuarantine()
        assertEquals(2, store.snapshot.size)
        val activeBytes = destinationFile(directory).readBytes()
        val quarantineBytes = quarantineFile(directory).readBytes()

        var outcome: Boolean? = null
        store.forget("ал", "бар") { outcome = it }

        assertEquals(false, outcome)
        assertEquals(2, store.snapshot.size)
        assertEquals(listOf("ал" to "бар", "кит" to "кал"), pairsOnDisk(directory))
        assertArrayEquals(activeBytes, destinationFile(directory).readBytes())
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
    }

    @Test
    fun aPairOnlyInTheUnreadTailIsNotPurged() {
        val directory = quarantinedDirectory(damage = { truncate(it, dropTailBytes = 4) })
        val store = store(directory)
        store.restoreQuarantine()
        val salvage = PersonalBigramQuarantineSalvage.read(quarantineFile(directory), subtype)
        assertNotNull(salvage)
        assertFalse("the harness must really leave an unread tail", salvage!!.readToEnd)
        assertEquals("the cut pair must not be in the readable head",
            listOf("ал", "кит"), salvage.contexts)
        val activeBytes = destinationFile(directory).readBytes()
        val quarantineBytes = quarantineFile(directory).readBytes()

        var outcome: Boolean? = null
        store.forget("сүз", "дөст") { outcome = it }

        assertEquals(false, outcome)
        assertEquals(2, store.snapshot.size)
        assertArrayEquals(activeBytes, destinationFile(directory).readBytes())
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
    }

    @Test
    fun forgettingAPairAbsentFromATruncatedHeadStillFailsClosed() {
        val directory = quarantinedDirectory(damage = { truncate(it, dropTailBytes = 20) })
        val store = store(directory)
        store.prime()
        assertTrue(store.snapshot.isEmpty)
        val quarantineBytes = quarantineFile(directory).readBytes()

        var outcome: Boolean? = null
        store.forget("сүз", "дөст") { outcome = it }

        assertEquals(false, outcome)
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
        assertFalse(destinationFile(directory).exists())
    }

    @Test
    fun aThrownQuarantineDeleteFailsClosedAndLeavesBothFilesUntouched() {
        // The copy holds exactly the pair to forget, so the purge takes the delete path.
        val directory = quarantinedDirectoryWithOnePairCopy()
        val store = store(directory)
        store.restoreQuarantine()
        assertEquals(1, store.snapshot.size)
        val activeBytes = destinationFile(directory).readBytes()
        val quarantineBytes = quarantineFile(directory).readBytes()

        val ops = object : PassthroughOps() {
            override fun delete(file: File): Boolean =
                if (file.name.endsWith(QUARANTINE_SUFFIX)) throw IOException("delete threw")
                else super.delete(file)
        }
        var outcome: Boolean? = null
        store(directory, ops = ops).forget("ал", "бар") { outcome = it }

        assertEquals(false, outcome)
        assertEquals(1, store.snapshot.size)
        assertEquals(listOf("ал" to "бар"), pairsOnDisk(directory))
        assertArrayEquals(activeBytes, destinationFile(directory).readBytes())
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
    }

    @Test
    fun aDeleteFollowedByAFsyncFailureIsTrueBecauseTheSlotIsGone() {
        val directory = quarantinedDirectoryWithOnePairCopy()
        val store = store(directory)
        store.restoreQuarantine()
        assertEquals(1, store.snapshot.size)

        var quarantineDeleted = false
        val ops = object : PassthroughOps() {
            override fun delete(file: File): Boolean {
                val removed = super.delete(file)
                if (removed && file.name.endsWith(QUARANTINE_SUFFIX)) quarantineDeleted = true
                return removed
            }

            override fun syncDirectory(dir: File) {
                if (quarantineDeleted) throw IOException("dir fsync failed")
            }
        }
        val faulted = store(directory, ops = ops)
        faulted.prime()

        var outcome: Boolean? = null
        faulted.forget("ал", "бар") { outcome = it }

        assertEquals("the empty slot is the proof", true, outcome)
        assertFalse(quarantineFile(directory).exists())
        assertFalse(destinationFile(directory).exists())
        assertTrue(faulted.snapshot.isEmpty)
    }

    @Test
    fun aReplaceFollowedByAFsyncFailureIsTrueWhenTheSlotHoldsThePurgedCopy() {
        val directory = quarantinedDirectory(damage = ::flipChecksum)
        val store = store(directory)
        store.restoreQuarantine()
        assertEquals(3, store.snapshot.size)

        var quarantineRewritten = false
        val ops = object : PassthroughOps() {
            override fun atomicReplace(source: File, destination: File) {
                super.atomicReplace(source, destination)
                if (destination.name.endsWith(QUARANTINE_SUFFIX)) quarantineRewritten = true
            }

            override fun syncDirectory(dir: File) {
                if (quarantineRewritten) throw IOException("dir fsync failed")
            }
        }
        val faulted = store(directory, ops = ops)
        faulted.prime()

        var outcome: Boolean? = null
        faulted.forget("кит", "кал") { outcome = it }

        assertEquals("the slot holds exactly the copy without the pair", true, outcome)
        val salvage = PersonalBigramQuarantineSalvage.read(quarantineFile(directory), subtype)!!
        assertEquals(listOf("ал", "сүз"), salvage.contexts)
        assertEquals(2, faulted.snapshot.size)
        assertEquals(listOf("ал" to "бар", "сүз" to "дөст"), pairsOnDisk(directory))
    }

    @Test
    fun aFsyncFailureWhoseReplaceNeverLandedRollsTheActivePairBack() {
        val directory = quarantinedDirectory(damage = ::flipChecksum)
        val store = store(directory)
        store.restoreQuarantine()
        assertEquals(3, store.snapshot.size)
        val activeBytes = destinationFile(directory).readBytes()
        val quarantineBytes = quarantineFile(directory).readBytes()

        val ops = object : PassthroughOps() {
            override fun atomicReplace(source: File, destination: File) {
                if (destination.name.endsWith(QUARANTINE_SUFFIX)) throw IOException("replace failed")
                super.atomicReplace(source, destination)
            }
        }
        var outcome: Boolean? = null
        store(directory, ops = ops).forget("кит", "кал") { outcome = it }

        assertEquals(false, outcome)
        assertEquals(3, store.snapshot.size)
        assertArrayEquals(activeBytes, destinationFile(directory).readBytes())
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
    }

    @Test
    fun aFailedActiveRollbackKeepsMemoryConsistentWithTheDisk() {
        val directory = quarantinedDirectory(damage = { truncate(it, dropTailBytes = 5) })
        val active = store(directory)
        active.restoreQuarantine()
        assertEquals(2, active.snapshot.size)

        val ops = object : PassthroughOps() {
            private var activeReplaces = 0
            override fun atomicReplace(source: File, destination: File) {
                // The forget's own write lands; the second replace to the active name is the
                // rollback, which fails.
                if (destination.name == activeName() && activeReplaces++ > 0) {
                    throw IOException("rollback replace failed")
                }
                super.atomicReplace(source, destination)
            }
        }
        val faulted = store(directory, ops = ops)
        faulted.prime()

        var outcome: Boolean? = null
        faulted.forget("ал", "бар") { outcome = it }

        assertEquals(false, outcome)
        assertEquals("memory keeps what the disk shows", 1, faulted.snapshot.size)
        assertEquals("a fresh reader sees exactly the installed deletion",
            listOf("кит" to "кал"), pairsOnDisk(directory))
        assertTrue(quarantineFile(directory).isFile)
    }

    @Test
    fun aFailedRollbackAfterADeletedActiveFileKeepsTheDiskStateInMemory() {
        val directory = quarantinedDirectoryWithOnePairCopy()
        val active = store(directory)
        active.restoreQuarantine()
        assertEquals(1, active.snapshot.size)
        truncate(quarantineFile(directory), dropTailBytes = 5)

        val ops = object : PassthroughOps() {
            override fun atomicReplace(source: File, destination: File) {
                // The only replace to the active name here is the rollback's, and it fails.
                if (destination.name == activeName()) throw IOException("rollback replace failed")
                super.atomicReplace(source, destination)
            }
        }
        val faulted = store(directory, ops = ops)
        faulted.prime()

        var outcome: Boolean? = null
        faulted.forget("ал", "бар") { outcome = it }

        assertEquals(false, outcome)
        assertTrue("the disk shows the file gone, so the snapshot does too", faulted.snapshot.isEmpty)
        assertFalse(destinationFile(directory).exists())
        assertTrue(quarantineFile(directory).isFile)
    }

    @Test
    fun aFsyncFailureAfterTheActiveReplaceKeepsTheDeletionInstalledAndSkipsThePurge() {
        val directory = quarantinedDirectory(damage = ::flipChecksum)
        val store = store(directory)
        store.restoreQuarantine()
        assertEquals(3, store.snapshot.size)
        val quarantineBytes = quarantineFile(directory).readBytes()

        var activeReplaced = false
        val ops = object : PassthroughOps() {
            override fun atomicReplace(source: File, destination: File) {
                super.atomicReplace(source, destination)
                if (destination.name == activeName()) activeReplaced = true
            }

            override fun syncDirectory(dir: File) {
                if (activeReplaced) throw IOException("dir fsync failed")
            }
        }
        val faulted = store(directory, ops = ops)
        faulted.prime()

        var outcome: Boolean? = null
        faulted.forget("ал", "бар") { outcome = it }

        assertEquals("durability is unknown, so this is not a success", false, outcome)
        assertEquals(2, faulted.snapshot.size)
        assertEquals("a fresh reader sees the installed deletion",
            listOf("кит" to "кал", "сүз" to "дөст"), pairsOnDisk(directory))
        assertArrayEquals("the purge was skipped, so the copy keeps every pair",
            quarantineBytes, quarantineFile(directory).readBytes())
    }

    // ---- helpers --------------------------------------------------------------------------------

    private val directExecutor = Executor { it.run() }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private fun store(
        directory: File,
        ops: DurableFileOps = PassthroughOps(),
        spaceProbe: SpaceProbe = SpaceProbe { Long.MAX_VALUE },
    ): PersonalBigramStore = PersonalBigramStore(
        subtypeId = subtype,
        directoryProvider = { directory },
        fileOps = ops,
        outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
        spaceProbe = spaceProbe,
        clock = { 1000L },
        executor = directExecutor,
        contextMembership = PersonalBigramContextMembership { _, _ -> true },
    )

    private fun destinationFile(directory: File) =
        File(directory, TpersbFormat.personalBigramsFileName(subtype))

    private fun quarantineFile(directory: File) =
        File(directory, TpersbFormat.personalBigramsFileName(subtype) + QUARANTINE_SUFFIX)

    private fun activeName() = TpersbFormat.personalBigramsFileName(subtype)

    /** The pairs a fresh VALIDATION of the active file finds — the deletion is only real if it reopened. */
    private fun pairsOnDisk(directory: File): List<Pair<String, String>> {
        val validated = TpersbValidator().validate(destinationFile(directory), subtype)
        return (0 until validated.pairCount).map {
            validated.contexts[it] to validated.successorNormalizedForms[it]
        }
    }

    /** Three pairs are learned, the file is damaged as [damage] says, and one open quarantines it. */
    private fun quarantinedDirectory(damage: (File) -> Unit): File {
        val directory = newPersonalDir()
        learnThreePairs(store(directory))
        damage(destinationFile(directory))
        store(directory).prime()
        assertTrue("the harness must really produce a copy", quarantineFile(directory).isFile)
        return directory
    }

    /** A whole-copy directory whose quarantine slot holds exactly the single pair to forget. */
    private fun quarantinedDirectoryWithOnePairCopy(): File {
        val directory = quarantinedDirectory(damage = ::flipChecksum)
        val onePairDirectory = newPersonalDir()
        store(onePairDirectory).also { repeat(2) { _ -> it.notePair("ал", "бар") } }
        quarantineFile(directory).writeBytes(destinationFile(onePairDirectory).readBytes())
        return directory
    }

    private fun learnThreePairs(store: PersonalBigramStore) {
        repeat(2) { store.notePair("ал", "бар") }
        repeat(2) { store.notePair("кит", "кал") }
        repeat(2) { store.notePair("сүз", "дөст") }
        assertEquals("the harness must really learn three pairs", 3, store.snapshot.size)
    }

    /** The validator refuses the file while every record still parses: the copy salvages whole. */
    private fun flipChecksum(file: File) {
        val bytes = file.readBytes()
        bytes[TpersbFormat.CHECKSUM_OFFSET] =
            (bytes[TpersbFormat.CHECKSUM_OFFSET].toInt() xor 0xFF).toByte()
        file.writeBytes(bytes)
    }

    /** Cuts the tail off a file: the shape a power cut mid-write leaves behind. */
    private fun truncate(file: File, dropTailBytes: Int) {
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - dropTailBytes))
    }

    private companion object {
        const val QUARANTINE_SUFFIX = ".quarantine"
    }

    /** Performs every durable op for real; a test overrides the one it wants to fail. */
    private open class PassthroughOps : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()

        override fun syncFile(fileDescriptor: FileDescriptor) = fileDescriptor.sync()

        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }

        override fun atomicReplace(source: File, destination: File) {
            if (!source.renameTo(destination)) throw IOException("replace failed")
        }

        override fun delete(file: File): Boolean = file.delete()

        override fun syncDirectory(directory: File) = Unit
    }
}
