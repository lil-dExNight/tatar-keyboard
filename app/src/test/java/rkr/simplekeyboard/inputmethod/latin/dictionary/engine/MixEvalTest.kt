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
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.io.File
import java.nio.ByteBuffer

/**
 * Runs the shipped Tatar and Russian dictionaries over the hand-written mixing set
 * `app/src/test/resources/tt_ru_mix_sentences.txt` and prints `EVAL|metric|value` lines.
 * Each set line is `expected_lead<TAB>sentence`. A mixing case is a sentence word that is a
 * dictionary entry of the expected-lead language and whose full-word exact pass in the active
 * (other) language is empty: the user is typing a word of the companion language on the active
 * The strip lead is replayed at the index level: the active arm runs the production
 * wiring (the Tatar index carries the suffix rules, the TATAR fuzzy policy and the Tatar layout
 * neighbor table; the Russian index runs the default policy with the Russian layout table), and
 * the companion arm runs exact-only, the pass the exact-lead rule takes its leading candidates
 * from. The lead is correct when the active answer holds no exact candidate and the companion
 * exact pass is non-empty: the one condition under which the companion takes over the strip's
 * first cell. Assets are SHA-pinned, so every count is a deterministic function of committed
 * bytes.
 */
class MixEvalTest {

    @Test
    fun mixingSetShapeMatchesTheContract() {
        assertTrue(
            "the mixing set keeps at least the committed size",
            rawLines.size >= MIN_SET_LINES,
        )
        val sentences = HashSet<String>()
        var ttLines = 0
        var ruLines = 0
        for (raw in rawLines) {
            val fields = raw.split("\t")
            assertEquals("one lead field and one sentence field per line", 2, fields.size)
            val tag = fields[0]
            val sentence = fields[1]
            assertTrue("the lead tag is tt or ru", tag == "tt" || tag == "ru")
            assertTrue("the sentence field is not empty", sentence.isNotEmpty())
            assertEquals("no padding around the sentence", sentence, sentence.trim())
            assertTrue("single spaces only", !sentence.contains("  "))
            assertTrue("no duplicate sentences", sentences.add(sentence))
            val words = sentence.split(" ")
            assertTrue("a mixing sentence carries 3..12 words", words.size in 3..12)
            for (word in words) {
                for (letter in word) {
                    assertTrue(
                        "a letter outside the two alphabets in $word",
                        letter in SENTENCE_LETTERS,
                    )
                }
            }
            if (tag == "tt") ttLines++ else ruLines++
        }
        assertTrue("the tt-lead direction keeps a meaningful mass", ttLines >= MIN_DIRECTION_LINES)
        assertTrue("the ru-lead direction keeps a meaningful mass", ruLines >= MIN_DIRECTION_LINES)
    }

    /**
     * The mixing set is new material: no sentence of it may appear in either monolingual eval
     * set. The monolingual resources are only read, never written.
     */
    @Test
    fun mixingSetIsDisjointFromTheMonolingualEvalSets() {
        val mixSentences = parseMixLines().map { it.sentence }.toSet()
        for (line in tatarEvalLines + russianEvalLines) {
            assertTrue(
                "a mixing sentence repeats a monolingual eval line: $line",
                line !in mixSentences,
            )
        }
    }

