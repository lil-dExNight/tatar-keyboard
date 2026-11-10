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
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LongPressTestFixtures.rawKey
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Compares long-press-only advice with the full DL-1 arm in both languages, unarmed and under
 * the context-successor guard. Recall, reviewed valid-OOV replacements, correctly typed words,
 * ambiguity and host cost stay separate. The widened arm won the pre-registered comparison
 * (recall 8 to 2 960 of 9 524 rows at zero harmful replacements, 3 vs 2 valid-OOV false fires of
 * 175), so both shipped policies autocorrect from class #1 and the DL-1 tier; the guard arm
 * (`tt-guard`, `ru-guard`) is the production mid-sentence behavior, measured with the preceding
 * eval word's packed bigram successors armed. Frequency floors that retain recall do not meet
 * valid-OOV safety; the guard is what ships instead of a floor change.
 */
class AutocorrectWideningCalibrationTest {
    private data class SafetyRow(val word: String, val stratum: String)

    /** Measures every reviewed Tatar row; no unreviewed eval-OOV word enters this denominator. */
    @Test
    fun falseReplacementsOnTatarValidOovWordsArePinned() {
        fun measure(index: TdictPrefixIndex, arm: String): Map<String, Int> {
            val counts = SAFETY_STRATA.associateWith { 0 }.toMutableMap()
            val offenders = ArrayList<String>()
            var measured = 0
            for (row in safetyRows) {
                val bytes = row.word.toByteArray(Charsets.UTF_8)
                assertTrue("safety word entered the dictionary: ${row.word}",
                    index.indexOfWord(bytes, bytes.size) < 0)
                index.lookup(ImmutableUtf8Prefix.copyOf(bytes))
                measured++
                val advice = index.lastAutocorrectAdvice ?: continue
                counts[row.stratum] = counts.getValue(row.stratum) + 1
                offenders.add("${advice.typedWord}->${advice.replacement} (${advice.frequency})")
            }
            assertEquals(PIN_VALID_OOV_ROWS, measured)
            for (stratum in SAFETY_STRATA) {
                val words = safetyRows.count { it.stratum == stratum }
                println("widening safety arm=$arm stratum=$stratum words=$words false_replacements=${counts.getValue(stratum)}")
            }
            println("widening safety arm=$arm valid_oov_words=$measured false_replacements=${counts.values.sum()}")
            for (line in offenders.sorted()) println("widening safety arm=$arm would-fire: $line")
            return counts
        }

        val current = measure(requireNotNull(currentIndex), "tt-current")
        val widened = measure(requireNotNull(widenedIndex), "tt-widened")
        val guarded = measureSafetyGuarded(
            requireNotNull(widenedIndex), requireNotNull(tatarBigramIndex), "tt-guard",
            safetyRows, tatarContextByWord,
        )
        assertEquals(PIN_TT_CURRENT_SEEN_FORM, current.getValue("seen_form"))
        assertEquals(PIN_TT_CURRENT_NEW_FORM, current.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_TT_CURRENT_UNSEEN_STEM, current.getValue("unseen_stem"))
        assertEquals(PIN_TT_WIDENED_SEEN_FORM, widened.getValue("seen_form"))
        assertEquals(PIN_TT_WIDENED_NEW_FORM, widened.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_TT_WIDENED_UNSEEN_STEM, widened.getValue("unseen_stem"))
        assertEquals(PIN_TT_CURRENT_FIRES, current.values.sum())
        assertEquals(PIN_TT_WIDENED_FIRES, widened.values.sum())
        assertEquals(PIN_TT_GUARD_SEEN_FORM, guarded.getValue("seen_form"))
        assertEquals(PIN_TT_GUARD_NEW_FORM, guarded.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_TT_GUARD_UNSEEN_STEM, guarded.getValue("unseen_stem"))
        assertEquals(PIN_TT_GUARD_FIRES, guarded.values.sum())
    }

