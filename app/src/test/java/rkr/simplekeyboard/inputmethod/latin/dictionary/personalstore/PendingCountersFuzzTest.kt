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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import org.junit.Assert.assertEquals
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.SeededFuzzHarness
import java.util.Random

/**
 * Seeded fuzzing of [PendingCounters.parseOrNull] (the `pending-*.bin` counter files). See
 * [SeededFuzzHarness].
 *
 * The format has no integrity field and no exception type: rejection is a null return, wrapped
 * here in [Rejected] so the shared property gate applies unchanged — any other throwable escaping
 * the parser (BufferUnderflowException, OutOfMemoryError, ...) fails the test. The base image is
 * a valid 16-entry counter file written through the production serializer, so the record loop and
 * the count, ordering and serial checks are all reachable. Shapes mirror the validator fuzz
 * suites, but with no checksum there is nothing to refresh: a mutation that keeps the header
 * self-consistent legitimately parses, so only truncations and garbage must always reject.
 */
class PendingCountersFuzzTest {

    private val baseImage: ByteArray = run {
        val salt = ByteArray(16) { it.toByte() }
        var counters = PendingCounters.EMPTY
        for (word in SeededFuzzHarness.tatarWords(16)) {
            counters = counters.note(PendingCounters.keyOf(salt, word))
        }
        counters.serialize()
    }

    @Test
    fun baseImageParses() {
        assertEquals(16, PendingCounters.parse(baseImage).size)
    }

    @Test
    fun fuzzBitFlipsNeverThrow() {
        val random = Random(SEED_BIT_FLIPS)
        repeat(2_500) { iteration ->
            val mutated = SeededFuzzHarness.flipBits(random, baseImage)
            // No integrity gate in this format: a flip that keeps the header self-consistent
            // (a record byte, a low nextSerial byte) legitimately parses.
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "bit-flips", SEED_BIT_FLIPS, iteration, mutated.detail,
                Rejected::class.java, mustReject = false,
            ) {
                rejectOnNull(mutated.bytes)
            }
        }
    }

    @Test
    fun fuzzTruncationsNeverParse() {
        val random = Random(SEED_TRUNCATIONS)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.truncate(random, baseImage)
            // A strict prefix always mismatches the claimed record count (or the header size).
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "truncations", SEED_TRUNCATIONS, iteration, mutated.detail,
                Rejected::class.java, mustReject = true,
            ) {
                rejectOnNull(mutated.bytes)
            }
        }
    }

    @Test
    fun fuzzCountAndSerialFieldInflationStaysUnderTheCaps() {
        val random = Random(SEED_INFLATION)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.inflateFields(random, baseImage, FIELD_OFFSETS)
            // The count guard precedes the count-sized arrays, so an inflated count is rejected
            // before any allocation; a write landing only in the low nextSerial bytes keeps the
            // header self-consistent and legitimately parses.
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "inflation", SEED_INFLATION, iteration, mutated.detail,
                Rejected::class.java, mustReject = false,
            ) {
                rejectOnNull(mutated.bytes)
            }
        }
    }

    @Test
    fun fuzzRandomGarbageNeverParses() {
        val random = Random(SEED_GARBAGE)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.garbage(random, 2_048)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "garbage", SEED_GARBAGE, iteration, mutated.detail,
                Rejected::class.java, mustReject = true,
            ) {
                rejectOnNull(mutated.bytes)
            }
        }
    }

    @Test
    fun fuzzValidImageMutationNeverThrows() {
        val random = Random(SEED_VALID_IMAGE_MUTATION)
        var parsedCleanly = 0
        repeat(3_000) { iteration ->
            val mutated = SeededFuzzHarness.corruptBytes(random, baseImage)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "valid-image-mutation", SEED_VALID_IMAGE_MUTATION, iteration, mutated.detail,
                Rejected::class.java, mustReject = false,
            ) {
                rejectOnNull(mutated.bytes)
                parsedCleanly++
            }
        }
        // Pin the exact deterministic count of clean parses (corruptions that kept the header
        // self-consistent) so a parser change that makes the fuzz shallower forces a review here.
        assertEquals(1770, parsedCleanly)
    }

    private fun rejectOnNull(bytes: ByteArray) {
        if (PendingCounters.parseOrNull(bytes) == null) throw Rejected()
    }

    /** The format's rejection signal, a null return, as an exception for the shared gate. */
    private class Rejected : Exception()

    private companion object {
        const val SEED_BIT_FLIPS = 0x5EED_9001L
        const val SEED_TRUNCATIONS = 0x5EED_9002L
        const val SEED_INFLATION = 0x5EED_9003L
        const val SEED_GARBAGE = 0x5EED_9004L
        const val SEED_VALID_IMAGE_MUTATION = 0x5EED_9005L

        /**
         * formatVersion (u16 at 8), count (u16 at 10) and the low half of nextSerial (i64 at 12);
         * the u32 writes span the u16 fields and the low bytes of the i64.
         */
        val FIELD_OFFSETS = intArrayOf(8, 10, 12)
    }
}
