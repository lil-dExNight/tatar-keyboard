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
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidate
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource

/**
 * The D3 rules as the index enforces them: which typed word gets a verdict at all, and which
 * candidate that verdict names. Everything here runs against the same class #1 (long-press partner)
 * variant generator the E3 band already uses — there is no second edit-distance mechanism to test.
 *
 * The frequency values below are chosen around [AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY] on
 * purpose, so the threshold is exercised as the number it actually is rather than as a symbol.
 */
class TdictPrefixIndexAutocorrectTest {
    private val table = E3aTestFixtures.tatarNeighborTable()

    private fun index(
        entries: List<Pair<String, Long>>,
        withTable: Boolean = true,
    ): TdictPrefixIndex {
        val index = EngineTestFixtures.index(entries)
        if (withTable) index.updateKeyNeighbors(table)
        return index
    }

    private fun advise(index: TdictPrefixIndex, word: String): AutocorrectAdvice? {
        index.lookup(ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8)))
        return index.lastAutocorrectAdvice
    }

    @Test
    fun aWordOneLongPressEditAwayFromExactlyOneDictionaryWordIsAdvised() {
        // "китеп" is not a word; one class #1 edit (е→ё at position 3... in this fixture е↔ё) does
        // not reach it, but а↔ә does: the typed "китәп" resolves to the dictionary "китап".
        val index = index(listOf("китап" to 5_000L))

        val advice = advise(index, "китәп")

        assertNotNull(advice)
        assertEquals("китәп", advice!!.typedWord)
        assertEquals("китап", advice.replacement)
        assertEquals(5_000L, advice.frequency)
    }

    @Test
    fun aWordThatIsItselfInTheDictionaryIsNeverAdvised() {
        // "китап" is a word people write; that it is also one edit from "китәп" changes nothing.
        val index = index(listOf("китап" to 5_000L, "китәп" to 9_000L))

        assertNull(advise(index, "китап"))
    }

    @Test
    fun twoCandidatesOfTheSameClassLeaveTheWordAlone() {
        // "балә" reaches BOTH "бала" (ә→а at the end) and "бәлә" (а→ә at the second position).
        // An ambiguous typo is left alone rather than resolved by frequency — the count is taken
        // before any threshold is applied.
        val index = index(listOf("бала" to 90_000L, "бәлә" to 500L))

        assertNull(advise(index, "балә"))
    }

    @Test
    fun aCandidateBelowTheFrequencyThresholdIsNotAdvised() {
        // Re-measured 2026-09-20 (TT-SUGGESTIONS P2) on the 110 000-entry artifact:
        // 403 -> 411 (frequency of rank 10 000).
        assertEquals(411L, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY)
        val below = index(listOf("китап" to 410L))
        val atThreshold = index(listOf("китап" to 411L))

        assertNull(advise(below, "китәп"))
        assertEquals("китап", advise(atThreshold, "китәп")?.replacement)
    }

    @Test
    fun aWordShorterThanTheMinimumIsNotAdvised() {
        assertEquals(4, AutocorrectPolicy.MIN_WORD_CODE_POINTS)
        // "бал" is three code points; its class #1 variant "бәл" is a dictionary word and frequent
        // enough, and it is still left alone.
        val index = index(listOf("бәл" to 90_000L, "бәлә" to 90_000L))

        assertNull(advise(index, "бал"))
        // One letter more and the same shape is advised, so the refusal above is the length rule
        // and nothing else.
        assertEquals("бәлә", advise(index, "балә")?.replacement)
    }

    @Test
    fun aWordWithNoCandidateAtAllIsNotAdvised() {
        val index = index(listOf("китап" to 5_000L))

        assertNull(advise(index, "сүзләр"))
    }

    @Test
    fun withoutAKeyNeighborTableNothingIsEverAdvised() {
        // The table comes from the live layout; a non-alphabet layout or an ineligible field yields
        // none, and D3 then behaves exactly as it did before the phase.
        val index = index(listOf("китап" to 5_000L), withTable = false)

        assertNull(advise(index, "китәп"))
    }

    @Test
    fun theVerdictIsRecomputedByEveryLookupAndNeverLeftBehind() {
        val index = index(listOf("китап" to 5_000L))

        assertNotNull(advise(index, "китәп"))
        // A word with no candidate must clear the previous verdict, not inherit it.
        assertNull(advise(index, "сүзләр"))
        assertNotNull(advise(index, "китәп"))
        index.clearAutocorrectAdvice()
        assertNull(index.lastAutocorrectAdvice)
    }

    @Test
    fun theMatchIsWholeWordSoAContinuationIsNeverOfferedAsACorrection() {
        // "китаплар" begins with the variant "китап" and would be a prefix-block hit; D3 replaces a
        // word by a word one edit away, so with "китап" absent from the dictionary nothing is
        // advised at all.
        val index = index(listOf("китаплар" to 90_000L))

        assertNull(advise(index, "китәп"))
    }

    @Test
    fun theBandIsUnchangedByTheVerdict() {
        // The D3 pass reads the same index and must not disturb the frozen band: same three cells,
        // same order, exact before fuzzy.
        val index = index(
            listOf(
                "китап" to 5_000L,
                "китәм" to 4_000L,
                "китәпләр" to 3_000L,
            ),
        )

        val shown = index.lookup(ImmutableUtf8Prefix.copyOf("китә".toByteArray(Charsets.UTF_8)))

        assertEquals(listOf("китәм", "китәпләр", "китап"), shown)
        assertEquals(2, index.lastExactCount)
    }

    @Test
    fun aWordOfThePersonalDictionaryIsNeverAutocorrected() {
        // The pair the contract names: a personal record that the shipped asset happens to have an
        // opinion about. The verdict exists at the index level and is vetoed by the composite,
        // because the user has already said this is their word.
        val entries = listOf("гүзәл" to 90_000L)
        val personal = personalSourceOf("гүзал")
        val withPersonal = CompositePrefixComputer(index(entries), personal)
        val withoutPersonal = CompositePrefixComputer(index(entries), PersonalCandidateSource.EMPTY)

        withPersonal.lookup(ImmutableUtf8Prefix.copyOf("гүзал".toByteArray(Charsets.UTF_8)))
        withoutPersonal.lookup(ImmutableUtf8Prefix.copyOf("гүзал".toByteArray(Charsets.UTF_8)))

        assertNull(withPersonal.lastAutocorrectAdvice)
        // Without the personal record the very same word IS advised, so the refusal above is the
        // personal veto and not some other rule.
        assertEquals("гүзәл", withoutPersonal.lastAutocorrectAdvice?.replacement)
    }

    @Test
    fun aPersonalSourceThatThrowsAdvisesNothing() {
        val throwing = object : PersonalCandidateSource {
            override fun candidatesFor(normalizedPrefix: String): List<PersonalCandidate> =
                emptyList()

            override fun isEmpty(): Boolean = false

            override fun containsNormalized(normalizedWord: String): Boolean =
                throw IllegalStateException("broken personal source")
        }
        val composite = CompositePrefixComputer(index(listOf("китап" to 5_000L)), throwing)

        composite.lookup(ImmutableUtf8Prefix.copyOf("китәп".toByteArray(Charsets.UTF_8)))

        assertNull(composite.lastAutocorrectAdvice)
    }

    /** A personal source holding exactly [words], compared by normalized form as E4d does. */
    private fun personalSourceOf(vararg words: String): PersonalCandidateSource =
        object : PersonalCandidateSource {
            override fun candidatesFor(normalizedPrefix: String): List<PersonalCandidate> =
                words.filter { it.startsWith(normalizedPrefix) && it != normalizedPrefix }
                    .map { PersonalCandidate(it, it) }

            override fun isEmpty(): Boolean = words.isEmpty()

            override fun containsNormalized(normalizedWord: String): Boolean =
                words.contains(normalizedWord)
        }

    // --- ROADMAP-P3 P7: the autocorrect-class policy machinery (measured, gate-rejected) --------

    /** The P7 candidate arm: display {1, 4} + autocorrect {1, 4}. */
    private val widenedPolicy = FuzzyEditPolicy(
        intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
        true,
        intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
    )

    private fun policyIndex(
        entries: List<Pair<String, Long>>,
        policy: FuzzyEditPolicy,
    ): TdictPrefixIndex {
        val index = EngineTestFixtures.index(entries, fuzzyEditPolicy = policy)
        index.updateKeyNeighbors(table)
        return index
    }

    @Test
    fun theDefaultAutocorrectClassesAreClass1OnlyEverywhere() {
        // The P7 verdict keeps every shipped autocorrect class set at {1} — including the Tatar
        // policy (its display classes are irrelevant to autocorrect).
        assertEquals(
            listOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS),
            FuzzyEditPolicy.DEFAULT.autocorrectClasses.toList(),
        )
        assertEquals(
            listOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS),
            FuzzyEditPolicy.TATAR.autocorrectClasses.toList(),
        )
    }

    @Test
    fun class4CorrectsAClass4OnlyCaseUnderTheWidenedPolicy() {
        // "барди" is absent and NOT reachable by class #1 (р/д are no long-press pair and the
        // other letters' partners lead nowhere), but one substitution (д→р at position 2) lands
        // exactly on "барти" — nothing else in its substitution space is a dictionary word. The
        // default policy never fires here.
        val entries = listOf("барти" to 5_000L)
        val widened = policyIndex(entries, widenedPolicy)
        val current = policyIndex(entries, FuzzyEditPolicy.TATAR)

        assertNull(advise(current, "барди"))
        val advice = advise(widened, "барди")
        assertNotNull(advice)
        assertEquals("барди", advice!!.typedWord)
        assertEquals("барти", advice.replacement)
        assertEquals(5_000L, advice.frequency)
    }

    @Test
    fun twoClass4CandidatesAreStillRefusedUnderTheWidenedPolicy() {
        // "балти": position 2 л→р gives "барти", position 2 л→д gives "бадти" — two candidates,
        // the single-candidate rule refuses regardless of frequency.
        val index = policyIndex(
            listOf("бадти" to 90_000L, "барти" to 90_000L),
            widenedPolicy,
        )

        assertNull(advise(index, "балти"))
    }

    @Test
    fun aCrossClassDuplicateCountsOnceUnderTheWidenedPolicy() {
        // "бәлти" reaches "балти" by class #1 (ә→а at position 1) AND by class #4 (the same
        // substitution) — the entry must count once, or the single candidate would read as two
        // and the ambiguity rule would refuse it.
        val index = policyIndex(listOf("балти" to 5_000L), widenedPolicy)

        assertEquals("балти", advise(index, "бәлти")?.replacement)
    }

    @Test
    fun aDictionaryWordIsNeverAdvisedUnderTheWidenedPolicyEither() {
        // The G1 construction invariant, pinned on the widened pass: presence early-out wins over
        // the whole substitution space.
        val index = policyIndex(listOf("алта" to 5_000L), widenedPolicy)

        assertNull(advise(index, "алта"))
    }

    @Test
    fun theWidenedProbeCountersAreObservable() {
        // "әлтә" on this fixture: position 0 probes the whole dictionary (the fixture alphabet is
        // 32 letters, so 31 probes), later positions are skipped by the empty-range narrowing
        // (no "ә*"/"әл*"/"әлт*" words) — 31 probes exactly.
        val index = policyIndex(listOf("алта" to 5_000L), widenedPolicy)
        advise(index, "әлтә")
        assertEquals(31, index.lastAutocorrectProbeCount)
    }
}
