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

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** The temp sibling's suffix, shared by the writer and the erase sweep. */
private const val RECENT_EMOJI_TEMP_SUFFIX = ".tmp"

/**
 * The single file the recents store owns. Production resolves it under the base (credential-
 * protected) `noBackupFilesDir`. It has its own provider instead of the dictionary's
 * `DeviceProtectedDirectoryProvider`, because this file is credential-protected.
 */
fun interface RecentEmojiFileProvider {
    fun file(): File
}

/** Reads and atomically replaces the recents medium. Never throws and never logs. */
interface RecentEmojiFileOps {
    /** The medium's content, or null when it is absent or unreadable. */
    fun read(file: File): String?

    /**
     * Atomically replaces the medium with [content]: temp fsync, atomic rename, directory fsync.
     * True only when every stage succeeded. A failure before the rename leaves the previous
     * medium in place; a directory-sync failure after the rename leaves the new content installed
     * but not proven durable, so the caller keeps the change pending and retries. Off the UI
     * thread only.
     */
    fun writeAtomic(file: File, content: String): Boolean

    /** True when the medium is absent or empty; false when it holds data or cannot be checked. */
    fun isAbsentOrEmpty(file: File): Boolean

    /** Removes the temp sibling an interrupted write may leave; true when no temp remains. */
    fun removeTemp(file: File): Boolean
}

/** The environment gate, re-read on every store operation (so it is honoured on the executor). */
fun interface RecentEmojiGate {
    fun current(): RecentEmojiGateState
}

/**
 * A single evaluation of the gate factors. No factor has a default, so a caller cannot leave one
 * open by omission.
 *
 * [allowsRecording] is the list-update gate: `mShouldShowSuggestions` (already covering password,
 * visible password, e-mail, URI, filter, NO_SUGGESTIONS and autocomplete), unlocked user, NOT
 * `IME_FLAG_NO_PERSONALIZED_LEARNING`, no keyguard shown and learning not paused (incognito).
 * [allowsDisplay] hides the list while the keyguard is shown. [allowsPathAccess] is the read/write
 * gate: before the first unlock the credential-protected path does not exist yet.
 */
data class RecentEmojiGateState(
    val shouldShowSuggestions: Boolean,
    val userUnlocked: Boolean,
    val noPersonalizedLearning: Boolean,
    val keyguardLocked: Boolean,
    val incognito: Boolean,
) {
    val allowsRecording: Boolean
        get() = shouldShowSuggestions && userUnlocked && !noPersonalizedLearning &&
            !keyguardLocked && !incognito

    val allowsDisplay: Boolean
        get() = !keyguardLocked

    val allowsPathAccess: Boolean
        get() = userUnlocked

    companion object {
        /** Fail-closed default: nothing is recorded or shown and the path is not touched. */
        val BLOCKED = RecentEmojiGateState(
            shouldShowSuggestions = false,
            userUnlocked = false,
            noPersonalizedLearning = true,
            keyguardLocked = true,
            incognito = true,
        )
    }
}

