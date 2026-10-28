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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SentStartOverlay
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageClock
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Executor

/**
 * The serialized owner of one subtype's `.tsent` sentence-initial overlay file.
 *
 * Threading, the whole-file write sequence (exclusive temp → write → flush → fsync file →
 * re-validate → atomic replace → fsync directory) and the fail-closed rule are the personal
 * stores' shared ones; the shared explanations live in [PersonalDictionaryStore]. The UI thread
 * does no I/O, no checksum, no read and no write; readers see the published immutable
 * [SentStartOverlay] snapshot.
 *
 * A word is written in plaintext only after [PendingCounters.LEARN_THRESHOLD] sentence-initial
 * commits; until then only a salted truncated hash exists (see [PendingCounters]), in memory
 * between flushes. The threshold is shared with the personal dictionary: the same slip repeated
 * twice is not the user's habit yet.
 *
 * The quarantine here is the move only, with no notice: no screen lists the overlay, so a loss is
 * silent by design and the overlay simply relearns. The quarantined file keeps the unreadable
 * bytes out of the validation loop; [clearAll] removes it.
 */
internal class SentStartOverlayStore(
    private val subtypeId: String,
    private val directoryProvider: PersonalDirectoryProvider,
    private val fileOps: DurableFileOps,
    private val outputOpener: PersonalOutputOpener,
    private val spaceProbe: SpaceProbe,
    private val clock: StorageClock,
    private val executor: Executor,
    private val validator: TsentValidator = TsentValidator(),
    private val unlockGate: () -> Boolean = { true },
    private val maxEntries: Int = TsentFormat.MAX_SENTSTART_ENTRIES.toInt(),
) {
    private val alphabet: Set<Int>? = PersonalSubtypes.alphabetFor(subtypeId)

    // Worker-confined state (touched only on [executor]).
    private var entries: SentStartOverlayEntries = SentStartOverlayEntries.empty(maxEntries)
    private var loaded = false
    private var pendingCounterFlush = false

    // Progress towards learning, as salted truncated hashes. Held in memory and written on the
    // same boundary as the usage counters, never once per committed word.
    private var pending: PendingCounters = PendingCounters.EMPTY
    private var pendingDirty = false
    private var salt: ByteArray? = null

    /** Count of physical `.tsent` writes performed; a test counter (never grows on in-memory notes). */
    @Volatile
    var writeCount: Int = 0
        private set

    /** The published immutable snapshot; read by the strip's UI-thread fill. */
    @Volatile
    var snapshot: SentStartOverlay = SentStartOverlay.EMPTY
        private set

    /**
     * Records one sentence-initial commit of [word]: a saved word gets a usage bump in memory (the
     * session-end [flush] writes it), a new word advances its pending counter and enters the
     * overlay in plaintext at the learn threshold.
     */
    fun noteSentenceStart(word: String) = onWorker {
        val normalized = eligibleNormalizedForm(word) ?: return@onWorker
        if (entries.containsNormalized(normalized)) {
            // Already saved: a usage bump, like the dictionary's accepted-suggestion bump. The
            // file is not rewritten per commit; [flush] writes it once per session.
            entries = entries.noted(word, normalized)
            publish()
            pendingCounterFlush = true
            return@onWorker
        }
        val key = PendingCounters.keyOf(saltOrCreate() ?: return@onWorker, normalized)
        val noted = pending.note(key)
        if (noted.countOf(key) >= PendingCounters.LEARN_THRESHOLD) {
            // Graduated: it goes into the overlay itself, and its pending trace goes away.
            val next = entries.noted(word, normalized)
            if (writeWhole(next)) {
                entries = next
                publish()
                pending = noted.without(key)
            } else {
                pending = noted
            }
        } else {
            pending = noted
        }
        pendingDirty = true
    }

    /**
     * Writes changed counters and pending hashes to disk. Called at the end of an input session
     * (`SuggestionsController.onFinishInput`), the only point where usage counters and pending
     * hashes are written.
     */
    fun flush() = onWorker {
        if (!open()) return@onWorker
        if (pendingCounterFlush && writeWhole(entries)) pendingCounterFlush = false
        if (pendingDirty) {
            pending = pending.prunedForFlush()
            if (writePending(pending)) pendingDirty = false
        }
    }

    /**
     * Erases this subtype's overlay: empties memory and deletes the file, the pending counters,
     * the salt and any quarantined copy. The salt goes too, so the hashes of a future session
     * cannot be compared with those of the erased one; a new one is created on demand.
     */
    fun clearAll(outcome: PersonalMutationOutcome? = null) = onWorker {
        entries = SentStartOverlayEntries.empty(maxEntries)
        pendingCounterFlush = false
        pending = PendingCounters.EMPTY
        pendingDirty = false
        salt = null
        snapshot = SentStartOverlay.EMPTY
        loaded = true
        if (!unlockGate()) {
            // Memory is empty but the files are untouched and come back on the next start, so this
            // is reported as a failure.
            report(outcome, false)
            return@onWorker
        }
        // Independent deletions: a failure on one must not skip the others.
        val fileGone = deleted { deleteFile() }
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull()
        val countersGone = directory != null &&
            deleted { deleteFile(directory, File(directory, pendingFileName())) }
        val saltGone = directory != null &&
            deleted { deleteFile(directory, File(directory, SALT_FILE_NAME)) }
        val quarantineGone = directory != null &&
            deleted { deleteFile(directory, File(directory, quarantineFileName())) }
        report(outcome, fileGone && countersGone && saltGone && quarantineGone)
    }

    /**
     * Opens the store on its worker if it is not open yet, publishing the snapshot the strip will
     * read. Safe to call from any thread: the file read runs on the executor.
     */
    fun prime() = onWorker { open() }

    /** Test hook: runs [block] on the store's executor (so tests can drive the serialized owner). */
    fun runOnWorker(block: () -> Unit) = onWorker(block)

    private fun onWorker(block: () -> Unit) = executor.execute(block)

    /**
     * The normalized form of [word] when it may enter the overlay, or null. The content checks
     * are the personal word filter's, with the overlay's own length bounds: a single-code-point
     * word («я» opens Russian sentences) is eligible here. Mixed casing, a combining mark left
     * after NFC and any code point outside the subtype's alphabet are rejected.
     */
    private fun eligibleNormalizedForm(word: String): String? {
        if (!open()) return null
        val alpha = alphabet ?: return null
        if (word.isEmpty()) return null
        if (TatarWordUtils.classifyCasing(word) == TatarWordUtils.PrefixCasing.MIXED) return null
        // The raw form is what goes on disk; its UTF-8 length must fit the u8 length field.
        if (word.toByteArray(StandardCharsets.UTF_8).size > MAX_RAW_WORD_BYTES) return null
        val normalized = PersonalWordFilter.normalize(word)
        val codePointCount = normalized.codePointCount(0, normalized.length)
        if (codePointCount < TsentFormat.MIN_WORD_CODE_POINTS ||
            codePointCount > TsentFormat.MAX_WORD_CODE_POINTS
        ) {
            return null
        }
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            val type = Character.getType(codePoint)
            if (type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            ) {
                return null
            }
            if (codePoint !in alpha) return null
            offset += Character.charCount(codePoint)
        }
        return normalized
    }

    /**
     * Opens the directory once: honors the unlock gate (before the first unlock the path is
     * inaccessible, so the feature stays empty), removes stale temps, reads the file, and
     * quarantines an unreadable file (see [quarantine]) with an empty snapshot. Returns true once
     * loaded.
     */
    private fun open(): Boolean {
        if (loaded) return true
        if (!unlockGate()) return false
        loaded = true
        try {
            load()
        } catch (_: Exception) {
            entries = SentStartOverlayEntries.empty(maxEntries)
            snapshot = SentStartOverlay.EMPTY
        }
        return true
    }

    private fun load() {
        val directory = directoryProvider.personalDirectory()
        if (!directory.isDirectory) {
            snapshot = SentStartOverlay.EMPTY
            return
        }
        cleanupTemps(directory)
        readPending(directory)
        val file = File(directory, TsentFormat.sentStartFileName(subtypeId))
        if (!file.isFile) {
            snapshot = SentStartOverlay.EMPTY
            return
        }
        val validated = try {
            validator.validate(file, subtypeId)
        } catch (_: Exception) {
            null
        }
        if (validated == null) {
            quarantine(directory, file)
            entries = SentStartOverlayEntries.empty(maxEntries)
            snapshot = SentStartOverlay.EMPTY
            return
        }
        entries = SentStartOverlayEntries.fromValidated(validated, maxEntries)
        publish()
    }

    /** Publishes the current entries as the snapshot; the word list is computed off the UI thread. */
    private fun publish() {
        snapshot = SentStartOverlay.of(entries.wordsByUsage())
    }

    /**
     * The whole-file write sequence. Returns true only when every step succeeded. On any caught
     * failure the temp is removed and false is returned, leaving the previous file untouched. An
     * uncaught [Error] (a simulated process death) leaves the temp for the next open to discard.
     */
    private fun writeWhole(next: SentStartOverlayEntries): Boolean {
        val directory = directoryProvider.personalDirectory()
        return try {
            ensureDirectory(directory)
            cleanupTemps(directory)
            val bytes = next.serialize(subtypeId)
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
                val destination = File(directory, TsentFormat.sentStartFileName(subtypeId))
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
     * The salt for the pending hashes: random bytes in this store's own salt file, created on
     * first use and deleted by [clearAll], so erasing one feature does not invalidate another's
     * progress. Returns null when it can be neither read nor created; then nothing is counted
     * (no learning rather than unsalted keys).
     */
    private fun saltOrCreate(): ByteArray? {
        salt?.let { return it }
        return try {
            val directory = directoryProvider.personalDirectory()
            ensureDirectory(directory)
            val file = File(directory, SALT_FILE_NAME)
            val existing = if (file.isFile && file.length() == SALT_SIZE.toLong()) {
                file.readBytes()
            } else {
                val fresh = ByteArray(SALT_SIZE)
                SecureRandom().nextBytes(fresh)
                writeBytesDurably(directory, file, fresh)
                fresh
            }
            salt = existing
            existing
        } catch (_: Exception) {
            null
        }
    }

    /** Reads the pending counters once, alongside the overlay itself. Fail-closed to empty. */
    private fun readPending(directory: File) {
        pending = try {
            val file = File(directory, pendingFileName())
            // Size check before the read: a file past MAX_SERIALIZED_BYTES cannot parse, and past
            // 2 GiB readBytes() throws an OutOfMemoryError, which the catch below does not stop.
            if (file.isFile && file.length() <= PendingCounters.MAX_SERIALIZED_BYTES) {
                PendingCounters.parse(file.readBytes())
            } else {
                PendingCounters.EMPTY
            }
        } catch (_: Exception) {
            PendingCounters.EMPTY
        }
    }

    private fun writePending(counters: PendingCounters): Boolean = try {
        val directory = directoryProvider.personalDirectory()
        ensureDirectory(directory)
        writeBytesDurably(directory, File(directory, pendingFileName()), counters.serialize())
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Writes one small file through the same temp → fsync → atomic replace → directory fsync
     * sequence as the overlay, so a half-written pending file or salt cannot drop learning
     * progress.
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
     * Moves an unreadable file into this subtype's single quarantine slot instead of deleting it,
     * so it does not fail validation on every start. One slot per language, replaced by the next
     * failure. There is no restore path: no screen lists the overlay, and the overlay relearns.
     * If the move fails, the unreadable file is deleted.
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
        TsentFormat.sentStartFileName(subtypeId) + QUARANTINE_SUFFIX

    private fun pendingFileName(): String = "pending-sentstart-$subtypeId-s1-f1.bin"

    /**
     * Removes this subtype's `.tsent` file. Fails only by throwing; callers handle it with `try`
     * or [deleted].
     */
    private fun deleteFile() {
        val directory = directoryProvider.personalDirectory()
        val file = File(directory, TsentFormat.sentStartFileName(subtypeId))
        deleteFile(directory, file)
    }

    private fun deleteFile(directory: File, file: File) {
        if (!file.exists()) return
        if (!fileOps.delete(file) && file.exists()) throw IOException("cannot remove personal file")
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
            val file = File(directory, "$TEMP_PREFIX$subtypeId.$timestamp.$counter$TEMP_SUFFIX")
            if (fileOps.createNewFile(file)) return file
        }
        throw IOException("cannot create exclusive personal temp")
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
     * The one way a mutation answers its caller; see [PersonalDictionaryStore]. A throwing
     * callback is silently ignored, since nothing here may log.
     */
    private fun report(outcome: PersonalMutationOutcome?, succeeded: Boolean) {
        if (outcome == null) return
        try {
            outcome.onFinished(succeeded)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TEMP_PREFIX = ".personal-"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_TEMP_ATTEMPTS = 100
        private const val FREE_SPACE_RESERVE_BYTES = 64L * 1024L
        private const val SALT_FILE_NAME = "salt-sentstart.bin"
        private const val SALT_SIZE = 16

        /**
         * Appended to the ordinary file name for the quarantine slot. Neither a `.tsent` nor a
         * temp name, so nothing validates it and [cleanupTemps] leaves it alone; [clearAll]
         * removes it.
         */
        private const val QUARANTINE_SUFFIX = ".quarantine"

        /** u8 length field: the raw form cannot exceed 255 UTF-8 bytes on disk. */
        private const val MAX_RAW_WORD_BYTES = 255
    }
}
