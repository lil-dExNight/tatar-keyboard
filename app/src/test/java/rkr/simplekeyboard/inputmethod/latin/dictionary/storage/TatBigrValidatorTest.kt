package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The schema-3 (SIZE-2) sibling of the E5b validator contract: one test per corruption class —
 * чужой magic, schema ≠ 3, неверная версия/размер заголовка/алгоритм checksum, несовпадение
 * checksum, сырого SHA-256 или SHA-256 СЛОВАРЯ против пина (связка таблицы со словарём),
 * неканоническая арифметика секций, ненулевые reserved-байты, нулевой/переполненный счётчик
 * голов, пустой диапазон преемников, нулевая дельта индекса головы, немонотонные записи
 * блочного индекса, сумма u8-счётчиков ≠ pairCount, хвостовые байты — плюс zlib-классы
 * ([TatBigrValidator.inflateAsset]), зеркально [TdictValidatorTest].
 *
 * Класса «id ≥ размера словаря» здесь нет: у schema 3 нет собственного словаря преемников —
 * верхнюю границу индексов проверяет `TatBigrPrefixIndex.open`, у которого словарь есть
 * (см. KDoc [TatBigrValidator]).
 */
class TatBigrValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validator = TatBigrValidator()

    @Test
    fun acceptsGoldenFixture() {
        val artifact = BigramTestFixtures.artifact()
        val rawFile = inflate(artifact)

        val validated = validator.validateRaw(rawFile, artifact.spec)

        assertEquals(2, validated.headCount)
        assertEquals(3, validated.pairCount)
        assertEquals(2, validated.successVocabularyCount)
        assertEquals(BigramTestFixtures.sha256(artifact.raw), validated.rawSha256)
    }

    @Test
    fun acceptsCommittedTatarBigramAssetWithFrozenProvenance() {
        val validated = validateCommitted(
            "tatar_bigrams_v1.tatbigr.zlib",
            BigramArtifactSpec.TATAR_BIGRAMS_V1,
        )

        // Schema 3 since 2026-09-01 (SIZE-2, docs/SIZE-SCHEMA3.md): content carried over from the
        // schema-2 asset verbatim — 10 204 heads and 40 734 pairs are the 2026-08-31
        // conversational repack's (docs/CORPUS-CONVERSATIONAL-TT.md); only the encoding changed.
        // Repacked 2026-09-20 (TT-SUGGESTIONS P2) against the 110 000-entry dictionary: the head
        // set is identical (generated forms enter far below the H = 10 132 cutoff) and the same
        // three -гәнчә converbs are dropped pairless; ten pairs whose successor was outside the
        // old dictionary now count (бервакытта → бернәрсәдә, …), so the pair total moves
        // 40 734 -> 40 735.
        assertEquals(10_204, validated.headCount)
        assertEquals(134_938, validated.rawSize)
        assertEquals(40_735, validated.pairCount)
    }

    /**
     * The ru table shipped in 1.8.x with no test against its real committed bytes while the tt
     * table had one; both are pinned since the K = 4 repack.
     *
     * Schema 3 since 2026-09-01 (SIZE-2): the 9 998 heads / 39 949 pairs of the 2026-08-31
     * conversational repack (docs/CORPUS-CONVERSATIONAL-RU.md), cross-referenced into the
     * shipped Russian dictionary instead of carrying own word blobs.
     */
    @Test
    fun acceptsCommittedRussianBigramAssetWithFrozenProvenance() {
        val validated = validateCommitted(
            "russian_bigrams_v1.tatbigr.zlib",
            BigramArtifactSpec.RUSSIAN_BIGRAMS_V1,
        )

        assertEquals(9_998, validated.headCount)
        assertEquals(131_662, validated.rawSize)
        assertEquals(39_949, validated.pairCount)
    }

    @Test
    fun rejectsMalformedTruncatedAndCorruptZlib() {
        val artifact = BigramTestFixtures.artifact()
        val variants = listOf(
            byteArrayOf(1, 2, 3),
            artifact.compressed.copyOf(artifact.compressed.size - 2),
            artifact.compressed.copyOf().also {
                it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 0x40).toByte()
            },
        )
        variants.forEachIndexed { index, bytes ->
            val spec = specForCompressed(artifact, bytes, generation = 10 + index)
            assertValidationFails { inflate(bytes, spec) }
        }
    }

    @Test
    fun rejectsTrailingAndConcatenatedZlib() {
        val artifact = BigramTestFixtures.artifact()
        val variants = listOf(
            artifact.compressed + byteArrayOf(0),
            artifact.compressed + artifact.compressed,
        )
        variants.forEachIndexed { index, bytes ->
            assertValidationFails { inflate(bytes, specForCompressed(artifact, bytes, 20 + index)) }
        }
    }

    @Test
    fun rejectsPresetDictionaryZlib() {
        val dictionary = "preset dictionary".toByteArray()
        val artifact = BigramTestFixtures.artifact()
        val presetCompressed = BigramTestFixtures.compress(artifact.raw, dictionary)

        assertValidationFails {
            inflate(presetCompressed, specForCompressed(artifact, presetCompressed, 25))
        }
    }

    @Test
    fun rejectsCompressedAndRawCaps() {
        val artifact = BigramTestFixtures.artifact()
        val oversizedCompressed = ByteArray(128) { it.toByte() }
        val compressedSpec = BigramArtifactSpec(
            family = "tatar_bigrams",
            generation = 30,
            fileLanguageTag = "tt",
            subtypeId = "tt_RU",
            storageDirectoryName = "bigrams",
            assetPath = "oversized",
            expectedCompressedSize = 64,
            expectedCompressedSha256 = BigramTestFixtures.sha256(oversizedCompressed),
            expectedRawSize = artifact.raw.size.toLong(),
            expectedRawSha256 = BigramTestFixtures.sha256(artifact.raw),
            expectedDictionaryRawSha256 = BigramTestFixtures.DEFAULT_DICTIONARY_SHA,
            expectedHeadCount = 2,
            maxCompressedSize = 64,
        )
        assertValidationFails { inflate(oversizedCompressed, compressedSpec) }

        val largeRaw = ByteArray(artifact.raw.size + 1)
        val compressed = BigramTestFixtures.compress(largeRaw)
        val rawSpec = BigramArtifactSpec(
            family = "tatar_bigrams",
            generation = 31,
            fileLanguageTag = "tt",
            subtypeId = "tt_RU",
            storageDirectoryName = "bigrams",
            assetPath = "raw-oversized",
            expectedCompressedSize = compressed.size.toLong(),
            expectedCompressedSha256 = BigramTestFixtures.sha256(compressed),
            expectedRawSize = artifact.raw.size.toLong(),
            expectedRawSha256 = BigramTestFixtures.sha256(artifact.raw),
            expectedDictionaryRawSha256 = BigramTestFixtures.DEFAULT_DICTIONARY_SHA,
            expectedHeadCount = 2,
            maxRawSize = artifact.raw.size.toLong(),
        )
        assertValidationFails { inflate(compressed, rawSpec) }
    }

    @Test
    fun rejectsWrongMagic() {
        val artifact = BigramTestFixtures.artifact()
        val corrupted = BigramTestFixtures.refreshEmbeddedChecksum(
            artifact.raw.copyOf().also { it[0] = 'X'.code.toByte() },
        )
        assertRawFails(corrupted, 40)
    }

    @Test
    fun rejectsWrongSchemaId() {
        assertRawFails(mutateU16AndRehash(BigramTestFixtures.artifact().raw, 8, 2), 41)
    }

    @Test
    fun rejectsWrongFormatVersion() {
        assertRawFails(mutateU16AndRehash(BigramTestFixtures.artifact().raw, 10, 2), 42)
    }

    @Test
    fun rejectsWrongHeaderSize() {
        assertRawFails(mutateU16AndRehash(BigramTestFixtures.artifact().raw, 12, 96), 43)
    }

    @Test
    fun rejectsWrongChecksumAlgorithm() {
        assertRawFails(mutateU16AndRehash(BigramTestFixtures.artifact().raw, 14, 2), 44)
    }

    @Test
    fun rejectsChecksumMismatch() {
        val artifact = BigramTestFixtures.artifact()
        val corrupt = artifact.raw.copyOf().also { it[96] = (it[96].toInt() xor 1).toByte() }
        assertValidationFails { validator.validateRaw(writeRaw(corrupt), artifact.spec) }
    }

    @Test
    fun rejectsIdentityMismatchAgainstSpec() {
        val artifact = BigramTestFixtures.artifact()
        val wrongIdentity = artifact.spec.copy(expectedRawSha256 = "0".repeat(64))
        assertValidationFails { validator.validateRaw(writeRaw(artifact.raw), wrongIdentity) }
    }

    @Test
    fun rejectsDictionaryLinkMismatchAgainstSpec() {
        // The schema-3 link: the header names the dictionary the table was packed against, and
        // the spec pins exactly that dictionary — a table dragged next to another dictionary is
        // rejected before a single index is read.
        val artifact = BigramTestFixtures.artifact()
        val wrongDictionary = artifact.spec.copy(expectedDictionaryRawSha256 = "0".repeat(64))
        assertValidationFails { validator.validateRaw(writeRaw(artifact.raw), wrongDictionary) }
    }

    @Test
    fun rejectsNonZeroReservedHeaderBytes() {
        val artifact = BigramTestFixtures.artifact()
        val corrupted = BigramTestFixtures.refreshEmbeddedChecksum(
            artifact.raw.copyOf().also { it[88] = 1 },
        )
        assertRawFails(corrupted, 45)
    }

    @Test
    fun rejectsNoncanonicalSectionArithmetic() {
        val artifact = BigramTestFixtures.artifact()
        // Offsets, in header order: three counts at 16/20/24, five section offsets at
        // 28/32/40/44, two stream sizes at 36/48, file size at 52.
        val variants = listOf(
            mutateU32AndRehash(artifact.raw, 28, 129),
            mutateU32AndRehash(artifact.raw, 32, 999),
            mutateU32AndRehash(artifact.raw, 40, 999),
            mutateU32AndRehash(artifact.raw, 44, 999),
            mutateU32AndRehash(artifact.raw, 52, artifact.raw.size + 1),
        )
        variants.forEachIndexed { index, raw -> assertRawFails(raw, 50 + index) }
    }

    @Test
    fun rejectsZeroAndOverflowingHeadCount() {
        val artifact = BigramTestFixtures.artifact()
        val zeroCount = mutateU32AndRehash(artifact.raw, 16, 0)
        assertValidationFails {
            validator.validateRaw(
                writeRaw(zeroCount),
                artifact.spec.copy(expectedRawSha256 = BigramTestFixtures.sha256(zeroCount)),
            )
        }

        val hugeCount = mutateU32AndRehash(artifact.raw, 16, -1)
        assertValidationFails {
            validator.validateRaw(
                writeRaw(hugeCount),
                artifact.spec.copy(
                    expectedRawSha256 = BigramTestFixtures.sha256(hugeCount),
                    expectedHeadCount = 0xffff_ffffL,
                ),
            )
        }
    }

    @Test
    fun rejectsEmptySuccessRange() {
        // A head with no successes at all — the exact shape the Python generator refuses to ever
        // produce, and the validator must refuse to ever accept (schema 3 stores it as a 0 byte
        // in the u8 count array).
        val raw = BigramTestFixtures.raw(listOf("аб" to listOf("аба"), "аба" to emptyList()))
        assertRawFails(raw, 65)
    }

    @Test
    fun rejectsZeroHeadIndexDelta() {
        val artifact = BigramTestFixtures.artifact()
        val headDeltasOffset = ByteBuffer.wrap(artifact.raw, 32, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val corrupted = BigramTestFixtures.refreshEmbeddedChecksum(
            artifact.raw.copyOf().also { it[headDeltasOffset] = 0 },
        )
        assertRawFails(corrupted, 66)
    }

    @Test
    fun rejectsSuccessCountsNotAddingUpToPairCount() {
        val artifact = BigramTestFixtures.artifact()
        val countsOffset = ByteBuffer.wrap(artifact.raw, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val corrupted = BigramTestFixtures.refreshEmbeddedChecksum(
            artifact.raw.copyOf().also { it[countsOffset] = 3 }, // 3 + 1 = 4 ≠ pairCount 3
        )
        assertRawFails(corrupted, 67)
    }

    @Test
    fun rejectsNonMonotonicBlockFirstIndices() {
        // 65 heads → two blocks; pushing the second block's first index down to the first
        // block's breaks the strict increase the binary search relies on.
        val heads = (0 until 65).map { index -> "а%03d".format(index) to listOf("б") }
        val raw = BigramTestFixtures.raw(heads)
        val corrupted = BigramTestFixtures.refreshEmbeddedChecksum(
            raw.copyOf().also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(128 + 12, 0)
            },
        )
        assertRawFails(corrupted, 68)
    }

    @Test
    fun rejectsTrailingRawBytesAndTruncation() {
        val artifact = BigramTestFixtures.artifact()
        val withTrailing = BigramTestFixtures.refreshEmbeddedChecksum(artifact.raw + byteArrayOf(0))
        assertRawFails(withTrailing, 80)

        assertValidationFails {
            validator.validateRaw(
                writeRaw(artifact.raw.copyOf(artifact.raw.size - 1)),
                artifact.spec,
            )
        }
    }

    private fun inflate(artifact: TestBigramArtifact): File = inflate(artifact.compressed, artifact.spec)

    private fun inflate(bytes: ByteArray, spec: BigramArtifactSpec): File {
        val file = temporaryFolder.newFile("inflated-${System.nanoTime()}.tatbigr")
        file.outputStream().use { validator.inflateAsset(bytes.inputStream(), it, spec) }
        return file
    }

    private fun assertRawFails(raw: ByteArray, generation: Int) {
        val compressed = BigramTestFixtures.compress(raw)
        val spec = BigramTestFixtures.spec(generation, "tt", raw, compressed)
        assertValidationFails { validator.validateRaw(writeRaw(raw), spec) }
    }

    private fun specForCompressed(
        artifact: TestBigramArtifact,
        compressed: ByteArray,
        generation: Int,
    ) = artifact.spec.copy(
        generation = generation,
        expectedCompressedSize = compressed.size.toLong(),
        expectedCompressedSha256 = BigramTestFixtures.sha256(compressed),
    )

    private fun writeRaw(bytes: ByteArray): File =
        temporaryFolder.newFile("raw-${System.nanoTime()}.tatbigr").also { it.writeBytes(bytes) }

    private fun mutateU16AndRehash(raw: ByteArray, offset: Int, value: Int): ByteArray =
        BigramTestFixtures.refreshEmbeddedChecksum(
            raw.copyOf().also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value.toShort())
            },
        )

    private fun mutateU32AndRehash(raw: ByteArray, offset: Int, value: Int): ByteArray =
        BigramTestFixtures.refreshEmbeddedChecksum(
            raw.copyOf().also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            },
        )

    private fun validateCommitted(
        assetFileName: String,
        spec: BigramArtifactSpec,
    ): ValidatedBigramTable {
        val asset = locateCommittedAsset(assetFileName).readBytes()
        val rawFile = temporaryFolder.newFile("${spec.fileLanguageTag}.tatbigr")
        rawFile.outputStream().use { output ->
            validator.inflateAsset(asset.inputStream(), output, spec)
        }
        return validator.validateRaw(rawFile, spec)
    }

    private fun locateCommittedAsset(assetFileName: String): File {
        val candidates = listOf(
            File("src/main/assets/bigrams/$assetFileName"),
            File("app/src/main/assets/bigrams/$assetFileName"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("cannot locate $assetFileName from ${File(".").absolutePath}")
    }

    private fun assertValidationFails(block: () -> Unit) {
        try {
            block()
            fail("expected BigramValidationException")
        } catch (expected: BigramValidationException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }
}
