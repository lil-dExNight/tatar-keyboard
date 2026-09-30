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
import java.io.RandomAccessFile
import java.util.concurrent.Executor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/**
 * `readPending` rejects a pending file larger than [PendingCounters.MAX_SERIALIZED_BYTES] (a
 * 20-byte header plus at most MAX_PENDING 14-byte records) from `File.length()` alone, before
 * anything is read or allocated. Past 2 GiB `readBytes()` throws `OutOfMemoryError`, an `Error`
 * the store's `catch (Exception)` cannot stop, on the worker thread the keyboard shares.
 */
class PersonalPendingFileSizeCapTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    /** The positive control: a file the writer itself produced is under the cap and IS read. */
    @Test
    fun aPendingFileUnderTheCapIsReadAcrossSessions() {
        val directory = newPersonalDir()
        val first = store(directory)
        first.noteCompletion("сүзләр")
        first.noteCompletion("сүзләр")
        first.flush()

        val pendingFile = pendingFile(directory)
        assertTrue("two completions below the threshold leave a pending file", pendingFile.isFile)
        assertTrue(
            "the writer never produces a file past the cap",
            pendingFile.length() <= PendingCounters.MAX_SERIALIZED_BYTES,
        )

        // A new session reads the counters back: the third completion is the one that graduates.
        val second = store(directory)
        second.prime()
        second.noteCompletion("сүзләр")
        assertTrue(
            "progress survived the restart, so the file was read",
            second.snapshot.indexOfNormalized("сүзләр") >= 0,
        )
    }

    /**
     * A 3 GiB pending file, larger than any byte array, is rejected from its length alone. Reading
     * it would throw `OutOfMemoryError` on the direct executor, i.e. in the test thread, so the
     * test passing shows that no read and no allocation happened.
     */
    @Test
    fun anOversizedPendingFileIsRejectedWithoutBeingRead() {
        val directory = newPersonalDir()
        val pendingFile = pendingFile(directory)
        RandomAccessFile(pendingFile, "rw").use { it.setLength(THREE_GIB) }

        val store = store(directory)
        store.prime()
        assertTrue(store.snapshot.isEmpty)

        // Rejected means EMPTY, not broken: learning starts from scratch and still works.
        store.noteCompletion("сүзләр")
        store.noteCompletion("сүзләр")
        store.noteCompletion("сүзләр")
        assertTrue(
            "three completions learn the word even with the garbage file in place",
            store.snapshot.indexOfNormalized("сүзләр") >= 0,
        )

        // And the next flush replaces the garbage with a valid small file.
        store.flush()
        assertTrue(pendingFile.length() <= PendingCounters.MAX_SERIALIZED_BYTES)
    }

    /** Exactly at the cap the file is still a read candidate (rejected by parse, not by length). */
    @Test
    fun aFileExactlyAtTheCapIsNotRejectedByLength() {
        val directory = newPersonalDir()
        val pendingFile = pendingFile(directory)
        RandomAccessFile(pendingFile, "rw").use {
            it.setLength(PendingCounters.MAX_SERIALIZED_BYTES.toLong())
        }

        val store = store(directory)
        store.prime()
        assertTrue("zero-filled content fails parse and fails closed", store.snapshot.isEmpty)
        store.noteCompletion("сүзләр")
        store.noteCompletion("сүзләр")
        store.noteCompletion("сүзләр")
        assertTrue(store.snapshot.indexOfNormalized("сүзләр") >= 0)
    }

    // ---- helpers (the harness PersonalDictionarySilentFailureTest already uses) ---------------

    private val directExecutor = Executor { it.run() }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private fun store(directory: File): PersonalDictionaryStore = PersonalDictionaryStore(
        subtypeId = subtype,
        directoryProvider = { directory },
        fileOps = PassthroughOps(),
        outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
        spaceProbe = SpaceProbe { Long.MAX_VALUE },
        clock = { 1000L },
        executor = directExecutor,
        unlockGate = { true },
    )

    /** The pending file's name is the store's private format detail; the test pins it. */
    private fun pendingFile(directory: File) = File(directory, "pending-$subtype-s1-f1.bin")

    private companion object {
        const val THREE_GIB = 3L * 1024 * 1024 * 1024
    }

    /** Performs every durable op for real; no failure injection needed in these tests. */
    private class PassthroughOps : DurableFileOps {
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

        override fun syncDirectory(directory: File) = Unit

        override fun delete(file: File): Boolean = file.delete()
    }
}
