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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TcutFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TcutValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Write-path contract of the text-shortcut store: the durable whole-file write sequence with a
 * fault injected at the decisive step, the round trip through a fresh store, the removal rules
 * (published before the write, the file deleted with the last pair), the unlock gate, the
 * move-aside of an unreadable file and the capacity refusal. Plain JVM.
 */
class TextShortcutStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // ---- round trip -----------------------------------------------------------------------------

    @Test
    fun pairsRoundTripThroughAFreshStore() {
        val directory = newPersonalDir()
        val store = store(directory)
        store.put("тк", "Татарстан Республикасы")
        store.put("бик", "бик үк")

        val reopened = reopenedStore(directory)
        assertEquals(2, reopened.snapshot.size)
        assertEquals("Татарстан Республикасы", reopened.snapshot.expansionFor("тк"))
        assertEquals("бик үк", reopened.snapshot.expansionFor("бик"))
    }

    @Test
    fun putWithAnExistingShortcutReplacesItsExpansion() {
        val directory = newPersonalDir()
        val store = store(directory)
        store.put("тк", "Татарстан")
        store.put("тк", "Татарстан Республикасы")

        assertEquals(1, store.snapshot.size)
        assertEquals("Татарстан Республикасы", reopenedStore(directory).snapshot.expansionFor("тк"))
    }

    @Test
    fun theShortcutIsMatchedExactlyWithNfcFoldingAndKeptCasing() {
        val directory = newPersonalDir()
        val store = store(directory)
        // Stored NFC; looked up from a canonically decomposed (NFD) spelling of the same word
        // ("й" as "и" + U+0306).
        store.put("әй", "әйдә")

        assertEquals("әйдә", store.snapshot.expansionFor("әй"))
        assertNull("casing is the user's own: a different case is not the shortcut",
            store.snapshot.expansionFor("Әй"))
        assertNull("a longer typed word is not the shortcut",
            store.snapshot.expansionFor("әйдә"))
    }

    // ---- removal ---------------------------------------------------------------------------------

    @Test
    fun removeRewritesTheFileAndDeletesItWithTheLastPair() {
        val directory = newPersonalDir()
        val store = store(directory)
        store.put("тк", "Татарстан")
        store.put("бик", "бик үк")
        assertTrue(destinationFile(directory).isFile)

        store.remove("тк")
        assertNull(store.snapshot.expansionFor("тк"))
        assertEquals("бик үк", reopenedStore(directory).snapshot.expansionFor("бик"))

        store.remove("бик")
        assertTrue(store.snapshot.isEmpty)
        assertFalse(destinationFile(directory).exists())
    }

    @Test
    fun removeOfAnUnknownShortcutIsANoOpThatAnswersTrue() {
        val directory = newPersonalDir()
        val store = store(directory)
        store.put("тк", "Татарстан")

        val outcomes = mutableListOf<Boolean>()
        store.remove("мк") { outcomes.add(it) }

        assertEquals(listOf(true), outcomes)
        assertEquals("Татарстан", store.snapshot.expansionFor("тк"))
    }

    @Test
    fun removeIsPublishedBeforeTheWriteAndRestoredWhenItFails() {
        val directory = newPersonalDir()
        val first = store(directory)
        first.put("тк", "Татарстан")
        first.put("бик", "бик үк")

        val failing = store(directory, ops = object : RecordingOps() {
            override fun atomicReplace(source: File, destination: File) =
                throw IOException("replace failed")
        })
        val outcomes = mutableListOf<Boolean>()
        failing.remove("тк") { outcomes.add(it) }

        // The write failed, so the pair is still saved and the snapshot is restored.
        assertEquals(listOf(false), outcomes)
        assertEquals("Татарстан", failing.snapshot.expansionFor("тк"))
        assertEquals("Татарстан", reopenedStore(directory).snapshot.expansionFor("тк"))
        assertEquals(2, reopenedStore(directory).snapshot.size)
    }

    // ---- the durable write sequence --------------------------------------------------------------

    @Test
    fun wholeFileWriteFollowsTheContractSequence() {
        val directory = newPersonalDir()
        val events = mutableListOf<String>()
        store(directory, ops = RecordingOps(events), opener = RecordingOpener(events))
            .put("тк", "Татарстан")

        assertEquals(
            listOf("create", "write", "flush", "file-fsync", "replace", "dir-fsync"),
            events.toList(),
        )
    }

    @Test
    fun aFaultBeforeTheReplaceKeepsThePriorFileIntactAndLeavesNoTemp() {
        val directory = newPersonalDir()
        store(directory).put("тк", "Татарстан")
        val priorBytes = destinationFile(directory).readBytes()

        val failing = store(directory, opener = PersonalOutputOpener { temp ->
            object : FileOutputStream(temp) {
                override fun write(bytes: ByteArray) = throw IOException("write failed")
            }
        })
        failing.put("бик", "бик үк")

        assertArrayEquals("prior file must be untouched", priorBytes,
            destinationFile(directory).readBytes())
        assertNoTemp(directory)
        assertEquals(1, failing.snapshot.size)
    }

    @Test
    fun noFreeSpaceDropsTheChangeAndKeepsThePriorFile() {
        val directory = newPersonalDir()
        store(directory).put("тк", "Татарстан")
        val priorBytes = destinationFile(directory).readBytes()

        store(directory, spaceProbe = SpaceProbe { 0 }).put("бик", "бик үк")

        assertArrayEquals(priorBytes, destinationFile(directory).readBytes())
        assertNoTemp(directory)
    }

    // ---- gates, capacity, quarantine --------------------------------------------------------------

    @Test
    fun ineligibleInputIsNeverStoredNorDoesItCreateAFile() {
        val badPairs = listOf(
            "т к" to "whitespace in the shortcut",
            "тк2" to "a digit can never be typed as one word",
            "" to "empty shortcut",
            "тк" to "",
            "тк" to "   ",
            "тк" to "line\nbreak",
            "тк".repeat(33) to "over the shortcut cap",
            "тк" to "а".repeat(141),
        )
        for ((shortcut, expansion) in badPairs) {
            val directory = newPersonalDir()
            val store = store(directory)
            val outcomes = mutableListOf<Boolean>()
            store.put(shortcut, expansion) { outcomes.add(it) }
            assertEquals("[$shortcut] must be refused", listOf(false), outcomes)
            assertFalse(destinationFile(directory).isFile)
            assertTrue(store.snapshot.isEmpty)
            assertNoTemp(directory)
        }
    }

    @Test
    fun theStoreRefusesToGrowPastItsCapacity() {
        val directory = newPersonalDir()
        val store = store(directory, maxEntries = 2)
        store.put("бк", "бер")
        store.put("ик", "ике")

        val outcomes = mutableListOf<Boolean>()
        store.put("өч", "өч") { outcomes.add(it) }

        assertEquals(listOf(false), outcomes)
        assertEquals(2, reopenedStore(directory).snapshot.size)
        // Replacing an existing pair is not growth and still works.
        store.put("бк", "бер үк")
        assertEquals("бер үк", reopenedStore(directory).snapshot.expansionFor("бк"))
    }

    @Test
    fun lockedDeviceSessionNeverTouchesTheExistingFile() {
        val directory = newPersonalDir()
        store(directory).put("тк", "Татарстан")
        val priorBytes = destinationFile(directory).readBytes()

        val locked = store(directory, unlockGate = { false })
        repeat(5) { locked.put("бик", "бик үк") }
        locked.remove("тк")

        assertArrayEquals(priorBytes, destinationFile(directory).readBytes())
        assertTrue("the locked store never publishes a snapshot", locked.snapshot.isEmpty)
    }

    @Test
    fun anUnreadableFileIsMovedAsideAndTheFeatureStartsEmpty() {
        val directory = newPersonalDir()
        store(directory).put("тк", "Татарстан")
        val destination = destinationFile(directory)
        val bytes = destination.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x7f).toByte()
        destination.writeBytes(bytes)

        val store = store(directory).apply { prime() }
        assertTrue(store.snapshot.isEmpty)
        assertFalse(destination.exists())
        assertTrue("the unreadable file is kept aside, not destroyed",
            File(directory, TcutFormat.shortcutsFileName() + ".quarantine").isFile)

        // Ordinary input keeps working: a fresh valid file is written.
        store.put("бик", "бик үк")
        assertEquals("бик үк", reopenedStore(directory).snapshot.expansionFor("бик"))
    }

    // ---- helpers --------------------------------------------------------------------------------

    private val directExecutor = Executor { it.run() }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    /** A fresh store over [directory], opened (the read the worker would do) before it answers. */
    private fun reopenedStore(directory: File): TextShortcutStore =
        store(directory).apply { prime() }

    private fun store(
        directory: File,
        ops: DurableFileOps = RecordingOps(),
        opener: PersonalOutputOpener = RealOpener,
        spaceProbe: SpaceProbe = SpaceProbe { Long.MAX_VALUE },
        unlockGate: () -> Boolean = { true },
        maxEntries: Int = TcutFormat.MAX_SHORTCUT_ENTRIES.toInt(),
    ): TextShortcutStore = TextShortcutStore(
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
        File(directory, TcutFormat.shortcutsFileName())

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
