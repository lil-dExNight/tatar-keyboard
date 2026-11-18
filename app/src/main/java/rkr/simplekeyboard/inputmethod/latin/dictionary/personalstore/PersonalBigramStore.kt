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
) : PersonalInventoryTarget {
    private val alphabet: Set<Int>? = PersonalSubtypes.alphabetFor(subtypeId)

    // Worker-confined state (touched only on [executor]).
    private var entries: PersonalBigramEntries = PersonalBigramEntries.empty(maxPairs)
    private var loaded = false
    private var counterFlushPending = false

    // See PersonalDictionaryStore.loadFailed.
    private var loadFailed = false

    // See PersonalDictionaryStore.wholeWriteSuppressed.
    private var wholeWriteSuppressed = false

    // See PersonalDictionaryStore.justQuarantined.
    private var justQuarantined = false

    // See PersonalDictionaryStore.lastWriteInstalled.
    private var lastWriteInstalled = false

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
            // Graduated: it goes into the store itself, and its pending trace goes away. While a
            // failed whole-file write is suppressed, no candidate is built and no write is
            // attempted: the counter keeps accumulating in memory until the next flush.
            val candidate = if (wholeWriteSuppressed) null
                else entries.upsert(normalizedContext, rawWord, normalizedWord, LEARN_THRESHOLD)
            if (candidate != null) {
                val written = writeWhole(candidate)
                if (written) {
                    entries = candidate
                    snapshot = candidate.toSnapshot(subtypeId)
                }
                pending = if (written || lastWriteInstalled) noted.without(key) else noted
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
        val candidate = entries.remove(normalizedContext, normalizedWord)
        if (candidate === entries) {
            // Not saved, so already gone; the quarantine purge still runs.
            if (!purgeFromQuarantine(normalizedContext, normalizedWord)) return false
            dropPending(normalizedContext, normalizedWord)
            return true
        }
        val previousEntries = entries
        val previousSnapshot = snapshot
        val activeFile = File(
            runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return false,
            TpersbFormat.personalBigramsFileName(subtypeId),
        )
        val previousActiveBytes = try {
            if (activeFile.isFile) activeFile.readBytes() else null
        } catch (_: Exception) {
            return false
        }
        snapshot =
            if (candidate.isEmpty) PersonalBigramDictionary.EMPTY else candidate.toSnapshot(subtypeId)
        lastWriteInstalled = false
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
        if (!removed) {
            if (lastWriteInstalled) {
                entries = candidate
            } else {
                snapshot = previousSnapshot
            }
            return false
        }
        entries = candidate
        if (!purgeFromQuarantine(normalizedContext, normalizedWord)) {
            // Only a successful rollback puts memory back: when the active file could not be
            // restored, memory must keep the deletion the disk still shows; the answer is false
            // either way. See PersonalDictionaryStore.removeOnWorker.
            if (restoreActiveFile(activeFile, previousActiveBytes)) {
                entries = previousEntries
                snapshot = previousSnapshot
            }
            return false
        }
        dropPending(normalizedContext, normalizedWord)
        return true
    }

    /** The pending hash goes with a confirmed pair, so it is not learned again from scratch. */
    private fun dropPending(normalizedContext: String, normalizedWord: String) {
        salt?.let { existing ->
            val key = PendingCounters.keyOfPair(existing, normalizedContext, normalizedWord)
            if (pending.countOf(key) > 0) {
                pending = pending.without(key)
                pendingDirty = true
            }
        }
    }

    /** Puts the active file back after a failed multi-file deletion. */
    private fun restoreActiveFile(activeFile: File, bytes: ByteArray?): Boolean = try {
        val directory = directoryProvider.personalDirectory()
        if (bytes == null) {
            deleteFile(directory, activeFile)
        } else {
            writeBytesDurably(directory, activeFile, bytes)
        }
        true
    } catch (_: Exception) {
        false
    }

    /** Erases this subtype's learned pairs. See [PersonalDictionaryStore.clearAll]. */
    fun clearAll(outcome: PersonalMutationOutcome? = null) = onWorker {
        entries = PersonalBigramEntries.empty(maxPairs)
        counterFlushPending = false
        pending = PendingCounters.EMPTY
        pendingDirty = false
        salt = null
        snapshot = PersonalBigramDictionary.EMPTY
        if (!unlockGate()) {
            // The files are untouched, so the store is not loaded and this is a failure.
            loaded = false
            report(outcome, false)
            return@onWorker
        }
        loaded = true
        loadFailed = false
        // Independent deletions, as in PersonalDictionaryStore.clearAll.
        val storeGone = deleted { deleteFile() }
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull()
        val countersGone = directory != null &&
            deleted { deleteFile(directory, File(directory, pendingFileName())) }
        val saltGone = directory != null &&
            deleted { deleteFile(directory, File(directory, saltFileName())) }
        val quarantineGone = directory != null &&
            deleted { deleteFile(directory, File(directory, quarantineFileName())) }
        // The notice marks and the pending quarantine slot go too, outside the answer below; see
        // PersonalDictionaryStore.clearAll.
        if (directory != null) {
            deleted { deleteFile(directory, File(directory, quarantineNoticeFileName())) }
            deleted { deleteFile(directory, File(directory, pendingQuarantineNoticeFileName())) }
            deleted { deleteFile(directory, File(directory, pendingQuarantineFileName())) }
        }
        report(outcome, storeGone && countersGone && saltGone && quarantineGone)
    }

    override fun readInventoryOnWorker(): PersonalStoreInventory {
        loadFailed = false
        val available = open()
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull()
        val pendingData = directory != null && (pending.size > 0 ||
            File(directory, pendingFileName()).isFile || File(directory, saltFileName()).isFile)
        return PersonalStoreInventory(
            available,
            if (available) snapshot.size else 0,
            if (available) pending.size else 0,
            pendingData,
        )
    }

    override fun clearEntriesOnWorker(): Boolean {
        if (!unlockGate()) return false
        val pendingBefore = pending
        val pendingDirtyBefore = pendingDirty
        val saltBefore = salt
        entries = PersonalBigramEntries.empty(maxPairs)
        counterFlushPending = false
        snapshot = PersonalBigramDictionary.EMPTY
        val storeGone = deleted { deleteFile() }
        loaded = false
        loadFailed = false
        val opened = open()
        pending = pendingBefore
        pendingDirty = pendingDirtyBefore
        salt = saltBefore
        return storeGone && opened
    }

    override fun clearPendingOnWorker(): Boolean {
        if (!unlockGate()) return false
        pending = PendingCounters.EMPTY
        pendingDirty = false
        salt = null
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull()
        val countersGone = directory != null &&
            deleted { deleteFile(directory, File(directory, pendingFileName())) }
        val saltGone = directory != null &&
            deleted { deleteFile(directory, File(directory, saltFileName())) }
        val cleared = countersGone && saltGone
        if (!cleared && directory != null) {
            try {
                readPending(directory)
            } catch (_: Exception) {
                pending = PendingCounters.EMPTY
            }
        }
        return cleared
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
        if (bytes != null) validator.validate(bytes, subtypeId)
        val replaced = try {
            if (bytes != null) {
                ensureDirectory(directory)
                writeBytesDurably(
                    directory, File(directory, TpersbFormat.personalBigramsFileName(subtypeId)), bytes)
            } else {
                deleteFile()
            }
            true
        } catch (_: Exception) {
            false
        }
        entries = PersonalBigramEntries.empty(maxPairs)
        counterFlushPending = false
        snapshot = PersonalBigramDictionary.EMPTY
        loaded = false
        loadFailed = false
        val opened = open()
        return replaced && opened
    }

    /** Clears the notice marks. See [PersonalDictionaryStore.noticeDelivered]. */
    fun noticeDelivered() = onWorker {
        justQuarantined = false
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return@onWorker
        deleted { deleteFile(directory, File(directory, quarantineNoticeFileName())) }
        deleted { deleteFile(directory, File(directory, pendingQuarantineNoticeFileName())) }
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
    private fun purgeFromQuarantine(normalizedContext: String, normalizedWord: String): Boolean {
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return false
        val copy = File(directory, quarantineFileName())
        if (!copy.isFile) return true
        // A failed fsync on a previous operation may have left a stale temp behind; removing it
        // keeps any following createExclusiveTemp from stepping on it.
        cleanupTemps(directory)
        val salvage = try {
            PersonalBigramQuarantineSalvage.read(copy, subtypeId)
        } catch (_: Exception) {
            null
        } ?: return false
        val salvageWhole = salvage.readToEnd
        val kept = (0 until salvage.pairCount).filter { index ->
            salvage.contexts[index] != normalizedContext ||
                salvage.successorNormalizedForms[index] != normalizedWord
        }
        if (kept.size == salvage.pairCount && salvageWhole) return true // provably not in the copy
        if (!salvageWhole) return false // part of the copy is unread; the pair may be in it
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
            return try {
                if (!fileOps.delete(copy) || copy.exists()) return false
                fileOps.syncDirectory(directory)
                // The fsync landed; the empty slot above proves the delete.
                true
            } catch (_: Exception) {
                // A failed step may still have unlinked the copy; the reader's answer for a
                // missing slot is the only proof the delete is not rolled back.
                quarantineSlotIsEmpty(copy)
            }
        }
        val expected = candidate.serialize(subtypeId)
        return try {
            writeBytesDurably(directory, copy, expected)
            true
        } catch (_: Exception) {
            // The temp, the open or the write may have failed before the replace and left the
            // original copy intact; only the expected bytes prove the purge.
            quarantineSlotHas(copy, expected)
        }
    }

    /** Whether the slot holds exactly [expected]; see `PersonalDictionaryStore.quarantineSlotHas`. */
    private fun quarantineSlotHas(copy: File, expected: ByteArray): Boolean = try {
        copy.isFile && copy.length() == expected.size.toLong() && copy.readBytes().contentEquals(expected)
    } catch (_: Exception) {
        false
    }

    /** Whether nothing can be read out of the slot; see `PersonalDictionaryStore.quarantineSlotIsEmpty`. */
    private fun quarantineSlotIsEmpty(copy: File): Boolean = try {
        !copy.isFile || PersonalBigramQuarantineSalvage.read(copy, subtypeId) == null
    } catch (_: Exception) {
        false
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
        // The flush boundary lifts the write suppression; see PersonalDictionaryStore.flush.
        wholeWriteSuppressed = false
        if (counterFlushPending && writeWhole(entries)) counterFlushPending = false
        if (pendingDirty) {
            pending = pending.prunedForFlush()
            if (writePending(pending)) pendingDirty = false
        }
    }

    /** Opens the store on its worker. See [PersonalDictionaryStore.prime]. */
    fun prime(outcome: PersonalMutationOutcome? = null) = onWorker {
        loadFailed = false
        report(outcome, open())
    }

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
        if (loadFailed || !unlockGate()) return false
        var loadSucceeded = false
        try {
            load()
            loadSucceeded = true
        } catch (_: Exception) {
            entries = PersonalBigramEntries.empty(maxPairs)
            pending = PendingCounters.EMPTY
            pendingDirty = false
            counterFlushPending = false
            snapshot = PersonalBigramDictionary.EMPTY
            loadFailed = true
        }
        // The only place the notice is raised; see PersonalDictionaryStore.open.
        if (justQuarantined || quarantineNoticeIsMarked()) {
            try {
                quarantineNotice?.onQuarantined()
            } catch (_: Exception) {
            }
        }
        if (!loadSucceeded) return false
        loaded = true
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
        // A read error propagates to [open]; see PersonalDictionaryStore.load.
        val bytes = PersonalFileReads.readCapped(file, TpersbFormat.MAX_FILE_SIZE)
        val validated = bytes?.let {
            try {
                validator.validate(it, subtypeId)
            } catch (_: Exception) {
                null
            }
        }
        if (validated == null) {
            if (!quarantine(directory, file)) throw IOException("cannot quarantine bigram file")
            entries = PersonalBigramEntries.empty(maxPairs)
            snapshot = PersonalBigramDictionary.EMPTY
            return
        }
        entries = PersonalBigramEntries.fromValidated(validated, maxPairs)
        snapshot = entries.toSnapshot(subtypeId)
    }

    /**
     * The whole-file write sequence. See `PersonalDictionaryStore.writeWhole`; every result also
     * drives [wholeWriteSuppressed] there and here.
     */
    private fun writeWhole(candidate: PersonalBigramEntries): Boolean {
        lastWriteInstalled = false
        val directory = directoryProvider.personalDirectory()
        val written = try {
            ensureDirectory(directory)
            cleanupTemps(directory)
            val bytes = candidate.serialize(subtypeId)
            val required = bytes.size.toLong() + FREE_SPACE_RESERVE_BYTES
            if (spaceProbe.usableBytes(directory) < required) {
                false
            } else {
                val temporary = createExclusiveTemp(directory)
                var replaced = false
                try {
                    outputOpener.open(temporary).use { output ->
                        output.write(bytes)
                        output.flush()
                        fileOps.syncFile(output.fd)
                    }
                    validator.validate(temporary, subtypeId)
                    val destination = File(directory, TpersbFormat.personalBigramsFileName(subtypeId))
                    fileOps.atomicReplace(temporary, destination)
                    replaced = true
                    lastWriteInstalled = true
                    fileOps.syncDirectory(directory)
                    writeCount++
                    true
                } catch (_: Exception) {
                    if (replaced) {
                        entries = candidate
                        snapshot = candidate.toSnapshot(subtypeId)
                    }
                    if (temporary.exists()) runCatching { fileOps.delete(temporary) }
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
        wholeWriteSuppressed = !written
        return written
    }

    /**
     * The salt for the pending hashes, in this subtype's own salt file, so erasing one feature
     * or language does not invalidate another's progress. See `PersonalDictionaryStore.saltOrCreate`.
     */
    private fun saltOrCreate(): ByteArray? {
        salt?.let { return it }
        return try {
            val directory = directoryProvider.personalDirectory()
            ensureDirectory(directory)
            val file = File(directory, saltFileName())
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

    /**
     * Reads the pending counters once, alongside the store itself. A read error propagates; a file
     * that does not parse is set aside (see `PersonalDictionaryStore.readPending`).
     */
    private fun readPending(directory: File) {
        val file = File(directory, pendingFileName())
        // Size check before the read; see PersonalDictionaryStore.readPending.
        if (!file.isFile || file.length() > PendingCounters.MAX_SERIALIZED_BYTES) {
            pending = PendingCounters.EMPTY
            return
        }
        val bytes = file.readBytes()
        val parsed = try {
            PendingCounters.parseOrNull(bytes)
        } catch (_: Exception) {
            null
        }
        if (parsed == null) {
            pending = PendingCounters.EMPTY
            quarantinePending(directory, file)
        } else {
            pending = parsed
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
    private fun quarantine(directory: File, file: File): Boolean {
        val moved = try {
            fileOps.atomicReplace(file, File(directory, quarantineFileName()))
            true
        } catch (_: Exception) {
            false
        }
        if (moved) {
            try {
                fileOps.syncDirectory(directory)
            } catch (_: Exception) {
            }
        }
        // Marked in both cases; see PersonalDictionaryStore.quarantine.
        justQuarantined = true
        runCatching {
            writeBytesDurably(directory, File(directory, quarantineNoticeFileName()), ByteArray(1))
        }
        return moved
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

    /** Sets aside an unreadable pending file, with no notice. See `PersonalDictionaryStore.quarantinePending`. */
    private fun quarantinePending(directory: File, file: File) {
        try {
            fileOps.atomicReplace(file, File(directory, pendingQuarantineFileName()))
            fileOps.syncDirectory(directory)
        } catch (_: Exception) {
        }
    }

    private fun pendingQuarantineFileName(): String = pendingFileName() + QUARANTINE_SUFFIX

    /** A pending-counter notice mark. See `PersonalDictionaryStore.pendingQuarantineNoticeFileName`. */
    private fun pendingQuarantineNoticeFileName(): String =
        "quarantine-notice-bigrams-$subtypeId-s1-f1-pending.flag"

    private fun pendingFileName(): String = "pending-bigrams-$subtypeId-s1-f1.bin"

    private fun saltFileName(): String = "salt-bigrams-$subtypeId-s1-f1.bin"

    /** Removes this subtype's `.tpersb` file. Fails only by throwing. */
    private fun deleteFile() {
        val directory = directoryProvider.personalDirectory()
        val file = File(directory, TpersbFormat.personalBigramsFileName(subtypeId))
        deleteFile(directory, file, trackInstalled = true)
    }

    private fun deleteFile(directory: File, file: File, trackInstalled: Boolean = false) {
        if (!file.exists()) return
        if (!fileOps.delete(file) && file.exists()) throw IOException("cannot remove personal file")
        if (trackInstalled) lastWriteInstalled = true
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
        private const val SALT_SIZE = 16

        /** Appended to the ordinary file name for the quarantine slot. See [PersonalDictionaryStore]. */
        private const val QUARANTINE_SUFFIX = ".quarantine"
    }
}
