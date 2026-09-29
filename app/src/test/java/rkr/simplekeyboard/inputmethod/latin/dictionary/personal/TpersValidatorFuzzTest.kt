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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionaryTestFixtures.Entry
import java.util.Random

/**
 * Seeded deterministic fuzzing of [TpersValidator] (the `.tpers` personal dictionary) — S5 of
 * `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md`. Zero dependencies: mutations come from a seeded
 * [java.util.Random] via [SeededFuzzHarness]; a fixed seed per shape makes every run
 * byte-identical, and on a property violation the failure message names shape + seed + iteration
 * + the touched offsets, which reproduce the exact input.
 *
 * The base image is a fixture-built valid 24-entry Tatar image, so the record loop, the
 * UTF-8/casing/alphabet checks and the ordering checks are all reachable by the mutations.
 *
 * Shapes (all against [TpersValidator.validate]):
 *
 *  * **bit flips** (2 500): 1–4 single-bit flips, no checksum refresh — the integrity gates must
 *    reject from every position;
 *  * **truncations** (2 000): strict prefixes at random lengths — the header's payload size can
 *    never match, so all must reject;
 *  * **count/size-field inflation** (2 000): the two u32 header fields (entryCount, payloadSize)
 *    set to huge values — [TpersFormat.MAX_PERSONAL_ENTRIES] and the payload-size check must
 *    reject BEFORE any count-sized allocation; the file itself stays ~400 bytes, so an allocation
 *    attempt per an inflated count would blow up and be caught;
 *  * **random garbage** (2 000): random bytes of random length;
 *  * **valid-image mutation** (3 000): payload corruption, then half the time the embedded
 *    SHA-256 is refreshed (via [PersonalDictionaryTestFixtures.refreshEmbeddedChecksum]) so the
 *    fuzz reaches PAST the checksum gate into the structural checks. A refreshed mutation may
 *    validate cleanly (the corruption can land in bytes the structure leaves free, e.g. inside a
 *    last-use serial); a non-refreshed one must always reject.
 *
 * Properties asserted on EVERY input (see [SeededFuzzHarness.assertCleanOrValidationFailure]):
 * clean validation or [PersonalDictionaryValidationException], never any other throwable, and —
 * for the shapes that cannot produce a valid image — never a clean validation (fail-closed; the
 * reader turns a rejection into an empty personal dictionary).
 */
class TpersValidatorFuzzTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validator = TpersValidator()

    private val baseImage = PersonalDictionaryTestFixtures.build(
        SeededFuzzHarness.tatarWords(24).mapIndexed { index, word ->
            Entry(word, count = index % 7 + 1, serial = index + 1L)
        },
    )

    @Test
    fun baseImageValidates() {
        val validated = validator.validate(writeBytes(baseImage), PersonalSubtypes.TATAR_RU)
        assertEquals(24, validated.entryCount)
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
                PersonalDictionaryTestFixtures.refreshEmbeddedChecksum(mutated.bytes)
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
                if (refresh) PersonalDictionaryTestFixtures.refreshEmbeddedChecksum(corrupted.bytes) else corrupted.bytes
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
        assertEquals(141, validatedCleanly)
    }

    private fun writeBytes(bytes: ByteArray) =
        temporaryFolder.newFile("fuzz-${System.nanoTime()}.tpers").also { it.writeBytes(bytes) }

    private companion object {
        const val SEED_BIT_FLIPS = 0x5EED_3001L
        const val SEED_TRUNCATIONS = 0x5EED_3002L
        const val SEED_INFLATION = 0x5EED_3003L
        const val SEED_GARBAGE = 0x5EED_3004L
        const val SEED_VALID_IMAGE_MUTATION = 0x5EED_3005L

        /** entryCount and payloadSize, the only u32 header fields (offsets per TpersFormat). */
        val U32_FIELD_OFFSETS = intArrayOf(16, 20)

        /** The embedded-checksum field, excluded from corruptions that get refreshed. */
        val CHECKSUM_RANGE = TpersFormat.CHECKSUM_OFFSET until
            (TpersFormat.CHECKSUM_OFFSET + TpersFormat.CHECKSUM_SIZE)
    }
}