    @Test
    fun falseReplacementsOnRussianValidOovWordsArePinned() {
        fun measure(index: TdictPrefixIndex, arm: String): Map<String, Int> {
            val counts = SAFETY_STRATA.associateWith { 0 }.toMutableMap()
            val offenders = ArrayList<String>()
            for (row in russianSafetyRows) {
                val bytes = row.word.toByteArray(Charsets.UTF_8)
                assertTrue("safety word entered the dictionary: ${row.word}",
                    index.indexOfWord(bytes, bytes.size) < 0)
                index.lookup(ImmutableUtf8Prefix.copyOf(bytes))
                val advice = index.lastAutocorrectAdvice ?: continue
                counts[row.stratum] = counts.getValue(row.stratum) + 1
                offenders.add("${advice.typedWord}->${advice.replacement} (${advice.frequency})")
            }
            for (stratum in SAFETY_STRATA) {
                val words = russianSafetyRows.count { it.stratum == stratum }
                println(
                    "widening safety arm=$arm stratum=$stratum words=$words " +
                        "false_replacements=${counts.getValue(stratum)}",
                )
            }
            println(
                "widening safety arm=$arm valid_oov_words=${russianSafetyRows.size} " +
                    "false_replacements=${counts.values.sum()} offenders=${offenders.sorted()}",
            )
            return counts
        }

        val current = measure(requireNotNull(russianCurrentIndex), "ru-current")
        val widened = measure(requireNotNull(russianWidenedIndex), "ru-widened")
        val guarded = measureSafetyGuarded(
            requireNotNull(russianWidenedIndex), requireNotNull(russianBigramIndex), "ru-guard",
            russianSafetyRows, russianContextByWord,
        )
        assertEquals(PIN_RU_CURRENT_SEEN_FORM, current.getValue("seen_form"))
        assertEquals(PIN_RU_CURRENT_NEW_FORM, current.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_RU_CURRENT_UNSEEN_STEM, current.getValue("unseen_stem"))
        assertEquals(PIN_RU_WIDENED_SEEN_FORM, widened.getValue("seen_form"))
        assertEquals(PIN_RU_WIDENED_NEW_FORM, widened.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_RU_WIDENED_UNSEEN_STEM, widened.getValue("unseen_stem"))
        assertEquals(PIN_RU_GUARD_SEEN_FORM, guarded.getValue("seen_form"))
        assertEquals(PIN_RU_GUARD_NEW_FORM, guarded.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_RU_GUARD_UNSEEN_STEM, guarded.getValue("unseen_stem"))
        assertEquals(PIN_RU_GUARD_FIRES, guarded.values.sum())
    }

    /**
     * The guard arm of the valid-OOV measurement: every row is looked up twice on the widened
     * index, once unarmed (the reference) and once armed with the preceding eval word's packed
     * successors, the production mid-sentence shape. Prints the armed false replacements and how
     * many unarmed fires the guard blocked.
     */
    private fun measureSafetyGuarded(
        index: TdictPrefixIndex,
        bigrams: TatBigrPrefixIndex,
        arm: String,
        rows: List<SafetyRow>,
        contextByWord: Map<String, String>,
    ): Map<String, Int> {
        val counts = SAFETY_STRATA.associateWith { 0 }.toMutableMap()
        val offenders = ArrayList<String>()
        val ids = IntArray(TatBigrPrefixIndex.MAX_CONTEXT_SUCCESSORS)
        var armed = 0
        var blocked = 0
        for (row in rows) {
            val bytes = row.word.toByteArray(Charsets.UTF_8)
            index.lookup(ImmutableUtf8Prefix.copyOf(bytes))
            val unarmedFired = index.lastAutocorrectAdvice != null
            val context = contextByWord[row.word]
            val count = if (context == null) {
                0
            } else {
                bigrams.collectSuccessorIds(
                    ImmutableUtf8Prefix.copyOf(context.toByteArray(Charsets.UTF_8)), ids,
                )
            }
            if (count > 0) armed++
            index.lookup(ImmutableUtf8Prefix.copyOf(bytes), ids, count)
            val advice = index.lastAutocorrectAdvice
            if (unarmedFired && advice == null) blocked++
            if (advice == null) continue
            counts[row.stratum] = counts.getValue(row.stratum) + 1
            offenders.add("${advice.typedWord}->${advice.replacement} (${advice.frequency})")
        }
        for (stratum in SAFETY_STRATA) {
            val words = rows.count { it.stratum == stratum }
            println(
                "widening safety arm=$arm stratum=$stratum words=$words " +
                    "false_replacements=${counts.getValue(stratum)}",
            )
        }
        println(
            "widening safety arm=$arm valid_oov_words=${rows.size} armed_rows=$armed " +
                "false_replacements=${counts.values.sum()} guard_blocked=$blocked",
        )
        for (line in offenders.sorted()) println("widening safety arm=$arm would-fire: $line")
        return counts
    }

