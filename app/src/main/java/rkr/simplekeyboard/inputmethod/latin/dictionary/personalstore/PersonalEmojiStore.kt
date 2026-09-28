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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalEmojiDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageClock
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiTextUtils
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.Executor

/**
 * The serialized owner of one subtype's personal-emoji `.tpersem` file — the deliberate mirror of
 * [PersonalBigramStore] for (word, emoji) co-occurrences, and every structural guarantee of that
 * class holds here unchanged:
 *
 * Every mutation is an event on the single background [executor], so all in-memory state lives on
 * one worker and never on the UI thread. After each SUCCESSFUL mutation a fresh immutable
 * [PersonalEmojiDictionary] snapshot is published through the `@Volatile` [snapshot] reference;
 * the engine's worker thread reads it. The UI thread does no I/O, no checksum, no read and no
 * write.
 *
 * Whole-file write only, in the frozen sequence: exclusive temp in the same directory → write →
 * flush → fsync file → RE-VALIDATE the written bytes → atomic replace → fsync directory. A partial
 * temp never becomes the main file; any failure leaves the previous valid file untouched; temp
 * garbage is removed when the store next opens the directory.
 *
 * An entry is never written in plaintext before it has survived [LEARN_THRESHOLD] clean
 * observations: until then only a salted truncated hash of it exists (see [PendingCounters]), and
 * only in memory between flushes.
 *
 * Unlike the pairs store there is NO context-membership probe at graduation — by design. The pairs
 * store needs that gate because its context half is an arbitrary previously-committed word whose
 * standing as a word of the language must be proven before it may seed predictions. Here the word
 * half comes from real committed editor text and passes the very same content filter
 * (alphabet, length, casing, no combining marks) the pairs store applies to both halves, and the
 * emoji half is a deliberate pick from the panel, never a keystroke: both halves of an entry are
 * the user's own explicit acts, so a dictionary-membership check on top would only drop honest
 * co-usage (e.g. a name the static dictionary does not know) without filtering any junk.
 *
 * Fail-closed everywhere: a caught exception (including the unlock gate closing the
 * credential-protected path before the first unlock) drops the mutation and leaves the feature
 * empty rather than throwing. Nothing here logs, and no message carries a user's word or a path.
 */
