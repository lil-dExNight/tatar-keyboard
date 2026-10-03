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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

/**
 * An immutable in-memory snapshot of the user's text shortcuts: two parallel arrays ordered by the
 * shortcut's UTF-8 bytes ascending (unsigned) — the exact order `TcutValidator` enforces on disk.
 * The shortcut is the lookup key as the user typed it (NFC, case kept); the expansion is verbatim.
 *
 * Every mutation returns a new instance; nothing is changed in place. The store keeps one instance
 * as its worker-side model and publishes it to readers as-is, so there is no separate snapshot
 * type. A plain class without a generated `toString`, because it carries the user's text.
 */
class TextShortcuts private constructor(
    private val shortcuts: Array<String>,
    private val expansions: Array<String>,
) {
    val size: Int
        get() = shortcuts.size

    val isEmpty: Boolean
        get() = shortcuts.isEmpty()

    fun shortcutAt(index: Int): String = shortcuts[index]

    fun expansionAt(index: Int): String = expansions[index]

    /**
     * The expansion stored for [typedWord], or null. The match is exact after NFC folding: the
     * strip offers an expansion only while the typed word IS a shortcut, casing included.
     */
    fun expansionFor(typedWord: String): String? {
        if (isEmpty || typedWord.isEmpty()) return null
        val key = Normalizer.normalize(typedWord, Normalizer.Form.NFC)
        val index = indexOfShortcut(key)
        return if (index >= 0) expansions[index] else null
    }

    /**
     * Inserts or replaces the (shortcut → expansion) pair, keeping the byte order. Returns a new
     * instance; the caller enforces the entry cap and the content rules.
     */
    fun upsert(shortcut: String, expansion: String): TextShortcuts {
        val existing = indexOfShortcut(shortcut)
        if (existing >= 0) {
            if (expansions[existing] == expansion) return this
            val updated = expansions.copyOf()
            updated[existing] = expansion
            return TextShortcuts(shortcuts, updated)
        }
        val position = insertionPoint(shortcut)
        val newShortcuts = shortcuts.toMutableList().apply { add(position, shortcut) }
        val newExpansions = expansions.toMutableList().apply { add(position, expansion) }
        return TextShortcuts(newShortcuts.toTypedArray(), newExpansions.toTypedArray())
    }

    /** Removes [shortcut] if present, returning a new instance; returns `this` when absent. */
    fun remove(shortcut: String): TextShortcuts {
        val index = indexOfShortcut(shortcut)
        if (index < 0) return this
        val newShortcuts = shortcuts.toMutableList().apply { removeAt(index) }
        val newExpansions = expansions.toMutableList().apply { removeAt(index) }
        if (newShortcuts.isEmpty()) return EMPTY
        return TextShortcuts(newShortcuts.toTypedArray(), newExpansions.toTypedArray())
    }

    /** The in-memory bytes to be written whole to disk, in the frozen `.tcut` layout. */
    fun serialize(): ByteArray {
        val encodedShortcuts = shortcuts.map { it.toByteArray(StandardCharsets.UTF_8) }
        val encodedExpansions = expansions.map { it.toByteArray(StandardCharsets.UTF_8) }
        val payloadSize = (0 until size)
            .sumOf { TcutFormat.RECORD_HEADER_SIZE + encodedShortcuts[it].size + encodedExpansions[it].size }
        val fileSize = TcutFormat.HEADER_SIZE + payloadSize

        val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(TcutFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(TcutFormat.SCHEMA_ID.toShort())
        buffer.putShort(TcutFormat.FORMAT_VERSION.toShort())
        buffer.putShort(TcutFormat.HEADER_SIZE.toShort())
        buffer.putShort(TcutFormat.CHECKSUM_ALGORITHM_SHA256.toShort())
        buffer.putInt(size)
        buffer.putInt(payloadSize)
        buffer.put(ByteArray(TcutFormat.RESERVED_SIZE))
        buffer.put(ByteArray(TcutFormat.CHECKSUM_SIZE))
        for (index in shortcuts.indices) {
            buffer.put(encodedShortcuts[index].size.toByte())
            buffer.putShort(encodedExpansions[index].size.toShort())
            buffer.put(encodedShortcuts[index])
            buffer.put(encodedExpansions[index])
        }

        val image = buffer.array()
        val checksum = MessageDigest.getInstance("SHA-256").digest(image)
        checksum.copyInto(image, TcutFormat.CHECKSUM_OFFSET)
        return image
    }

    private fun indexOfShortcut(shortcut: String): Int {
        val point = insertionPoint(shortcut)
        return if (point < size && compareShortcuts(shortcuts[point], shortcut) == 0) point else -1
    }

    private fun insertionPoint(shortcut: String): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareShortcuts(shortcuts[mid], shortcut) < 0) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        @JvmField
        val EMPTY = TextShortcuts(emptyArray(), emptyArray())

        internal fun of(validated: ValidatedTextShortcuts): TextShortcuts {
            if (validated.entryCount == 0) return EMPTY
            return TextShortcuts(
                validated.shortcuts.toTypedArray(),
                validated.expansions.toTypedArray(),
            )
        }

        /**
         * Compares two shortcuts by their UTF-8 bytes, unsigned — the exact order `TcutValidator`
         * requires on disk, so writer, validator and reader agree.
         */
        internal fun compareShortcuts(first: String, second: String): Int {
            val a = first.toByteArray(StandardCharsets.UTF_8)
            val b = second.toByteArray(StandardCharsets.UTF_8)
            val count = minOf(a.size, b.size)
            for (index in 0 until count) {
                val difference = (a[index].toInt() and 0xff) - (b[index].toInt() and 0xff)
                if (difference != 0) return difference
            }
            return a.size - b.size
        }
    }
}
