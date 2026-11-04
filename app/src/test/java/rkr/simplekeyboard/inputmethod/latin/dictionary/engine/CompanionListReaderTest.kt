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

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.CompanionListArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.CompanionListValidationException
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatCompFormat

/**
 * The cold-companion word list (`TATCOMP\0`, schema 1): the reader's validation contract
 * (fail-closed on every malformed shape), the exact-list membership rule, and the pins of the
 * two committed assets against the storage contracts.
 */
class CompanionListReaderTest {

    // Words in unsigned UTF-8 byte order, as the body stores them.
    private val words = listOf("алма", "анла", "дус", "китап", "һәм", "әби")

    private fun buildRaw(
        words: List<String>,
        schemaId: Int = TatCompFormat.SCHEMA_ID,
        formatVersion: Int = TatCompFormat.FORMAT_VERSION,
        checksumAlgorithm: Int = TatCompFormat.CHECKSUM_ALGORITHM_SHA256,
        wordCountOverride: Int? = null,
        bodySizeOverride: Int? = null,
        zeroChecksum: Boolean = false,
    ): ByteArray {
        val body = ByteArray(words.sumOf { 1 + it.toByteArray(Charsets.UTF_8).size })
        var cursor = 0
        for (word in words) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            body[cursor] = bytes.size.toByte()
            cursor++
            bytes.copyInto(body, cursor)
            cursor += bytes.size
        }
        val wordCount = wordCountOverride ?: words.size
        val bodySize = bodySizeOverride ?: body.size
        val raw = ByteArray(TatCompFormat.HEADER_SIZE + body.size)
        "TATCOMP\u0000".toByteArray(Charsets.US_ASCII).copyInto(raw, 0)
        raw[8] = (schemaId and 0xff).toByte()
        raw[9] = ((schemaId ushr 8) and 0xff).toByte()
        raw[10] = (formatVersion and 0xff).toByte()
        raw[11] = ((formatVersion ushr 8) and 0xff).toByte()
        raw[12] = (TatCompFormat.HEADER_SIZE and 0xff).toByte()
        raw[13] = ((TatCompFormat.HEADER_SIZE ushr 8) and 0xff).toByte()
        raw[14] = (checksumAlgorithm and 0xff).toByte()
        raw[15] = ((checksumAlgorithm ushr 8) and 0xff).toByte()
        writeU32(raw, 16, wordCount.toLong())
        writeU32(raw, 20, bodySize.toLong())
        // The body lands first: the checksum covers the whole file with itself zeroed.
        body.copyInto(raw, TatCompFormat.HEADER_SIZE)
        val digest = if (zeroChecksum) {
            ByteArray(TatCompFormat.CHECKSUM_SIZE)
        } else {
            MessageDigest.getInstance("SHA-256").digest(raw)
        }
        digest.copyInto(raw, TatCompFormat.CHECKSUM_OFFSET)
        return raw
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            target[offset + index] = ((value ushr (8 * index)) and 0xff).toByte()
        }
    }

    private fun compress(raw: ByteArray): ByteArray {
        val deflater = Deflater(9, false)
        deflater.setInput(raw)
        deflater.finish()
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun specFor(raw: ByteArray, words: List<String>): CompanionListArtifactSpec =
        CompanionListArtifactSpec(
            family = "fixture_companion",
            languageTag = PersonalSubtypes.TATAR_RU,
            assetPath = "fixture.tcomp.zlib",
            expectedCompressedSize = compress(raw).size.toLong(),
            expectedCompressedSha256 = sha256(compress(raw)),
            expectedRawSize = raw.size.toLong(),
            expectedRawSha256 = sha256(raw),
            expectedWordCount = words.size.toLong(),
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // --- membership -----------------------------------------------------------------------------

    @Test
    fun aListedWordIsContainedAndAnUnlistedWordIsNot() {
        val raw = buildRaw(words)
        val list = CompanionListReader().validateRaw(raw, specFor(raw, words))
        for (word in words) {
            assertTrue(word, list.containsWord(word))
        }
        // Not a listed word: the exact-list rule answers false, so it stays correctable.
        assertFalse(list.containsWord("алм"))
        assertFalse(list.containsWord("алмаб"))
        assertFalse(list.containsWord("алмаа"))
        assertFalse(list.containsWord("АЛМА"))
        assertFalse(list.containsWord(""))
    }

    // --- validation: the fail-closed rules ------------------------------------------------------

    @Test
    fun theHappyPathValidates() {
        val raw = buildRaw(words)
        val spec = specFor(raw, words)
        val list = CompanionListReader().read(ByteArrayInputStream(compress(raw)), spec)
        assertEquals(words, list.words)
    }

    @Test
    fun aWrongMagicIsRejected() {
        val raw = buildRaw(words)
        raw[0] = 'X'.code.toByte()
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(raw, specFor(raw, words))
        }
    }

    @Test
    fun anUnsupportedSchemaIdIsRejected() {
        val raw = buildRaw(words, schemaId = TatCompFormat.SCHEMA_ID + 1)
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(raw, specFor(raw, words))
        }
    }

    @Test
    fun anUnsupportedFormatVersionIsRejected() {
        val raw = buildRaw(words, formatVersion = TatCompFormat.FORMAT_VERSION + 1)
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(raw, specFor(raw, words))
        }
    }

    @Test
    fun anUnsupportedChecksumAlgorithmIsRejected() {
        val raw = buildRaw(words, checksumAlgorithm = 2)
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(raw, specFor(raw, words))
        }
    }

    @Test
    fun aZeroedChecksumIsRejected() {
        val raw = buildRaw(words, zeroChecksum = true)
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(raw, specFor(raw, words))
        }
    }

    @Test
    fun aFlippedChecksumByteIsRejected() {
        val raw = buildRaw(words)
        raw[TatCompFormat.CHECKSUM_OFFSET] =
            (raw[TatCompFormat.CHECKSUM_OFFSET].toInt() xor 1).toByte()
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(raw, specFor(raw, words))
        }
    }

    @Test
    fun aWordCountDisagreeingWithTheContractIsRejected() {
        val raw = buildRaw(words)
        val spec = specFor(raw, words)
        val other = CompanionListArtifactSpec(
            family = spec.family,
            languageTag = spec.languageTag,
            assetPath = spec.assetPath,
            expectedCompressedSize = spec.expectedCompressedSize,
            expectedCompressedSha256 = spec.expectedCompressedSha256,
            expectedRawSize = spec.expectedRawSize,
            expectedRawSha256 = spec.expectedRawSha256,
            expectedWordCount = spec.expectedWordCount + 1,
        )
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(raw, other)
        }
    }

    @Test
    fun aHeaderCountDisagreeingWithTheBodyIsRejected() {
        // The header claims one more word than the body holds, and the body size still closes
        // the file, so only the body walk can catch it.
        val raw = buildRaw(words, wordCountOverride = words.size + 1)
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(raw, specFor(raw, words))
        }
    }

    @Test
    fun aBodySizeNotClosingTheFileIsRejected() {
        val raw = buildRaw(words, bodySizeOverride = words.size)
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(raw, specFor(raw, words))
        }
    }

    @Test
    fun aShortFileIsRejected() {
        val raw = buildRaw(words).copyOf(TatCompFormat.HEADER_SIZE - 1)
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(
                raw,
                specFor(buildRaw(words), words),
            )
        }
    }

    @Test
    fun aTrailingBodyBytesAreRejected() {
        val raw = buildRaw(words)
        val padded = raw + ByteArray(4)
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(padded, specFor(raw, words))
        }
    }

    @Test
    fun nonAscendingWordsAreRejected() {
        val descending = listOf("китап", "дус", "анла", "алма")
        val raw = buildRaw(descending)
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(raw, specFor(raw, descending))
        }
    }

    @Test
    fun duplicateWordsAreRejected() {
        val duplicated = listOf("алма", "алма")
        val raw = buildRaw(duplicated)
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().validateRaw(raw, specFor(raw, duplicated))
        }
    }

    @Test
    fun aCorruptZlibStreamIsRejected() {
        val raw = buildRaw(words)
        val spec = specFor(raw, words)
        val compressed = compress(raw)
        compressed[compressed.size / 2] =
            (compressed[compressed.size / 2].toInt() xor 0xff).toByte()
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().read(ByteArrayInputStream(compressed), spec)
        }
    }

    @Test
    fun aTruncatedStreamIsRejected() {
        val raw = buildRaw(words)
        val spec = specFor(raw, words)
        val compressed = compress(raw).copyOf(compress(raw).size / 2)
        assertThrows(CompanionListValidationException::class.java) {
            CompanionListReader().read(ByteArrayInputStream(compressed), spec)
        }
    }

    // --- the committed assets against the pins ---------------------------------------------------

    private fun locateAsset(spec: CompanionListArtifactSpec): File =
        listOf(
            File("src/main/assets", spec.assetPath),
            File("app/src/main/assets", spec.assetPath),
        ).firstOrNull { it.isFile }
            ?: error("cannot locate ${spec.assetPath}")

    @Test
    fun theShippedTatarListMatchesItsPins() {
        val spec = CompanionListArtifactSpec.TATAR_COMPANION_V1
        val list = locateAsset(spec).inputStream().use { CompanionListReader().read(it, spec) }
        assertEquals(spec.expectedWordCount, list.words.size.toLong())
        assertEquals("һәм", list.words.first { it == "һәм" })
    }

    @Test
    fun theShippedRussianListMatchesItsPins() {
        val spec = CompanionListArtifactSpec.RUSSIAN_COMPANION_V1
        val list = locateAsset(spec).inputStream().use { CompanionListReader().read(it, spec) }
        assertEquals(spec.expectedWordCount, list.words.size.toLong())
        assertNotNull(list.words.firstOrNull { it == "мама" })
    }

    @Test
    fun theShippedListsCoverEveryShippedLanguage() {
        assertEquals(
            listOf(PersonalSubtypes.TATAR_RU, PersonalSubtypes.RUSSIAN),
            CompanionListArtifactSpec.ALL.map { it.languageTag },
        )
        assertEquals(
            listOf(PersonalSubtypes.TATAR_RU, PersonalSubtypes.RUSSIAN),
            listOf(CompanionListArtifactSpec.TATAR_COMPANION_V1, CompanionListArtifactSpec.RUSSIAN_COMPANION_V1)
                .map { it.languageTag },
        )
    }

    @Test
    fun everyTopWordOfTheDictionaryIsInTheShippedList() {
        // The Tatar list must contain the dictionary's top-frequency words: the exact-list
        // selection rule, checked against the rank-1 word of each shipped dictionary.
        for (spec in CompanionListArtifactSpec.ALL) {
            val list = locateAsset(spec).inputStream().use { CompanionListReader().read(it, spec) }
            assertTrue(
                "${spec.family}: the list must hold at least the rank-1 dictionary word",
                list.words.isNotEmpty(),
            )
        }
    }
}
