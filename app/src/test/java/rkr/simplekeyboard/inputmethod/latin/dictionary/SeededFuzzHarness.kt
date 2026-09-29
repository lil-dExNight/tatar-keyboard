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

package rkr.simplekeyboard.inputmethod.latin.dictionary

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * Shared seeded-mutation harness for the parser fuzz tests (S5 of
 * `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md`), used by the five reader fuzz suites
 * (`TdictValidatorFuzzTest`, `TatBigrValidatorFuzzTest`, `TpersValidatorFuzzTest`,
 * `TpersbValidatorFuzzTest`, `TpersemValidatorFuzzTest`).
 *
 * Everything here is deterministic: mutators draw only from the caller's seeded
 * [java.util.Random] (stdlib, zero dependencies — no fuzzing library), so a seed plus an
 * iteration index reproduces the exact byte image that tripped a failure. Every mutation
 * returns a [MutatedImage] whose [MutatedImage.detail] names the offsets/lengths touched, and
 * the assertion helper prints shape + seed + iteration + detail on any property violation.
 *
 * The properties every fuzz loop asserts on EVERY input:
 *
 *  1. the reader either validates cleanly or throws the format's OWN validation exception type —
 *     never any other throwable (no IndexOutOfBoundsException, no NegativeArraySizeException,
 *     no OutOfMemoryError: [assertCleanOrValidationFailure] catches [Throwable] and fails the
 *     test with the full reproduction coordinates);
 *  2. no allocation beyond the format's declared caps — the ceiling guard is the cap checks the
 *     validators already carry (count/size fields are validated against the caps and the real
 *     file length BEFORE any count-sized allocation happens); the inflation shape keeps the file
 *     itself small, so any attempt to allocate per an inflated count would blow up and be caught
 *     by property 1;
 *  3. fail-closed: shapes that cannot produce a structurally valid image (bit flips without a
 *     checksum refresh, truncations, random garbage, huge-count inflation) must NEVER validate
 *     cleanly — a clean validation there is itself the defect.
 */
internal object SeededFuzzHarness {

    /** A mutated byte image plus a short human-readable description of what was touched. */
    class MutatedImage(val bytes: ByteArray, val detail: String)

    /**
     * Shape (a): 1..4 uniform single-bit flips at uniformly chosen positions of a copy of
     * [image]. Every flip changes a byte, so without a checksum refresh the image can never
     * validate — the shape proves the integrity gates fire from every position.
     */
    fun flipBits(random: Random, image: ByteArray): MutatedImage {
        val mutated = image.copyOf()
        val flips = 1 + random.nextInt(4)
        // (offset, bit) pairs must be distinct: applying the same pair twice cancels itself and
        // could recreate the original image, which LEGITIMATELY validates — the shape's contract
        // is that the mutated image always differs from the input.
        val appliedPairs = HashSet<Int>()
        val offsets = ArrayList<Int>(flips)
        var applied = 0
        while (applied < flips) {
            val offset = random.nextInt(mutated.size)
            val bit = random.nextInt(8)
            if (!appliedPairs.add(offset * 8 + bit)) continue
            mutated[offset] = (mutated[offset].toInt() xor (1 shl bit)).toByte()
            offsets += offset
            applied++
        }
        return MutatedImage(mutated, "bit-flips@${offsets.sorted()}")
    }

    /** Shape (b): a strict prefix of [image] of a uniformly chosen length (0 until size). */
    fun truncate(random: Random, image: ByteArray): MutatedImage {
        val length = random.nextInt(image.size)
        return MutatedImage(image.copyOf(length), "truncated-to=$length")
    }

    /** Shape (d): random-byte garbage of a random length in `0..maxLength`. */
    fun garbage(random: Random, maxLength: Int): MutatedImage {
        val length = random.nextInt(maxLength + 1)
        return MutatedImage(ByteArray(length).also(random::nextBytes), "garbage-len=$length")
    }

    /**
     * The payload corruption of shape (e): 1..8 bytes of a copy of [image] XORed with a nonzero
     * random value at uniformly chosen DISTINCT positions. Unlike [flipBits] this is meant to be
     * paired with a checksum refresh so the fuzz reaches past the integrity gate into the
     * structural checks (the caller decides whether to refresh). [excluding] skips a byte range
     * when picking positions: a caller that refreshes the embedded checksum passes the checksum
     * field, because the refresh zeroes and rewrites that field — a corruption landing only
     * there would be wiped, recreating the original image, which legitimately validates.
     */
    fun corruptBytes(random: Random, image: ByteArray, excluding: IntRange? = null): MutatedImage {
        val mutated = image.copyOf()
        val corruptions = 1 + random.nextInt(8)
        // Distinct offsets: XORing the same byte twice with the same value would restore it and
        // could recreate the original image, which LEGITIMATELY validates — the shape's contract
        // is that the mutated image always differs from the input.
        val appliedOffsets = HashSet<Int>()
        val offsets = ArrayList<Int>(corruptions)
        var applied = 0
        while (applied < corruptions) {
            val offset = random.nextInt(mutated.size)
            if (excluding != null && offset in excluding) continue
            if (!appliedOffsets.add(offset)) continue
            mutated[offset] = (mutated[offset].toInt() xor (1 + random.nextInt(255))).toByte()
            offsets += offset
            applied++
        }
        return MutatedImage(mutated, "corrupt@${offsets.sorted()}")
    }

