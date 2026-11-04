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
import java.util.Locale

/** Builds `.tsent` byte images (valid and deliberately corrupt) for the sentence-start tests. */
internal object SentStartOverlayTestFixtures {
    data class Entry(val word: String, val count: Int = 1, val serial: Long = 1L)

    fun normalized(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /**
     * Builds a `.tsent` image. By default the entries are sorted by their normalized form so the
     * result is valid; pass [sort] = false to write them verbatim (for ordering/duplicate tests).
     * The header fields can be overridden individually to forge corruption, and [refreshChecksum]
     * controls whether the embedded SHA-256 is recomputed after all overrides.
     */
    fun build(
        entries: List<Entry>,
        subtypeTag: String = PersonalSubtypes.TATAR_RU,
        sort: Boolean = true,
        schemaId: Int = TsentFormat.SCHEMA_ID,
        formatVersion: Int = TsentFormat.FORMAT_VERSION,
        headerSize: Int = TsentFormat.HEADER_SIZE,
        checksumAlgorithm: Int = TsentFormat.CHECKSUM_ALGORITHM_SHA256,
        magic: String = TsentFormat.MAGIC,
        entryCountOverride: Long? = null,
        payloadSizeOverride: Long? = null,
        refreshChecksum: Boolean = true,
    ): ByteArray {
        val ordered = if (sort) entries.sortedWith(compareBy { normalized(it.word) }) else entries
        val encoded = ordered.map { it.word.toByteArray(StandardCharsets.UTF_8) }
        val payloadSize = encoded.sumOf { TsentFormat.RECORD_HEADER_SIZE + it.size }.toLong()
        val fileSize = TsentFormat.HEADER_SIZE + payloadSize.toInt()

        val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(magic.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(schemaId.toShort())
        buffer.putShort(formatVersion.toShort())
        buffer.putShort(headerSize.toShort())
        buffer.putShort(checksumAlgorithm.toShort())
        buffer.putInt((entryCountOverride ?: ordered.size.toLong()).toInt())
        buffer.putInt((payloadSizeOverride ?: payloadSize).toInt())
        val tag = ByteArray(TsentFormat.SUBTYPE_TAG_SIZE)
        val tagBytes = subtypeTag.toByteArray(StandardCharsets.US_ASCII)
        tagBytes.copyInto(tag, 0, 0, minOf(tagBytes.size, tag.size))
        buffer.put(tag)
        buffer.put(ByteArray(TsentFormat.CHECKSUM_SIZE))
        ordered.forEachIndexed { index, entry ->
            val bytes = encoded[index]
            buffer.put(bytes.size.toByte())
            buffer.putShort(entry.count.toShort())
            buffer.putInt(entry.serial.toInt())
            buffer.put(bytes)
        }
        val image = buffer.array()
        return if (refreshChecksum) refreshEmbeddedChecksum(image) else image
    }

    fun refreshEmbeddedChecksum(input: ByteArray): ByteArray {
        val result = input.copyOf()
        result.fill(0, TsentFormat.CHECKSUM_OFFSET, TsentFormat.CHECKSUM_OFFSET + TsentFormat.CHECKSUM_SIZE)
        val checksum = MessageDigest.getInstance("SHA-256").digest(result)
        checksum.copyInto(result, TsentFormat.CHECKSUM_OFFSET)
        return result
    }
}
