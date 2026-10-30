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
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.E3aTestFixtures.rawKey
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionStripState
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Measures the committed Russian typo set with [FuzzyEditPolicy.RUSSIAN].
 * Positive recall, budget trips and host latency stay separate from false-trigger measurements
 * over correctly typed dictionary words and every reviewed valid-OOV row. The safety report
 * covers all lemma strata. Set identities, recovery bars and measured counts are pinned.
 */
class RuTypoMutatedEvalTest {

    private data class EvalRow(val original: String, val editClass: String, val mutated: String)
    private data class SafetyRow(val word: String, val stratum: String)

    // ---- The committed set. ----

    @Test
    fun theCommittedSetMatchesItsPins() {
        assertEquals(PIN_SET_ROWS, rows.size)
        assertEquals(PIN_SET_BYTES, setText.toByteArray(Charsets.UTF_8).size)
        assertEquals(PIN_SET_SHA256, sha256(setText))
        assertEquals(PIN_SUBSTRATE_WORDS, substrate.size)
        val rowsPerClass = rows.groupingBy { it.editClass }.eachCount()
        for (className in CLASS_NAMES) {
            assertEquals(PIN_SUBSTRATE_WORDS, rowsPerClass.getValue(className))
        }
    }

    /**
     * Every row differs from its original in exactly the edit of its class. The geometric
     * membership of a substitution is checked Python-side; here the `ins` letters are checked
     * against the layout node set, tying the committed set to the fixture below.
     */
    @Test
    fun everyRowDiffersByExactlyTheClassEdit() {
        val alphabet = neighborTable.nodes.toSet()
        for (row in rows) {
            val original = row.original
            val mutated = row.mutated
            assertTrue(original != mutated)
            when (row.editClass) {
                // All letters here are BMP, so Char indexing is code-point indexing.
                "sub" -> {
                    assertEquals(original.length, mutated.length)
                    val differing = original.indices.count { original[it] != mutated[it] }
                    assertTrue("$original->$mutated", differing == 1)
                }
                "del" -> {
                    assertEquals(original.length - 1, mutated.length)
                    assertTrue(
                        "$original->$mutated",
                        original.indices.any { original.removeRange(it, it + 1) == mutated },
                    )
                }
                "ins" -> {
                    assertEquals(original.length + 1, mutated.length)
                    val hits = mutated.indices.filter {
                        mutated.removeRange(it, it + 1) == original
                    }
                    assertTrue("$original->$mutated", hits.isNotEmpty())
                    assertTrue(alphabet.contains(mutated[hits.first()].code))
                }
                "trans" -> {
                    assertEquals(original.length, mutated.length)
                    assertTrue(
                        "$original->$mutated",
                        (0 until original.length - 1).any { i ->
                            original[i] == mutated[i + 1] &&
                                original[i + 1] == mutated[i] &&
                                original.substring(0, i) == mutated.substring(0, i) &&
                                original.substring(i + 2) == mutated.substring(i + 2)
                        },
                    )
                }
                else -> error("unknown class ${row.editClass}")
            }
        }
    }

    @Test
    fun theNeighborTableIsTheRussianLayout() {
        assertEquals(31, neighborTable.letterKeyCount)
        assertEquals(39, neighborTable.nodes.size)
        assertTrue(neighborTable.nodes.contentEquals(neighborTable.nodes.sortedArray()))
        assertEquals(neighborTable.nodes.size, neighborTable.nodes.distinct().size)
    }

    @Test
    fun noLongPressPairConnectsTwoLetterKeys() {
        // In the Russian layout every long-press partner of a letter key is a more-key-only
        // letter, never another letter key. So edit class #1 alone can never undo a geometric
        // substitution (key letter -> key letter); only the tier's alphabet-wide substitution
        // family can (pinned by theSubstitutionFamilyDrawsFromTheFullLayoutAlphabet).
        val keyLetters = RUSSIAN_LAYOUT_KEYS.map { it.codePoint }.toSet()
        assertEquals(neighborTable.letterKeyCount, keyLetters.size)
        for (key in RUSSIAN_LAYOUT_KEYS) {
            for (partner in key.moreKeyCodePoints) {
                assertTrue(!keyLetters.contains(partner))
            }
        }
    }