    /**
     * Shape (c): writes huge values into 1..3 distinct fields of a copy of [image] (offsets of
     * u32 little-endian count/size fields chosen by the caller), then reports them. The values
     * mix u32-max, the sign bit, power-of-two boundaries and raw randomness.
     */
    fun inflateFields(random: Random, image: ByteArray, fieldOffsets: IntArray): MutatedImage {
        val mutated = image.copyOf()
        val fieldCount = 1 + random.nextInt(minOf(3, fieldOffsets.size))
        // Partial Fisher-Yates over a copy: [fieldCount] distinct offsets, java.util.Random only.
        val pool = fieldOffsets.copyOf()
        val chosen = IntArray(fieldCount)
        for (index in 0 until fieldCount) {
            val pick = index + random.nextInt(pool.size - index)
            val tmp = pool[index]
            pool[index] = pool[pick]
            pool[pick] = tmp
            chosen[index] = pool[index]
        }
        val writes = ArrayList<String>(fieldCount)
        val buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in chosen) {
            var value = hugeFieldValue(random)
            if (value == buffer.getInt(offset)) {
                // Writing back the field's current value would leave the image unchanged; the
                // complement always differs and stays huge-shaped.
                value = value.inv()
            }
            buffer.putInt(offset, value)
            writes += "$offset=${value.toLong() and 0xffff_ffffL}"
        }
        return MutatedImage(mutated, "inflate{${writes.joinToString(",")}}")
    }

    /** Trailing garbage for the compressed-stream shape: [image] plus 1..64 random bytes. */
    fun appendGarbage(random: Random, image: ByteArray): MutatedImage {
        val extra = 1 + random.nextInt(64)
        val suffix = ByteArray(extra).also(random::nextBytes)
        return MutatedImage(image + suffix, "trailing+$extra")
    }

    private fun hugeFieldValue(random: Random): Int = when (random.nextInt(8)) {
        0 -> Int.MAX_VALUE
        1 -> -1 // u32 max
        2 -> Int.MIN_VALUE
        3 -> 0x00ff_ffff
        4 -> 0x0001_0000
        5 -> 0x0000_ffff
        6 -> random.nextInt()
        else -> random.nextInt() or Int.MIN_VALUE
    }

    /**
     * [count] distinct 3-letter words spelled from Tatar lowercase letters, sorted ascending.
     * Three letters over an 8-letter alphabet give 512 distinct combinations; every letter is in
     * the alphabet `TdictValidator`/`PersonalSubtypes` enforce, the words are already NFC
     * lowercase, and for these all-BMP Cyrillic strings the UTF-16 `String` order, the code-point
     * order and the unsigned UTF-8 byte order the validators enforce all coincide.
     */
    fun tatarWords(count: Int): List<String> {
        require(count <= 512) { "the 8-letter 3-position generator yields at most 512 words" }
        val letters = listOf('а', 'б', 'в', 'г', 'д', 'ә', 'е', 'ж')
        val words = ArrayList<String>(count)
        var value = 0
        while (words.size < count) {
            words += buildString {
                append(letters[value and 7])
                append(letters[(value ushr 3) and 7])
                append(letters[(value ushr 6) and 7])
            }
            value++
        }
        return words.sorted()
    }

    /**
     * The property gate every fuzz iteration funnels through. [block] must either return
     * normally (clean validation — only acceptable when [mustReject] is false) or throw an
     * [expectedFailure] instance. ANY other throwable — including [Error]s such as
     * [OutOfMemoryError] — fails the test with the shape, seed, iteration and mutation detail,
     * which together reproduce the exact input byte-for-byte.
     */
    fun assertCleanOrValidationFailure(
        shape: String,
        seed: Long,
        iteration: Int,
        detail: String,
        expectedFailure: Class<out Exception>,
        mustReject: Boolean,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (throwable: Throwable) {
            if (expectedFailure.isInstance(throwable)) return
            throw AssertionError(
                "fuzz[$shape seed=$seed iteration=$iteration $detail]: expected clean " +
                    "validation or ${expectedFailure.simpleName}, got " +
                    "${throwable.javaClass.name}: ${throwable.message}",
                throwable,
            )
        }
        if (mustReject) {
            throw AssertionError(
                "fuzz[$shape seed=$seed iteration=$iteration $detail]: structurally-invalid " +
                    "input validated cleanly (fail-closed violation)",
            )
        }
    }
}
