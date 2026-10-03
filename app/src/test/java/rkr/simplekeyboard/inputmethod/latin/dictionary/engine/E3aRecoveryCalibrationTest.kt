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

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Calibration of recovery@3 for edit class #1 (letter -> long-press partner) on the real
 * committed dictionary. The test enumerates the committed `tatar_top100k_v1.tdict.zlib`
 * vocabulary, rebuilds the typo set `scripts/typo_pack.py` emits (same seed, same 3-code-point
 * prefix window, same layout-derived long-press pairs, same FNV-1a / SplitMix64 selection), then
 * counts how often each word's typo prefix puts the word in the top three. A SHA-256 match with
 * the generator's run shows both build the same set; the recovery number is printed.
 */
class E3aRecoveryCalibrationTest {

    // ---- Portable deterministic primitives (must match scripts/typo_pack.py bit-for-bit). ----

    private fun fnv1a64(data: ByteArray): Long {
        var hash = 0xCBF29CE484222325uL.toLong()
        for (byte in data) {
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= 0x100000001B3L
        }
        return hash
    }

    private fun splitmix64(seed: Long): Long {
        var z = seed + 0x9E3779B97F4A7C15uL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    private fun selectionIndex(word: String, choices: Int): Int =
        java.lang.Long.remainderUnsigned(
            splitmix64(SEED xor fnv1a64(word.toByteArray(Charsets.UTF_8))),
            choices.toLong(),
        ).toInt()

    @Test
    fun primitivesMatchTheGeneratorGoldenVectors() {
        assertEquals(java.lang.Long.parseUnsignedLong("14695981039346656037"), fnv1a64(ByteArray(0)))
        assertEquals(
            java.lang.Long.parseUnsignedLong("3368278190552415294"),
            fnv1a64("ана".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(java.lang.Long.parseUnsignedLong("16294208416658607535"), splitmix64(0L))
        assertEquals(java.lang.Long.parseUnsignedLong("5623135597990589359"), splitmix64(SEED))
        // Selection golden vectors produced by scripts/typo_pack.py over an illustrative N=10.
        assertEquals(0, selectionIndex("ана", 10))
        assertEquals(3, selectionIndex("китап", 10))
        assertEquals(7, selectionIndex("авыл", 10))
    }

    // ---- The reproducible typo set, built from the enumerated real vocabulary. ----

    private data class TypoRow(val word: String, val typoPrefixUtf8: ByteArray)

    private fun buildTypoSet(): Triple<List<TypoRow>, List<Int>, String> {
        val table = neighborTable
        val rows = ArrayList<TypoRow>(vocabulary.size)
        val variantCounts = ArrayList<Int>(vocabulary.size)
        val rendered = StringBuilder(vocabulary.size * 12)
        for (word in vocabulary) {
            val codePoints = word.codePoints().toArray()
            if (codePoints.size < PREFIX_CODE_POINTS) continue
            val eligiblePositions = ArrayList<Int>(PREFIX_CODE_POINTS)
            val eligiblePartners = ArrayList<Int>(PREFIX_CODE_POINTS)
            for (position in 0 until PREFIX_CODE_POINTS) {
                val partners = table.longPressPartnersOf(codePoints[position]) ?: continue
                for (partner in partners) {
                    eligiblePositions.add(position)
                    eligiblePartners.add(partner)
                }
            }
            if (eligiblePositions.isEmpty()) continue
            val choice = selectionIndex(word, eligiblePositions.size)
            val typoCodePoints = codePoints.copyOf(PREFIX_CODE_POINTS)
            typoCodePoints[eligiblePositions[choice]] = eligiblePartners[choice]
            val typoPrefix = StringBuilder(PREFIX_CODE_POINTS)
            for (slot in 0 until PREFIX_CODE_POINTS) typoPrefix.appendCodePoint(typoCodePoints[slot])
            val typoPrefixString = typoPrefix.toString()
            rows.add(TypoRow(word, typoPrefixString.toByteArray(Charsets.UTF_8)))
            rendered.append(word).append('\t').append(typoPrefixString).append('\n')
            var variants = 0
            for (codePoint in typoCodePoints) {
                variants += table.longPressPartnersOf(codePoint)?.size ?: 0
            }
            variantCounts.add(variants)
        }
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(rendered.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return Triple(rows, variantCounts, sha)
    }

    @Test
    fun theTypoSetIsByteIdenticalToTheGeneratorRun() {
        val (rows, _, sha) = buildTypoSet()
        // Same size and same bytes as the independent scripts/typo_pack.py run on the same asset:
        // this proves the JVM test and the offline generator produce THE SAME reproducible set.
        assertEquals(GENERATOR_SET_SIZE, rows.size)
        assertEquals(GENERATOR_SET_SHA256, sha)
    }

    @Test
    fun recoveryAtThreeForEditClassOne() {
        val index = realIndex ?: error("real dictionary index not loaded")
        val (rows, variantCounts, sha) = buildTypoSet()
        assertEquals(GENERATOR_SET_SIZE, rows.size)
        assertEquals(GENERATOR_SET_SHA256, sha)

        // Baseline: exact pass only (no neighbor table => no fuzzy level). A typo prefix carries
        // the wrong letter, so the correct word cannot appear; this pins the no-fuzzy-pass
        // reference the calibration is measured against. We also record, per row, whether the fuzzy
        // pass would even fire (the engine runs it only when the exact pass returns < MAX_RESULTS).
        index.updateKeyNeighbors(null)
        var baselineRecovered = 0
        var fuzzyFired = 0
        for (row in rows) {
            val exact = index.lookup(ImmutableUtf8Prefix.copyOf(row.typoPrefixUtf8))
            if (exact.contains(row.word)) baselineRecovered++
            if (exact.size < MAX_RESULTS) fuzzyFired++
        }

        // Fuzzy pass enabled with the layout-derived table.
        index.updateKeyNeighbors(neighborTable)
        var recovered = 0
        for (row in rows) {
            if (lookupContains(index, row.typoPrefixUtf8, row.word)) recovered++
        }

        val total = rows.size
        val recovery = recovered.toDouble() / total
        val baseline = baselineRecovered.toDouble() / total
        // Diagnostic: recovery among only those prefixes where the fuzzy pass actually ran. When the
        // typed (typo) prefix already has three or more exact continuations, the cell-fill rule
        // leaves the fuzzy level switched off, so those rows can never recover.
        val conditional = if (fuzzyFired > 0) recovered.toDouble() / fuzzyFired else 0.0
        val sorted = variantCounts.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[ceil(sorted.size * 0.95).toInt() - 1]
        val max = sorted.last()
        val deltaPp = recovery * 100 - CONTRACT_RECOVERY_PP
        val withinTolerance = kotlin.math.abs(deltaPp) <= TOLERANCE_PP

        // Raw calibration line (grep target: "E3a recovery@3").
        println(
            "E3a recovery@3 class#1 seed=$SEED prefix_cp=$PREFIX_CODE_POINTS set=$total " +
                "recovered=$recovered recovery@3=${format(recovery * 100)}% " +
                "baseline_exact_only=${format(baseline * 100)}% " +
                "fuzzy_fired=$fuzzyFired recovery@3_when_fuzzy_fired=${format(conditional * 100)}% " +
                "variant_p50=$p50 variant_p95=$p95 variant_max=$max " +
                "contract=${CONTRACT_RECOVERY_PP}% delta=${format(deltaPp)}pp " +
                "within_${TOLERANCE_PP}pp=$withinTolerance set_sha256=$sha",
        )

        // Non-fudged invariants that hold irrespective of the exact recovery value.
        assertTrue("set is far larger than a smoke sample", total > 10_000)
        assertTrue("recovery is a fraction", recovery in 0.0..1.0)
        assertTrue(
            "fuzzy must recover strictly more than the exact-only baseline",
            recovered > baselineRecovered,
        )
        // No hard equality gate on the 14.2% target: the test measures and prints the real number,
        // and a mismatch must not be tuned away in code, test, generator or tolerance.
    }

    private fun lookupContains(index: TdictPrefixIndex, prefixUtf8: ByteArray, word: String): Boolean {
        val results = index.lookup(ImmutableUtf8Prefix.copyOf(prefixUtf8))
        return results.contains(word)
    }

    /**
     * Records what the fuzzy pass adds on the 22 reviewed everyday prefixes. These are real
     * prefixes, not typos: the fuzzy level fills only cells the exact pass left empty and never
     * shifts an exact candidate. The printed deltas are for human review.
     */
    @Test
    fun fuzzyAddedCandidatesOnTheTwentyTwoEverydayPrefixes() {
        val index = realIndex ?: error("real dictionary index not loaded")
        val prefixes = everydayPrefixes()
        assertEquals(22, prefixes.size)
        for (prefix in prefixes) {
            val query = ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8))
            index.updateKeyNeighbors(null)
            val exact = index.lookup(query)
            index.updateKeyNeighbors(neighborTable)
            val fuzzy = index.lookup(query)
            // The exact candidates are never shifted, replaced or removed: the fuzzy result begins
            // with exactly the exact-only result.
            assertEquals(exact, fuzzy.take(exact.size))
            val added = fuzzy.drop(exact.size)
            println("E3a fuzzy-delta\t$prefix\texact=${exact.joinToString("|")}\tadded=${added.joinToString("|")}")
        }
    }

    private fun everydayPrefixes(): List<String> {
        val review = File("data/dictionary/tt-query-review.tsv").takeIf(File::isFile)
            ?: File("../data/dictionary/tt-query-review.tsv")
        return review.readLines(Charsets.UTF_8).drop(1)
            .filter { it.isNotBlank() }
            .map { it.split('\t')[0] }
    }

    private fun format(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)

    companion object {
        private const val SEED = 20260727L
        private const val PREFIX_CODE_POINTS = 3

        // The suggestion strip has three cells; the engine runs the fuzzy pass only when the exact
        // pass leaves at least one empty (TdictPrefixIndex.MAX_RESULTS).
        private const val MAX_RESULTS = 3

        // Independently produced by `python3 scripts/typo_pack.py build ...` on the same committed
        // asset; equality with the JVM-built set shows both implementations build the same set.
        // Re-pin when the dictionary is rebuilt.
        private const val GENERATOR_SET_SIZE = 96_079
        private const val GENERATOR_SET_SHA256 =
            "f75c6db263f2b619f502f235dd3081c4691d1a4870ff7bf8fefb6e47a1adbdb4"

        // Target recovery and the chosen tolerance, in percentage points.
        private const val CONTRACT_RECOVERY_PP = 14.2
        private const val TOLERANCE_PP = 1.0

        private val neighborTable = E3aTestFixtures.tatarNeighborTable()
        private var realIndex: TdictPrefixIndex? = null
        private lateinit var vocabulary: List<String>

        @JvmStatic
        @BeforeClass
        fun loadCommittedDictionary() {
            val asset = locate(
                "src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
            )
            val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val rawFile = File.createTempFile("e3a-recovery-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                val raw = rawFile.readBytes()
                val identity = DictionaryIdentity(
                    spec.generation,
                    validated.schemaId,
                    validated.formatVersion,
                    validated.rawSha256,
                )
                realIndex = TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw),
                    identity,
                    validated.entryCount,
                    validated.rawSize,
                )
                check(realIndex != null)
                vocabulary = enumerateWords(raw)
                check(vocabulary.size == spec.expectedEntryCount.toInt())
            } finally {
                rawFile.delete()
            }
        }

        /** Enumerates the words of a validated schema-2 tdict directly off its documented layout. */
        private fun enumerateWords(raw: ByteArray): List<String> =
            DictionaryTestFixtures.words(raw)

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed dictionary test resource")
    }
}