    /**
     * The tier's substitution family draws from the full layout alphabet (KeyNeighborTable.nodes),
     * not from the sparse long-press partner map: "шолоко" recovers "молоко" (ш→м at position 0)
     * though neither letter has any long-press partner, so no class #1 variant can connect them.
     * The control arm (the DEFAULT policy, class #1 only) provably recovers nothing here.
     */
    @Test
    fun theSubstitutionFamilyDrawsFromTheFullLayoutAlphabet() {
        assertTrue(neighborTable.nodes.contains('ш'.code))
        assertTrue(neighborTable.nodes.contains('м'.code))
        assertTrue(neighborTable.longPressPartnersOf('ш'.code) == null)
        assertTrue(neighborTable.longPressPartnersOf('м'.code) == null)

        val tierIndex = EngineTestFixtures.index(
            listOf("молоко" to 10L),
            fuzzyEditPolicy = FuzzyEditPolicy.RUSSIAN,
        )
        tierIndex.updateKeyNeighbors(neighborTable)
        assertEquals(
            listOf("молоко"),
            tierIndex.lookup(ImmutableUtf8Prefix.copyOf("шолоко".toByteArray(Charsets.UTF_8))),
        )

        val class1OnlyIndex = EngineTestFixtures.index(listOf("молоко" to 10L))
        class1OnlyIndex.updateKeyNeighbors(neighborTable)
        assertEquals(
            emptyList<String>(),
            class1OnlyIndex.lookup(ImmutableUtf8Prefix.copyOf("шолоко".toByteArray(Charsets.UTF_8))),
        )
    }

    // ---- The recovery baseline under the tier. ----

    @Test
    fun typoRecoveryBaselinePerEditClass() {
        val index = requireNotNull(russianIndex)
        assertEquals(PIN_SET_ROWS, rows.size)

        // The hit path is the completion path: TdictPrefixIndex.lookup returns the strip cells
        // (at most MAX_RESULTS), exact candidates first, typo-recovery candidates only in cells
        // the exact pass left empty. The production arm carries the layout-derived neighbor
        // table (KeyNeighborTableBuilder.fromKeyboard on device, the equivalent fixture here);
        // the control arm has no table, so only the exact pass runs.
        index.updateKeyNeighbors(neighborTable)
        val withFuzzy = countHits(index)
        index.updateKeyNeighbors(null)
        val exactOnly = countHits(index)

        val rowsPerClass = rows.groupingBy { it.editClass }.eachCount()
        var totalTop1 = 0
        var totalVisible = 0
        var totalEngine = 0
        var totalExactTop1 = 0
        var totalExactVisible = 0
        var totalExactEngine = 0
        for (className in CLASS_NAMES) {
            val classRows = rowsPerClass.getValue(className)
            val top1 = withFuzzy.top1PerClass.getValue(className)
            val visible = withFuzzy.visibleTop3PerClass.getValue(className)
            val engine = withFuzzy.engineTop4PerClass.getValue(className)
            val exactTop1 = exactOnly.top1PerClass.getValue(className)
            val exactVisible = exactOnly.visibleTop3PerClass.getValue(className)
            val exactEngine = exactOnly.engineTop4PerClass.getValue(className)
            totalTop1 += top1
            totalVisible += visible
            totalEngine += engine
            totalExactTop1 += exactTop1
            totalExactVisible += exactVisible
            totalExactEngine += exactEngine
            println("EVAL|typo_${className}_rows|$classRows")
            println("EVAL|typo_${className}_top1_hits|$top1")
            println("EVAL|typo_${className}_top1_pct|${format(top1 * 100.0 / classRows)}")
            println("EVAL|typo_${className}_visible_top3_hits|$visible")
            println("EVAL|typo_${className}_visible_top3_pct|${format(visible * 100.0 / classRows)}")
            println("EVAL|typo_${className}_engine_top4_hits|$engine")
            println("EVAL|typo_${className}_engine_top4_pct|${format(engine * 100.0 / classRows)}")
            println("EVAL|typo_${className}_top1_hits_exactonly|$exactTop1")
            println("EVAL|typo_${className}_visible_top3_hits_exactonly|$exactVisible")
            println("EVAL|typo_${className}_engine_top4_hits_exactonly|$exactEngine")
            assertTrue(top1 <= visible && visible <= engine)
        }
        println("EVAL|typo_total_rows|${rows.size}")
        println("EVAL|typo_total_top1_hits|$totalTop1")
        println("EVAL|typo_total_top1_pct|${format(totalTop1 * 100.0 / rows.size)}")
        println("EVAL|typo_total_visible_top3_hits|$totalVisible")
        println("EVAL|typo_total_visible_top3_pct|${format(totalVisible * 100.0 / rows.size)}")
        println("EVAL|typo_total_engine_top4_hits|$totalEngine")
        println("EVAL|typo_total_engine_top4_pct|${format(totalEngine * 100.0 / rows.size)}")
        println("EVAL|typo_total_top1_hits_exactonly|$totalExactTop1")
        println("EVAL|typo_total_visible_top3_hits_exactonly|$totalExactVisible")
        println("EVAL|typo_total_engine_top4_hits_exactonly|$totalExactEngine")
        println("EVAL|typo_fuzzy_over_budget|${withFuzzy.overBudget}")
        println("EVAL|typo_fuzzy_over_budget_exactonly|${exactOnly.overBudget}")

        // The exact-only control: the exact pass cannot fix any of the four edits except a
        // last-position deletion (a prefix hit), so only del scores, at the pinned count.
        assertEquals(0, exactOnly.engineTop4PerClass.getValue("sub"))
        assertEquals(0, exactOnly.engineTop4PerClass.getValue("ins"))
        assertEquals(0, exactOnly.engineTop4PerClass.getValue("trans"))
        assertEquals(
            PIN_DEL_ENGINE_TOP4_HITS_EXACTONLY, exactOnly.engineTop4PerClass.getValue("del"),
        )

        // The production arm: the DL-1 tier recovers all four classes. The counts are exact
        // pins over the committed set; the pre-registered bars are the floor a re-pin must
        // never drop below silently.
        assertEquals(PIN_SUB_ENGINE_TOP4_HITS, withFuzzy.engineTop4PerClass.getValue("sub"))
        assertEquals(PIN_DEL_ENGINE_TOP4_HITS, withFuzzy.engineTop4PerClass.getValue("del"))
        assertEquals(PIN_INS_ENGINE_TOP4_HITS, withFuzzy.engineTop4PerClass.getValue("ins"))
        assertEquals(PIN_TRANS_ENGINE_TOP4_HITS, withFuzzy.engineTop4PerClass.getValue("trans"))
        for ((className, bar) in CLASS_BARS) {
            val pct = withFuzzy.engineTop4PerClass.getValue(className) * 100.0 /
                rowsPerClass.getValue(className)
            assertTrue(
                "$className recovery ${format(pct)}% is below the ${format(bar)}% bar",
                pct >= bar,
            )
        }
        assertEquals("the fuzzy budget must never trip on the eval set", 0, withFuzzy.overBudget)
    }

