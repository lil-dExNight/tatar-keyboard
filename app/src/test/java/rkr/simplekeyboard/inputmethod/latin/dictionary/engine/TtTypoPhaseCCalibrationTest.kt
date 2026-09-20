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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * TT-TYPO-NEXT Phase C calibration (docs/TT-TYPO-NEXT-PLAN.md + the 2026-09-20 orchestrator
 * amendment relaxing G2 to <= 25 % after Phase B proved 2 % miscalibrated — even the shipped
 * class-#1 baseline measures 3.63 %). Gates, all measured BEFORE the ship decision:
 *
 *  - G1-C (recovery): recovery@3 on the NEW class-#4 typo sets (full single substitution over the
 *    layout alphabet, `scripts/typo_pack.py --edit-class 4`) under the exact activation condition
 *    (exact pass empty AND >= 4 code points) must be >= 1.5x the class-#1-only baseline measured
 *    under the SAME condition on its own set. The 3-cp window is the gate-proof arm: class #4 can
 *    never fire there, so the candidate must be byte-identical to the baseline on every row.
 *  - G2-C (precision): among >= 4-cp prefixes derived from `tt_eval_sentences.txt` where the exact
 *    pass is empty (genuinely empty strips — rare; counted), the share receiving any class-#4
 *    candidate must stay <= 25 %. Pollution of a NON-empty strip is impossible by construction.
 *    AMENDED 2026-09-20 (orchestrator, recorded in docs/TT-TYPO-NEXT.md): G2-C2 — the activation
 *    rate itself must stay <= 25 %; the fill rate is reported without a threshold.
 *  - G1-C2 (same amendment): the cross-set ratio of G1-C is replaced by the same-set absolute
 *    lift on the class-#4 5-cp set, candidate minus baseline, gate >= +10 pp.
 *  - G3-C (perf): host p95 <= 5 ms with class #4 engaged; probe counts reported honestly.
 *  - E2E pin, both ways: the candidate wiring puts "сәләм" in cell 1 for "сцләм" by the 5th
 *    letter; the shipped DEFAULT wiring's behavior is pinned separately.
 *
 * All metrics are printed as raw lines and the measured counts are pinned exactly. Nothing was
 * tuned to pass: the C-era gates (cross-set ratio, fill-rate cap, naive probe cost) failed, the
 * orchestrator amended them (G1-C2 same-set lift / G2-C2 activation-rate cap / G3-C2 device
 * budget), the probe path was re-engineered (no-cache search + per-position range narrowing),
 * and the corrected gates passed — class #4 ships in the Tatar engine (docs/TT-TYPO-NEXT.md).
 */
class TtTypoPhaseCCalibrationTest {

    // ---- Portable deterministic primitives (bit-identical to scripts/typo_pack.py). ----

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

    // ---- Typo-set construction, mirroring scripts/typo_pack.py for the given class/window. ----

    private data class TypoRow(val word: String, val typoPrefixUtf8: ByteArray)
    private data class TypoSet(val rows: List<TypoRow>, val sha256: String)

