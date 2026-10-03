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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.RefusedCorrections
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TrefFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TrefValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageClock
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The serialized owner of one subtype's `.tref` refused-corrections file.
 *
 * Every mutation is an event on the single background [executor], so all in-memory state lives on
 * one worker and never on the UI thread. After each SUCCESSFUL mutation a fresh immutable
 * [RefusedCorrections] snapshot is published through the `@Volatile` [snapshot] reference; the
 * autocorrect paths read it. The UI thread does no I/O, no checksum, no read and no write.
 *
 * Whole-file write only, in the same fixed sequence as the other personal stores: exclusive temp in
 * the same directory → write → flush → fsync file → RE-VALIDATE the written bytes → atomic
 * replace → fsync directory. A partial temp never becomes the main file; any failure leaves the
 * previous valid file untouched; temp garbage is removed when the store next opens the directory.
 *
 * Fail-closed everywhere: a caught exception (including the unlock gate closing the
 * credential-protected path before the first unlock) drops the mutation and leaves the feature
 * empty rather than throwing. Nothing here logs, and no message carries the user's text or the
 * file path.
 *
 * There are no pending counters and no salt: a refusal is recorded only when the learning predicate
 * already allowed it, and a refused pair suppresses — it is never shown back as a suggestion, so a
 * half-learned refusal carries no privacy weight the threshold hash dance would lift.
 */
