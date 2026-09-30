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

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.SeededFuzzHarness
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * Seeded fuzzing of [TdictValidator]. See [SeededFuzzHarness].
 *
 * The base image is a valid 40-word schema-2 fixture dictionary (5 front-coding blocks), so the
 * block index walk, the per-block entries and the frequency varints are all reachable. Shapes:
 * bit flips, truncations, header count/size inflation, random garbage, payload corruption with an
 * optional checksum refresh and re-derived spec pins (only these may validate), and the same
 * corruptions of the compressed stream, including a decompression-bomb probe.
 */
class TdictValidatorFuzzTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validator = TdictValidator()

    private val base = DictionaryTestFixtures.artifact(
        entries = SeededFuzzHarness.tatarWords(40).mapIndexed { index, word ->
            word to (index % 100 + 1).toLong()
        },
    )

    @Test
    fun baseImageValidates() {
        val validated = validator.validateRaw(writeBytes(base.raw), base.spec)
        assertEquals(40, validated.entryCount)
    }

    @Test
    fun fuzzBitFlipsNeverEscapeTheIntegrityGate() {
        val file = temporaryFolder.newFile("fuzz-bitflips.tdict")
        val random = Random(SEED_BIT_FLIPS)
        repeat(2_500) { iteration ->
            val mutated = SeededFuzzHarness.flipBits(random, base.raw)
            file.writeBytes(mutated.bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "bit-flips", SEED_BIT_FLIPS, iteration, mutated.detail,
                DictionaryValidationException::class.java, mustReject = true,
            ) {
                validator.validateRaw(file, specFor(mutated.bytes))
            }
        }
    }

    @Test
    fun fuzzTruncationsNeverValidate() {
        val file = temporaryFolder.newFile("fuzz-truncations.tdict")
        val random = Random(SEED_TRUNCATIONS)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.truncate(random, base.raw)
            file.writeBytes(mutated.bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "truncations", SEED_TRUNCATIONS, iteration, mutated.detail,
                DictionaryValidationException::class.java, mustReject = true,
            ) {
                validator.validateRaw(file, specFor(mutated.bytes))
            }
        }
    }

    @Test
    fun fuzzCountAndSizeFieldInflationStaysUnderTheCaps() {
        val file = temporaryFolder.newFile("fuzz-inflation.tdict")
        val random = Random(SEED_INFLATION)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.inflateFields(random, base.raw, U32_FIELD_OFFSETS)
            file.writeBytes(mutated.bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "inflation", SEED_INFLATION, iteration, mutated.detail,
                DictionaryValidationException::class.java, mustReject = true,
            ) {
                validator.validateRaw(file, specFor(mutated.bytes))
            }
        }
    }

    @Test
    fun fuzzRandomGarbageNeverValidates() {
        val file = temporaryFolder.newFile("fuzz-garbage.tdict")
        val random = Random(SEED_GARBAGE)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.garbage(random, 2_048)
            file.writeBytes(mutated.bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "garbage", SEED_GARBAGE, iteration, mutated.detail,
                DictionaryValidationException::class.java, mustReject = true,
            ) {
                validator.validateRaw(file, specFor(mutated.bytes))
            }
        }
    }

    @Test
    fun fuzzValidImageMutationReachesStructuralChecks() {
        val file = temporaryFolder.newFile("fuzz-mutation.tdict")
        val random = Random(SEED_VALID_IMAGE_MUTATION)
        var validatedCleanly = 0
        repeat(3_000) { iteration ->
            val refresh = random.nextBoolean()
            val deriveSpec = random.nextBoolean()
            val corrupted = SeededFuzzHarness.corruptBytes(
                random,
                base.raw,
                // The refresh zeroes and rewrites the checksum field: a corruption landing only
                // there would be wiped, recreating the valid base image.
                excluding = if (refresh) CHECKSUM_RANGE else null,
            )
            val bytes =
                if (refresh) DictionaryTestFixtures.refreshEmbeddedChecksum(corrupted.bytes) else corrupted.bytes
            val spec = if (deriveSpec) specFor(bytes) else base.spec
            file.writeBytes(bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "valid-image-mutation", SEED_VALID_IMAGE_MUTATION, iteration,
                corrupted.detail + " refresh=$refresh derive-spec=$deriveSpec",
                DictionaryValidationException::class.java, mustReject = !(refresh && deriveSpec),
            ) {
                validator.validateRaw(file, spec)
                validatedCleanly++
            }
        }
        // The shape exists to reach the structural checks; pin the exact deterministic count of
        // clean validations (corruptions landing only in bytes the structure leaves free) so a
        // future validator change that makes the fuzz shallower forces a review here.
        assertEquals(6, validatedCleanly)
    }

    @Test
    fun fuzzCompressedStreamRobustness() {
        val file = temporaryFolder.newFile("fuzz-inflate.tdict")
        val random = Random(SEED_COMPRESSED_STREAM)
        repeat(2_000) { iteration ->
            val mutated = when (random.nextInt(8)) {
                0, 1 -> SeededFuzzHarness.flipBits(random, base.compressed)
                2 -> SeededFuzzHarness.truncate(random, base.compressed)
                3 -> SeededFuzzHarness.garbage(random, 512)
                4 -> SeededFuzzHarness.appendGarbage(random, base.compressed)
                5 -> {
                    // Decompression-bomb probe: a small valid zlib stream whose inflated size
                    // crosses the raw cap; the cap must fire mid-inflation.
                    val oversized = ByteArray(base.spec.maxRawSize.toInt() + 1 + random.nextInt(1024))
                    SeededFuzzHarness.MutatedImage(
                        DictionaryTestFixtures.compress(oversized),
                        "bomb-raw=${oversized.size}",
                    )
                }
                else -> SeededFuzzHarness.corruptBytes(random, base.compressed)
            }
            val bomb = mutated.detail.startsWith("bomb-")
            // The compressed pins follow the mutated stream so the gate under test is the inflate
            // loop itself; an empty stream keeps the base pins (a zero pinned size would not
            // construct) and is rejected as a truncated zlib stream anyway.
            val spec = if (mutated.bytes.isEmpty()) {
                base.spec
            } else {
                base.spec.copy(
                    expectedCompressedSize = mutated.bytes.size.toLong(),
                    expectedCompressedSha256 = DictionaryTestFixtures.sha256(mutated.bytes),
                )
            }
            val output = ByteArrayOutputStream()
            var inflated = false
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "compressed-stream", SEED_COMPRESSED_STREAM, iteration, mutated.detail,
                DictionaryValidationException::class.java, mustReject = bomb,
            ) {
                validator.inflateAsset(mutated.bytes.inputStream(), output, spec)
                inflated = true
            }
            if (inflated) {
                // The real pipeline feeds inflated bytes to validateRaw; keep following it. A
                // mutation that decodes to the exact original image may legitimately validate.
                val raw = output.toByteArray()
                file.writeBytes(raw)
                SeededFuzzHarness.assertCleanOrValidationFailure(
                    "compressed-stream->raw", SEED_COMPRESSED_STREAM, iteration,
                    mutated.detail + " inflated=${raw.size}",
                    DictionaryValidationException::class.java, mustReject = false,
                ) {
                    validator.validateRaw(file, base.spec)
                }
            }
        }
    }

    /**
     * Re-derives the spec pins from a (possibly mutated) raw image so the fuzz reaches past the
     * identity gates into the structural checks. Images shorter than the header keep the base
     * pins — the validator rejects them on the header size before consulting any pin. A zeroed
     * entryCount field likewise keeps the base pin (the validator rejects a zero count outright,
     * and the spec constructor refuses to pin zero).
     */
    private fun specFor(raw: ByteArray): DictionaryArtifactSpec {
        if (raw.size < TdictFormat.HEADER_SIZE) return base.spec
        val entryCount = readU32(raw, ENTRY_COUNT_OFFSET)
        return base.spec.copy(
            expectedRawSize = raw.size.toLong(),
            expectedRawSha256 = DictionaryTestFixtures.sha256(raw),
            expectedEntryCount = if (entryCount == 0L) base.spec.expectedEntryCount else entryCount,
        )
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL

    private fun writeBytes(bytes: ByteArray) =
        temporaryFolder.newFile("fuzz-${System.nanoTime()}.tdict").also { it.writeBytes(bytes) }

    private companion object {
        const val SEED_BIT_FLIPS = 0x5EED_1001L
        const val SEED_TRUNCATIONS = 0x5EED_1002L
        const val SEED_INFLATION = 0x5EED_1003L
        const val SEED_GARBAGE = 0x5EED_1004L
        const val SEED_VALID_IMAGE_MUTATION = 0x5EED_1005L
        const val SEED_COMPRESSED_STREAM = 0x5EED_1006L

        /** The u32 count/size header fields, in header order (offsets per the format KDoc). */
        val U32_FIELD_OFFSETS = intArrayOf(16, 20, 24, 28, 32, 36)

        const val ENTRY_COUNT_OFFSET = 16

        /** The embedded-checksum field, excluded from corruptions that get refreshed. */
        val CHECKSUM_RANGE = TdictFormat.CHECKSUM_OFFSET until
            (TdictFormat.CHECKSUM_OFFSET + TdictFormat.CHECKSUM_SIZE)
    }
}
