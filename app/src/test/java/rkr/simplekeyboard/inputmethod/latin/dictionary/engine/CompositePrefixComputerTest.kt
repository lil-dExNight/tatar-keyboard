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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidate
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionStripState

/**
 * The single ranking of E4b, one named test per point of the written amendment to «Контракт текста»
 * (2026-07-30, «единая редакция ранжирования на три класса»).
 *
 * The tests drive [CompositePrefixComputer] with a fake primary, so they exercise the merge itself
 * rather than the mmap index: what is under test is the ORDER, not the dictionary.
 */
class CompositePrefixComputerTest {

    private class FakePrimary(
        private val results: List<String>,
        override val lastExactCount: Int,
    ) : ClassifiedPrefixComputer {
        override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> = results
    }

    private fun prefix(text: String) = ImmutableUtf8Prefix.copyOf(text.toByteArray(Charsets.UTF_8))

    private fun personalSource(vararg entries: Pair<String, String>): PersonalCandidateSource =
        PersonalCandidateSource { entries.map { PersonalCandidate(it.first, it.second) } }

    private fun merge(
        dictionary: List<String>,
        exactCount: Int,
        personal: PersonalCandidateSource,
    ): List<String> =
        CompositePrefixComputer(FakePrimary(dictionary, exactCount), personal).lookup(prefix("гүз"))

    @Test
    fun personalOnlyWordTakesIndexOneWhenExactCandidatesExist() {
        val result = merge(
            listOf("гүзәллек", "гүзәлләр"), exactCount = 2,
            personalSource("гүзәлия" to "гүзәлия"),
        )
        assertEquals(listOf("гүзәллек", "гүзәлия", "гүзәлләр"), result)
    }

    @Test
    fun personalOnlyWordTakesIndexZeroWhenThereAreNoExactCandidates() {
        val result = merge(
            listOf("гүзәлфия"), exactCount = 0, // the one dictionary hit is fuzzy
            personalSource("гүзәлия" to "гүзәлия"),
        )
        assertEquals(listOf("гүзәлия", "гүзәлфия"), result)
    }

    @Test
    fun atMostOnePersonalOnlyWordEverAppears() {
        val result = merge(
            listOf("гүзәллек"), exactCount = 1,
            personalSource("гүзәлия" to "гүзәлия", "гүзәлбану" to "гүзәлбану"),
        )
        assertEquals("only one personal cell, whatever the personal source offers",
            listOf("гүзәллек", "гүзәлия"), result)
    }

    @Test
    fun theThirdExactCandidateIsPushedOutOfTheBandEntirely() {
        val result = merge(
            listOf("гүзәллек", "гүзәлләр", "гүзәллеге"), exactCount = 3,
            personalSource("гүзәлия" to "гүзәлия"),
        )
        assertEquals("the band is three cells: the third exact candidate is not shown at all",
            listOf("гүзәллек", "гүзәлия", "гүзәлләр"), result)
        assertEquals(SuggestionStripState.CELL_COUNT, result.size)
    }

    @Test
    fun fuzzyCandidatesFollowBothExactAndPersonalOnly() {
        val result = merge(
            listOf("гүзәллек", "гүзалләр"), exactCount = 1, // second entry is a fuzzy candidate
            personalSource("гүзәлия" to "гүзәлия"),
        )
        assertEquals(listOf("гүзәллек", "гүзәлия", "гүзалләр"), result)
    }

    @Test
    fun withThePersonalDictionaryOffTheResultIsByteForByteTheE3Result() {
        val dictionary = listOf("гүзәллек", "гүзәлләр", "гүзәллеге")
        val result = CompositePrefixComputer(
            FakePrimary(dictionary, lastExactCount = 2), PersonalCandidateSource.EMPTY,
        ).lookup(prefix("гүз"))
        assertSame("not merely equal — the very same list the primary returned", dictionary, result)
    }

    @Test
    fun anEmptyPersonalMatchSetChangesNothingEither() {
        val dictionary = listOf("гүзәллек")
        val result = merge(dictionary, exactCount = 1, personalSource())
        assertSame(dictionary, result)
    }

