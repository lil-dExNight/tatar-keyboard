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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.RefusedCorrections
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TrefFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Write-path contract of the refused-corrections store: the threshold crossing persisted across a
 * reload, the per-language separation, the cap eviction on disk, the unlock gate, the move-aside of
 * an unreadable file, the erasure and the backup restore. Plain JVM, modelled on
 * [TextShortcutStoreTest].
 */
class RefusedCorrectionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // ---- the threshold and the persistence -------------------------------------------------------

    @Test
    fun theThresholdCrossingIsPersistedAcrossAReload() {
        val directory = newPersonalDir()
        val store = store(directory)
        store.noteRefusal("китәп", "китап")
        assertFalse("one refusal is not yet a memory", store.snapshot.isRefused("китәп", "китап"))
        assertTrue("the first refusal is already durable", destinationFile(directory).isFile)

        val reloaded = reopenedStore(directory)
        assertFalse(reloaded.snapshot.isRefused("китәп", "китап"))
        reloaded.noteRefusal("китәп", "китап")
        assertTrue("the second refusal crosses the threshold", reloaded.snapshot.isRefused("китәп", "китап"))

        assertTrue("the refusal survives another process",
            reopenedStore(directory).snapshot.isRefused("китәп", "китап"))
    }

    @Test
    fun anAlreadySuppressedPairCostsNoFurtherWrite() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(RefusedCorrections.REFUSAL_THRESHOLD) { store.noteRefusal("китәп", "китап") }
        val writes = store.writeCount

        store.noteRefusal("китәп", "китап")

        assertEquals(writes, store.writeCount)
    }

    @Test
    fun aRefusalIsRecordedInItsOwnLanguageOnly() {
        val directory = newPersonalDir()
        val tatar = store(directory, PersonalSubtypes.TATAR_RU)
        repeat(RefusedCorrections.REFUSAL_THRESHOLD) { tatar.noteRefusal("китәп", "китап") }

        assertTrue(tatar.snapshot.isRefused("китәп", "китап"))
        assertFalse("the Russian store never heard of it",
            reopenedStore(directory, PersonalSubtypes.RUSSIAN).snapshot.isRefused("китәп", "китап"))
    }

    @Test
    fun pastTheCapTheOldestRefusalIsEvictedOnDiskToo() {
        val directory = newPersonalDir()
        val store = store(directory, maxEntries = 2)
        repeat(RefusedCorrections.REFUSAL_THRESHOLD) { store.noteRefusal("бала", "бәлә") }
        repeat(RefusedCorrections.REFUSAL_THRESHOLD) { store.noteRefusal("китәп", "китап") }
        repeat(RefusedCorrections.REFUSAL_THRESHOLD) { store.noteRefusal("дөнья", "донъя") }

        val reopened = reopenedStore(directory)
        assertEquals(2, reopened.snapshot.size)
        assertFalse("the oldest refusal went first", reopened.snapshot.isRefused("бала", "бәлә"))
        assertTrue(reopened.snapshot.isRefused("китәп", "китап"))
        assertTrue(reopened.snapshot.isRefused("дөнья", "донъя"))
    }

    // ---- gates and failures -----------------------------------------------------------------------

    @Test
    fun malformedPairsAreNeverStoredNorCreateAFile() {
        val directory = newPersonalDir()
        val store = store(directory)
        store.noteRefusal("Китәп", "китап") // not normalized
        store.noteRefusal("китәп", "кит әп") // whitespace inside
        store.noteRefusal("", "китап")
        store.noteRefusal("китәп", "")

        assertTrue(store.snapshot.isEmpty)
        assertFalse(destinationFile(directory).exists())
        assertNoTemp(directory)
    }

    @Test
    fun aSubtypeWithoutAPersonalStoreNeverWrites() {
        val directory = newPersonalDir()
        val store = store(directory, "en_US")
        repeat(3) { store.noteRefusal("teh", "the") }

        assertTrue(store.snapshot.isEmpty)
        assertFalse(File(directory, TrefFormat.refusedCorrectionsFileName("en_US")).exists())
    }

    @Test
    fun aLockedDeviceSessionNeitherReadsNorWrites() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(RefusedCorrections.REFUSAL_THRESHOLD) { store.noteRefusal("китәп", "китап") }
        val priorBytes = destinationFile(directory).readBytes()

        val locked = store(directory, unlockGate = { false })
        locked.prime()
        repeat(5) { locked.noteRefusal("бала", "бәлә") }

        assertArrayEquals(priorBytes, destinationFile(directory).readBytes())
        assertTrue("the locked store never publishes a snapshot", locked.snapshot.isEmpty)
    }

    @Test
    fun anUnreadableFileIsMovedAsideAndTheFeatureStartsEmpty() {
        val directory = newPersonalDir()
        store(directory).noteRefusal("китәп", "китап")
        val destination = destinationFile(directory)
        val bytes = destination.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x7f).toByte()
        destination.writeBytes(bytes)

        val store = store(directory).apply { prime() }
        assertTrue(store.snapshot.isEmpty)
        assertFalse(destination.exists())
        assertTrue("the unreadable file is kept aside, not destroyed",
            File(directory, TrefFormat.refusedCorrectionsFileName(PersonalSubtypes.TATAR_RU) + ".quarantine").isFile)

        // Ordinary input keeps working: a fresh valid file is written.
        store.noteRefusal("бала", "бәлә")
        assertEquals(1, reopenedStore(directory).snapshot.size)
    }

    // ---- erasure and restore ----------------------------------------------------------------------

    @Test
    fun clearAllDeletesTheFileAndTheQuarantinedCopy() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(RefusedCorrections.REFUSAL_THRESHOLD) { store.noteRefusal("китәп", "китап") }
        // A quarantined copy from an earlier loss, holding the same kind of data.
        val quarantine = File(directory,
            TrefFormat.refusedCorrectionsFileName(PersonalSubtypes.TATAR_RU) + ".quarantine")
        quarantine.writeBytes(ByteArray(100) { 7 })
        assertTrue(destinationFile(directory).isFile)

        val outcomes = mutableListOf<Boolean>()
        store.clearAll { outcomes.add(it) }

        assertEquals(listOf(true), outcomes)
        assertTrue(store.snapshot.isEmpty)
        assertFalse(destinationFile(directory).exists())
        assertFalse(quarantine.exists())
    }

    @Test
    fun clearAllOnALockedDeviceReportsFailureAndKeepsTheFile() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(RefusedCorrections.REFUSAL_THRESHOLD) { store.noteRefusal("китәп", "китап") }

        val outcomes = mutableListOf<Boolean>()
        store(directory, unlockGate = { false }).clearAll { outcomes.add(it) }

        assertEquals(listOf(false), outcomes)
        assertTrue(destinationFile(directory).isFile)
    }

    @Test
    fun replaceAllSwapsTheFileAndPublishesIt() {
        val directory = newPersonalDir()
        val store = store(directory)
        store.noteRefusal("китәп", "китап")
        val imported = RefusedCorrections.EMPTY
            .noting("бала", "бәлә", 500)
            .noting("бала", "бәлә", 500)
            .serialize(PersonalSubtypes.TATAR_RU)

        val outcomes = mutableListOf<Boolean>()
        store.replaceAll(imported) { outcomes.add(it) }

        assertEquals(listOf(true), outcomes)
        assertTrue(store.snapshot.isRefused("бала", "бәлә"))
        assertFalse(store.snapshot.isRefused("китәп", "китап"))
        assertEquals(imported.toList(), destinationFile(directory).readBytes().toList())
    }

    @Test
    fun replaceAllWithInvalidBytesWritesNothing() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(RefusedCorrections.REFUSAL_THRESHOLD) { store.noteRefusal("китәп", "китап") }
        val priorBytes = destinationFile(directory).readBytes()

        val outcomes = mutableListOf<Boolean>()
        store.replaceAll(ByteArray(100) { 3 }) { outcomes.add(it) }

        assertEquals(listOf(false), outcomes)
        assertArrayEquals(priorBytes, destinationFile(directory).readBytes())
        assertTrue(store.snapshot.isRefused("китәп", "китап"))
    }

    @Test
    fun replaceAllWithNullDeletesTheFile() {
        val directory = newPersonalDir()
        val store = store(directory)
        repeat(RefusedCorrections.REFUSAL_THRESHOLD) { store.noteRefusal("китәп", "китап") }

        val outcomes = mutableListOf<Boolean>()
        store.replaceAll(null) { outcomes.add(it) }

        assertEquals(listOf(true), outcomes)
        assertFalse(destinationFile(directory).exists())
        assertTrue(store.snapshot.isEmpty)
    }

    // ---- the durable write sequence ---------------------------------------------------------------

    @Test
    fun wholeFileWriteFollowsTheContractSequence() {
        val directory = newPersonalDir()
        val events = mutableListOf<String>()
        store(directory, ops = RecordingOps(events), opener = RecordingOpener(events))
            .noteRefusal("китәп", "китап")

        assertEquals(
            listOf("create", "write", "flush", "file-fsync", "replace", "dir-fsync"),
            events.toList(),
        )
    }

    @Test
    fun aFaultBeforeTheReplaceKeepsThePriorFileIntactAndLeavesNoTemp() {
        val directory = newPersonalDir()
        store(directory).noteRefusal("китәп", "китап")
        val priorBytes = destinationFile(directory).readBytes()

        val failing = store(directory, opener = PersonalOutputOpener { temp ->
            object : FileOutputStream(temp) {
                override fun write(bytes: ByteArray) = throw IOException("write failed")
            }
        })
        failing.noteRefusal("бала", "бәлә")

        assertArrayEquals("prior file must be untouched", priorBytes,
            destinationFile(directory).readBytes())
        assertNoTemp(directory)
        assertEquals("the failed mutation is dropped, not half-kept",
            1, failing.snapshot.size)
    }

    @Test
    fun noFreeSpaceDropsTheChangeAndKeepsThePriorFile() {
        val directory = newPersonalDir()
        store(directory).noteRefusal("китәп", "китап")
        val priorBytes = destinationFile(directory).readBytes()

        store(directory, spaceProbe = SpaceProbe { 0 }).noteRefusal("бала", "бәлә")

        assertArrayEquals(priorBytes, destinationFile(directory).readBytes())
        assertNoTemp(directory)
    }

    // ---- helpers --------------------------------------------------------------------------------

    private val directExecutor = Executor { it.run() }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    /** A fresh store over [directory], opened (the read the worker would do) before it answers. */
    private fun reopenedStore(
        directory: File,
        subtypeId: String = PersonalSubtypes.TATAR_RU,
    ): RefusedCorrectionStore = store(directory, subtypeId).apply { prime() }

    private fun store(
        directory: File,
        subtypeId: String = PersonalSubtypes.TATAR_RU,
        ops: DurableFileOps = RecordingOps(),
        opener: PersonalOutputOpener = RealOpener,
        spaceProbe: SpaceProbe = SpaceProbe { Long.MAX_VALUE },
        unlockGate: () -> Boolean = { true },
        maxEntries: Int = TrefFormat.MAX_REFUSED_ENTRIES.toInt(),
    ): RefusedCorrectionStore = RefusedCorrectionStore(
        subtypeId = subtypeId,
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
        File(directory, TrefFormat.refusedCorrectionsFileName(PersonalSubtypes.TATAR_RU))

    private fun temps(directory: File): List<File> =
        directory.listFiles { f -> f.name.startsWith(".personal-") && f.name.endsWith(".tmp") }
            ?.toList() ?: emptyList()

    private fun assertNoTemp(directory: File) =
        assertTrue("no temp must remain", temps(directory).isEmpty())

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
