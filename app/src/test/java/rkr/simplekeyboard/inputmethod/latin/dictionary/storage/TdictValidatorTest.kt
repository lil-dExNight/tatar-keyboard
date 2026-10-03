package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TdictValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validator = TdictValidator()

    @Test
    fun acceptsGoldenFixture() {
        val artifact = DictionaryTestFixtures.artifact()
        val rawFile = inflate(artifact)

        val validated = validator.validateRaw(rawFile, artifact.spec)

        assertEquals(3, validated.entryCount)
        assertEquals(DictionaryTestFixtures.sha256(artifact.raw), validated.rawSha256)
    }

    @Test
    fun acceptsCommittedTop100kAssetWithFrozenProvenance() {
        val asset = locateCommittedAsset().readBytes()
        val rawFile = temporaryFolder.newFile("top100k.tdict")
        rawFile.outputStream().use { output ->
            validator.inflateAsset(
                asset.inputStream(),
                output,
                DictionaryArtifactSpec.TATAR_TOP100K_V1,
            )
        }

        val validated = validator.validateRaw(
            rawFile,
            DictionaryArtifactSpec.TATAR_TOP100K_V1,
        )

        // Pins of the committed dictionary; re-pin when the asset is rebuilt.
        assertEquals(110_000, validated.entryCount)
        assertEquals(1_311_438, validated.rawSize)
    }

    @Test
    fun rejectsMalformedTruncatedAndCorruptZlib() {
        val artifact = DictionaryTestFixtures.artifact()
        val variants = listOf(
            byteArrayOf(1, 2, 3),
            artifact.compressed.copyOf(artifact.compressed.size - 2),
            artifact.compressed.copyOf().also { it[it.lastIndex / 2] =
                (it[it.lastIndex / 2].toInt() xor 0x40).toByte() },
        )
        variants.forEachIndexed { index, bytes ->
            val spec = specForCompressed(artifact, bytes, generation = 10 + index)
            assertValidationFails { inflate(bytes, spec) }
        }
    }

    @Test
    fun rejectsTrailingAndConcatenatedZlib() {
        val artifact = DictionaryTestFixtures.artifact()
        val variants = listOf(
            artifact.compressed + byteArrayOf(0),
            artifact.compressed + artifact.compressed,
        )
        variants.forEachIndexed { index, bytes ->
            assertValidationFails {
                inflate(bytes, specForCompressed(artifact, bytes, 20 + index))
            }
        }
    }

    @Test
    fun rejectsPresetDictionaryZlib() {
        val dictionary = "preset dictionary".toByteArray()
        val artifact = DictionaryTestFixtures.artifact(dictionary = dictionary)

        assertValidationFails { inflate(artifact.compressed, artifact.spec) }
    }

    @Test
    fun rejectsCompressedAndRawCaps() {
        val artifact = DictionaryTestFixtures.artifact()
        val oversizedCompressed = ByteArray(128) { it.toByte() }
        val compressedSpec = DictionaryArtifactSpec(
            family = "tatar_top100k",
            languageTag = "tt_RU",
            storageDirectoryName = "dictionaries",
            generation = 30,
            assetPath = "oversized",
            expectedCompressedSize = 64,
            expectedCompressedSha256 = DictionaryTestFixtures.sha256(oversizedCompressed),
            expectedRawSize = artifact.raw.size.toLong(),
            expectedRawSha256 = DictionaryTestFixtures.sha256(artifact.raw),
            expectedEntryCount = 3,
            maxCompressedSize = 64,
        )
        assertValidationFails { inflate(oversizedCompressed, compressedSpec) }

        val largeRaw = ByteArray(artifact.raw.size + 1)
        val compressed = DictionaryTestFixtures.compress(largeRaw)
        val rawSpec = DictionaryArtifactSpec(
            family = "tatar_top100k",
            languageTag = "tt_RU",
            storageDirectoryName = "dictionaries",
            generation = 31,
            assetPath = "raw-oversized",
            expectedCompressedSize = compressed.size.toLong(),
            expectedCompressedSha256 = DictionaryTestFixtures.sha256(compressed),
            expectedRawSize = artifact.raw.size.toLong(),
            expectedRawSha256 = DictionaryTestFixtures.sha256(artifact.raw),
            expectedEntryCount = 3,
            maxRawSize = artifact.raw.size.toLong(),
        )
        assertValidationFails { inflate(compressed, rawSpec) }
    }

    @Test
    fun rejectsHeaderSchemaVersionAndLayoutCorruption() {
        val artifact = DictionaryTestFixtures.artifact()
        val variants = listOf(
            mutateAndRehash(artifact.raw, 0, 0),
            mutateU16AndRehash(artifact.raw, 8, 3),
            mutateU16AndRehash(artifact.raw, 10, 2),
            mutateU16AndRehash(artifact.raw, 12, 71),
            mutateU16AndRehash(artifact.raw, 14, 2),
            mutateU32AndRehash(artifact.raw, 20, 73),
            mutateU32AndRehash(artifact.raw, 36, artifact.raw.size + 1),
        )
        variants.forEachIndexed { index, raw ->
            assertRawFails(raw, 40 + index)
        }
    }

    @Test
    fun rejectsZeroCountAndOverflowingSectionArithmetic() {
        val artifact = DictionaryTestFixtures.artifact()
        val zeroCount = mutateU32AndRehash(artifact.raw, 16, 0)
        assertValidationFails {
            validator.validateRaw(
                writeRaw(zeroCount),
                artifact.spec.copy(
                    expectedRawSha256 = DictionaryTestFixtures.sha256(zeroCount),
                ),
            )
        }

        val hugeCount = mutateU32AndRehash(artifact.raw, 16, -1)
        assertValidationFails {
            validator.validateRaw(
                writeRaw(hugeCount),
                artifact.spec.copy(
                    expectedRawSha256 = DictionaryTestFixtures.sha256(hugeCount),
                    expectedEntryCount = 0xffff_ffffL,
                ),
            )
        }
    }

    @Test
    fun rejectsChecksumAndReleaseIdentityMismatch() {
        val artifact = DictionaryTestFixtures.artifact()
        val corrupt = artifact.raw.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        val file = writeRaw(corrupt)

        assertValidationFails { validator.validateRaw(file, artifact.spec) }

        val wrongIdentity = artifact.spec.copy(expectedRawSha256 = "0".repeat(64))
        assertValidationFails { validator.validateRaw(writeRaw(artifact.raw), wrongIdentity) }
    }

    @Test
    fun rejectsInvalidOffsetsAndZeroFrequency() {
        val artifact = DictionaryTestFixtures.artifact()
        val variants = listOf(
            // The first block offset must equal the blocks-section offset.
            mutateU32AndRehash(artifact.raw, 72, 1),
            // The first word of a block with a zero u8 length.
            mutateU32AndRehash(artifact.raw, 76, 0),
            // The last byte of the fixture is the last frequency's single-byte varint.
            mutateAndRehash(artifact.raw, artifact.raw.size - 1, 0),
        )
        variants.forEachIndexed { index, raw -> assertRawFails(raw, 50 + index) }
    }

    @Test
    fun rejectsInvalidUtf8AlphabetCaseCanonicalOrderAndDuplicates() {
        val artifact = DictionaryTestFixtures.artifact()
        val blocksOffset = ByteBuffer.wrap(artifact.raw, 28, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        val malformedUtf8 = DictionaryTestFixtures.refreshEmbeddedChecksum(
            artifact.raw.copyOf().also { it[blocksOffset + 1] = 0xff.toByte() },
        )
        val rawVariants = listOf(
            malformedUtf8,
            DictionaryTestFixtures.raw(listOf("abc" to 1)),
            DictionaryTestFixtures.raw(listOf("АБ" to 1)),
            DictionaryTestFixtures.raw(listOf("е\u0308" to 1)),
            DictionaryTestFixtures.raw(listOf("әби" to 1, "аба" to 2)),
            DictionaryTestFixtures.raw(listOf("аба" to 1, "аба" to 2)),
            DictionaryTestFixtures.raw(listOf("а".repeat(65) to 1)),
        )
        rawVariants.forEachIndexed { index, raw -> assertRawFails(raw, 60 + index) }
    }

    @Test
    fun rejectsEveryNonCanonicalWordEncoding() {
        val a = "а".toByteArray(Charsets.UTF_8)
        val cases = listOf(
            "overlong 3-byte а" to bytes(0xE0, 0x90, 0xB0),
            "overlong 2-byte lead C1" to bytes(0xC1, 0xB0),
            "overlong 2-byte lead C0" to bytes(0xC0, 0x80),
            "lone continuation byte" to bytes(0xB0),
            "continuation after a letter" to a + bytes(0xB0),
            "truncated 2-byte sequence" to bytes(0xD0),
            "truncated after a letter" to a + bytes(0xD3),
            "lead without continuation" to bytes(0xD0, 0x41),
            "surrogate" to bytes(0xED, 0xA0, 0x80),
            "uppercase А" to "А".toByteArray(Charsets.UTF_8),
            "uppercase Ә" to "Ә".toByteArray(Charsets.UTF_8),
            "Latin a" to "a".toByteArray(Charsets.UTF_8),
            "Latin a after a letter" to a + "a".toByteArray(Charsets.UTF_8),
            "NFD й" to "и\u0306".toByteArray(Charsets.UTF_8),
            "combining mark alone" to "\u0306".toByteArray(Charsets.UTF_8),
            "non-alphabet Cyrillic і" to "і".toByteArray(Charsets.UTF_8),
            "non-alphabet U+0400" to "\u0400".toByteArray(Charsets.UTF_8),
            "3-byte character" to "€".toByteArray(Charsets.UTF_8),
            "4-byte emoji" to "\uD83D\uDE00".toByteArray(Charsets.UTF_8),
            "invalid lead F8" to bytes(0xF8, 0x80, 0x80, 0x80),
            "byte FF" to bytes(0xFF),
            "129 bytes" to "а".repeat(64).toByteArray(Charsets.UTF_8) + bytes(0x61),
            "65 letters" to "а".repeat(65).toByteArray(Charsets.UTF_8),
        )
        cases.forEachIndexed { index, (name, word) ->
            val raw = DictionaryTestFixtures.rawFromBytes(listOf(word to 1L))
            val spec = DictionaryTestFixtures.spec(100 + index, raw)
            try {
                validator.validateRaw(writeRaw(raw), spec)
                fail("accepted: $name")
            } catch (expected: DictionaryValidationException) {
                assertTrue(name, expected.message.orEmpty().isNotEmpty())
            }
        }
    }

    @Test
    fun acceptsEveryAlphabetLetterAndTheLongestWord() {
        val letters = TATAR_ALPHABET.codePoints().toArray().map { String(Character.toChars(it)) }
        assertEquals(39, letters.size)
        val words = (letters + TATAR_ALPHABET + "я".repeat(64))
            .distinct()
            .sortedWith { x, y -> compareBytes(x.toByteArray(), y.toByteArray()) }
        val raw = DictionaryTestFixtures.raw(words.map { it to 1L })
        val spec = DictionaryTestFixtures.spec(200, raw)

        assertEquals(words.size.toLong(), validator.validateRaw(writeRaw(raw), spec).entryCount)
    }

    @Test
    fun everyAlphabetLetterAndPairIsNfcLowercase() {
        val letters = TATAR_ALPHABET.codePoints().toArray().map { String(Character.toChars(it)) }
        for (first in letters) {
            assertEquals(2, first.toByteArray(Charsets.UTF_8).size)
            for (candidate in listOf(first) + letters.map { first + it }) {
                assertEquals(
                    candidate,
                    java.text.Normalizer.normalize(candidate, java.text.Normalizer.Form.NFC),
                )
                assertEquals(candidate, candidate.lowercase(java.util.Locale.ROOT))
            }
        }
    }

    @Test
    fun rejectsTrailingRawBytesAndWrongExpectedCount() {
        val artifact = DictionaryTestFixtures.artifact()
        val withTrailing = DictionaryTestFixtures.refreshEmbeddedChecksum(
            artifact.raw + byteArrayOf(0),
        )
        assertRawFails(withTrailing, 70)

        assertValidationFails {
            validator.validateRaw(
                writeRaw(artifact.raw),
                artifact.spec.copy(expectedEntryCount = 4),
            )
        }
    }

    @Test
    fun validatesAllFrequenciesAsUnsignedU32() {
        val raw = DictionaryTestFixtures.raw(listOf("аба" to 0xffff_ffffL))
        val artifact = TestArtifact(
            DictionaryTestFixtures.spec(80, raw),
            raw,
            DictionaryTestFixtures.compress(raw),
        )

        assertEquals(1, validator.validateRaw(writeRaw(raw), artifact.spec).entryCount)
    }

    private fun inflate(artifact: TestArtifact): File = inflate(artifact.compressed, artifact.spec)

    private fun inflate(bytes: ByteArray, spec: DictionaryArtifactSpec): File {
        val file = temporaryFolder.newFile("inflated-${System.nanoTime()}.tdict")
        file.outputStream().use { validator.inflateAsset(bytes.inputStream(), it, spec) }
        return file
    }

    private fun assertRawFails(raw: ByteArray, generation: Int) {
        val compressed = DictionaryTestFixtures.compress(raw)
        val spec = DictionaryTestFixtures.spec(generation, raw, compressed)
        assertValidationFails { validator.validateRaw(writeRaw(raw), spec) }
    }

    private fun specForCompressed(
        artifact: TestArtifact,
        compressed: ByteArray,
        generation: Int,
    ) = artifact.spec.copy(
        generation = generation,
        expectedCompressedSize = compressed.size.toLong(),
        expectedCompressedSha256 = DictionaryTestFixtures.sha256(compressed),
    )

    private fun writeRaw(bytes: ByteArray): File =
        temporaryFolder.newFile("raw-${System.nanoTime()}.tdict").also { it.writeBytes(bytes) }

    private fun mutateAndRehash(raw: ByteArray, offset: Int, value: Int): ByteArray =
        DictionaryTestFixtures.refreshEmbeddedChecksum(raw.copyOf().also { it[offset] = value.toByte() })

    private fun mutateU16AndRehash(raw: ByteArray, offset: Int, value: Int): ByteArray =
        DictionaryTestFixtures.refreshEmbeddedChecksum(
            raw.copyOf().also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value.toShort())
            },
        )

    private fun mutateU32AndRehash(raw: ByteArray, offset: Int, value: Int): ByteArray =
        DictionaryTestFixtures.refreshEmbeddedChecksum(
            raw.copyOf().also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            },
        )

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun compareBytes(first: ByteArray, second: ByteArray): Int {
        for (index in 0 until minOf(first.size, second.size)) {
            val difference = (first[index].toInt() and 0xff) - (second[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return first.size - second.size
    }

    private fun locateCommittedAsset(): File {
        val candidates = listOf(
            File("src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib"),
            File("app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("cannot locate committed dictionary asset from ${File(".").absolutePath}")
    }

    private fun assertValidationFails(block: () -> Unit) {
        try {
            block()
            fail("expected DictionaryValidationException")
        } catch (expected: DictionaryValidationException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    private companion object {
        const val TATAR_ALPHABET = "аәбвгдеёжҗзийклмнңоөпрстуүфхһцчшщъыьэюя"
    }
}
