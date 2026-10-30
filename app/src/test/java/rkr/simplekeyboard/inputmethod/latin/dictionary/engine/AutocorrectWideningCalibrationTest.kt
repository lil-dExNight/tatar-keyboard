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
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.E3aTestFixtures.rawKey
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Compares long-press-only advice with a test-only full DL-1 arm in both languages. Recall,
 * reviewed valid-OOV replacements, correctly typed words, ambiguity and host cost stay separate.
 * Frequency floors that retain recall do not meet valid-OOV safety, so shipped policies keep
 * long-press-only advice and autocorrect remains opt-in.
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
                println("E02 safety arm=$arm stratum=$stratum words=$words false_replacements=${counts.getValue(stratum)}")
            }
            println("E02 safety arm=$arm valid_oov_words=$measured false_replacements=${counts.values.sum()}")
            for (line in offenders.sorted()) println("E02 safety arm=$arm would-fire: $line")
            return counts
        }

        val current = measure(requireNotNull(currentIndex), "tt-current")
        val widened = measure(requireNotNull(widenedIndex), "tt-widened")
        assertEquals(PIN_TT_CURRENT_SEEN_FORM, current.getValue("seen_form"))
        assertEquals(PIN_TT_CURRENT_NEW_FORM, current.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_TT_CURRENT_UNSEEN_STEM, current.getValue("unseen_stem"))
        assertEquals(PIN_TT_WIDENED_SEEN_FORM, widened.getValue("seen_form"))
        assertEquals(PIN_TT_WIDENED_NEW_FORM, widened.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_TT_WIDENED_UNSEEN_STEM, widened.getValue("unseen_stem"))
        assertEquals(PIN_TT_CURRENT_FIRES, current.values.sum())
        assertEquals(PIN_TT_WIDENED_FIRES, widened.values.sum())
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
                    "E02 safety arm=$arm stratum=$stratum words=$words " +
                        "false_replacements=${counts.getValue(stratum)}",
                )
            }
            println(
                "E02 safety arm=$arm valid_oov_words=${russianSafetyRows.size} " +
                    "false_replacements=${counts.values.sum()} offenders=${offenders.sorted()}",
            )
            return counts
        }

        val current = measure(requireNotNull(russianCurrentIndex), "ru-current")
        val widened = measure(requireNotNull(russianWidenedIndex), "ru-widened")
        assertEquals(PIN_RU_CURRENT_SEEN_FORM, current.getValue("seen_form"))
        assertEquals(PIN_RU_CURRENT_NEW_FORM, current.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_RU_CURRENT_UNSEEN_STEM, current.getValue("unseen_stem"))
        assertEquals(PIN_RU_WIDENED_SEEN_FORM, widened.getValue("seen_form"))
        assertEquals(PIN_RU_WIDENED_NEW_FORM, widened.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_RU_WIDENED_UNSEEN_STEM, widened.getValue("unseen_stem"))
    }

    @Test
    fun correctlyTypedDictionaryWordsAreNeverReplaced() {
        fun verify(index: TdictPrefixIndex, words: List<String>, arm: String) {
            var replacements = 0
            for (word in words) {
                index.lookup(ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8)))
                if (index.lastAutocorrectAdvice != null) replacements++
            }
            println("E02 correct_words arm=$arm words=${words.size} replacements=$replacements")
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
                "E02 recall arm=$arm class=$editClass rows=$hits/$classRows " +
                    "pct=${fmt(hits * 100.0 / classRows)}",
            )
        }
        val total = recovered.values.sum()
        println(
            "E02 recall arm=$arm rows=$total/${rows.size} " +
                "pct=${fmt(total * 100.0 / rows.size)} harmful=$harmful",
        )
        return total to harmful
    }

    @Test
    fun fullWordRecallAndHarmfulReplacementsArePinned() {
        val tatarCurrent = measureFullWordRecall(requireNotNull(currentIndex), "tt-current", fullWordRows)
        val tatarWidened = measureFullWordRecall(requireNotNull(widenedIndex), "tt-widened", fullWordRows)
        val russianCurrent =
            measureFullWordRecall(requireNotNull(russianCurrentIndex), "ru-current", russianFullWordRows)
        val russianWidened =
            measureFullWordRecall(requireNotNull(russianWidenedIndex), "ru-widened", russianFullWordRows)

        assertEquals(PIN_TT_CURRENT_RECOVERED to PIN_TT_CURRENT_HARMFUL, tatarCurrent)
        assertEquals(PIN_TT_WIDENED_RECOVERED to PIN_TT_WIDENED_HARMFUL, tatarWidened)
        assertEquals(PIN_RU_CURRENT_RECOVERED to PIN_RU_CURRENT_HARMFUL, russianCurrent)
        assertEquals(PIN_RU_WIDENED_RECOVERED to PIN_RU_WIDENED_HARMFUL, russianWidened)
        assertTrue(tatarWidened.first * 100 >= fullWordRows.size * 25)
        assertTrue(russianWidened.first * 100 >= russianFullWordRows.size * 25)
    }

    @Test
    fun frequencyFloorsCannotMeetBothRecallAndValidOovSafety() {
        val raw = inflate(DictionaryArtifactSpec.TATAR_TOP100K_V1)
        val sortedFrequencies = vocabulary.map { requireNotNull(currentIndex).frequencyOf(it) }.sorted()
        val expected = mapOf(
            10_000 to Triple(3_283L, 2_960, 3),
            5_000 to Triple(7_565L, 2_157, 3),
            2_000 to Triple(20_010L, 1_290, 1),
            1_000 to Triple(38_353L, 777, 0),
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
                "E02 floor_rank=$rank frequency=$floor recall=$recovered/${fullWordRows.size} " +
                    "pct=${fmt(recovered * 100.0 / fullWordRows.size)} " +
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
                "E02 perf arm=$name samples=${timings.size} p50=${fmtMs(p50)} ms " +
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
        assertEquals(14_458L, widenedAdvice.frequency)

        assertNull("two class-#4 candidates: the single-candidate rule still refuses",
            advice(widened, "ааҗнңң"))
        assertNull(advice(current, "ааҗнңң"))
    }

    @Test
    fun shippedPoliciesKeepLongPressOnlyAutocorrect() {
        assertEquals(
            listOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS),
            FuzzyEditPolicy.TATAR.autocorrectClasses.toList(),
        )
        assertEquals(
            listOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS),
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
        private val RUSSIAN_WIDENED_POLICY = FuzzyEditPolicy(
            intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
            true,
            intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
            sameLengthBonusOnlyInDl1 = true,
        )

        // Pins over the committed assets, generators and reviewed safety sets.
        private const val PIN_VALID_OOV_ROWS = 175
        private const val PIN_TT_CURRENT_SEEN_FORM = 0
        private const val PIN_TT_CURRENT_NEW_FORM = 2
        private const val PIN_TT_CURRENT_UNSEEN_STEM = 0
        private const val PIN_TT_WIDENED_SEEN_FORM = 0
        private const val PIN_TT_WIDENED_NEW_FORM = 2
        private const val PIN_TT_WIDENED_UNSEEN_STEM = 1
        private const val PIN_TT_CURRENT_FIRES = 2
        private const val PIN_TT_WIDENED_FIRES = 3
        private const val PIN_RU_CURRENT_SEEN_FORM = 0
        private const val PIN_RU_CURRENT_NEW_FORM = 0
        private const val PIN_RU_CURRENT_UNSEEN_STEM = 0
        private const val PIN_RU_WIDENED_SEEN_FORM = 0
        private const val PIN_RU_WIDENED_NEW_FORM = 0
        private const val PIN_RU_WIDENED_UNSEEN_STEM = 2
        private const val PIN_TT_CURRENT_RECOVERED = 8
        private const val PIN_TT_CURRENT_HARMFUL = 14
        private const val PIN_TT_WIDENED_RECOVERED = 2_960
        private const val PIN_TT_WIDENED_HARMFUL = 0
        private const val PIN_RU_CURRENT_RECOVERED = 0
        private const val PIN_RU_CURRENT_HARMFUL = 3
        private const val PIN_RU_WIDENED_RECOVERED = 3_223
        private const val PIN_RU_WIDENED_HARMFUL = 0

        private val SAFETY_STRATA = listOf("seen_form", "new_form_of_seen_stem", "unseen_stem")
        private val neighborTable = E3bTestFixtures.tatarNeighborTable()
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
        private var currentIndex: TdictPrefixIndex? = null
        private var widenedIndex: TdictPrefixIndex? = null
        private var russianCurrentIndex: TdictPrefixIndex? = null
        private var russianWidenedIndex: TdictPrefixIndex? = null

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
                FuzzyEditPolicy.RUSSIAN,
                AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
                DictionaryArtifactSpec.RUSSIAN_TOP100K_V1,
            ).also { it.updateKeyNeighbors(russianNeighborTable) }
            russianWidenedIndex = openIndex(
                russianRaw,
                RUSSIAN_WIDENED_POLICY,
                AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
                DictionaryArtifactSpec.RUSSIAN_TOP100K_V1,
            ).also { it.updateKeyNeighbors(russianNeighborTable) }
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
