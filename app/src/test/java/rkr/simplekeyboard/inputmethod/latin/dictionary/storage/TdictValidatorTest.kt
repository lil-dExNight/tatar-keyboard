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

        // 110 000 / 1 276 289 since 2026-09-20 (TT-SUGGESTIONS P2, docs/TT-SUGGESTIONS.md):
        // the dictionary grew by 9 052 admitted generated word forms and 645 further accepted
        // conversational words; nothing was displaced (was 100 000 / 1 162 870).
        assertEquals(110_000, validated.entryCount)
        assertEquals(1_276_289, validated.rawSize)
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
}
