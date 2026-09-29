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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramSource
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
        // The PREFIX-path sources stay out of NEXT_WORD even after P1 (docs/ROADMAP-P2.md):
        // personal bigrams arrive through their OWN seam ([PersonalBigramSource], EMPTY here), so
        // predict() still never reaches the primary or the prefix personal source — the property
        // this test pins by call count.
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
        // The PREFIX-path personal source is still never consulted by predict() — P1 of Phase 2
        // (docs/ROADMAP-P2.md) brings personal bigrams through their OWN seam, and this computer
        // was built without it (EMPTY). The call-count proof above (E5c) shows predict() never
        // reaches the prefix personal source; this is the same property demonstrated by RESULT —
        // a personal word that would plausibly interfere never appears in or reorders the list.
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

    // --- P1: personal bigrams of the NEXT_WORD slot (docs/ROADMAP-P2.md) -------------------------

    private fun fakeBigrams(vararg pairs: Pair<String, String>): PersonalBigramSource =
        object : PersonalBigramSource {
            override fun successorsFor(normalizedContextWord: String): List<PersonalCandidate> =
                pairs.map { PersonalCandidate(it.first, it.second) }

            override fun isEmpty(): Boolean = false
        }

    @Test
    fun personalPairsFillTheCellsTheStaticSuccessorsLeaveFree() {
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            personalBigrams = fakeBigrams("бакча" to "бакча", "капка" to "капка"),
        )
        computer.attachBigramSource(NextWordComputer { listOf("йорт") })

        assertEquals(listOf("йорт", "бакча", "капка"), computer.predict(prefix("өй")))
    }

    @Test
    fun staticSuccessorsAreNeverDisplacedByPersonalPairs() {
        val bigrams = listOf("йорт", "бакча", "капка")
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            personalBigrams = fakeBigrams("да" to "да"),
        )
        computer.attachBigramSource(NextWordComputer { bigrams })

        // Three successors take all three cells: the personal source is never even consulted
        // beyond the emptiness check, and the list is the bigram list itself.
        assertSame(bigrams, computer.predict(prefix("өй")))
    }

    @Test
    fun atMostTwoCellsArePersonalEvenWithAllCellsFree() {
        // The leave-room pin: with no static successor at all the personal half takes TWO cells
        // and the forms/fallback half keeps its chance at the third.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")),
            personalBigrams = fakeBigrams(
                "бакча" to "бакча", "капка" to "капка", "йорт" to "йорт",
            ),
        )
        computer.attachBigramSource(NextWordComputer { emptyList() })

        assertEquals(2, CompositePrefixComputer.MAX_PERSONAL_BIGRAM_CELLS)
        assertEquals(listOf("бакча", "капка", "сүзләр"), computer.predict(prefix("өй")))
    }

    @Test
    fun aPairDuplicatingAStaticSuccessorIsShownOnceAndTheStaticSpellingWins() {
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            personalBigrams = fakeBigrams("Бакча" to "бакча", "капка" to "капка"),
        )
        computer.attachBigramSource(NextWordComputer { listOf("бакча", "йорт") })

        val result = computer.predict(prefix("өй"))
        assertEquals(listOf("бакча", "йорт", "капка"), result)
        assertEquals("the duplicate is shown once, spelled as the table spells it",
            1, result.count { it.equals("бакча", ignoreCase = true) })
    }

    @Test
    fun thePairCasingIsShownForAPersonalOnlyCell() {
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            personalBigrams = fakeBigrams("Гүзәл" to "гүзәл"),
        )
        computer.attachBigramSource(NextWordComputer { listOf("минем") })

        assertEquals(listOf("минем", "Гүзәл"), computer.predict(prefix("исем")))
    }

    @Test
    fun personalPairsAreNotOfferedBeforeABigramSourceIsAttached() {
        // The NEXTWORD-RACE rule holds for personal pairs exactly as for forms and the fallback:
        // a personal-only band painted from "not attached yet" would suppress the re-request the
        // attach repair fires, and the static successors — which outrank pairs — would never
        // appear until the next keystroke.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")), fakeFallback(listOf("һәм")),
            fakeBigrams("бакча" to "бакча"),
        )

        assertTrue(computer.predict(prefix("өй")).isEmpty())
    }

    @Test
    fun aBrokenPersonalBigramSourceLeavesTheStaticAnswerUntouched() {
        val bigrams = listOf("йорт")
        val broken = object : PersonalBigramSource {
            override fun successorsFor(normalizedContextWord: String): List<PersonalCandidate> =
                throw IllegalStateException("broken")

            override fun isEmpty(): Boolean = false
        }
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY, personalBigrams = broken,
        )
        computer.attachBigramSource(NextWordComputer { bigrams })

        assertSame(bigrams, computer.predict(prefix("өй")))
    }

    @Test
    fun formsAndFallbackAlreadyExcludeThePersonalPairsShown() {
        var formsSaw: List<String>? = null
        var fallbackSaw: List<String>? = null
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            AfterWordForms { _, alreadyShown, _ ->
                formsSaw = alreadyShown
                emptyList() // no forms: the fallback must be reached with the pairs excluded too
            },
            FallbackWords { _, alreadyShown, _ ->
                fallbackSaw = alreadyShown
                listOf("һәм")
            },
            fakeBigrams("бакча" to "бакча", "капка" to "капка"),
        )
        computer.attachBigramSource(NextWordComputer { emptyList() })

        computer.predict(prefix("өй"))
        assertEquals("forms are asked to exclude everything already shown, pairs included",
            listOf("бакча", "капка"), formsSaw)
        assertEquals(listOf("бакча", "капка"), fallbackSaw)
    }

    @Test
    fun withThePersonalBigramSourceEmptyTheAnswerIsByteForByteThePreP1One() {
        val bigrams = listOf("йорт", "бакча")
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
        )
        computer.attachBigramSource(NextWordComputer { bigrams })

        assertSame(bigrams, computer.predict(prefix("өй")))
    }

    @Test
    fun anEmptyPersonalBigramMatchSetChangesNothing() {
        val bigrams = listOf("йорт")
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            personalBigrams = fakeBigrams(),
        )
        computer.attachBigramSource(NextWordComputer { bigrams })

        assertSame(bigrams, computer.predict(prefix("өй")))
    }

    // --- TT-NEXTWORD-FILL: the global top-frequency fallback of the NEXT_WORD slot -------------

    /** A fake fallback with the production exclusion semantics, so the merge itself is measured. */
    private fun fakeFallback(words: List<String>): FallbackWords =
        FallbackWords { context, alreadyShown, maxOut ->
            words.filter { it != context.decodeUtf8() && !alreadyShown.contains(it) }.take(maxOut)
        }

    @Test
    fun theFallbackFillsTheCellsBigramsAndFormsLeaveFree() {
        // The chain is bigram successors > after-word forms > fallback, one cell each.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")), fakeFallback(listOf("һәм", "белән")),
        )
        computer.attachBigramSource(NextWordComputer { listOf("эшләгән") })

        assertEquals(listOf("эшләгән", "сүзләр", "һәм"), computer.predict(prefix("сүз")))
    }

    @Test
    fun theFallbackNeverDisplacesBigramsOrForms() {
        val bigrams = listOf("эшләгән", "белән", "туры")
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")), fakeFallback(listOf("һәм")),
        )
        computer.attachBigramSource(NextWordComputer { bigrams })

        // Three successors take all three cells: the forms and the fallback are never consulted
        // beyond the room check, and the list is the bigram list itself.
        assertSame(bigrams, computer.predict(prefix("сүз")))
    }

    @Test
    fun theFallbackIsNotOfferedBeforeABigramSourceIsAttached() {
        // The NEXTWORD-RACE repair re-issues the request when the attach lands, but only while the
        // band holds no active-language word; a fallback-only band painted from "not attached yet"
        // would suppress it — the exact rule the forms already live by.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")), fakeFallback(listOf("һәм", "белән", "да")),
        )

        assertTrue(computer.predict(prefix("сүз")).isEmpty())
    }

    @Test
    fun aBrokenFallbackLeavesTheBigramsAndFormsUntouched() {
        val broken = FallbackWords { _, _, _ -> throw IllegalStateException("broken") }
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")), broken,
        )
        computer.attachBigramSource(NextWordComputer { listOf("эшләгән") })

        assertEquals(listOf("эшләгән", "сүзләр"), computer.predict(prefix("сүз")))
    }

    @Test
    fun brokenFormsDoNotTakeTheFallbackDown() {
        val broken = AfterWordForms { _, _, _ -> throw IllegalStateException("broken") }
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            broken, fakeFallback(listOf("һәм", "белән")),
        )
        computer.attachBigramSource(NextWordComputer { listOf("эшләгән") })

        assertEquals(listOf("эшләгән", "һәм", "белән"), computer.predict(prefix("сүз")))
    }

    @Test
    fun withoutAFallbackTheNextWordAnswerIsUnchanged() {
        val bigrams = listOf("эшләгән")
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY, fakeForms(listOf("сүзләр")),
        )
        computer.attachBigramSource(NextWordComputer { bigrams })

        // Exactly the pre-fill answer: bigrams + forms, and no third source.
        assertEquals(listOf("эшләгән", "сүзләр"), computer.predict(prefix("сүз")))
    }

    @Test
    fun theRealFallbackExcludesTheCommittedWordAndTheAlreadyShown() {
        // The production implementation against a fixed pool: the committed word drops out, an
        // already-shown word drops out, the pool's own order stands.
        val fallback = GlobalTopFrequencyFallback(listOf("һәм", "белән", "да", "бу"))
        assertEquals(
            listOf("да", "бу"),
            fallback.fallbackWords(prefix("һәм"), listOf("белән"), 3),
        )
    }

    @Test
    fun theRealFallbackNeverExceedsMaxOutAndNeverDuplicates() {
        val fallback = GlobalTopFrequencyFallback(listOf("һәм", "белән", "да", "бу"))
        assertEquals(1, fallback.fallbackWords(prefix("сүз"), emptyList(), 1).size)
        assertTrue(fallback.fallbackWords(prefix("сүз"), emptyList(), 0).isEmpty())
        val filled = fallback.fallbackWords(prefix("һәм"), listOf("белән", "да", "бу"), 3)
        assertTrue(filled.isEmpty())
        assertEquals(filled.distinct(), filled)
    }
}
