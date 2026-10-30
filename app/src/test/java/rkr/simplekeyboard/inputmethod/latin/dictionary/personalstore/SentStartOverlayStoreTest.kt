/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/**
 * The sentence-initial overlay store: the learn threshold with its pending counters, the
 * whole-file write discipline, the cap and its LRU eviction, the usage-ordered snapshot, the
 * quarantine of an unreadable file, the unlock gate and erasure. Plain JVM; the write-sequence
 * fault matrix is the personal dictionary's, pinned in `PersonalDictionaryStoreWriteTest`, and is
 * not repeated at full width here.
 */
class SentStartOverlayStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    // ---- the learn threshold and the pending counters -------------------------------------------

    @Test
    fun belowTheThresholdNothingIsPlaintextAndNoOverlayFileExists() {
        val directory = newPersonalDir()
        val store = store(directory)

        repeat(PendingCounters.LEARN_THRESHOLD - 1) { store.noteSentenceStart("сәләм") }

        assertTrue("below the threshold the snapshot stays empty", store.snapshot.isEmpty)
        assertFalse("no overlay file before graduation", overlayFile(directory).isFile)
        assertFalse("pending hashes are written only at the session boundary",
            pendingFile(directory).isFile)

        store.flush()

        assertTrue("a flush writes the pending progress", pendingFile(directory).isFile)
        assertFalse("but still no plaintext", overlayFile(directory).isFile)
        assertTrue(store.snapshot.isEmpty)
    }

    @Test
    fun theThirdSentenceStartCommitGraduatesTheWordIntoPlaintext() {
        val directory = newPersonalDir()
        val store = store(directory)

        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("сәләм") }

        assertEquals(listOf("сәләм"), store.snapshot.topWords())
        assertTrue(overlayFile(directory).isFile)
        val onDisk = TsentValidator().validate(overlayFile(directory), subtype)
        assertEquals(listOf("сәләм"), onDisk.normalizedForms)
        // The graduated word's pending trace is gone, on disk after the flush.
        store.flush()
        assertEquals(0, PendingCounters.parse(pendingFile(directory).readBytes()).size)
    }

    @Test
    fun aFreshStoreReadsBackTheOverlayInUsageOrder() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("сәләм") }
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("дөнья") }
        // Usage: «сәләм» is the stronger habit.
        store.noteSentenceStart("сәләм")
        store.flush()

        val reopened = store(directory)
        assertTrue("nothing is published before the store opens", reopened.snapshot.isEmpty)
        reopened.prime()
        assertEquals(listOf("сәләм", "дөнья"), reopened.snapshot.topWords())
        val onDisk = TsentValidator().validate(overlayFile(directory), subtype)
        assertEquals(2, onDisk.entryCount)
    }

    @Test
    fun usageCountersMoveInMemoryAndAreWrittenOnceAtFlush() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("сәләм") }
        assertEquals("graduation is the one write so far", 1, store.writeCount)

        repeat(5) { store.noteSentenceStart("сәләм") }
        assertEquals("usage bumps never rewrite the file", 1, store.writeCount)
        // The bump already reorders the published snapshot.
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("дөнья") }
        assertEquals("a second graduation is the second write", 2, store.writeCount)
        assertEquals(listOf("сәләм", "дөнья"), store.snapshot.topWords())

        store.flush()
        assertEquals("the boundary flush writes the counters once", 3, store.writeCount)
        store.flush()
        assertEquals("nothing dirty, nothing written", 3, store.writeCount)
        val onDisk = TsentValidator().validate(overlayFile(directory), subtype)
        // «сәләм»: 1 at graduation + 5 bumps; «дөнья»: 1 at graduation.
        assertEquals(6, onDisk.usageCounts[onDisk.normalizedForms.indexOf("сәләм")])
    }

    // ---- a failed graduation write is not retried until the flush boundary ----------------------

    @Test
    fun aFailedGraduationWriteIsNotRetriedUntilTheNextFlush() {
        val directory = newPersonalDir()
        var replaceFails = false
        var overlayReplacements = 0
        val ops = object : RecordingOps() {
            override fun atomicReplace(source: File, destination: File) {
                if (destination.name == TsentFormat.sentStartFileName(subtype)) {
                    overlayReplacements++
                    if (replaceFails) throw IOException("no space left")
                }
                super.atomicReplace(source, destination)
            }
        }
        val store = store(directory, ops = ops)
        store.noteSentenceStart("сүзләр") // creates the salt while writes still work

        replaceFails = true
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("гүзәлия") }
        assertEquals("the graduation write was attempted once", 1, overlayReplacements)
        assertFalse(overlayFile(directory).isFile)

        repeat(3) { store.noteSentenceStart("гүзәлия") }
        assertEquals("a suppressed write is not even attempted", 1, overlayReplacements)

        replaceFails = false
        store.flush() // the boundary: the counters reach the disk and the suppression lifts
        assertEquals("the flush did not need another whole-file write", 1, overlayReplacements)
        val salt = File(directory, "salt-sentstart.bin").readBytes()
        val counters = PendingCounters.parse(pendingFile(directory).readBytes())
        assertEquals(
            "six commits were counted, none lost",
            6,
            counters.countOf(PendingCounters.keyOf(salt, "гүзәлия")),
        )

        store.noteSentenceStart("гүзәлия") // the first commit after the boundary retries
        assertEquals(2, overlayReplacements)
        assertEquals(listOf("гүзәлия"), store.snapshot.topWords())
    }

    @Test
    fun theCommittedCasingIsKeptOnDiskAndInTheSnapshot() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("Сәләм") }
        assertEquals(listOf("Сәләм"), store.snapshot.topWords())
        assertEquals(listOf("Сәләм"), TsentValidator().validate(overlayFile(directory), subtype).rawForms)
    }

    // ---- cap and LRU ----------------------------------------------------------------------------

    @Test
    fun theCapEvictsTheLeastRecentlyUsedOnDiskToo() {
        val directory = newPersonalDir()
        val store = store(directory, maxEntries = 3)
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("сәләм") }
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("дөнья") }
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("китап") }
        // «сәләм» is touched again, so «дөнья» is now the least recently used.
        store.noteSentenceStart("сәләм")
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("малай") }

        val onDisk = TsentValidator().validate(overlayFile(directory), subtype)
        assertEquals(3, onDisk.entryCount)
        assertFalse(onDisk.normalizedForms.contains("дөнья"))
        assertTrue(onDisk.normalizedForms.contains("сәләм"))
        assertTrue(onDisk.normalizedForms.contains("китап"))
        assertTrue(onDisk.normalizedForms.contains("малай"))
        assertTrue(overlayFile(directory).length() <= TsentFormat.MAX_FILE_SIZE)
    }

    @Test
    fun aOneLetterWordIsEligible() {
        // «я» is Russian, so use the Russian subtype: sentence starts are exactly where the
        // one-letter words live.
        val directory = newPersonalDir()
        val store = store(directory, subtype = PersonalSubtypes.RUSSIAN)
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("Я") }
        assertEquals(listOf("Я"), store.snapshot.topWords())
        assertEquals(
            listOf("я"),
            TsentValidator().validate(
                File(directory, TsentFormat.sentStartFileName(PersonalSubtypes.RUSSIAN)),
                PersonalSubtypes.RUSSIAN,
            ).normalizedForms,
        )
    }

    // ---- ineligible input ------------------------------------------------------------------------

    @Test
    fun ineligibleWordsNeverEnterTheStoreOrTheSnapshot() {
        val directory = newPersonalDir()
        val store = store(directory)
        val badWords = listOf(
            "аБвгд", // mixed case
            "word", // outside the alphabet
            "123", // digits
            "😀", // an emoji is not a word
            "э".repeat(TsentFormat.MAX_WORD_CODE_POINTS + 1), // too long
        )
        for (word in badWords) {
            repeat(PendingCounters.LEARN_THRESHOLD + 2) { store.noteSentenceStart(word) }
        }
        store.flush()
        assertTrue(store.snapshot.isEmpty)
        assertFalse(overlayFile(directory).isFile)
        assertFalse("nothing counted, so no pending hashes were ever written",
            pendingFile(directory).isFile)
    }

    // ---- quarantine, the unlock gate, erasure ----------------------------------------------------

    @Test
    fun aCorruptPendingFileIsSetAsideAndAnnouncedUntilDelivered() {
        val directory = newPersonalDir()
        val writer = store(directory)
        writer.noteSentenceStart("сүзләр")
        writer.flush()
        val pendingFile = pendingFile(directory)
        assertTrue(pendingFile.isFile)
        val bytes = pendingFile.readBytes()
        bytes[0] = 'X'.code.toByte() // the magic no longer matches
        pendingFile.writeBytes(bytes)

        var notices = 0
        val store = store(directory, quarantineNotice = { notices++ })
        store.prime()

        assertEquals("the loss is announced on the open that sets the file aside", 1, notices)
        assertFalse("the corrupt file does not stay where the reader looks", pendingFile.exists())
        assertArrayEquals(
            "the bytes are set aside, not destroyed",
            bytes,
            File(directory, "pending-sentstart-$subtype-s1-f1.bin.quarantine").readBytes(),
        )
        assertTrue(
            "the durable mark is the pending channel's own",
            File(directory, "quarantine-notice-sentstart-$subtype-s1-f1-pending.flag").isFile,
        )
        assertFalse(
            "the overlay's own mark stays down: its file is fine",
            File(directory, "quarantine-notice-sentstart-$subtype-s1-f1.flag").exists(),
        )
        assertTrue("learning restarts from empty", store.snapshot.isEmpty)

        // Across a process restart the mark raises the notice again, until it is delivered.
        var nextProcess = 0
        store(directory, quarantineNotice = { nextProcess++ }).prime()
        assertEquals("a notice nobody has taken is still owed", 1, nextProcess)

        store(directory).noticeDelivered()
        assertFalse(File(directory, "quarantine-notice-sentstart-$subtype-s1-f1-pending.flag").exists())
        var afterDelivery = 0
        store(directory, quarantineNotice = { afterDelivery++ }).prime()
        assertEquals("a delivered notice is not raised again", 0, afterDelivery)
    }

    @Test
    fun aCorruptFileIsQuarantinedAndLearningContinues() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("сәләм") }
        // Corrupt a payload byte so the checksum no longer matches.
        val bytes = overlayFile(directory).readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x7f).toByte()
        overlayFile(directory).writeBytes(bytes)

        val reopened = store(directory)
        reopened.prime()
        assertTrue("the unreadable file publishes nothing", reopened.snapshot.isEmpty)
        assertFalse("the unreadable file left its slot", overlayFile(directory).isFile)
        assertTrue("and sits in the quarantine slot",
            File(directory, TsentFormat.sentStartFileName(subtype) + ".quarantine").isFile)

        repeat(PendingCounters.LEARN_THRESHOLD) { reopened.noteSentenceStart("дөнья") }
        assertEquals(listOf("дөнья"), reopened.snapshot.topWords())
        assertEquals(listOf("дөнья"), TsentValidator().validate(overlayFile(directory), subtype).normalizedForms)
    }

    @Test
    fun aLockedDeviceSessionNeverReadsNorWrites() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("сәләм") }
        store.flush()
        val priorBytes = overlayFile(directory).readBytes()
        val priorNames = directory.list()!!.sorted()

        val locked = store(directory, unlockGate = { false })
        locked.prime()
        repeat(PendingCounters.LEARN_THRESHOLD + 2) { locked.noteSentenceStart("дөнья") }
        locked.flush()

        assertTrue("a locked store publishes nothing", locked.snapshot.isEmpty)
        assertArrayEquals(priorBytes, overlayFile(directory).readBytes())
        assertEquals(priorNames, directory.list()!!.sorted())
    }

    @Test
    fun clearAllEmptiesMemoryAndDeletesEveryFileOfTheFeature() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(PendingCounters.LEARN_THRESHOLD - 1) { store.noteSentenceStart("китап") }
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("сәләм") }
        store.flush()
        // Plus a quarantined copy from an earlier loss.
        overlayFile(directory).let { file ->
            val corrupt = file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
            file.writeBytes(corrupt)
        }
        pendingFile(directory).let { file ->
            val corrupt = file.readBytes().also { it[0] = 'X'.code.toByte() }
            file.writeBytes(corrupt)
        }
        val reopened = store(directory)
        reopened.prime() // quarantines both corrupt files
        assertTrue(File(directory, TsentFormat.sentStartFileName(subtype) + ".quarantine").isFile)
        assertTrue(File(directory, "pending-sentstart-$subtype-s1-f1.bin.quarantine").isFile)
        assertTrue(File(directory, "quarantine-notice-sentstart-$subtype-s1-f1.flag").isFile)
        assertTrue(
            File(directory, "quarantine-notice-sentstart-$subtype-s1-f1-pending.flag").isFile,
        )

        var answered: Boolean? = null
        reopened.clearAll { succeeded -> answered = succeeded }

        assertEquals(true, answered)
        assertTrue(reopened.snapshot.isEmpty)
        assertEquals("every file of the feature is gone", emptyList<String>(), directory.list()!!.toList())
    }

    @Test
    fun aFailedQuarantineRenamePreservesTheOriginalBytes() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(PendingCounters.LEARN_THRESHOLD) { store.noteSentenceStart("сәләм") }
        store.flush()
        val destination = overlayFile(directory)
        val corrupt = destination.readBytes()
        corrupt[corrupt.size - 1] = (corrupt[corrupt.size - 1].toInt() xor 0x7f).toByte()
        destination.writeBytes(corrupt)
        val failing = object : RecordingOps() {
            override fun atomicReplace(source: File, destination: File) =
                throw IOException("replace failed")
        }

        store(directory, ops = failing).prime()

        assertArrayEquals(corrupt, destination.readBytes())
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private val directExecutor = Executor { it.run() }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private fun store(
        directory: File,
        subtype: String = this.subtype,
        unlockGate: () -> Boolean = { true },
        maxEntries: Int = TsentFormat.MAX_SENTSTART_ENTRIES.toInt(),
        ops: DurableFileOps = RecordingOps(),
        quarantineNotice: PersonalQuarantineNotice? = null,
    ): SentStartOverlayStore = SentStartOverlayStore(
        subtypeId = subtype,
        directoryProvider = { directory },
        fileOps = ops,
        outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
        spaceProbe = SpaceProbe { Long.MAX_VALUE },
        clock = { 1000L },
        executor = directExecutor,
        unlockGate = unlockGate,
        maxEntries = maxEntries,
        quarantineNotice = quarantineNotice,
    )

    private fun overlayFile(directory: File) =
        File(directory, TsentFormat.sentStartFileName(subtype))

    private fun pendingFile(directory: File) =
        File(directory, "pending-sentstart-$subtype-s1-f1.bin")

    /** Performs every durable op for real; the fault matrix lives in the words store's test. */
    private open class RecordingOps : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()

        override fun syncFile(fileDescriptor: FileDescriptor) {
            fileDescriptor.sync()
        }

        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }

        override fun atomicReplace(source: File, destination: File) {
            if (!source.renameTo(destination)) {
                destination.delete()
                if (!source.renameTo(destination)) throw IOException("replace failed")
            }
        }

        override fun syncDirectory(directory: File) = Unit

        override fun delete(file: File): Boolean = file.delete()
    }
}