/**
 * Serialized owner of the recent-emoji list and its medium.
 *
 * Every method is meant to run on the single background executor that the [EmojiPanelController]
 * owns, so all mutation of the in-memory list happens on one serialized owner and never on the UI
 * thread. Kept injectable — file access, the gate and the medium behind seams — so the MRU rules,
 * the gate factors, the "write once per hide only when changed" rule, the fail-closed reads and the
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

    /** Number of durable medium saves performed on hide; a JVM test counter. Clearing does not count. */
    var saveCount = 0
        private set

    /**
     * The current recents, honouring the unlock gate for the first read. Empty while the keyguard
     * is shown; the list itself is kept for the moment the device is unlocked. Entries absent from
     * a non-empty [available] are not returned (one recorded before the panel's set was known).
     */
    fun currentRecents(available: Set<String>): List<String> {
        if (!gate.current().allowsDisplay) return emptyList()
        ensureLoaded(available)
        return if (available.isEmpty()) recents.entries else recents.filteredTo(available).entries
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
     * Records a use of [sequence]. Gated by [RecentEmojiGateState.allowsRecording], re-read here
     * before any mutation of the in-memory list, so a closed gate never grows the list by even one
     * entry. A sequence absent from a non-empty [available] is not recorded.
     */
    fun recordUse(sequence: String, available: Set<String>) {
        if (!gate.current().allowsRecording) return
        if (available.isNotEmpty() && sequence !in available) return
        // allowsRecording implies unlocked, so this reads the existing medium before appending.
        ensureLoaded(available)
        val next = recents.used(sequence)
        if (next != recents) {
            recents = next
            dirty = true
        }
    }

    /**
     * Removes one sequence from the list. Gate discipline as [recordUse]: recording is not
     * required, but the path gate still governs the medium, and a removal is marked dirty so the
     * write happens at the next hide, exactly like a use. Removing a missing sequence is a no-op.
     */
    fun removeRecent(sequence: String, available: Set<String>) {
        ensureLoaded(available)
        val next = recents.without(sequence)
        if (next != recents) {
            recents = next
            dirty = true
        }
    }

    /**
     * Writes at most once per hide, only when the list changed and the device is unlocked. Because
     * it runs on the background executor, the write never touches the UI thread. Only a durable
     * write clears the changed flag and counts a save; a failed or interrupted write keeps the
     * list dirty so a later hide retries. A failure after the rename may leave the new content
     * installed without proven durability; the retry then writes the same content again. No
     * exception escapes and nothing is logged.
     */
    fun flushOnHide() {
        if (!dirty) return
        if (!gate.current().allowsPathAccess) return
        val saved = try {
            fileOps.writeAtomic(fileProvider.file(), recents.serialize())
        } catch (_: Throwable) {
            false
        }
        if (saved) {
            dirty = false
            saveCount++
        }
    }

    /**
     * Empties the in-memory list, the changed flag (so no hide resurrects it) and the medium, and
     * sweeps the temp file an interrupted write may have stranded. True only when the medium is
     * durably absent or empty and no temp remains afterwards: a failed empty write is tolerated
     * only when the medium already was clear. A locked path is left untouched and answers false.
     * Never throws, never logs.
     */
    fun clear(): Boolean {
        recents = RecentEmojiList.EMPTY
        loaded = true
        dirty = false
        return try {
            if (!gate.current().allowsPathAccess) return false
            val file = fileProvider.file()
            val wasClear = fileOps.isAbsentOrEmpty(file)
            val written = fileOps.writeAtomic(file, "")
            val mediumCleared = fileOps.isAbsentOrEmpty(file)
            // The temp is swept even when the medium write failed, so no leftover survives the
            // erase silently; a temp that cannot be removed fails the whole erase.
            val tempRemoved = fileOps.removeTemp(file)
            mediumCleared && (written || wasClear) && tempRemoved
        } catch (_: Throwable) {
            // Never crash, never log; the caller reports the failure.
            false
        }
    }

    /** Inspection for tests: whether an unwritten change is pending. */
    fun isDirty(): Boolean = dirty
}

/**
 * Production [RecentEmojiFileOps]: reads the whole (tiny, <= 512 `char`) file and replaces it
 * atomically through [AtomicRecentEmojiWriter]: temp fsync, rename, directory fsync.
 *
 * It uses only JDK file APIs, so the emoji package owns this credential-protected file end to end
 * without a device-protected seam. Never throws and never logs; a failure before the rename
 * leaves the previous medium in place. The medium is a plain file under the base context's
 * `noBackupFilesDir`, which is not a subdirectory of `files/` and so is outside every backup
 * domain.
 */
internal object AtomicRecentEmojiFileOps : RecentEmojiFileOps {

