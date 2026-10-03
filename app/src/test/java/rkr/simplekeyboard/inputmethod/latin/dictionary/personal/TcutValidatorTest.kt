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

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The `.tcut` validator: a valid image parses, and every structural, checksum, content, ordering
 * or duplicate violation is rejected. Builds its byte images by hand, so both valid and deliberately
 * corrupt layouts are covered. Plain JVM.
 */
class TcutValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun aValidImageParsesIntoOrderedPairs() {
        val validated = TcutValidator().validate(write(build(listOf("бик" to "бик үк", "тк" to "Татарстан"))))

        assertEquals(listOf("бик", "тк"), validated.shortcuts)
        assertEquals(listOf("бик үк", "Татарстан"), validated.expansions)
    }

    @Test
    fun anEmptyImageParsesIntoNoPairs() {
        assertEquals(0, TcutValidator().validate(write(build(emptyList()))).entryCount)
    }

    @Test
    fun rejectsTheWrongMagic() = assertRejected(build(listOf("тк" to "Татарстан"), magic = "TATPERS\u0000"))

    @Test
    fun rejectsAnUnsupportedSchemaAndFormat() {
        assertRejected(build(listOf("тк" to "Татарстан"), schemaId = 2))
        assertRejected(build(listOf("тк" to "Татарстан"), formatVersion = 2))
    }

    @Test
    fun rejectsAChecksumMismatch() {
        val image = build(listOf("тк" to "Татарстан"))
        image[image.size - 1] = (image[image.size - 1].toInt() xor 0x7f).toByte()
        assertRejected(image)
    }

    @Test
    fun rejectsAStalePayloadSize() =
        assertRejected(build(listOf("тк" to "Татарстан"), payloadSizeOverride = 1L))

    @Test
    fun rejectsAnEntryCountOverTheCap() =
        assertRejected(build(emptyList(), entryCountOverride = TcutFormat.MAX_SHORTCUT_ENTRIES + 1))

    @Test
    fun rejectsUnsortedAndDuplicateShortcuts() {
        assertRejected(build(listOf("тк" to "Татарстан", "бик" to "бик үк"), sort = false))
        assertRejected(build(listOf("тк" to "Татарстан", "тк" to "Татарстан"), sort = false))
    }

    @Test
    fun rejectsTrailingPayloadBytes() {
        // One recordless payload byte: the sizes agree, the checksum is fresh, and the parser must
        // still refuse bytes no record claimed.
        val base = build(emptyList())
        val image = base.copyOf(base.size + 1)
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TcutFormat.PAYLOAD_SIZE_OFFSET, 1)
        assertRejected(refreshChecksum(image))
    }

    @Test
    fun rejectsNonUtf8Records() {
        val image = buildWithRawRecord(byteArrayOf(0xC3.toByte()), "текст".toByteArray(StandardCharsets.UTF_8))
        assertRejected(image)
    }

    @Test
    fun rejectsContentTheFilterWouldNeverAccept() {
        assertRejected(build(listOf("тк2" to "санау")), "a digit can never be typed as one word")
        assertRejected(buildWithRawRecord("тк".toByteArray(StandardCharsets.UTF_8),
            "line\nbreak".toByteArray(StandardCharsets.UTF_8)), "a line break is not a fixed phrase")
        assertRejected(build(listOf("" to "буш")), "an empty shortcut")
    }

    private fun assertRejected(image: ByteArray, why: String = "") {
        try {
            TcutValidator().validate(write(image))
            fail("must be rejected: $why")
        } catch (_: TextShortcutValidationException) {
            // Expected.
        }
    }

    private fun write(image: ByteArray): File =
        temporaryFolder.newFile().apply { writeBytes(image) }

    /** Builds a `.tcut` image; the header fields can be overridden individually to forge corruption. */
    private fun build(
        pairs: List<Pair<String, String>>,
        sort: Boolean = true,
        schemaId: Int = TcutFormat.SCHEMA_ID,
        formatVersion: Int = TcutFormat.FORMAT_VERSION,
        entryCountOverride: Long? = null,
        payloadSizeOverride: Long? = null,
        magic: String = TcutFormat.MAGIC,
    ): ByteArray {
        val ordered = if (sort) {
            pairs.sortedWith { a, b -> TextShortcuts.compareShortcuts(a.first, b.first) }
        } else {
            pairs
        }
        val shortcuts = ordered.map { it.first.toByteArray(StandardCharsets.UTF_8) }
        val expansions = ordered.map { it.second.toByteArray(StandardCharsets.UTF_8) }
        val payloadSize = shortcuts.indices
            .sumOf { TcutFormat.RECORD_HEADER_SIZE + shortcuts[it].size + expansions[it].size }.toLong()
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
        return refreshChecksum(image)
    }

    /** Builds an image of one record from raw bytes, so malformed encodings can be forged. */
    private fun buildWithRawRecord(shortcut: ByteArray, expansion: ByteArray): ByteArray {
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
        return refreshChecksum(buffer.array())
    }

    private fun refreshChecksum(image: ByteArray): ByteArray {
        image.fill(0, TcutFormat.CHECKSUM_OFFSET, TcutFormat.CHECKSUM_OFFSET + TcutFormat.CHECKSUM_SIZE)
        val checksum = MessageDigest.getInstance("SHA-256").digest(image)
        checksum.copyInto(image, TcutFormat.CHECKSUM_OFFSET)
        return image
    }
}
