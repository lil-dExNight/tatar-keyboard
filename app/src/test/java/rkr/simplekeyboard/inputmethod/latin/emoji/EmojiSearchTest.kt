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

package rkr.simplekeyboard.inputmethod.latin.emoji

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure halves of the emoji search: the shipped index and the typed query. */
class EmojiSearchTest {

    private fun indexOf(vararg lines: String): EmojiSearchIndex =
        EmojiSearchIndex.parse(lines.joinToString("\n") + "\n")

    private fun results(index: EmojiSearchIndex, query: String): List<String> {
        val count = index.search(query)
        return (0 until count).map { index.resultAt(it) }
    }

    // --- Parsing ------------------------------------------------------------------------------

    @Test
    fun aWellFormedAssetParsesIntoOneEntryPerLine() {
        val index = indexOf(
            "🐱\tкот\tкот кошка животное cat",
            "🐶\tсобака\tсобака животное dog",
        )
        assertEquals(2, index.entryCount)
        assertFalse(index.isEmpty)
    }

    @Test
    fun malformedLinesAndDuplicatesAreDroppedAndAnUnreadableAssetIsEmpty() {
        val index = indexOf(
            "🐱\tкот\tкот кошка",
            "no tabs at all",
            "🐶\tтолько два поля",
            "\tкот\tбез последовательности",
            "🐱\tдубль\tдубль",
            "🐶\tсобака\tсобака dog\tлишнее поле",
        )
        assertEquals(1, index.entryCount)
        assertTrue(EmojiSearchIndex.parse("").isEmpty)
        assertTrue(EmojiSearchIndex.parse("junk\njunk\n").isEmpty)
    }

    // --- Matching -----------------------------------------------------------------------------

    @Test
    fun aQueryMatchesAWordPrefixOfTheNameOrOfTheKeywords() {
        val index = indexOf(
            "🐱\tкот\tкот кошка животное cat",
            "🐶\tсобака\tсобака животное dog",
            "🌿\tтравы\tтравы растение зелень herb",
        )
        assertEquals(listOf("🐱"), results(index, "кот"))
        assertEquals(listOf("🐱"), results(index, "кош"))
        // A word in the middle of the keyword list still matches by its own prefix.
        assertEquals(listOf("🐱", "🐶"), results(index, "живот"))
        // A prefix that starts inside a word never matches.
        assertTrue(results(index, "отня").isEmpty())
        assertTrue(results(index, "ошка").isEmpty())
    }

    @Test
    fun matchingIsCaseInsensitiveAndIgnoresSurroundingSpace() {
        val index = indexOf("🐱\tкот\tкот кошка cat")
        assertEquals(listOf("🐱"), results(index, "КОТ"))
        assertEquals(listOf("🐱"), results(index, "  Кот  "))
        assertEquals(listOf("🐱"), results(index, "CAT"))
    }

    @Test
    fun aBlankQueryReturnsNothing() {
        val index = indexOf("🐱\tкот\tкот кошка cat")
        assertEquals(0, index.search(""))
        assertEquals(0, index.search("   "))
        assertEquals("", index.resultAt(0))
    }

    /** The name is the strongest signal, then a word of the name, then a keyword. */
    @Test
    fun resultsAreOrderedNameFirstThenKeyword() {
        val index = indexOf(
            "🌿\tтравы\tтравы зелень",
            "🐱\tкот\tкот трава",
            "😺\tулыбающийся кот\tкот трава улыбка",
        )
        // "трав": the name of the first entry starts with it; the third has it in a keyword only,
        // and the second likewise — so the name match leads.
        assertEquals("🌿", results(index, "трав").first())
        // "кот": entry 2's whole name is the query; entry 3's name has it as a later word.
        assertEquals(listOf("🐱", "😺"), results(index, "кот"))
    }

    @Test
    fun theResultCountIsCapped() {
        val lines = Array(EmojiSearchIndex.MAX_RESULTS * 3) { "e$it\tимя$it\tобщее слово$it" }
        val index = EmojiSearchIndex.parse(lines.joinToString("\n") + "\n")
        assertEquals(EmojiSearchIndex.MAX_RESULTS, index.search("общее"))
    }

