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

/** Builds `.tcut` byte images (valid and deliberately corrupt) for the text-shortcut tests. */
internal object TextShortcutTestFixtures {
    data class Entry(val shortcut: String, val expansion: String)

    /**
     * Builds a `.tcut` image. By default the entries are sorted by the shortcut's unsigned UTF-8
     * byte order so the result is valid; pass [sort] = false to write them verbatim (for
     * ordering/duplicate tests). The header fields can be overridden individually to forge
     * corruption, and [refreshChecksum] controls whether the embedded SHA-256 is recomputed after
     * all overrides.
     */
    fun build(
        entries: List<Entry>,
        sort: Boolean = true,
        schemaId: Int = TcutFormat.SCHEMA_ID,
        formatVersion: Int = TcutFormat.FORMAT_VERSION,
        magic: String = TcutFormat.MAGIC,
        entryCountOverride: Long? = null,
        payloadSizeOverride: Long? = null,
        refreshChecksum: Boolean = true,
    ): ByteArray {
        val ordered = if (sort) {
            entries.sortedWith { a, b -> TextShortcuts.compareShortcuts(a.shortcut, b.shortcut) }
        } else {
            entries
        }
        val shortcuts = ordered.map { it.shortcut.toByteArray(StandardCharsets.UTF_8) }
        val expansions = ordered.map { it.expansion.toByteArray(StandardCharsets.UTF_8) }
        val payloadSize = shortcuts.indices
            .sumOf { TcutFormat.RECORD_HEADER_SIZE + shortcuts[it].size + expansions[it].size }
            .toLong()
        val buffer = ByteBuffer.allocate(TcutFormat.HEADER_SIZE + payloadSize.toInt())
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(magic.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(schemaId.toShort())
        buffer.putShort(formatVersion.toShort())
        buffer.putShort(TcutFormat.HEADER_SIZE.toShort())
        buffer.putShort(TcutFormat.CHECKSUM_ALGORITHM_SHA256.toShort())
        buffer.putInt((entryCountOverride ?: ordered.size.toLong()).toInt())
        buffer.putInt((payloadSizeOverride ?: payloadSize).toInt())
        buffer.put(ByteArray(TcutFormat.RESERVED_SIZE))
        buffer.put(ByteArray(TcutFormat.CHECKSUM_SIZE))
        for (index in ordered.indices) {
            buffer.put(shortcuts[index].size.toByte())
            buffer.putShort(expansions[index].size.toShort())
            buffer.put(shortcuts[index])
            buffer.put(expansions[index])
        }
        val image = buffer.array()
        return if (refreshChecksum) refreshEmbeddedChecksum(image) else image
    }

    /** Builds an image of one record from raw bytes, so malformed encodings can be forged. */
    fun buildRawRecord(shortcut: ByteArray, expansion: ByteArray): ByteArray {
        val payloadSize = TcutFormat.RECORD_HEADER_SIZE + shortcut.size + expansion.size
        val buffer = ByteBuffer.allocate(TcutFormat.HEADER_SIZE + payloadSize)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(TcutFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(TcutFormat.SCHEMA_ID.toShort())
        buffer.putShort(TcutFormat.FORMAT_VERSION.toShort())
        buffer.putShort(TcutFormat.HEADER_SIZE.toShort())
        buffer.putShort(TcutFormat.CHECKSUM_ALGORITHM_SHA256.toShort())
        buffer.putInt(1)
        buffer.putInt(payloadSize)
        buffer.put(ByteArray(TcutFormat.RESERVED_SIZE))
        buffer.put(ByteArray(TcutFormat.CHECKSUM_SIZE))
        buffer.put(shortcut.size.toByte())
        buffer.putShort(expansion.size.toShort())
        buffer.put(shortcut)
        buffer.put(expansion)
        return refreshEmbeddedChecksum(buffer.array())
    }

    fun refreshEmbeddedChecksum(input: ByteArray): ByteArray {
        val result = input.copyOf()
        result.fill(0, TcutFormat.CHECKSUM_OFFSET, TcutFormat.CHECKSUM_OFFSET + TcutFormat.CHECKSUM_SIZE)
        val checksum = MessageDigest.getInstance("SHA-256").digest(result)
        checksum.copyInto(result, TcutFormat.CHECKSUM_OFFSET)
        return result
    }
}
