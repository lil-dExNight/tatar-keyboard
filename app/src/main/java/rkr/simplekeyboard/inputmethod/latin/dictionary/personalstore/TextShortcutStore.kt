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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TcutFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TcutValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TextShortcuts
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageClock
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The serialized owner of the single `.tcut` text-shortcut file.
 *
 * Every mutation is an event on the single background [executor], so all in-memory state lives on
 * one worker and never on the UI thread. After each SUCCESSFUL mutation a fresh immutable
 * [TextShortcuts] snapshot is published through the `@Volatile` [snapshot] reference; the
 * suggestion strip reads it. The UI thread does no I/O, no checksum, no read and no write.
 *
 * Whole-file write only, in the same fixed sequence as the personal-dictionary store: exclusive
 * temp in the same directory → write → flush → fsync file → RE-VALIDATE the written bytes →
 * atomic replace → fsync directory. A partial temp never becomes the main file; any failure leaves
 * the previous valid file untouched; temp garbage is removed when the store next opens the
 * directory.
 *
 * Fail-closed everywhere: a caught exception (including the unlock gate closing the
 * credential-protected path before the first unlock) drops the mutation and leaves the feature
 * empty rather than throwing. Nothing here logs, and no message carries the user's text or the
 * file path.
 *
 * There are no usage counters, no pending hashes and no learning: pairs enter only from the
 * settings screen, so the store is much smaller than the personal-dictionary one.
 */
