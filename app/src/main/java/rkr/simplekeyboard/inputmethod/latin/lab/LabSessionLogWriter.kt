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

package rkr.simplekeyboard.inputmethod.latin.lab

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Append-only writer of the lab session log: one ASCII line per event,
 * `<epochMillis> <kind> <keyCode> <arm>`, space-separated. The API accepts numbers only, so no
 * typed text can reach the file through it; LabSessionLogContractTest pins that surface. Pure
 * java.io, so the format and the rotation run in plain JVM tests.
 *
 * Rotation: an append that would push [file] past [maxBytes] first renames it to `<name>.1`
 * (replacing an older rotation), so at most two files exist and the pair stays under about twice
 * [maxBytes].
 */
class LabSessionLogWriter(
    private val file: File,
    private val maxBytes: Long = MAX_BYTES,
) {
    private var out: BufferedWriter? = null
    private var fileBytes = -1L

    @Synchronized
    fun append(timestampMillis: Long, kind: Int, keyCode: Int, arm: Int) {
        val line = StringBuilder(32)
            .append(timestampMillis).append(' ')
            .append(kind).append(' ')
            .append(keyCode).append(' ')
            .append(arm).append('\n')
        var target = out
        if (target == null) {
            fileBytes = file.length()
            target = open(append = true)
        }
        if (fileBytes > 0L && fileBytes + line.length > maxBytes) {
            target.close()
            val rotated = rotatedFile(file)
            rotated.delete()
            file.renameTo(rotated)
            fileBytes = 0L
            target = open(append = true)
        }
        target.append(line)
        fileBytes += line.length
    }

    @Synchronized
    fun flush() {
        out?.flush()
    }

    /** Closes the writer and deletes the log and its rotation. */
    @Synchronized
    fun clear() {
        close()
        file.delete()
        rotatedFile(file).delete()
    }

    @Synchronized
    fun close() {
        out?.close()
        out = null
    }

    private fun open(append: Boolean): BufferedWriter =
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, append), Charsets.US_ASCII))
            .also { out = it }

    companion object {
        const val FILE_NAME = "lab-session.log"
        const val ROTATED_SUFFIX = ".1"

        /** One rotation caps the log pair at about twice this many bytes. */
        const val MAX_BYTES = 256L * 1024L

        const val KIND_LETTER = 1
        const val KIND_SPACE = 2
        const val KIND_DELETE = 3
        const val KIND_KEYBOARD_SHOWN = 4
        const val KIND_KEYBOARD_HIDDEN = 5

        /** The key-code column of the shown and hidden lines, which have no key. */
        const val KEY_CODE_NONE = 0

        fun rotatedFile(file: File): File = File(file.parentFile, file.name + ROTATED_SUFFIX)
    }
}
