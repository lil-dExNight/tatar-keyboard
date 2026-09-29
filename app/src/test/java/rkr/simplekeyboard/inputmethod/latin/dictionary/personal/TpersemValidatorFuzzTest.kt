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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalEmojiTestFixtures.Entry
import java.util.Random

/**
 * Seeded deterministic fuzzing of [TpersemValidator] (the `.tpersem` personal emoji store) — S5
 * of `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md`, the mirror of [TpersbValidatorFuzzTest] for
 * (word, emoji) entries. Zero dependencies: mutations come from a seeded [java.util.Random] via
 * [SeededFuzzHarness]; a fixed seed per shape makes every run byte-identical, and on a property
 * violation the failure message names shape + seed + iteration + the touched offsets, which
 * reproduce the exact input.
 *
 * The base image is a fixture-built valid 16-entry Tatar image with distinct words, so the record
 * loop, the word-half normalization checks, the emoji-cluster ruler
 * ([rkr.simplekeyboard.inputmethod.latin.emoji.EmojiTextUtils.trailingEmojiClusterLength]) and
 * the entry-ordering checks are all reachable by the mutations.
 *
 * Shapes (all against [TpersemValidator.validate]): bit flips (2 500), truncations (2 000),
 * count/size-field inflation (2 000) of the two u32 header fields (entryCount, payloadSize) —
 * [TpersemFormat.MAX_PERSONAL_EMOJI_ENTRIES] and the payload-size check must reject BEFORE any
 * count-sized allocation — random garbage (2 000), and valid-image mutation (3 000) with the
 * embedded SHA-256 refreshed half the time (via
 * [PersonalEmojiTestFixtures.refreshEmbeddedChecksum]) so the fuzz reaches PAST the checksum gate
 * into the structural checks; only refreshed mutations may validate cleanly.
 *
 * Properties asserted on EVERY input (see [SeededFuzzHarness.assertCleanOrValidationFailure]):
 * clean validation or [PersonalDictionaryValidationException], never any other throwable, and —
 * for the shapes that cannot produce a valid image — never a clean validation (fail-closed; the
 * reader turns a rejection into an empty personal-emoji store).
 */
class TpersemValidatorFuzzTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validator = TpersemValidator()

    private val baseImage: ByteArray = run {
        val words = SeededFuzzHarness.tatarWords(16)
        val emoji = listOf("☀️", "🌙", "🎉")
        PersonalEmojiTestFixtures.build(
            words.mapIndexed { index, word ->
                Entry(
                    word = word,
                    emoji = emoji[index % emoji.size],
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
        assertEquals(16, validated.entryCount)
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
                PersonalEmojiTestFixtures.refreshEmbeddedChecksum(mutated.bytes)
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
                if (refresh) PersonalEmojiTestFixtures.refreshEmbeddedChecksum(corrupted.bytes) else corrupted.bytes
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
        assertEquals(97, validatedCleanly)
    }

    private fun writeBytes(bytes: ByteArray) =
        temporaryFolder.newFile("fuzz-${System.nanoTime()}.tpersem").also { it.writeBytes(bytes) }

    private companion object {
        const val SEED_BIT_FLIPS = 0x5EED_5001L
        const val SEED_TRUNCATIONS = 0x5EED_5002L
        const val SEED_INFLATION = 0x5EED_5003L
        const val SEED_GARBAGE = 0x5EED_5004L
        const val SEED_VALID_IMAGE_MUTATION = 0x5EED_5005L

        /** entryCount and payloadSize, the only u32 header fields (offsets per TpersemFormat). */
        val U32_FIELD_OFFSETS = intArrayOf(16, 20)

        /** The embedded-checksum field, excluded from corruptions that get refreshed. */
        val CHECKSUM_RANGE = TpersemFormat.CHECKSUM_OFFSET until
            (TpersemFormat.CHECKSUM_OFFSET + TpersemFormat.CHECKSUM_SIZE)
    }
}