internal class TextShortcutStore(
    private val directoryProvider: PersonalDirectoryProvider,
    private val fileOps: DurableFileOps,
    private val outputOpener: PersonalOutputOpener,
    private val spaceProbe: SpaceProbe,
    private val clock: StorageClock,
    private val executor: Executor,
    private val validator: TcutValidator = TcutValidator(),
    private val unlockGate: () -> Boolean = { true },
    private val maxEntries: Int = TcutFormat.MAX_SHORTCUT_ENTRIES.toInt(),
) {
    // Worker-confined state (touched only on [executor]).
    private var pairs: TextShortcuts = TextShortcuts.EMPTY
    private var loaded = false

    /** Count of physical `.tcut` writes performed; a test counter. */
    @Volatile
    var writeCount: Int = 0
        private set

    /** The published immutable snapshot; read by the suggestion strip on the UI thread. */
    @Volatile
    var snapshot: TextShortcuts = TextShortcuts.EMPTY
        private set

    /**
     * Inserts or replaces one pair (the settings screen's "Save" action); a no-op with a false
     * report if either side is not eligible or the store is full. [outcome] is told on the worker
     * whether the whole-file write succeeded, so a failed write is visible to the screen.
     */
    fun put(shortcut: String, expansion: String, outcome: PersonalMutationOutcome? = null) = onWorker {
        val acceptedShortcut = TextShortcutFilter.acceptedShortcut(shortcut)
        val acceptedExpansion = TextShortcutFilter.acceptedExpansion(expansion)
        if (acceptedShortcut == null || acceptedExpansion == null || !open()) {
            report(outcome, false)
            return@onWorker
        }
        val isNew = pairs.expansionFor(acceptedShortcut) == null
        if (isNew && pairs.size >= maxEntries) {
            // No usage counters, so no fair eviction: the add is refused and the screen says so.
            report(outcome, false)
            return@onWorker
        }
        report(outcome, commitWrite(pairs.upsert(acceptedShortcut, acceptedExpansion)))
    }

    /**
     * Removes one pair and rewrites the file, or deletes it when the pair was the last one.
     *
     * [outcome] is told whether the shortcut is really gone. The removal is published to readers
     * before the write, so a keystroke during the write cannot offer the expansion again; if the
     * write fails, the previous snapshot is restored, because the pair is still saved.
     */
    fun remove(shortcut: String, outcome: PersonalMutationOutcome? = null) = onWorker {
        // The body runs inside a try: the worker has no UncaughtExceptionHandler, so a throw here
        // would kill the IME. A failed delete loses nothing (the pair stays saved), so the failure
        // goes to [outcome], which gets exactly one answer.
        val removed = try {
            removeOnWorker(shortcut)
        } catch (_: Exception) {
            false
        }
        report(outcome, removed)
    }

    /** The body of [remove], on the worker: returns whether the shortcut is really gone. */
    private fun removeOnWorker(shortcut: String): Boolean {
        if (!open()) return false
        val candidate = pairs.remove(TextShortcutFilter.normalizeShortcut(shortcut))
        if (candidate === pairs) return true // the shortcut was not saved, so it is gone as asked
        val previousSnapshot = snapshot
        snapshot = candidate
        // A throw here becomes `false` inside the mutation, so the snapshot restore below still
        // runs: the pair is still on disk.
        val removed = try {
            if (candidate.isEmpty) {
                // Deletion fails only by throwing; the catch below handles it.
                deleteFile()
                true
            } else {
                writeWhole(candidate)
            }
        } catch (_: Exception) {
            false
        }
        if (removed) {
            pairs = candidate
        } else {
            snapshot = previousSnapshot
            return false
        }
        return true
    }

    /**
     * Opens the store on its worker if it is not open yet, publishing the snapshot the strip will
     * read. Safe to call from any thread: the file read runs on the executor.
     */
    fun prime() = onWorker { open() }

    /**
     * The backup restore: replace the whole store from the archive's bytes (already validated by
     * the importer, validated here again before writing), or delete it when [bytes] is null. The
     * published snapshot re-reads from the disk afterwards, so the strip follows the restore.
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
        if (!unlockGate()) return false
        if (bytes != null) {
            validator.validate(bytes)
            val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull()
                ?: return false
            if (!writeBytesDurably(directory, bytes)) return false
        } else {
            deleteFile()
        }
        // Re-read the disk state: the published snapshot now matches the imported file (or none).
        loaded = false
        open()
        return true
    }

    /**
     * The whole-file write of raw validated bytes — the same durable sequence as [writeWhole],
     * without the serialize step: temp, fsync, re-validate, atomic replace, directory fsync.
     */
    private fun writeBytesDurably(directory: File, bytes: ByteArray): Boolean {
        ensureDirectory(directory)
        cleanupTemps(directory)
        val required = bytes.size.toLong() + FREE_SPACE_RESERVE_BYTES
        if (spaceProbe.usableBytes(directory) < required) return false
        val temporary = createExclusiveTemp(directory)
        return try {
            outputOpener.open(temporary).use { output ->
                output.write(bytes)
                output.flush()
                fileOps.syncFile(output.fd)
            }
            validator.validate(temporary)
            fileOps.atomicReplace(temporary, File(directory, TcutFormat.shortcutsFileName()))
            fileOps.syncDirectory(directory)
            writeCount++
            true
        } catch (_: Exception) {
            if (temporary.exists()) runCatching { fileOps.delete(temporary) }
            false
        }
    }

    /** Test hook: runs [block] on the store's executor (so tests can drive the serialized owner). */
    fun runOnWorker(block: () -> Unit) = onWorker(block)

    private fun onWorker(block: () -> Unit) = executor.execute(block)

    private fun commitWrite(candidate: TextShortcuts): Boolean {
        if (!writeWhole(candidate)) return false
        pairs = candidate
        snapshot = candidate
        return true
    }

    /**
     * The one way a mutation answers its caller. The callback posts to an Activity's UI thread and
     * can throw (dead Handler, detached screen); the worker has no UncaughtExceptionHandler, so the
     * call is wrapped in a try. [succeeded] is an argument, so it is always evaluated, even for a
     * null outcome. A throwing callback is silently ignored, since nothing here may log.
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
            pairs = TextShortcuts.EMPTY
            snapshot = TextShortcuts.EMPTY
        }
        return true
    }

    /** The body of [open], where an early exit is a plain `return` rather than a `return true`. */
    private fun load() {
        val directory = directoryProvider.personalDirectory()
        if (!directory.isDirectory) {
            snapshot = TextShortcuts.EMPTY
            return
        }
        cleanupTemps(directory)
        val file = File(directory, TcutFormat.shortcutsFileName())
        if (!file.isFile) {
            snapshot = TextShortcuts.EMPTY
            return
        }
        val validated = try {
            validator.validate(file)
        } catch (_: Exception) {
            null
        }
        if (validated == null) {
            quarantine(directory, file)
            pairs = TextShortcuts.EMPTY
            snapshot = TextShortcuts.EMPTY
            return
        }
        pairs = TextShortcuts.of(validated)
        snapshot = pairs
    }

    /**
     * The whole-file write sequence. Returns true only when every step succeeded. On any caught
     * failure the temp is removed and false is returned, leaving the previous file untouched. An
     * uncaught [Error] (a simulated process death) leaves the temp for the next open to discard.
     */
    private fun writeWhole(candidate: TextShortcuts): Boolean {
        val directory = directoryProvider.personalDirectory()
        return try {
            ensureDirectory(directory)
            cleanupTemps(directory)
            val bytes = candidate.serialize()
            val required = bytes.size.toLong() + FREE_SPACE_RESERVE_BYTES
            if (spaceProbe.usableBytes(directory) < required) return false
            val temporary = createExclusiveTemp(directory)
            try {
                outputOpener.open(temporary).use { output ->
                    output.write(bytes)
                    output.flush()
                    fileOps.syncFile(output.fd)
                }
                validator.validate(temporary)
                val destination = File(directory, TcutFormat.shortcutsFileName())
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
     * Moves an unreadable file into the single quarantine slot instead of deleting it: the pairs
     * are the user's own, so a damaged file is kept next to the store rather than destroyed. The
     * slot is one file, replaced by the next failure; if the move fails, the unreadable file is
     * deleted so it does not fail validation on every start.
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

    private fun quarantineFileName(): String = TcutFormat.shortcutsFileName() + QUARANTINE_SUFFIX

    /**
     * Removes the `.tcut` file. Fails only by throwing; callers handle it with `try`.
     */
    private fun deleteFile() {
        val directory = directoryProvider.personalDirectory()
        val file = File(directory, TcutFormat.shortcutsFileName())
        deleteFile(directory, file)
    }

    private fun deleteFile(directory: File, file: File) {
        if (!file.exists()) return
        if (!fileOps.delete(file) && file.exists()) throw IOException("cannot remove shortcut file")
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
            val file = File(directory, "$TEMP_PREFIX$SHORTCUT_TEMP_TAG.$timestamp.$counter$TEMP_SUFFIX")
            if (fileOps.createNewFile(file)) return file
        }
        throw IOException("cannot create exclusive shortcut temp")
    }

    companion object {
        private const val TEMP_PREFIX = ".personal-"
        private const val TEMP_SUFFIX = ".tmp"
        private const val SHORTCUT_TEMP_TAG = "shortcuts"
        private const val MAX_TEMP_ATTEMPTS = 100
        private const val FREE_SPACE_RESERVE_BYTES = 64L * 1024L

        /**
         * Appended to the ordinary file name for the quarantine slot. Neither a `.tcut` nor a temp
         * name, so nothing reads it or cleans it up by accident.
         */
        private const val QUARANTINE_SUFFIX = ".quarantine"
    }
}
