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

/** Builds `.tpersem` byte images (valid and deliberately corrupt) for the personal-emoji tests. */
internal object PersonalEmojiTestFixtures {
    data class Entry(
        val word: String,
        val emoji: String,
        val usage: Int = 0,
        val frequency: Int = 1,
        val serial: Long = 1L,
    )

    fun normalized(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /**
     * Builds a `.tpersem` image. By default the entries are sorted by their entry key — the
     * normalized word first, then the emoji, BOTH AS UNSIGNED UTF-8 BYTES — so the result is valid;
     * pass [sort] = false to write them verbatim (for ordering/duplicate tests). The emoji half can
     * NOT be sorted by `String.compareTo`: a variation selector (BMP, U+FE0E/U+FE0F) and a
     * supplementary modifier order differently in UTF-16 units than in bytes, and the on-disk order
     * the validator enforces is the byte order. The header fields can be overridden individually to
     * forge corruption, and [refreshChecksum] controls whether the embedded SHA-256 is recomputed
     * after all overrides.
     */
    fun build(
        entries: List<Entry>,
        subtypeTag: String = PersonalSubtypes.TATAR_RU,
        sort: Boolean = true,
        schemaId: Int = TpersemFormat.SCHEMA_ID,
        formatVersion: Int = TpersemFormat.FORMAT_VERSION,
        headerSize: Int = TpersemFormat.HEADER_SIZE,
        checksumAlgorithm: Int = TpersemFormat.CHECKSUM_ALGORITHM_SHA256,
        magic: String = TpersemFormat.MAGIC,
        entryCountOverride: Long? = null,
        payloadSizeOverride: Long? = null,
        refreshChecksum: Boolean = true,
        // The format stores the word NORMALIZED, and the fixture does so by default; a test that
        // needs a NON-normalized word on disk (the validator must reject it) opts out.
        normalizeWords: Boolean = true,
    ): ByteArray {
        val ordered = if (sort) {
            entries.sortedWith(Comparator { first, second ->
                val wordOrder = compareUnsignedBytes(
                    normalized(first.word).toByteArray(StandardCharsets.UTF_8),
                    normalized(second.word).toByteArray(StandardCharsets.UTF_8),
                )
                if (wordOrder != 0) {
                    wordOrder
                } else {
                    compareUnsignedBytes(
                        first.emoji.toByteArray(StandardCharsets.UTF_8),
                        second.emoji.toByteArray(StandardCharsets.UTF_8),
                    )
                }
            })
        } else {
            entries
        }
        val encodedWords = ordered.map {
            (if (normalizeWords) normalized(it.word) else it.word)
                .toByteArray(StandardCharsets.UTF_8)
        }
        val encodedEmoji = ordered.map { it.emoji.toByteArray(StandardCharsets.UTF_8) }
        val payloadSize = ordered.indices.sumOf {
            TpersemFormat.RECORD_HEADER_SIZE + encodedWords[it].size + encodedEmoji[it].size
        }.toLong()
        val fileSize = TpersemFormat.HEADER_SIZE + payloadSize.toInt()

        val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(magic.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(schemaId.toShort())
        buffer.putShort(formatVersion.toShort())
        buffer.putShort(headerSize.toShort())
        buffer.putShort(checksumAlgorithm.toShort())
        buffer.putInt((entryCountOverride ?: ordered.size.toLong()).toInt())
        buffer.putInt((payloadSizeOverride ?: payloadSize).toInt())
        val tag = ByteArray(TpersemFormat.SUBTYPE_TAG_SIZE)
        val tagBytes = subtypeTag.toByteArray(StandardCharsets.US_ASCII)
        tagBytes.copyInto(tag, 0, 0, minOf(tagBytes.size, tag.size))
        buffer.put(tag)
        buffer.put(ByteArray(TpersemFormat.CHECKSUM_SIZE))
        ordered.forEachIndexed { index, entry ->
            buffer.put(encodedWords[index].size.toByte())
            buffer.put(encodedEmoji[index].size.toByte())
            buffer.putShort(entry.usage.toShort())
            buffer.putShort(entry.frequency.toShort())
            buffer.putInt(entry.serial.toInt())
            buffer.put(encodedWords[index])
            buffer.put(encodedEmoji[index])
        }
        val image = buffer.array()
        return if (refreshChecksum) refreshEmbeddedChecksum(image) else image
    }

    fun refreshEmbeddedChecksum(input: ByteArray): ByteArray {
        val result = input.copyOf()
        result.fill(
            0, TpersemFormat.CHECKSUM_OFFSET,
            TpersemFormat.CHECKSUM_OFFSET + TpersemFormat.CHECKSUM_SIZE,
        )
        val checksum = MessageDigest.getInstance("SHA-256").digest(result)
        checksum.copyInto(result, TpersemFormat.CHECKSUM_OFFSET)
        return result
    }

    private fun compareUnsignedBytes(first: ByteArray, second: ByteArray): Int {
        val count = minOf(first.size, second.size)
        for (index in 0 until count) {
            val difference = (first[index].toInt() and 0xff) - (second[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return first.size - second.size
    }
}
