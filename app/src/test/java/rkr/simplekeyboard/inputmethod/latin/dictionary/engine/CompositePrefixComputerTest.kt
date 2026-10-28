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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidate
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.glide.GlideDecoder
import rkr.simplekeyboard.inputmethod.latin.glide.GlideTestFixtures
import rkr.simplekeyboard.inputmethod.latin.glide.ListGlideInventory
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionStripState

/**
 * The single three-class ranking (exact, personal, fuzzy), one named test per rule: a
 * personal-only word takes index 1 after exact candidates (index 0 when there are none), at most
 * one personal-only word appears, and a personal match of a dictionary word only changes casing.
 *
 * The tests drive [CompositePrefixComputer] with a fake primary, so they exercise the merge itself
 * rather than the mmap index: what is under test is the ORDER, not the dictionary.
 */
class CompositePrefixComputerTest {

    private class FakePrimary(
        private val results: List<String>,
        override val lastExactCount: Int,
        // Long.MAX_VALUE models a strong dictionary top: the promotion gate stays shut unless a
        // test arms a weak one.
        override val lastTopExactFrequency: Long = Long.MAX_VALUE,
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
        topFrequency: Long = Long.MAX_VALUE,
    ): List<String> =
        CompositePrefixComputer(FakePrimary(dictionary, exactCount, topFrequency), personal)
            .lookup(prefix("гүз"))

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
    fun theFourthExactCandidateIsPushedOutOfTheBandEntirely() {
        val result = merge(
            listOf("гүзәллек", "гүзәлләр", "гүзәллеге", "гүзәллеген"), exactCount = 4,
            personalSource("гүзәлия" to "гүзәлия"),
        )
        assertEquals("the band is four cells: the fourth exact candidate is not shown at all",
            listOf("гүзәллек", "гүзәлия", "гүзәлләр", "гүзәллеге"), result)
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
            personalSource("Гүзәл" to "гүзәл"), // a name the user saved capitalized
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

    // --- Personal word promotion ---------------------------------------------------------------

    @Test
    fun aGraduatedPersonalWordLeadsWhenTheDictionaryTopIsWeak() {
        val result = merge(
            listOf("гүзәллек", "гүзәлләр", "гүзәллеге", "гүзәллеген"), exactCount = 4,
            personalSource("гүзәлия" to "гүзәлия"),
            topFrequency = CompositePrefixComputer.PROMOTION_TOP_FREQUENCY_GATE - 1,
        )
        assertEquals(
            "the personal word leads; the fourth exact candidate leaves the strip",
            listOf("гүзәлия", "гүзәллек", "гүзәлләр", "гүзәллеге"), result,
        )
    }

    @Test
    fun aGraduatedPersonalWordNeverLeadsAboveAFrequentDictionaryTop() {
        val result = merge(
            listOf("гүзәллек", "гүзәлләр"), exactCount = 2,
            personalSource("гүзәлия" to "гүзәлия"),
            topFrequency = CompositePrefixComputer.PROMOTION_TOP_FREQUENCY_GATE,
        )
        assertEquals(listOf("гүзәллек", "гүзәлия", "гүзәлләр"), result)
    }

    @Test
    fun aSubThresholdPersonalWordKeepsTheSecondCell() {
        val result = merge(
            listOf("гүзәллек", "гүзәлләр"), exactCount = 2,
            PersonalCandidateSource {
                listOf(PersonalCandidate("гүзәлия", "гүзәлия", usageCount = 0))
            },
            topFrequency = 1L,
        )
        assertEquals(listOf("гүзәллек", "гүзәлия", "гүзәлләр"), result)
    }

    @Test
    fun thePromotionNeverReordersWithinAWordLifetime() {
        // The promotion moves the word exactly one cell (from the second to the first), and a
        // growing usage counter never moves it again.
        val dictionary = listOf("гүзәллек", "гүзәлләр")
        val subThreshold = CompositePrefixComputer(
            FakePrimary(dictionary, 2, 1L),
            PersonalCandidateSource {
                listOf(PersonalCandidate("гүзәлия", "гүзәлия", usageCount = 0))
            },
        ).lookup(prefix("гүз"))
        val justGraduated = CompositePrefixComputer(
            FakePrimary(dictionary, 2, 1L),
            PersonalCandidateSource {
                listOf(PersonalCandidate("гүзәлия", "гүзәлия", usageCount = 1))
            },
        ).lookup(prefix("гүз"))
        val grown = CompositePrefixComputer(
            FakePrimary(dictionary, 2, 1L),
            PersonalCandidateSource {
                listOf(PersonalCandidate("гүзәлия", "гүзәлия", usageCount = 5))
            },
        ).lookup(prefix("гүз"))
        assertEquals(listOf("гүзәллек", "гүзәлия", "гүзәлләр"), subThreshold)
        assertEquals(listOf("гүзәлия", "гүзәллек", "гүзәлләр"), justGraduated)
        assertEquals(justGraduated, grown)
        assertEquals(1, subThreshold.indexOf("гүзәлия") - justGraduated.indexOf("гүзәлия"))
    }

    @Test
    fun withThePersonalStoreEmptyThePromotionCannotFire() {
        // Zero-churn is structural: with an empty source the merge returns the primary's own list
        // even when the frequency gate would pass.
        val dictionary = listOf("гүзәллек", "гүзәлләр")
        val result = CompositePrefixComputer(
            FakePrimary(dictionary, 2, 1L), PersonalCandidateSource.EMPTY,
        ).lookup(prefix("гүз"))
        assertSame("not merely equal — the very same list the primary returned", dictionary, result)
    }

    // --- The context boost of the exact pass -----------------------------------------------------

    @Test
    fun theContextWordResolvesToPackedIdsThatArmThePrimary() {
        val received = mutableListOf<Pair<IntArray, Int>>()
        val primary = object : ContextBoostedPrefixComputer {
            override val lastExactCount = 1
            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> =
                throw AssertionError("an armed request must reach the armed lookup")

            override fun lookup(
                normalizedPrefixUtf8: ImmutableUtf8Prefix,
                contextSuccessorIds: IntArray,
                contextSuccessorCount: Int,
            ): List<String> {
                received += contextSuccessorIds.copyOf(contextSuccessorCount) to contextSuccessorCount
                return listOf("казанган", "казан")
            }
        }
        val bigrams = object : NextWordComputer, PackedSuccessorSource {
            override fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String> =
                emptyList()

            override fun collectSuccessorIds(
                normalizedContextWordUtf8: ImmutableUtf8Prefix,
                sink: IntArray,
            ): Int {
                sink[0] = 42
                sink[1] = 7
                return 2
            }
        }
        val computer = CompositePrefixComputer(primary, PersonalCandidateSource.EMPTY)
        computer.attachBigramSource(bigrams)

        val result = computer.lookup(prefix("ка"), prefix("дан"))

        assertEquals(listOf("казанган", "казан"), result)
        assertEquals(listOf(intArrayOf(42, 7).toList() to 2), received.map { it.first.toList() to it.second })
    }

    @Test
    fun withoutABigramSourceTheArmedLookupFallsBackToThePlainOne() {
        val primary = object : ContextBoostedPrefixComputer {
            override val lastExactCount = 1
            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> =
                listOf("казан", "казанда")

            override fun lookup(
                normalizedPrefixUtf8: ImmutableUtf8Prefix,
                contextSuccessorIds: IntArray,
                contextSuccessorCount: Int,
            ): List<String> = throw AssertionError("no table, no armed lookup")
        }
        val computer = CompositePrefixComputer(primary, PersonalCandidateSource.EMPTY)

        assertEquals(listOf("казан", "казанда"), computer.lookup(prefix("ка"), prefix("дан")))
    }

    @Test
    fun aBrokenBigramTableLeavesTheLookupUnarmed() {
        var armedCalls = 0
        val primary = object : ContextBoostedPrefixComputer {
            override val lastExactCount = 1
            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> =
                listOf("казан", "казанда")

            override fun lookup(
                normalizedPrefixUtf8: ImmutableUtf8Prefix,
                contextSuccessorIds: IntArray,
                contextSuccessorCount: Int,
            ): List<String> {
                armedCalls++
                return emptyList()
            }
        }
        val broken = object : NextWordComputer, PackedSuccessorSource {
            override fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String> =
                emptyList()

            override fun collectSuccessorIds(
                normalizedContextWordUtf8: ImmutableUtf8Prefix,
                sink: IntArray,
            ): Int = throw IllegalStateException("broken table")
        }
        val computer = CompositePrefixComputer(primary, PersonalCandidateSource.EMPTY)
        computer.attachBigramSource(broken)

        assertEquals(listOf("казан", "казанда"), computer.lookup(prefix("ка"), prefix("дан")))
        assertEquals(0, armedCalls)
    }

    @Test
    fun aZeroCountResolutionKeepsThePlainLookup() {
        // The context word heads no table entry: no successor ids, no armed lookup.
        var armedCalls = 0
        val primary = object : ContextBoostedPrefixComputer {
            override val lastExactCount = 1
            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> =
                listOf("казан")

            override fun lookup(
                normalizedPrefixUtf8: ImmutableUtf8Prefix,
                contextSuccessorIds: IntArray,
                contextSuccessorCount: Int,
            ): List<String> {
                armedCalls++
                return emptyList()
            }
        }
        val noHeads = object : NextWordComputer, PackedSuccessorSource {
            override fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String> =
                emptyList()

            override fun collectSuccessorIds(
                normalizedContextWordUtf8: ImmutableUtf8Prefix,
                sink: IntArray,
            ): Int = 0
        }
        val computer = CompositePrefixComputer(primary, PersonalCandidateSource.EMPTY)
        computer.attachBigramSource(noHeads)

        assertEquals(listOf("казан"), computer.lookup(prefix("ка"), prefix("дан")))
        assertEquals(0, armedCalls)
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

    // --- NEXT_WORD side, two-stage readiness ----------------------------------------------------

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
        // The PREFIX-path sources stay out of NEXT_WORD: learned pairs arrive through their OWN
        // seam ([PersonalBigramSource], EMPTY here), so predict() never reaches the primary or the
        // prefix personal source — the property this test pins by call count.
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

    // --- after-word forms of the NEXT_WORD slot ------------------------------------------------

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
        val bigrams = listOf("эшләгән", "белән", "туры", "дөнья")
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY, fakeForms(listOf("сүзләр")),
        )
        computer.attachBigramSource(NextWordComputer { bigrams })

        // Four successors take all four cells: the forms are never even consulted beyond the
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
    fun formsAnswerWhenNoBigramSourceIsAttached() {
        // No table at all takes only the bigram successors out of the chain: the word forms
        // still answer, so a language without a table (or one whose table failed to open) does
        // not show an empty strip forever.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY, fakeForms(listOf("сүзләр")),
        )

        assertEquals(listOf("сүзләр"), computer.predict(prefix("сүз")))
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
        // The PREFIX-path personal source is still never consulted by predict(): learned pairs
        // come through their OWN seam, and this computer was built without it (EMPTY). The
        // call-count test above shows predict() never reaches the prefix personal source; this
        // shows the same by result — a personal word never appears in or reorders the list.
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

    // --- learned word pairs in the NEXT_WORD slot ----------------------------------------------

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
        val bigrams = listOf("йорт", "бакча", "капка", "дөнья")
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            personalBigrams = fakeBigrams("да" to "да"),
        )
        computer.attachBigramSource(NextWordComputer { bigrams })

