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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime Tatar suffix table used by the same-stem prefix boost: the grouped inventory and
 * membership of representative suffixes. Chains are covered through their intermediate words.
 */
class TatarSuffixRulesTest {

    // --- Table shape ---------------------------------------------------------------------------

    @Test
    fun theTableIsSortedDistinctAndHoldsTheGroupedInventory() {
        // The init-time check already enforces strictly increasing; the count pins the inventory
        // so a careless edit cannot silently grow or shrink it.
        assertEquals(167, TatarSuffixRules.suffixCount)
    }

    // --- Membership ----------------------------------------------------------------------------

    @Test
    fun representativeSuffixesOfEveryGroupAreMembers() {
        val members = listOf(
            // plural, cases
            "лар", "ләр", "нар", "нәр", "ның", "нең", "га", "гә", "ка", "кә", "ны", "не",
            "да", "дә", "та", "тә", "дан", "дән", "тан", "тән", "нан", "нән",
            // possessives
            "ым", "ем", "м", "ың", "ең", "ң", "ы", "е", "сы", "се", "ыбыз", "ебез", "быз", "без",
            "ыгыз", "егез", "гыз", "гез", "лары", "ләре", "нары", "нәре",
            // post-3sg cases
            "н", "на", "нә", "нда", "ндә", "ннан", "ннән",
            // verb forms
            "а", "ә", "мый", "ми", "ды", "де", "ты", "те", "дым", "дең", "тык", "тегез", "дылар",
            "мады", "мәделәр", "ган", "гән", "кан", "кән", "маган", "мәгән",
            "ар", "әр", "ыр", "ер", "р", "яр", "мас", "мәс", "ачак", "әчәк", "ячак", "ячәк",
            "са", "сә", "маса", "мәсә", "ып", "еп", "п", "гач", "гәч", "кач", "кәч",
            "ганчы", "гәнче", "мыйча", "мичә", "учы", "үче", "асы", "әсе", "у", "ү", "ю",
            "макчы", "мәкче", "сың", "сең",
            // derivational
            "ча", "чә", "лык", "лек", "лы", "ле", "сыз", "сез", "чы", "че",
            "даш", "дәш", "таш", "тәш", "рак", "рәк",
        )
        for (suffix in members) {
            assertTrue("expected member: $suffix", TatarSuffixRules.isInflectedContinuation(suffix))
        }
    }

    @Test
    fun chainsForeignAndEmptyRemaindersAreNotMembers() {
        val nonMembers = listOf(
            // suffix CHAINS are not single suffixes: they reach the boost through the intermediate
            // word (татарларның via татарлар + ның), so they must not be in the table.
            "", "ларның", "ләргә", "сын", "сында", "ына", "ендә",
            // not Tatar suffixes at all
            "стан", "ание", "лыкка", "xyz", "әрм",
        )
        for (suffix in nonMembers) {
            assertFalse("expected non-member: $suffix", TatarSuffixRules.isInflectedContinuation(suffix))
        }
    }

}
