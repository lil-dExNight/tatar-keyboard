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
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * ROADMAP-P4 P6 calibration (docs/ROADMAP-P4.md, Batch B gates G1–G3 written 2026-09-23 BEFORE
 * any code): edit class #5 — TWO substitutions at distinct positions, chained-probe enumeration,
 * plausibility ranking key.
 *
 * Arms, differing ONLY in whether class #5 is enabled — the display configuration of both is the
 * shipped Tatar one ({1, 4} + the same-length bonus):
 *  - CURRENT: {1, 4} (the shipped Tatar configuration, pinned as an explicit policy so the
 *    calibration keeps comparing the same two arms);
 *  - CANDIDATE: {1, 4, 5}.
 *
 * VERDICT (2026-09-23): **NO SHIP — G1 fails below the theoretical ceiling.** The gate demanded
 * +10 pp recovery lift on the activation subset; the PERFECT-RECALL ceiling under the engine
 * ranking is 9.79 % (see [gateG1DiagnosticRankingCeilingUnderPerfectRecall] — 113.6 average
 * edit-2 competitors per row, the original word's median rank 28), and the frequency-only
 * ceiling is 10.26 % — the +10 pp gate is unreachable even with infinite probing budget, and a
 * frequency-dominant ranking that would marginal-open it is vetoed by the operator's case
 * (салым 7 466 would outrank сәләм 36). The measured candidate recovery is 0: the chained
 * probe-per-letter enumeration trips the fail-closed probe budget on 73 % of firing rows
 * (fertile prefixes die whole — the сэлэм case returns an empty strip). The class stays UNWIRED
 * (FuzzyEditPolicy.TATAR keeps {1, 4}); the machinery, the calibration and these numbers stand
 * as the documented evidence. All gate thresholds are printed as verdicts, not asserted; what
 * IS asserted is structural (set identity, the class-#5 activation subset, the probe budget).
 */
class TwoSubstitutionCalibrationTest {

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

    private data class TypoRow(val word: String, val typoPrefixUtf8: ByteArray)
    private data class TypoSet(val rows: List<TypoRow>, val sha256: String)

    /**
     * The edit-2 typo set, mirroring `scripts/typo_pack.py --edit-class 5`: one chained
     * SplitMix64 stream per word picks the (i < j) pair index, then the replacement indices of
     * each position's alphabet-minus-own list.
     */
    private fun buildTwoSubstitutionSet(window: Int): TypoSet {
        val rows = ArrayList<TypoRow>(vocabulary.size)
        val rendered = StringBuilder(vocabulary.size * 16)
        for (word in vocabulary) {
            val codePoints = word.codePoints().toArray()
            if (codePoints.size < window) continue
            val pairCount = window * (window - 1) / 2
            if (pairCount == 0) continue
            var mixed = splitmix64(SEED xor fnv1a64(word.toByteArray(Charsets.UTF_8)))
            val pairIndex = java.lang.Long.remainderUnsigned(mixed, pairCount.toLong()).toInt()
            mixed = splitmix64(mixed)
            val xIndex = java.lang.Long.remainderUnsigned(mixed, 38L).toInt()
            mixed = splitmix64(mixed)
            val yIndex = java.lang.Long.remainderUnsigned(mixed, 38L).toInt()
            var i = 0
            var k = pairIndex
            while (k >= window - 1 - i) {
                k -= window - 1 - i
                i++
            }
            val j = i + 1 + k
            val xChoices = neighborTable.nodes.filter { it != codePoints[i] }
            val yChoices = neighborTable.nodes.filter { it != codePoints[j] }
            val typo = codePoints.copyOf(window)
            typo[i] = xChoices[xIndex]
            typo[j] = yChoices[yIndex]
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

    /**
     * Set identity: the JVM mirror of `python3 scripts/typo_pack.py build --edit-class 5
     * [--prefix-code-points 3]` must be byte-identical to the generator runs on the committed
     * 110k asset (sizes + SHA-256 of the `word<TAB>typo\n` render) — the same pin every other
     * calibration set carries.
     */
    @Test
    fun theP6SetsAreByteIdenticalToTheGeneratorRuns() {
        val set3 = buildTwoSubstitutionSet(3)
        assertEquals(109_649, set3.rows.size)
        assertEquals("d955151e1e00fed3e85e88f6a2187f431ab9bc5eebc932cde1041c6ee6775899", set3.sha256)
        val set5 = buildTwoSubstitutionSet(5)
        assertEquals(104_955, set5.rows.size)
        assertEquals("03159a49d645026653cf6da9a6b982a3dac7d6f77b90a1ad96b012298520fb68", set5.sha256)
    }

    /**
     * G1 diagnostic — the RANKING CEILING under perfect recall. The engine's measured recovery
     * is bounded by what the ranking lets through: for a deterministic sample of activation
     * rows, every edit-2 competitor is enumerated BRUTE FORCE over the vocabulary (words whose
     * first 5 code points sit at Hamming distance exactly 2 from the typo), then ranked three
     * ways — (a) the engine key (same-length, then plausibility, then frequency), (b) without
     * the same-length bonus, (c) frequency only — and the original word's top-3 membership is
     * counted for each. Printed, not gated: when G1 runs BELOW, this is the number that
     * separates "the enumeration misses" from "the ranking cannot surface it".
     */
    @Test
    fun gateG1DiagnosticRankingCeilingUnderPerfectRecall() {
        val current = requireNotNull(currentIndex)
        val set5 = buildTwoSubstitutionSet(5)
        val words = vocabulary
        val cps = vocabCodePoints
        val freqs = vocabFrequencies

        var sampled = 0
        var hitEngine = 0
        var hitNoBonus = 0
        var hitFrequency = 0
        var competitorTotal = 0L
        val engineRanks = ArrayList<Int>()
        for (rowIndex in set5.rows.indices step 19) {
            val row = set5.rows[rowIndex]
            val query = ImmutableUtf8Prefix.copyOf(row.typoPrefixUtf8)
            val results = current.lookup(query)
            if (current.lastExactCount != 0 || results.isNotEmpty()) continue
            sampled++
            val typoCp = String(row.typoPrefixUtf8, Charsets.UTF_8).codePoints().toArray()
            val ranked = ArrayList<Long>(64)
            for (w in words.indices) {
                val wcp = cps[w]
                if (wcp.size < 5) continue
                var diff = 0
                var di = -1
                var dj = -1
                for (p in 0 until 5) {
                    if (wcp[p] != typoCp[p]) {
                        diff++
                        if (diff > 2) break
                        if (di < 0) di = p else dj = p
                    }
                }
                if (diff != 2) continue
                val plausibility =
                    (if (attested(typoCp[di], wcp[di])) 1 else 0) +
                        (if (attested(typoCp[dj], wcp[dj])) 1 else 0)
                val sameLength = if (wcp.size == 5) 1 else 0
                // One packed long: the comparator fields (pl, sl, freq, index-as-word-order).
                ranked.add(
                    (plausibility.toLong() shl 57) or (sameLength.toLong() shl 56) or
                        (minOf(freqs[w], 0xFFFFFFL) shl 32) or w.toLong(),
                )
            }
            val originalIndex = requireNotNull(vocabIndex[row.word])
            val byEngine = ranked.sortedWith(
                compareByDescending<Long> { (it ushr 56) and 0x1 }  // same-length first
                    .thenByDescending { (it ushr 57) and 0x7 }     // then plausibility
                    .thenByDescending { (it ushr 32) and 0xFFFFFF } // then frequency
                    .thenBy { (it and 0xFFFFFFFFL).toInt() },       // then dictionary order
            ).map { (it and 0xFFFFFFFFL).toInt() }
            val engineRank = byEngine.indexOf(originalIndex)
            if (engineRank in 0..2) hitEngine++
            engineRanks.add(engineRank)
            val byNoBonus = ranked.sortedWith(
                compareByDescending<Long> { (it ushr 57) and 0x7 }
                    .thenByDescending { (it ushr 32) and 0xFFFFFF }
                    .thenBy { (it and 0xFFFFFFFFL).toInt() },
            ).map { (it and 0xFFFFFFFFL).toInt() }
            if (byNoBonus.indexOf(originalIndex) in 0..2) hitNoBonus++
            val byFrequency = ranked.sortedWith(
                compareByDescending<Long> { (it ushr 32) and 0xFFFFFF }
                    .thenBy { (it and 0xFFFFFFFFL).toInt() },
            ).map { (it and 0xFFFFFFFFL).toInt() }
            if (byFrequency.indexOf(originalIndex) in 0..2) hitFrequency++
            competitorTotal += ranked.size - 1
        }
        engineRanks.sort()
        println(
            "P6 G1-DIAG sampled=$sampled competitors_avg=${fmt(competitorTotal * 1.0 / sampled)} " +
                "ceiling_engine=${fmt(hitEngine * 100.0 / sampled)}% ($hitEngine) " +
                "ceiling_no_same_length_bonus=${fmt(hitNoBonus * 100.0 / sampled)}% ($hitNoBonus) " +
                "ceiling_frequency_only=${fmt(hitFrequency * 100.0 / sampled)}% ($hitFrequency) " +
                "original_engine_rank_p50=${engineRanks[engineRanks.size / 2]} " +
                "p95=${engineRanks[(engineRanks.size * 95) / 100]}",
        )
        assertTrue("the diagnostic sample is meaningful", sampled > 1_000)
    }

    /** Mirror of the engine's attested-confusion check (sorted long-press partner list). */
    private fun attested(a: Int, b: Int): Boolean {
        val partners = neighborTable.longPressPartnersOf(a) ?: return false
        return java.util.Arrays.binarySearch(partners, b) >= 0
    }

    /**
     * G1 — recovery@3 on the edit-2 set. The gate number is the ACTIVATION subset: rows whose
     * strip would be EMPTY without class #5 (CURRENT exact==0 AND no class-#1/#4 cell — exactly
     * the class-#5 firing condition). CURRENT's recovery there is 0 by construction, so the lift
     * IS the candidate's recovery on that subset; the whole-set numbers are reported for
     * context. The +10 pp verdict is printed, not asserted (see the class docstring).
     */
    @Test
    fun gateG1RecoveryOnTheEdit2Set() {
        val current = requireNotNull(currentIndex)
        val candidate = requireNotNull(candidateIndex)
        val set5 = buildTwoSubstitutionSet(5)
        val set3 = buildTwoSubstitutionSet(3)

        // 3-cp window: the >= 4 cp floor makes class #5 structurally inert — CANDIDATE and
        // CURRENT must agree on every row.
        var identical3 = 0
        for (row in set3.rows) {
            val query = ImmutableUtf8Prefix.copyOf(row.typoPrefixUtf8)
            if (current.lookup(query) == candidate.lookup(query)) identical3++
        }

        var wholeCurrent = 0
        var wholeCandidate = 0
        var active = 0
        var activeCurrent = 0
        var activeCandidate = 0
        for (row in set5.rows) {
            val query = ImmutableUtf8Prefix.copyOf(row.typoPrefixUtf8)
            val currentResults = current.lookup(query)
            val currentHit = currentResults.contains(row.word)
            val stripWouldBeEmpty = current.lastExactCount == 0 && currentResults.isEmpty()
            val candidateHit = candidate.lookup(query).contains(row.word)
            if (currentHit) wholeCurrent++
            if (candidateHit) wholeCandidate++
            if (stripWouldBeEmpty) {
                active++
                if (currentHit) activeCurrent++
                if (candidateHit) activeCandidate++
            }
        }
        val total = set5.rows.size
        val rateWholeCurrent = wholeCurrent * 100.0 / total
        val rateWholeCandidate = wholeCandidate * 100.0 / total
        val rateActiveCandidate = activeCandidate * 100.0 / active
        val liftActive = rateActiveCandidate - activeCurrent * 100.0 / active

        println(
            "P6 G1 window=3cp set=${set3.rows.size} identical=$identical3 (gate inert) | " +
                "window=5cp set=$total whole_set: current=${fmt(rateWholeCurrent)}% ($wholeCurrent) " +
                "candidate=${fmt(rateWholeCandidate)}% ($wholeCandidate) | " +
                "activation_subset=$active current=$activeCurrent " +
                "candidate=${fmt(rateActiveCandidate)}% ($activeCandidate) " +
                "lift=${fmt(liftActive)}pp gate>=+10pp verdict=${if (liftActive >= 10.0) "PASS" else "BELOW"}",
        )

        assertEquals("class #5 must be inert at 3 code points", set3.rows.size, identical3)
        assertEquals(PIN_G1_WHOLE_CURRENT, wholeCurrent)
        assertEquals(PIN_G1_WHOLE_CANDIDATE, wholeCandidate)
        assertEquals(PIN_G1_ACTIVE, active)
        assertEquals("the activation subset is empty-CURRENT by construction", 0, activeCurrent)
        assertEquals(PIN_G1_ACTIVE_CANDIDATE, activeCandidate)
    }

    /**
     * G2 — precision, structural: class #5 fires ONLY when exact==0 AND classes #1/#4 produced
     * nothing. "Filled" counts empty-strip prefixes where class #5 issued at least one probe (a
     * budget trip still counts as probed — whether results survive the trip is the G1/G3 story);
     * pollution of a non-empty strip is impossible by construction and pinned (a prefix with any
     * earlier candidate shows zero edit-#5 probes).
     */
    @Test
    fun gateG2ActivationIsExactlyTheEmptyStrip() {
        val current = requireNotNull(currentIndex)
        val candidate = requireNotNull(candidateIndex)
        var activationSubset = 0
        var filled = 0
        for (word in uniqueEvalWords) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            if (bytes.size < 8) continue  // < 4 Cyrillic code points
            val query = ImmutableUtf8Prefix.copyOf(bytes)
            // The activation condition is defined WITHOUT class #5 (the CURRENT arm): exact pass
            // empty AND no class-#1/#4 candidate — the strip would be empty.
            val currentResults = current.lookup(query)
            val stripWouldBeEmpty = current.lastExactCount == 0 && currentResults.isEmpty()
            candidate.lookup(query)
            if (stripWouldBeEmpty) {
                activationSubset++
                if (candidate.lastEdit2ProbeCount > 0) filled++
            } else {
                // The structural pin: whenever ANY earlier class produced a cell, class #5
                // issued no probe at all — pollution of a non-empty strip is impossible.
                assertEquals(
                    "class #5 must not probe a non-empty strip ($word)",
                    0, candidate.lastEdit2ProbeCount,
                )
            }
        }
        val fillPct = filled * 100.0 / activationSubset
        println(
            "P6 G2 eval_words=${uniqueEvalWords.size} activation_subset=$activationSubset " +
                "edit5_filled=$filled (${fmt(fillPct)}% of empty strips)",
        )
        assertEquals(PIN_G2_ACTIVATION_SUBSET, activationSubset)
        assertEquals(PIN_G2_FILLED, filled)
    }

    /**
     * G3 — host perf with class #5 engaged: a deterministic sample of the edit-2 5-cp set (the
     * firing-path workload), both arms; probe-count p95/max and the fail-closed budget trip count.
     */
    @Test
    fun gateG3HostComputeWithClass5Engaged() {
        val set5 = buildTwoSubstitutionSet(5)
        val sample = set5.rows.filterIndexed { index, _ -> index % 53 == 0 }
            .map { ImmutableUtf8Prefix.copyOf(it.typoPrefixUtf8) }
        assertTrue("the perf sample is meaningful", sample.size > 1_500)

        val arms = listOf(
            "current" to requireNotNull(currentIndex),
            "candidate" to requireNotNull(candidateIndex),
        )
        for ((name, index) in arms) {
            repeat(2) { for (prefix in sample) index.lookup(prefix) }
            val timings = LongArray(sample.size * 2)
            val probes = ArrayList<Int>(sample.size)
            var trips = 0
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
            for (prefix in sample) {
                index.lookup(prefix)
                if (index.lastEdit2ProbeCount > 0) probes.add(index.lastEdit2ProbeCount)
                if (index.lastFuzzyOverBudget) trips++
            }
            timings.sort()
            val p50 = timings[timings.size / 2] / 1_000_000.0
            val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
            val max = timings.last() / 1_000_000.0
            println(
                "P6 G3 host policy=$name samples=${timings.size} p50=${fmtMs(p50)} ms " +
                    "p95=${fmtMs(p95)} ms max=${fmtMs(max)} ms " +
                    "edit2probe_p95=${percentile(probes.sorted(), 0.95)} " +
                    "edit2probe_max=${probes.maxOrNull() ?: 0} budget_trips=$trips consumed=$consumed " +
                    "gate: candidate p95<=5ms verdict=${if (name != "candidate" || p95 <= 5.0) "PASS" else "ABOVE"}",
            )
            // The only HARD bound: the fail-closed probe budget itself (the engine enforces it;
            // this proves the accounting). The 5 ms host threshold is a printed verdict.
            assertTrue(
                "edit-2 probes are bounded by the budget",
                (probes.maxOrNull() ?: 0) <= TdictPrefixIndex.MAX_EDIT2_PROBES,
            )
        }
    }

    /**
     * The operator's case, pinned HONESTLY on the real asset: `сэлэм` (two э where `сәләм` has
     * ә) trips the fail-closed probe budget — the fertile э/л continuations explode the chained
     * enumeration — so the level is dropped and the strip stays EMPTY (this is the G1-failure
     * mechanism, not a ranking loss: the brute-force top-3 [сәләм, сәлам, сәлим] is exactly what
     * the plausibility key would have produced with an infinite budget). The 3-cp and 4-cp
     * intermediates pin the class-#1 / class-#4 pictures proving class #5 does not leak into a
     * non-empty strip; the three-edit negative (`сөлүкә`) stays empty as well.
     */
    @Test
    fun theOperatorsCaseAndTheThreeEditNegativeArePinnedAsMeasured() {
        val candidate = requireNotNull(candidateIndex)
        fun strip(prefix: String): List<String> =
            candidate.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))

        // 3 code points: class #5 is gated at >= 4 — the strip is the class-#1 (э→ә) picture
        // exactly as before.
        assertEquals(listOf("сәламәтлек", "сәләтле", "сәламәт"), strip("сэл"))
        // 4 code points: class #5's activation condition does NOT hold here — class #4 fills
        // the strip (л→б gives сэбэ*, с→ф gives фэлэ*), so class #5 never probes. The strip is
        // the class-#4 picture, pinned to PROVE the firing gate keeps class #5 out of a
        // non-empty strip at the 4-cp boundary too.
        assertEquals(AT4_STRIP, strip("сэлэ"))
        // 5 code points: the operator's case — the probe budget trips (512/512, fail-closed),
        // the level is dropped in full: an empty strip, honestly pinned.
        assertEquals(emptyList<String>(), strip("сэлэм"))
        // Three edits from every dictionary word: nothing is ever offered.
        assertEquals(emptyList<String>(), strip("сөлүкә"))
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

        // CURRENT: the shipped Tatar display configuration at calibration time ({1, 4} + bonus),
        // pinned as an explicit policy — NOT [FuzzyEditPolicy.TATAR] — so the calibration keeps
        // comparing the same two arms even if class #5 ships and TATAR becomes {1, 4, 5}.
        private val CURRENT_POLICY = FuzzyEditPolicy(
            intArrayOf(
                TdictPrefixIndex.EDIT_CLASS_LONG_PRESS,
                TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION,
            ),
            true,
        )

        // The candidate adds class #5 to the shipped Tatar display configuration; CURRENT is it.
        private val CANDIDATE_POLICY = FuzzyEditPolicy(
            intArrayOf(
                TdictPrefixIndex.EDIT_CLASS_LONG_PRESS,
                TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION,
                TdictPrefixIndex.EDIT_CLASS_TWO_SUBSTITUTIONS,
            ),
            true,
        )

        // Exact pins of the P6 measurements (2026-09-23, post the walkEntries/upperBound fixes);
        // re-pin consciously. The candidate's zero recovery IS the G1 failure: the probe budget
        // trips on 73 % of firing rows, dropping the level whole wherever it would matter.
        private const val PIN_G1_WHOLE_CURRENT = 0
        private const val PIN_G1_WHOLE_CANDIDATE = 0
        private const val PIN_G1_ACTIVE = 76_399
        private const val PIN_G1_ACTIVE_CANDIDATE = 0
        private const val PIN_G2_ACTIVATION_SUBSET = 304
        private const val PIN_G2_FILLED = 304

        // The 4-cp intermediate of the operator's case, pinned as measured (2026-09-23): the
        // class-#4 strip — class #5 does not fire here (the strip is not empty).
        private val AT4_STRIP = listOf("сэбэпле", "сэбэп", "фэлэн")

        private val neighborTable = E3bTestFixtures.tatarNeighborTable()
        private lateinit var vocabulary: List<String>
        private lateinit var vocabCodePoints: Array<IntArray>
        private lateinit var vocabFrequencies: LongArray
        private lateinit var vocabIndex: Map<String, Int>
        private lateinit var uniqueEvalWords: List<String>
        private var currentIndex: TdictPrefixIndex? = null
        private var candidateIndex: TdictPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssets() {
            val raw = inflate(DictionaryArtifactSpec.TATAR_TOP100K_V1)
            vocabulary = DictionaryTestFixtures.words(raw)
            check(vocabulary.size == DictionaryArtifactSpec.TATAR_TOP100K_V1.expectedEntryCount.toInt())
            val parsed = wordsAndFrequencies(raw)
            check(parsed.first == vocabulary) { "the frequency parser must agree with the fixture" }
            vocabFrequencies = parsed.second
            vocabCodePoints = vocabulary.map { it.codePoints().toArray() }.toTypedArray()
            vocabIndex = HashMap<String, Int>(vocabulary.size * 2).also { map ->
                vocabulary.forEachIndexed { index, word -> map[word] = index }
            }

            val evalLines = locate(
                "src/test/resources/tt_eval_sentences.txt",
                "app/src/test/resources/tt_eval_sentences.txt",
            ).readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            uniqueEvalWords = evalLines.flatMap { it.split(" ") }.distinct()

            currentIndex = openIndex(raw, CURRENT_POLICY).also { it.updateKeyNeighbors(neighborTable) }
            candidateIndex = openIndex(raw, CANDIDATE_POLICY).also { it.updateKeyNeighbors(neighborTable) }
        }

        /**
         * The fixture's schema-2 block walk, extended with the frequency varints (they follow
         * each block's entries in entry order) — the G1 diagnostic ranks by frequency.
         */
        private fun wordsAndFrequencies(raw: ByteArray): Pair<List<String>, LongArray> {
            val buffer = ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val entryCount = buffer.getInt(16)
            val blockCount = buffer.getInt(20)
            val blockIndexOffset = buffer.getInt(24)
            val words = ArrayList<String>(entryCount)
            val frequencies = LongArray(entryCount)
            for (block in 0 until blockCount) {
                var cursor = buffer.getInt(blockIndexOffset + block * 4)
                val inBlock = minOf(TdictFormat.BLOCK_SIZE, entryCount - block * TdictFormat.BLOCK_SIZE)
                val firstLength = raw[cursor].toInt() and 0xff
                cursor++
                val first = raw.copyOfRange(cursor, cursor + firstLength)
                cursor += firstLength
                words.add(String(first, Charsets.UTF_8))
                for (entry in 1 until inBlock) {
                    var prefix = 0
                    var shift = 0
                    while (true) {
                        val byte = raw[cursor].toInt() and 0xff
                        cursor++
                        prefix = prefix or ((byte and 0x7f) shl shift)
                        if (byte and 0x80 == 0) break
                        shift += 7
                    }
                    val suffixLength = raw[cursor].toInt() and 0xff
                    cursor++
                    val word = ByteArray(prefix + suffixLength)
                    System.arraycopy(first, 0, word, 0, prefix)
                    System.arraycopy(raw, cursor, word, prefix, suffixLength)
                    cursor += suffixLength
                    words.add(String(word, Charsets.UTF_8))
                }
                val blockStart = words.size - inBlock
                var entry = 0
                repeat(inBlock) {
                    var value = 0L
                    var shift = 0
                    while (true) {
                        val byte = raw[cursor].toInt() and 0xff
                        cursor++
                        value = value or ((byte and 0x7f).toLong() shl shift)
                        if (byte and 0x80 == 0) break
                        shift += 7
                    }
                    frequencies[blockStart + entry] = value
                    entry++
                }
            }
            return words to frequencies
        }

        private fun inflate(spec: DictionaryArtifactSpec): ByteArray {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("p6-two-subst-", ".tdict")
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

        private fun openIndex(raw: ByteArray, policy: FuzzyEditPolicy): TdictPrefixIndex {
            val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val identity = DictionaryIdentity(
                spec.generation, spec.schemaId, spec.formatVersion,
                MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) },
            )
            return requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw), identity, spec.expectedEntryCount, raw.size.toLong(),
                    null, policy,
                ),
            )
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed test resource")
    }
}
