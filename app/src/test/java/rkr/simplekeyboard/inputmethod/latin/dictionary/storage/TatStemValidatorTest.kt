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

package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TatStemValidatorTest {
    private val validator = TatStemValidator()

    @Test
    fun acceptsGoldenFixture() {
        val artifact = StemLexiconTestFixtures.artifact()
        val validated = validator.inflateAsset(artifact.compressed.inputStream(), artifact.spec)

        assertEquals(40, validated.stemCount)
        assertEquals(5, validated.blockCount)
        assertEquals(TatStemFormat.HEADER_SIZE, validated.blockIndexOffset)
        assertEquals(artifact.spec.expectedRawSize, validated.raw.size.toLong())
    }

    @Test
    fun acceptsCommittedAssetAndChecksItsPins() {
        val spec = StemLexiconArtifactSpec.TATAR_STEMS_V1
        val validated = validator.inflateAsset(locateCommittedAsset().inputStream(), spec)

        assertEquals(spec.expectedStemCount, validated.stemCount.toLong())
        assertEquals(spec.expectedRawSize, validated.raw.size.toLong())
        assertEquals(spec.expectedRawSha256, StemLexiconTestFixtures.sha256(validated.raw))
        assertTrue(validated.raw.size <= TatStemFormat.MAX_RAW_SIZE)
    }

    @Test
    fun rejectsTruncatedAndCorruptedRawImages() {
        val artifact = StemLexiconTestFixtures.artifact()
        val truncated = artifact.raw.copyOf(artifact.raw.size - 1)
        val corrupt = artifact.raw.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertRawFails(truncated, artifact.spec)
        assertRawFails(corrupt, artifact.spec)
    }

    @Test
    fun rejectsHeaderAndSectionCorruption() {
        val artifact = StemLexiconTestFixtures.artifact()
        val variants = mutableListOf<ByteArray>()
        variants += mutate(artifact.raw, 0, 0x01)
        variants += mutateU16(artifact.raw, 8, 2)
        variants += mutateU16(artifact.raw, 10, 2)
        variants += mutateU16(artifact.raw, 12, 135)
        variants += mutateU16(artifact.raw, 14, 2)
        variants += mutateU32(artifact.raw, 20, 1)
        variants += mutateU32(artifact.raw, 24, 72)
        variants += mutateU32(artifact.raw, 28, 72)
        variants += mutateU32(artifact.raw, 32, 0)
        variants += mutateU32(artifact.raw, 36, artifact.raw.size + 1L)
        variants.forEach { raw ->
            assertRawFails(raw, StemLexiconTestFixtures.spec(raw, compressed = byteArrayOf(1)))
        }
    }

    @Test
    fun rejectsChecksumAndSourceIdentityMismatch() {
        val artifact = StemLexiconTestFixtures.artifact()
        val checksum = artifact.raw.copyOf().also {
            it[TatStemFormat.CHECKSUM_OFFSET] = (it[TatStemFormat.CHECKSUM_OFFSET].toInt() xor 1).toByte()
        }
        assertRawFails(checksum, artifact.spec)
        val dictionary = mutate(artifact.raw, TatStemFormat.DICTIONARY_SHA_OFFSET, 1)
        assertRawFails(dictionary, StemLexiconTestFixtures.spec(dictionary, compressed = byteArrayOf(1)))
        val apertium = mutate(artifact.raw, TatStemFormat.APERTIUM_SHA_OFFSET, 1)
        assertRawFails(apertium, StemLexiconTestFixtures.spec(apertium, compressed = byteArrayOf(1)))
    }

    @Test
    fun rejectsInvalidWordsAndOrdering() {
        val baseFlags = TatStemFormat.FLAG_NOUN or TatStemFormat.FLAG_BACK or
            TatStemFormat.FLAG_NOUN_CHAIN
        val words = listOf("а", "ббб", "Алма", "алма ")
        words.forEach { word ->
            val raw = StemLexiconTestFixtures.raw(listOf(StemTestEntry(word, baseFlags)))
            assertRawFails(raw, StemLexiconTestFixtures.spec(raw, compressed = byteArrayOf(1)))
        }
        val invalidUtf8 = StemLexiconTestFixtures.rawFromBytes(
            listOf(byteArrayOf(0xff.toByte(), 0xfe.toByte()) to baseFlags)
        )
        assertRawFails(invalidUtf8, StemLexiconTestFixtures.spec(invalidUtf8, compressed = byteArrayOf(1)))
        val unsorted = StemLexiconTestFixtures.raw(listOf(
            StemTestEntry("әни", StemLexiconTestFixtures.flags("әни")),
            StemTestEntry("алма", baseFlags),
        ))
        assertRawFails(unsorted, StemLexiconTestFixtures.spec(unsorted, compressed = byteArrayOf(1)))
        val duplicate = StemLexiconTestFixtures.raw(listOf(
            StemTestEntry("алма", baseFlags), StemTestEntry("алма", baseFlags),
        ))
        assertRawFails(duplicate, StemLexiconTestFixtures.spec(duplicate, compressed = byteArrayOf(1)))
    }

    @Test
    fun rejectsInvalidPartOfSpeechHarmonyAndContinuationFlags() {
        val noun = TatStemFormat.FLAG_NOUN
        val verb = TatStemFormat.FLAG_VERB
        val back = TatStemFormat.FLAG_BACK
        val front = TatStemFormat.FLAG_FRONT
        val nounChain = TatStemFormat.FLAG_NOUN_CHAIN
        val forceBack = TatStemFormat.FLAG_FORCE_BACK
        val variants = listOf(
            back,
            noun or verb,
            noun or back or front,
            noun or back or 0x80,
            verb or back or nounChain,
            noun or front,
            noun or back or forceBack,
        )
        variants.forEach { flags ->
            val raw = StemLexiconTestFixtures.raw(listOf(StemTestEntry("бала", flags)))
            assertRawFails(raw, StemLexiconTestFixtures.spec(raw, compressed = byteArrayOf(1)))
        }
    }

    @Test
    fun acceptsForcedBackAndFrontHarmony() {
        val entries = listOf(
            StemTestEntry("кондиционер", StemLexiconTestFixtures.flags(
                "кондиционер", forceBack = true,
            )),
            StemTestEntry("әби", StemLexiconTestFixtures.flags("әби")),
        )
        val artifact = StemLexiconTestFixtures.artifact(entries)
        assertEquals(2, validator.validateRaw(artifact.raw, artifact.spec).stemCount)
    }

    @Test
    fun rejectsNonMinimalVarints() {
        val artifact = StemLexiconTestFixtures.artifact(
            StemLexiconTestFixtures.defaultEntries(12), overlongPrefixes = true,
        )
        assertRawFails(artifact.raw, artifact.spec)
    }

    @Test
    fun rejectsMalformedTruncatedAndCorruptZlib() {
        val artifact = StemLexiconTestFixtures.artifact()
        val variants = listOf(
            byteArrayOf(1, 2, 3),
            artifact.compressed.copyOf(artifact.compressed.size - 2),
            artifact.compressed.copyOf().also {
                it[it.size / 2] = (it[it.size / 2].toInt() xor 0x40).toByte()
            },
            artifact.compressed + byteArrayOf(0),
            artifact.compressed + artifact.compressed,
        )
        variants.forEach { compressed ->
            val spec = artifact.spec.copy(
                expectedCompressedSize = compressed.size.toLong(),
                expectedCompressedSha256 = StemLexiconTestFixtures.sha256(compressed),
            )
            assertValidationFails { validator.inflateAsset(compressed.inputStream(), spec) }
        }
    }

    @Test
    fun rejectsOversizedCompressedAndInflatedInputs() {
        val artifact = StemLexiconTestFixtures.artifact()
        assertValidationFails {
            validator.inflateAsset((artifact.compressed + byteArrayOf(1)).inputStream(), artifact.spec)
        }
        val oversizedRaw = ByteArray(artifact.spec.maxRawSize.toInt() + 1)
        val compressed = DictionaryTestFixtures.compress(oversizedRaw)
        val spec = artifact.spec.copy(
            expectedCompressedSize = compressed.size.toLong(),
            expectedCompressedSha256 = StemLexiconTestFixtures.sha256(compressed),
        )
        assertValidationFails { validator.inflateAsset(compressed.inputStream(), spec) }
        assertRawFails(ByteArray(TatStemFormat.MAX_RAW_SIZE.toInt() + 1), artifact.spec)
    }

    private fun locateCommittedAsset(): File {
        var directory = File("").absoluteFile
        while (true) {
            val candidate = File(
                directory, "app/src/main/assets/dictionaries/tatar_stems_v1.tstem.zlib"
            )
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: error("committed stem asset not found")
        }
    }

    private fun mutate(input: ByteArray, offset: Int, mask: Int): ByteArray =
        StemLexiconTestFixtures.refreshEmbeddedChecksum(input.copyOf().also {
            it[offset] = (it[offset].toInt() xor mask).toByte()
        })

    private fun mutateU16(input: ByteArray, offset: Int, value: Int): ByteArray =
        StemLexiconTestFixtures.refreshEmbeddedChecksum(input.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value.toShort())
        })

    private fun mutateU32(input: ByteArray, offset: Int, value: Long): ByteArray =
        StemLexiconTestFixtures.refreshEmbeddedChecksum(input.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value.toInt())
        })

    private fun assertRawFails(raw: ByteArray, spec: StemLexiconArtifactSpec) {
        assertValidationFails { validator.validateRaw(raw, spec) }
    }

    private fun assertValidationFails(block: () -> Unit) {
        try {
            block()
            fail("expected stem lexicon validation failure")
        } catch (_: StemLexiconValidationException) {
        }
    }
}