internal class PersonalEmojiStore(
    private val subtypeId: String,
    private val directoryProvider: PersonalDirectoryProvider,
    private val fileOps: DurableFileOps,
    private val outputOpener: PersonalOutputOpener,
    private val spaceProbe: SpaceProbe,
    private val clock: StorageClock,
    private val executor: Executor,
    private val validator: TpersemValidator = TpersemValidator(),
    private val unlockGate: () -> Boolean = { true },
    private val maxEntries: Int = TpersemFormat.MAX_PERSONAL_EMOJI_ENTRIES.toInt(),
    private val quarantineNotice: PersonalQuarantineNotice? = null,
) {
    private val alphabet: Set<Int>? = PersonalSubtypes.alphabetFor(subtypeId)

    // Worker-confined state (touched only on [executor]).
    private var entries: PersonalEmojiEntries = PersonalEmojiEntries.empty(maxEntries)
    private var loaded = false
    private var counterFlushPending = false

    // Set when THIS session set a file aside, and kept only until the notice has been raised: the
    // durable half of the same mark is the file [quarantineNoticeFileName] names.
    private var justQuarantined = false

    // Progress towards learning, as salted truncated hashes. Held in memory and written on the
    // same boundary as the usage counters — never once per observed co-usage.
    private var pending: PendingCounters = PendingCounters.EMPTY
    private var pendingDirty = false
    private var salt: ByteArray? = null

    /** Count of physical `.tpersem` writes performed; a test counter (never grows on in-memory notes). */
    @Volatile
    var writeCount: Int = 0
        private set

    /** The published immutable snapshot; read by the engine's worker thread. */
    @Volatile
    var snapshot: PersonalEmojiDictionary = PersonalEmojiDictionary.EMPTY
        private set

    /**
     * Records ONE clean co-usage: [rawWord] is the word whose clean run just ended (the committed
     * editor text), [rawEmoji] the emoji cluster inserted right after it. Nothing is written to the
     * file until the (word, emoji) pair has survived [LEARN_THRESHOLD] such observations; until
     * then only a salted truncated hash exists, and only in memory between flushes.
     *
     * An already-learned entry skips the counters entirely: the observation bumps its frequency and
     * LRU serial IN MEMORY only (flushed at the session boundary, like an accepted tap), never
     * rewriting the file per completed word.
     */
    fun noteObservation(rawWord: String, rawEmoji: String) = onWorker {
        if (!open()) return@onWorker
        val alpha = alphabet ?: return@onWorker
        val normalizedWord = PersonalBigramWordFilter.acceptedNormalizedForm(rawWord, alpha)
            ?: return@onWorker
        val emoji = acceptedEmojiCluster(rawEmoji) ?: return@onWorker
        if (entries.containsEntry(normalizedWord, emoji)) {
            val candidate = entries.noteObservation(normalizedWord, emoji) ?: return@onWorker
            entries = candidate
            snapshot = candidate.toSnapshot(subtypeId)
            counterFlushPending = true
            return@onWorker
        }
        val key = PendingCounters.keyOf(
            saltOrCreate() ?: return@onWorker, pendingKey(normalizedWord, emoji),
        )
        val noted = pending.note(key)
        if (noted.countOf(key) >= LEARN_THRESHOLD) {
            // Graduated: it goes into the store itself, and its pending trace goes away.
            val candidate = entries.upsert(normalizedWord, emoji, LEARN_THRESHOLD)
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
     * Records an accepted learned-emoji suggestion as a use: bumps the usage counter and LRU serial
     * IN MEMORY only, publishing the updated snapshot. It never rewrites the file — flushing whole
     * for every tap would be up to 64 KiB plus two fsyncs per tap. A tap on anything that is NOT a
     * learned entry (a static emoji-suggest cell, a panel pick, a recent emoji) finds no entry and
     * changes nothing. Runs as an executor event, not inline on the UI thread, so it can never race
     * [clearAll].
     */
    fun noteUse(rawWord: String, rawEmoji: String) = onWorker {
        if (!open()) return@onWorker
        alphabet ?: return@onWorker
        val candidate = entries.noteUse(
            PersonalBigramWordFilter.normalize(rawWord), rawEmoji,
        ) ?: return@onWorker
        entries = candidate
        snapshot = candidate.toSnapshot(subtypeId)
        counterFlushPending = true
    }

    /**
     * Removes one entry and rewrites (or deletes, when it was the last) the file. The guarantees of
     * the words store hold verbatim: [outcome] is told whether the entry is really gone, and the
     * removal is published to READERS before the write, not after it — "erased means erased" — with
     * the previous snapshot restored if the write fails.
     *
     * The entry is ALSO purged from the quarantine copy, if one exists: a copy that still holds an
     * entry the user deleted would resurrect it on the next restore, and "forgotten" must not have
     * a back door. A copy that cannot be rewritten without the entry is deleted outright — losing
     * the salvage of other entries is the smaller lie than keeping a deleted one.
     */
    fun forget(rawWord: String, rawEmoji: String, outcome: PersonalMutationOutcome? = null) = onWorker {
        val removed = try {
            removeOnWorker(rawWord, rawEmoji)
        } catch (_: Exception) {
            false
        }
        report(outcome, removed)
    }

    /** The body of [forget], on the worker: returns whether the entry is really gone. */
    private fun removeOnWorker(rawWord: String, rawEmoji: String): Boolean {
        if (!open()) return false
        if (alphabet == null) return false
        val normalizedWord = PersonalBigramWordFilter.normalize(rawWord)
        // The pending hash goes with the entry: forgetting it must not leave progress behind that
        // would re-learn it after two more observations.
        salt?.let { existing ->
            val key = PendingCounters.keyOf(existing, pendingKey(normalizedWord, rawEmoji))
            if (pending.countOf(key) > 0) {
                pending = pending.without(key)
                pendingDirty = true
            }
        }
        val candidate = entries.remove(normalizedWord, rawEmoji)
        if (candidate === entries) {
            // The entry was not in this store at all: nothing to remove, and from where the user
            // stands it is gone, which is what they asked for. The quarantine purge still runs —
            // an entry can sit in the copy without ever having been restored.
            purgeFromQuarantine(normalizedWord, rawEmoji)
            return true
        }
        val previousSnapshot = snapshot
        snapshot =
            if (candidate.isEmpty) PersonalEmojiDictionary.EMPTY else candidate.toSnapshot(subtypeId)
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
        purgeFromQuarantine(normalizedWord, rawEmoji)
        return true
    }

    /**
     * Erases this subtype's personal emoji: empties memory and deletes the file, the pending
     * counters, the salt and any quarantined copy of an unreadable file. The salt goes too, so the
     * hashes of a future session cannot be compared with those of the erased one; a new one is
     * created on demand.
     */
    fun clearAll(outcome: PersonalMutationOutcome? = null) = onWorker {
        entries = PersonalEmojiEntries.empty(maxEntries)
        counterFlushPending = false
        pending = PendingCounters.EMPTY
        pendingDirty = false
        salt = null
        snapshot = PersonalEmojiDictionary.EMPTY
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
        // a quarantine copy left behind would resurrect entries on the next restore.
        val storeGone = deleted { deleteFile() }
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull()
        val countersGone = directory != null &&
            deleted { deleteFile(directory, File(directory, pendingFileName())) }
        val saltGone = directory != null &&
            deleted { deleteFile(directory, File(directory, SALT_FILE_NAME)) }
        val quarantineGone = directory != null &&
            deleted { deleteFile(directory, File(directory, quarantineFileName())) }
        // The "not told yet" mark goes as well, but DELIBERATELY outside the answer below: it is not
        // one of the user's entries. Its only cost when it survives is one pointless notice about a
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
     * entries came out, and whether the copy was read to its end. No word and no path leaves here;
     * `null` means there is no copy at all.
     */
    fun inspectQuarantine(sink: PersonalQuarantineReportSink) = onWorker {
        val salvage = try {
            readQuarantine()
        } catch (_: Exception) {
            null
        }
        val report = salvage?.let { PersonalQuarantineReport(it.entryCount, it.readToEnd) }
        try {
            sink.onInspected(report)
        } catch (_: Exception) {
        }
    }

    /**
     * Puts the salvaged entries back into the store, at the user's explicit request and never on
     * its own. Entries already in the list are SKIPPED rather than upserted: a restore must not
     * quietly promote them up the usage order, and skipping is what makes running it twice
     * harmless. Entries the user deleted since the copy was made are NOT in the copy any more
     * ([forget] purges them there too), so a restore cannot resurrect them.
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

    /** The body of [restoreQuarantine], on the worker: returns whether the entries are really saved. */
    private fun restoreOnWorker(): Boolean {
        if (!open()) return false
        if (alphabet == null) return false
        val salvage = readQuarantine() ?: return false
        if (salvage.entryCount == 0) return false
        var candidate = entries
        var added = 0
        for (index in 0 until salvage.entryCount) {
            val word = salvage.words[index]
            val emoji = salvage.emojiClusters[index]
            if (candidate.containsEntry(word, emoji)) continue
            candidate = candidate.upsert(word, emoji, 1)
            added++
        }
        // Every salvaged entry was already there. Nothing to write, and from where the user stands
        // the entries they asked for are in the list, which is what they asked for.
        if (added == 0) return true
        return commitWrite(candidate)
    }

    /**
     * The quarantine half of [forget]: drops the entry from the copy too, so a later restore cannot
     * resurrect what the user deleted. A copy that becomes empty is removed; a copy that cannot be
     * rewritten is removed as well — fail-closed toward NOT resurrecting, at the price of losing
     * the salvage of the other entries.
     */
    private fun purgeFromQuarantine(normalizedWord: String, emoji: String) {
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return
        val copy = File(directory, quarantineFileName())
        if (!copy.isFile) return
        val salvage = try {
            PersonalEmojiQuarantineSalvage.read(copy, subtypeId)
        } catch (_: Exception) {
            null
        } ?: return
        val kept = (0 until salvage.entryCount).filter { index ->
            salvage.words[index] != normalizedWord || salvage.emojiClusters[index] != emoji
        }
        if (kept.size == salvage.entryCount) return // the entry was not in the copy: nothing to purge
        val rewritten = try {
            var candidate = PersonalEmojiEntries.empty(maxEntries)
            for (index in kept) {
                candidate = candidate.upsert(salvage.words[index], salvage.emojiClusters[index], 1)
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
            // The copy could not be rewritten without the deleted entry. Deleting it outright loses
            // the salvage of the other entries — and keeping it would resurrect an entry the user
            // was told is gone. Erased means erased.
            deleted { deleteFile(directory, copy) }
        }
    }

    /** Reads the copy behind the unlock gate. `null` when there is no readable copy to speak of. */
    private fun readQuarantine(): PersonalEmojiQuarantineSalvage? {
        if (!unlockGate()) return null
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return null
        if (!directory.isDirectory) return null
        return PersonalEmojiQuarantineSalvage.read(File(directory, quarantineFileName()), subtypeId)
    }

    /**
     * Flushes in-memory counter/serial changes to disk once, only when something changed — the one
     * boundary (`SuggestionsController.onFinishInput`) where both the usage counters and the pending
     * hashes are written, never per keystroke and never per observed co-usage.
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

    private fun commitWrite(candidate: PersonalEmojiEntries): Boolean {
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
            entries = PersonalEmojiEntries.empty(maxEntries)
            snapshot = PersonalEmojiDictionary.EMPTY
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
            snapshot = PersonalEmojiDictionary.EMPTY
            return
        }
        cleanupTemps(directory)
        readPending(directory)
        val file = File(directory, TpersemFormat.personalEmojiFileName(subtypeId))
        if (!file.isFile) {
            snapshot = PersonalEmojiDictionary.EMPTY
            return
        }
        val validated = try {
            validator.validate(file, subtypeId)
        } catch (_: Exception) {
            null
        }
        if (validated == null) {
            quarantine(directory, file)
            entries = PersonalEmojiEntries.empty(maxEntries)
            snapshot = PersonalEmojiDictionary.EMPTY
            return
        }
        entries = PersonalEmojiEntries.fromValidated(validated, maxEntries)
        snapshot = entries.toSnapshot(subtypeId)
    }

    /**
     * The whole-file write sequence. Returns true only when every step succeeded. On any caught
     * failure the temp is removed and false is returned, leaving the previous file untouched. An
     * uncaught [Error] (a simulated process death) leaves the temp for the next open to discard.
     */
    private fun writeWhole(candidate: PersonalEmojiEntries): Boolean {
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
                val destination = File(directory, TpersemFormat.personalEmojiFileName(subtypeId))
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
     * The salt for the pending hashes: 16 random bytes in the store's OWN `salt-emoji.bin`, created
     * on first use and destroyed by [clearAll]. The emoji store shares neither the words store's
     * `salt.bin` nor the pairs store's `salt-bigrams.bin`: erasing one feature's data must not
     * silently invalidate another feature's pending progress. Returns null when the salt can
     * neither be read nor created — in which case nothing is counted at all, the fail-closed
     * direction.
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

    /**
     * The pending key of one (word, emoji) co-occurrence: the single-word [PendingCounters.keyOf]
     * applied to the two halves joined by a NUL. The byte stream is exactly what
     * [PendingCounters.keyOfPair] hashes (salt ‖ word ‖ 0x00 ‖ emoji), so the same separation
     * guarantee holds — and keying the PAIR, not the bare word, is what keeps «сәләм»→☀️ and
     * «сәләм»→🌙 in two counters of their own instead of one shared counter that would graduate
     * whichever emoji happened to cross the threshold on the other's observations. Neither half
     * can contain a NUL: the alphabet filter excludes it from the word and the cluster check from
     * the emoji.
     */
    private fun pendingKey(normalizedWord: String, emoji: String): String = "$normalizedWord\u0000$emoji"

    /**
     * The emoji half's boundary check — the exact rules the validator enforces on disk, so a
     * cluster accepted here round-trips through the writer and back: non-empty, at most
     * [TpersemFormat.MAX_EMOJI_CLUSTER_CHARS] UTF-16 units, and the WHOLE string one emoji cluster
     * by [EmojiTextUtils.trailingEmojiClusterLength] (the same ruler the editor's backspace
     * deletes by). Anything else — plain text, two clusters, a lone joiner — is dropped.
     */
    private fun acceptedEmojiCluster(rawEmoji: String): String? {
        if (rawEmoji.isEmpty() || rawEmoji.length > TpersemFormat.MAX_EMOJI_CLUSTER_CHARS) return null
        if (EmojiTextUtils.trailingEmojiClusterLength(rawEmoji) != rawEmoji.length) return null
        return rawEmoji
    }

    private fun quarantineFileName(): String =
        TpersemFormat.personalEmojiFileName(subtypeId) + QUARANTINE_SUFFIX

    private fun quarantineNoticeFileName(): String = "quarantine-notice-emoji-$subtypeId-s1-f1.flag"

    /** Whether the on-disk "not told yet" mark is there. Fail-closed to "already told". */
    private fun quarantineNoticeIsMarked(): Boolean = try {
        File(directoryProvider.personalDirectory(), quarantineNoticeFileName()).isFile
    } catch (_: Exception) {
        false
    }

    private fun pendingFileName(): String = "pending-emoji-$subtypeId-s1-f1.bin"

    /**
     * Removes this subtype's `.tpersem` file. Returns nothing ON PURPOSE, like the words store's:
     * a throw is the single failure signal, and the callers' `try`/[deleted] is what answers for it.
     */
    private fun deleteFile() {
        val directory = directoryProvider.personalDirectory()
        val file = File(directory, TpersemFormat.personalEmojiFileName(subtypeId))
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
         * Clean co-observations of one (word, emoji) entry before it is written into the store
         * itself. The same value the pairs store pins ([PersonalBigramStore.LEARN_THRESHOLD] = 2),
         * by the analogue of its reasoning: the words store's third observation exists to filter
         * accidental junk, and that risk is already closed here — the word half comes from real
         * committed editor text (and passes the same content filter the pairs store applies), and
         * an emoji insertion is a deliberate pick from the panel, never a fat-fingered keystroke.
         */
        const val LEARN_THRESHOLD = 2

        private const val TEMP_PREFIX = ".personal-emoji-"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_TEMP_ATTEMPTS = 100
        private const val FREE_SPACE_RESERVE_BYTES = 64L * 1024L
        private const val SALT_FILE_NAME = "salt-emoji.bin"
        private const val SALT_SIZE = 16

        /**
         * Appended to the ordinary file name for the one quarantine slot. Deliberately not a
         * `.tpersem` name and not a temp name: nothing reads it, nothing cleans it up by accident.
         */
        private const val QUARANTINE_SUFFIX = ".quarantine"
    }
}
