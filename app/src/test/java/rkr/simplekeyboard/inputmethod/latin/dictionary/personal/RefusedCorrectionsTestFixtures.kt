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

/** Builds `.tref` byte images (valid and deliberately corrupt) for the refused-corrections tests. */
internal object RefusedCorrectionsTestFixtures {

    /** One record body, outside any container structure. */
    fun record(typed: String, replacement: String, refusalCount: Int): ByteArray {
        val typedBytes = typed.toByteArray(StandardCharsets.UTF_8)
        val replacementBytes = replacement.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer
            .allocate(TrefFormat.RECORD_HEADER_SIZE + typedBytes.size + replacementBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(typedBytes.size.toByte())
        buffer.put(replacementBytes.size.toByte())
        buffer.put(refusalCount.toByte())
        buffer.put(typedBytes)
        buffer.put(replacementBytes)
        return buffer.array()
    }

    /**
     * Builds a `.tref` image around [payload], claiming [entryCount] records (an honest count, or
     * a forged one when the payload cannot back it). The records stay in the given order: the
     * format keeps the refusal order, there is no sorting to forge. [refreshChecksum] controls
     * whether the embedded SHA-256 is recomputed.
     */
    fun build(
        payload: ByteArray,
        entryCount: Int,
        subtypeTag: String = PersonalSubtypes.TATAR_RU,
        refreshChecksum: Boolean = true,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(TrefFormat.HEADER_SIZE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(TrefFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(TrefFormat.SCHEMA_ID.toShort())
        buffer.putShort(TrefFormat.FORMAT_VERSION.toShort())
        buffer.putShort(TrefFormat.HEADER_SIZE.toShort())
        buffer.putShort(TrefFormat.CHECKSUM_ALGORITHM_SHA256.toShort())
        buffer.putInt(entryCount)
        buffer.putInt(payload.size)
        val tag = ByteArray(TrefFormat.SUBTYPE_TAG_SIZE)
        subtypeTag.toByteArray(StandardCharsets.US_ASCII).copyInto(tag)
        buffer.put(tag)
        buffer.put(ByteArray(TrefFormat.CHECKSUM_SIZE))
        buffer.put(payload)
        val image = buffer.array()
        return if (refreshChecksum) refreshEmbeddedChecksum(image) else image
    }

    fun refreshEmbeddedChecksum(input: ByteArray): ByteArray {
        val result = input.copyOf()
        result.fill(0, TrefFormat.CHECKSUM_OFFSET, TrefFormat.CHECKSUM_OFFSET + TrefFormat.CHECKSUM_SIZE)
        val checksum = MessageDigest.getInstance("SHA-256").digest(result)
        checksum.copyInto(result, TrefFormat.CHECKSUM_OFFSET)
        return result
    }
}
