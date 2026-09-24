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

package rkr.simplekeyboard.inputmethod.latin.emoji

import java.io.File
import java.io.FileOutputStream

/**
 * The single file the recents store owns. Production resolves it under the base (credential-
 * protected) `noBackupFilesDir`; that seam is deliberately its own, not the dictionary's
 * `DeviceProtectedDirectoryProvider`, whose name would become a lie for a credential-protected file.
 */
fun interface RecentEmojiFileProvider {
    fun file(): File
}

/** Reads and atomically replaces the recents medium. Never throws and never logs. */
interface RecentEmojiFileOps {
    /** The medium's content, or null when it is absent or unreadable. */
    fun read(file: File): String?

    /** Atomically replaces the medium with [content]. Off the UI thread by construction. */
    fun writeAtomic(file: File, content: String)
}

/** The environment gate, re-read on every store operation (so it is honoured on the executor). */
fun interface RecentEmojiGate {
    fun current(): RecentEmojiGateState
}

/**
 * A single evaluation of the three gate factors.
 *
 * [allowsRecording] is the list-update gate: all three of `mShouldShowSuggestions` (already covering
 * password, visible password, e-mail, URI, filter, NO_SUGGESTIONS and autocomplete),
 * `UserManager.isUserUnlocked()` and NOT `IME_FLAG_NO_PERSONALIZED_LEARNING` must hold.
 * [allowsPathAccess] is the read/write gate: touching the path requires the device to be unlocked,
 * because before the first unlock the credential-protected path does not exist yet.
 */
data class RecentEmojiGateState(
    val shouldShowSuggestions: Boolean,
    val userUnlocked: Boolean,
    val noPersonalizedLearning: Boolean,
) {
    val allowsRecording: Boolean
        get() = shouldShowSuggestions && userUnlocked && !noPersonalizedLearning

    val allowsPathAccess: Boolean
        get() = userUnlocked

    companion object {
        /** Fail-closed default: nothing is recorded and the path is not touched. */
        val BLOCKED = RecentEmojiGateState(
            shouldShowSuggestions = false,
            userUnlocked = false,
            noPersonalizedLearning = true,
        )
    }
}

/**
 * Serialized owner of the recent-emoji list and its medium.
 *
 * Every method is meant to run on the single background executor that the [EmojiPanelController]
 * owns, so all mutation of the in-memory list happens on one serialized owner and never on the UI
 * thread. Kept injectable — file access, the gate and the medium behind seams — so the MRU rules,
 * the three gates, the "write once per hide only when changed" rule, the fail-closed reads and the
 * "erased never resurrects" rule are all verified on the plain JVM.
 *
 * The gate is re-read on every attempt: the record path checks [RecentEmojiGateState.allowsRecording]
 * *before* it mutates the in-memory list, so an emoji inserted while the gate is closed leaves no
 * trace to flush later; the read and flush paths check [RecentEmojiGateState.allowsPathAccess], so a
 * locked device never has its path touched and never throws instead of returning an empty list.
 */
internal class RecentEmojiStore(
    private val fileProvider: RecentEmojiFileProvider,
    private val fileOps: RecentEmojiFileOps,
    private val gate: RecentEmojiGate,
) {
    private var loaded = false
    private var recents = RecentEmojiList.EMPTY
    private var dirty = false

    /** Number of medium saves performed on hide; a JVM test counter. Clearing does not count. */
    var saveCount = 0
        private set

    /** The current in-memory recents, honouring the unlock gate for the first read. */
    fun currentRecents(available: Set<String>): List<String> {
        ensureLoaded(available)
        return recents.entries
    }

    private fun ensureLoaded(available: Set<String>) {
        if (loaded) return
        // Before the first unlock the path is inaccessible: leave the list empty and untouched.
        if (!gate.current().allowsPathAccess) return
        val raw = try {
            fileOps.read(fileProvider.file())
        } catch (_: Throwable) {
            null
        }
        recents = RecentEmojiList.deserialize(raw).filteredTo(available)
        loaded = true
    }

    /**
     * Records a use of [sequence]. Gated by all three factors, re-read here before any mutation of
     * the in-memory list, so a field forbidding suggestions, a locked device or a
     * no-personalized-learning field never grows the list by even one entry.
     */
    fun recordUse(sequence: String, available: Set<String>) {
        if (!gate.current().allowsRecording) return
        // allowsRecording implies unlocked, so this reads the existing medium before appending.
        ensureLoaded(available)
        val next = recents.used(sequence)
        if (next != recents) {
            recents = next
            dirty = true
        }
    }

    /**
     * Writes at most once per hide, only when the list changed and the device is unlocked. Because
     * it runs on the background executor, the write never touches the UI thread. A failed write
     * keeps the list dirty so a later hide may retry; no exception escapes and nothing is logged.
     */
    fun flushOnHide() {
        if (!dirty) return
        if (!gate.current().allowsPathAccess) return
        try {
            fileOps.writeAtomic(fileProvider.file(), recents.serialize())
            dirty = false
            saveCount++
        } catch (_: Throwable) {
            // Keep dirty; a later hide retries. Never crash, never log.
        }
    }

    /**
     * Clears the recents: zeroes the in-memory list, resets the changed flag, and replaces the
     * medium with an empty one via the same write path as an ordinary save. An absent or locked
     * medium makes the replacement a no-op — no exception, no crash — and the reset flag means the
     * next hide performs no save, so erased entries never resurrect from memory.
     */
    fun clear() {
        recents = RecentEmojiList.EMPTY
        loaded = true
        dirty = false
        if (!gate.current().allowsPathAccess) return
        try {
            fileOps.writeAtomic(fileProvider.file(), "")
        } catch (_: Throwable) {
            // Absent/locked medium: nothing to clear. Never crash, never log.
        }
    }

    /** Inspection for tests: whether an unwritten change is pending. */
    fun isDirty(): Boolean = dirty
}

/**
 * Production [RecentEmojiFileOps]: reads the whole (tiny, <= 512 `char`) file and replaces it
 * atomically by writing a sibling temp file, fsyncing it and renaming it over the destination.
 *
 * It uses only `java.io`, so the emoji package owns this credential-protected file end to end
 * without a device-protected seam. Never throws and never logs; a failure leaves the previous
 * medium in place. The medium is a plain file under the base context's `noBackupFilesDir`, which is
 * not a subdirectory of `files/` and so is excluded from every backup domain by construction.
 */
internal object AtomicRecentEmojiFileOps : RecentEmojiFileOps {

    private const val TEMP_SUFFIX = ".tmp"

    /**
     * Fail-closed read cap (2026-09-24 audit, finding 11): the medium this class writes holds at
     * most [RecentEmojiList.MAX_CHARS] UTF-16 `char`, which is under 2 KiB in UTF-8. A file past
     * this bound is not a medium to decode but a corrupt one to refuse — the check runs on the
     * length, BEFORE a single byte is read, so a bloated file never reaches memory at all.
     */
    private const val MAX_MEDIUM_BYTES = 4096L

    override fun read(file: File): String? =
        try {
            if (file.isFile && file.length() <= MAX_MEDIUM_BYTES) {
                file.readText(Charsets.UTF_8)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }

    override fun writeAtomic(file: File, content: String) {
        val parent = file.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return
        val temp = File(parent, file.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) {
                    temp.delete()
                }
            }
        } catch (_: Throwable) {
            try {
                temp.delete()
            } catch (_: Throwable) {
                // Best effort; leave the previous medium in place.
            }
        }
    }
}
