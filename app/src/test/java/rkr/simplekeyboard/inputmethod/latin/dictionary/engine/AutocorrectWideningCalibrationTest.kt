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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * ROADMAP-P3 P7 calibration (docs/ROADMAP-P3.md, gates G1–G4 written 2026-09-23 BEFORE measuring):
 * widening the D3 autocorrect edit classes from {1} to {1, 4} (probe-first full single
 * substitution), frequency floor 411 and the single-candidate rule unchanged. Tatar only.
 *
 * Arms, differing ONLY in [FuzzyEditPolicy.autocorrectClasses] — the display configuration of both
 * is today's shipped Tatar one ({1, 4} + the same-length bonus):
 *  - CURRENT: autocorrect classes {1} — the pre-P7 shipped behavior;
 *  - WIDENED: autocorrect classes {1, 4} — the P7 candidate.
 *
 * All metrics are printed as raw lines and the measured counts are pinned exactly. Nothing is
 * tuned to pass: if a gate fails, the autocorrect classes stay {1} and the numbers stand.
 */
class AutocorrectWideningCalibrationTest {

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

    // ---- Typo-set construction (5-cp window, mirroring TtTypoPhaseCCalibrationTest). ----

    private data class TypoRow(val word: String, val typoPrefixUtf8: ByteArray)

