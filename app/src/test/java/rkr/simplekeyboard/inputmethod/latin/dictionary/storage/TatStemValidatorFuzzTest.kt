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

import java.util.Random
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.SeededFuzzHarness

/** Seeded raw and compressed-stream fuzzing of [TatStemValidator]. */
class TatStemValidatorFuzzTest {
    private val validator = TatStemValidator()
    private val base = StemLexiconTestFixtures.artifact()

    @Test
    fun baseImageValidates() {
        validator.validateRaw(base.raw, base.spec)
    }

    @Test
    fun bitFlipsNeverEscapeTheIntegrityGate() {
        val random = Random(SEED_BIT_FLIPS)
        repeat(1_000) { iteration ->
            val mutated = SeededFuzzHarness.flipBits(random, base.raw)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "bit-flips", SEED_BIT_FLIPS, iteration, mutated.detail,
                StemLexiconValidationException::class.java, mustReject = true,
            ) {
                validator.validateRaw(mutated.bytes, specFor(mutated.bytes))
            }
        }
    }

    @Test
    fun truncationsNeverValidate() {
        val random = Random(SEED_TRUNCATIONS)
        repeat(1_000) { iteration ->
            val mutated = SeededFuzzHarness.truncate(random, base.raw)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "truncations", SEED_TRUNCATIONS, iteration, mutated.detail,
                StemLexiconValidationException::class.java, mustReject = true,
            ) {
                validator.validateRaw(mutated.bytes, specFor(mutated.bytes))
            }
        }
    }

    @Test
    fun countAndSizeInflationStaysUnderTheCaps() {
        val random = Random(SEED_INFLATION)
        repeat(1_000) { iteration ->
            val mutated = SeededFuzzHarness.inflateFields(random, base.raw, U32_FIELDS)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "inflation", SEED_INFLATION, iteration, mutated.detail,
                StemLexiconValidationException::class.java, mustReject = true,
            ) {
                validator.validateRaw(mutated.bytes, specFor(mutated.bytes))
            }
        }
    }

    @Test
    fun randomGarbageNeverValidates() {
        val random = Random(SEED_GARBAGE)
        repeat(1_000) { iteration ->
            val mutated = SeededFuzzHarness.garbage(random, 2_048)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "garbage", SEED_GARBAGE, iteration, mutated.detail,
                StemLexiconValidationException::class.java, mustReject = true,
            ) {
                validator.validateRaw(mutated.bytes, specFor(mutated.bytes))
            }
        }
    }

    @Test
    fun checksumRefreshedMutationsReachStructuralChecks() {
        val random = Random(SEED_STRUCTURAL)
        repeat(2_000) { iteration ->
            val refresh = random.nextBoolean()
            val deriveSpec = random.nextBoolean()
            val corrupted = SeededFuzzHarness.corruptBytes(
                random, base.raw, excluding = if (refresh) CHECKSUM_RANGE else null,
            )
            val raw = if (refresh) {
                StemLexiconTestFixtures.refreshEmbeddedChecksum(corrupted.bytes)
            } else {
                corrupted.bytes
            }
            val spec = if (deriveSpec) specFor(raw) else base.spec
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "structural", SEED_STRUCTURAL, iteration,
                corrupted.detail + " refresh=$refresh derive-spec=$deriveSpec",
                StemLexiconValidationException::class.java,
                mustReject = !(refresh && deriveSpec),
            ) {
                validator.validateRaw(raw, spec)
            }
        }
    }

    @Test
    fun compressedStreamRobustness() {
        val random = Random(SEED_COMPRESSED)
        repeat(1_000) { iteration ->
            val mutated = when (random.nextInt(8)) {
                0, 1 -> SeededFuzzHarness.flipBits(random, base.compressed)
                2 -> SeededFuzzHarness.truncate(random, base.compressed)
                3 -> SeededFuzzHarness.garbage(random, 512)
                4 -> SeededFuzzHarness.appendGarbage(random, base.compressed)
                5 -> {
                    val oversized = ByteArray(base.spec.maxRawSize.toInt() + 1)
                    SeededFuzzHarness.MutatedImage(
                        DictionaryTestFixtures.compress(oversized), "raw-bomb",
                    )
                }
                else -> SeededFuzzHarness.corruptBytes(random, base.compressed)
            }
            val spec = if (mutated.bytes.isEmpty()) {
                base.spec
            } else {
                base.spec.copy(
                    expectedCompressedSize = mutated.bytes.size.toLong(),
                    expectedCompressedSha256 = StemLexiconTestFixtures.sha256(mutated.bytes),
                )
            }
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "compressed", SEED_COMPRESSED, iteration, mutated.detail,
                StemLexiconValidationException::class.java, mustReject = false,
            ) {
                validator.inflateAsset(mutated.bytes.inputStream(), spec)
            }
        }
    }

    private fun specFor(raw: ByteArray): StemLexiconArtifactSpec {
        if (raw.size < TatStemFormat.HEADER_SIZE) return base.spec
        return StemLexiconTestFixtures.spec(raw, compressed = base.compressed)
    }

    private companion object {
        const val SEED_BIT_FLIPS = 0x5EED_2101L
        const val SEED_TRUNCATIONS = 0x5EED_2102L
        const val SEED_INFLATION = 0x5EED_2103L
        const val SEED_GARBAGE = 0x5EED_2104L
        const val SEED_STRUCTURAL = 0x5EED_2105L
        const val SEED_COMPRESSED = 0x5EED_2106L
        val U32_FIELDS = intArrayOf(16, 20, 24, 28, 32, 36)
        val CHECKSUM_RANGE = TatStemFormat.CHECKSUM_OFFSET until TatStemFormat.HEADER_SIZE
    }
}
