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

import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/** A verified interrupted import: the complete state before it and the requested state after it. */
internal class VerifiedImportJournal(
    val before: VerifiedBackup,
    val after: VerifiedBackup,
)

/**
 * Private on-disk journal for one import. Both states use the verified archive representation;
 * the outer header only frames them. This is not a user-carried archive and has its own magic.
 */
internal object BackupImportJournal {
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'K'.code.toByte(), 'B'.code.toByte(),
        'J'.code.toByte(), 1)
    private const val HEADER_BYTES = 9
    const val MAX_SERIALIZED_BYTES = 2 * BackupFormat.MAX_ARCHIVE_BYTES + HEADER_BYTES

    /** Validates the rollback snapshot and frames it with the verified requested archive. */
    fun create(versionCode: Int, before: VerifiedBackup, afterArchive: ByteArray): ByteArray {
        val beforeArchive = BackupArchive.create(versionCode, before.preferences, before.personalFiles)
        require(beforeArchive.size.toLong() <= BackupFormat.MAX_ARCHIVE_BYTES)
        BackupArchive.readAndVerifySnapshot(beforeArchive)
        require(afterArchive.size.toLong() <= BackupFormat.MAX_ARCHIVE_BYTES)
        val output = ByteBuffer.allocate(HEADER_BYTES + beforeArchive.size + afterArchive.size)
        output.put(MAGIC)
        output.putInt(beforeArchive.size)
        output.put(beforeArchive)
        output.put(afterArchive)
        return output.array()
    }

    /** Verifies both complete states before recovery is allowed to mutate anything. */
    fun read(bytes: ByteArray): VerifiedImportJournal {
        if (bytes.size < HEADER_BYTES || bytes.size.toLong() > MAX_SERIALIZED_BYTES) {
            fail("journal size is invalid")
        }
        val input = ByteBuffer.wrap(bytes)
        for (expected in MAGIC) {
            if (input.get() != expected) fail("journal identity is invalid")
        }
        val beforeSize = input.int
        if (beforeSize <= 0 || beforeSize.toLong() > BackupFormat.MAX_ARCHIVE_BYTES ||
                HEADER_BYTES.toLong() + beforeSize >= bytes.size.toLong()) {
            fail("journal frame is invalid")
        }
        val afterSize = bytes.size - HEADER_BYTES - beforeSize
        if (afterSize.toLong() > BackupFormat.MAX_ARCHIVE_BYTES) fail("journal frame is invalid")
        val beforeBytes = ByteArray(beforeSize)
        val afterBytes = ByteArray(afterSize)
        input.get(beforeBytes)
        input.get(afterBytes)
        return VerifiedImportJournal(
            BackupArchive.readAndVerifySnapshot(beforeBytes),
            BackupArchive.readAndVerify(afterBytes),
        )
    }

    private fun fail(message: String): Nothing = throw BackupValidationException(message)
}

/**
 * The journal's durable home in one private directory.
 *
 * The commit point is the [commitFile] record, which carries the SHA-256 of the exact journal
 * bytes. The journal is never deleted before that record is durable, so a cleanup whose directory
 * fsync fails cannot destroy the rollback evidence of an import that was not committed: the
 * journal either survives at its canonical name or is already covered by a durable commit. A
 * recovery rolls the pending journal back; a journal whose digest the commit record carries has
 * already committed, so only its files are swept.
 *
 * A temporary copy is only ever a write cut short before the first mutation, and a commit record
 * is only ever written by [commit]; both are always replaced atomically, so a damaged commit
 * record is filesystem damage and recovery refuses to guess rather than discard the journal.
 */
