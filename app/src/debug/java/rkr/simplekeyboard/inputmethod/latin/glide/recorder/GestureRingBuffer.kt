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

package rkr.simplekeyboard.inputmethod.latin.glide.recorder

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The recorder's medium: one JSONL file capped at [MAX_RECORDS] lines, the oldest line dropped
 * when a new one arrives at the cap. The file holds only [GestureRecord.toJsonLine] output; the
 * single owning directory is resolved by the caller, and production resolves it inside the base
 * (credential-protected) `noBackupFilesDir`, which is outside every backup domain.
 *
 * The in-memory line list mirrors the file exactly: appends stream to the tail while there is
 * room, and at the cap the whole file is rewritten from the list (a sub-second write of well
 * under a megabyte on a completed gesture, in a debug-only tool). Operations never throw: an IO
 * failure returns false and leaves the in-memory state consistent.
 */
class GestureRingBuffer(
    directory: File,
    private val maxRecords: Int = MAX_RECORDS,
    fileName: String = FILE_NAME,
) {
    private val file = File(directory, fileName)
    private val lock = ReentrantLock()
    private val lines = ArrayDeque<String>()

    init {
        require(maxRecords > 0) { "the ring buffer holds at least one record" }
        directory.mkdirs()
        loadFromDisk()
    }

    val size: Int
        get() = lock.withLock { lines.size }

    /** Appends one serialized record; false when the write failed. */
    fun append(line: String): Boolean = lock.withLock {
        if (lines.size >= maxRecords) {
            lines.removeFirst()
            lines.addLast(line)
            return rewrite()
        }
        lines.addLast(line)
        try {
            file.appendText(line + "\n", Charsets.UTF_8)
            true
        } catch (e: Exception) {
            lines.removeLast()
            false
        }
    }

    /** A copy of the buffered lines, oldest first. */
    fun snapshot(): List<String> = lock.withLock { lines.toList() }

    /** Discards every buffered record (the file is truncated to empty). */
    fun clear(): Boolean = lock.withLock {
        lines.clear()
        rewrite()
    }

    private fun loadFromDisk() {
        val loaded = try {
            if (file.isFile) file.readLines(Charsets.UTF_8) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        for (line in loaded) {
            if (line.isBlank()) continue
            if (lines.size >= maxRecords) lines.removeFirst()
            lines.addLast(line)
        }
        // A file that outgrew the cap (a raised cap is never expected, a lowered one is a
        // debug build change) is compacted back on the first load.
        if (loaded.count { it.isNotBlank() } > maxRecords) rewrite()
    }

    private fun rewrite(): Boolean = try {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(lines.joinToString(separator = "") { it + "\n" }, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        /** Retained-record cap: the ring buffer never holds more than this many records. */
        const val MAX_RECORDS = 2000

        /** The single file this store owns, inside the caller-resolved directory. */
        const val FILE_NAME = "gesture_records.jsonl"
    }
}
