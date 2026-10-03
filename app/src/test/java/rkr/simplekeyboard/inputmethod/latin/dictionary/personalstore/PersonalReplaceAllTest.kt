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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbFormat
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps

/**
 * The backup-restore mutation of the personal stores (`replaceAll`): a valid file swaps the store
 * whole — on disk and in the published snapshot, live — null deletes, and invalid bytes write
 * nothing and keep the old snapshot. Also pinned: in-flight learning progress (the pending
 * counters) survives a restore.
 */
class PersonalReplaceAllTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    @Test
    fun replacePublishesTheImportedContentLive() {
        val directory = newPersonalDir()
        val sourceDirectory = newPersonalDir()
        wordStore(sourceDirectory).addManually("абыйлар")
        val imported = File(sourceDirectory, TpersFormat.personalFileName(subtype)).readBytes()

        val store = wordStore(directory)
        store.addManually("сүзлек")

        var outcome = false
        store.replaceAll(imported) { outcome = it }
        assertTrue(outcome)
        assertEquals(listOf("абыйлар"), store.snapshot.lookupRawForms("а"))
        assertTrue("the previous content is gone from the snapshot",
            store.snapshot.lookupRawForms("с").isEmpty())
        assertArrayEquals(imported, File(directory, TpersFormat.personalFileName(subtype)).readBytes())
    }

    @Test
    fun replaceWithNullDeletesTheFileAndEmptiesTheSnapshot() {
        val directory = newPersonalDir()
        val store = wordStore(directory)
        store.addManually("абыйлар")
        assertTrue(File(directory, TpersFormat.personalFileName(subtype)).isFile)

        var outcome = false
        store.replaceAll(null) { outcome = it }
        assertTrue(outcome)
        assertFalse(File(directory, TpersFormat.personalFileName(subtype)).exists())
        assertTrue(store.snapshot.isEmpty)
    }

    @Test
    fun invalidBytesWriteNothingAndKeepTheSnapshot() {
        val directory = newPersonalDir()
        val store = wordStore(directory)
        store.addManually("абыйлар")
        val before = File(directory, TpersFormat.personalFileName(subtype)).readBytes()

        var outcome = true
        store.replaceAll(ByteArray(128) { 0x55 }) { outcome = it }
        assertFalse("invalid bytes must be refused", outcome)
        assertArrayEquals(before, File(directory, TpersFormat.personalFileName(subtype)).readBytes())
        assertEquals(listOf("абыйлар"), store.snapshot.lookupRawForms("а"))
    }

    @Test
    fun bytesOfAnotherSubtypeAreRefused() {
        val directory = newPersonalDir()
        val russianDirectory = newPersonalDir()
        wordStore(russianDirectory, PersonalSubtypes.RUSSIAN).addManually("китап")
        val russianBytes = File(russianDirectory,
            TpersFormat.personalFileName(PersonalSubtypes.RUSSIAN)).readBytes()

        val store = wordStore(directory)
        var outcome = true
        store.replaceAll(russianBytes) { outcome = it }
        assertFalse("a file tagged with another subtype must be refused", outcome)
        assertFalse(File(directory, TpersFormat.personalFileName(subtype)).exists())
    }

    @Test
    fun learningProgressSurvivesARestore() {
        val directory = newPersonalDir()
        val store = wordStore(directory)
        // Two clean completions: below the learning threshold, so only the pending counters hold it.
        store.noteCompletion("китапханә")
        store.noteCompletion("китапханә")
        assertTrue(store.snapshot.isEmpty)

        var outcome = false
        store.replaceAll(null) { outcome = it }
        assertTrue(outcome)

        // The third completion still graduates: the restore did not wipe the in-flight progress.
        store.noteCompletion("китапханә")
        assertEquals(listOf("китапханә"), store.snapshot.lookupRawForms("к"))
    }

    @Test
    fun pairStoreReplaceSwapsTheContent() {
        val directory = newPersonalDir()
        val sourceDirectory = newPersonalDir()
        val source = pairStore(sourceDirectory)
        repeat(2) { source.notePair("бүген", "киләм") }
        val imported = File(sourceDirectory, TpersbFormat.personalBigramsFileName(subtype)).readBytes()

        val store = pairStore(directory)
        var outcome = false
        store.replaceAll(imported) { outcome = it }
        assertTrue(outcome)
        assertArrayEquals(imported,
            File(directory, TpersbFormat.personalBigramsFileName(subtype)).readBytes())
        assertFalse(store.snapshot.isEmpty)

        var deleteOutcome = false
        store.replaceAll(null) { deleteOutcome = it }
        assertTrue(deleteOutcome)
        assertTrue(store.snapshot.isEmpty)
        assertFalse(File(directory, TpersbFormat.personalBigramsFileName(subtype)).exists())
    }

    @Test
    fun emojiStoreReplaceSwapsTheContent() {
        val directory = newPersonalDir()
        val sourceDirectory = newPersonalDir()
        val source = emojiStore(sourceDirectory)
        repeat(2) { source.noteObservation("бәйрәм", "🎉") }
        val imported = File(sourceDirectory, TpersemFormat.personalEmojiFileName(subtype)).readBytes()

        val store = emojiStore(directory)
        var outcome = false
        store.replaceAll(imported) { outcome = it }
        assertTrue(outcome)
        assertArrayEquals(imported,
            File(directory, TpersemFormat.personalEmojiFileName(subtype)).readBytes())

        var invalidOutcome = true
        store.replaceAll(ByteArray(96) { 1 }) { invalidOutcome = it }
        assertFalse(invalidOutcome)
        assertArrayEquals(imported,
            File(directory, TpersemFormat.personalEmojiFileName(subtype)).readBytes())
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private fun wordStore(
        directory: File,
        subtypeId: String = subtype,
    ): PersonalDictionaryStore {
        return PersonalDictionaryStore(
            subtypeId = subtypeId,
            directoryProvider = { directory },
            fileOps = ops,
            outputOpener = opener,
            spaceProbe = { Long.MAX_VALUE },
            clock = { 1000L },
            executor = directExecutor,
        )
    }

    private fun pairStore(directory: File): PersonalBigramStore {
        return PersonalBigramStore(
            subtypeId = subtype,
            directoryProvider = { directory },
            fileOps = ops,
            outputOpener = opener,
            spaceProbe = { Long.MAX_VALUE },
            clock = { 1000L },
            executor = directExecutor,
            contextMembership = { _, _ -> true },
        )
    }

    private fun emojiStore(directory: File): PersonalEmojiStore {
        return PersonalEmojiStore(
            subtypeId = subtype,
            directoryProvider = { directory },
            fileOps = ops,
            outputOpener = opener,
            spaceProbe = { Long.MAX_VALUE },
            clock = { 1000L },
            executor = directExecutor,
        )
    }

    private val directExecutor = Executor { it.run() }

    private val opener = PersonalOutputOpener { temp -> FileOutputStream(temp) }

    private val ops = object : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()
        override fun syncFile(fileDescriptor: FileDescriptor) = fileDescriptor.sync()
        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }
        override fun atomicReplace(source: File, destination: File) {
            if (!source.renameTo(destination)) {
                destination.delete()
                if (!source.renameTo(destination)) throw IOException("replace failed")
            }
        }
        override fun syncDirectory(directory: File) {}
        override fun delete(file: File): Boolean = file.delete()
    }
}
