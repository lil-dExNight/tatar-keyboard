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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.SeededFuzzHarness
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramTestFixtures.Entry
import java.util.Random

/**
 * Seeded fuzzing of [TpersbValidator] (the `.tpersb` learned word pairs). See [SeededFuzzHarness].
 *
 * The base image is a valid 20-pair Tatar fixture with distinct contexts, so the record loop, the
 * two-word UTF-8 and normalization checks and the pair ordering are reachable. Shapes mirror
 * [TpersValidatorFuzzTest], inflating pairCount and payloadSize.
 */
class TpersbValidatorFuzzTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validator = TpersbValidator()

    private val baseImage: ByteArray = run {
        val words = SeededFuzzHarness.tatarWords(40)
        PersonalBigramTestFixtures.build(
            (0 until 20).map { index ->
                Entry(
                    context = words[index],
                    successor = words[(index * 7 + 3) % words.size],
                    usage = index % 3,
                    frequency = index % 5 + 1,
                    serial = index + 1L,
                )
            },
        )
    }

    @Test
    fun baseImageValidates() {
        val validated = validator.validate(writeBytes(baseImage), PersonalSubtypes.TATAR_RU)
        assertEquals(20, validated.pairCount)
    }

    @Test
    fun fuzzBitFlipsNeverEscapeTheIntegrityGate() {
        val file = writeBytes(baseImage)
        val random = Random(SEED_BIT_FLIPS)
        repeat(2_500) { iteration ->
            val mutated = SeededFuzzHarness.flipBits(random, baseImage)
            file.writeBytes(mutated.bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "bit-flips", SEED_BIT_FLIPS, iteration, mutated.detail,
                PersonalDictionaryValidationException::class.java, mustReject = true,
            ) {
                validator.validate(file, PersonalSubtypes.TATAR_RU)
            }
        }
    }

    @Test
    fun fuzzTruncationsNeverValidate() {
        val file = writeBytes(baseImage)
        val random = Random(SEED_TRUNCATIONS)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.truncate(random, baseImage)
            file.writeBytes(mutated.bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "truncations", SEED_TRUNCATIONS, iteration, mutated.detail,
                PersonalDictionaryValidationException::class.java, mustReject = true,
            ) {
                validator.validate(file, PersonalSubtypes.TATAR_RU)
            }
        }
    }

    @Test
    fun fuzzCountAndSizeFieldInflationStaysUnderTheCaps() {
        val file = writeBytes(baseImage)
        val random = Random(SEED_INFLATION)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.inflateFields(random, baseImage, U32_FIELD_OFFSETS)
            // Half the iterations also refresh the checksum: the count/size guards precede the
            // checksum gate, but this arm additionally reaches the record loop with a mutated
            // count that slipped under the cap (a random value can land below it).
            val bytes = if (random.nextBoolean()) {
                PersonalBigramTestFixtures.refreshEmbeddedChecksum(mutated.bytes)
            } else {
                mutated.bytes
            }
            file.writeBytes(bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "inflation", SEED_INFLATION, iteration, mutated.detail,
                PersonalDictionaryValidationException::class.java, mustReject = true,
            ) {
                validator.validate(file, PersonalSubtypes.TATAR_RU)
            }
        }
    }

    @Test
    fun fuzzRandomGarbageNeverValidates() {
        val file = writeBytes(baseImage)
        val random = Random(SEED_GARBAGE)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.garbage(random, 2_048)
            file.writeBytes(mutated.bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "garbage", SEED_GARBAGE, iteration, mutated.detail,
                PersonalDictionaryValidationException::class.java, mustReject = true,
            ) {
                validator.validate(file, PersonalSubtypes.TATAR_RU)
            }
        }
    }

    @Test
    fun fuzzValidImageMutationReachesStructuralChecks() {
        val file = writeBytes(baseImage)
        val random = Random(SEED_VALID_IMAGE_MUTATION)
        var validatedCleanly = 0
        repeat(3_000) { iteration ->
            val refresh = random.nextBoolean()
            val corrupted = SeededFuzzHarness.corruptBytes(
                random,
                baseImage,
                // The refresh zeroes and rewrites the checksum field: a corruption landing only
                // there would be wiped, recreating the valid base image.
                excluding = if (refresh) CHECKSUM_RANGE else null,
            )
            val bytes =
                if (refresh) PersonalBigramTestFixtures.refreshEmbeddedChecksum(corrupted.bytes) else corrupted.bytes
            file.writeBytes(bytes)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "valid-image-mutation", SEED_VALID_IMAGE_MUTATION, iteration,
                corrupted.detail + " refresh=$refresh",
                PersonalDictionaryValidationException::class.java, mustReject = !refresh,
            ) {
                validator.validate(file, PersonalSubtypes.TATAR_RU)
                validatedCleanly++
            }
        }
        // Pin the exact deterministic count of clean validations (refreshed corruptions that
        // landed only in bytes the structure leaves free) so a future validator change that
        // makes the fuzz shallower forces a review here.
        assertEquals(119, validatedCleanly)
    }

    private fun writeBytes(bytes: ByteArray) =
        temporaryFolder.newFile("fuzz-${System.nanoTime()}.tpersb").also { it.writeBytes(bytes) }

    private companion object {
        const val SEED_BIT_FLIPS = 0x5EED_4001L
        const val SEED_TRUNCATIONS = 0x5EED_4002L
        const val SEED_INFLATION = 0x5EED_4003L
        const val SEED_GARBAGE = 0x5EED_4004L
        const val SEED_VALID_IMAGE_MUTATION = 0x5EED_4005L

        /** pairCount and payloadSize, the only u32 header fields (offsets per TpersbFormat). */
        val U32_FIELD_OFFSETS = intArrayOf(16, 20)

        /** The embedded-checksum field, excluded from corruptions that get refreshed. */
        val CHECKSUM_RANGE = TpersbFormat.CHECKSUM_OFFSET until
            (TpersbFormat.CHECKSUM_OFFSET + TpersbFormat.CHECKSUM_SIZE)
    }
}