    /**
     * Read cap: the medium holds at most [RecentEmojiList.MAX_CHARS] UTF-16 `char` (under 2 KiB
     * in UTF-8), so a larger file is corrupt and is refused. The length is checked before any byte
     * is read, so a bloated file never reaches memory.
     */
    private const val MAX_MEDIUM_BYTES = 4096L

    private val writer = AtomicRecentEmojiWriter()

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

    override fun isAbsentOrEmpty(file: File): Boolean =
        try {
            !file.exists() || (file.isFile && file.length() == 0L)
        } catch (_: Throwable) {
            false
        }

    override fun writeAtomic(file: File, content: String): Boolean = writer.write(file, content)

    override fun removeTemp(file: File): Boolean {
        val parent = file.parentFile ?: return true
        val temp = File(parent, file.name + RECENT_EMOJI_TEMP_SUFFIX)
        return try {
            !temp.exists() || (temp.delete() && !temp.exists())
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * The replacement sequence behind [AtomicRecentEmojiFileOps.writeAtomic]: write and fsync the
 * temp sibling, atomically rename it over the destination, then fsync the directory so the rename
 * itself survives a crash. Every stage is injectable, so a test can fail the write, the temp
 * sync, the rename or the directory sync deterministically, without filesystem permission tricks.
 *
 * True only when every stage succeeded. A failure before the rename leaves the previous medium in
 * place and sweeps the temp. A directory-sync failure after the rename leaves the NEW content
 * installed but not proven durable: the answer is still false, the caller keeps the change dirty
 * and retries, which is safe because rewriting the same content is idempotent. Never throws to
 * the caller, never logs.
 */
internal class AtomicRecentEmojiWriter(
    private val writeAndSyncTemp: (File, ByteArray) -> Unit = { temp, bytes ->
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    },
    private val rename: (File, File) -> Boolean = { temp, file -> temp.renameTo(file) },
    private val syncDirectory: (File) -> Unit = { directory -> syncDirectoryEntry(directory) },
) {
    fun write(file: File, content: String): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
        val temp = File(parent, file.name + RECENT_EMOJI_TEMP_SUFFIX)
        try {
            writeAndSyncTemp(temp, content.toByteArray(Charsets.UTF_8))
        } catch (_: Throwable) {
            deleteTemp(temp)
            return false
        }
        // The destination may change only now that the replacement is durable in the temp file.
        // POSIX rename(2) replaces the old medium atomically; it is never deleted first.
        val renamed = try {
            rename(temp, file)
        } catch (_: Throwable) {
            false
        }
        if (!renamed) {
            deleteTemp(temp)
            return false
        }
        return try {
            syncDirectory(parent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun deleteTemp(temp: File) {
        try {
            temp.delete()
        } catch (_: Throwable) {
            // Best effort; erase-all reports a leftover through removeTemp.
        }
    }
}

/**
 * fsyncs the directory entry itself, the counterpart of the temp's `fd.sync()`. On Android 26+
 * the NIO channel opens the directory read-only; below 26 `java.nio.file` does not exist, so
 * libcore's `FileInputStream` (which opens directories read-only there) does the job. A plain JVM
 * reports `SDK_INT == 0` (android.jar stubs; a device always reports >= 1) and takes the NIO
 * branch, because the desktop JDK refuses directories in `FileInputStream`. Failures throw to
 * the writer's stage handling; nothing is logged.
 */
private fun syncDirectoryEntry(directory: File) {
    if (Build.VERSION.SDK_INT >= 26 || Build.VERSION.SDK_INT == 0) {
        RecentEmojiDirectorySyncNio.sync(directory)
    } else {
        FileInputStream(directory).use { input -> input.fd.sync() }
    }
}

/**
 * The API 26+ directory sync, isolated in its own class so a runtime below 26 never loads it.
 * `File.toPath` and `FileChannel.open` exist only there.
 */
// @RequiresApi lives in androidx.annotation, which the app does not depend on.
@SuppressLint("UseRequiresApi")
@TargetApi(Build.VERSION_CODES.O)
private object RecentEmojiDirectorySyncNio {
    fun sync(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}
