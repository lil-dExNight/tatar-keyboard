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
import org.junit.Test

/**
 * Ranking inside the fuzzy level under the default policy: edit class first, and only inside one
 * class the frozen tie-break (frequency descending, then Unicode code-point ascending). The exact
 * level stays above every fuzzy candidate and its rule is unchanged.
 */
class TdictPrefixIndexEditClassRankingTest {
    // The full Tatar alphabet keyboard.
    private val tatarTable = E3bTestFixtures.tatarNeighborTable()

    // The reduced letter set of [E3aTestFixtures], with the same long-press partners.
    private val longPressOnlyTable = E3aTestFixtures.tatarNeighborTable()

    private fun index(entries: List<Pair<String, Long>>, table: KeyNeighborTable): TdictPrefixIndex {
        val index = EngineTestFixtures.index(entries)
        index.updateKeyNeighbors(table)
        return index
    }

    private fun lookup(index: TdictPrefixIndex, prefix: String): List<String> =
        index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))

    /**
     * This engine is opened without an explicit fuzzy policy, i.e. [FuzzyEditPolicy.DEFAULT]
     * (class #1 only). "көмеш" (frequency 9 999) is not a long-press variant of "кум", so it never
     * appears; only the class #1 candidate "күмеш" (у→ү) does.
     */
    @Test
    fun theClass2CandidateNeverAppearsBecauseClass2IsOffTheShippedPath() {
        val index = index(
            // Code-point sorted: ү (U+04AF) precedes ө (U+04E9) at the second position.
            listOf(
                "күмеш" to 1L,        // class #1 (у→ү), lowest possible frequency
                "көмеш" to 9_999L,    // not a long-press variant, far higher frequency
            ),
            tatarTable,
        )
        assertEquals(listOf("күмеш"), lookup(index, "кум"))
    }

    /**
     * Within one edit class the frequency / code-point order applies.
     *
     * Typed "бар": every candidate is reachable only through the single class #1 variant "бәр"
     * (а→ә), so all three share one class and rank purely by the frozen tie-break — frequency
     * descending first ("бәрәч" at 100), then the 50-frequency tie broken by code point ("бәре"
     * before the longer "бәрен").
     */
    @Test
    fun withinOneClassTheOrderIsFrequencyDescendingThenCodePointAscending() {
        val index = index(
            listOf(
                "бәре" to 50L,
                "бәрен" to 50L,
                "бәрәч" to 100L,
            ),
            tatarTable,
        )
        assertEquals(listOf("бәрәч", "бәре", "бәрен"), lookup(index, "бар"))
    }

    /**
     * An exact candidate always ranks above any fuzzy one.
     *
     * Typed "кат": the exact continuation "катык" (frequency 1) stays first even though the class
     * #1 fuzzy candidate "кәтү" (а→ә) carries frequency 9 999. The exact level is exhausted before
     * any fuzzy candidate.
     */
    @Test
    fun anExactCandidateAlwaysOutranksAnyFuzzyCandidate() {
        val index = index(
            listOf(
                "катык" to 1L,        // exact continuation of "кат"
                "кәтү" to 9_999L,     // class #1 (а→ә), far higher frequency
            ),
            tatarTable,
        )
        assertEquals(listOf("катык", "кәтү"), lookup(index, "кат"))
    }

    /**
     * Characterization: with a single contributing edit class the order is the long-press-only
     * order.
     *
     * Only class #1 (у→ү) contributes, so the class key is a constant tie and the order collapses
     * to frequency descending, then code point: identical to
     * TdictPrefixIndexFuzzyTest.withinTheFuzzyLevelOrderIsFrequencyDescendingThenCodePointAscending.
     */
    @Test
    fun withASingleEditClassTheOrderMatchesE3a() {
        val index = index(
            listOf(
                "күл" to 50L,
                "күлә" to 50L,
                "күләк" to 100L,
            ),
            longPressOnlyTable,
        )
        assertEquals(listOf("күләк", "күл", "күлә"), lookup(index, "кул"))
    }
}