    private class HitCount(
        val top1PerClass: Map<String, Int>,
        val visibleTop3PerClass: Map<String, Int>,
        val engineTop4PerClass: Map<String, Int>,
        val overBudget: Int,
    )

    private fun countHits(index: TdictPrefixIndex): HitCount {
        val top1 = HashMap<String, Int>()
        val visible = HashMap<String, Int>()
        val engine = HashMap<String, Int>()
        for (className in CLASS_NAMES) {
            top1[className] = 0
            visible[className] = 0
            engine[className] = 0
        }
        var overBudget = 0
        for (row in rows) {
            val results = index.lookup(
                ImmutableUtf8Prefix.copyOf(row.mutated.toByteArray(Charsets.UTF_8))
            )
            if (index.lastFuzzyOverBudget) overBudget++
            if (results.firstOrNull() == row.original) {
                top1[row.editClass] = top1.getValue(row.editClass) + 1
            }
            if (results.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT)
                .contains(row.original)
            ) {
                visible[row.editClass] = visible.getValue(row.editClass) + 1
            }
            if (results.take(SuggestionStripState.CELL_COUNT).contains(row.original)) {
                engine[row.editClass] = engine.getValue(row.editClass) + 1
            }
        }
        return HitCount(top1, visible, engine, overBudget)
    }

    /**
     * Host compute with the tier engaged: a deterministic sample of the committed set (every 5th
     * row whose mutated string is >= 4 code points), two warm-up and two measured rounds,
     * p95 <= 5 ms. The tier-active share of the sample and the sample's budget trips are
     * reported alongside.
     */
    @Test
    fun hostP95WithTheTierEngagedStaysUnder5ms() {
        val index = requireNotNull(russianIndex)
        index.updateKeyNeighbors(neighborTable)
        val sample = rows
            .filter { it.mutated.codePointCount(0, it.mutated.length) >= 4 }
            .filterIndexed { rowIndex, _ -> rowIndex % 5 == 0 }
            .map { ImmutableUtf8Prefix.copyOf(it.mutated.toByteArray(Charsets.UTF_8)) }
        assertTrue("the perf sample is meaningful", sample.size > 1_000)

        repeat(2) { for (prefix in sample) index.lookup(prefix) }
        val timings = LongArray(sample.size * 2)
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
        // The tier-active share and the budget trips of the sample, from a fresh single pass.
        var active = 0
        var overBudget = 0
        for (prefix in sample) {
            index.lookup(prefix)
            if (index.lastExactCount != 0) continue
            active++
            if (index.lastFuzzyOverBudget) overBudget++
        }
        timings.sort()
        val p50 = timings[timings.size / 2] / 1_000_000.0
        val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
        val max = timings.last() / 1_000_000.0
        println("EVAL|perf_host_samples|${timings.size}")
        println("EVAL|perf_host_tier_active|$active")
        println("EVAL|perf_host_p50_ms|${formatMs(p50)}")
        println("EVAL|perf_host_p95_ms|${formatMs(p95)}")
        println("EVAL|perf_host_max_ms|${formatMs(max)}")
        println("EVAL|perf_host_over_budget|$overBudget")
        println("EVAL|perf_host_consumed|$consumed")
        assertTrue("tier-engaged host p95=${formatMs(p95)}ms exceeds the 5 ms budget", p95 <= 5.0)
        assertEquals("the fuzzy budget must never trip on the perf sample", 0, overBudget)
    }

    // ---- The autocorrect false-trigger gate. ----

    /**
     * A correctly typed word must never be replaced: the index answers autocorrect only for a
     * typed string that is absent from the dictionary, so the expected count is zero. The pin is
     * exact and the assertion is an upper bound, so a regression fails loudly.
     */
    @Test
    fun autocorrectNeverReplacesACorrectlyTypedEvalWord() {
        val index = requireNotNull(russianIndex)
        index.updateKeyNeighbors(neighborTable)
        var triggers = 0
        val offenders = ArrayList<String>()
        for (word in substrate) {
            index.lookup(ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8)))
            val advice = index.lastAutocorrectAdvice ?: continue
            if (advice.replacement == word) continue
            triggers++
            if (offenders.size < 10) offenders.add("${advice.typedWord}->${advice.replacement}")
        }
        println("EVAL|autocorrect_correct_words|${substrate.size}")
        println("EVAL|autocorrect_false_triggers|$triggers")
        println(
            "EVAL|autocorrect_false_trigger_pct|${format(triggers * 100.0 / substrate.size)}"
        )
        if (offenders.isNotEmpty()) {
            println("EVAL|autocorrect_false_trigger_examples|${offenders.joinToString(",")}")
        }
        assertEquals(PIN_SUBSTRATE_WORDS, substrate.size)
        assertEquals(PIN_AUTOCORRECT_FALSE_TRIGGERS, triggers)
        assertTrue(
            "autocorrect false triggers $triggers exceed the pinned $PIN_AUTOCORRECT_FALSE_TRIGGERS",
            triggers <= PIN_AUTOCORRECT_FALSE_TRIGGERS,
        )
    }

    @Test
    fun validOovSafetySetMatchesItsPins() {
        val index = requireNotNull(russianIndex)
        assertEquals(PIN_VALID_OOV_ROWS, safetyRows.size)
        assertEquals(PIN_VALID_OOV_ROWS, safetyRows.map { it.word }.distinct().size)
        assertEquals(PIN_VALID_OOV_SET_BYTES, safetyText.toByteArray(Charsets.UTF_8).size)
        assertEquals(PIN_VALID_OOV_SET_SHA256, sha256(safetyText))
        val counts = safetyRows.groupingBy { it.stratum }.eachCount()
        assertEquals(PIN_VALID_OOV_SEEN_FORM_WORDS, counts.getValue("seen_form"))
        assertEquals(PIN_VALID_OOV_NEW_FORM_WORDS, counts.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_VALID_OOV_UNSEEN_STEM_WORDS, counts.getValue("unseen_stem"))
        for (row in safetyRows) {
            val bytes = row.word.toByteArray(Charsets.UTF_8)
            assertTrue("safety word entered the dictionary: ${row.word}",
                index.indexOfWord(bytes, bytes.size) < 0)
        }
    }

    /** Measures every valid-OOV row; no corpus form is excluded from the denominator. */
    @Test
    fun autocorrectFalseReplacementsOnValidOovWords() {
        val index = requireNotNull(russianIndex)
        index.updateKeyNeighbors(neighborTable)
        val replacements = HashMap<String, Int>()
        for (stratum in SAFETY_STRATA) replacements[stratum] = 0
        val offenders = ArrayList<String>()
        var measured = 0
        for (row in safetyRows) {
            val bytes = row.word.toByteArray(Charsets.UTF_8)
            assertTrue("safety word entered the dictionary: ${row.word}",
                index.indexOfWord(bytes, bytes.size) < 0)
            index.lookup(ImmutableUtf8Prefix.copyOf(bytes))
            measured++
            val advice = index.lastAutocorrectAdvice ?: continue
            replacements[row.stratum] = replacements.getValue(row.stratum) + 1
            offenders.add("${advice.typedWord}->${advice.replacement}")
        }
        for (stratum in SAFETY_STRATA) {
            val words = safetyRows.count { it.stratum == stratum }
            println("EVAL|autocorrect_valid_oov_${stratum}_words|$words")
            println("EVAL|autocorrect_valid_oov_${stratum}_false_replacements|${replacements.getValue(stratum)}")
        }
        val total = replacements.values.sum()
        println("EVAL|autocorrect_valid_oov_words|$measured")
        println("EVAL|autocorrect_valid_oov_false_replacements|$total")
        println("EVAL|autocorrect_valid_oov_false_replacement_pct|${format(total * 100.0 / measured)}")
        if (offenders.isNotEmpty()) {
            println("EVAL|autocorrect_valid_oov_false_replacement_examples|${offenders.joinToString(",")}")
        }
        assertEquals(PIN_VALID_OOV_ROWS, measured)
        assertEquals(PIN_VALID_OOV_SEEN_FORM_FALSE_REPLACEMENTS, replacements.getValue("seen_form"))
        assertEquals(PIN_VALID_OOV_NEW_FORM_FALSE_REPLACEMENTS,
            replacements.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_VALID_OOV_UNSEEN_STEM_FALSE_REPLACEMENTS,
            replacements.getValue("unseen_stem"))
        assertEquals(PIN_VALID_OOV_FALSE_REPLACEMENTS, total)
    }

    private fun format(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)

    private fun formatMs(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        // Pins of the committed set; re-pin when the dictionary, the eval set or the layout
        // change (regenerate: scripts/typo_eval_pack.py build --language rus).
        private const val PIN_SUBSTRATE_WORDS = 2_051
        private const val PIN_SET_ROWS = 8_204
        private const val PIN_SET_BYTES = 273_422
        private const val PIN_SET_SHA256 =
            "abf3988db71591bdde26562439c08a5c50ac4e3338ff4681384854144872108d"

        // The recovery counts, measured on the committed Russian dictionary under the production
        // wiring (the RUSSIAN policy); the exact-only control recovers last-position deletions
        // as prefix hits and nothing else. Re-pin when the dictionary, the eval set, the layout
        // or the ranking change, and never below the bars of CLASS_BARS.
        private const val PIN_SUB_ENGINE_TOP4_HITS = 1_902
        private const val PIN_DEL_ENGINE_TOP4_HITS = 1_635
        private const val PIN_INS_ENGINE_TOP4_HITS = 2_041
        private const val PIN_TRANS_ENGINE_TOP4_HITS = 1_916
        private const val PIN_DEL_ENGINE_TOP4_HITS_EXACTONLY = 274

        // The pre-registered per-class recovery bars (engine top-4, percent of each class).
        private val CLASS_BARS = listOf("sub" to 85.0, "del" to 45.0, "ins" to 70.0, "trans" to 60.0)

        private const val PIN_AUTOCORRECT_FALSE_TRIGGERS = 0

        private const val PIN_VALID_OOV_ROWS = 110
        private const val PIN_VALID_OOV_SET_BYTES = 3_884
        private const val PIN_VALID_OOV_SET_SHA256 =
            "8057871b0db8f8e863807cb47cf2f7d5015f3a20f168451b25354bf2c0e7bb17"
        private const val PIN_VALID_OOV_SEEN_FORM_WORDS = 11
        private const val PIN_VALID_OOV_NEW_FORM_WORDS = 34
        private const val PIN_VALID_OOV_UNSEEN_STEM_WORDS = 65
        private const val PIN_VALID_OOV_SEEN_FORM_FALSE_REPLACEMENTS = 0
        private const val PIN_VALID_OOV_NEW_FORM_FALSE_REPLACEMENTS = 0
        private const val PIN_VALID_OOV_UNSEEN_STEM_FALSE_REPLACEMENTS = 0
        private const val PIN_VALID_OOV_FALSE_REPLACEMENTS = 0

        private val SAFETY_STRATA = listOf("seen_form", "new_form_of_seen_stem", "unseen_stem")
        private val CLASS_NAMES = listOf("sub", "del", "ins", "trans")

        // The Russian layout: the letter keys of rows_russian.xml in layout order with the
        // long-press partners of rowkeys_russian*.xml. Production builds the same table from
        // the live keyboard via KeyNeighborTableBuilder.fromKeyboard.
        private val RUSSIAN_LAYOUT_KEYS: List<KeyNeighborTable.RawKey> = listOf(
            // й ц у к е н г ш щ з х
            rawKey('й'), rawKey('ц'), rawKey('у', 'ү'), rawKey('к'), rawKey('е', 'ё'),
            rawKey('н', 'ң'), rawKey('г', 'һ'), rawKey('ш'), rawKey('щ'), rawKey('з'),
            rawKey('х', 'һ'),
            // ф ы в а п р о л д ж э
            rawKey('ф'), rawKey('ы'), rawKey('в'), rawKey('а', 'ә'), rawKey('п'), rawKey('р'),
            rawKey('о', 'ө'), rawKey('л'), rawKey('д'), rawKey('ж', 'җ'), rawKey('э', 'ә'),
            // я ч с м и т ь б ю
            rawKey('я'), rawKey('ч'), rawKey('с'), rawKey('м'), rawKey('и'), rawKey('т'),
            rawKey('ь', 'ъ'), rawKey('б'), rawKey('ю'),
        )
        private val neighborTable = KeyNeighborTable.build("ru", true, RUSSIAN_LAYOUT_KEYS)

        private lateinit var setText: String
        private lateinit var rows: List<EvalRow>
        private lateinit var substrate: List<String>
        private lateinit var safetyText: String
        private lateinit var safetyRows: List<SafetyRow>
        private var russianIndex: TdictPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedSetAndDictionary() {
            setText = locate(
                "src/test/resources/ru_typo_eval_set.tsv",
                "app/src/test/resources/ru_typo_eval_set.tsv",
            ).readText(Charsets.UTF_8)
            val parsed = ArrayList<EvalRow>()
            for (line in setText.split("\n")) {
                if (line.isEmpty()) continue
                val fields = line.split("\t")
                check(fields.size == 3) { "malformed set row: $line" }
                parsed.add(EvalRow(fields[0], fields[1], fields[2]))
            }
            check(parsed.isNotEmpty()) { "the committed set has no rows" }
            rows = parsed
            substrate = parsed.map { it.original }.distinct()
            safetyText = locate(
                "src/test/resources/ru_valid_oov_safety_set.tsv",
                "app/src/test/resources/ru_valid_oov_safety_set.tsv",
            ).readText(Charsets.UTF_8)
            safetyRows = safetyText.split("\n").filter { it.isNotEmpty() }.map { line ->
                val fields = line.split("\t")
                check(fields.size == 2) { "malformed valid-OOV safety row: $line" }
                check(fields[1] in SAFETY_STRATA) { "unknown safety stratum: ${fields[1]}" }
                SafetyRow(fields[0], fields[1])
            }
            check(safetyRows.isNotEmpty()) { "the valid-OOV safety set is empty" }

            // The production Russian wiring: no suffix rules, the RUSSIAN fuzzy policy (edit
            // class #1 plus the DL-1 tier, no same-length bonus).
            val spec = DictionaryArtifactSpec.RUSSIAN_TOP100K_V1
            val asset = locate(
                "src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib",
            )
            val rawFile = File.createTempFile("ru-typo-eval-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                val identity = DictionaryIdentity(
                    spec.generation,
                    validated.schemaId,
                    validated.formatVersion,
                    validated.rawSha256,
                )
                russianIndex = requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(rawFile.readBytes()), identity,
                        validated.entryCount, validated.rawSize,
                        fuzzyEditPolicy = FuzzyEditPolicy.RUSSIAN,
                        autocorrectMinCandidateFrequency = AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed eval test resource")
    }
}