internal class BackupImportJournalFiles(
    private val directory: File,
    private val ops: DurableFileOps,
) {
    init {
        directory.mkdirs()
    }

    private val journalFile = File(directory, JOURNAL_FILE_NAME)
    private val temporaryFile = File(directory, "$JOURNAL_FILE_NAME.tmp")
    private val commitFile = File(directory, COMMIT_FILE_NAME)
    private val commitTemporaryFile = File(directory, "$COMMIT_FILE_NAME.tmp")

    /**
     * The journal a recovery must roll back, or null when none is pending. A journal covered by a
     * matching commit record is swept here and reported as none; a corrupt commit record throws.
     */
    fun read(): ByteArray? {
        if (!journalFile.exists() && !commitFile.exists()) {
            // A lone temporary copy never reached the journal and preceded no mutation.
            deleteQuietly(temporaryFile)
            return null
        }
        val committedDigest = readCommit()
        if (!journalFile.exists()) {
            if (committedDigest != null) finishCommitQuietly()
            return null
        }
        val bytes = readJournal()
        if (committedDigest != null) {
            if (committedDigest.contentEquals(sha256(bytes))) {
                finishCommitQuietly()
                return null
            }
            // A record left by an earlier import: the pending journal still needs its rollback.
            deleteQuietly(commitFile)
            syncDirectoryQuietly()
        }
        return bytes
    }

    /** Durably replaces the pending journal; false leaves the previous journal in place. */
    fun write(bytes: ByteArray): Boolean {
        if (bytes.size > BackupImportJournal.MAX_SERIALIZED_BYTES) return false
        return try {
            if (!delete(temporaryFile)) return false
            FileOutputStream(temporaryFile).use { output ->
                output.write(bytes)
                output.flush()
                ops.syncFile(output.fd)
            }
            ops.atomicReplace(temporaryFile, journalFile)
            ops.syncDirectory(directory)
            true
        } catch (_: Exception) {
            deleteQuietly(temporaryFile)
            false
        }
    }

    /**
     * Durably records the import as committed and then sweeps the journal. False means no durable
     * commit was recorded: the pending journal is untouched and still names the exact rollback.
     * Once true, a cleanup that fails cannot un-commit the import; the next recovery finishes it.
     */
    fun commit(): Boolean {
        val digest = try {
            sha256(readJournal())
        } catch (_: Exception) {
            return false
        }
        return try {
            writeCommit(digest)
            finishCommitQuietly()
            true
        } catch (_: Exception) {
            // Do not leave a maybe-durable record claiming a commit recovery must not make.
            deleteCommitQuietly()
            false
        }
    }

    /** Removes every journal byte after a completed rollback or an explicit erase. */
    fun clear(): Boolean = try {
        val removed = delete(journalFile) and delete(temporaryFile) and
            delete(commitFile) and delete(commitTemporaryFile)
        if (!removed) {
            false
        } else {
            ops.syncDirectory(directory)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun readJournal(): ByteArray {
        if (!journalFile.isFile || journalFile.length() > BackupImportJournal.MAX_SERIALIZED_BYTES) {
            throw IOException("cannot read import journal")
        }
        return journalFile.readBytes()
    }

    private fun readCommit(): ByteArray? {
        if (!commitFile.exists()) return null
        if (!commitFile.isFile || commitFile.length() != COMMIT_BYTES.toLong()) {
            throw IOException("the import commit record is damaged")
        }
        val bytes = commitFile.readBytes()
        if (!bytes.copyOfRange(0, COMMIT_MAGIC.size).contentEquals(COMMIT_MAGIC)) {
            throw IOException("the import commit record is damaged")
        }
        return bytes.copyOfRange(COMMIT_MAGIC.size, bytes.size)
    }

    private fun writeCommit(digest: ByteArray) {
        val bytes = ByteArray(COMMIT_BYTES)
        COMMIT_MAGIC.copyInto(bytes)
        digest.copyInto(bytes, COMMIT_MAGIC.size)
        try {
            FileOutputStream(commitTemporaryFile).use { output ->
                output.write(bytes)
                output.flush()
                ops.syncFile(output.fd)
            }
            ops.atomicReplace(commitTemporaryFile, commitFile)
            ops.syncDirectory(directory)
        } catch (error: Exception) {
            deleteQuietly(commitTemporaryFile)
            throw error
        }
    }

    /** Sweeps a committed journal; every step stays retryable because the record is durable. */
    private fun finishCommitQuietly() {
        try {
            if (!delete(journalFile) || !delete(temporaryFile)) return
            ops.syncDirectory(directory)
            // Only a durable journal removal makes the record disposable.
            deleteQuietly(commitFile)
            syncDirectoryQuietly()
        } catch (_: Exception) {
            // The record already proves the import stands; the next recovery sweeps again.
        }
    }

    private fun deleteCommitQuietly() {
        try {
            deleteQuietly(commitFile)
            ops.syncDirectory(directory)
        } catch (_: Exception) {
            // The journal still carries the rollback; recovery decides from both files.
        }
    }

    private fun delete(file: File): Boolean =
        !file.exists() || ops.delete(file) || !file.exists()

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists()) ops.delete(file)
        } catch (_: Exception) {
            // Best effort: the caller keeps the evidence it still has.
        }
    }

    private fun syncDirectoryQuietly() {
        try {
            ops.syncDirectory(directory)
        } catch (_: Exception) {
            // Durability of best-effort cleanup is retried, never assumed.
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        const val JOURNAL_FILE_NAME = "backup-import-journal.bin"
        const val COMMIT_FILE_NAME = "$JOURNAL_FILE_NAME.commit"
        private val COMMIT_MAGIC = byteArrayOf(
            'T'.code.toByte(), 'K'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 1,
        )
        private const val COMMIT_BYTES = 32 + 5
    }
}