    @Test
    fun duplicateByNormalizedFormOccupiesExactlyOneCell() {
        val result = merge(
            listOf("гүзәл", "гүзәллек"), exactCount = 2,
            personalSource("гүзәл" to "гүзәл"), // saved exactly as the dictionary spells it
        )
        assertEquals("no second cell for a word the dictionary already offers",
            listOf("гүзәл", "гүзәллек"), result)
        assertEquals(1, result.count { it == "гүзәл" })
    }

    @Test
    fun theStoredCasingWinsTheDuplicateWhenItDiffersFromTheNormalizedForm() {
        val result = merge(
            listOf("гүзәл", "гүзәллек"), exactCount = 2,
            personalSource("Гүзәл" to "гүзәл"), // a name the user saved capitalised
        )
        assertEquals("one cell, spelled the way the user saved it",
            listOf("Гүзәл", "гүзәллек"), result)
    }

    @Test
    fun withinThePersonalSourceOrderIsUsageCountThenCodePoint() {
        // The order is produced by PersonalDictionary.lookupCandidates; the merge must take the
        // FIRST personal-only match of that order and no other.
        val result = merge(
            listOf("гүзәллек"), exactCount = 1,
            personalSource("гүзәлбану" to "гүзәлбану", "гүзәлия" to "гүзәлия"),
        )
        assertEquals(listOf("гүзәллек", "гүзәлбану"), result)
    }

    @Test
    fun aDuplicateNeverBecomesThePersonalOnlyWord() {
        // The first personal match duplicates a dictionary candidate: it changes that cell's casing
        // and the SECOND match becomes the personal-only word.
        val result = merge(
            listOf("гүзәл", "гүзәллек"), exactCount = 2,
            personalSource("Гүзәл" to "гүзәл", "гүзәлия" to "гүзәлия"),
        )
        assertEquals(listOf("Гүзәл", "гүзәлия", "гүзәллек"), result)
    }

    @Test
    fun aBrokenPersonalSourceCannotTakeDictionarySuggestionsDown() {
        val dictionary = listOf("гүзәллек", "гүзәлләр")
        val exploding = object : PersonalCandidateSource {
            override fun candidatesFor(normalizedPrefix: String): List<PersonalCandidate> =
                throw IllegalStateException("personal source is broken")

            override fun isEmpty(): Boolean = false
        }
        val result = CompositePrefixComputer(FakePrimary(dictionary, 2), exploding)
            .lookup(prefix("гүз"))
        assertSame(dictionary, result)
    }

    @Test
    fun theBandCapMatchesTheStripAndTheIndex() {
        assertEquals(SuggestionStripState.CELL_COUNT, CompositePrefixComputer.CELL_COUNT)
        // Three exact plus a personal-only word can never produce a fourth cell.
        val result = merge(
            listOf("а1", "а2", "а3"), exactCount = 3, personalSource("личное" to "личное"),
        )
        assertTrue(result.size <= CompositePrefixComputer.CELL_COUNT)
    }

    // --- E5c: NEXT_WORD side, two-stage readiness ------------------------------------------------

    @Test
    fun predictReturnsEmptyBeforeABigramSourceIsAttached() {
        val computer = CompositePrefixComputer(FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY)

        assertTrue(computer.predict(prefix("өй")).isEmpty())
    }

    @Test
    fun predictDelegatesToTheAttachedBigramSourceAfterAttachment() {
        val computer = CompositePrefixComputer(FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY)
        var receivedContext: String? = null
        computer.attachBigramSource(
            NextWordComputer { context ->
                receivedContext = context.decodeUtf8()
                listOf("йорт", "бакча")
            },
        )

        val result = computer.predict(prefix("өй"))

        assertEquals(listOf("йорт", "бакча"), result)
        assertEquals("өй", receivedContext)
    }

