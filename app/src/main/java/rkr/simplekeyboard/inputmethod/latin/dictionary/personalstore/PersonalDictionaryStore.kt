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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageClock
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.Executor

/**
 * The serialized owner of one subtype's personal `.tpers` file: every mutation is an event on
 * the single [executor], where the in-memory state lives; each success publishes an immutable
 * [PersonalDictionary] through the `@Volatile` [snapshot]. The UI thread does no I/O.
 *
 * A caught exception, the closed unlock gate included, drops the mutation. Only a validation
 * failure is quarantined; other load failures leave the store not loaded. Nothing here logs.
 */
internal class PersonalDictionaryStore(
    private val subtypeId: String,
    private val directoryProvider: PersonalDirectoryProvider,
    private val fileOps: DurableFileOps,
    private val outputOpener: PersonalOutputOpener,
    private val spaceProbe: SpaceProbe,
    private val clock: StorageClock,
    private val executor: Executor,
    private val validator: TpersValidator = TpersValidator(),
    private val unlockGate: () -> Boolean = { true },
    private val maxEntries: Int = TpersFormat.MAX_PERSONAL_ENTRIES.toInt(),
    private val quarantineNotice: PersonalQuarantineNotice? = null,
) {
    private val alphabet: Set<Int>? = PersonalSubtypes.alphabetFor(subtypeId)

    // Worker-confined state (touched only on [executor]).
    private var entries: PersonalEntries = PersonalEntries.empty(maxEntries)
    private var loaded = false
    private var pendingCounterFlush = false

    // Set when the last load failed without a validation verdict (an I/O error), lifted by
    // [prime], [clearAll] and [replaceAll]: until then [open] answers false without touching disk.
    private var loadFailed = false

    // Set by a failed whole-file write, lifted by the next flush boundary or a successful write:
    // while set, the learning path keeps graduated words in the pending counters instead of
    // retrying the write on every completion (see [writeWhole]).
    private var wholeWriteSuppressed = false

    // Set when this session quarantined the dictionary file, until the notice has been raised.
    // The durable half of the same mark is the file [quarantineNoticeFileName] names.
    private var justQuarantined = false

    // Progress towards learning, as salted truncated hashes. Held in memory and written on the
    // same boundary as the usage counters, never once per completed word.
    private var pending: PendingCounters = PendingCounters.EMPTY
    private var pendingDirty = false
    private var salt: ByteArray? = null

    /** Count of physical `.tpers` writes performed; a test counter (never grows on in-memory notes). */
    @Volatile
    var writeCount: Int = 0
        private set

    /** The published immutable snapshot; read by the engine's worker thread. */
    @Volatile
    var snapshot: PersonalDictionary = PersonalDictionary.EMPTY
        private set

    /**
     * The word-set version stamped into [snapshot]: bumped only when the published word set
     * changes (a new word, a removal, an import), never on a counter-only republish, so the glide
     * decoder does not rebuild its word index for an accepted suggestion. Runtime-only; nothing on
     * disk carries it.
     */
    @Volatile
    var structureVersion: Long = 0L
        private set

    /**
     * Manually adds one word (the settings screen's "Add word" action); a no-op if the word is not
     * eligible. [outcome] is told on the worker whether the whole-file write succeeded, so a failed
     * write is visible to the screen.
     */
    fun addManually(word: String, outcome: PersonalMutationOutcome? = null) = onWorker {
        val normalized = eligibleNormalizedForm(word)
        if (normalized == null) {
            report(outcome, false)
            return@onWorker
        }
        // Evaluated before reporting: a safe call on a null outcome would skip evaluating its
        // argument, and the write with it.
        // Adding a word already saved is a counter bump, not a word-set change.
        val saved = commitWrite(entries.upsert(word, normalized), !entries.containsNormalized(normalized))
        report(outcome, saved)
    }

    /**
     * Records one clean completion of [word]. The word enters the dictionary only after
     * [PendingCounters.LEARN_THRESHOLD] of them; until then only a salted truncated hash exists, in
     * memory between flushes. The threshold is 3 because the same typo is often made twice.
     */
    fun noteCompletion(word: String) = onWorker {
        val normalized = eligibleNormalizedForm(word) ?: return@onWorker
        if (entries.containsNormalized(normalized)) return@onWorker
        val key = PendingCounters.keyOf(saltOrCreate() ?: return@onWorker, normalized)
        val noted = pending.note(key)
        if (noted.countOf(key) >= PendingCounters.LEARN_THRESHOLD) {
            // Graduated: it goes into the dictionary itself, and its pending trace goes away.
            // While a failed whole-file write is suppressed, no candidate is built and no write
            // is attempted: the counter keeps accumulating in memory until the next flush.
            val candidate = if (wholeWriteSuppressed) null else entries.upsert(word, normalized)
            if (candidate != null && writeWhole(candidate)) {
                entries = candidate
                publish(candidate.toSnapshot(subtypeId), structural = true)
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
     * Removes one word and rewrites the file, or deletes it when the word was the last one.
     *
     * [outcome] is told whether the word is really gone. The removal is published to readers before
     * the write, so a keystroke during the write cannot show the word again; if the write fails, the
     * previous snapshot is restored, because the word is still saved.
     *
     * The word is also purged from the quarantined file when that copy can be rewritten safely. A
     * rewrite failure leaves the copy intact so its other recoverable words are not lost.
     */
    fun forget(word: String, outcome: PersonalMutationOutcome? = null) = onWorker {
        // The body runs inside a try: the worker has no UncaughtExceptionHandler, so a throw here
        // would kill the IME. A failed delete loses nothing (the word stays saved), so the failure
        // goes to [outcome], which gets exactly one answer.
        val removed = try {
            removeOnWorker(word)
        } catch (_: Exception) {
            false
        }
        report(outcome, removed)
    }

    /** The body of [forget], on the worker: returns whether the word is really gone. */
    private fun removeOnWorker(word: String): Boolean {
        if (!open()) return false
        if (alphabet == null) return false
        val normalized = PersonalWordFilter.normalize(word)
        // The pending hash goes with the word, so three more completions do not learn it again.
        salt?.let { existing ->
            val key = PendingCounters.keyOf(existing, normalized)
            if (pending.countOf(key) > 0) {
                pending = pending.without(key)
                pendingDirty = true
            }
        }
        val candidate = entries.remove(normalized)
        if (candidate === entries) {
            // The word was not saved, so it is gone as asked. The quarantine purge still runs: a
            // word can sit in the quarantined file without ever having been restored.
            purgeFromQuarantine(normalized)
            return true
        }
        val previousSnapshot = snapshot
        publish(
            if (candidate.isEmpty) PersonalDictionary.EMPTY else candidate.toSnapshot(subtypeId),
            structural = true,
        )
        // A throw here becomes `false` inside the mutation, so the snapshot restore below still
        // runs: the word is still on disk.
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
            entries = candidate
        } else {
            publish(previousSnapshot, structural = true)
            return false
        }
        purgeFromQuarantine(normalized)
        return true
    }

    /**
     * Drops the word from the quarantined file when possible. An empty rewritten copy is deleted;
     * a failed rewrite preserves the original bytes for recovery.
     */
    private fun purgeFromQuarantine(normalizedWord: String) {
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return
        val copy = File(directory, quarantineFileName())
        if (!copy.isFile) return
        val salvage = try {
            PersonalQuarantineSalvage.read(copy, subtypeId)
        } catch (_: Exception) {
            null
        } ?: return
        val kept = (0 until salvage.wordCount).filter { index ->
            salvage.normalizedForms[index] != normalizedWord
        }
        if (kept.size == salvage.wordCount) return // the word was not in the copy: nothing to purge
        try {
            var candidate = PersonalEntries.empty(maxEntries)
            for (index in kept) {
                candidate = candidate.upsert(salvage.rawForms[index], salvage.normalizedForms[index])
            }
            if (candidate.isEmpty) {
                deleteFile(directory, copy)
            } else {
                writeBytesDurably(directory, copy, candidate.serialize(subtypeId))
            }
        } catch (_: Exception) {
            // Keep the original copy: the other recoverable words are still the user's data.
        }
    }

    /**
     * Erases this subtype's personal dictionary: empties memory and deletes the file, the pending
     * counters, the salt and any quarantined copy of an unreadable file. The salt goes too, so the
     * hashes of a future session cannot be compared with those of the erased one; a new one is
     * created on demand.
     */
    fun clearAll(outcome: PersonalMutationOutcome? = null) = onWorker {
        entries = PersonalEntries.empty(maxEntries)
        pendingCounterFlush = false
        pending = PendingCounters.EMPTY
        pendingDirty = false
        salt = null
        publishLoaded(PersonalDictionary.EMPTY)
        if (!unlockGate()) {
            // Memory is empty but the files are untouched and come back on the next open, so the
            // store is not loaded and this is reported as a failure.
            loaded = false
            report(outcome, false)
            return@onWorker
        }
        loaded = true
        loadFailed = false
        // Independent deletions: a failure on one must not skip the others. A surviving salt would
        // keep the pending hashes valid, and half-learned words would come back.
        val dictionaryGone = deleted { deleteFile() }
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull()
        val countersGone = directory != null &&
            deleted { deleteFile(directory, File(directory, pendingFileName())) }
        val saltGone = directory != null &&
            deleted { deleteFile(directory, File(directory, saltFileName())) }
        // The quarantined file (see [quarantine]) holds the user's words, so a copy left behind is
        // a failed erasure.
        val quarantineGone = directory != null &&
            deleted { deleteFile(directory, File(directory, quarantineFileName())) }
        // The notice marks and the pending quarantine slot go too, but outside the answer below:
        // the marks hold none of the user's words and the slot only salted hashes that the
        // deleted salt invalidates, so failing to delete them must not turn "your words are gone"
        // into "the erasure failed".
        if (directory != null) {
            deleted { deleteFile(directory, File(directory, quarantineNoticeFileName())) }
            deleted { deleteFile(directory, File(directory, pendingQuarantineNoticeFileName())) }
            deleted { deleteFile(directory, File(directory, pendingQuarantineFileName())) }
        }
        report(outcome, dictionaryGone && countersGone && saltGone && quarantineGone)
    }

    /**
     * Replaces the whole store with [bytes] from a backup (null deletes the file), then re-reads
     * the disk state and publishes it as the snapshot. Runs on the worker, so it cannot race a
     * learning write. The bytes are validated again here even though the backup layer checks them
     * before anything is written: nothing is written when validation fails.
     *
     * Pending learning progress is flushed first, so a restore does not lose completions that did
     * not graduate yet; the salt stays, because the pending keys reference it. Usage-counter changes
     * not yet written are discarded: the imported file replaces the entries whole. A quarantined
     * copy, if one exists, is left alone — restoring it remains the user's separate decision.
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
            writeBytesDurably(directory, File(directory, TpersFormat.personalFileName(subtypeId)), bytes)
        } else {
            deleteFile()
        }
        // Re-read the disk state: the published snapshot now matches the imported file (or none).
        entries = PersonalEntries.empty(maxEntries)
        pendingCounterFlush = false
        publishLoaded(PersonalDictionary.EMPTY)
        loaded = false
        loadFailed = false
        open()
        return true
    }

    /**
     * Clears the notice mark on the worker once the notice has reached the user. Called by the
     * layer that shows the notice, not the one that raises it.
     */
    fun noticeDelivered() = onWorker {
        justQuarantined = false
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return@onWorker
        deleted { deleteFile(directory, File(directory, quarantineNoticeFileName())) }
        deleted { deleteFile(directory, File(directory, pendingQuarantineNoticeFileName())) }
    }

    /**
     * Reads what is still readable in the quarantined file and reports two numbers: how many words
     * came out, and whether the file was read to its end. No word and no path leaves here. `null`
     * means there is no file; a zero count means there is one but nothing was readable. When
     * [PersonalQuarantineReport.readToEnd] is false, the screen must say that part of it was lost.
     */
    fun inspectQuarantine(sink: PersonalQuarantineReportSink) = onWorker {
        val salvage = try {
            readQuarantine()
        } catch (_: Exception) {
            null
        }
        val report = salvage?.let { PersonalQuarantineReport(it.wordCount, it.readToEnd) }
        // As in [report]: a screen that has gone away must not kill the keyboard.
        try {
            sink.onInspected(report)
        } catch (_: Exception) {
        }
    }

    /**
     * Puts the salvaged words back into the dictionary, only at the user's request. Words already in
     * the list are skipped, not upserted, so their usage order is unchanged and a second run is
     * harmless. The quarantined file is kept: discarding it is a separate action, and [clearAll]
     * removes it.
     */
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

    /** The body of [restoreQuarantine], on the worker: returns whether the words are really saved. */
    private fun restoreOnWorker(): Boolean {
        if (!open()) return false
        if (alphabet == null) return false
        val salvage = readQuarantine() ?: return false
        if (salvage.wordCount == 0) return false
        var candidate = entries
        var added = 0
        for (index in 0 until salvage.wordCount) {
            val normalized = salvage.normalizedForms[index]
            if (candidate.containsNormalized(normalized)) continue
            candidate = candidate.upsert(salvage.rawForms[index], normalized)
            added++
        }
        // Every salvaged word was already saved: nothing to write, and the request is fulfilled.
        if (added == 0) return true
        return commitWrite(candidate, structural = true)
    }

    /** Reads the quarantined file behind the unlock gate; `null` when there is none to read. */
    private fun readQuarantine(): PersonalQuarantineSalvage? {
        if (!unlockGate()) return null
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return null
        if (!directory.isDirectory) return null
        return PersonalQuarantineSalvage.read(File(directory, quarantineFileName()), subtypeId)
    }

    /**
     * Records an accepted personal suggestion as a use: bumps the counter and LRU serial in memory
     * and publishes the snapshot, without rewriting the file (see [flush]). Runs on the executor, so
     * it cannot race [clearAll].
     */
    fun noteAcceptedSuggestion(word: String) = onWorker {
        if (!open()) return@onWorker
        alphabet ?: return@onWorker
        val candidate = entries.noteUse(PersonalWordFilter.normalize(word)) ?: return@onWorker
        entries = candidate
        // A counter bump is not a word-set change: the version is kept, so the glide decoder does
        // not rebuild its word index here.
        publish(candidate.toSnapshot(subtypeId), structural = false)
        pendingCounterFlush = true
    }

    /**
     * Writes changed counters and serials to disk. Called at the end of an input session
     * (`SuggestionsController.onFinishInput`), the only point where usage counters and pending
     * hashes are written.
     */
    fun flush() = onWorker {
        if (!open()) return@onWorker
        // The flush boundary lifts the write suppression: the learning path may retry from here.
        wholeWriteSuppressed = false
        if (pendingCounterFlush && writeWhole(entries)) pendingCounterFlush = false
        if (pendingDirty) {
            pending = pending.prunedForFlush()
            if (writePending(pending)) pendingDirty = false
        }
    }

    /**
     * Opens the store on its worker if it is not open yet, publishing the snapshot the engine will
     * read; a load that failed before is tried again. [outcome] is told whether the store is now
     * loaded. Safe to call from any thread.
     */
    fun prime(outcome: PersonalMutationOutcome? = null) = onWorker {
        loadFailed = false
        report(outcome, open())
    }

    /** Test hook: runs [block] on the store's executor (so tests can drive the serialized owner). */
    fun runOnWorker(block: () -> Unit) = onWorker(block)

    private fun onWorker(block: () -> Unit) = executor.execute(block)

    private fun eligibleNormalizedForm(word: String): String? {
        if (!open()) return null
        val alpha = alphabet ?: return null
        return PersonalWordFilter.acceptedNormalizedForm(word, alpha)
    }

    private fun commitWrite(candidate: PersonalEntries, structural: Boolean): Boolean {
        if (!writeWhole(candidate)) return false
        entries = candidate
        publish(candidate.toSnapshot(subtypeId), structural)
        return true
    }

    /**
     * Publishes [next] as the snapshot. [structural] says the word set may have changed and bumps
     * [structureVersion]; a counter-only republish keeps it. The version rides inside the snapshot,
     * so a reader's one volatile read carries a consistent (words, version) pair.
     */
    private fun publish(next: PersonalDictionary, structural: Boolean) {
        if (structural) structureVersion++
        snapshot = next.withStructureVersion(structureVersion)
    }

    /** Publishes a disk-loaded [next]; the version bumps only when the word set really changed. */
    private fun publishLoaded(next: PersonalDictionary) = publish(next, !sameWordSet(snapshot, next))

    /** Whether two snapshots hold the same normalized forms in the same (canonical) order. */
    private fun sameWordSet(first: PersonalDictionary, second: PersonalDictionary): Boolean {
        if (first.size != second.size) return false
        for (index in 0 until first.size) {
            if (first.normalizedFormAt(index) != second.normalizedFormAt(index)) return false
        }
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
     * Opens the directory once, after the unlock gate: removes stale temps, reads the file and
     * quarantines one that fails validation (see [quarantine]). Returns true once loaded; any other
     * failure keeps the store not loaded and refusing writes until [prime] tries again.
     */
    private fun open(): Boolean {
        if (loaded) return true
        if (loadFailed || !unlockGate()) return false
        try {
            load()
        } catch (_: Exception) {
            entries = PersonalEntries.empty(maxEntries)
            pending = PendingCounters.EMPTY
            pendingDirty = false
            pendingCounterFlush = false
            publishLoaded(PersonalDictionary.EMPTY)
            loadFailed = true
            return false
        }
        loaded = true
        // The only place the notice is raised. Every open passes here, both after a quarantine now
        // and when a previous process left the mark, so an unannounced loss is announced at the next
        // open. `justQuarantined` covers the case where the mark could not be written.
        if (justQuarantined || quarantineNoticeIsMarked()) {
            // The seam posts to an Activity on the UI thread; a throw must not kill the worker.
            try {
                quarantineNotice?.onQuarantined()
            } catch (_: Exception) {
            }
        }
        return true
    }

    /** The body of [open], where an early exit is a plain `return` rather than a `return true`. */
    private fun load() {
        val directory = directoryProvider.personalDirectory()
        if (!directory.isDirectory) {
            publishLoaded(PersonalDictionary.EMPTY)
            return
        }
        cleanupTemps(directory)
        readPending(directory)
        val file = File(directory, TpersFormat.personalFileName(subtypeId))
        if (!file.isFile) {
            publishLoaded(PersonalDictionary.EMPTY)
            return
        }
        // A read error propagates to [open]; only the bytes that were read are judged here.
        val bytes = PersonalFileReads.readCapped(file, TpersFormat.MAX_FILE_SIZE)
        val validated = bytes?.let {
            try {
                validator.validate(it, subtypeId)
            } catch (_: Exception) {
                null
            }
        }
        if (validated == null) {
            quarantine(directory, file)
            entries = PersonalEntries.empty(maxEntries)
            publishLoaded(PersonalDictionary.EMPTY)
            return
        }
        entries = PersonalEntries.fromValidated(validated, maxEntries)
        publishLoaded(entries.toSnapshot(subtypeId))
    }

    /**
     * The whole-file write sequence: exclusive temp in the same directory → write → flush → fsync
     * file → re-validate the written bytes → atomic replace → fsync directory. Returns true only
     * when every step succeeded. On any caught failure the temp is removed and false is returned,
     * leaving the previous file untouched. An uncaught [Error] (a simulated process death) leaves
     * the temp for the next open to discard.
     *
     * Every result also drives [wholeWriteSuppressed]: a failure suppresses the learning path's
     * retries until the next flush boundary, a success lifts the suppression at once, so a full
     * disk costs one failed write per input session while the pending counters keep accumulating
     * in memory. Callers the user asked directly (add, forget, restore) always attempt their
     * write, so their answer stays honest.
     */
    private fun writeWhole(candidate: PersonalEntries): Boolean {
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
                try {
                    outputOpener.open(temporary).use { output ->
                        output.write(bytes)
                        output.flush()
                        fileOps.syncFile(output.fd)
                    }
                    validator.validate(temporary, subtypeId)
                    val destination = File(directory, TpersFormat.personalFileName(subtypeId))
                    fileOps.atomicReplace(temporary, destination)
                    fileOps.syncDirectory(directory)
                    writeCount++
                    true
                } catch (_: Exception) {
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
     * The salt for the pending hashes: random bytes in this subtype's own salt file, created on
     * first use and deleted by [clearAll]. Returns null when it can be neither read nor created;
     * then nothing is counted (no learning rather than unsalted keys).
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
     * Reads the pending counters once, alongside the dictionary itself. A file that does not parse
     * is set aside (see [quarantinePending]) instead of being silently dropped; a read error
     * propagates to [open].
     */
    private fun readPending(directory: File) {
        val file = File(directory, pendingFileName())
        // Size check before the read: a file past MAX_SERIALIZED_BYTES cannot parse, and past
        // 2 GiB readBytes() throws an OutOfMemoryError, which the catch below does not stop. A
        // file rejected by length alone is left in place for the next flush to overwrite: setting
        // aside possibly gigabytes of garbage would pin that space in the quarantine slot.
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

    /**
     * Writes one small file through the same temp → fsync → atomic replace → directory fsync
     * sequence as the dictionary, so a half-written pending file or salt cannot drop learning
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
     * and marks that the user is owed a notice. An interrupted write or checksum mismatch usually
     * leaves most words readable, so they are kept for a restore.
     *
     * One slot per language, replaced by the next failure. Its name is neither a `.tpers` nor a temp
     * name, so nothing validates it and [cleanupTemps] leaves it alone; [clearAll] removes it. A
     * failed move leaves the original file untouched for a later recovery attempt.
     */
    private fun quarantine(directory: File, file: File) {
        try {
            fileOps.atomicReplace(file, File(directory, quarantineFileName()))
            fileOps.syncDirectory(directory)
        } catch (_: Exception) {
        }
        // Marked in both cases: the list is empty and the user did not empty it. The mark is kept
        // on disk, since the process may end before any screen shows the notice. It is one byte
        // whose existence is the message: no word, no path, no reason.
        justQuarantined = true
        runCatching {
            writeBytesDurably(directory, File(directory, quarantineNoticeFileName()), ByteArray(1))
        }
    }

    private fun quarantineFileName(): String =
        TpersFormat.personalFileName(subtypeId) + QUARANTINE_SUFFIX

    private fun quarantineNoticeFileName(): String = "quarantine-notice-$subtypeId-s1-f1.flag"

    /** Whether the dictionary's on-disk notice mark exists; false on error. */
    private fun quarantineNoticeIsMarked(): Boolean = try {
        File(directoryProvider.personalDirectory(), quarantineNoticeFileName()).isFile
    } catch (_: Exception) {
        false
    }

    /**
     * Moves an unreadable pending file into this subtype's single pending quarantine slot. No
     * notice: the slot holds only salted hashes that nothing restores, and the counters relearn by
     * themselves. [clearAll] removes the slot.
     */
    private fun quarantinePending(directory: File, file: File) {
        try {
            fileOps.atomicReplace(file, File(directory, pendingQuarantineFileName()))
            fileOps.syncDirectory(directory)
        } catch (_: Exception) {
        }
    }

    private fun pendingQuarantineFileName(): String = pendingFileName() + QUARANTINE_SUFFIX

    /**
     * A pending-counter notice mark. Nothing raises a notice from it; [clearAll] and
     * [noticeDelivered] remove one left on disk.
     */
    private fun pendingQuarantineNoticeFileName(): String =
        "quarantine-notice-$subtypeId-s1-f1-pending.flag"

    private fun pendingFileName(): String = "pending-$subtypeId-s1-f1.bin"

    private fun saltFileName(): String = "salt-$subtypeId-s1-f1.bin"

    /**
     * Removes this subtype's `.tpers` file. Fails only by throwing; callers handle it with `try` or
     * [deleted].
     */
    private fun deleteFile() {
        val directory = directoryProvider.personalDirectory()
        val file = File(directory, TpersFormat.personalFileName(subtypeId))
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
        private const val TEMP_PREFIX = ".personal-"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_TEMP_ATTEMPTS = 100
        private const val FREE_SPACE_RESERVE_BYTES = 64L * 1024L
        private const val SALT_SIZE = 16

        /**
         * Appended to the ordinary file name for the quarantine slot. Neither a `.tpers` nor a temp
         * name, so nothing reads it or cleans it up by accident.
         */
        private const val QUARANTINE_SUFFIX = ".quarantine"
    }
}