        // Four successors take all four cells: the personal source is never even consulted
        // beyond the emptiness check, and the list is the bigram list itself.
        assertSame(bigrams, computer.predict(prefix("өй")))
    }

    @Test
    fun atMostThreeCellsArePersonalEvenWithAllCellsFree() {
        // The leave-room pin: with no static successor at all the personal half takes THREE cells
        // and the forms/fallback half keeps its chance at the fourth.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")),
            personalBigrams = fakeBigrams(
                "бакча" to "бакча", "капка" to "капка", "йорт" to "йорт", "эчке" to "эчке",
            ),
        )
        computer.attachBigramSource(NextWordComputer { emptyList() })

        assertEquals(3, CompositePrefixComputer.MAX_PERSONAL_BIGRAM_CELLS)
        assertEquals(listOf("бакча", "капка", "йорт", "сүзләр"), computer.predict(prefix("өй")))
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
    fun personalPairsAnswerWhenNoBigramSourceIsAttached() {
        // The no-source chain is pairs, then forms, then the fallback: the pairs lead as they do
        // behind the bigram successors, and each later source fills what the earlier ones left.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")), fakeFallback(listOf("һәм")),
            fakeBigrams("бакча" to "бакча"),
        )

        assertEquals(listOf("бакча", "сүзләр", "һәм"), computer.predict(prefix("өй")))
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

    // --- the glide channel's successor source (learned pairs above the bundled table) ---------

    @Test
    fun learnedPairsRankAboveBundledSuccessorsInTheGlideChannel() {
        // The no-loop path decodes to the twin ("ала") and the channel penalty is forced
        // decisive, so the glide answer's leader is the context word's first successor: "ала"
        // from the bundled table alone, the learned pair "алла" once it merges in ahead of it.
        val geometry = GlideTestFixtures.tatarGeometry()
        val path = GlideTestFixtures.idealPath("алла", geometry)!!
        fun computerWith(personal: PersonalBigramSource): CompositePrefixComputer {
            val host = GlideDecoderHost(
                ListGlideInventory(listOf("алла" to 7466L, "ала" to 36L)),
                glideConstants = GlideDecoder.GlideConstants(bigramRankPenalty = 1e6f),
            )
            host.updateGlideGeometry(geometry)
            return CompositePrefixComputer(
                FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
                personalBigrams = personal, glideHost = host,
            ).also { it.attachBigramSource(NextWordComputer { listOf("ала") }) }
        }

        assertEquals("ала", computerWith(PersonalBigramSource.EMPTY).decodeGlide(path, null)[0])
        assertEquals("ала", computerWith(PersonalBigramSource.EMPTY).decodeGlide(path, "кук")[0])
        assertEquals(
            "алла",
            computerWith(fakeBigrams("алла" to "алла")).decodeGlide(path, "кук")[0],
        )
    }

    // --- the global top-frequency fallback of the NEXT_WORD slot ------------------------------

    /** A fake fallback with the production exclusion semantics, so the merge itself is measured. */
    private fun fakeFallback(words: List<String>): FallbackWords =
        FallbackWords { context, alreadyShown, maxOut ->
            words.filter { it != context.decodeUtf8() && !alreadyShown.contains(it) }.take(maxOut)
        }

    @Test
    fun theFallbackFillsTheCellsBigramsAndFormsLeaveFree() {
        // The chain is bigram successors > after-word forms > fallback; the fallback takes the
        // two cells the single successor and the single form leave free.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")), fakeFallback(listOf("һәм", "белән")),
        )
        computer.attachBigramSource(NextWordComputer { listOf("эшләгән") })

        assertEquals(listOf("эшләгән", "сүзләр", "һәм", "белән"), computer.predict(prefix("сүз")))
    }

    @Test
    fun theFallbackNeverDisplacesBigramsOrForms() {
        val bigrams = listOf("эшләгән", "белән", "туры", "дөнья")
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")), fakeFallback(listOf("һәм")),
        )
        computer.attachBigramSource(NextWordComputer { bigrams })

        // Four successors take all four cells: the forms and the fallback are never consulted
        // beyond the room check, and the list is the bigram list itself.
        assertSame(bigrams, computer.predict(prefix("сүз")))
    }

    @Test
    fun theFallbackAnswersWhenNoBigramSourceIsAttached() {
        // With no table the fallback still fills the cells the forms leave free, in the same
        // order as behind the bigram successors.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")), fakeFallback(listOf("һәм", "белән", "да")),
        )

        assertEquals(listOf("сүзләр", "һәм", "белән", "да"), computer.predict(prefix("сүз")))
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

        // Only bigrams + forms, with no third source.
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

    // --- failed bigram attach: terminal, but the rest of the chain still answers -----------------

    @Test
    fun aFailedDeferredAttachAnswersFromPairsFormsAndFallback() {
        // The attach throws (a corrupt table's open walk fails inside it): the failure is
        // terminal for the bigram successors only — pairs, forms and the fallback still answer.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")), fakeFallback(listOf("һәм")),
            fakeBigrams("бакча" to "бакча"),
        )
        computer.deferBigramAttach { throw IllegalStateException("table is broken") }

        assertEquals(listOf("бакча", "сүзләр", "һәм"), computer.predict(prefix("өй")))
    }

    @Test
    fun aDeferredAttachThatOpensNoTableAnswersFromTheChain() {
        // The attach runs cleanly but wires nothing (a missing or vanished table): the same
        // fall-through as a throwing attach.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")), fakeFallback(listOf("һәм")),
        )
        computer.deferBigramAttach { }

        assertEquals(listOf("сүзләр", "һәм"), computer.predict(prefix("сүз")))
    }

    @Test
    fun aFailedAttachIsConsumedAndNeverRetried() {
        // Terminal means terminal: the attach ran once, and later predictions do not run it
        // again — a failing table open must not be paid on every NEXT_WORD lookup.
        var attachRuns = 0
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")),
        )
        computer.deferBigramAttach {
            attachRuns++
            throw IllegalStateException("table is broken")
        }

        computer.predict(prefix("сүз"))
        computer.predict(prefix("өй"))
        computer.predict(prefix("бакча"))

        assertEquals(1, attachRuns)
        assertEquals(listOf("сүзләр"), computer.predict(prefix("сүз")))
    }

    @Test
    fun aLateSuccessfulAttachStillFeedsTheChain() {
        // The failing case is terminal, but a deferred attach that succeeds on the first
        // predict feeds the successors into the chain ahead of pairs and forms.
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), 0), PersonalCandidateSource.EMPTY,
            fakeForms(listOf("сүзләр")),
            fakeFallback(listOf("һәм")),
            fakeBigrams("бакча" to "бакча"),
        )
        computer.deferBigramAttach {
            computer.attachBigramSource(NextWordComputer { listOf("йорт") })
        }

        assertEquals(listOf("йорт", "бакча", "сүзләр", "һәм"), computer.predict(prefix("өй")))
    }

    // --- The exact-miss verdict ------------------------------------------------------------------

    @Test
    fun anEmptyExactPassIsReportedEvenWhenTypoRecoveryFilledTheStrip() {
        val computer = CompositePrefixComputer(
            FakePrimary(listOf("көлә", "кулә"), lastExactCount = 0), PersonalCandidateSource.EMPTY,
        )
        assertEquals(listOf("көлә", "кулә"), computer.lookup(prefix("күлә")))
        assertEquals("күлә", computer.lastExactMissPrefix)
    }

    @Test
    fun anExactCandidateClearsTheExactMissVerdict() {
        val primary = object : ClassifiedPrefixComputer {
            var exact = 0
            override val lastExactCount: Int get() = exact
            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> =
                if (exact == 0) emptyList() else listOf("гүзәл")
        }
        val computer = CompositePrefixComputer(primary, PersonalCandidateSource.EMPTY)
        computer.lookup(prefix("гүз"))
        assertEquals("гүз", computer.lastExactMissPrefix)
        primary.exact = 1
        computer.lookup(prefix("гүз"))
        assertNull(computer.lastExactMissPrefix)
    }

    @Test
    fun clearingTheVerdictsDropsTheExactMiss() {
        val computer = CompositePrefixComputer(
            FakePrimary(emptyList(), lastExactCount = 0), PersonalCandidateSource.EMPTY,
        )
        computer.lookup(prefix("гүз"))
        computer.clearLookupVerdicts()
        assertNull(computer.lastExactMissPrefix)
    }
}
