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
 * The serialized owner of one subtype's personal `.tpers` file.
 *
 * Every mutation is an event on the single background [executor], so all in-memory state lives on
 * one worker and never on the UI thread. After each SUCCESSFUL mutation a fresh immutable
 * [PersonalDictionary] snapshot is published through the `@Volatile` [snapshot] reference; the
 * engine's worker thread reads it. The UI thread does no I/O, no checksum, no read and no write.
 *
 * Whole-file write only, in the frozen sequence (E4a-2): exclusive temp in the same directory →
 * write → flush → fsync file → RE-VALIDATE the written bytes → atomic replace → fsync directory.
 * A partial temp never becomes the main file; any failure leaves the previous valid file untouched;
 * temp garbage is removed when the store next opens the directory.
 *
 * Fail-closed everywhere: a caught exception (including the `UserManager.isUserUnlocked() == false`
 * gate closing the credential-protected path before the first unlock) drops the mutation and leaves
 * the feature empty rather than throwing. Nothing here logs, and no message carries the user's word
 * or the file path.
 *
 * E4a-2 wires none of this into the live IME — it is exercised only from tests via the explicit API
 * below. The settings toggle, the merge with dictionary candidates and clean-run learning are E4b
 * and E4c.
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

    // B5. Set when THIS session set a file aside, and kept only until the notice has been raised: the
    // durable half of the same mark is the file [quarantineNoticeFileName] names.
    private var justQuarantined = false

    // E4c: progress towards learning, as salted truncated hashes. Held in memory and written on the
    // same boundary as the usage counters — never once per completed word.
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
     * Manually adds one word (E4b's "Add word…" path); a no-op if the word is not eligible.
     *
     * [outcome] is told what actually happened, on the worker, once the whole-file write has either
     * succeeded or failed — never at the moment the event was queued. Without it the screen could
     * only report that it had asked, and a write that ran out of space or failed re-validation would
     * be invisible: no list entry, no message, no retry.
     */
    fun addManually(word: String, outcome: PersonalMutationOutcome? = null) = onWorker {
        val normalized = eligibleNormalizedForm(word)
        if (normalized == null) {
            report(outcome, false)
            return@onWorker
        }
        // Evaluated first and reported second, deliberately: inside `outcome?.onFinished(...)` a
        // null outcome would short-circuit the argument too, and the write itself would vanish.
        // [report] cannot repeat that mistake — the value is an argument, so it is always computed.
        val saved = commitWrite(entries.upsert(word, normalized))
        report(outcome, saved)
    }

    /**
     * Records ONE clean completion of [word] (E4c). Nothing is written to the dictionary until the
     * word has survived [PendingCounters.LEARN_THRESHOLD] of them; until then only a salted
     * truncated hash exists, and only in memory between flushes.
     *
     * The threshold is 3 for a reason worth keeping written down: 1 would learn any typo, 2 would
     * learn a typo repeated twice — and the same slip is exactly what a person repeats — while 3
     * demands that the spelling survive three independent completions without a single correction.
     */
    fun noteCompletion(word: String) = onWorker {
        val normalized = eligibleNormalizedForm(word) ?: return@onWorker
        if (entries.containsNormalized(normalized)) return@onWorker
        val key = PendingCounters.keyOf(saltOrCreate() ?: return@onWorker, normalized)
        val noted = pending.note(key)
        if (noted.countOf(key) >= PendingCounters.LEARN_THRESHOLD) {
            // Graduated: it goes into the dictionary itself, and its pending trace goes away.
            val candidate = entries.upsert(word, normalized)
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
     * Removes one word and rewrites (or deletes, when it was the last) the file.
     *
     * Two things the caller is entitled to and did not use to get:
     *
     * * [outcome] is told whether the word is really gone. A failed rewrite leaves the word both in
     *   memory and on disk, and the user had already been told the opposite — the dialog closed and
     *   the band was cleared — so the word came back on the next keystroke with nothing said.
     * * The removal is published to READERS before the write, not after it. "Erased means erased" is
     *   the whole value of this feature, and the engine reads [snapshot] from its own thread: while
     *   the write was in flight — two fsyncs, tens to hundreds of milliseconds on the cheap devices
     *   this project targets — a keystroke could still bring the erased word back onto the band. If
     *   the write then fails, the previous snapshot is restored, because at that point the word IS
     *   still saved and pretending otherwise would be the same lie in the other direction.
     *
     * The word is ALSO purged from the quarantine copy, if one exists: a copy that still holds a
     * word the user deleted would resurrect it on the next restore, and "forgotten" must not have
     * a back door. A copy that cannot be rewritten without the word is deleted outright — losing
     * the salvage of other words is the smaller lie than keeping a deleted one. (U7 of Phase 2,
     * docs/ROADMAP-P2.md — the P1 pairs store pinned this rule first; the words store had the hole.)
     */
    fun forget(word: String, outcome: PersonalMutationOutcome? = null) = onWorker {
        // B3. This was the one mutation whose body ran outside a `try`, and the exception did not
        // stay inside it: the worker is a bare single-thread executor created without an
        // `UncaughtExceptionHandler`, so `deleteFile()` throwing on the removal of the LAST saved
        // word reached the default handler — `KillApplicationHandler`. The keyboard died in the
        // middle of typing in someone else's app.
        //
        // Falling over protected nothing here. The usual argument for a loud crash assumes data is
        // being lost; a delete that did not happen loses nothing — the word simply stays saved. The
        // cost was a dead IME and there was no gain, so the refusal travels the channel instead, and
        // [outcome] hears exactly one answer whatever happens below.
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
        // The pending hash goes with the word: forgetting it must not leave progress behind that
        // would re-learn it after three more completions.
        salt?.let { existing ->
            val key = PendingCounters.keyOf(existing, normalized)
            if (pending.countOf(key) > 0) {
                pending = pending.without(key)
                pendingDirty = true
            }
        }
        val candidate = entries.remove(normalized)
        if (candidate === entries) {
            // The word was not in this dictionary at all: nothing to remove, and from where the
            // user stands it is gone, which is what they asked for. The quarantine purge still
            // runs — a word can sit in the copy without ever having been restored.
            purgeFromQuarantine(normalized)
            return true
        }
        val previousSnapshot = snapshot
        snapshot = if (candidate.isEmpty) PersonalDictionary.EMPTY else candidate.toSnapshot(subtypeId)
        // B3, the part that makes the refusal true rather than merely quiet: a throw here becomes
        // `false` HERE, inside the mutation, so the restore below still runs. The word is still on
        // disk at this point, and a snapshot that hides it would turn one lie into a permanent one.
        val removed = try {
            if (candidate.isEmpty) {
                // `deleteFile()` used to return a Boolean that could only ever be `true`, and this
                // read `if (candidate.isEmpty) deleteFile()` — a branch shaped like a check on a
                // value that did not exist. A throw is the only way the deletion can fail, and the
                // `catch` below is what handles it; saying so out loud is the honest shape.
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
        purgeFromQuarantine(normalized)
        return true
    }

    /**
     * The quarantine half of [forget]: drops the word from the copy too, so a later restore cannot
     * resurrect what the user deleted. A copy that becomes empty is removed; a copy that cannot be
     * rewritten is removed as well — fail-closed toward NOT resurrecting, at the price of losing
     * the salvage of the other words. The exact mirror of the pairs store's purge (P1 pinned the
     * rule there first).
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
        val rewritten = try {
            var candidate = PersonalEntries.empty(maxEntries)
            for (index in kept) {
                candidate = candidate.upsert(salvage.rawForms[index], salvage.normalizedForms[index])
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
            // The copy could not be rewritten without the deleted word. Deleting it outright loses
            // the salvage of the other words — and keeping it would resurrect a word the user was
            // told is gone. Erased means erased.
            deleted { deleteFile(directory, copy) }
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
        snapshot = PersonalDictionary.EMPTY
        loaded = true
        if (!unlockGate()) {
            // Memory is empty, the files are untouched and the next process start reads them all
            // back. The screen shows an empty list either way, so this is exactly the case that must
            // not pass for success.
            report(outcome, false)
            return@onWorker
        }
        // Three INDEPENDENT deletions. They used to share one try, so a failure on the first one
        // skipped the other two and left the salt and the pending counters behind: the same salt
        // means the same hashes, so words two-thirds of the way to being learned kept their
        // progress and re-appeared after three more completions, with the screen showing nothing.
        val dictionaryGone = deleted { deleteFile() }
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull()
        val countersGone = directory != null &&
            deleted { deleteFile(directory, File(directory, pendingFileName())) }
        val saltGone = directory != null &&
            deleted { deleteFile(directory, File(directory, SALT_FILE_NAME)) }
        // B2 keeps a copy of an unreadable file (see [quarantine]), and that copy is the user's own
        // words, which no screen shows. "Erase all" is the only way they can ask for those bytes to
        // go, so a copy left behind is a failed erasure — not a detail.
        val quarantineGone = directory != null &&
            deleted { deleteFile(directory, File(directory, quarantineFileName())) }
        // The "not told yet" mark goes as well, but DELIBERATELY outside the answer below: it is not
        // one of the user's words. A mark that would not delete must not turn "your words are gone"
        // into "the erasure failed" — that would be the same class of lie in the other direction. Its
        // only cost when it survives is one pointless notice about a list the user emptied by hand.
        if (directory != null) {
            deleted { deleteFile(directory, File(directory, quarantineNoticeFileName())) }
        }
        report(outcome, dictionaryGone && countersGone && saltGone && quarantineGone)
    }

    /**
     * B5. Clears the "not told yet" mark, on the worker, once the notice has actually reached the
     * user. Called by the layer that shows it — never by the layer that raises it, or the mark would
     * be gone before anyone had seen anything.
     */
    fun noticeDelivered() = onWorker {
        justQuarantined = false
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return@onWorker
        deleted { deleteFile(directory, File(directory, quarantineNoticeFileName())) }
    }

    /**
     * B4. Reads what is still readable in the quarantine copy and hands back TWO NUMBERS — how many
     * words came out, and whether the copy was read to its end. No word and no path leaves here.
     *
     * `null` means there is no copy at all; a report with a count of zero means there IS one and it
     * yielded nothing — a different answer, because the second still leaves the user something to
     * delete. When [PersonalQuarantineReport.readToEnd] is false part of the copy is damaged and lost,
     * and the screen is obliged to say so: a partial recovery presented as a whole one is the one
     * outcome this feature must never produce.
     */
    fun inspectQuarantine(sink: PersonalQuarantineReportSink) = onWorker {
        val salvage = try {
            readQuarantine()
        } catch (_: Exception) {
            null
        }
        val report = salvage?.let { PersonalQuarantineReport(it.wordCount, it.readToEnd) }
        // Same seam, same reason as [report]: a screen that has gone away must not kill the keyboard.
        try {
            sink.onInspected(report)
        } catch (_: Exception) {
        }
    }

    /**
     * B4. Puts the salvaged words back into the dictionary, at the user's explicit request and never
     * on its own. Words already in the list are SKIPPED rather than upserted: a restore must not
     * quietly promote them up the usage order, and skipping is what makes running it twice harmless.
     *
     * The copy is deliberately NOT removed on success. Restoring and discarding are two separate
     * actions, so the damaged tail — the part no parser could read this time — survives a restore and
     * stays available to a later, better reader. Deleting it is the user's own second decision, and
     * "erase all words" still takes it with everything else (see [clearAll]).
     */
    fun restoreQuarantine(outcome: PersonalMutationOutcome? = null) = onWorker {
        val restored = try {
            restoreOnWorker()
        } catch (_: Exception) {
            false
        }
        report(outcome, restored)
    }

    /** B4. Removes the quarantine copy and nothing else. */
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
        // Every salvaged word was already there. Nothing to write, and from where the user stands the
        // words they asked for are in the list, which is what they asked for.
        if (added == 0) return true
        return commitWrite(candidate)
    }

    /** Reads the copy behind the unlock gate. `null` when there is no readable copy to speak of. */
    private fun readQuarantine(): PersonalQuarantineSalvage? {
        if (!unlockGate()) return null
        val directory = runCatching { directoryProvider.personalDirectory() }.getOrNull() ?: return null
        if (!directory.isDirectory) return null
        return PersonalQuarantineSalvage.read(File(directory, quarantineFileName()), subtypeId)
    }

    /**
     * Records an accepted personal suggestion as a use: bumps the counter and LRU serial IN MEMORY
     * only, publishing the updated snapshot. It never rewrites the file — flushing whole for every
     * tap would be up to 128 KiB plus two fsyncs per tap. Runs as an executor event, not inline on
     * the UI thread, so it can never race [clearAll].
     */
    fun noteAcceptedSuggestion(word: String) = onWorker {
        if (!open()) return@onWorker
        alphabet ?: return@onWorker
        val candidate = entries.noteUse(PersonalWordFilter.normalize(word)) ?: return@onWorker
        entries = candidate
        snapshot = candidate.toSnapshot(subtypeId)
        pendingCounterFlush = true
    }

    /**
     * Flushes in-memory counter/serial changes to disk once, only when something changed — the one
     * boundary (`SuggestionsController.onFinishInput`) where both the usage counters and the pending
     * hashes are written, never per keystroke and never per completed word.
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
     * Opens the store on its worker if it is not open yet, publishing the snapshot the engine will
     * read. A no-op afterwards. Safe to call from any thread — like every other mutation it is an
     * event on the executor, so the file read never lands on the caller's thread.
     */
    fun prime() = onWorker { open() }

    /** Test hook: runs [block] on the store's executor (so tests can drive the serialized owner). */
    fun runOnWorker(block: () -> Unit) = onWorker(block)

    private fun onWorker(block: () -> Unit) = executor.execute(block)

    private fun eligibleNormalizedForm(word: String): String? {
        if (!open()) return null
        val alpha = alphabet ?: return null
        return PersonalWordFilter.acceptedNormalizedForm(word, alpha)
    }

    private fun commitWrite(candidate: PersonalEntries): Boolean {
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
     * The one way a mutation answers its caller. Two problems, one shape.
     *
     * B3 closed the throw INSIDE each mutation, but every `outcome?.onFinished(...)` still sat outside
     * the `try` that guarded it. The callback belongs to an Activity and posts to the UI thread; a
     * dead Handler, a detached screen or a listener the settings code replaced mid-flight throws from
     * `post` itself, and on this bare single-thread executor — created with no `UncaughtExceptionHandler`
     * — that throw reaches `KillApplicationHandler`. The keyboard died for having said what it did.
     *
     * And because [succeeded] is an ARGUMENT it is always evaluated, unlike `outcome?.onFinished(work())`
     * where a null outcome swallows the work as well. The bug that shape once caused cannot be written
     * here at all.
     *
     * Silent by necessity, not by preference: nothing in this subsystem may log, so a callback that
     * throws leaves no trace anywhere. What it must not do is take the process with it.
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
            entries = PersonalEntries.empty(maxEntries)
            snapshot = PersonalDictionary.EMPTY
        }
        // B5, the single place the notice is raised. Every open passes here — the one that quarantined
        // the file just now AND the one that merely found the mark a previous process left behind — so
        // a loss the user was never told about is told about at the next chance there is, however many
        // process deaths later. `justQuarantined` keeps the promise even when the mark itself could not
        // be written: this session at least still says it out loud.
        if (justQuarantined || quarantineNoticeIsMarked()) {
            // The seam leads to an Activity through a UI-thread post. A throw on the way out would
            // reach the worker's default handler and kill the keyboard, and it would do it while
            // reporting that the dictionary is empty — the least deserving moment there is.
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
            snapshot = PersonalDictionary.EMPTY
            return
        }
        cleanupTemps(directory)
        readPending(directory)
        val file = File(directory, TpersFormat.personalFileName(subtypeId))
        if (!file.isFile) {
            snapshot = PersonalDictionary.EMPTY
            return
        }
        val validated = try {
            validator.validate(file, subtypeId)
        } catch (_: Exception) {
            null
        }
        if (validated == null) {
            quarantine(directory, file)
            entries = PersonalEntries.empty(maxEntries)
            snapshot = PersonalDictionary.EMPTY
            return
        }
        entries = PersonalEntries.fromValidated(validated, maxEntries)
        snapshot = entries.toSnapshot(subtypeId)
    }

    /**
     * The whole-file write sequence. Returns true only when every step succeeded. On any caught
     * failure the temp is removed and false is returned, leaving the previous file untouched. An
     * uncaught [Error] (a simulated process death) leaves the temp for the next open to discard.
     */
    private fun writeWhole(candidate: PersonalEntries): Boolean {
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
                val destination = File(directory, TpersFormat.personalFileName(subtypeId))
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
     * The salt for the pending hashes: 16 random bytes in `salt.bin`, created on first use and
     * destroyed by [clearAll]. Returns null when it can neither be read nor created — in which case
     * nothing is counted at all, which is the fail-closed direction (no learning rather than
     * unsalted keys).
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

    /** Reads the pending counters once, alongside the dictionary itself. Fail-closed to empty. */
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
     * fsync sequence the dictionary uses. The pending file and the salt are not user text, but a
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
     * B2. Moves an unreadable file into this subtype's single quarantine slot instead of deleting
     * it, and tells [quarantineNotice] that the list the user will see is empty for a reason.
     *
     * Validation fails for an interrupted write (a power cut mid-replace), a checksum that no longer
     * matches, or a format a later version changes — and what usually survives such a failure is
     * most of the words. Deleting destroyed the only data this keyboard keeps about its user, with
     * no copy and nothing said; a repair path added later can only read those words if they still
     * exist. Between losing the data and keeping one extra file, the file wins.
     *
     * ONE slot per language, replaced by the next corruption, so the copy cannot accumulate: the
     * ceiling on disk is one file, not one per failure. Its name is not the name the reader looks
     * for, so nothing ever validates, reads or publishes it, and it is not a temp either, so
     * [cleanupTemps] leaves it alone. "Erase all" removes it — see [clearAll].
     *
     * If the move itself cannot happen the unreadable file is removed after all: leaving it where
     * the reader looks would fail validation again on every single start, and the copy is worth
     * strictly less than a feature that works.
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
        // do it, which is equally true whether the copy was kept or could not be made.
        //
        // B5. The mark used to be a field on a process that was usually about to end: the quarantine
        // happens while the store opens, which on a keyboard is the moment the input field appears,
        // and the notice waited for a screen nobody had opened yet. The next process start began with
        // a fresh `false` and the loss was never mentioned again. It goes to disk instead, one byte
        // whose EXISTENCE is the whole message: no word, no path, no reason — a flag, not text.
        justQuarantined = true
        runCatching {
            writeBytesDurably(directory, File(directory, quarantineNoticeFileName()), ByteArray(1))
        }
    }

    private fun quarantineFileName(): String =
        TpersFormat.personalFileName(subtypeId) + QUARANTINE_SUFFIX

    private fun quarantineNoticeFileName(): String = "quarantine-notice-$subtypeId-s1-f1.flag"

    /** Whether the on-disk "not told yet" mark is there. Fail-closed to "already told". */
    private fun quarantineNoticeIsMarked(): Boolean = try {
        File(directoryProvider.personalDirectory(), quarantineNoticeFileName()).isFile
    } catch (_: Exception) {
        false
    }

    private fun pendingFileName(): String = "pending-$subtypeId-s1-f1.bin"

    /**
     * Removes this subtype's `.tpers` file. Returns nothing ON PURPOSE: it used to return a `Boolean`
     * whose only two outcomes were `true` and a throw, and every caller that read it was written as a
     * check on a value that did not exist. A throw is the single failure signal, and the callers'
     * `try`/[deleted] is what answers for it.
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
        private const val SALT_FILE_NAME = "salt.bin"
        private const val SALT_SIZE = 16

        /**
         * Appended to the ordinary file name for the one quarantine slot (B2). Deliberately not a
         * `.tpers` name and not a temp name: nothing reads it, nothing cleans it up by accident.
         */
        private const val QUARANTINE_SUFFIX = ".quarantine"
    }
}
