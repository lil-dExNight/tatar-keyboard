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
 * Seeded deterministic fuzzing of [TatBigrValidator] (TATBIGR schema 3) — S5 of
 * `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md`, the mirror of [TdictValidatorFuzzTest] for the
 * bigram table. Zero dependencies: mutations come from a seeded [java.util.Random] via
 * [SeededFuzzHarness]; a fixed seed per shape makes every run byte-identical, and on a property
 * violation the failure message names shape + seed + iteration + the touched offsets, which
 * reproduce the exact input.
 *
 * The base image is a fixture-built valid table of 70 heads (two head blocks) with two successes
 * each, cross-referenced into the fixture dictionary, so both the block-index walk and the
 * per-block delta/count/success streams are reachable by the mutations.
 *
 * Shapes (all against [TatBigrValidator.validateRaw] unless noted):
 *
 *  * **bit flips** (2 500): 1–4 single-bit flips, no checksum refresh — the integrity gates must
 *    reject from every position;
 *  * **truncations** (2 000): strict prefixes at random lengths — the header's declared file
 *    size can never match, so all must reject;
 *  * **count/size-field inflation** (2 000): the ten u32 header fields (headCount, pairCount,
 *    blockCount, the four section offsets, the two stream sizes, declaredFileSize) set to huge
 *    values — the caps and the canonical-layout check must reject BEFORE any count-sized walk or
 *    allocation; the file itself stays ~400 bytes, so an allocation attempt per an inflated count
 *    would blow up and be caught;
 *  * **random garbage** (2 000): random bytes of random length;
 *  * **valid-image mutation** (3 000): payload corruption, then half the time the embedded
 *    SHA-256 is refreshed (via [BigramTestFixtures.refreshEmbeddedChecksum]) and/or the spec pins
 *    (raw size/SHA, head count, linked-dictionary SHA) are re-derived from the mutated image —
 *    this reaches PAST the checksum gate into the structural checks. Only refresh + derived-spec
 *    mutations may validate cleanly; every other combination must reject;
 *  * **compressed stream** (2 000): the same corruption shapes against
 *    [TatBigrValidator.inflateAsset], including a decompression-bomb probe; successful inflations
 *    are fed on to [TatBigrValidator.validateRaw] exactly like the real pipeline does.
 *
 * Properties asserted on EVERY input (see [SeededFuzzHarness.assertCleanOrValidationFailure]):
 * clean validation or [BigramValidationException], never any other throwable, and — for the
 * shapes that cannot produce a valid image — never a clean validation (fail-closed).
 */
class TatBigrValidatorFuzzTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validator = TatBigrValidator()

    // 70 heads → 2 head blocks (HEAD_BLOCK_SIZE = 64), so the multi-block paths of the
    // structural walk (block records, per-block stream boundaries) are exercised.
    private val base: TestBigramArtifact = run {
        val words = SeededFuzzHarness.tatarWords(100)
        BigramTestFixtures.artifact(
            headsToSuccesses = words.take(70).mapIndexed { index, head ->
                head to listOf(words[(index + 1) % words.size], words[(index + 37) % words.size])
            },
        )
    }

    @Test
    fun baseImageValidates() {
        val validated = validator.validateRaw(writeBytes(base.raw), base.spec)
        assertEquals(70, validated.headCount)
        assertEquals(140, validated.pairCount)
    }

    @Test
    fun fuzzBitFlipsNeverEscapeTheIntegrityGate() {
        val file = temporaryFolder.newFile("fuzz-bitflips.tatbigr")
        val random = Random(SEED_BIT_FLIPS)
        repeat(2_500) { iteration ->
            val mutated = SeededFuzzHarness.flipBits(random, base.raw)
            file.writeBytes(mutated.bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "bit-flips", SEED_BIT_FLIPS, iteration, mutated.detail,
                BigramValidationException::class.java, mustReject = true,
            ) {
                validator.validateRaw(file, specFor(mutated.bytes))
            }
        }
    }

    @Test
    fun fuzzTruncationsNeverValidate() {
        val file = temporaryFolder.newFile("fuzz-truncations.tatbigr")
        val random = Random(SEED_TRUNCATIONS)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.truncate(random, base.raw)
            file.writeBytes(mutated.bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "truncations", SEED_TRUNCATIONS, iteration, mutated.detail,
                BigramValidationException::class.java, mustReject = true,
            ) {
                validator.validateRaw(file, specFor(mutated.bytes))
            }
        }
    }

    @Test
    fun fuzzCountAndSizeFieldInflationStaysUnderTheCaps() {
        val file = temporaryFolder.newFile("fuzz-inflation.tatbigr")
        val random = Random(SEED_INFLATION)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.inflateFields(random, base.raw, U32_FIELD_OFFSETS)
            file.writeBytes(mutated.bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "inflation", SEED_INFLATION, iteration, mutated.detail,
                BigramValidationException::class.java, mustReject = true,
            ) {
                validator.validateRaw(file, specFor(mutated.bytes))
            }
        }
    }

    @Test
    fun fuzzRandomGarbageNeverValidates() {
        val file = temporaryFolder.newFile("fuzz-garbage.tatbigr")
        val random = Random(SEED_GARBAGE)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.garbage(random, 2_048)
            file.writeBytes(mutated.bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "garbage", SEED_GARBAGE, iteration, mutated.detail,
                BigramValidationException::class.java, mustReject = true,
            ) {
                validator.validateRaw(file, specFor(mutated.bytes))
            }
        }
    }

    @Test
    fun fuzzValidImageMutationReachesStructuralChecks() {
        val file = temporaryFolder.newFile("fuzz-mutation.tatbigr")
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
                if (refresh) BigramTestFixtures.refreshEmbeddedChecksum(corrupted.bytes) else corrupted.bytes
            val spec = if (deriveSpec) specFor(bytes) else base.spec
            file.writeBytes(bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "valid-image-mutation", SEED_VALID_IMAGE_MUTATION, iteration,
                corrupted.detail + " refresh=$refresh derive-spec=$deriveSpec",
                BigramValidationException::class.java, mustReject = !(refresh && deriveSpec),
            ) {
                validator.validateRaw(file, spec)
                validatedCleanly++
            }
        }
        // The shape exists to reach the structural checks; pin the exact deterministic count of
        // clean validations (corruptions landing only in bytes the structure leaves free) so a
        // future validator change that makes the fuzz shallower forces a review here.
        assertEquals(31, validatedCleanly)
    }

    @Test
    fun fuzzCompressedStreamRobustness() {
        val file = temporaryFolder.newFile("fuzz-inflate.tatbigr")
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
                        BigramTestFixtures.compress(oversized),
                        "bomb-raw=${oversized.size}",
                    )
                }
                else -> SeededFuzzHarness.corruptBytes(random, base.compressed)
            }
            val bomb = mutated.detail.startsWith("bomb-")
            val spec = if (mutated.bytes.isEmpty()) {
                base.spec
            } else {
                base.spec.copy(
                    expectedCompressedSize = mutated.bytes.size.toLong(),
                    expectedCompressedSha256 = BigramTestFixtures.sha256(mutated.bytes),
                )
            }
            val output = ByteArrayOutputStream()
            var inflated = false
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "compressed-stream", SEED_COMPRESSED_STREAM, iteration, mutated.detail,
                BigramValidationException::class.java, mustReject = bomb,
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
                    BigramValidationException::class.java, mustReject = false,
                ) {
                    validator.validateRaw(file, base.spec)
                }
            }
        }
    }

    /**
     * Re-derives the spec pins from a (possibly mutated) raw image so the fuzz reaches past the
     * identity gates — including the schema-3 link, the header's dictionary SHA-256 at bytes
     * 56..88 — into the structural checks. Images shorter than the header keep the base pins (the
     * validator rejects them on the header size first); a zeroed headCount keeps the base pin
     * (the validator rejects a zero count outright, and the spec constructor refuses to pin
     * zero).
     */
    private fun specFor(raw: ByteArray): BigramArtifactSpec {
        if (raw.size < TatBigrFormat.HEADER_SIZE) return base.spec
        val headCount = readU32(raw, HEAD_COUNT_OFFSET)
        return base.spec.copy(
            expectedRawSize = raw.size.toLong(),
            expectedRawSha256 = BigramTestFixtures.sha256(raw),
            expectedHeadCount = if (headCount == 0L) base.spec.expectedHeadCount else headCount,
            expectedDictionaryRawSha256 = raw.copyOfRange(56, 88).toHex(),
        )
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL

    private fun writeBytes(bytes: ByteArray) =
        temporaryFolder.newFile("fuzz-${System.nanoTime()}.tatbigr").also { it.writeBytes(bytes) }

    private companion object {
        const val SEED_BIT_FLIPS = 0x5EED_2001L
        const val SEED_TRUNCATIONS = 0x5EED_2002L
        const val SEED_INFLATION = 0x5EED_2003L
        const val SEED_GARBAGE = 0x5EED_2004L
        const val SEED_VALID_IMAGE_MUTATION = 0x5EED_2005L
        const val SEED_COMPRESSED_STREAM = 0x5EED_2006L

        /**
         * The u32 header fields, in header order: headCount, pairCount, blockCount,
         * blockIndexOffset, headDeltasOffset, headDeltasSize, countsOffset, successIdsOffset,
         * successIdsSize, declaredFileSize.
         */
        val U32_FIELD_OFFSETS = intArrayOf(16, 20, 24, 28, 32, 36, 40, 44, 48, 52)

        const val HEAD_COUNT_OFFSET = 16

        /** The embedded-checksum field, excluded from corruptions that get refreshed. */
        val CHECKSUM_RANGE = TatBigrFormat.CHECKSUM_OFFSET until
            (TatBigrFormat.CHECKSUM_OFFSET + TatBigrFormat.CHECKSUM_SIZE)
    }
}