    @Test
    fun predictNeverTouchesThePrimaryOrPersonalSources() {
        // E5 has no personal bigrams and no fuzzy pass for NEXT_WORD (PROPOSALS.md, "E5c. Один
        // вычислитель, один токен") — predict must be a pure pass-through to the bigram source.
        var primaryCalls = 0
        val primary = object : ClassifiedPrefixComputer {
            override val lastExactCount: Int = 0
            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> {
                primaryCalls++
                return emptyList()
            }
        }
        var personalCalls = 0
        val personal = object : PersonalCandidateSource {
            override fun candidatesFor(normalizedPrefix: String): List<PersonalCandidate> {
                personalCalls++
                return emptyList()
            }

            override fun isEmpty(): Boolean = false
        }
        val computer = CompositePrefixComputer(primary, personal)
        computer.attachBigramSource(NextWordComputer { listOf("йорт") })

        computer.predict(prefix("өй"))

        assertEquals(0, primaryCalls)
        assertEquals(0, personalCalls)
    }

    // --- P3: after-word forms of the NEXT_WORD slot (docs/TT-SUGGESTIONS.md) ---------------------

    private fun fakeForms(forms: List<String>): AfterWordForms =
        AfterWordForms { _, _, maxOut -> forms.take(maxOut) }

    @Test
    fun formsFillTheCellsTheBigramSuccessorsLeaveFree() {
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY, fakeForms(listOf("сүзләр", "сүзем")),
        )
        computer.attachBigramSource(NextWordComputer { listOf("эшләгән") })

        assertEquals(listOf("эшләгән", "сүзләр", "сүзем"), computer.predict(prefix("сүз")))
    }

    @Test
    fun bigramSuccessorsAreNeverDisplacedByForms() {
        val bigrams = listOf("эшләгән", "белән", "туры")
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY, fakeForms(listOf("сүзләр")),
        )
        computer.attachBigramSource(NextWordComputer { bigrams })

        // Three successors take all three cells: the forms are never even consulted beyond the
        // room check, and the list is the bigram list itself.
        assertSame(bigrams, computer.predict(prefix("сүз")))
    }

    @Test
    fun withoutFormsTheNextWordAnswerIsThePureBigramList() {
        val bigrams = listOf("эшләгән")
        val computer = CompositePrefixComputer(FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY)
        computer.attachBigramSource(NextWordComputer { bigrams })

        assertSame(bigrams, computer.predict(prefix("сүз")))
    }

    @Test
    fun formsAreNotOfferedBeforeABigramSourceIsAttached() {
        // The first-NEXT_WORD race repair (docs/NEXTWORD-RACE.md) re-issues the request when the
        // attach lands, but only while the band holds no active-language word; a forms-only band
        // painted from "not attached yet" would suppress it and the bigram successors — which
        // outrank forms — would never appear until the next keystroke.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY, fakeForms(listOf("сүзләр")),
        )

        assertTrue(computer.predict(prefix("сүз")).isEmpty())
    }

    @Test
    fun aBrokenFormsSourceLeavesTheBigramAnswerUntouched() {
        val bigrams = listOf("эшләгән")
        val broken = AfterWordForms { _, _, _ -> throw IllegalStateException("broken") }
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY, broken,
        )
        computer.attachBigramSource(NextWordComputer { bigrams })

        assertSame(bigrams, computer.predict(prefix("сүз")))
    }

    @Test
    fun predictionsIgnorePersonalDictionaryEntirely() {
        // E5d, "Контракт текста" amendment, пункт 3: "Личных биграмм в E5 нет ... таблица биграмм
        // не знает о личном словаре". The call-count proof above (E5c) shows predict() never even
        // reaches the personal source; this is the same property demonstrated by RESULT instead —
        // a personal candidate that would plausibly interfere (the same word the bigram source
        // returns, or a word for the same head) never appears in or reorders the prediction list.
        val personalWithAMatchingWord = object : PersonalCandidateSource {
            override fun candidatesFor(normalizedPrefix: String): List<PersonalCandidate> =
                listOf(PersonalCandidate("йорт", "йорт"))

            override fun isEmpty(): Boolean = false
        }
        val computer = CompositePrefixComputer(FakePrimary(emptyList(), 0), personalWithAMatchingWord)
        computer.attachBigramSource(NextWordComputer { listOf("бакча", "капка") })

        val result = computer.predict(prefix("өй"))

        // Exactly the bigram source's own list, in its own order — the personal word never joins,
        // precedes, or reorders it.
        assertEquals(listOf("бакча", "капка"), result)
    }
}
