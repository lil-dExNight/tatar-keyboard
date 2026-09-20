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
 * P3 (docs/TT-SUGGESTIONS.md): the runtime Tatar suffix table — harmony classes, assimilation,
 * membership of representative suffixes, and the bounded after-word generation. Expected forms are
 * the ones `scripts/wordform_gen.py` emits for the same stems (the runtime table is the fixed
 * single-suffix inventory; chains are covered through their intermediate words).
 */
class TatarSuffixRulesTest {

    private fun generated(stem: String, maxOut: Int = 100): List<String> {
        val out = ArrayList<String>()
        TatarSuffixRules.generateForms(stem, out, maxOut)
        return out
    }

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

    // --- Generation: harmony classes and assimilation ------------------------------------------

    @Test
    fun backHarmonyConsonantStemGeneratesTheP0Paradigm() {
        val forms = generated("татар")
        for (expected in listOf(
            "татарлар", "татарның", "татарга", "татарны", "татарда", "татардан",
            "татары", "татарын", "татарына", "татарында", "татарыннан",
            "татара", "татарды", "татарыр", "татарып", "татарган", "татару", "татарча",
        )) {
            assertTrue("missing $expected in $forms", expected in forms)
        }
        assertEquals(18, forms.size)
        assertFalse("the bare stem is never a candidate", "татар" in forms)
    }

    @Test
    fun frontHarmonyConsonantStemGeneratesFrontForms() {
        val forms = generated("өй")
        for (expected in listOf(
            "өйләр", "өйнең", "өйгә", "өйне", "өйдә", "өйдән",
            "өйе", "өйен", "өйенә", "өйендә", "өйеннән",
            "өйә", "өйде", "өйәр", "өйеп", "өйгән", "өйү", "өйчә",
        )) {
            assertTrue("missing $expected in $forms", expected in forms)
        }
        // Back-harmony twins must not leak into a front stem's list.
        assertFalse("өйлар" in forms)
        assertFalse("өйга" in forms)
    }

    @Test
    fun nasalFinalStemAssimilatesPluralAndAblative() {
        val forms = generated("урман")
        assertTrue("урманнар" in forms)
        assertTrue("урманнан" in forms)
        // ...but the locative keeps д after a nasal (урманда), and the dative never assimilates.
        assertTrue("урманда" in forms)
        assertTrue("урманга" in forms)
        assertFalse("урманлар" in forms)
        assertFalse("урмантан" in forms)
    }

    @Test
    fun voicelessFinalStemAssimilatesDativeLocativeAblativeAndParticiple() {
        val forms = generated("китап")
        assertTrue("китапка" in forms)
        assertTrue("китапта" in forms)
        assertTrue("китаптан" in forms)
        assertTrue("китапкан" in forms)
        assertTrue("китапты" in forms)
        assertFalse("китапга" in forms)
        assertFalse("китапда" in forms)
    }

    @Test
    fun vowelFinalStemGeneratesTheContractedAndBareVariants() {
        val forms = generated("су")
        for (expected in listOf(
            "сулар", "суның", "суга", "суны", "суда", "судан",
            // 3sg possessive is bare -ы after у (суы), and the post-3sg cases ride it
            "суы", "суын", "суына", "суында", "суыннан",
            // present contracts the final vowel to ый; the future of a monosyllabic vowel stem
            // is -яр; the gerund is bare -п; the masdar of an у-final stem is -ю
            "сый", "суды", "суяр", "суп", "суган", "сую", "суча",
        )) {
            assertTrue("missing $expected in $forms", expected in forms)
        }
    }

    @Test
    fun frontVowelFinalStemContractsPresentToNAndTakesGlideMasdar() {
        val forms = generated("эшлә")
        assertTrue("эшли" in forms)   // present contraction ә→и
        assertTrue("эшләр" in forms)  // vowel-stem future is harmony-blind -р
        assertTrue("эшләп" in forms)
        assertTrue("эшләү" in forms)  // ә-final masdar takes -ү, not -ю
        assertTrue("эшләсе" in forms) // 3sg -се after a vowel other than у/ү
        assertFalse("эшләә" in forms)
    }

    @Test
    fun mixedHarmonyLoanStemGeneratesBothVariants() {
        val forms = generated("совет")
        assertTrue("советлар" in forms)
        assertTrue("советләр" in forms)
        assertTrue("советка" in forms)
        assertTrue("советкә" in forms)
    }

    @Test
    fun aStemWithoutAHarmonyVowelGeneratesNothing() {
        assertTrue(generated("кк").isEmpty())
        assertTrue(generated("сть").isEmpty())
    }

    @Test
    fun generationRespectsTheBoundAndNeverDuplicates() {
        val full = generated("татар")
        for (cap in listOf(0, 1, 5, 17, 18)) {
            val bounded = generated("татар", maxOut = cap)
            assertEquals(cap, bounded.size)
            assertEquals("a bound only truncates, in order", full.take(cap), bounded)
        }
        // Dual-harmony stems: 36 candidate slots, duplicates (the -р future) collapse.
        val loan = generated("совет")
        assertEquals(loan.size, loan.distinct().size)
        assertTrue(loan.size <= 36)
    }
}