    /** The search may only ever offer emoji the device can actually draw. */
    @Test
    fun filterToKeepsOnlyTheSequencesTheGlyphProbeAccepted() {
        val index = indexOf(
            "🐱\tкот\tкот животное",
            "🐶\tсобака\tсобака животное",
        )
        val filtered = index.filterTo(setOf("🐱"))
        assertEquals(1, filtered.entryCount)
        assertEquals(listOf("🐱"), results(filtered, "животное"))
        assertTrue(index.filterTo(emptySet()).isEmpty)
        // The original is untouched: filtering returns a copy.
        assertEquals(2, index.entryCount)
    }

    @Test
    fun wordPrefixMatchingIsExactAboutWordBoundaries() {
        assertTrue(EmojiSearchIndex.startsWordWith("кот кошка", "кот"))
        assertTrue(EmojiSearchIndex.startsWordWith("кот кошка", "кош"))
        assertTrue(EmojiSearchIndex.startsWordWith("кот кошка", "кот кош"))
        assertFalse(EmojiSearchIndex.startsWordWith("кот кошка", "ошка"))
        assertFalse(EmojiSearchIndex.startsWordWith("кот", "котёнок"))
        assertFalse(EmojiSearchIndex.startsWordWith("кот", ""))
    }

    // --- The shipped asset --------------------------------------------------------------------

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    /**
     * The committed index must name only sequences the committed panel asset can draw, and must
     * cover essentially all of them — otherwise the search would either offer an emoji the grid
     * has no cell for, or silently miss most of the set.
     */
    @Test
    fun theShippedIndexCoversThePanelSetAndNothingBesides() {
        val assets = File(sourceRoot(), "assets/emoji")
        val panel = File(assets, "emoji_set_v1.txt").readText()
        val search = File(assets, "emoji_search_v1.txt").readText()

        val headerRegex = Regex("^#[a-z][a-z0-9-]*$")
        val panelSequences = panel.split('\n')
            .filter { it.isNotEmpty() && !headerRegex.matches(it) }
            .toSet()
        val indexed = search.split('\n')
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('\t') }

        assertEquals("the index holds a duplicate", indexed.size, indexed.toSet().size)
        for (sequence in indexed) {
            assertTrue("indexed sequence absent from the panel: $sequence", sequence in panelSequences)
        }
        assertEquals(panelSequences.size, indexed.size)

