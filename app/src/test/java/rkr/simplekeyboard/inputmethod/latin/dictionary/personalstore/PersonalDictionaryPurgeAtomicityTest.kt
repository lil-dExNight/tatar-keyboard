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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/**
 * The atomicity contract of `forget`'s quarantine half in [PersonalDictionaryStore], under faults:
 *
 * - a copy that was not read to its end is never rewritten or deleted: the target may sit in the
 *   unread part, and a purge over it could resurrect what the user was told is gone;
 * - a failed directory fsync after the quarantine delete or replace is not swallowed: the bytes
 *   may be rolled back to the original copy, so success is claimed only when the slot provably
 *   holds the purged state;
 * - a failed purge rolls the active file back, and when the rollback itself fails, memory keeps
 *   the deletion the disk still shows rather than disagreeing with the file;
 * - a fsync failure after the active replace installed the deletion keeps it and skips the purge.
 *
 * Every faulted path ends with the active file and the quarantine copy byte-for-byte unchanged,
 * or with an answer that honestly says which half could not be taken back. Plain JVM.
 */
class PersonalDictionaryPurgeAtomicityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    // ---- an unread copy fails closed ------------------------------------------------------------

    /**
     * The copy was cut short through the last record: its readable head holds two words, its
     * unread tail holds the rest. The forgotten word is in the active list AND in the head, but
     * the tail is unproven, so the purge must refuse and leave every byte alone.
     */
    @Test
    fun forgettingOverATruncatedCopyFailsClosedAndLeavesEveryByte() {
        val directory = quarantinedDirectory(damage = { truncate(it, dropTailBytes = 4) })
        val store = store(directory)
        store.restoreQuarantine()
        assertEquals(2, store.snapshot.size)
        val activeBytes = destinationFile(directory).readBytes()
        val quarantineBytes = quarantineFile(directory).readBytes()

        var outcome: Boolean? = null
        store.forget("абыйлар") { outcome = it }

        assertEquals("a purge over an unread tail is a failure, never a silent loss", false, outcome)
        assertTrue("the word stays in the list", store.snapshot.indexOfNormalized("абыйлар") >= 0)
        assertEquals(2, store.snapshot.size)
        assertArrayEquals(activeBytes, destinationFile(directory).readBytes())
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
    }

    /**
     * The target itself sits in the cut record: the salvage head stops before it, so its bytes
     * are only in the unread tail. Nothing may be rewritten or deleted over them.
     */
    @Test
    fun aWordOnlyInTheUnreadTailIsNotPurged() {
        val directory = quarantinedDirectory(damage = { truncate(it, dropTailBytes = 4) })
        val store = store(directory)
        store.restoreQuarantine()
        val salvage = PersonalQuarantineSalvage.read(quarantineFile(directory), subtype)
        assertNotNull(salvage)
        assertFalse("the harness must really leave an unread tail", salvage!!.readToEnd)
        assertEquals("and the cut word must not be in the readable head",
            listOf("абыйлар", "бабай"), salvage.normalizedForms)
        val activeBytes = destinationFile(directory).readBytes()
        val quarantineBytes = quarantineFile(directory).readBytes()

        var outcome: Boolean? = null
        store.forget("гүзәл") { outcome = it }

        assertEquals(false, outcome)
        assertEquals(2, store.snapshot.size)
        assertArrayEquals(activeBytes, destinationFile(directory).readBytes())
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
    }

    /** A copy whose readable head lacks the word is no safer: absence is only provable whole. */
    @Test
    fun forgettingAWordAbsentFromATruncatedHeadStillFailsClosed() {
        val directory = quarantinedDirectory(damage = { truncate(it, dropTailBytes = 20) })
        val store = store(directory)
        store.prime()
        assertTrue("the corruption emptied the list", store.snapshot.isEmpty)
        val quarantineBytes = quarantineFile(directory).readBytes()

        var outcome: Boolean? = null
        store.forget("гүзәл") { outcome = it }

        assertEquals(false, outcome)
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
        assertFalse(destinationFile(directory).exists())
    }

    // ---- the fsync after the purge step is part of the answer -----------------------------------

    /**
     * A thrown quarantine delete may have landed or not; the slot still holds the original copy
     * with the word in it, so the purge failed and the active word comes back byte-for-byte.
     */
    @Test
    fun aThrownQuarantineDeleteFailsClosedAndLeavesBothFilesUntouched() {
        // The copy holds exactly the word to forget, so the purge takes the delete path.
        val directory = quarantinedDirectoryWithOneWordCopy()
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
        store(directory, ops = ops).forget("бабай") { outcome = it }

        assertEquals(false, outcome)
        assertTrue(store.snapshot.indexOfNormalized("бабай") >= 0)
        assertEquals(1, store.snapshot.size)
        assertArrayEquals(activeBytes, destinationFile(directory).readBytes())
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
    }

    /**
     * The last word of the copy is forgotten: the delete lands, the directory fsync behind it
     * fails, the slot is empty — which is the purged state — so the deletion is honestly durable
     * and the answer is true.
     */
    @Test
    fun aDeleteFollowedByAFsyncFailureIsTrueBecauseTheSlotIsGone() {
        val directory = quarantinedDirectoryWithOneWordCopy()
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
        faulted.forget("бабай") { outcome = it }

        assertEquals("the empty slot is the proof", true, outcome)
        assertFalse(quarantineFile(directory).exists())
        assertFalse(destinationFile(directory).exists())
        assertTrue(faulted.snapshot.isEmpty)
    }

    /**
     * The rewrite shape: the quarantine replace lands, the fsync behind it fails, the slot now
     * holds exactly the copy without the word. Those bytes are the proof, so the answer is true.
     */
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
        faulted.forget("бабай") { outcome = it }

        assertEquals("the slot holds exactly the copy without the word", true, outcome)
        assertEquals(
            listOf("абыйлар", "гүзәл"),
            PersonalQuarantineSalvage.read(quarantineFile(directory), subtype)!!.normalizedForms,
        )
        assertTrue(faulted.snapshot.indexOfNormalized("бабай") < 0)
        assertEquals(2, faulted.snapshot.size)
    }

    /**
     * The other rewrite outcome: the replace never landed, so the slot still holds the original
     * copy — word included — and the purge failed. The active word comes back.
     */
    @Test
    fun aFsyncFailureWhoseReplaceNeverLandedRollsTheActiveWordBack() {
        val directory = quarantinedDirectory(damage = ::flipChecksum)
        val store = store(directory)
        store.restoreQuarantine()
        assertEquals(3, store.snapshot.size)
        val activeBytes = destinationFile(directory).readBytes()
        val quarantineBytes = quarantineFile(directory).readBytes()

        val ops = object : PassthroughOps() {
            override fun atomicReplace(source: File, destination: File) {
                // Only the purge's rewrite is refused; the forget's own active write lands.
                if (destination.name.endsWith(QUARANTINE_SUFFIX)) throw IOException("replace failed")
                super.atomicReplace(source, destination)
            }
        }
        var outcome: Boolean? = null
        store(directory, ops = ops).forget("бабай") { outcome = it }

        assertEquals(false, outcome)
        assertTrue(store.snapshot.indexOfNormalized("бабай") >= 0)
        assertEquals(3, store.snapshot.size)
        assertArrayEquals(activeBytes, destinationFile(directory).readBytes())
        assertArrayEquals(quarantineBytes, quarantineFile(directory).readBytes())
    }

    // ---- the rollback itself can fail -----------------------------------------------------------

    /**
     * The purge cannot run (unread tail) and putting the active file back fails too. The disk
     * still shows the deletion, so memory must agree with the disk — not with the list the user
     * last saw — and the answer is false.
     */
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
        faulted.forget("абыйлар") { outcome = it }

        assertEquals(false, outcome)
        assertTrue("memory keeps what the disk shows", faulted.snapshot.indexOfNormalized("абыйлар") < 0)
        assertEquals(1, faulted.snapshot.size)
        assertEquals("a fresh reader sees exactly the installed deletion", listOf("бабай"),
            reopenedWords(directory))
        // The copy was never touched: the forgotten word is still recoverable there.
        assertTrue(quarantineFile(directory).isFile)
    }

    /**
     * The forget deleted the active file, the purge failed, and the rollback write failed as
     * well: a double fault. Same rule — memory keeps the disk state, the answer is false, the
     * copy is untouched.
     */
    @Test
    fun aFailedRollbackAfterADeletedActiveFileKeepsTheDiskStateInMemory() {
        val directory = quarantinedDirectoryWithOneWordCopy()
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
        faulted.forget("бабай") { outcome = it }

        assertEquals(false, outcome)
        assertTrue("the disk shows the file gone, so the snapshot does too", faulted.snapshot.isEmpty)
        assertFalse(destinationFile(directory).exists())
        assertTrue(quarantineFile(directory).isFile)
    }

    // ---- a fault after the active write, before the purge ---------------------------------------

    /**
     * The active replace lands and the fsync behind IT fails: the deletion is installed but not
     * known durable, the answer is false, and the purge must not run — the copy keeps all three
     * words, so a later restore can only add what the user asks for, never resurrect silently.
     */
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
        faulted.forget("абыйлар") { outcome = it }

        assertEquals("durability is unknown, so this is not a success", false, outcome)
        assertTrue(faulted.snapshot.indexOfNormalized("абыйлар") < 0)
        assertEquals(2, faulted.snapshot.size)
        assertEquals("a fresh reader sees the installed deletion",
            listOf("бабай", "гүзәл"), reopenedWords(directory))
        assertArrayEquals("the purge was skipped, so the copy keeps every word",
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
    ): PersonalDictionaryStore = PersonalDictionaryStore(
        subtypeId = subtype,
        directoryProvider = { directory },
        fileOps = ops,
        outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
        spaceProbe = spaceProbe,
        clock = { 1000L },
        executor = directExecutor,
    )

    private fun destinationFile(directory: File) =
        File(directory, TpersFormat.personalFileName(subtype))

    private fun quarantineFile(directory: File) =
        File(directory, TpersFormat.personalFileName(subtype) + QUARANTINE_SUFFIX)

    private fun activeName() = TpersFormat.personalFileName(subtype)

    /** The words a FRESH reader finds in the active file — the deletion is only real if it reopened. */
    private fun reopenedWords(directory: File): List<String> {
        val reopened = store(directory)
        reopened.prime()
        val snapshot = reopened.snapshot
        return (0 until snapshot.size).map { snapshot.normalizedFormAt(it) }
    }

    /**
     * Three words are written, the file is damaged as [damage] says, and one open quarantines it:
     * the active name is gone, the damaged bytes sit in the quarantine slot.
     */
    private fun quarantinedDirectory(damage: (File) -> Unit): File {
        val directory = newPersonalDir()
        store(directory).apply {
            addManually("абыйлар")
            addManually("бабай")
            addManually("гүзәл")
        }
        damage(destinationFile(directory))
        store(directory).prime()
        assertTrue("the harness must really produce a copy", quarantineFile(directory).isFile)
        return directory
    }

    /** A whole-copy directory whose quarantine slot holds exactly the single word to forget. */
    private fun quarantinedDirectoryWithOneWordCopy(): File {
        val directory = quarantinedDirectory(damage = ::flipChecksum)
        val oneWordDirectory = newPersonalDir()
        store(oneWordDirectory).addManually("бабай")
        quarantineFile(directory).writeBytes(destinationFile(oneWordDirectory).readBytes())
        return directory
    }

    /** The validator refuses the file while every record still parses: the copy salvages whole. */
    private fun flipChecksum(file: File) {
        val bytes = file.readBytes()
        bytes[TpersFormat.CHECKSUM_OFFSET] =
            (bytes[TpersFormat.CHECKSUM_OFFSET].toInt() xor 0xFF).toByte()
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