    @Test
    fun correctlyTypedDictionaryWordsAreNeverReplaced() {
        fun verify(index: TdictPrefixIndex, words: List<String>, arm: String) {
            var replacements = 0
            for (word in words) {
                index.lookup(ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8)))
                if (index.lastAutocorrectAdvice != null) replacements++
            }
            println(
                "widening correct_words arm=$arm words=${words.size} replacements=$replacements",
            )
            assertEquals("correctly typed dictionary words were replaced in $arm", 0, replacements)
        }

        verify(requireNotNull(currentIndex), vocabulary, "tt-current")
        verify(requireNotNull(widenedIndex), vocabulary, "tt-widened")
        verify(requireNotNull(russianCurrentIndex), russianVocabulary, "ru-current")
        verify(requireNotNull(russianWidenedIndex), russianVocabulary, "ru-widened")
    }

    private fun measureFullWordRecall(
        index: TdictPrefixIndex,
        arm: String,
        rows: List<TypoMutatedEvalTest.EvalRow>,
    ): Pair<Int, Int> {
        val recovered = HashMap<String, Int>()
        var harmful = 0
        for (row in rows) {
            index.lookup(ImmutableUtf8Prefix.copyOf(row.mutated.toByteArray(Charsets.UTF_8)))
            val advice = index.lastAutocorrectAdvice ?: continue
            if (advice.replacement == row.original) {
                recovered[row.editClass] = (recovered[row.editClass] ?: 0) + 1
            } else {
                harmful++
            }
        }
        for (editClass in listOf("sub", "del", "ins", "trans")) {
            val classRows = rows.count { it.editClass == editClass }
            val hits = recovered[editClass] ?: 0
            println(
                "widening recall arm=$arm class=$editClass rows=$hits/$classRows " +
                    "pct=${fmt(hits * 100.0 / classRows)}",
            )
        }
        val total = recovered.values.sum()
        println(
            "widening recall arm=$arm rows=$total/${rows.size} " +
                "pct=${fmt(total * 100.0 / rows.size)} harmful=$harmful",
        )
        return total to harmful
    }

    /**
     * The guard arm of the full-word recall measurement: every row is looked up twice on the
     * widened index, unarmed then armed with the preceding eval word's packed successors.
     * `guard_refused_correct` counts rows the unarmed arm recovers but the guard refuses — the
     * recall price of the require rule; `armed_rows` is the bigram coverage of the pairs.
     */
    private fun measureFullWordRecallGuarded(
        index: TdictPrefixIndex,
        bigrams: TatBigrPrefixIndex,
        arm: String,
        rows: List<TypoMutatedEvalTest.EvalRow>,
        contextByWord: Map<String, String>,
    ): Pair<Int, Int> {
        val ids = IntArray(TatBigrPrefixIndex.MAX_CONTEXT_SUCCESSORS)
        val recovered = HashMap<String, Int>()
        var harmful = 0
        var armed = 0
        var guardRefusedCorrect = 0
        for (row in rows) {
            val bytes = row.mutated.toByteArray(Charsets.UTF_8)
            index.lookup(ImmutableUtf8Prefix.copyOf(bytes))
            val unarmedCorrect = index.lastAutocorrectAdvice?.replacement == row.original
            val context = contextByWord[row.original]
            val count = if (context == null) {
                0
            } else {
                bigrams.collectSuccessorIds(
                    ImmutableUtf8Prefix.copyOf(context.toByteArray(Charsets.UTF_8)), ids,
                )
            }
            if (count > 0) armed++
            index.lookup(ImmutableUtf8Prefix.copyOf(bytes), ids, count)
            val advice = index.lastAutocorrectAdvice
            if (unarmedCorrect && advice?.replacement != row.original) guardRefusedCorrect++
            if (advice == null) continue
            if (advice.replacement == row.original) {
                recovered[row.editClass] = (recovered[row.editClass] ?: 0) + 1
            } else {
                harmful++
            }
        }
        for (editClass in listOf("sub", "del", "ins", "trans")) {
            val classRows = rows.count { it.editClass == editClass }
            val hits = recovered[editClass] ?: 0
            println(
                "widening recall arm=$arm class=$editClass rows=$hits/$classRows " +
                    "pct=${fmt(hits * 100.0 / classRows)}",
            )
        }
        val total = recovered.values.sum()
        println(
            "widening recall arm=$arm rows=$total/${rows.size} " +
                "pct=${fmt(total * 100.0 / rows.size)} harmful=$harmful armed_rows=$armed " +
                "guard_refused_correct=$guardRefusedCorrect",
        )
        return total to harmful
    }

    @Test
    fun fullWordRecallAndHarmfulReplacementsArePinned() {
        val tatarCurrent = measureFullWordRecall(requireNotNull(currentIndex), "tt-current", fullWordRows)
        val tatarWidened = measureFullWordRecall(requireNotNull(widenedIndex), "tt-widened", fullWordRows)
        val tatarGuarded = measureFullWordRecallGuarded(
            requireNotNull(widenedIndex), requireNotNull(tatarBigramIndex), "tt-guard",
            fullWordRows, tatarContextByWord,
        )
        val russianCurrent =
            measureFullWordRecall(requireNotNull(russianCurrentIndex), "ru-current", russianFullWordRows)
        val russianWidened =
            measureFullWordRecall(requireNotNull(russianWidenedIndex), "ru-widened", russianFullWordRows)
        val russianGuarded = measureFullWordRecallGuarded(
            requireNotNull(russianWidenedIndex), requireNotNull(russianBigramIndex), "ru-guard",
            russianFullWordRows, russianContextByWord,
        )

        assertEquals(PIN_TT_CURRENT_RECOVERED to PIN_TT_CURRENT_HARMFUL, tatarCurrent)
        assertEquals(PIN_TT_WIDENED_RECOVERED to PIN_TT_WIDENED_HARMFUL, tatarWidened)
        assertEquals(PIN_TT_GUARD_RECOVERED to PIN_TT_GUARD_HARMFUL, tatarGuarded)
        assertEquals(PIN_RU_CURRENT_RECOVERED to PIN_RU_CURRENT_HARMFUL, russianCurrent)
        assertEquals(PIN_RU_WIDENED_RECOVERED to PIN_RU_WIDENED_HARMFUL, russianWidened)
        assertEquals(PIN_RU_GUARD_RECOVERED to PIN_RU_GUARD_HARMFUL, russianGuarded)
        assertTrue(tatarWidened.first * 100 >= fullWordRows.size * 25)
        assertTrue(russianWidened.first * 100 >= russianFullWordRows.size * 25)
    }

    @Test
    fun frequencyFloorsCannotMeetBothRecallAndValidOovSafety() {
        val raw = inflate(DictionaryArtifactSpec.TATAR_TOP100K_V1)
        val sortedFrequencies = vocabulary.map { requireNotNull(currentIndex).frequencyOf(it) }.sorted()
        val expected = mapOf(
            10_000 to Triple(3_284L, 2_929, 4),
            5_000 to Triple(7_566L, 2_178, 3),
            2_000 to Triple(20_012L, 1_265, 2),
            1_000 to Triple(38_359L, 755, 1),
        )
        for ((rank, pins) in expected) {
            val floor = sortedFrequencies[sortedFrequencies.size - rank]
            val index = openIndex(raw, WIDENED_POLICY, floor)
                .also { it.updateKeyNeighbors(neighborTable) }
            var recovered = 0
            for (row in fullWordRows) {
                index.lookup(ImmutableUtf8Prefix.copyOf(row.mutated.toByteArray(Charsets.UTF_8)))
                if (index.lastAutocorrectAdvice?.replacement == row.original) recovered++
            }
            val offenders = ArrayList<String>()
            for (row in safetyRows) {
                index.lookup(ImmutableUtf8Prefix.copyOf(row.word.toByteArray(Charsets.UTF_8)))
                val advice = index.lastAutocorrectAdvice ?: continue
                offenders.add("${advice.typedWord}->${advice.replacement} (${advice.frequency})")
            }
            println(
                "widening floor_rank=$rank frequency=$floor " +
                    "recall=$recovered/${fullWordRows.size} pct=${fmt(recovered * 100.0 / fullWordRows.size)} " +
                    "valid_oov_false=${offenders.size} offenders=${offenders.sorted()}",
            )
            assertEquals(pins.first, floor)
            assertEquals(pins.second, recovered)
            assertEquals(pins.third, offenders.size)
            assertTrue(recovered * 100 < fullWordRows.size * 25 || offenders.isNotEmpty())
        }
    }

    /** Measures both arms on the same full-word samples with the existing host-time budget. */
    @Test
    fun hostP95WithWidenedAutocorrectStaysWithinBudget() {
        val tatarSample = fullWordRows
            .filter { it.mutated.codePointCount(0, it.mutated.length) >= 4 }
            .filterIndexed { index, _ -> index % 5 == 0 }
            .map { ImmutableUtf8Prefix.copyOf(it.mutated.toByteArray(Charsets.UTF_8)) }
        val russianSample = russianFullWordRows
            .filter { it.mutated.codePointCount(0, it.mutated.length) >= 4 }
            .filterIndexed { index, _ -> index % 5 == 0 }
            .map { ImmutableUtf8Prefix.copyOf(it.mutated.toByteArray(Charsets.UTF_8)) }
        assertTrue("the Tatar perf sample is meaningful", tatarSample.size > 1_000)
        assertTrue("the Russian perf sample is meaningful", russianSample.size > 1_000)

        val arms = listOf(
            Triple("tt-current", requireNotNull(currentIndex), tatarSample),
            Triple("tt-widened", requireNotNull(widenedIndex), tatarSample),
            Triple("ru-current", requireNotNull(russianCurrentIndex), russianSample),
            Triple("ru-widened", requireNotNull(russianWidenedIndex), russianSample),
        )
        for ((name, index, sample) in arms) {
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
                "widening perf arm=$name samples=${timings.size} p50=${fmtMs(p50)} ms " +
                    "p95=${fmtMs(p95)} ms max=${fmtMs(max)} ms " +
                    "acprobe_p95=${percentile(probes.sorted(), 0.95)} " +
                    "acprobe_max=${probes.maxOrNull() ?: 0} consumed=$consumed",
            )
            assertTrue("$name p95=${p95}ms exceeds the 5 ms budget", p95 <= 5.0)
        }
    }

    /**
     * The widened policy's pinned real cases: "ертты" — absent, NOT reachable by class #1, and
     * exactly one single-substitution dictionary neighbor (артты, frequency 14 458, above the
     * Tatar floor) — is corrected by WIDENED and left alone by CURRENT; the two-candidate
     * "ааҗнңң" (ааҗный + ааҗнең) is refused by the single-candidate rule under WIDENED too.
     */
    @Test
    fun theWidenedPolicyCorrectsAClass4OnlyCaseAndRefusesAmbiguity() {
        val current = requireNotNull(currentIndex)
        val widened = requireNotNull(widenedIndex)

        fun advice(index: TdictPrefixIndex, word: String): AutocorrectAdvice? {
            index.lookup(ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8)))
            return index.lastAutocorrectAdvice
        }

        assertNull("CURRENT leaves the class-#4-only case alone", advice(current, "ертты"))
        val widenedAdvice = advice(widened, "ертты")
        assertNotNull("WIDENED corrects the class-#4-only case", widenedAdvice)
        assertEquals("ертты", widenedAdvice!!.typedWord)
        assertEquals("артты", widenedAdvice.replacement)
        assertEquals(14_461L, widenedAdvice.frequency)

        assertNull("two class-#4 candidates: the single-candidate rule still refuses",
            advice(widened, "ааҗнңң"))
        assertNull(advice(current, "ааҗнңң"))
    }

    @Test
    fun shippedPoliciesShipDl1AutocorrectUnderTheContextGuard() {
        // Both shipped policies autocorrect from class #1 and the DL-1 tier; tier matches fire
        // only as context successors (the guard arms above carry the measurement).
        assertEquals(
            listOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
            FuzzyEditPolicy.TATAR.autocorrectClasses.toList(),
        )
        assertEquals(
            listOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
            FuzzyEditPolicy.RUSSIAN.autocorrectClasses.toList(),
        )
    }

    /**
     * Zero-allocation contract with the widened advice pass engaged: an absent >= 4-cp word that
     * does not fire (the common path) stays within <= 8 bytes/lookup. The firing path allocates
     * the AutocorrectAdvice object itself, as the class-#1 pass does; that is out of scope.
     */
    @Test
    fun widenedAutocorrectCommonPathHasNoPerLookupAllocation() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.Assume.assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        // A fixture dictionary whose DL-1 neighborhood holds neither a strip survivor nor an
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
        // The two arms differ ONLY in the autocorrect class set; the display side of both is the
        // shipped Tatar configuration.
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
        private val RUSSIAN_CURRENT_POLICY = FuzzyEditPolicy(
            intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
            true,
            intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS),
            sameLengthBonusOnlyInDl1 = true,
        )
        private val RUSSIAN_WIDENED_POLICY = FuzzyEditPolicy(
            intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
            true,
            intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
            sameLengthBonusOnlyInDl1 = true,
        )

        // Pins over the committed assets, generators and reviewed safety sets.
        private const val PIN_VALID_OOV_ROWS = 220
        private const val PIN_TT_CURRENT_SEEN_FORM = 0
        private const val PIN_TT_CURRENT_NEW_FORM = 1
        private const val PIN_TT_CURRENT_UNSEEN_STEM = 0
        private const val PIN_TT_WIDENED_SEEN_FORM = 0
        private const val PIN_TT_WIDENED_NEW_FORM = 1
        private const val PIN_TT_WIDENED_UNSEEN_STEM = 3
        private const val PIN_TT_CURRENT_FIRES = 1
        private const val PIN_TT_WIDENED_FIRES = 4
        private const val PIN_RU_CURRENT_SEEN_FORM = 0
        private const val PIN_RU_CURRENT_NEW_FORM = 0
        private const val PIN_RU_CURRENT_UNSEEN_STEM = 0
        private const val PIN_RU_WIDENED_SEEN_FORM = 0
        private const val PIN_RU_WIDENED_NEW_FORM = 0
        private const val PIN_RU_WIDENED_UNSEEN_STEM = 0
        private const val PIN_TT_CURRENT_RECOVERED = 7
        private const val PIN_TT_CURRENT_HARMFUL = 10
        private const val PIN_TT_WIDENED_RECOVERED = 2_929
        private const val PIN_TT_WIDENED_HARMFUL = 0
        private const val PIN_RU_CURRENT_RECOVERED = 0
        private const val PIN_RU_CURRENT_HARMFUL = 4
        private const val PIN_RU_WIDENED_RECOVERED = 3_433
        private const val PIN_RU_WIDENED_HARMFUL = 0

        // The guard arms: the widened policy armed with the preceding eval word's packed bigram
        // successors, the production mid-sentence shape. Pinned from the committed dictionaries,
        // bigram tables, eval sets and the first-predecessor context map. The require rule costs
        // recall where the packed successors lack the pair (the widening suite prints
        // guard_refused_correct), and blocks high-frequency false fires a floor cannot reach.
        private const val PIN_TT_GUARD_SEEN_FORM = 0
        private const val PIN_TT_GUARD_NEW_FORM = 0
        private const val PIN_TT_GUARD_UNSEEN_STEM = 1
        private const val PIN_TT_GUARD_FIRES = 1
        private const val PIN_RU_GUARD_SEEN_FORM = 0
        private const val PIN_RU_GUARD_NEW_FORM = 0
        private const val PIN_RU_GUARD_UNSEEN_STEM = 0
        private const val PIN_RU_GUARD_FIRES = 0
        private const val PIN_TT_GUARD_RECOVERED = 848
        private const val PIN_TT_GUARD_HARMFUL = 0
        private const val PIN_RU_GUARD_RECOVERED = 797
        private const val PIN_RU_GUARD_HARMFUL = 0

        private val SAFETY_STRATA = listOf("seen_form", "new_form_of_seen_stem", "unseen_stem")
        private val neighborTable = TatarLayoutTestFixtures.tatarNeighborTable()
        private val russianNeighborTable = KeyNeighborTable.build(
            "ru",
            true,
            listOf(
                rawKey('й'), rawKey('ц'), rawKey('у', 'ү'), rawKey('к'), rawKey('е', 'ё'),
                rawKey('н', 'ң'), rawKey('г', 'һ'), rawKey('ш'), rawKey('щ'), rawKey('з'),
                rawKey('х', 'һ'), rawKey('ф'), rawKey('ы'), rawKey('в'), rawKey('а', 'ә'),
                rawKey('п'), rawKey('р'), rawKey('о', 'ө'), rawKey('л'), rawKey('д'),
                rawKey('ж', 'җ'), rawKey('э', 'ә'), rawKey('я'), rawKey('ч'), rawKey('с'),
                rawKey('м'), rawKey('и'), rawKey('т'), rawKey('ь', 'ъ'), rawKey('б'), rawKey('ю'),
            ),
        )
        private lateinit var vocabulary: List<String>
        private lateinit var uniqueEvalWords: List<String>
        private lateinit var safetyRows: List<SafetyRow>
        private lateinit var fullWordRows: List<TypoMutatedEvalTest.EvalRow>
        private lateinit var russianFullWordRows: List<TypoMutatedEvalTest.EvalRow>
        private lateinit var russianSafetyRows: List<SafetyRow>
        private lateinit var russianVocabulary: List<String>
        // The guard arm inputs: the shipped bigram tables (linked to the same dictionary bytes
        // the arms run on) and the first-predecessor context map of each eval set.
        private lateinit var tatarContextByWord: Map<String, String>
        private lateinit var russianContextByWord: Map<String, String>
        private var currentIndex: TdictPrefixIndex? = null
        private var widenedIndex: TdictPrefixIndex? = null
        private var russianCurrentIndex: TdictPrefixIndex? = null
        private var russianWidenedIndex: TdictPrefixIndex? = null
        private var tatarBigramIndex: TatBigrPrefixIndex? = null
        private var russianBigramIndex: TatBigrPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssets() {
            TypoMutatedEvalTest.loadCommittedAssetsAndEvalSet()
            fullWordRows = TypoMutatedEvalTest().buildSet(
                TypoMutatedEvalTest.substrate,
                TypoMutatedEvalTest.GEOMETRIC_NEIGHBORS,
                TypoMutatedEvalTest.ALPHABET,
            ).rows
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
            tatarContextByWord = predecessorByWord(evalLines)
            safetyRows = locate(
                "src/test/resources/tt_valid_oov_safety_set.tsv",
                "app/src/test/resources/tt_valid_oov_safety_set.tsv",
            ).readLines(Charsets.UTF_8).filter { it.isNotEmpty() }.map { line ->
                val fields = line.split("\t")
                check(fields.size == 2) { "malformed valid-OOV safety row: $line" }
                check(fields[0] in uniqueEvalWords) { "safety word is not held out: ${fields[0]}" }
                check(fields[1] in SAFETY_STRATA) { "unknown safety stratum: ${fields[1]}" }
                SafetyRow(fields[0], fields[1])
            }
            check(safetyRows.isNotEmpty()) { "the valid-OOV safety set is empty" }

            val index = openIndex(raw, CURRENT_POLICY)
            currentIndex = index.also { it.updateKeyNeighbors(neighborTable) }
            widenedIndex = openIndex(raw, WIDENED_POLICY).also { it.updateKeyNeighbors(neighborTable) }
            tatarBigramIndex = openBigramIndex(BigramArtifactSpec.TATAR_BIGRAMS_V1, index)

            russianFullWordRows = locate(
                "src/test/resources/ru_typo_eval_set.tsv",
                "app/src/test/resources/ru_typo_eval_set.tsv",
            ).readLines(Charsets.UTF_8).filter { it.isNotEmpty() }.map { line ->
                val fields = line.split("\t")
                check(fields.size == 3) { "malformed typo row: $line" }
                TypoMutatedEvalTest.EvalRow(fields[0], fields[1], fields[2])
            }
            russianSafetyRows = locate(
                "src/test/resources/ru_valid_oov_safety_set.tsv",
                "app/src/test/resources/ru_valid_oov_safety_set.tsv",
            ).readLines(Charsets.UTF_8).filter { it.isNotEmpty() }.map { line ->
                val fields = line.split("\t")
                check(fields.size == 2) { "malformed valid-OOV safety row: $line" }
                check(fields[1] in SAFETY_STRATA) { "unknown safety stratum: ${fields[1]}" }
                SafetyRow(fields[0], fields[1])
            }
            val russianRaw = inflate(DictionaryArtifactSpec.RUSSIAN_TOP100K_V1)
            russianVocabulary = DictionaryTestFixtures.words(russianRaw)
            russianCurrentIndex = openIndex(
                russianRaw,
                RUSSIAN_CURRENT_POLICY,
                AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
                DictionaryArtifactSpec.RUSSIAN_TOP100K_V1,
            ).also { it.updateKeyNeighbors(russianNeighborTable) }
            russianWidenedIndex = openIndex(
                russianRaw,
                RUSSIAN_WIDENED_POLICY,
                AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
                DictionaryArtifactSpec.RUSSIAN_TOP100K_V1,
            ).also { it.updateKeyNeighbors(russianNeighborTable) }
            val russianEvalLines = locate(
                "src/test/resources/ru_eval_sentences.txt",
                "app/src/test/resources/ru_eval_sentences.txt",
            ).readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            russianContextByWord = predecessorByWord(russianEvalLines)
            russianBigramIndex = openBigramIndex(
                BigramArtifactSpec.RUSSIAN_BIGRAMS_V1, requireNotNull(russianCurrentIndex),
            )
        }

        /**
         * The first-seen preceding token of every eval word that ever follows another token,
         * scanning the eval lines in file order: the context the guard arms type each row after.
         */
        private fun predecessorByWord(lines: List<String>): Map<String, String> {
            val predecessors = HashMap<String, String>()
            for (line in lines) {
                val words = line.split(" ")
                for (position in 1 until words.size) {
                    predecessors.putIfAbsent(words[position], words[position - 1])
                }
            }
            return predecessors
        }

        /** Inflates and validates a shipped bigram table, linked to [dictionary]. */
        private fun openBigramIndex(
            spec: BigramArtifactSpec,
            dictionary: TdictPrefixIndex,
        ): TatBigrPrefixIndex {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("autocorrect-widening-bigrams-", ".tatbigr")
            try {
                rawFile.outputStream().use { output ->
                    TatBigrValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TatBigrValidator().validateRaw(rawFile, spec)
                val identity = BigramTableIdentity(
                    spec.generation, spec.fileLanguageTag, validated.schemaId,
                    validated.formatVersion, validated.rawSha256,
                )
                return requireNotNull(
                    TatBigrPrefixIndex.open(
                        ByteBuffer.wrap(rawFile.readBytes()), identity, dictionary,
                        validated.headCount, validated.rawSize,
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun inflate(spec: DictionaryArtifactSpec): ByteArray {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("autocorrect-widening-", ".tdict")
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
            policy: FuzzyEditPolicy,
            minCandidateFrequency: Long = AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
            spec: DictionaryArtifactSpec = DictionaryArtifactSpec.TATAR_TOP100K_V1,
        ): TdictPrefixIndex {
            val identity = DictionaryIdentity(
                spec.generation, spec.schemaId, spec.formatVersion,
                MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) },
            )
            return requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw), identity, spec.expectedEntryCount, raw.size.toLong(),
                    null, policy, minCandidateFrequency,
                ),
            )
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed test resource")
    }
}