    /**
     * The correct-language-lead rate over all mixing cases, with the companion exact-lead rule
     * ON. A case leads correctly when the active answer holds NO exact candidate (typo-recovery
     * candidates may fill it) and the companion's top-3 holds the word: then the companion's
     * exact candidate leads the strip. An active exact candidate or a buried companion word both
     * count as wrong leads. The fill-only baseline (the companion leads only a completely empty
     * active answer) is printed alongside for the record; the gate pins the rule's rate.
     */
    @Test
    fun companionExactLeadClearsTheGateOnTheMixingSet() {
        val indexes = arrayOf(requireNotNull(tatarIndex), requireNotNull(russianIndex))
        val activeTables = arrayOf(tatarTable, russianTable)

        var linesWithoutCase = 0
        val cases = IntArray(2)
        val correct = IntArray(2)
        val correctFillOnly = IntArray(2)
        val companionBuried = IntArray(2)
        val coveredLetters = arrayOf(HashSet<Char>(), HashSet<Char>())
        var linesTt = 0
        var linesRu = 0
        for (mixLine in parseMixLines()) {
            // The lead language is the companion; the active layout is the other language.
            val lead = if (mixLine.expectedLead == "tt") 0 else 1
            val active = 1 - lead
            if (mixLine.expectedLead == "tt") linesTt++ else linesRu++
            var lineCases = 0
            for (word in mixLine.sentence.split(" ")) {
                // A case word belongs to the lead language's dictionary and to no other: a word
                // both dictionaries carry can never tell the languages apart.
                if (indexes[lead].frequencyOf(word) <= 0L) continue
                if (indexes[active].frequencyOf(word) > 0L) continue
                // The probe is the word one letter short: the last strip moment before it ends.
                // (The exact pass never returns the query word itself, so a full-word probe
                // cannot see coverage.)
                val probe = word.dropLast(1)
                if (probe.codePointCount(0, probe.length) < 3) continue
                lineCases++
                cases[lead]++
                indexes[active].updateKeyNeighbors(activeTables[active])
                val activeAnswer = indexes[active].lookup(prefixOf(probe))
                val activeExactCount = indexes[active].lastExactCount
                indexes[lead].updateKeyNeighbors(null)
                val companionAnswer = indexes[lead].lookup(prefixOf(probe))
                if (word !in companionAnswer) {
                    companionBuried[lead]++
                } else {
                    // The rule engages exactly when the active exact pass is empty; within it,
                    // the fill-only baseline additionally requires an empty active answer.
                    if (activeExactCount == 0) correct[lead]++
                    if (activeAnswer.isEmpty()) correctFillOnly[lead]++
                }
                val markers = if (lead == 0) TT_MARKER_LETTERS else RU_MARKER_LETTERS
                for (letter in word) {
                    if (letter in markers) coveredLetters[lead].add(letter)
                }
            }
            if (lineCases == 0) linesWithoutCase++
        }
        val casesAll = cases[0] + cases[1]
        val correctAll = correct[0] + correct[1]
        val correctFillOnlyAll = correctFillOnly[0] + correctFillOnly[1]
        println("EVAL|mix_lines_tt|$linesTt")
        println("EVAL|mix_lines_ru|$linesRu")
        println("EVAL|mix_cases_tt|${cases[0]}")
        println("EVAL|mix_cases_ru|${cases[1]}")
        println("EVAL|mix_correct_lead_tt|${correct[0]}")
        println("EVAL|mix_correct_lead_ru|${correct[1]}")
        println("EVAL|mix_wrong_lead_tt|${cases[0] - correct[0]}")
        println("EVAL|mix_wrong_lead_ru|${cases[1] - correct[1]}")
        println("EVAL|mix_companion_buried_tt|${companionBuried[0]}")
        println("EVAL|mix_companion_buried_ru|${companionBuried[1]}")
        println("EVAL|mix_lead_pct_tt|${format(ratePct(correct[0], cases[0]))}")
        println("EVAL|mix_lead_pct_ru|${format(ratePct(correct[1], cases[1]))}")
        println("EVAL|mix_cases|$casesAll")
        println("EVAL|mix_correct_lead|$correctAll")
        println("EVAL|mix_wrong_lead|${casesAll - correctAll}")
        println("EVAL|mix_lead_pct|${format(ratePct(correctAll, casesAll))}")
        println("EVAL|mix_fill_only_correct_lead|$correctFillOnlyAll")
        println("EVAL|mix_fill_only_lead_pct|${format(ratePct(correctFillOnlyAll, casesAll))}")

        assertEquals("every mixing line yields at least one case", 0, linesWithoutCase)
        for (letter in TT_MARKER_LETTERS) {
            assertTrue("a tt-lead case word carries $letter", letter in coveredLetters[0])
        }
        for (letter in RU_MARKER_LETTERS) {
            assertTrue("a ru-lead case word carries $letter", letter in coveredLetters[1])
        }
        assertTrue(
            "companion exact-lead rate ${format(ratePct(correctAll, casesAll))}% is below" +
                " the gate ${format(GATE_LEAD_PCT)}% ($correctAll of $casesAll cases)",
            ratePct(correctAll, casesAll) >= GATE_LEAD_PCT,
        )
    }

    private class MixLine(val expectedLead: String, val sentence: String)

    private fun parseMixLines(): List<MixLine> = rawLines.map { raw ->
        val fields = raw.split("\t")
        check(fields.size == 2) { "malformed mixing line: $raw" }
        check(fields[0] == "tt" || fields[0] == "ru") { "unknown lead tag in: $raw" }
        MixLine(fields[0], fields[1])
    }

