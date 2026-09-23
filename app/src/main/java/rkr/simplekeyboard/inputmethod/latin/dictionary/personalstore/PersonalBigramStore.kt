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
 * The serialized owner of one subtype's personal-bigram `.tpersb` file (P1 of Phase 2,
 * docs/ROADMAP-P2.md) — the deliberate mirror of [PersonalDictionaryStore] for word PAIRS, and
 * every structural guarantee of that class holds here unchanged:
 *
 * Every mutation is an event on the single background [executor], so all in-memory state lives on
 * one worker and never on the UI thread. After each SUCCESSFUL mutation a fresh immutable
 * [PersonalBigramDictionary] snapshot is published through the `@Volatile` [snapshot] reference;
 * the engine's worker thread reads it. The UI thread does no I/O, no checksum, no read and no
 * write.
 *
 * Whole-file write only, in the frozen sequence: exclusive temp in the same directory → write →
 * flush → fsync file → RE-VALIDATE the written bytes → atomic replace → fsync directory. A partial
 * temp never becomes the main file; any failure leaves the previous valid file untouched; temp
 * garbage is removed when the store next opens the directory.
 *
 * A pair is never written in plaintext before it has survived [LEARN_THRESHOLD] clean
 * observations: until then only a salted truncated hash of it exists (see [PendingCounters]), and
 * only in memory between flushes. The threshold is 2 — one lower than the words store's 3, because
 * the context half of a pair is independently gated ([contextMembership]: a context the keyboard
 * does not know is dropped at graduation), so the accidental-pair risk the third observation
 * guards against in the words design is already closed here by the dictionary.
 *
 * Fail-closed everywhere: a caught exception (including the unlock gate closing the
 * credential-protected path before the first unlock) drops the mutation and leaves the feature
 * empty rather than throwing. Nothing here logs, and no message carries a user's word or a path.
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

    // Set when THIS session set a file aside, and kept only until the notice has been raised: the
    // durable half of the same mark is the file [quarantineNoticeFileName] names.
    private var justQuarantined = false

    // Progress towards learning, as salted truncated hashes. Held in memory and written on the
    // same boundary as the usage counters — never once per completed pair.
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
     * Records ONE clean completion of the pair ([rawContext], [rawWord]): [rawWord] is the word
     * whose clean run just ended, [rawContext] the committed word immediately before it. Nothing
     * is written to the file until the pair has survived [LEARN_THRESHOLD] such observations; until
     * then only a salted truncated hash exists, and only in memory between flushes.
     *
     * At graduation the context must additionally prove itself through [contextMembership]: a word
     * of the shipped dictionary of this subtype or of its personal dictionary. That is what makes
     * the lower threshold safe (see the class doc), and it is checked at graduation rather than at
     * observation for two reasons: the check reads the dictionary mapping and belongs off the UI
     * thread, and a word learned into the personal dictionary BETWEEN two observations still counts
     * as known.
     *
     * An already-learned pair skips the counters entirely: the observation bumps its frequency and
     * LRU serial IN MEMORY only (flushed at the session boundary, like an accepted tap), never
     * rewriting the file per completed word.
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
                // The context is no word of this language that the keyboard knows: the pair is not
                // a candidate and never was. Drop its progress so a repeated typo does not keep
                // paying the membership check.
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
     * Records an accepted personal prediction as a use: bumps the usage counter and LRU serial IN
     * MEMORY only, publishing the updated snapshot. It never rewrites the file — flushing whole for
     * every tap would be up to 64 KiB plus two fsyncs per tap. A tap on anything that is NOT a
     * learned pair (a static successor, a word form, a fallback word) finds no pair and changes
     * nothing. Runs as an executor event, not inline on the UI thread, so it can never race
     * [clearAll].
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

    /**
     * Removes one pair and rewrites (or deletes, when it was the last) the file. The guarantees of
     * the words store hold verbatim: [outcome] is told whether the pair is really gone, and the
     * removal is published to READERS before the write, not after it — "erased means erased" — with
     * the previous snapshot restored if the write fails.
     *
     * The pair is ALSO purged from the quarantine copy, if one exists: a copy that still holds a
     * pair the user deleted would resurrect it on the next restore, and "forgotten" must not have
     * a back door. A copy that cannot be rewritten without the pair is deleted outright — losing
     * the salvage of other pairs is the smaller lie than keeping a deleted one.
     */
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
        // The pending hash goes with the pair: forgetting it must not leave progress behind that
        // would re-learn it after two more observations.
        salt?.let { existing ->
            val key = PendingCounters.keyOfPair(existing, normalizedContext, normalizedWord)
            if (pending.countOf(key) > 0) {
                pending = pending.without(key)
                pendingDirty = true
            }
        }
        val candidate = entries.remove(normalizedContext, normalizedWord)
        if (candidate === entries) {
            // The pair was not in this store at all: nothing to remove, and from where the user
            // stands it is gone, which is what they asked for. The quarantine purge still runs —
            // a pair can sit in the copy without ever having been restored.
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

    /**
     * Erases this subtype's personal bigrams: empties memory and deletes the file, the pending
     * counters, the salt and any quarantined copy of an unreadable file. The salt goes too, so the
     * hashes of a future session cannot be compared with those of the erased one; a new one is
     * created on demand.
     */
    fun clearAll(outcome: PersonalMutationOutcome? = null) = onWorker {
        entries = PersonalBigramEntries.empty(maxPairs)
        counterFlushPending = false
        pending = PendingCounters.EMPTY
        pendingDirty = false
        salt = null
        snapshot = PersonalBigramDictionary.EMPTY
        loaded = true
        if (!unlockGate()) {
            // Memory is empty, the files are untouched and the next process start reads them all
            // back. The screen shows an empty list either way, so this is exactly the case that must
            // not pass for success.
            report(outcome, false)
            return@onWorker
        }
        // Independent deletions, exactly like the words store: a failure on one must not skip the
        // others — the same salt would otherwise keep the erased session's hashes comparable, and
        // a quarantine copy left behind would resurrect pairs on the next restore.
        val storeGone = deleted { deleteFile() }
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull()
        val countersGone = directory != null &&
            deleted { deleteFile(directory, File(directory, pendingFileName())) }
        val saltGone = directory != null &&
            deleted { deleteFile(directory, File(directory, SALT_FILE_NAME)) }
        val quarantineGone = directory != null &&
            deleted { deleteFile(directory, File(directory, quarantineFileName())) }
        // The "not told yet" mark goes as well, but DELIBERATELY outside the answer below: it is not
        // one of the user's pairs. Its only cost when it survives is one pointless notice about a
        // list the user emptied by hand.
        if (directory != null) {
            deleted { deleteFile(directory, File(directory, quarantineNoticeFileName())) }
        }
        report(outcome, storeGone && countersGone && saltGone && quarantineGone)
    }

    /** Clears the "not told yet" mark, on the worker, once the notice has actually reached the user. */
    fun noticeDelivered() = onWorker {
        justQuarantined = false
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return@onWorker
        deleted { deleteFile(directory, File(directory, quarantineNoticeFileName())) }
    }

    /**
     * Reads what is still readable in the quarantine copy and hands back TWO NUMBERS — how many
     * pairs came out, and whether the copy was read to its end. No word and no path leaves here;
     * `null` means there is no copy at all.
     */
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

    /**
     * Puts the salvaged pairs back into the store, at the user's explicit request and never on its
     * own. Pairs already in the list are SKIPPED rather than upserted: a restore must not quietly
     * promote them up the usage order, and skipping is what makes running it twice harmless.
     * Pairs the user deleted since the copy was made are NOT in the copy any more ([forget] purges
     * them there too), so a restore cannot resurrect them.
     *
     * The copy is deliberately NOT removed on success — restoring and discarding are two separate
     * actions, and the damaged tail survives a restore for a later, better reader. "Erase all"
     * still takes it with everything else (see [clearAll]).
     */
    fun restoreQuarantine(outcome: PersonalMutationOutcome? = null) = onWorker {
        val restored = try {
            restoreOnWorker()
        } catch (_: Exception) {
            false
        }
        report(outcome, restored)
    }

    /** Removes the quarantine copy and nothing else. */
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
        // Every salvaged pair was already there. Nothing to write, and from where the user stands
        // the pairs they asked for are in the list, which is what they asked for.
        if (added == 0) return true
        return commitWrite(candidate)
    }

    /**
     * The quarantine half of [forget]: drops the pair from the copy too, so a later restore cannot
     * resurrect what the user deleted. A copy that becomes empty is removed; a copy that cannot be
     * rewritten is removed as well — fail-closed toward NOT resurrecting, at the price of losing
     * the salvage of the other pairs.
     */
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
            // The copy could not be rewritten without the deleted pair. Deleting it outright loses
            // the salvage of the other pairs — and keeping it would resurrect a pair the user was
            // told is gone. Erased means erased.
            deleted { deleteFile(directory, copy) }
        }
    }

    /** Reads the copy behind the unlock gate. `null` when there is no readable copy to speak of. */
    private fun readQuarantine(): PersonalBigramQuarantineSalvage? {
        if (!unlockGate()) return null
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return null
        if (!directory.isDirectory) return null
        return PersonalBigramQuarantineSalvage.read(File(directory, quarantineFileName()), subtypeId)
    }

    /**
     * Flushes in-memory counter/serial changes to disk once, only when something changed — the one
     * boundary (`SuggestionsController.onFinishInput`) where both the usage counters and the pending
     * hashes are written, never per keystroke and never per completed pair.
     */
    fun flush() = onWorker {
        if (!open()) return@onWorker
        if (counterFlushPending && writeWhole(entries)) counterFlushPending = false
        if (pendingDirty) {
            pending = pending.prunedForFlush()
            if (writePending(pending)) pendingDirty = false
        }
    }

    /**
     * Opens the store on its worker if it is not open yet, publishing the snapshot the engine will
     * read. A no-op afterwards. Safe to call from any thread — like every other mutation it is an
     * event on the executor, so the file read never lands on the caller's thread.
     */
    fun prime() = onWorker { open() }

    /** Test hook: runs [block] on the store's executor (so tests can drive the serialized owner). */
    fun runOnWorker(block: () -> Unit) = onWorker(block)

    private fun onWorker(block: () -> Unit) = executor.execute(block)

    private fun isKnownContext(normalizedContext: String): Boolean = try {
        contextMembership.isKnownContext(subtypeId, normalizedContext)
    } catch (_: Exception) {
        // A broken oracle vetoes the learn: fail-closed toward writing LESS, exactly like every
        // other check in this class. The pair's pending progress is dropped by the caller.
        false
    }

    private fun commitWrite(candidate: PersonalBigramEntries): Boolean {
        if (!writeWhole(candidate)) return false
        entries = candidate
        snapshot = candidate.toSnapshot(subtypeId)
        return true
    }

    /**
     * Runs ONE erasure step so that its failure can neither skip the next step nor escape: returns
     * whether it went through. Nothing is logged — not the path, not the reason — which is why the
     * boolean has to travel back to the caller instead.
     */
    private inline fun deleted(step: () -> Unit): Boolean = try {
        step()
        true
    } catch (_: Exception) {
        false
    }

    /**
     * The one way a mutation answers its caller. The callback belongs to an Activity and posts to
     * the UI thread; a throw out of it on this bare single-thread executor — created with no
     * `UncaughtExceptionHandler` — would reach `KillApplicationHandler`, so the answer travels
     * inside its own `try`. And because [succeeded] is an ARGUMENT it is always evaluated.
     */
    private fun report(outcome: PersonalMutationOutcome?, succeeded: Boolean) {
        if (outcome == null) return
        try {
            outcome.onFinished(succeeded)
        } catch (_: Exception) {
        }
    }

    /**
     * Opens the directory once: honours the unlock gate (before the first unlock the path is
     * physically inaccessible, so the feature stays empty and untouched), removes stale temps, reads
     * the file into the model, and sets an unreadable file ASIDE (see [quarantine]), publishing an
     * empty snapshot in the same step. Returns true once the store is loaded.
     */
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
        // The single place the notice is raised: every open passes here — the one that quarantined
        // the file just now AND the one that merely found the mark a previous process left behind.
        if (justQuarantined || quarantineNoticeIsMarked()) {
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

    /**
     * The whole-file write sequence. Returns true only when every step succeeded. On any caught
     * failure the temp is removed and false is returned, leaving the previous file untouched. An
     * uncaught [Error] (a simulated process death) leaves the temp for the next open to discard.
     */
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
     * The salt for the pending hashes: 16 random bytes in the store's OWN `salt-bigrams.bin`,
     * created on first use and destroyed by [clearAll]. The bigram store does not share the words
     * store's `salt.bin`: erasing one feature's data must not silently invalidate the other
     * feature's pending progress. Returns null when the salt can neither be read nor created — in
     * which case nothing is counted at all, the fail-closed direction.
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
            // The size cap comes BEFORE the read: a file past MAX_SERIALIZED_BYTES can never parse
            // (parse bounds the record count by MAX_PENDING), so reading it would only allocate for
            // garbage — and past 2 GiB readBytes() throws an OutOfMemoryError, an Error that the
            // catch below cannot stop (S2 of docs/AUDIT-2026-08-31.md).
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
     * Writes one small fixed-size file through the same temp → fsync → atomic replace → directory
     * fsync sequence the store itself uses. The pending file and the salt are not user text, but a
     * half-written one would be read as garbage, and fail-closed parsing would then silently drop a
     * user's progress.
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
     * and tells [quarantineNotice] that the list the user will see is empty for a reason — the
     * same discipline as the words store: ONE slot per language, replaced by the next corruption,
     * named so that nothing validates, reads or cleans it up by accident, and removed by
     * [clearAll]. If the move itself cannot happen the unreadable file is removed after all:
     * leaving it where the reader looks would fail validation again on every single start.
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
        // Said in both cases: what the user is told is that the list is empty and that they did not
        // do it, which is equally true whether the copy was kept or could not be made. The mark is
        // one byte whose EXISTENCE is the whole message: no word, no path, no reason — a flag.
        justQuarantined = true
        runCatching {
            writeBytesDurably(directory, File(directory, quarantineNoticeFileName()), ByteArray(1))
        }
    }

    private fun quarantineFileName(): String =
        TpersbFormat.personalBigramsFileName(subtypeId) + QUARANTINE_SUFFIX

    private fun quarantineNoticeFileName(): String = "quarantine-notice-bigrams-$subtypeId-s1-f1.flag"

    /** Whether the on-disk "not told yet" mark is there. Fail-closed to "already told". */
    private fun quarantineNoticeIsMarked(): Boolean = try {
        File(directoryProvider.personalDirectory(), quarantineNoticeFileName()).isFile
    } catch (_: Exception) {
        false
    }

    private fun pendingFileName(): String = "pending-bigrams-$subtypeId-s1-f1.bin"

    /**
     * Removes this subtype's `.tpersb` file. Returns nothing ON PURPOSE, like the words store's:
     * a throw is the single failure signal, and the callers' `try`/[deleted] is what answers for it.
     */
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
        /**
         * Clean observations of one pair before it is written into the store itself (P1 pin).
         * Lower than the words store's 3 on purpose: the context half is independently gated by
         * the dictionary-membership check at graduation, which is the filter the third observation
         * provides for single words.
         */
        const val LEARN_THRESHOLD = 2

        private const val TEMP_PREFIX = ".personal-bigrams-"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_TEMP_ATTEMPTS = 100
        private const val FREE_SPACE_RESERVE_BYTES = 64L * 1024L
        private const val SALT_FILE_NAME = "salt-bigrams.bin"
        private const val SALT_SIZE = 16

        /**
         * Appended to the ordinary file name for the one quarantine slot. Deliberately not a
         * `.tpersb` name and not a temp name: nothing reads it, nothing cleans it up by accident.
         */
        private const val QUARANTINE_SUFFIX = ".quarantine"
    }
}
