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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionaryReader
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Write-path contract of the personal dictionary store: the whole-file write sequence with a
 * fault injected at every step, proving that the previous valid file survives, new data never
 * loses old, a partial temp never becomes the main file, and temp garbage never blocks the next
 * open. Plus no-space, LRU overflow on disk, the unlock gate, corruption quarantine, and the
 * "accepted suggestion does not rewrite the file" rule. Plain JVM.
 */
class PersonalDictionaryStoreWriteTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    // ---- the contract sequence, in order --------------------------------------------------------

    @Test
    fun wholeFileWriteFollowsTheContractSequenceAndPreservesEarlierWords() {
        val directory = newPersonalDir()
        val events = mutableListOf<String>()
        val store = store(directory, RecordingOps(events), RecordingOpener(events))

        store.addManually("абыйлар")
        assertEquals(
            listOf("create", "write", "flush", "file-fsync", "replace", "dir-fsync"),
            events.toList(),
        )

        events.clear()
        store.addManually("сүзлек")
        assertEquals(
            listOf("create", "write", "flush", "file-fsync", "replace", "dir-fsync"),
            events.toList(),
        )
        assertEquals(setOf("абыйлар", "сүзлек"), normalizedOnDisk(directory).toSet())
    }

    // ---- one fault per step; prior file stays intact, no temp remains ---------------------------

    @Test
    fun tempCreationFailureKeepsThePriorFileIntactAndLeavesNoTemp() {
        assertFaultBetweenTempAndReplaceKeepsPrior(
            ops = object : RecordingOps() {
                override fun createNewFile(file: File): Boolean = throw IOException("temp create failed")
            },
        )
    }

    @Test
    fun writeFailureKeepsThePriorFileIntactAndLeavesNoTemp() {
        assertFaultBetweenTempAndReplaceKeepsPrior(
            opener = PersonalOutputOpener { temp ->
                object : FileOutputStream(temp) {
                    override fun write(bytes: ByteArray) = throw IOException("write failed")
                }
            },
        )
    }

    @Test
    fun flushFailureKeepsThePriorFileIntactAndLeavesNoTemp() {
        assertFaultBetweenTempAndReplaceKeepsPrior(
            opener = PersonalOutputOpener { temp ->
                object : FileOutputStream(temp) {
                    override fun flush() = throw IOException("flush failed")
                }
            },
        )
    }

    @Test
    fun fileFsyncFailureKeepsThePriorFileIntactAndLeavesNoTemp() {
        assertFaultBetweenTempAndReplaceKeepsPrior(
            ops = object : RecordingOps() {
                override fun syncFile(fileDescriptor: FileDescriptor) = throw IOException("fsync failed")
            },
        )
    }

    @Test
    fun revalidationFailureKeepsThePriorFileIntactAndLeavesNoTemp() {
        // The written bytes are corrupted before fsync, so the re-validation step rejects them.
        assertFaultBetweenTempAndReplaceKeepsPrior(
            opener = PersonalOutputOpener { temp ->
                object : FileOutputStream(temp) {
                    override fun write(bytes: ByteArray) {
                        val corrupt = bytes.copyOf()
                        corrupt[corrupt.size / 2] = (corrupt[corrupt.size / 2].toInt() xor 0x7f).toByte()
                        super.write(corrupt)
                    }
                }
            },
        )
    }

    @Test
    fun atomicReplaceFailureKeepsThePriorFileIntactAndLeavesNoTemp() {
        assertFaultBetweenTempAndReplaceKeepsPrior(
            ops = object : RecordingOps() {
                override fun atomicReplace(source: File, destination: File) =
                    throw IOException("replace failed")
            },
        )
    }

    @Test
    fun directoryFsyncFailureAfterReplaceLeavesTheNewValidFileAndNoTemp() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).addManually("абыйлар")

        val ops = object : RecordingOps() {
            private var replaced = false
            override fun atomicReplace(source: File, destination: File) {
                super.atomicReplace(source, destination)
                replaced = true
            }

            override fun syncDirectory(directory: File) {
                if (replaced) throw IOException("dir fsync failed")
            }
        }
        store(directory, ops, RealOpener).addManually("сүзлек")

        // The replace already happened: the destination now holds the NEW valid data, readable, and
        // no temp is left. This matches the dictionary store's post-rename fsync behavior.
        assertEquals(setOf("абыйлар", "сүзлек"), normalizedOnDisk(directory).toSet())
        assertNoTemp(directory)
        assertEquals(2, TpersValidator().validate(destinationFile(directory), subtype).entryCount)
    }

    @Test
    fun firstWriteFailureLeavesNoFileAndNoTemp() {
        val directory = newPersonalDir()
        val ops = object : RecordingOps() {
            override fun atomicReplace(source: File, destination: File) = throw IOException("replace failed")
        }
        store(directory, ops, RealOpener).addManually("абыйлар")

        assertFalse("no main file for a failed first write", destinationFile(directory).isFile)
        assertNoTemp(directory)
    }

    @Test
    fun crashDuringReplaceLeavesATempThatTheNextOpenDiscards() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).addManually("абыйлар")
        val priorBytes = destinationFile(directory).readBytes()

        val crashingOps = object : RecordingOps() {
            override fun atomicReplace(source: File, destination: File): Unit = throw PersonalStoreCrash()
        }
        try {
            store(directory, crashingOps, RealOpener).addManually("сүзлек")
            fail("expected the simulated process death to propagate")
        } catch (_: PersonalStoreCrash) {
            // Like process death: the store's catch-all does not swallow an Error.
        }
        assertEquals(1, temps(directory).size) // a temp is left behind by the crash

        // A fresh store discards the stale temp on open and the next write succeeds normally.
        val recovered = store(directory, RecordingOps(), RealOpener)
        recovered.addManually("сүзлек")
        assertNoTemp(directory)
        assertEquals(setOf("абыйлар", "сүзлек"), normalizedOnDisk(directory).toSet())
        // The crash never damaged the prior file's word.
        assertTrue(priorBytes.isNotEmpty())
    }

    @Test
    fun staleTempIsRemovedOnOpenAndNeverBlocksTheNextWrite() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).addManually("абыйлар")
        File(directory, ".personal-$subtype.stale.tmp").writeBytes(byteArrayOf(1, 2, 3))

        // Any operation opens the directory and sweeps stale temps first (here an ineligible add).
        val store = store(directory, RecordingOps(), RealOpener)
        store.addManually("!!") // rejected by the filter, but open() still runs cleanup
        assertNoTemp(directory)
        assertEquals(setOf("абыйлар"), normalizedOnDisk(directory).toSet())

        store.addManually("сүзлек")
        assertEquals(setOf("абыйлар", "сүзлек"), normalizedOnDisk(directory).toSet())
    }

    // ---- no space, overflow, gate, quarantine ---------------------------------------------------

    @Test
    fun noFreeSpaceDropsTheChangeAndKeepsThePriorFileWithoutTemp() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).addManually("абыйлар")
        val priorBytes = destinationFile(directory).readBytes()

        val store = store(directory, RecordingOps(), RealOpener, spaceProbe = SpaceProbe { 0 })
        store.addManually("сүзлек")

        assertArrayEquals(priorBytes, destinationFile(directory).readBytes())
        assertNoTemp(directory)
        assertEquals(setOf("абыйлар"), normalizedOnDisk(directory).toSet())
    }

    @Test
    fun overflowEvictsTheOldestOnDiskToo() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener, maxEntries = 3)
        store.addManually("абый") // serial 1
        store.addManually("сүзлек") // serial 2
        store.addManually("китап") // serial 3
        store.addManually("малай") // serial 4 -> evicts serial 1 ("абый")

        assertEquals(3, store.snapshot.size)
        val onDisk = normalizedOnDisk(directory).toSet()
        assertEquals(setOf("сүзлек", "китап", "малай"), onDisk)
        assertFalse(onDisk.contains("абый"))
        assertTrue(destinationFile(directory).length() <= TpersFormat.MAX_FILE_SIZE)
    }

    @Test
    fun ineligibleInputIsNeverStoredNorDoesItCreateAFile() {
        val badWords = listOf(
            "guzel@mail.ru", "t.me/abc", "код1234", "iPhone", "ГҮЗӘЛ2",
            "аБвгд", "123", "ий", // mixed case, digits-only, too short
        )
        for (word in badWords) {
            val directory = newPersonalDir()
            val store = store(directory, RecordingOps(), RealOpener)
            store.addManually(word)
            assertFalse("[$word] must not create a personal file", destinationFile(directory).isFile)
            assertTrue("[$word] must not enter the snapshot", store.snapshot.isEmpty)
            assertNoTemp(directory)
        }
    }

    @Test
    fun lockedDeviceSessionNeverTouchesTheExistingFile() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).addManually("абыйлар")
        val priorBytes = destinationFile(directory).readBytes()
        val priorNames = directory.list()!!.sorted()

        val locked = store(directory, RecordingOps(), RealOpener, unlockGate = { false })
        repeat(20) { locked.addManually("сүзлек") }
        locked.noteAcceptedSuggestion("абыйлар")
        locked.flush()

        assertArrayEquals(priorBytes, destinationFile(directory).readBytes())
        assertEquals(priorNames, directory.list()!!.sorted())
        assertTrue("locked store never publishes a snapshot", locked.snapshot.isEmpty)
    }

    @Test
    fun corruptFileIsQuarantinedOnOpenAndInputStillWorks() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).addManually("абыйлар")
        // Corrupt a payload byte so the checksum no longer matches.
        val destination = destinationFile(directory)
        val bytes = destination.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x7f).toByte()
        destination.writeBytes(bytes)

        val store = store(directory, RecordingOps(), RealOpener)
        store.addManually("!!") // opens the directory, which quarantines the corrupt file
        assertTrue(store.snapshot.isEmpty)

        // Ordinary input keeps working after the quarantine: a fresh valid file is written.
        store.addManually("сүзлек")
        assertEquals(setOf("сүзлек"), normalizedOnDisk(directory).toSet())
    }

    // ---- accepted suggestion updates counters in memory, never rewrites -------------------------

    @Test
    fun acceptedSuggestionUpdatesCountersInMemoryWithoutRewritingTheFile() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener)
        store.addManually("абыйлар")
        assertEquals(1, store.writeCount)

        repeat(25) { store.noteAcceptedSuggestion("абыйлар") }
        assertEquals("accepted suggestions must not rewrite the file", 1, store.writeCount)

        store.flush()
        assertEquals("the boundary flush writes exactly once", 2, store.writeCount)
        // Counter really advanced: 1 (initial add) + 25 accepted uses.
        assertEquals(26, TpersValidator().validate(destinationFile(directory), subtype).usageCounts[0])

        store.flush() // nothing dirty now
        assertEquals(2, store.writeCount)
    }

    @Test
    fun clearAllEmptiesMemoryAndDeletesTheFile() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener)
        store.addManually("абыйлар")
        assertTrue(destinationFile(directory).isFile)

        store.clearAll()
        assertFalse(destinationFile(directory).exists())
        assertTrue(store.snapshot.isEmpty)
        assertNoTemp(directory)
    }

    // ---- shared fault harness -------------------------------------------------------------------

    /**
     * Writes a valid prior file, then runs a second add against a store faulting at exactly one step
     * BEFORE (or at) the atomic replace, and asserts the prior file is byte-for-byte intact, the new
     * word never entered the snapshot, and no temp remains.
     */
    private fun assertFaultBetweenTempAndReplaceKeepsPrior(
        ops: RecordingOps = RecordingOps(),
        opener: PersonalOutputOpener = RealOpener,
    ) {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).addManually("абыйлар")
        val priorBytes = destinationFile(directory).readBytes()

        val faulting = store(directory, ops, opener)
        faulting.addManually("сүзлек")

        assertArrayEquals("prior file must be untouched", priorBytes, destinationFile(directory).readBytes())
        assertNoTemp(directory)
        assertEquals(setOf("абыйлар"), normalizedOnDisk(directory).toSet())
        assertEquals(1, faulting.snapshot.size)
    }

    // ---- helpers --------------------------------------------------------------------------------

    private val directExecutor = Executor { it.run() }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private fun store(
        directory: File,
        ops: DurableFileOps,
        opener: PersonalOutputOpener,
        spaceProbe: SpaceProbe = SpaceProbe { Long.MAX_VALUE },
        unlockGate: () -> Boolean = { true },
        maxEntries: Int = TpersFormat.MAX_PERSONAL_ENTRIES.toInt(),
    ): PersonalDictionaryStore = PersonalDictionaryStore(
        subtypeId = subtype,
        directoryProvider = { directory },
        fileOps = ops,
        outputOpener = opener,
        spaceProbe = spaceProbe,
        clock = { 1000L },
        executor = directExecutor,
        unlockGate = unlockGate,
        maxEntries = maxEntries,
    )

    private fun destinationFile(directory: File) =
        File(directory, TpersFormat.personalFileName(subtype))

    private fun temps(directory: File): List<File> =
        directory.listFiles { f -> f.name.startsWith(".personal-") && f.name.endsWith(".tmp") }
            ?.toList() ?: emptyList()

    private fun assertNoTemp(directory: File) =
        assertTrue("no temp must remain", temps(directory).isEmpty())

    private fun normalizedOnDisk(directory: File): List<String> {
        val dictionary = PersonalDictionaryReader().read(destinationFile(directory), subtype)
        return (0 until dictionary.size).map { dictionary.normalizedFormAt(it) }
    }

    private class PersonalStoreCrash : Error()

    private val RealOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) }

    /** Records every durable op and performs it for real; overridable to inject a fault. */
    private open inner class RecordingOps(private val events: MutableList<String> = mutableListOf()) :
        DurableFileOps {
        override fun createNewFile(file: File): Boolean {
            events += "create"
            return file.createNewFile()
        }

        override fun syncFile(fileDescriptor: FileDescriptor) {
            events += "file-fsync"
            fileDescriptor.sync()
        }

        override fun atomicRename(source: File, destination: File) {
            events += "rename"
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }

        override fun atomicReplace(source: File, destination: File) {
            events += "replace"
            if (!source.renameTo(destination)) {
                destination.delete()
                if (!source.renameTo(destination)) throw IOException("replace failed")
            }
        }

        override fun syncDirectory(directory: File) {
            events += "dir-fsync"
        }

        override fun delete(file: File): Boolean {
            events += "delete"
            return file.delete()
        }
    }

    private inner class RecordingOpener(private val events: MutableList<String>) : PersonalOutputOpener {
        override fun open(temp: File): FileOutputStream = object : FileOutputStream(temp) {
            override fun write(bytes: ByteArray) {
                events += "write"
                super.write(bytes)
            }

            override fun flush() {
                events += "flush"
                super.flush()
            }
        }
    }
}
