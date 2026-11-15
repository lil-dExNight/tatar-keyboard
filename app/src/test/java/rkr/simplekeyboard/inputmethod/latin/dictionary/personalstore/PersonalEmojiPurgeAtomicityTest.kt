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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/**
 * The emoji twin of [PersonalDictionaryPurgeAtomicityTest]: the quarantine half of `forget` in
 * [PersonalEmojiStore] fails closed over an unread copy, never swallows a post-delete/replace
 * fsync failure, and keeps memory consistent with the disk when the active rollback itself fails.
 * Entries on disk are ordered by word, so the cut tail always holds (сүз, 🌙). Plain JVM.
 */
class PersonalEmojiPurgeAtomicityTest {
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
        store.forget("ал", "☀️") { outcome = it }

        assertEquals(false, outcome)
        assertEquals(2, store.snapshot.size)
        assertArrayEquals(activeBytes, destinationFile(directory).readBytes())
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
    }

    @Test
    fun anEntryOnlyInTheUnreadTailIsNotPurged() {
        val directory = quarantinedDirectory(damage = { truncate(it, dropTailBytes = 4) })
        val store = store(directory)
        store.restoreQuarantine()
        val salvage = PersonalEmojiQuarantineSalvage.read(quarantineFile(directory), subtype)
        assertNotNull(salvage)
        assertFalse("the harness must really leave an unread tail", salvage!!.readToEnd)
        assertEquals("the cut entry must not be in the readable head",
            listOf("ал", "бал"), salvage.words)
        val activeBytes = destinationFile(directory).readBytes()
        val quarantineBytes = quarantineFile(directory).readBytes()

        var outcome: Boolean? = null
        store.forget("сүз", "🌙") { outcome = it }

        assertEquals(false, outcome)
        assertEquals(2, store.snapshot.size)
        assertArrayEquals(activeBytes, destinationFile(directory).readBytes())
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
    }

    @Test
    fun forgettingAnEntryAbsentFromATruncatedHeadStillFailsClosed() {
        val directory = quarantinedDirectory(damage = { truncate(it, dropTailBytes = 20) })
        val store = store(directory)
        store.prime()
        assertTrue(store.snapshot.isEmpty)
        val quarantineBytes = quarantineFile(directory).readBytes()

        var outcome: Boolean? = null
        store.forget("сүз", "🌙") { outcome = it }

        assertEquals(false, outcome)
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
        assertFalse(destinationFile(directory).exists())
    }

    @Test
    fun aThrownQuarantineDeleteFailsClosedAndLeavesBothFilesUntouched() {
        // The copy holds exactly the entry to forget, so the purge takes the delete path.
        val directory = quarantinedDirectoryWithOneEntryCopy()
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
        store(directory, ops = ops).forget("ал", "☀️") { outcome = it }

        assertEquals(false, outcome)
        assertEquals(1, store.snapshot.size)
        assertArrayEquals(activeBytes, destinationFile(directory).readBytes())
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
    }

    @Test
    fun aDeleteFollowedByAFsyncFailureIsTrueBecauseTheSlotIsGone() {
        val directory = quarantinedDirectoryWithOneEntryCopy()
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
        faulted.forget("ал", "☀️") { outcome = it }

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
        faulted.forget("бал", "🎉") { outcome = it }

        assertEquals("the slot holds exactly the copy without the entry", true, outcome)
        val salvage = PersonalEmojiQuarantineSalvage.read(quarantineFile(directory), subtype)!!
        assertEquals(listOf("ал", "сүз"), salvage.words)
        assertEquals(2, faulted.snapshot.size)
        assertEquals(listOf("ал" to "☀️", "сүз" to "🌙"), entriesOnDisk(directory))
    }

    @Test
    fun aFsyncFailureWhoseReplaceNeverLandedRollsTheActiveEntryBack() {
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
        store(directory, ops = ops).forget("бал", "🎉") { outcome = it }

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
        faulted.forget("ал", "☀️") { outcome = it }

        assertEquals(false, outcome)
        assertEquals("memory keeps what the disk shows", 1, faulted.snapshot.size)
        assertEquals("a fresh reader sees exactly the installed deletion",
            listOf("бал" to "🎉"), entriesOnDisk(directory))
        assertTrue(quarantineFile(directory).isFile)
    }

    @Test
    fun aFailedRollbackAfterADeletedActiveFileKeepsTheDiskStateInMemory() {
        val directory = quarantinedDirectoryWithOneEntryCopy()
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
        faulted.forget("ал", "☀️") { outcome = it }

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
        faulted.forget("ал", "☀️") { outcome = it }

        assertEquals("durability is unknown, so this is not a success", false, outcome)
        assertEquals(2, faulted.snapshot.size)
        assertEquals("a fresh reader sees the installed deletion",
            listOf("бал" to "🎉", "сүз" to "🌙"), entriesOnDisk(directory))
        assertArrayEquals("the purge was skipped, so the copy keeps every entry",
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
    ): PersonalEmojiStore = PersonalEmojiStore(
        subtypeId = subtype,
        directoryProvider = { directory },
        fileOps = ops,
        outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
        spaceProbe = spaceProbe,
        clock = { 1000L },
        executor = directExecutor,
    )

    private fun destinationFile(directory: File) =
        File(directory, TpersemFormat.personalEmojiFileName(subtype))

    private fun quarantineFile(directory: File) =
        File(directory, TpersemFormat.personalEmojiFileName(subtype) + QUARANTINE_SUFFIX)

    private fun activeName() = TpersemFormat.personalEmojiFileName(subtype)

    /** The entries a fresh VALIDATION of the active file finds — the deletion is only real if it reopened. */
    private fun entriesOnDisk(directory: File): List<Pair<String, String>> {
        val validated = TpersemValidator().validate(destinationFile(directory), subtype)
        return (0 until validated.entryCount).map {
            validated.words[it] to validated.emojiClusters[it]
        }
    }

    /** Three entries are learned, the file is damaged as [damage] says, and one open quarantines it. */
    private fun quarantinedDirectory(damage: (File) -> Unit): File {
        val directory = newPersonalDir()
        learnThreeEntries(store(directory))
        damage(destinationFile(directory))
        store(directory).prime()
        assertTrue("the harness must really produce a copy", quarantineFile(directory).isFile)
        return directory
    }

    /** A whole-copy directory whose quarantine slot holds exactly the single entry to forget. */
    private fun quarantinedDirectoryWithOneEntryCopy(): File {
        val directory = quarantinedDirectory(damage = ::flipChecksum)
        val oneEntryDirectory = newPersonalDir()
        store(oneEntryDirectory).also {
            repeat(PersonalEmojiStore.LEARN_THRESHOLD) { _ -> it.noteObservation("ал", "☀️") }
        }
        quarantineFile(directory).writeBytes(destinationFile(oneEntryDirectory).readBytes())
        return directory
    }

    private fun learnThreeEntries(store: PersonalEmojiStore) {
        repeat(PersonalEmojiStore.LEARN_THRESHOLD) { store.noteObservation("ал", "☀️") }
        repeat(PersonalEmojiStore.LEARN_THRESHOLD) { store.noteObservation("бал", "🎉") }
        repeat(PersonalEmojiStore.LEARN_THRESHOLD) { store.noteObservation("сүз", "🌙") }
        assertEquals("the harness must really learn three entries", 3, store.snapshot.size)
    }

    /** The validator refuses the file while every record still parses: the copy salvages whole. */
    private fun flipChecksum(file: File) {
        val bytes = file.readBytes()
        bytes[TpersemFormat.CHECKSUM_OFFSET] =
            (bytes[TpersemFormat.CHECKSUM_OFFSET].toInt() xor 0xFF).toByte()
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
