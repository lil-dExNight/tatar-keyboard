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
 * Named tests for the contract amendment (2026-07-27, вторая к этому пункту): "внутри нечёткого
 * уровня вводится порядок по классу правки". Inside the fuzzy level the order is edit class first
 * (#1 long-press partner, then #2 geometric neighbour, then #3 transposition) and only inside one
 * class the frozen tie-break (frequency descending, then Unicode code-point ascending). The exact
 * level stays above every fuzzy candidate and its rule is unchanged.
 *
 * The four tests are condition (3) of the frozen-contract edit procedure, one per clause of the
 * amendment.
 */
class TdictPrefixIndexEditClassRankingTest {
    // With geometry, so edit class #2 (geometric neighbour) is derived and can compete with #1.
    private val geometricTable = E3bTestFixtures.tatarNeighborTable()

    // Degenerate geometry: no geometric neighbour, so class #2 never contributes — the E3a source.
    private val longPressOnlyTable = E3aTestFixtures.tatarNeighborTable()

    private fun index(entries: List<Pair<String, Long>>, table: KeyNeighborTable): TdictPrefixIndex {
        val index = EngineTestFixtures.index(entries)
        index.updateKeyNeighbors(table)
        return index
    }

    private fun lookup(index: TdictPrefixIndex, prefix: String): List<String> =
        index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))

    /**
     * Clause 1, brought to the E3b verdict (2026-07-27; PROPOSALS.md "Контракт текста", "Итог") and
     * read through the TT-TYPO-NEXT Phase-B policy seam: this engine is opened WITHOUT an explicit
     * fuzzy policy, i.e. [FuzzyEditPolicy.DEFAULT] — class #1 only, exactly the pre-Phase-B shipped
     * behavior (and the Russian engine's configuration). The original clause asserted "class #1
     * always outranks class #2 at any frequency" via the live path; with class #2 off this path,
     * the class #2 candidate "көмеш" (frequency 9 999, reached only by the у→ө geometric neighbour)
     * never appears at all, and only the class #1 candidate "күмеш" (у→ү long-press) does. The
     * class-first order in [ranksBefore] is retained as infrastructure, so this stronger default
     * guarantee replaces the multi-class assertion.
     */
    @Test
    fun theClass2CandidateNeverAppearsBecauseClass2IsOffTheShippedPath() {
        val index = index(
            // Code-point sorted: ү (U+04AF) precedes ө (U+04E9) at the second position.
            listOf(
                "күмеш" to 1L,        // class #1 (у→ү), lowest possible frequency
                "көмеш" to 9_999L,    // class #2 (у→ө), far higher frequency — excluded from ship
            ),
            geometricTable,
        )
        assertEquals(listOf("күмеш"), lookup(index, "кум"))
    }

    /**
     * Clause 2: "внутри одного класса действует прежний порядок частота/кодпоинт."
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
            geometricTable,
        )
        assertEquals(listOf("бәрәч", "бәре", "бәрен"), lookup(index, "бар"))
    }

    /**
     * Clause 3: "точный кандидат всегда выше любого нечёткого."
     *
     * Typed "кат": the exact continuation "катык" (frequency 1) stays first even though the class
     * #1 fuzzy candidate "кәтү" (а→ә) carries frequency 9 999. The exact level is exhausted before
     * any fuzzy candidate, exactly as in E3a — the amendment does not touch the exact rule.
     */
    @Test
    fun anExactCandidateAlwaysOutranksAnyFuzzyCandidate() {
        val index = index(
            listOf(
                "катык" to 1L,        // exact continuation of "кат"
                "кәтү" to 9_999L,     // class #1 (а→ә), far higher frequency
            ),
            geometricTable,
        )
        assertEquals(listOf("катык", "кәтү"), lookup(index, "кат"))
    }

    /**
     * Clause 4 (characterization): "при единственном классе правок порядок совпадает с порядком
     * E3a."
     *
     * With the long-press-only table no geometric neighbour exists (class #2 empty), and no word is
     * reachable by a transposition of "кул" (class #3 empty), so only class #1 (у→ү) contributes.
     * When a single class contributes, the class key is a constant tie and the order collapses to
     * the E3a rule (frequency descending, then code point): identical to
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