    private fun buildSet(window: Int, choices: (IntArray, Int) -> IntArray): List<TypoRow> {
        val rows = ArrayList<TypoRow>(vocabulary.size)
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
            rows.add(TypoRow(word, prefix.toString().toByteArray(Charsets.UTF_8)))
        }
        return rows
    }

    private fun buildLongPressSet(window: Int): List<TypoRow> = buildSet(window) { codePoints, position ->
        neighborTable.longPressPartnersOf(codePoints[position]) ?: IntArray(0)
    }

    private fun buildSubstitutionSet(window: Int): List<TypoRow> = buildSet(window) { codePoints, position ->
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

    /**
     * G1a — false corrections on the eval set's OOV words. Every would-fire case is printed for
     * the report's manual review (fail-closed: one implausible case fails the gate). The
     * construction invariant is pinned alongside: a word PRESENT in the dictionary never gets a
     * verdict, on every eval word that is present.
     */
    @Test
    fun gateG1FalseCorrectionsOnEvalOovWords() {
        val widened = requireNotNull(widenedIndex)
        val current = requireNotNull(currentIndex)
        var presentWords = 0
        var currentFires = 0
        val widenedFires = ArrayList<String>()
        for (word in uniqueEvalWords) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            if (widened.indexOfWord(bytes, bytes.size) >= 0) {
                presentWords++
                widened.lookup(ImmutableUtf8Prefix.copyOf(bytes))
                assertNull("invariant: a dictionary word is never advised ($word)", widened.lastAutocorrectAdvice)
                continue
            }
            current.lookup(ImmutableUtf8Prefix.copyOf(bytes))
            if (current.lastAutocorrectAdvice != null) currentFires++
            widened.lookup(ImmutableUtf8Prefix.copyOf(bytes))
            val advice = widened.lastAutocorrectAdvice
            if (advice != null) {
                widenedFires.add("$word -> ${advice.replacement} (${advice.frequency})")
            }
        }
        println(
            "P7 G1a eval words=${uniqueEvalWords.size} present=$presentWords oov=${uniqueEvalWords.size - presentWords} " +
                "current_fires=$currentFires widened_fires=${widenedFires.size}",
        )
        for (line in widenedFires.sorted()) {
            println("P7 G1a would-fire: $line")
        }
        assertEquals(PIN_G1A_PRESENT, presentWords)
        assertEquals(PIN_G1A_CURRENT_FIRES, currentFires)
        assertEquals(PIN_G1A_WIDENED_FIRES, widenedFires.size)
    }

    /**
     * G1b — the rare-but-correct proxy: the dictionary's own lowest-frequency decile, at the
     * variant level (the decile words ARE present, so the real pass never fires on them; this
     * simulates "if this rare word were OOV"). A word would-fire when its substitution space
     * contains EXACTLY ONE other dictionary word with frequency >= 411. Listed for review.
     */
    @Test
    fun gateG1FalseCorrectionsOnTheLowestFrequencyDecile() {
        val index = requireNotNull(widenedIndex)
        val decile = bottomDecileWords
        val class1Fires = ArrayList<String>()
        val class4OnlyFires = ArrayList<String>()
        val scratch = IntArray(64)
        val variantScratch = ByteArray(256)
        for (word in decile) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            val class1 = singleMatchFrequency(bytes, geometric = false, scratch, variantScratch)
            if (class1 != null && class1 >= AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY) {
                class1Fires.add(word)
                continue
            }
            val class4 = singleMatchFrequency(bytes, geometric = true, scratch, variantScratch)
            if (class4 != null && class4 >= AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY) {
                class4OnlyFires.add(word)
            }
        }
        println(
            "P7 G1b decile=${decile.size} class1_fires=${class1Fires.size} " +
                "class4only_fires=${class4OnlyFires.size}",
        )
        for (line in (class1Fires + class4OnlyFires).sorted()) {
            println("P7 G1b would-fire-if-oov: $line")
        }
        assertEquals(PIN_G1B_CLASS1_FIRES, class1Fires.size)
        assertEquals(PIN_G1B_CLASS4ONLY_FIRES, class4OnlyFires.size)
    }

    /**
     * Variant-level whole-word single-match frequency of [wordBytes]'s substitution space: how the
     * D3 pass counts candidates, minus the presence early-out. Returns the single matching
     * dictionary entry's frequency, or null when zero or >= 2 matches exist. [geometric] selects
     * the class (#1 long-press when false, #4 full substitution when true).
     */
    private fun singleMatchFrequency(
        wordBytes: ByteArray,
        geometric: Boolean,
        scratch: IntArray,
        variantScratch: ByteArray,
    ): Long? {
        val index = requireNotNull(widenedIndex)
        var matches = 0
        var matchIndex = -1
        val emitted = if (geometric) {
            FuzzyPrefixVariants.generateFullSubstitutionVariants(
                wordBytes, wordBytes.size, neighborTable.nodes, scratch, variantScratch, 8_192,
            ) { _, variant, length ->
                if (matches > 1) return@generateFullSubstitutionVariants
                val entry = index.indexOfWord(variant, length)
                if (entry >= 0 && entry != matchIndex) {
                    matches++
                    matchIndex = entry
                }
            }
        } else {
            FuzzyPrefixVariants.generateLongPressVariants(
                wordBytes, wordBytes.size, neighborTable, scratch, variantScratch, 64,
            ) { variant, length ->
                if (matches > 1) return@generateLongPressVariants
                val entry = index.indexOfWord(variant, length)
                if (entry >= 0 && entry != matchIndex) {
                    matches++
                    matchIndex = entry
                }
            }
        }
        if (emitted < 0) return null
        if (matches != 1) return null
        return index.frequencyOf(index.wordAt(matchIndex))
    }

    /**
     * G2 — autocorrect-recovery@separator on the 5-cp typo sets, identical conditions both arms:
     * a row recovers when its typo prefix is absent from the dictionary and the advice names the
     * original word (the >= 411 floor applies — it stays unchanged).
     */
    @Test
    fun gateG2RecoveryAtSeparatorOnTheTypoSets() {
        val current = requireNotNull(currentIndex)
        val widened = requireNotNull(widenedIndex)
        val class1Set = buildLongPressSet(5)
        val class4Set = buildSubstitutionSet(5)

        fun measure(index: TdictPrefixIndex, rows: List<TypoRow>): Pair<Int, Int> {
            var active = 0
            var recovered = 0
            for (row in rows) {
                if (index.indexOfWord(row.typoPrefixUtf8, row.typoPrefixUtf8.size) >= 0) continue
                active++
                index.lookup(ImmutableUtf8Prefix.copyOf(row.typoPrefixUtf8))
                val advice = index.lastAutocorrectAdvice
                if (advice != null && advice.replacement == row.word) recovered++
            }
            return active to recovered
        }

        val (active1Current, recovered1Current) = measure(current, class1Set)
        val (active1Widened, recovered1Widened) = measure(widened, class1Set)
        val (active4Current, recovered4Current) = measure(current, class4Set)
        val (active4Widened, recovered4Widened) = measure(widened, class4Set)
        val rate1Current = recovered1Current * 100.0 / active1Current
        val rate1Widened = recovered1Widened * 100.0 / active1Widened
        val rate4Current = recovered4Current * 100.0 / active4Current
        val rate4Widened = recovered4Widened * 100.0 / active4Widened
        val lift4 = rate4Widened - rate4Current

        println(
            "P7 G2 class1_set active=$active1Current current=${fmt(rate1Current)}% ($recovered1Current) " +
                "widened=${fmt(rate1Widened)}% ($recovered1Widened) | " +
                "class4_set active=$active4Current current=${fmt(rate4Current)}% ($recovered4Current) " +
                "widened=${fmt(rate4Widened)}% ($recovered4Widened) " +
                "lift=${fmt(lift4)}pp gate>=+5pp verdict=${if (lift4 >= 5.0) "PASS" else "BELOW"}",
        )

        assertEquals(active1Current, active1Widened)
        assertEquals(active4Current, active4Widened)
        assertEquals(PIN_G2_ACTIVE1, active1Current)
        assertEquals(PIN_G2_RECOVERED1_CURRENT, recovered1Current)
        assertEquals(PIN_G2_RECOVERED1_WIDENED, recovered1Widened)
        assertEquals(PIN_G2_ACTIVE4, active4Current)
        assertEquals(PIN_G2_RECOVERED4_CURRENT, recovered4Current)
        assertEquals(PIN_G2_RECOVERED4_WIDENED, recovered4Widened)
    }

    /**
     * G3 — multi-candidate safety: the single-candidate rule STAYS, so widening turns some
     * recoverable typos into safe no-fires. Reported: zero / exactly-one / multi match rates on
     * the class-#4 5-cp set (the single-match bucket is the fire-eligible one before the floor).
     */
    @Test
    fun gateG3MultiCandidateSafetyRates() {
        val class4Set = buildSubstitutionSet(5)
        val scratch = IntArray(64)
        val variantScratch = ByteArray(256)
        var zero = 0
        var single = 0
        var multi = 0
        val index = requireNotNull(widenedIndex)
        for (row in class4Set) {
            if (index.indexOfWord(row.typoPrefixUtf8, row.typoPrefixUtf8.size) >= 0) continue
            var matches = 0
            var matchIndex = -1
            FuzzyPrefixVariants.generateFullSubstitutionVariants(
                row.typoPrefixUtf8, row.typoPrefixUtf8.size, neighborTable.nodes, scratch,
                variantScratch, 8_192,
            ) { _, variant, length ->
                if (matches > 1) return@generateFullSubstitutionVariants
                val entry = index.indexOfWord(variant, length)
                if (entry >= 0 && entry != matchIndex) {
                    matches++
                    matchIndex = entry
                }
            }
            when {
                matches == 0 -> zero++
                matches == 1 -> single++
                else -> multi++
            }
        }
        val total = zero + single + multi
        println(
            "P7 G3 class4_set=$total zero=${fmt(zero * 100.0 / total)}% ($zero) " +
                "single=${fmt(single * 100.0 / total)}% ($single) " +
                "multi=${fmt(multi * 100.0 / total)}% ($multi)",
        )
        assertEquals(PIN_G3_ZERO, zero)
        assertEquals(PIN_G3_SINGLE, single)
        assertEquals(PIN_G3_MULTI, multi)
    }

    /**
     * G4 — host perf with the widened advice pass engaged: a deterministic sample of the class-#4
     * 5-cp set (absent >= 4-cp words, so the class-#4 autocorrect probes actually run), both arms.
     */
    @Test
    fun gateG4HostComputeWithWidenedAutocorrect() {
        val class4Set = buildSubstitutionSet(5)
        val sample = class4Set.filterIndexed { index, _ -> index % 53 == 0 }
            .map { ImmutableUtf8Prefix.copyOf(it.typoPrefixUtf8) }
        assertTrue("the perf sample is meaningful", sample.size > 1_500)

        val arms = listOf(
            "current" to requireNotNull(currentIndex),
            "widened" to requireNotNull(widenedIndex),
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
            for (prefix in sample) {
                index.lookup(prefix)
                if (index.lastAutocorrectProbeCount > 0) probes.add(index.lastAutocorrectProbeCount)
            }
            timings.sort()
            val p50 = timings[timings.size / 2] / 1_000_000.0
            val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
            val max = timings.last() / 1_000_000.0
            println(
                "P7 G4 host policy=$name samples=${timings.size} p50=${fmtMs(p50)} ms " +
                    "p95=${fmtMs(p95)} ms max=${fmtMs(max)} ms " +
                    "acprobe_p95=${percentile(probes.sorted(), 0.95)} acprobe_max=${probes.maxOrNull() ?: 0} " +
                    "consumed=$consumed",
            )
            if (name == "widened") {
                assertTrue("widened-advice p95=${p95}ms exceeds the 5 ms budget", p95 <= 5.0)
            }
        }
    }

    /**
     * The widened policy's pinned real cases: "аашнең" — absent, NOT reachable by class #1, and
     * exactly one single-substitution dictionary neighbour (ааҗнең, frequency 1 738 >= 411) — is
     * corrected by WIDENED and left alone by CURRENT; the two-candidate "ааҗнңң" (ааҗның + ааҗнең)
     * is refused by the single-candidate rule under WIDENED too.
     */
    @Test
    fun theWidenedPolicyCorrectsAClass4OnlyCaseAndRefusesAmbiguity() {
        val current = requireNotNull(currentIndex)
        val widened = requireNotNull(widenedIndex)

        fun advice(index: TdictPrefixIndex, word: String): AutocorrectAdvice? {
            index.lookup(ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8)))
            return index.lastAutocorrectAdvice
        }

        assertNull("CURRENT leaves the class-#4-only case alone", advice(current, "аашнең"))
        val widenedAdvice = advice(widened, "аашнең")
        assertNotNull("WIDENED corrects the class-#4-only case", widenedAdvice)
        assertEquals("аашнең", widenedAdvice!!.typedWord)
        assertEquals("ааҗнең", widenedAdvice.replacement)
        assertEquals(1_738L, widenedAdvice.frequency)

        assertNull("two class-#4 candidates: the single-candidate rule still refuses",
            advice(widened, "ааҗнңң"))
        assertNull(advice(current, "ааҗнңң"))
    }

    /**
     * G4 — zero-allocation contract with the widened advice pass engaged: an absent >= 4-cp word
     * that does NOT fire (the common path) must stay within the <= 8 bytes/lookup contract. The
     * firing path allocates the AutocorrectAdvice object itself — pre-existing behavior of the
     * class-#1 pass, unchanged and out of scope.
     */
    @Test
    fun gateG4ZeroAllocationWithWidenedAutocorrect() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.Assume.assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        // A fixture dictionary whose substitution space holds neither a strip survivor nor an
        // autocorrect candidate for "мсмә": the widened advice pass probes (positions 0 and 1 —
        // "м*" is non-empty, "мс*" is empty) and never fires, and the strip stays empty, so the
        // measurement isolates the machinery from any result materialization.
        val index = EngineTestFixtures.index(
            listOf("мин" to 5L, "син" to 5L),
            fuzzyEditPolicy = WIDENED_POLICY,
        ).also { it.updateKeyNeighbors(neighborTable) }
        val prefix = ImmutableUtf8Prefix.copyOf("мсмә".toByteArray(Charsets.UTF_8))
        assertTrue(index.lookup(prefix).isEmpty())
        assertNull(index.lastAutocorrectAdvice)
        assertTrue(index.lastAutocorrectProbeCount > 0)

        repeat(50_000) { index.lookup(prefix) }
        val before = threadBean.getThreadAllocatedBytes(threadId)
        repeat(200_000) { index.lookup(prefix) }
        val after = threadBean.getThreadAllocatedBytes(threadId)
        val perLookup = (after - before) / 200_000
        assertTrue("perLookup=$perLookup bytes/lookup", perLookup <= 8L)
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

        // The two arms differ ONLY in the autocorrect class set; the display side of both is the
        // shipped Tatar configuration (TT-TYPO-NEXT C2).
        private val CURRENT_POLICY = FuzzyEditPolicy(
            intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
            true,
            intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS),
        )
        private val WIDENED_POLICY = FuzzyEditPolicy(
            intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
            true,
            intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
        )

        // Exact pins of the P7 measurements (2026-09-23); re-pin consciously. The verdict was
        // NOT SHIPPED: G1 failed on manual review (3 of the 5 widened would-fire cases on the
        // eval OOV set change meaning), G2 measured +0.57 pp of lift vs the +5 pp gate.
        private const val PIN_G1A_PRESENT = 2_373
        private const val PIN_G1A_CURRENT_FIRES = 1
        private const val PIN_G1A_WIDENED_FIRES = 5
        private const val PIN_G1B_CLASS1_FIRES = 151
        private const val PIN_G1B_CLASS4ONLY_FIRES = 199
        private const val PIN_G2_ACTIVE1 = 99_863
        private const val PIN_G2_RECOVERED1_CURRENT = 1_100
        private const val PIN_G2_RECOVERED1_WIDENED = 574
        private const val PIN_G2_ACTIVE4 = 103_488
        private const val PIN_G2_RECOVERED4_CURRENT = 9
        private const val PIN_G2_RECOVERED4_WIDENED = 598
        private const val PIN_G3_ZERO = 42_505
        private const val PIN_G3_SINGLE = 31_684
        private const val PIN_G3_MULTI = 29_299

        private val neighborTable = E3bTestFixtures.tatarNeighborTable()
        private lateinit var vocabulary: List<String>
        private lateinit var uniqueEvalWords: List<String>
        private lateinit var bottomDecileWords: List<String>
        private var currentIndex: TdictPrefixIndex? = null
        private var widenedIndex: TdictPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssets() {
            val raw = inflate(DictionaryArtifactSpec.TATAR_TOP100K_V1)
            vocabulary = DictionaryTestFixtures.words(raw)
            check(vocabulary.size == DictionaryArtifactSpec.TATAR_TOP100K_V1.expectedEntryCount.toInt())

            val evalLines = locate(
                "src/test/resources/tt_eval_sentences.txt",
                "app/src/test/resources/tt_eval_sentences.txt",
            ).readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            uniqueEvalWords = evalLines.flatMap { it.split(" ") }.distinct()

            val index = openIndex(raw, CURRENT_POLICY)
            val withFrequency = vocabulary.map { it to index.frequencyOf(it) }
            val sortedByFrequency = withFrequency.sortedWith(
                compareBy<Pair<String, Long>> { it.second }.thenBy { it.first },
            )
            bottomDecileWords = sortedByFrequency.take(vocabulary.size / 10).map { it.first }

            currentIndex = index.also { it.updateKeyNeighbors(neighborTable) }
            widenedIndex = openIndex(raw, WIDENED_POLICY).also { it.updateKeyNeighbors(neighborTable) }
        }

        private fun inflate(spec: DictionaryArtifactSpec): ByteArray {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("p7-autocorrect-", ".tdict")
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
