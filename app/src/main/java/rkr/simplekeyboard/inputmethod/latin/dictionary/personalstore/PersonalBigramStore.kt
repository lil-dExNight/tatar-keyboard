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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageClock
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.Executor

/**
 * The serialized owner of one subtype's learned-pairs `.tpersb` file. See [PersonalDictionaryStore]
 * for the threading, write-sequence, quarantine and fail-closed rules, which apply unchanged.
 *
 * A pair is written in plaintext only after [LEARN_THRESHOLD] clean observations; until then only a
 * salted truncated hash exists (see [PendingCounters]). The threshold is 2, one lower than for
 * words, because the context word must also be known to the keyboard ([contextMembership]).
 */
internal class PersonalBigramStore(
    private val subtypeId: String,
    private val directoryProvider: PersonalDirectoryProvider,
    private val fileOps: DurableFileOps,
    private val outputOpener: PersonalOutputOpener,
    private val spaceProbe: SpaceProbe,
    private val clock: StorageClock,
    private val executor: Executor,
    private val contextMembership: PersonalBigramContextMembership,
    private val validator: TpersbValidator = TpersbValidator(),
    private val unlockGate: () -> Boolean = { true },
    private val maxPairs: Int = TpersbFormat.MAX_PERSONAL_BIGRAM_PAIRS.toInt(),
    private val quarantineNotice: PersonalQuarantineNotice? = null,
) {
    private val alphabet: Set<Int>? = PersonalSubtypes.alphabetFor(subtypeId)

    // Worker-confined state (touched only on [executor]).
    private var entries: PersonalBigramEntries = PersonalBigramEntries.empty(maxPairs)
    private var loaded = false
    private var counterFlushPending = false

    // See PersonalDictionaryStore.justQuarantined.
    private var justQuarantined = false

    // Progress towards learning, as salted truncated hashes; see PersonalDictionaryStore.pending.
    private var pending: PendingCounters = PendingCounters.EMPTY
    private var pendingDirty = false
    private var salt: ByteArray? = null

    /** Count of physical `.tpersb` writes performed; a test counter (never grows on in-memory notes). */
    @Volatile
    var writeCount: Int = 0
        private set

    /** The published immutable snapshot; read by the engine's worker thread. */
    @Volatile
    var snapshot: PersonalBigramDictionary = PersonalBigramDictionary.EMPTY
        private set

    /**
     * Records one clean completion of the pair ([rawContext], [rawWord]); the pair is saved after
     * [LEARN_THRESHOLD] of them. At that point the context must be a word of this subtype's bundled
     * or personal dictionary ([contextMembership]); checking then keeps the dictionary read off the
     * UI thread and counts words learned in between.
     *
     * For an already learned pair the observation bumps frequency and LRU serial in memory only.
     */
    fun notePair(rawContext: String, rawWord: String) = onWorker {
        if (!open()) return@onWorker
        val alpha = alphabet ?: return@onWorker
        val normalizedContext = PersonalBigramWordFilter.acceptedNormalizedForm(rawContext, alpha)
            ?: return@onWorker
        val normalizedWord = PersonalBigramWordFilter.acceptedNormalizedForm(rawWord, alpha)
            ?: return@onWorker
        if (entries.containsPair(normalizedContext, normalizedWord)) {
            val candidate = entries.noteObservation(normalizedContext, normalizedWord) ?: return@onWorker
            entries = candidate
            snapshot = candidate.toSnapshot(subtypeId)
            counterFlushPending = true
            return@onWorker
        }
        val key = PendingCounters.keyOfPair(
            saltOrCreate() ?: return@onWorker, normalizedContext, normalizedWord,
        )
        val noted = pending.note(key)
        if (noted.countOf(key) >= LEARN_THRESHOLD) {
            if (!isKnownContext(normalizedContext)) {
                // The keyboard does not know the context word: drop the progress so a repeated
                // typo does not keep paying the membership check.
                pending = noted.without(key)
                pendingDirty = true
                return@onWorker
            }
            // Graduated: it goes into the store itself, and its pending trace goes away.
            val candidate = entries.upsert(normalizedContext, rawWord, normalizedWord, LEARN_THRESHOLD)
            if (writeWhole(candidate)) {
                entries = candidate
                snapshot = candidate.toSnapshot(subtypeId)
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
     * Records an accepted prediction as a use; see [PersonalDictionaryStore.noteAcceptedSuggestion].
     * A tap on a prediction that is not a learned pair changes nothing.
     */
    fun noteAcceptedPrediction(rawContext: String, rawWord: String) = onWorker {
        if (!open()) return@onWorker
        alphabet ?: return@onWorker
        val candidate = entries.noteUse(
            PersonalBigramWordFilter.normalize(rawContext),
            PersonalBigramWordFilter.normalize(rawWord),
        ) ?: return@onWorker
        entries = candidate
        snapshot = candidate.toSnapshot(subtypeId)
        counterFlushPending = true
    }

    /** Removes one pair. See [PersonalDictionaryStore.forget]. */
    fun forget(rawContext: String, rawWord: String, outcome: PersonalMutationOutcome? = null) = onWorker {
        val removed = try {
            removeOnWorker(rawContext, rawWord)
        } catch (_: Exception) {
            false
        }
        report(outcome, removed)
    }

    /** The body of [forget], on the worker: returns whether the pair is really gone. */
    private fun removeOnWorker(rawContext: String, rawWord: String): Boolean {
        if (!open()) return false
        if (alphabet == null) return false
        val normalizedContext = PersonalBigramWordFilter.normalize(rawContext)
        val normalizedWord = PersonalBigramWordFilter.normalize(rawWord)
        // The pending hash goes with the pair, so it is not learned again.
        salt?.let { existing ->
            val key = PendingCounters.keyOfPair(existing, normalizedContext, normalizedWord)
            if (pending.countOf(key) > 0) {
                pending = pending.without(key)
                pendingDirty = true
            }
        }
        val candidate = entries.remove(normalizedContext, normalizedWord)
        if (candidate === entries) {
            // Not saved, so already gone; the quarantine purge still runs.
            purgeFromQuarantine(normalizedContext, normalizedWord)
            return true
        }
        val previousSnapshot = snapshot
        snapshot =
            if (candidate.isEmpty) PersonalBigramDictionary.EMPTY else candidate.toSnapshot(subtypeId)
        val removed = try {
            if (candidate.isEmpty) {
                deleteFile()
                true
            } else {
                writeWhole(candidate)
            }
        } catch (_: Exception) {
            false
        }
        if (removed) {
            entries = candidate
        } else {
            snapshot = previousSnapshot
            return false
        }
        purgeFromQuarantine(normalizedContext, normalizedWord)
        return true
    }

    /** Erases this subtype's learned pairs. See [PersonalDictionaryStore.clearAll]. */
    fun clearAll(outcome: PersonalMutationOutcome? = null) = onWorker {
        entries = PersonalBigramEntries.empty(maxPairs)
        counterFlushPending = false
        pending = PendingCounters.EMPTY
        pendingDirty = false
        salt = null
        snapshot = PersonalBigramDictionary.EMPTY
        loaded = true
        if (!unlockGate()) {
            // The files are untouched, so this is a failure.
            report(outcome, false)
            return@onWorker
        }
        // Independent deletions, as in PersonalDictionaryStore.clearAll.
        val storeGone = deleted { deleteFile() }
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull()
        val countersGone = directory != null &&
            deleted { deleteFile(directory, File(directory, pendingFileName())) }
        val saltGone = directory != null &&
            deleted { deleteFile(directory, File(directory, SALT_FILE_NAME)) }
        val quarantineGone = directory != null &&
            deleted { deleteFile(directory, File(directory, quarantineFileName())) }
        // The notice mark goes too, outside the answer below: it holds none of the user's pairs.
        if (directory != null) {
            deleted { deleteFile(directory, File(directory, quarantineNoticeFileName())) }
        }
        report(outcome, storeGone && countersGone && saltGone && quarantineGone)
    }

    /** Replaces the whole store from a backup. See [PersonalDictionaryStore.replaceAll]. */
    fun replaceAll(bytes: ByteArray?, outcome: PersonalMutationOutcome? = null) = onWorker {
        val replaced = try {
            replaceOnWorker(bytes)
        } catch (_: Exception) {
            false
        }
        report(outcome, replaced)
    }

    /** The body of [replaceAll], on the worker. See `PersonalDictionaryStore.replaceOnWorker`. */
    private fun replaceOnWorker(bytes: ByteArray?): Boolean {
        if (alphabet == null) return false
        if (!unlockGate()) return false
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return false
        if (pendingDirty) {
            pending = pending.prunedForFlush()
            if (writePending(pending)) pendingDirty = false
        }
        if (bytes != null) {
            validator.validate(bytes, subtypeId)
            ensureDirectory(directory)
            writeBytesDurably(
                directory, File(directory, TpersbFormat.personalBigramsFileName(subtypeId)), bytes)
        } else {
            deleteFile()
        }
        entries = PersonalBigramEntries.empty(maxPairs)
        counterFlushPending = false
        snapshot = PersonalBigramDictionary.EMPTY
        loaded = false
        open()
        return true
    }

    /** Clears the notice mark. See [PersonalDictionaryStore.noticeDelivered]. */
    fun noticeDelivered() = onWorker {
        justQuarantined = false
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return@onWorker
        deleted { deleteFile(directory, File(directory, quarantineNoticeFileName())) }
    }

    /** Reports what is readable in the quarantined file. See [PersonalDictionaryStore.inspectQuarantine]. */
    fun inspectQuarantine(sink: PersonalQuarantineReportSink) = onWorker {
        val salvage = try {
            readQuarantine()
        } catch (_: Exception) {
            null
        }
        val report = salvage?.let { PersonalQuarantineReport(it.pairCount, it.readToEnd) }
        try {
            sink.onInspected(report)
        } catch (_: Exception) {
        }
    }

    /** Restores the salvaged pairs. See [PersonalDictionaryStore.restoreQuarantine]. */
    fun restoreQuarantine(outcome: PersonalMutationOutcome? = null) = onWorker {
        val restored = try {
            restoreOnWorker()
        } catch (_: Exception) {
            false
        }
        report(outcome, restored)
    }

    /** Removes the quarantined file and nothing else. */
    fun discardQuarantine(outcome: PersonalMutationOutcome? = null) = onWorker {
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull()
        val gone = directory != null &&
            deleted { deleteFile(directory, File(directory, quarantineFileName())) }
        report(outcome, gone)
    }

    /** The body of [restoreQuarantine], on the worker: returns whether the pairs are really saved. */
    private fun restoreOnWorker(): Boolean {
        if (!open()) return false
        if (alphabet == null) return false
        val salvage = readQuarantine() ?: return false
        if (salvage.pairCount == 0) return false
        var candidate = entries
        var added = 0
        for (index in 0 until salvage.pairCount) {
            val context = salvage.contexts[index]
            val successor = salvage.successorNormalizedForms[index]
            if (candidate.containsPair(context, successor)) continue
            candidate = candidate.upsert(context, salvage.successorRawForms[index], successor, 1)
            added++
        }
        // Every salvaged pair was already saved: nothing to write.
        if (added == 0) return true
        return commitWrite(candidate)
    }

    /** The quarantine half of [forget]. See `PersonalDictionaryStore.purgeFromQuarantine`. */
    private fun purgeFromQuarantine(normalizedContext: String, normalizedWord: String) {
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return
        val copy = File(directory, quarantineFileName())
        if (!copy.isFile) return
        val salvage = try {
            PersonalBigramQuarantineSalvage.read(copy, subtypeId)
        } catch (_: Exception) {
            null
        } ?: return
        val kept = (0 until salvage.pairCount).filter { index ->
            salvage.contexts[index] != normalizedContext ||
                salvage.successorNormalizedForms[index] != normalizedWord
        }
        if (kept.size == salvage.pairCount) return // the pair was not in the copy: nothing to purge
        val rewritten = try {
            var candidate = PersonalBigramEntries.empty(maxPairs)
            for (index in kept) {
                candidate = candidate.upsert(
                    salvage.contexts[index],
                    salvage.successorRawForms[index],
                    salvage.successorNormalizedForms[index],
                    1,
                )
            }
            if (candidate.isEmpty) {
                deleteFile(directory, copy)
                true
            } else {
                writeBytesDurably(directory, copy, candidate.serialize(subtypeId))
                true
            }
        } catch (_: Exception) {
            false
        }
        if (!rewritten) {
            // Could not rewrite without the deleted pair: delete the file rather than keep it.
            deleted { deleteFile(directory, copy) }
        }
    }

    /** Reads the quarantined file behind the unlock gate; `null` when there is none to read. */
    private fun readQuarantine(): PersonalBigramQuarantineSalvage? {
        if (!unlockGate()) return null
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return null
        if (!directory.isDirectory) return null
        return PersonalBigramQuarantineSalvage.read(File(directory, quarantineFileName()), subtypeId)
    }

    /** Writes changed counters and serials to disk. See [PersonalDictionaryStore.flush]. */
    fun flush() = onWorker {
        if (!open()) return@onWorker
        if (counterFlushPending && writeWhole(entries)) counterFlushPending = false
        if (pendingDirty) {
            pending = pending.prunedForFlush()
            if (writePending(pending)) pendingDirty = false
        }
    }

    /** Opens the store on its worker. See [PersonalDictionaryStore.prime]. */
    fun prime() = onWorker { open() }

    /** Test hook: runs [block] on the store's executor (so tests can drive the serialized owner). */
    fun runOnWorker(block: () -> Unit) = onWorker(block)

    private fun onWorker(block: () -> Unit) = executor.execute(block)

    private fun isKnownContext(normalizedContext: String): Boolean = try {
        contextMembership.isKnownContext(subtypeId, normalizedContext)
    } catch (_: Exception) {
        // A throwing membership check vetoes the learn; the caller drops the pending progress.
        false
    }

    private fun commitWrite(candidate: PersonalBigramEntries): Boolean {
        if (!writeWhole(candidate)) return false
        entries = candidate
        snapshot = candidate.toSnapshot(subtypeId)
        return true
    }

    /** Runs one erasure step. See `PersonalDictionaryStore.deleted`. */
    private inline fun deleted(step: () -> Unit): Boolean = try {
        step()
        true
    } catch (_: Exception) {
        false
    }

    /** The one way a mutation answers its caller. See `PersonalDictionaryStore.report`. */
    private fun report(outcome: PersonalMutationOutcome?, succeeded: Boolean) {
        if (outcome == null) return
        try {
            outcome.onFinished(succeeded)
        } catch (_: Exception) {
        }
    }

    /** Opens the directory once. See `PersonalDictionaryStore.open`. */
    private fun open(): Boolean {
        if (loaded) return true
        if (!unlockGate()) return false
        loaded = true
        try {
            load()
        } catch (_: Exception) {
            entries = PersonalBigramEntries.empty(maxPairs)
            snapshot = PersonalBigramDictionary.EMPTY
        }
        // The only place the notice is raised; see PersonalDictionaryStore.open.
        if (justQuarantined || quarantineNoticeIsMarked()) {
            try {
                quarantineNotice?.onQuarantined()
            } catch (_: Exception) {
            }
        }
        return true
    }

    /** The body of [open]. See [PersonalDictionaryStore.load]. */
    private fun load() {
        val directory = directoryProvider.personalDirectory()
        if (!directory.isDirectory) {
            snapshot = PersonalBigramDictionary.EMPTY
            return
        }
        cleanupTemps(directory)
        readPending(directory)
        val file = File(directory, TpersbFormat.personalBigramsFileName(subtypeId))
        if (!file.isFile) {
            snapshot = PersonalBigramDictionary.EMPTY
            return
        }
        val validated = try {
            validator.validate(file, subtypeId)
        } catch (_: Exception) {
            null
        }
        if (validated == null) {
            quarantine(directory, file)
            entries = PersonalBigramEntries.empty(maxPairs)
            snapshot = PersonalBigramDictionary.EMPTY
            return
        }
        entries = PersonalBigramEntries.fromValidated(validated, maxPairs)
        snapshot = entries.toSnapshot(subtypeId)
    }

    /** The whole-file write sequence. See `PersonalDictionaryStore.writeWhole`. */
    private fun writeWhole(candidate: PersonalBigramEntries): Boolean {
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
                val destination = File(directory, TpersbFormat.personalBigramsFileName(subtypeId))
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
     * The salt for the pending hashes, in this store's own `salt-bigrams.bin`, so erasing one
     * feature does not invalidate another's progress. See `PersonalDictionaryStore.saltOrCreate`.
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

    /** Reads the pending counters once, alongside the store itself. Fail-closed to empty. */
    private fun readPending(directory: File) {
        pending = try {
            val file = File(directory, pendingFileName())
            // Size check before the read; see PersonalDictionaryStore.readPending.
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

    /** Writes one small file durably. See `PersonalDictionaryStore.writeBytesDurably`. */
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

    /** Quarantines an unreadable file. See `PersonalDictionaryStore.quarantine`. */
    private fun quarantine(directory: File, file: File) {
        val moved = try {
            fileOps.atomicReplace(file, File(directory, quarantineFileName()))
            fileOps.syncDirectory(directory)
            true
        } catch (_: Exception) {
            false
        }
        if (!moved) runCatching { deleteFile(directory, file) }
        // Marked in both cases; see PersonalDictionaryStore.quarantine.
        justQuarantined = true
        runCatching {
            writeBytesDurably(directory, File(directory, quarantineNoticeFileName()), ByteArray(1))
        }
    }

    private fun quarantineFileName(): String =
        TpersbFormat.personalBigramsFileName(subtypeId) + QUARANTINE_SUFFIX

    private fun quarantineNoticeFileName(): String = "quarantine-notice-bigrams-$subtypeId-s1-f1.flag"

    /** Whether the on-disk notice mark exists; false on error. */
    private fun quarantineNoticeIsMarked(): Boolean = try {
        File(directoryProvider.personalDirectory(), quarantineNoticeFileName()).isFile
    } catch (_: Exception) {
        false
    }

    private fun pendingFileName(): String = "pending-bigrams-$subtypeId-s1-f1.bin"

    /** Removes this subtype's `.tpersb` file. Fails only by throwing. */
    private fun deleteFile() {
        val directory = directoryProvider.personalDirectory()
        val file = File(directory, TpersbFormat.personalBigramsFileName(subtypeId))
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

    companion object {
        /** Clean observations of one pair before it is saved; see the class doc. */
        const val LEARN_THRESHOLD = 2

        private const val TEMP_PREFIX = ".personal-bigrams-"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_TEMP_ATTEMPTS = 100
        private const val FREE_SPACE_RESERVE_BYTES = 64L * 1024L
        private const val SALT_FILE_NAME = "salt-bigrams.bin"
        private const val SALT_SIZE = 16

        /** Appended to the ordinary file name for the quarantine slot. See [PersonalDictionaryStore]. */
        private const val QUARANTINE_SUFFIX = ".quarantine"
    }
}
