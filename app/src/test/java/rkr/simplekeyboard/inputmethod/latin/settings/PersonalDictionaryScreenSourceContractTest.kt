/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.ValidatedPersonalBigrams
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.ValidatedPersonalDictionary

/**
 * The "Personal dictionary" screen, one test per guarantee the contract names: all languages, words
 * AND learned pairs (U7 of Phase 2, docs/ROADMAP-P2.md), a usage count on every row, a cap of 200
 * materialized rows shared across both stores, "showing N of M", `FLAG_SECURE` on the whole
 * Activity and the three privacy flags on BOTH text fields.
 *
 * The list logic is exercised for real (it is pure Kotlin); the Activity parts are source-contract,
 * in the established style, because `SettingsHostActivity` cannot run off-device.
 */
class PersonalDictionaryScreenSourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private val host by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt").readText()
    }

    private fun dictionaryOf(vararg words: String): PersonalDictionary {
        val sorted = words.sorted()
        return PersonalDictionary.of(
            ValidatedPersonalDictionary(
                rawForms = sorted,
                normalizedForms = sorted.map { it.lowercase() },
                usageCounts = IntArray(sorted.size) { it + 1 },
                lastUseSerials = LongArray(sorted.size) { (it + 1).toLong() },
                subtypeTag = "tt_RU",
            ),
        )
    }

    /** Pairs ("сәләм", "дөнья"), ("бәйрәм", "котлы"), … in the pair-key order the snapshot holds. */
    private fun bigramsOf(subtype: String, vararg pairs: Pair<String, String>): PersonalBigramDictionary {
        val sorted = pairs.sortedWith(compareBy({ it.first }, { it.second }))
        return PersonalBigramDictionary.of(
            ValidatedPersonalBigrams(
                contexts = sorted.map { it.first },
                successorRawForms = sorted.map { it.second },
                successorNormalizedForms = sorted.map { it.second.lowercase() },
                usageCounts = IntArray(sorted.size) { it + 1 },
                frequencyCounts = IntArray(sorted.size) { 2 },
                lastUseSerials = LongArray(sorted.size) { (it + 1).toLong() },
                subtypeTag = subtype,
            ),
        )
    }

    private fun build(
        words: List<Pair<String, PersonalDictionary>>,
        pairs: List<Pair<String, PersonalBigramDictionary>> = words.map { it.first to PersonalBigramDictionary.EMPTY },
        query: String = "",
    ) = PersonalDictionaryScreenModel.build(words, pairs, query)

    // --- The list model ---------------------------------------------------------------------

    @Test
    fun wordsOfEveryLanguageAreShownGroupedByLanguage() {
        val content = build(
            listOf("tt_RU" to dictionaryOf("гүзәлия"), "ru" to dictionaryOf("зәйнәп")),
        )
        assertEquals(2, content.sections.size)
        assertEquals("tt_RU", content.sections[0].subtypeId)
        assertEquals("ru", content.sections[1].subtypeId)
        assertEquals(2, content.shownCount)
    }

    @Test
    fun pairsSitInTheSameSectionAsTheWordsOfTheirLanguage() {
        val content = build(
            listOf("tt_RU" to dictionaryOf("гүзәлия")),
            listOf("tt_RU" to bigramsOf("tt_RU", "сәләм" to "дөнья")),
        )
        assertEquals(1, content.sections.size)
        val section = content.sections[0]
        assertEquals(1, section.wordRows.size)
        assertEquals(1, section.pairRows.size)
        assertEquals("сәләм", section.pairRows[0].contextForm)
        assertEquals("дөнья", section.pairRows[0].successorRawForm)
    }

    @Test
    fun aLanguageWithOnlyPairsStillGetsItsSection() {
        val content = build(
            listOf("tt_RU" to PersonalDictionary.EMPTY),
            listOf("tt_RU" to bigramsOf("tt_RU", "сәләм" to "дөнья")),
        )
        assertEquals(1, content.sections.size)
        assertEquals(0, content.sections[0].wordRows.size)
        assertEquals(1, content.sections[0].pairRows.size)
    }

    @Test
    fun everyRowCarriesItsUsageCountAndTheCountsAreTrueTotals() {
        val content = build(
            listOf("tt_RU" to dictionaryOf("гүзәлия", "зәйнәп")),
            listOf("tt_RU" to bigramsOf("tt_RU", "сәләм" to "дөнья")),
        )
        val section = content.sections[0]
        assertEquals("the word count is the saved total, not the shown rows",
            2, section.wordCount)
        assertEquals(1, section.pairCount)
        // The helper above assigns usage 1, 2, … in order: the counts really arrive on the rows.
        assertEquals(listOf(1, 2), section.wordRows.map { it.usageCount })
        assertEquals(listOf(1), section.pairRows.map { it.usageCount })
    }

    @Test
    fun noMoreThanTwoHundredRowsAreMaterializedAcrossWordsAndPairs() {
        val first = dictionaryOf(*Array(150) { "аа%03d".format(it) })
        val second = dictionaryOf(*Array(150) { "бб%03d".format(it) })
        val pairs = bigramsOf("tt_RU", *Array(50) { "сәләм%03d".format(it) to "дөнья" })
        val content = build(
            listOf("tt_RU" to first, "ru" to second),
            listOf("tt_RU" to pairs, "ru" to PersonalBigramDictionary.EMPTY),
        )
        assertEquals(PersonalDictionaryScreenModel.MAX_MATERIALIZED_ROWS, content.shownCount)
        assertEquals(350, content.totalCount)
        assertTrue("the screen must be able to say it is showing a part", content.isTruncated)
        assertEquals("the cap is shared across languages and both stores",
            200, content.sections.sumOf { it.wordRows.size + it.pairRows.size })
    }

    @Test
    fun theSearchNarrowsTheListBeforeAnyViewExists() {
        val content = build(
            listOf("tt_RU" to dictionaryOf("гүзәлия", "зәйнәп", "гүзәлбану")),
            query = "гүзәл",
        )
        assertEquals(2, content.shownCount)
        assertEquals("the total counts matches, not the whole dictionary", 2, content.totalCount)
        assertFalse(content.isTruncated)
    }

    @Test
    fun theSearchCoversPairsTooByContextOrBySuccessor() {
        val pairs = listOf("tt_RU" to bigramsOf("tt_RU", "сәләм" to "дөнья", "бәйрәм" to "котлы"))
        assertEquals("a hit on the context",
            1, build(listOf("tt_RU" to PersonalDictionary.EMPTY), pairs, query = "сәләм").shownCount)
        assertEquals("a hit on the successor",
            1, build(listOf("tt_RU" to PersonalDictionary.EMPTY), pairs, query = "дөнья").shownCount)
        assertEquals("a miss on both",
            0, build(listOf("tt_RU" to PersonalDictionary.EMPTY), pairs, query = "юк").shownCount)
    }

    @Test
    fun theSearchMatchesOnTheNormalizedFormButTheRowKeepsTheSavedSpelling() {
        val content = build(
            listOf("tt_RU" to dictionaryOf("Гүзәлия")), query = "ГҮЗӘЛ",
        )
        assertEquals(1, content.shownCount)
        assertEquals("Гүзәлия", content.sections[0].wordRows[0].rawForm)
    }

    @Test
    fun anEmptyDictionaryProducesNoSectionsAtAll() {
        val content = build(listOf("tt_RU" to PersonalDictionary.EMPTY))
        assertTrue(content.sections.isEmpty())
        assertEquals(0, content.totalCount)
        assertFalse(content.isTruncated)
    }

    // --- The Activity -----------------------------------------------------------------------

    @Test
    fun flagSecureIsSetOnceForTheWholeActivity() {
        val onCreate = host.substringAfter("override fun onCreate(").substringBefore("override fun onStart(")
        assertTrue("FLAG_SECURE is set in onCreate",
            onCreate.contains("window.setFlags(WindowManager.LayoutParams.FLAG_SECURE"))
        assertFalse("it must never be cleared while navigating between screens",
            host.contains("clearFlags(WindowManager.LayoutParams.FLAG_SECURE"))
        assertEquals("set exactly once, not per screen", 1,
            Regex("setFlags\\(WindowManager\\.LayoutParams\\.FLAG_SECURE").findAll(host).count())
    }

    @Test
    fun bothTextFieldsCarryTheThreePrivacyFlags() {
        val flags = host.substringAfter("private fun applyPrivateInputFlags(")
            .substringBefore("private fun textInputRow(")
        assertTrue(flags.contains("EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING"))
        assertTrue(flags.contains("EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS"))
        assertTrue(flags.contains("View.IMPORTANT_FOR_AUTOFILL_NO"))

        // Every place that inflates the text-input row must run them through that one function.
        val inflations = Regex("R\\.layout\\.row_text_input").findAll(host).count()
        val applications = Regex("applyPrivateInputFlags\\(field\\)").findAll(host).count()
        assertEquals("each of the two fields — search and add-word — applies the flags",
            inflations, applications)
        assertEquals(2, inflations)
    }

    @Test
    fun theShownOfTotalRowExistsAndOnlyAppearsWhenTheListIsTrimmed() {
        assertTrue(host.contains("if (content.isTruncated)"))
        assertTrue(host.contains("R.string.personal_dictionary_shown_of_total"))
        val english = File(sourceRoot(), "res/values/strings.xml").readText()
        assertTrue("the string names both numbers", english
            .substringAfter("<string name=\"personal_dictionary_shown_of_total\">")
            .substringBefore("</string>")
            .let { it.contains("%1\$d") && it.contains("%2\$d") })
    }

    @Test
    fun theSearchTextDoesNotTravelThroughTheSavedInstanceState() {
        val saveState = host.substringAfter("override fun onSaveInstanceState(")
            .substringBefore("override fun onBackPressed(")
        assertFalse("a fragment of a personal word must not go into a Bundle bound for system_server",
            saveState.contains("personalSearchQuery"))
        assertTrue("and the field itself is plain transient state",
            host.contains("private var personalSearchQuery: String = \"\""))
    }

    @Test
    fun theScreenWorksWithTheSettingOffButAddingFollowsTheSetting() {
        assertTrue("the entry row does not depend on the toggle",
            host.contains("addCard(listOf(linkRow(R.string.personal_dictionary_screen)"))
        assertTrue("only the add row follows the setting", host.contains(
            "setRowEnabled(addRow, Settings.readPersonalDictionaryEnabled(prefs)"))
        val screen = host.substringAfter("private fun buildPersonalDictionaryScreen()")
            .substringBefore("private fun personalSubtypeIds()")
        assertTrue("erasing is offered regardless of the toggle",
            screen.contains("R.string.personal_dictionary_erase_all"))
        assertFalse("no early return hides the screen when the setting is off",
            screen.contains("if (!Settings.readPersonalDictionaryEnabled(prefs)) return"))
    }

    @Test
    fun everySectionOpensWithItsTrueCountsAndClosesWithItsOwnClearAll() {
        val screen = host.substringAfter("private fun buildPersonalDictionaryScreen()")
            .substringBefore("private fun usageRow(")
        assertTrue("the words card starts from the saved-words count",
            screen.contains("R.plurals.personal_dictionary_words_count"))
        assertTrue("the pairs card starts from the saved-pairs count",
            screen.contains("R.plurals.personal_dictionary_pairs_count"))
        assertTrue("and the count printed is the total, not the materialized rows",
            screen.contains("section.wordCount") && screen.contains("section.pairCount"))
        assertTrue("each store of the language gets its own clear action",
            screen.contains("R.string.personal_dictionary_clear_words") &&
                screen.contains("R.string.personal_dictionary_clear_pairs"))
    }

    @Test
    fun everyRowShowsItsUsageCountBesideTheDeleteAffordance() {
        val row = host.substringAfter("private fun usageRow(").substringBefore("\n    /**")
        assertTrue("the summary carries the usage count",
            row.contains("R.plurals.personal_dictionary_usage_count"))
        assertTrue("and the delete affordance — the row is tappable",
            row.contains("R.string.personal_dictionary_delete"))
        // The pair row reads "A → B", the same shape its forget dialog repeats.
        assertTrue(host.contains("row.contextForm + \" → \" + row.successorRawForm"))
    }
}