internal class RefusedCorrectionStore(
    private val subtypeId: String,
    private val directoryProvider: PersonalDirectoryProvider,
    private val fileOps: DurableFileOps,
    private val outputOpener: PersonalOutputOpener,
    private val spaceProbe: SpaceProbe,
    private val clock: StorageClock,
    private val executor: Executor,
    private val validator: TrefValidator = TrefValidator(),
    private val unlockGate: () -> Boolean = { true },
    private val maxEntries: Int = TrefFormat.MAX_REFUSED_ENTRIES.toInt(),
) {
    // Worker-confined state (touched only on [executor]).
    private var pairs: RefusedCorrections = RefusedCorrections.EMPTY
    private var loaded = false

    /** Count of physical `.tref` writes performed; a test counter. */
    @Volatile
    var writeCount: Int = 0
        private set

    /** The published immutable snapshot; read by the autocorrect paths on the UI thread. */
    @Volatile
    var snapshot: RefusedCorrections = RefusedCorrections.EMPTY
        private set

    /**
     * Records one undone correction, as the normalized (typed word → replacement) pair. Like
     * [PersonalDictionaryStore.noteCompletion] this answers nothing: a refusal is a learning event,
     * not a user command, so a failed write is invisible by design. A pair already at the refusal
     * threshold is a no-op (its correction cannot fire, so no further refusal of it exists), as is
     * a pair for a subtype that has no personal store at all.
     */
    fun noteRefusal(typedWord: String, replacement: String) = onWorker {
        if (!PersonalSubtypes.isSupported(subtypeId)) return@onWorker
        if (!open()) return@onWorker
        // A correction never replaces a word with itself, so a self-pair cannot come from one.
        if (typedWord == replacement) return@onWorker
        if (!TrefValidator.isWellFormedWord(typedWord) || !TrefValidator.isWellFormedWord(replacement)) {
            return@onWorker
        }
        val candidate = pairs.noting(typedWord, replacement, maxEntries)
        if (candidate === pairs) return@onWorker
        commitWrite(candidate)
    }

    /**
     * Erases this subtype's refused corrections: empties memory and deletes the file and any
     * quarantined copy. [outcome] is told whether the files are really gone; before the first
     * unlock the files cannot be reached, so the erasure is reported as failed, as in
     * [PersonalDictionaryStore.clearAll].
     */
    fun clearAll(outcome: PersonalMutationOutcome? = null) = onWorker {
        pairs = RefusedCorrections.EMPTY
        snapshot = RefusedCorrections.EMPTY
        loaded = true
        if (!unlockGate()) {
            report(outcome, false)
            return@onWorker
        }
        // Independent deletions: a failure on one must not skip the other. A surviving quarantined
        // copy holds the same pairs, so a copy left behind is a failed erasure.
        val fileGone = deleted { deleteFile() }
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull()
        val quarantineGone = directory != null &&
            deleted { deleteFile(directory, File(directory, quarantineFileName())) }
        report(outcome, fileGone && quarantineGone)
    }

    /**
     * Replaces the whole store with [bytes] from a backup (null deletes the file), then re-reads
     * the disk state and publishes it as the snapshot. Runs on the worker, so it cannot race a
     * refusal write. The bytes are validated again here even though the backup layer checks them
     * before anything is written: nothing is written when validation fails. See
     * [PersonalDictionaryStore.replaceAll].
     */
    fun replaceAll(bytes: ByteArray?, outcome: PersonalMutationOutcome? = null) = onWorker {
        val replaced = try {
            replaceOnWorker(bytes)
        } catch (_: Exception) {
            false
        }
        report(outcome, replaced)
    }

    /** The body of [replaceAll], on the worker: returns whether the disk now matches the request. */
    private fun replaceOnWorker(bytes: ByteArray?): Boolean {
        if (!PersonalSubtypes.isSupported(subtypeId)) return false
        if (!unlockGate()) return false
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return false
        if (bytes != null) {
            validator.validate(bytes, subtypeId)
            ensureDirectory(directory)
            writeBytesDurably(directory, File(directory, TrefFormat.refusedCorrectionsFileName(subtypeId)), bytes)
        } else {
            deleteFile()
        }
        // Re-read the disk state: the published snapshot now matches the imported file (or none).
        pairs = RefusedCorrections.EMPTY
        snapshot = RefusedCorrections.EMPTY
        loaded = false
        open()
        return true
    }

    /**
     * Opens the store on its worker if it is not open yet, publishing the snapshot the autocorrect
     * paths will read. Safe to call from any thread: the file read runs on the executor.
     */
    fun prime() = onWorker { open() }

    /** Test hook: runs [block] on the store's executor (so tests can drive the serialized owner). */
    fun runOnWorker(block: () -> Unit) = onWorker(block)

    private fun onWorker(block: () -> Unit) = executor.execute(block)

    private fun commitWrite(candidate: RefusedCorrections): Boolean {
        if (!writeWhole(candidate)) return false
        pairs = candidate
        snapshot = candidate
        return true
    }

    /**
     * Runs one erasure step so that its failure neither skips the next step nor escapes. Returns
     * whether it succeeded; nothing is logged, so the result is the only signal.
     */
    private inline fun deleted(step: () -> Unit): Boolean = try {
        step()
        true
    } catch (_: Exception) {
        false
    }

    /**
     * The one way a mutation answers its caller. See [PersonalDictionaryStore]: the callback posts
     * to an Activity's UI thread and can throw, and the worker has no UncaughtExceptionHandler, so
     * the call is wrapped in a try and a throwing callback is silently ignored.
     */
    private fun report(outcome: PersonalMutationOutcome?, succeeded: Boolean) {
        if (outcome == null) return
        try {
            outcome.onFinished(succeeded)
        } catch (_: Exception) {
        }
    }

    /**
     * Opens the directory once: honors the unlock gate (before the first unlock the path is
     * inaccessible, so the feature stays empty), removes stale temps, reads the file, and moves an
     * unreadable file aside (see [quarantine]) with an empty snapshot. Returns true once loaded.
     */
    private fun open(): Boolean {
        if (loaded) return true
        if (!unlockGate()) return false
        loaded = true
        try {
            load()
        } catch (_: Exception) {
            pairs = RefusedCorrections.EMPTY
            snapshot = RefusedCorrections.EMPTY
        }
        return true
    }

    /** The body of [open], where an early exit is a plain `return` rather than a `return true`. */
    private fun load() {
        val directory = directoryProvider.personalDirectory()
        if (!directory.isDirectory) {
            snapshot = RefusedCorrections.EMPTY
            return
        }
        cleanupTemps(directory)
        val file = File(directory, TrefFormat.refusedCorrectionsFileName(subtypeId))
        if (!file.isFile) {
            snapshot = RefusedCorrections.EMPTY
            return
        }
        val validated = try {
            validator.validate(file, subtypeId)
        } catch (_: Exception) {
            null
        }
        if (validated == null) {
            quarantine(directory, file)
            pairs = RefusedCorrections.EMPTY
            snapshot = RefusedCorrections.EMPTY
            return
        }
        pairs = RefusedCorrections.of(validated)
        snapshot = pairs
    }

    /**
     * The whole-file write sequence. Returns true only when every step succeeded. On any caught
     * failure the temp is removed and false is returned, leaving the previous file untouched. An
     * uncaught [Error] (a simulated process death) leaves the temp for the next open to discard.
     */
    private fun writeWhole(candidate: RefusedCorrections): Boolean {
        val directory = directoryProvider.personalDirectory()
        return try {
            ensureDirectory(directory)
            cleanupTemps(directory)
            val bytes = candidate.serialize(subtypeId)
            val required = bytes.size.toLong() + FREE_SPACE_RESERVE_BYTES
            if (spaceProbe.usableBytes(directory) < required) return false
            val temporary = createExclusiveTemp(directory)
            try {
                outputOpener.open(temporary).use { output ->
                    output.write(bytes)
                    output.flush()
                    fileOps.syncFile(output.fd)
                }
                validator.validate(temporary, subtypeId)
                val destination = File(directory, TrefFormat.refusedCorrectionsFileName(subtypeId))
                fileOps.atomicReplace(temporary, destination)
                fileOps.syncDirectory(directory)
                writeCount++
                true
            } catch (_: Exception) {
                if (temporary.exists()) runCatching { fileOps.delete(temporary) }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Writes the imported bytes through the same temp → fsync → atomic replace → directory fsync
     * sequence as [writeWhole], so a half-written restore cannot drop the refusals.
     */
    private fun writeBytesDurably(directory: File, destination: File, bytes: ByteArray) {
        val temporary = createExclusiveTemp(directory)
        try {
            outputOpener.open(temporary).use { output ->
                output.write(bytes)
                output.flush()
                fileOps.syncFile(output.fd)
            }
            fileOps.atomicReplace(temporary, destination)
            fileOps.syncDirectory(directory)
        } catch (exception: Exception) {
            if (temporary.exists()) runCatching { fileOps.delete(temporary) }
            throw exception
        }
    }

    /**
     * Moves an unreadable file into this subtype's single quarantine slot instead of deleting it:
     * the pairs are the user's own, so a damaged file is kept next to the store rather than
     * destroyed. The slot is one file, replaced by the next failure; if the move fails, the
     * unreadable file is deleted so it does not fail validation on every start. No notice is
     * raised: no screen lists the refused pairs, so there is nothing to explain it on.
     */
    private fun quarantine(directory: File, file: File) {
        val moved = try {
            fileOps.atomicReplace(file, File(directory, quarantineFileName()))
            fileOps.syncDirectory(directory)
            true
        } catch (_: Exception) {
            false
        }
        if (!moved) runCatching { deleteFile(directory, file) }
    }

    private fun quarantineFileName(): String =
        TrefFormat.refusedCorrectionsFileName(subtypeId) + QUARANTINE_SUFFIX

    /**
     * Removes this subtype's `.tref` file. Fails only by throwing; callers handle it with `try` or
     * [deleted].
     */
    private fun deleteFile() {
        val directory = directoryProvider.personalDirectory()
        val file = File(directory, TrefFormat.refusedCorrectionsFileName(subtypeId))
        deleteFile(directory, file)
    }

    private fun deleteFile(directory: File, file: File) {
        if (!file.exists()) return
        if (!fileOps.delete(file) && file.exists()) throw IOException("cannot remove refused-corrections file")
        fileOps.syncDirectory(directory)
    }

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (directory.exists() || !directory.mkdirs()) throw IOException("cannot create personal directory")
        directory.parentFile?.let(fileOps::syncDirectory)
    }

    private fun cleanupTemps(directory: File) {
        val temporaries = directory.listFiles { file ->
            file.isFile && file.name.startsWith(TEMP_PREFIX) && file.name.endsWith(TEMP_SUFFIX)
        } ?: return
        if (temporaries.isEmpty()) return
        var removedAny = false
        for (temp in temporaries) {
            if (fileOps.delete(temp) || !temp.exists()) removedAny = true
        }
        if (removedAny) fileOps.syncDirectory(directory)
    }

    private fun createExclusiveTemp(directory: File): File {
        val timestamp = clock.nowMillis()
        for (counter in 0 until MAX_TEMP_ATTEMPTS) {
            val file = File(directory, "$TEMP_PREFIX$subtypeId.refused.$timestamp.$counter$TEMP_SUFFIX")
            if (fileOps.createNewFile(file)) return file
        }
        throw IOException("cannot create exclusive refused-corrections temp")
    }

    companion object {
        private const val TEMP_PREFIX = ".personal-"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_TEMP_ATTEMPTS = 100
        private const val FREE_SPACE_RESERVE_BYTES = 64L * 1024L

        /**
         * Appended to the ordinary file name for the quarantine slot. Neither a `.tref` nor a temp
         * name, so nothing reads it or cleans it up by accident.
         */
        private const val QUARANTINE_SUFFIX = ".quarantine"
    }
}
