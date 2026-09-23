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

/** Builds `.tpersb` byte images (valid and deliberately corrupt) for the personal-bigram tests. */
internal object PersonalBigramTestFixtures {
    data class Entry(
        val context: String,
        val successor: String,
        val usage: Int = 0,
        val frequency: Int = 1,
        val serial: Long = 1L,
    )

    fun normalized(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /**
     * Builds a `.tpersb` image. By default the entries are sorted by their pair key — the
     * normalized context first, then the normalized successor, both as unsigned UTF-8 bytes — so
     * the result is valid; pass [sort] = false to write them verbatim (for ordering/duplicate
     * tests). The header fields can be overridden individually to forge corruption, and
     * [refreshChecksum] controls whether the embedded SHA-256 is recomputed after all overrides.
     */
    fun build(
        entries: List<Entry>,
        subtypeTag: String = PersonalSubtypes.TATAR_RU,
        sort: Boolean = true,
        schemaId: Int = TpersbFormat.SCHEMA_ID,
        formatVersion: Int = TpersbFormat.FORMAT_VERSION,
        headerSize: Int = TpersbFormat.HEADER_SIZE,
        checksumAlgorithm: Int = TpersbFormat.CHECKSUM_ALGORITHM_SHA256,
        magic: String = TpersbFormat.MAGIC,
        pairCountOverride: Long? = null,
        payloadSizeOverride: Long? = null,
        refreshChecksum: Boolean = true,
        // The format stores the context NORMALIZED, and the fixture does so by default; a test
        // that needs a NON-normalized context on disk (the validator must reject it) opts out.
        normalizeContexts: Boolean = true,
    ): ByteArray {
        val ordered = if (sort) {
            entries.sortedWith(
                compareBy({ normalized(it.context) }, { normalized(it.successor) }),
            )
        } else {
            entries
        }
        val encodedContexts = ordered.map {
            (if (normalizeContexts) normalized(it.context) else it.context)
                .toByteArray(StandardCharsets.UTF_8)
        }
        val encodedSuccessors = ordered.map { it.successor.toByteArray(StandardCharsets.UTF_8) }
        val payloadSize = ordered.indices.sumOf {
            TpersbFormat.RECORD_HEADER_SIZE + encodedContexts[it].size + encodedSuccessors[it].size
        }.toLong()
        val fileSize = TpersbFormat.HEADER_SIZE + payloadSize.toInt()

        val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(magic.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(schemaId.toShort())
        buffer.putShort(formatVersion.toShort())
        buffer.putShort(headerSize.toShort())
        buffer.putShort(checksumAlgorithm.toShort())
        buffer.putInt((pairCountOverride ?: ordered.size.toLong()).toInt())
        buffer.putInt((payloadSizeOverride ?: payloadSize).toInt())
        val tag = ByteArray(TpersbFormat.SUBTYPE_TAG_SIZE)
        val tagBytes = subtypeTag.toByteArray(StandardCharsets.US_ASCII)
        tagBytes.copyInto(tag, 0, 0, minOf(tagBytes.size, tag.size))
        buffer.put(tag)
        buffer.put(ByteArray(TpersbFormat.CHECKSUM_SIZE))
        ordered.forEachIndexed { index, entry ->
            buffer.put(encodedContexts[index].size.toByte())
            buffer.put(encodedSuccessors[index].size.toByte())
            buffer.putShort(entry.usage.toShort())
            buffer.putShort(entry.frequency.toShort())
            buffer.putInt(entry.serial.toInt())
            buffer.put(encodedContexts[index])
            buffer.put(encodedSuccessors[index])
        }
        val image = buffer.array()
        return if (refreshChecksum) refreshEmbeddedChecksum(image) else image
    }

    fun refreshEmbeddedChecksum(input: ByteArray): ByteArray {
        val result = input.copyOf()
        result.fill(
            0, TpersbFormat.CHECKSUM_OFFSET, TpersbFormat.CHECKSUM_OFFSET + TpersbFormat.CHECKSUM_SIZE,
        )
        val checksum = MessageDigest.getInstance("SHA-256").digest(result)
        checksum.copyInto(result, TpersbFormat.CHECKSUM_OFFSET)
        return result
    }
}
