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
 * E3b calibration: recovery@3 of the combined fuzzy engine (edit classes #1 + #2 + #3) on the REAL
 * committed dictionary, plus the byte-identity of every extended typo set with the offline
 * generator and the measured per-lookup variant / visited-entry statistics.
 *
 * The class #1 set is byte-identical to E3a (its SHA-256 is unchanged, which keeps the E3a
 * calibration valid). Classes #2 (geometric neighbour) and #3 (adjacent transposition) are the new
 * extensions; the same reproducible `(seed, word)` selection primitive picks one typo per eligible
 * word, and the same layout-derived geometry drives class #2. The recovery number is measured on
 * the whole combined set (the contract's denominator) with the full engine and reported; the
 * threshold verdict is printed, not tuned.
 *
 * 2026-09-20 (TT-TYPO-NEXT Phase B): the fixture geometry is now device-true (the horizontal gap
 * kills the 33 same-row pairs — 65 -> 32 pairs, class-#2 set re-pinned), and the engine under test
 * runs the per-engine [FuzzyEditPolicy] of [TdictPrefixIndex.open] — DEFAULT here (class #1 only),
 * exactly as since the 2026-07-27 E3b verdict. The "full engine" phrasing above is the historic
 * E3b setup; the current Phase-B calibration lives in [TtTypoPhaseBCalibrationTest].
 */
class E3bRecoveryCalibrationTest {

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

    // ---- Typo-set construction, mirroring scripts/typo_pack.py for each edit class. ----

    private data class TypoRow(val word: String, val typoPrefixUtf8: ByteArray)
    private data class TypoSet(val rows: List<TypoRow>, val sha256: String)

    private fun renderSha(rows: List<TypoRow>, rendered: StringBuilder): String =
        MessageDigest.getInstance("SHA-256")
            .digest(rendered.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Edit class #1: substitute a long-press partner inside the 3-code-point window. */
    private fun buildLongPressSet(): TypoSet {
        val rows = ArrayList<TypoRow>(vocabulary.size)
        val rendered = StringBuilder(vocabulary.size * 12)
        for (word in vocabulary) {
            val codePoints = word.codePoints().toArray()
            if (codePoints.size < PREFIX_CODE_POINTS) continue
            val positions = ArrayList<Int>()
            val partners = ArrayList<Int>()
            for (position in 0 until PREFIX_CODE_POINTS) {
                val neighbours = neighborTable.longPressPartnersOf(codePoints[position]) ?: continue
                for (partner in neighbours) {
                    positions.add(position)
                    partners.add(partner)
                }
            }
            if (positions.isEmpty()) continue
            val choice = selectionIndex(word, positions.size)
            val typo = codePoints.copyOf(PREFIX_CODE_POINTS)
            typo[positions[choice]] = partners[choice]
            appendRow(word, typo, rows, rendered)
        }
        return TypoSet(rows, renderSha(rows, rendered))
    }

    /** Edit class #2: substitute a geometric neighbour inside the 3-code-point window. */
    private fun buildGeometricSet(): TypoSet {
        val rows = ArrayList<TypoRow>(vocabulary.size)
        val rendered = StringBuilder(vocabulary.size * 12)
        for (word in vocabulary) {
            val codePoints = word.codePoints().toArray()
            if (codePoints.size < PREFIX_CODE_POINTS) continue
            val positions = ArrayList<Int>()
            val neighboursOut = ArrayList<Int>()
            for (position in 0 until PREFIX_CODE_POINTS) {
                val neighbours = neighborTable.geometricNeighborsOf(codePoints[position]) ?: continue
                for (neighbour in neighbours) {
                    positions.add(position)
                    neighboursOut.add(neighbour)
                }
            }
            if (positions.isEmpty()) continue
            val choice = selectionIndex(word, positions.size)
            val typo = codePoints.copyOf(PREFIX_CODE_POINTS)
            typo[positions[choice]] = neighboursOut[choice]
            appendRow(word, typo, rows, rendered)
        }
        return TypoSet(rows, renderSha(rows, rendered))
    }

    /** Edit class #3: swap a distinct adjacent pair inside the 3-code-point window. */
    private fun buildTranspositionSet(): TypoSet {
        val rows = ArrayList<TypoRow>(vocabulary.size)
        val rendered = StringBuilder(vocabulary.size * 12)
        for (word in vocabulary) {
            val codePoints = word.codePoints().toArray()
            if (codePoints.size < PREFIX_CODE_POINTS) continue
            val pivots = ArrayList<Int>()
            for (i in 0 until PREFIX_CODE_POINTS - 1) {
                if (codePoints[i] != codePoints[i + 1]) pivots.add(i)
            }
            if (pivots.isEmpty()) continue
            val pivot = pivots[selectionIndex(word, pivots.size)]
            val typo = codePoints.copyOf(PREFIX_CODE_POINTS)
            val swap = typo[pivot]
            typo[pivot] = typo[pivot + 1]
            typo[pivot + 1] = swap
            appendRow(word, typo, rows, rendered)
        }
        return TypoSet(rows, renderSha(rows, rendered))
    }

    private fun appendRow(word: String, typo: IntArray, rows: ArrayList<TypoRow>, rendered: StringBuilder) {
        val prefix = StringBuilder(PREFIX_CODE_POINTS)
        for (slot in 0 until PREFIX_CODE_POINTS) prefix.appendCodePoint(typo[slot])
        val prefixString = prefix.toString()
        rows.add(TypoRow(word, prefixString.toByteArray(Charsets.UTF_8)))
        rendered.append(word).append('\t').append(prefixString).append('\n')
    }

    @Test
    fun everyExtendedTypoSetIsByteIdenticalToTheGeneratorRun() {
        val classOne = buildLongPressSet()
        val classTwo = buildGeometricSet()
        val classThree = buildTranspositionSet()
        // Class #1 MUST stay bit-for-bit identical to E3a, or the E3a calibration breaks.
        assertEquals(CLASS1_SIZE, classOne.rows.size)
        assertEquals(CLASS1_SHA256, classOne.sha256)
        // Classes #2 and #3 are the new extensions; equality with the independent generator run
        // proves the offline model and the JVM engine derive the SAME geometry and selection.
        assertEquals(CLASS2_SIZE, classTwo.rows.size)
        assertEquals(CLASS2_SHA256, classTwo.sha256)
        assertEquals(CLASS3_SIZE, classThree.rows.size)
        assertEquals(CLASS3_SHA256, classThree.sha256)
    }

    @Test
    fun recoveryAtThreeAfterE3b() {
        val index = realIndex ?: error("real dictionary index not loaded")
        val classOne = buildLongPressSet()
        val classTwo = buildGeometricSet()
        val classThree = buildTranspositionSet()
        val combined = classOne.rows + classTwo.rows + classThree.rows

        // Baseline: exact pass only (no table => no fuzzy). Every typo prefix carries the mistake
        // inside the prefix window, so the exact pass alone can never surface the word.
        index.updateKeyNeighbors(null)
        var baseline = 0
        for (row in combined) {
            if (index.lookup(ImmutableUtf8Prefix.copyOf(row.typoPrefixUtf8)).contains(row.word)) baseline++
        }

        // Full engine (classes #1 + #2 + #3) with the layout-derived table.
        index.updateKeyNeighbors(neighborTable)
        val recoveredPerClass = IntArray(3)
        val sets = listOf(classOne.rows, classTwo.rows, classThree.rows)
        val variantCounts = ArrayList<Int>(combined.size)
        val visitedCounts = ArrayList<Int>(combined.size)
        var overBudget = 0
        var totalRecovered = 0
        for ((classIndex, rows) in sets.withIndex()) {
            for (row in rows) {
                val results = index.lookup(ImmutableUtf8Prefix.copyOf(row.typoPrefixUtf8))
                if (index.lastFuzzyVariantCount > 0 || index.lastFuzzyVisitedCount > 0) {
                    variantCounts.add(index.lastFuzzyVariantCount)
                    visitedCounts.add(index.lastFuzzyVisitedCount)
                }
                if (index.lastFuzzyOverBudget) overBudget++
                if (results.contains(row.word)) {
                    recoveredPerClass[classIndex]++
                    totalRecovered++
                }
            }
        }

        val total = combined.size
        val recovery = totalRecovered.toDouble() / total * 100.0
        val recoveryOne = recoveredPerClass[0].toDouble() / classOne.rows.size * 100.0
        val recoveryTwo = recoveredPerClass[1].toDouble() / classTwo.rows.size * 100.0
        val recoveryThree = recoveredPerClass[2].toDouble() / classThree.rows.size * 100.0
        val threshold = THRESHOLD_MULTIPLIER * CLASS1_REFERENCE_PP
        val pass = recovery >= threshold

        val vSorted = variantCounts.sorted()
        val visSorted = visitedCounts.sorted()

        // Raw calibration line (grep target: "E3b recovery@3").
        println(
            "E3b recovery@3 seed=$SEED prefix_cp=$PREFIX_CODE_POINTS " +
                "combined_set=$total recovered=$totalRecovered recovery@3=${fmt(recovery)}% " +
                "baseline_exact_only=${fmt(baseline.toDouble() / total * 100.0)}% " +
                "class1_set=${classOne.rows.size} class1_recovery@3=${fmt(recoveryOne)}% " +
                "class2_set=${classTwo.rows.size} class2_recovery@3=${fmt(recoveryTwo)}% " +
                "class3_set=${classThree.rows.size} class3_recovery@3=${fmt(recoveryThree)}% " +
                "class1_reference=${fmt(CLASS1_REFERENCE_PP)}% " +
                "threshold=${THRESHOLD_MULTIPLIER}x=${fmt(threshold)}% verdict=${if (pass) "PASS" else "BELOW"} " +
                "variant_p50=${percentile(vSorted, 0.50)} variant_p95=${percentile(vSorted, 0.95)} " +
                "variant_max=${vSorted.lastOrNull() ?: 0} " +
                "visited_p50=${percentile(visSorted, 0.50)} visited_p95=${percentile(visSorted, 0.95)} " +
                "visited_max=${visSorted.lastOrNull() ?: 0} over_budget=$overBudget " +
                "offline_ref_variant_p95=33 offline_ref_visited_p95=133",
        )

        // Non-fudged invariants that hold irrespective of the exact recovery value.
        assertTrue("combined set is far larger than a smoke sample", total > 100_000)
        assertTrue("recovery is a fraction", recovery in 0.0..100.0)
        assertEquals("baseline with the typo inside the window is strictly zero", 0, baseline)
        assertTrue("the fuzzy budget must never trip on the typo set", overBudget == 0)
        // NO hard gate on the threshold: the phase measures and REPORTS recovery@3 and leaves the
        // verdict to the orchestrator. Lowering the threshold or tuning code/test/generator to hit
        // it is forbidden. See docs/DICTIONARY-E3.md, "E3b recovery@3 and verdict".
    }

    /**
     * Records what the E3b fuzzy engine ADDS on the 22 everyday prefixes reviewed in D1a. Real
     * prefixes (not typos): the fuzzy level fills only cells the exact pass left empty and never
     * shifts an exact candidate. The printed deltas populate docs/DICTIONARY-E3-TYPO-REVIEW.tsv.
     */
    @Test
    fun fuzzyAddedCandidatesOnTheTwentyTwoEverydayPrefixesAfterE3b() {
        val index = realIndex ?: error("real dictionary index not loaded")
        val prefixes = everydayPrefixes()
        assertEquals(22, prefixes.size)
        for (prefix in prefixes) {
            val query = ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8))
            index.updateKeyNeighbors(null)
            val exact = index.lookup(query)
            index.updateKeyNeighbors(neighborTable)
            val fuzzy = index.lookup(query)
            // The exact candidates are never shifted, replaced or removed.
            assertEquals(exact, fuzzy.take(exact.size))
            val added = fuzzy.drop(exact.size)
            println("E3b fuzzy-delta\t$prefix\texact=${exact.joinToString("|")}\tadded=${added.joinToString("|")}")
        }
    }

    private fun everydayPrefixes(): List<String> {
        val review = File("docs/archive/dictionary/DICTIONARY-D1A-QUERY-REVIEW.tsv").takeIf(File::isFile)
            ?: File("../docs/archive/dictionary/DICTIONARY-D1A-QUERY-REVIEW.tsv")
        return review.readLines(Charsets.UTF_8).drop(1)
            .filter { it.isNotBlank() }
            .map { it.split('\t')[0] }
    }

    private fun percentile(sorted: List<Int>, fraction: Double): Int {
        if (sorted.isEmpty()) return 0
        val rank = maxOf(1, ceil(sorted.size * fraction).toInt())
        return sorted[rank - 1]
    }

    private fun fmt(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)

    companion object {
        private const val SEED = 20260727L
        private const val PREFIX_CODE_POINTS = 3

        // Reproducible-set identities, produced independently by
        // `python3 scripts/typo_pack.py build --edit-class {1,2,3} ...` on the committed asset.
        // Recalibrated 2026-09-20 (TT-SUGGESTIONS P2) for the 110 000-entry dictionary:
        // 87 360 / 99 654 / 99 642 -> 96 118 / 109 649 / 109 637 rows.
        // Recalibrated 2026-09-20 (TT-TYPO-NEXT Phase B): the offline geometry model learned the
        // horizontal gap, so the class #2 set now derives from the DEVICE-true 32-pair relation
        // (the same-row pairs of the edge-to-edge model never existed on device). Classes #1 and
        // #3 do not involve geometry and are unchanged.
        private const val CLASS1_SIZE = 96_118
        private const val CLASS1_SHA256 =
            "1bf09f403a288c111a1607c83eecee3faa410ee5669015b558e292cbe28e9aee"
        private const val CLASS2_SIZE = 109_649
        private const val CLASS2_SHA256 =
            "f64f46506ec5c0be19aabc496b11da5c3ca9d875631dc25c6e1d511a87dce9d3"
        private const val CLASS3_SIZE = 109_637
        private const val CLASS3_SHA256 =
            "539a701aa80778bb62f5cb0d9a324cdbac7f611262a0eb5bc7af7fb7d6ef5a42"

        // Contract threshold (amendment 2026-07-27): recovery@3 after E3b >= 2.4x the measured
        // class #1 value (E3a: 7.2835%), i.e. >= 17.4804%. Fixed reference, never lowered.
        private const val CLASS1_REFERENCE_PP = 7.2835
        private const val THRESHOLD_MULTIPLIER = 2.4

        private val neighborTable = E3bTestFixtures.tatarNeighborTable()
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
            val rawFile = File.createTempFile("e3b-recovery-", ".tdict")
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

        private fun enumerateWords(raw: ByteArray): List<String> =
            DictionaryTestFixtures.words(raw)

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed dictionary test resource")
    }
}