        // Every line has exactly the three fields the parser expects.
        for (line in search.split('\n')) {
            if (line.isEmpty()) continue
            assertEquals("bad field count in: $line", 2, line.count { it == '\t' })
        }
    }

    @Test
    fun theShippedIndexIsCreditedInTheNoticeBesideIt() {
        val notice = File(sourceRoot(), "assets/emoji/NOTICE.txt").readText()
        assertTrue(notice.contains("emoji_search_v1.txt"))
        assertTrue(notice.contains("CLDR"))
        assertTrue(notice.contains("Unicode-DFS-2016"))
    }

    @Test
    fun aRussianQueryAgainstTheShippedIndexFindsTheObviousAnswer() {
        val assets = File(sourceRoot(), "assets/emoji")
        val index = EmojiSearchIndex.parse(File(assets, "emoji_search_v1.txt").readText())
        assertTrue(index.entryCount > 1000)
        assertTrue("кот", results(index, "кот").isNotEmpty())
        assertTrue("сердце", results(index, "сердце").isNotEmpty())
        assertTrue("cat", results(index, "cat").isNotEmpty())
    }

    /**
     * CLDR has no Tatar annotations, so the index
     * carries hand-written Tatar keywords (`scripts/emoji_search_tt_extra.txt`, appended by
     * `scripts/emoji_search_pack.py --tt-extra`). Matching itself needed no change: a query is
     * lowercased and prefix-matched against space-separated words, and the Tatar-specific letters
     * ә ө ү җ ң һ are ordinary cased letters for both sides of that comparison.
     */
    @Test
    fun aTatarQueryAgainstTheShippedIndexFindsTheObviousAnswer() {
        val assets = File(sourceRoot(), "assets/emoji")
        val index = EmojiSearchIndex.parse(File(assets, "emoji_search_v1.txt").readText())
        assertTrue("йөрәк", results(index, "йөрәк").contains("❤️"))
        assertTrue("мәхәббәт", results(index, "мәхәббәт").contains("❤️"))
        assertTrue("мәче", results(index, "мәче").isNotEmpty())
        assertTrue("сәлам", results(index, "сәлам").contains("👋"))
        assertTrue("китап", results(index, "китап").contains("📖"))
        // A hand-written Tatar keyword (the rooster).
        assertTrue("этэч", results(index, "этэч").contains("🐓"))
        assertTrue("этэ (prefix)", results(index, "этэ").contains("🐓"))
        // A prefix of a Tatar word matches, a suffix does not.
        assertTrue(results(index, "йөр").contains("❤️"))
        assertFalse(results(index, "рәк").contains("❤️"))
    }

    /** The Tatar-specific letters must lowercase the way the generator lowercased the asset. */
    @Test
    fun tatarLettersSurviveQueryNormalization() {
        assertEquals("йөрәк", EmojiSearchIndex.normalize("ЙӨРӘК"))
        assertEquals("мәҗбур", EmojiSearchIndex.normalize("МӘҖБУР"))
        assertEquals("җаңһыр", EmojiSearchIndex.normalize("ҖАҢҺЫР"))
    }

    // --- The query ----------------------------------------------------------------------------

    @Test
    fun theQueryGrowsAndShrinksByCodePoint() {
        val query = EmojiSearchQuery()
        assertTrue(query.isEmpty())
        assertTrue(query.appendCodePoint('к'.code))
        assertTrue(query.appendCodePoint('о'.code))
        assertTrue(query.appendCodePoint('т'.code))
        assertEquals("кот", query.text())
        assertTrue(query.backspace())
        assertEquals("ко", query.text())
        assertTrue(query.clear())
        assertTrue(query.isEmpty())
        assertFalse(query.clear())
    }

    /** One backspace removes one character even when it is a surrogate pair. */
    @Test
    fun aSurrogatePairIsOneBackspace() {
        val query = EmojiSearchQuery()
        assertTrue(query.appendCodePoint(0x1F431)) // 🐱
        assertEquals(2, query.length)
        assertTrue(query.backspace())
        assertTrue(query.isEmpty())
    }

    /** A backspace on an empty query is the caller's cue to leave the search. */
    @Test
    fun aBackspaceOnAnEmptyQueryIsRefused() {
        val query = EmojiSearchQuery()
        assertFalse(query.backspace())
    }

    @Test
    fun controlCharactersAndOverlongQueriesAreRefused() {
        val query = EmojiSearchQuery()
        assertFalse(query.appendCodePoint('\n'.code))
        assertFalse(query.appendCodePoint('\t'.code))
        assertFalse(query.appendCodePoint(0))
        assertFalse(query.appendCodePoint(-5))
        assertTrue(query.isEmpty())

        repeat(EmojiSearchQuery.MAX_LENGTH) { assertTrue(query.appendCodePoint('a'.code)) }
        assertEquals(EmojiSearchQuery.MAX_LENGTH, query.length)
        assertFalse(query.appendCodePoint('a'.code))
        // A surrogate pair that would straddle the bound is refused whole, never half.
        query.backspace()
        assertFalse(query.appendCodePoint(0x1F431))
        assertEquals(EmojiSearchQuery.MAX_LENGTH - 1, query.length)
    }

    @Test
    fun theQueryTextIsNotRebuiltWhileItDoesNotChange() {
        val query = EmojiSearchQuery()
        query.appendCodePoint('к'.code)
        val first = query.text()
        assertTrue(first === query.text())
        query.appendCodePoint('о'.code)
        assertNotEquals(first, query.text())
    }

    // --- nameOf (the strip's spoken labels) ---------------------------------------------------

    @Test
    fun nameOfAnswersTheShortNameWithoutASearch() {
        val index = indexOf(
            "🐱\tкот\tкот кошка животное cat",
            "✈️\tсамолёт\tсамолет самолёт самолета airplane",
        )
        assertEquals("кот", index.nameOf("🐱"))
        assertEquals("самолёт", index.nameOf("✈️"))
    }

    @Test
    fun nameOfMissesSilentlyOnAnUnknownSequence() {
        val index = indexOf("🐱\tкот\tкот кошка животное cat")
        assertEquals(null, index.nameOf("🐶"))
        assertEquals(null, EmojiSearchIndex.EMPTY.nameOf("🐱"))
    }
}