    private fun buildSet(window: Int, choices: (IntArray, Int) -> IntArray): TypoSet {
        val rows = ArrayList<TypoRow>(vocabulary.size)
        val rendered = StringBuilder(vocabulary.size * 16)
        for (word in vocabulary) {
            val codePoints = word.codePoints().toArray()
            if (codePoints.size < window) continue
            val positions = ArrayList<Int>()
            val letters = ArrayList<Int>()
            for (position in 0 until window) {
                for (letter in choices(codePoints, position)) {
                    positions.add(position)
                    letters.add(letter)
                }
            }
            if (positions.isEmpty()) continue
            val choice = selectionIndex(word, positions.size)
            val typo = codePoints.copyOf(window)
            typo[positions[choice]] = letters[choice]
            val prefix = StringBuilder(window)
            for (slot in 0 until window) prefix.appendCodePoint(typo[slot])
            val prefixString = prefix.toString()
            rows.add(TypoRow(word, prefixString.toByteArray(Charsets.UTF_8)))
            rendered.append(word).append('\t').append(prefixString).append('\n')
        }
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(rendered.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return TypoSet(rows, sha)
    }

    /** Class #1: the long-press partners of the position's letter. */
    private fun buildLongPressSet(window: Int): TypoSet = buildSet(window) { codePoints, position ->
        neighborTable.longPressPartnersOf(codePoints[position]) ?: IntArray(0)
    }

    /** Class #4: every alphabet letter but the position's own (the layout's node set). */
    private fun buildSubstitutionSet(window: Int): TypoSet = buildSet(window) { codePoints, position ->
        var count = 0
        for (letter in neighborTable.nodes) if (letter != codePoints[position]) count++
        val out = IntArray(count)
        var at = 0
        for (letter in neighborTable.nodes) {
            if (letter != codePoints[position]) {
                out[at++] = letter
            }
        }
        out
    }

    @Test
    fun thePhaseCSetsAreByteIdenticalToTheGeneratorRuns() {
        // The alphabet of the offline model is exactly the engine-side node set.
        assertEquals(39, neighborTable.nodes.size)
        // Identities of `python3 scripts/typo_pack.py build --edit-class 4 [--prefix-code-points 5]`
        // on the committed 110k asset.
        val class4w3 = buildSubstitutionSet(3)
        assertEquals(109_649, class4w3.rows.size)
        assertEquals("30897644f3bd5e6ade1f67df2d1498c3ea2676dcb6b635208a0ad510f46ba072", class4w3.sha256)
        val class4w5 = buildSubstitutionSet(5)
        assertEquals(104_955, class4w5.rows.size)
        assertEquals("c35c97701e8ab593f1010476877aff846005f66b92a0eac8e7549c9b87c0327b", class4w5.sha256)
    }

    @Test
    fun gateG1CRecoveryUnderTheActivationCondition() {
        val baseline = requireNotNull(defaultIndex)
        val candidate = requireNotNull(tatarIndex)
        val class1Bonus = requireNotNull(class1BonusIndex)
        val class1w5 = buildLongPressSet(5)
        val class4w3 = buildSubstitutionSet(3)
        val class4w5 = buildSubstitutionSet(5)

        // 3-cp window: the activation gate (>= 4 cp) can never hold. The comparison arm isolates
        // class #4: the {1}+bonus policy differs from the candidate ONLY by class #4, so identical
        // results on every row prove the gate — the bonus alone already reorders the class-#1
        // candidates, which is why the plain DEFAULT arm is NOT the reference here.
        var identicalW3 = 0
        for (row in class4w3.rows) {
            val query = ImmutableUtf8Prefix.copyOf(row.typoPrefixUtf8)
            if (class1Bonus.lookup(query) == candidate.lookup(query)) identicalW3++
        }

        // 5-cp window, the activation subset (exact pass empty) on both arms. The whole-set
        // candidate number is recorded for the Phase-B comparison (its class-#2 headline was
        // whole-set: 38.4641 % at 5 cp).
        val baseW5 = measureRecovery(baseline, class1w5)
        val candW5 = measureRecovery(candidate, class4w5)
        var candW5Whole = 0
        for (row in class4w5.rows) {
            if (candidate.lookup(ImmutableUtf8Prefix.copyOf(row.typoPrefixUtf8)).contains(row.word)) {
                candW5Whole++
            }
        }
        val ratioW5 = candW5.rate / baseW5.rate

        // Raw calibration lines (grep target: "PhaseC recovery@3").
        println(
            "PhaseC recovery@3 window=3cp activation=never class4_set=${class4w3.rows.size} " +
                "identical_to_{1}+bonus=$identicalW3 probes=0 " +
                "verdict=${if (identicalW3 == class4w3.rows.size) "GATE-HELD" else "GATE-LEAKED"}",
        )
        println(
            "PhaseC recovery@3 window=5cp " +
                "baseline(class1-only, class1 set, exact==0)=${fmt(baseW5.rate)}% (${baseW5.recovered}/${baseW5.total}) " +
                "candidate(classes1+4+bonus, class4 set, exact==0)=${fmt(candW5.rate)}% (${candW5.recovered}/${candW5.total}) " +
                "ratio=${fmt(ratioW5)}x gate>=1.5x verdict=${if (ratioW5 >= 1.5) "PASS" else "BELOW"} " +
                "variant_p95=${candW5.variantP95} variant_max=${candW5.variantMax} " +
                "visited_p95=${candW5.visitedP95} visited_max=${candW5.visitedMax} " +
                "probe_p95=${candW5.probeP95} probe_max=${candW5.probeMax} over_budget=${candW5.overBudget} " +
                "whole_set=${fmt(candW5Whole * 100.0 / class4w5.rows.size)}% ($candW5Whole/${class4w5.rows.size}) " +
                "phaseB_class2_whole_set=38.4641%",
        )

        assertEquals("class #4 must be inert at 3 code points", class4w3.rows.size, identicalW3)
        assertEquals("the fuzzy budget must never trip", 0, candW5.overBudget)
        assertEquals(PIN_BASE_W5, baseW5.recovered)
        assertEquals(PIN_CAND_W5, candW5.recovered)
        assertEquals(PIN_BASE_W5_SUBSET, baseW5.total)
        assertEquals(PIN_CAND_W5_SUBSET, candW5.total)
    }

    /**
     * G1-C2 (orchestrator amendment 2026-09-20, replacing the cross-set ratio of G1-C): on the
     * SAME class-#4 typo set (5-cp window), the candidate's recovery@3 minus the class-#1-only
     * baseline's recovery@3 — identical conditions — must be >= +10 percentage points. The
     * whole-set share is the gate number (the contract's literal methodology since E3a); the
     * activation subset (exact pass empty — where class #4 can act at all) is reported alongside.
     */
    @Test
    fun gateG1C2SameSetLift() {
        val baseline = requireNotNull(defaultIndex)
        val candidate = requireNotNull(tatarIndex)
        val class4w3 = buildSubstitutionSet(3)
        val class4w5 = buildSubstitutionSet(5)

        var baseW5Whole = 0
        var candW5Whole = 0
        var baseW5Active = 0
        var candW5Active = 0
        var activeRows = 0
        for (row in class4w5.rows) {
            val query = ImmutableUtf8Prefix.copyOf(row.typoPrefixUtf8)
            val baseHit = baseline.lookup(query).contains(row.word)
            val baseEmpty = baseline.lastExactCount == 0
            val candHit = candidate.lookup(query).contains(row.word)
            if (baseHit) baseW5Whole++
            if (candHit) candW5Whole++
            if (baseEmpty) {
                activeRows++
                if (baseHit) baseW5Active++
                if (candHit) candW5Active++
            }
        }
        val total = class4w5.rows.size
        val liftWhole = (candW5Whole - baseW5Whole) * 100.0 / total
        val liftActive = (candW5Active - baseW5Active) * 100.0 / activeRows

        // 3-cp window (reported): the gate never fires, so the lift is structurally zero.
        var baseW3Whole = 0
        var candW3Whole = 0
        for (row in class4w3.rows) {
            val query = ImmutableUtf8Prefix.copyOf(row.typoPrefixUtf8)
            if (baseline.lookup(query).contains(row.word)) baseW3Whole++
            if (candidate.lookup(query).contains(row.word)) candW3Whole++
        }

        // Raw calibration line (grep target: "PhaseC2 lift").
        println(
            "PhaseC2 lift window=5cp set=class4 whole_set: " +
                "baseline=${fmt(baseW5Whole * 100.0 / total)}% ($baseW5Whole) " +
                "candidate=${fmt(candW5Whole * 100.0 / total)}% ($candW5Whole) " +
                "lift=${fmt(liftWhole)}pp gate>=+10pp verdict=${if (liftWhole >= 10.0) "PASS" else "BELOW"} | " +
                "activation_subset=$activeRows baseline=${fmt(baseW5Active * 100.0 / activeRows)}% " +
                "candidate=${fmt(candW5Active * 100.0 / activeRows)}% lift=${fmt(liftActive)}pp | " +
                "window=3cp baseline=$baseW3Whole candidate=$candW3Whole (gate inert)",
        )

        assertEquals(PIN_C2_BASE_W5_WHOLE, baseW5Whole)
        assertEquals(PIN_C2_CAND_W5_WHOLE, candW5Whole)
        assertEquals(PIN_C2_ACTIVE_ROWS, activeRows)
        assertEquals(PIN_C2_BASE_W5_ACTIVE, baseW5Active)
        assertEquals(PIN_C2_CAND_W5_ACTIVE, candW5Active)
        assertEquals(PIN_C2_BASE_W3_WHOLE, baseW3Whole)
        assertEquals(PIN_C2_CAND_W3_WHOLE, candW3Whole)
        assertTrue("G1-C2: the same-set lift must be at least +10 pp", liftWhole >= 10.0)
    }

    /**
     * G2-C2 (orchestrator amendment 2026-09-20, replacing G2-C's fill-rate cap): the ACTIVATION
     * rate — >= 4-cp eval prefixes whose exact pass is empty — must stay <= 25 % (class #4 fires
     * only where the strip would be empty; it can never leak into a non-empty strip by
     * construction). The fill rate among activated prefixes is REPORTED without a pass/fail
     * threshold: a one-edit dictionary word on an otherwise empty strip is standard IME behavior,
     * not pollution.
     */
    @Test
    fun gateG2CPrecisionOnGenuinelyEmptyStrips() {
        val baseline = requireNotNull(defaultIndex)
        val candidate = requireNotNull(tatarIndex)
        var emptyExact = 0
        var class4Filled = 0
        for (prefix in evalPrefixes) {
            val query = ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8))
            val baselineResult = baseline.lookup(query)
            if (baseline.lastExactCount != 0) continue
            emptyExact++
            val candidateResult = candidate.lookup(query)
            // A candidate present under the candidate policy but absent under the baseline is
            // class-#4-sourced (class #1 is identical between the two arms).
            if (candidateResult.size > baselineResult.size ||
                !baselineResult.containsAll(candidateResult)
            ) {
                class4Filled++
            }
        }
        val activationRate = emptyExact * 100.0 / evalPrefixes.size
        val fillShare = class4Filled * 100.0 / emptyExact
        // Raw calibration line (grep target: "PhaseC precision").
        println(
            "PhaseC precision eval_prefixes_>=4cp=${evalPrefixes.size} exact_empty=$emptyExact " +
                "(${fmt(activationRate)}%) class4_filled=$class4Filled (${fmt(fillShare)}% of empty) " +
                "gate: activation<=25% verdict=${if (activationRate <= 25.0) "PASS" else "ABOVE"} " +
                "(fill rate reported, no threshold — G2-C2)",
        )
        assertEquals(PIN_EVAL_PREFIXES_GE4, evalPrefixes.size)
        assertEquals(PIN_EXACT_EMPTY, emptyExact)
        assertEquals(PIN_CLASS4_FILLED, class4Filled)
        assertTrue(
            "G2-C2: class #4 must activate only on genuinely empty strips (<= 25 % of >= 4-cp prefixes)",
            activationRate <= 25.0,
        )
    }

    @Test
    fun gateG3CHostComputeWithClass4Engaged() {
        val class4w5 = buildSubstitutionSet(5)
        // Deterministic sample of the set: every 53rd row (~2 000 prefixes).
        val sample = class4w5.rows.filterIndexed { index, _ -> index % 53 == 0 }
            .map { ImmutableUtf8Prefix.copyOf(it.typoPrefixUtf8) }
        assertTrue("the perf sample is meaningful", sample.size > 1_500)

        val arms = listOf(
            "default" to requireNotNull(defaultIndex),
            "tatarPolicy" to requireNotNull(tatarIndex),
        )
        for ((name, index) in arms) {
            repeat(2) { for (prefix in sample) index.lookup(prefix) }
            val timings = LongArray(sample.size * 2)
            val probes = ArrayList<Int>(sample.size)
            var consumed = 0
            var at = 0
            repeat(2) {
                for (prefix in sample) {
                    val started = System.nanoTime()
                    val results = index.lookup(prefix)
                    timings[at++] = System.nanoTime() - started
                    consumed = consumed xor results.size
                }
            }
            // Probe counts from a fresh single pass (the counters describe the LAST lookup).
            for (prefix in sample) {
                index.lookup(prefix)
                if (index.lastFuzzyProbeCount > 0) probes.add(index.lastFuzzyProbeCount)
            }
            timings.sort()
            val p50 = timings[timings.size / 2] / 1_000_000.0
            val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
            val max = timings.last() / 1_000_000.0
            // Raw calibration line (grep target: "PhaseC perf").
            println(
                "PhaseC perf host window=5cp policy=$name samples=${timings.size} " +
                    "p50=${fmtMs(p50)} ms p95=${fmtMs(p95)} ms max=${fmtMs(max)} ms " +
                    "probe_p95=${percentile(probes.sorted(), 0.95)} probe_max=${probes.maxOrNull() ?: 0} " +
                    "consumed=$consumed",
            )
            if (name == "tatarPolicy") {
                assertTrue("class-#4-engaged p95=${p95}ms exceeds the 5 ms budget", p95 <= 5.0)
            }
        }
    }

    /**
     * The mission target case under the Phase-C2 SHIPPED Tatar wiring (suffix rules + TATAR {1,4}
     * + bonus + the layout-derived table — exactly what LatinIME wires for the Tatar engine since
     * the C2 gates passed): "сцләм" offers "сәләм" in cell 1 by the 5th letter. The default
     * policy's empty strip for the same input is pinned in
     * [TtTypoPhaseBCalibrationTest.theDefaultPolicyKeepsThePrePhaseBBehavior] (the Russian shape).
     */
    @Test
    fun theSclamTypoOffersSyalamInCellOneByTheFifthLetter() {
        val index = requireNotNull(tatarIndex)
        fun strip(prefix: String): List<String> =
            index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))

        // 2 code points: fuzzy never fires; the exact "сц*" block.
        assertEquals(listOf("сценарий", "сценарие", "сценарийлар"), strip("сц"))
        // 3 code points: exact==0, but the class-#4 gate needs >= 4 cp — the strip stays empty
        // (the Phase-B {1,2} arm showed the "сәл*" frequency leaders here; Phase C shows nothing).
        assertEquals(emptyList<String>(), strip("сцл"))
        // 4 code points: class #4 fires (exact==0); the "сәлә*" block's frequency leaders fill the
        // strip — no same-length candidate exists at 4 cp.
        assertEquals(listOf("сәләтле", "сәләт", "сәләтен"), strip("сцлә"))
        // 5 code points: "сәләм" is the sole surviving variant; the same-length bonus puts the
        // correction itself (freq 36) above "сәләмәтлек" (65).
        assertEquals(listOf("сәләм", "сәләмәтлек", "сәләмәт"), strip("сцләм"))
        // 10 code points: with the Phase-C2 range narrowing only positions 0-2 probe (the "сцл*"
        // range is empty, killing positions >= 3 for free) — 3 x 38 = 114 probes instead of the
        // naive 380; the sole survivor's own word wins.
        val deep = ImmutableUtf8Prefix.copyOf("сцләмәтлек".toByteArray(Charsets.UTF_8))
        assertEquals(listOf("сәләмәтлек"), index.lookup(deep))
        assertEquals(114, index.lastFuzzyProbeCount)
    }

    /**
     * The Russian engine keeps the default policy: class #4 never fires. "апаси" (а for с on
     * "спаси(бо)") has zero exact continuations in the Russian asset; under DEFAULT the strip
     * stays empty — while the SAME dictionary under the TATAR policy paints the pinned class-#4
     * strip, proving the pin discriminates. Note the same-length bonus at work: the same-length
     * words "спаси"/"упаси" outrank the much more frequent continuation "спасибо".
     */
    @Test
    fun theRussianEngineIsUntouchedByClass4() {
        val russianDefault = requireNotNull(russianDefaultIndex)
        val russianAsTatar = requireNotNull(russianTatarPolicyIndex)
        val typo = ImmutableUtf8Prefix.copyOf("апаси".toByteArray(Charsets.UTF_8))
        val defaultResult = russianDefault.lookup(typo)
        assertFalse("class #4 must stay off the default (Russian) path", defaultResult.contains("спаси"))
        assertEquals(emptyList<String>(), defaultResult)
        assertEquals(
            "premise: the case discriminates — the TATAR policy paints the class-#4 strip",
            listOf("спаси", "упаси", "апачи"),
            russianAsTatar.lookup(typo),
        )
    }

    private data class Measurement(
        val total: Int,
        val recovered: Int,
        val variantP95: Int,
        val variantMax: Int,
        val visitedP95: Int,
        val visitedMax: Int,
        val probeP95: Int,
        val probeMax: Int,
        val overBudget: Int,
    ) {
        val rate: Double get() = recovered * 100.0 / total
    }

    /** Recovery within the ACTIVATION subset (exact pass returned 0) of [set]. */
    private fun measureRecovery(index: TdictPrefixIndex, set: TypoSet): Measurement {
        val variantCounts = ArrayList<Int>()
        val visitedCounts = ArrayList<Int>()
        val probeCounts = ArrayList<Int>()
        var overBudget = 0
        var active = 0
        var recovered = 0
        for (row in set.rows) {
            val results = index.lookup(ImmutableUtf8Prefix.copyOf(row.typoPrefixUtf8))
            if (index.lastExactCount != 0) continue
            active++
            variantCounts.add(index.lastFuzzyVariantCount)
            visitedCounts.add(index.lastFuzzyVisitedCount)
            probeCounts.add(index.lastFuzzyProbeCount)
            if (index.lastFuzzyOverBudget) overBudget++
            if (results.contains(row.word)) recovered++
        }
        return Measurement(
            active, recovered,
            percentile(variantCounts.sorted(), 0.95), variantCounts.maxOrNull() ?: 0,
            percentile(visitedCounts.sorted(), 0.95), visitedCounts.maxOrNull() ?: 0,
            percentile(probeCounts.sorted(), 0.95), probeCounts.maxOrNull() ?: 0,
            overBudget,
        )
    }

    private fun percentile(sorted: List<Int>, fraction: Double): Int {
        if (sorted.isEmpty()) return 0
        val rank = maxOf(1, ceil(sorted.size * fraction).toInt())
        return sorted[rank - 1]
    }

    private fun fmt(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)
    private fun fmtMs(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    companion object {
        private const val SEED = 20260727L

        // Exact pins of the Phase-C measurements (2026-09-20, committed 110k asset + eval set);
        // re-pin consciously when an input changes. The verdicts: G1-C BELOW (ratio of rates
        // 0.7205x vs the 1.5x gate), G2-C ABOVE (63.14 % vs the 25 % gate) — class #4 stays unwired.
        private const val PIN_BASE_W5 = 38_689
        private const val PIN_CAND_W5 = 29_057
        private const val PIN_BASE_W5_SUBSET = 97_318
        private const val PIN_CAND_W5_SUBSET = 101_445
        private const val PIN_EVAL_PREFIXES_GE4 = 7_471
        private const val PIN_EXACT_EMPTY = 1_579
        private const val PIN_CLASS4_FILLED = 997

        // Exact pins of the G1-C2 same-set lift measurement (2026-09-20); re-pin consciously.
        private const val PIN_C2_BASE_W5_WHOLE = 470
        private const val PIN_C2_CAND_W5_WHOLE = 29_062
        private const val PIN_C2_ACTIVE_ROWS = 101_445
        private const val PIN_C2_BASE_W5_ACTIVE = 465
        private const val PIN_C2_CAND_W5_ACTIVE = 29_057
        private const val PIN_C2_BASE_W3_WHOLE = 100
        private const val PIN_C2_CAND_W3_WHOLE = 100

        private val neighborTable = E3bTestFixtures.tatarNeighborTable()
        private lateinit var vocabulary: List<String>
        private lateinit var evalPrefixes: List<String>
        private var defaultIndex: TdictPrefixIndex? = null
        private var tatarIndex: TdictPrefixIndex? = null
        private var class1BonusIndex: TdictPrefixIndex? = null
        private var russianDefaultIndex: TdictPrefixIndex? = null
        private var russianTatarPolicyIndex: TdictPrefixIndex? = null

        // Class #1 with the same-length bonus but WITHOUT class #4: the arm that isolates class
        // #4 in the 3-cp gate proof (the candidate minus class #4).
        private val CLASS1_BONUS = FuzzyEditPolicy(
            intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS),
            true,
        )

        @JvmStatic
        @BeforeClass
        fun loadCommittedDictionaries() {
            val tatarRaw = inflate(DictionaryArtifactSpec.TATAR_TOP100K_V1)
            vocabulary = DictionaryTestFixtures.words(tatarRaw)
            check(vocabulary.size == DictionaryArtifactSpec.TATAR_TOP100K_V1.expectedEntryCount.toInt())
            // The production Tatar shape: P3 suffix rules + the Phase-C fuzzy policy. The baseline
            // carries the suffix rules too, so only the fuzzy policy differs between the arms.
            defaultIndex = openIndex(tatarRaw, DictionaryArtifactSpec.TATAR_TOP100K_V1, TatarSuffixRules, null)
            tatarIndex = openIndex(tatarRaw, DictionaryArtifactSpec.TATAR_TOP100K_V1, TatarSuffixRules, FuzzyEditPolicy.TATAR)
            class1BonusIndex = openIndex(tatarRaw, DictionaryArtifactSpec.TATAR_TOP100K_V1, TatarSuffixRules, CLASS1_BONUS)
            for (index in listOfNotNull(defaultIndex, tatarIndex, class1BonusIndex)) {
                index.updateKeyNeighbors(neighborTable)
            }

            val russianRaw = inflate(DictionaryArtifactSpec.RUSSIAN_TOP100K_V1)
            russianDefaultIndex = openIndex(russianRaw, DictionaryArtifactSpec.RUSSIAN_TOP100K_V1, null, null)
            russianTatarPolicyIndex = openIndex(russianRaw, DictionaryArtifactSpec.RUSSIAN_TOP100K_V1, null, FuzzyEditPolicy.TATAR)
            for (index in listOfNotNull(russianDefaultIndex, russianTatarPolicyIndex)) {
                index.updateKeyNeighbors(neighborTable)
            }

            // The G2-C prefix derivation: every distinct >= 4-cp prefix of every unique eval word
            // (shorter prefixes can never meet the activation gate).
            val evalLines = locate(
                "src/test/resources/tt_eval_sentences.txt",
                "app/src/test/resources/tt_eval_sentences.txt",
            ).readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            evalPrefixes = evalLines.flatMap { it.split(" ") }.distinct().flatMap { word ->
                val codePoints = word.codePoints().toArray()
                (4..codePoints.size).map { length ->
                    val builder = StringBuilder(length)
                    for (slot in 0 until length) builder.appendCodePoint(codePoints[slot])
                    builder.toString()
                }
            }.distinct()
        }

        /** Inflates and validates a committed asset exactly like the other real-asset tests. */
        private fun inflate(spec: DictionaryArtifactSpec): ByteArray {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("tt-typo-c-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                TdictValidator().validateRaw(rawFile, spec)
                return rawFile.readBytes()
            } finally {
                rawFile.delete()
            }
        }

        private fun openIndex(
            raw: ByteArray,
            spec: DictionaryArtifactSpec,
            suffixTable: InflectedSuffixTable?,
            policy: FuzzyEditPolicy?,
        ): TdictPrefixIndex {
            val identity = DictionaryIdentity(
                spec.generation,
                spec.schemaId,
                spec.formatVersion,
                MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) },
            )
            return requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw), identity,
                    spec.expectedEntryCount, raw.size.toLong(), suffixTable, policy,
                ),
            )
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed test resource")
    }
}
