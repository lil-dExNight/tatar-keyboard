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

package rkr.simplekeyboard.inputmethod.latin.settings.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import java.io.File
import java.io.IOException

/**
 * The journal's durable home under fsync failures. The review finding: the old cleanup deleted
 * the journal before its directory fsync, so a failed fsync swept the rollback evidence while the
 * imported state stayed. The commit record is now durable before any journal byte is swept, and
 * these rows pin each failure boundary: the exact bytes either remain pending rollback evidence
 * or are covered by a durable commit — never silently dropped by a failed cleanup.
 */
class BackupImportJournalFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val journalBytes = ByteArray(1024) { (it * 31 + 7).toByte() }

    private fun journalFile(directory: File) =
        File(directory, BackupImportJournalFiles.JOURNAL_FILE_NAME)

    private fun commitFile(directory: File) =
        File(directory, BackupImportJournalFiles.COMMIT_FILE_NAME)

    @Test
    fun aWriteSurvivesAReadThroughTheSameDirectory() {
        val directory = temporaryFolder.newFolder("write-read")
        val files = BackupImportJournalFiles(directory, BackupTestStores.realOps)
        assertNull(files.read())
        assertTrue(files.write(journalBytes))
        assertArrayEquals(journalBytes, files.read())
    }

    @Test
    fun aDirectoryFsyncFailureWhileRecordingTheCommitKeepsTheExactJournal() {
        val directory = temporaryFolder.newFolder("commit-record-fsync")
        val ops = FailingSync()
        val files = BackupImportJournalFiles(directory, ops)
        assertTrue(files.write(journalBytes))

        ops.failSyncCall(1) // the record's own directory fsync
        assertFalse("no durable commit record exists", files.commit())

        assertArrayEquals("the pending journal keeps its exact rollback bytes",
            journalBytes, journalFile(directory).readBytes())
        assertFalse("the unusable record is not left to mislead a later recovery",
            commitFile(directory).exists())
        val recovering = BackupImportJournalFiles(directory, BackupTestStores.realOps)
        assertArrayEquals(journalBytes, recovering.read())
    }

    @Test
    fun aDeletionThenDirectoryFsyncFailureNeverCostsACommittedImport() {
        val directory = temporaryFolder.newFolder("deletion-then-fsync")
        val ops = FailingSync()
        val files = BackupImportJournalFiles(directory, ops)
        assertTrue(files.write(journalBytes))

        ops.failSyncCall(2) // the sweep's directory fsync after the deletion
        assertTrue("the commit record was durable before the delete", files.commit())
        assertFalse("the deletion reached the namespace", journalFile(directory).exists())
        assertTrue("the durable record outlives the unfinished sweep",
            commitFile(directory).exists())

        // A crash may resurrect the never-fsynced deletion; the record decides, not the journal.
        journalFile(directory).writeBytes(journalBytes)
        val recovering = BackupImportJournalFiles(directory, BackupTestStores.realOps)
        assertNull("a committed journal is never rolled back", recovering.read())
        assertFalse(journalFile(directory).exists())
        assertFalse(commitFile(directory).exists())
    }

    @Test
    fun aRecordKeptByAFailingSweepDoesNotHideAPendingJournal() {
        val directory = temporaryFolder.newFolder("stale-record")
        val recordKept = FailingSync(failDeleteName = BackupImportJournalFiles.COMMIT_FILE_NAME)
        val first = BackupImportJournalFiles(directory, recordKept)
        assertTrue(first.write(journalBytes))
        assertTrue(first.commit())
        assertTrue("the stale record survives a cut-short sweep", commitFile(directory).exists())

        val laterBytes = ByteArray(64) { it.toByte() }
        assertTrue(first.write(laterBytes))
        val recovering = BackupImportJournalFiles(directory, BackupTestStores.realOps)
        assertArrayEquals("a mismatched record cannot stand over the pending journal",
            laterBytes, recovering.read())
        assertFalse("the stale record is swept with the pending journal's read",
            commitFile(directory).exists())
    }

    @Test
    fun aCorruptCommitRecordFailsClosedAndKeepsEveryByte() {
        val directory = temporaryFolder.newFolder("corrupt-record")
        val files = BackupImportJournalFiles(directory, BackupTestStores.realOps)
        assertTrue(files.write(journalBytes))
        val damagedRecord = byteArrayOf(9, 9, 9)
        commitFile(directory).writeBytes(damagedRecord)

        val failure = runCatching { files.read() }.exceptionOrNull()
        assertTrue("a damaged record is filesystem damage, not proof of a commit",
            failure is IOException)
        assertArrayEquals("the rollback evidence is untouched",
            journalBytes, journalFile(directory).readBytes())
        assertArrayEquals("the damaged record is untouched",
            damagedRecord, commitFile(directory).readBytes())
    }

    @Test
    fun clearSweepsEveryJournalFileAfterACommit() {
        val directory = temporaryFolder.newFolder("clear")
        val files = BackupImportJournalFiles(directory, BackupTestStores.realOps)
        assertTrue(files.write(journalBytes))
        assertTrue(files.commit())
        File(directory, "${BackupImportJournalFiles.JOURNAL_FILE_NAME}.tmp").writeBytes(byteArrayOf(1))
        assertTrue(files.clear())
        assertEquals("nothing of the journal remains", emptyList<String>(),
            directory.list().orEmpty().toList())
    }

    /** Real file operations, except one chosen directory fsync or named deletion fails. */
    private class FailingSync(
        private val failDeleteName: String? = null,
    ) : DurableFileOps by BackupTestStores.realOps {
        private var failAtSyncCall = 0
        private var syncCalls = 0

        /** Fails the [offset]-th directory fsync from now. */
        fun failSyncCall(offset: Int) {
            failAtSyncCall = syncCalls + offset
        }

        override fun syncDirectory(directory: File) {
            syncCalls++
            if (syncCalls == failAtSyncCall) throw IOException("injected directory fsync failure")
            BackupTestStores.realOps.syncDirectory(directory)
        }

        override fun delete(file: File): Boolean =
            if (file.name == failDeleteName) false else BackupTestStores.realOps.delete(file)
    }
}
