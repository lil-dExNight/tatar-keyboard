/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
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
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The `.tsent` validator: the golden fixture and every rejection rule, mirrored on
 * `TpersValidatorTest`. What differs from `.tpers` is pinned here too: the entry cap is the
 * overlay's own, and a one-code-point word is valid.
 */
class TsentValidatorTest {

    private val validator = TsentValidator()

    private val golden = listOf(
        Entry("китап", count = 30, serial = 20),
        Entry("сүз", count = 12, serial = 5),
        Entry("Сәләм", count = 5, serial = 10),
    )

    @Test
    fun acceptsGoldenFixture() {
        val validated = validator.validate(build(golden), PersonalSubtypes.TATAR_RU)

        assertEquals(3, validated.entryCount)
        assertEquals(PersonalSubtypes.TATAR_RU, validated.subtypeTag)
        // Words are ordered by their normalized form, and the raw uppercase form is preserved.
        assertEquals(listOf("китап", "сүз", "Сәләм"), validated.rawForms)
        assertEquals(listOf("китап", "сүз", "сәләм"), validated.normalizedForms)
        assertEquals(5, validated.usageCounts[2])
        assertEquals(10L, validated.lastUseSerials[2])
    }

    @Test
    fun acceptsAnEmptyOverlay() {
        assertEquals(0, validator.validate(build(emptyList()), PersonalSubtypes.TATAR_RU).entryCount)
    }

    @Test
    fun acceptsAOneCodePointWord() {
        val validated = validator.validate(
            build(listOf(Entry("Я")), subtypeTag = PersonalSubtypes.RUSSIAN), PersonalSubtypes.RUSSIAN)
        assertEquals(listOf("Я"), validated.rawForms)
        assertEquals(listOf("я"), validated.normalizedForms)
    }

    @Test
    fun rejectsWrongMagic() =
        assertRejected(build(golden, magic = "TATPERS\u0000"))

    @Test
    fun rejectsUnsupportedSchemaId() =
        assertRejected(build(golden, schemaId = 2))

    @Test
    fun rejectsUnsupportedFormatVersion() =
        assertRejected(build(golden, formatVersion = 2))

    @Test
    fun rejectsUnexpectedHeaderSize() =
        assertRejected(build(golden, headerSize = 71))

    @Test
    fun rejectsUnsupportedChecksumAlgorithm() =
        assertRejected(build(golden, checksumAlgorithm = 2))

    @Test
    fun rejectsSubtypeTagThatDoesNotMatchTheRequestedSubtype() =
        assertRejected(build(golden, subtypeTag = "ru_RU"))

    @Test
    fun rejectsEntryCountAboveTheOverlayCap() =
        assertRejected(build(golden, entryCountOverride = TsentFormat.MAX_SENTSTART_ENTRIES + 1))

    @Test
    fun rejectsPayloadSizeThatDoesNotMatchTheFile() =
        assertRejected(build(golden, payloadSizeOverride = 4096))

    @Test
    fun rejectsChecksumMismatch() {
        val bytes = build(golden)
        bytes[TsentFormat.CHECKSUM_OFFSET] = (bytes[TsentFormat.CHECKSUM_OFFSET].toInt() xor 1).toByte()
        assertRejected(bytes)
    }

    @Test
    fun rejectsZeroUsageCount() =
        assertRejected(build(listOf(Entry("китап", count = 0))))

    @Test
    fun rejectsInvalidUtf8Word() {
        val bytes = build(listOf(Entry("әби")))
        bytes[TsentFormat.HEADER_SIZE + TsentFormat.RECORD_HEADER_SIZE] = 0xFF.toByte()
        assertRejected(refreshEmbeddedChecksum(bytes))
    }

    @Test
    fun rejectsMixedCaseWord() =
        assertRejected(build(listOf(Entry("аБв"))))

    @Test
    fun rejectsWordOutsideTheSubtypeAlphabet() =
        assertRejected(build(listOf(Entry("abc"))))

    @Test
    fun rejectsUnsortedRecords() =
        // «әби» sorts after «китап» (unsigned UTF-8), so this order is invalid on disk.
        assertRejected(build(listOf(Entry("әби"), Entry("китап")), sort = false))

    @Test
    fun rejectsDuplicateNormalizedWords() =
        assertRejected(build(listOf(Entry("китап"), Entry("Китап")), sort = false))

    @Test
    fun rejectsTrailingPayloadBytes() {
        val valid = build(golden)
        val grown = valid.copyOf(valid.size + 1)
        // The header's payload size is bumped so only the trailing-byte rule can fire.
        val buffer = ByteBuffer.wrap(grown).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(TsentFormat.PAYLOAD_SIZE_OFFSET, grown.size - TsentFormat.HEADER_SIZE)
        assertRejected(refreshEmbeddedChecksum(grown))
    }

    private fun assertRejected(bytes: ByteArray) {
        try {
            validator.validate(bytes, PersonalSubtypes.TATAR_RU)
            fail("expected the file to be rejected")
        } catch (_: SentStartOverlayValidationException) {
            // Expected: every violation is the constant-message exception.
        }
    }

    // ---- the `.tsent` image builder, mirrored on PersonalDictionaryTestFixtures -------------------

    private data class Entry(val word: String, val count: Int = 1, val serial: Long = 1L)

    private fun normalized(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun build(
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

    private fun refreshEmbeddedChecksum(input: ByteArray): ByteArray {
        val result = input.copyOf()
        result.fill(0, TsentFormat.CHECKSUM_OFFSET, TsentFormat.CHECKSUM_OFFSET + TsentFormat.CHECKSUM_SIZE)
        val checksum = MessageDigest.getInstance("SHA-256").digest(result)
        checksum.copyInto(result, TsentFormat.CHECKSUM_OFFSET)
        return result
    }
}