    private fun prefixOf(word: String): ImmutableUtf8Prefix =
        ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8))

    /** One lookup with [table] installed; null keeps the exact pass only. */
    private fun runWithNeighbors(
        index: TdictPrefixIndex,
        table: KeyNeighborTable?,
        word: String,
    ): List<String> {
        index.updateKeyNeighbors(table)
        return index.lookup(prefixOf(word))
    }

    private fun ratePct(part: Int, whole: Int): Double = if (whole > 0) part * 100.0 / whole else 0.0

    private fun format(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)

    companion object {
        // The set contract: at least the committed size and a meaningful mass per direction.
        private const val MIN_SET_LINES = 180
        private const val MIN_DIRECTION_LINES = 60

        // The companion exact-lead gate: the share of mixing cases whose strip lead comes from
        // the expected language once an empty active exact pass lets the companion lead.
        private const val GATE_LEAD_PCT = 80.0

        // The marker letters: a word containing one can only belong to one side. The Russian
        // layout lacks әҗңөүһ as keys; щыэ have no Tatar dictionary words in practice.
        private const val TT_MARKER_LETTERS = "әҗңөүһ"
        private const val RU_MARKER_LETTERS = "щыэ"

        // The Tatar alphabet, a superset of the Russian one; sentence letters stay inside it.
        private const val SENTENCE_LETTERS = "аәбвгдеёжҗзийклмнңоөпрстуүфхһцчшщъыьэюя"

        private lateinit var rawLines: List<String>
        private lateinit var tatarEvalLines: List<String>
        private lateinit var russianEvalLines: List<String>
        private lateinit var tatarTable: KeyNeighborTable
        private lateinit var russianTable: KeyNeighborTable
        private var tatarIndex: TdictPrefixIndex? = null
        private var russianIndex: TdictPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssetsAndMixingSet() {
            rawLines = locate(
                "src/test/resources/tt_ru_mix_sentences.txt",
                "app/src/test/resources/tt_ru_mix_sentences.txt",
            ).readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            tatarEvalLines = readEvalLines(
                "src/test/resources/tt_eval_sentences.txt",
                "app/src/test/resources/tt_eval_sentences.txt",
            )
            russianEvalLines = readEvalLines(
                "src/test/resources/ru_eval_sentences.txt",
                "app/src/test/resources/ru_eval_sentences.txt",
            )

            // The production Tatar engine is constructed with the suffix rules and the TATAR
            // fuzzy policy; the production Russian engine carries neither.
            tatarIndex = openShippedDictionary(
                DictionaryArtifactSpec.TATAR_TOP100K_V1, true,
                "src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
            )
            russianIndex = openShippedDictionary(
                DictionaryArtifactSpec.RUSSIAN_TOP100K_V1, false,
                "src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib",
            )
            tatarTable = E3bTestFixtures.tatarNeighborTable()
            russianTable = KeyNeighborTable.build("ru", true, RUSSIAN_LAYOUT_KEYS)
        }

        private fun readEvalLines(vararg paths: String): List<String> =
            locate(*paths).readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }

        /**
         * Inflates and validates one committed dictionary asset and opens it exactly like the
         * production engine of its language does. See [TtSuggestEvalTest].
         */
        private fun openShippedDictionary(
            spec: DictionaryArtifactSpec,
            withTatarWiring: Boolean,
            vararg paths: String,
        ): TdictPrefixIndex {
            val asset = locate(*paths)
            val rawFile = File.createTempFile("mix-eval-dict-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                val identity = DictionaryIdentity(
                    spec.generation, validated.schemaId, validated.formatVersion,
                    validated.rawSha256,
                )
                val raw = rawFile.readBytes()
                return requireNotNull(
                    if (withTatarWiring) {
                        TdictPrefixIndex.open(
                            ByteBuffer.wrap(raw), identity,
                            validated.entryCount, validated.rawSize, TatarSuffixRules,
                            FuzzyEditPolicy.TATAR, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                        )
                    } else {
                        TdictPrefixIndex.open(
                            ByteBuffer.wrap(raw), identity,
                            validated.entryCount, validated.rawSize,
                            autocorrectMinCandidateFrequency = AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
                        )
                    },
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed eval test resource")

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
    }
}
