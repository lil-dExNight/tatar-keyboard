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
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource

/**
 * The companion-language rule of the autocorrect verdict: a typed word that is an exact entry of
 * the companion language's dictionary leaves the engine without advice, so neither the separator
 * nor the preview replaces it.
 */
class CompanionLexiconAutocorrectTest {

    private class AdvisingPrimary(private val advice: AutocorrectAdvice?) : ClassifiedPrefixComputer {
        override val lastExactCount: Int = 0
        override val lastAutocorrectAdvice: AutocorrectAdvice? get() = advice
        override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> = listOf("мәмә")
    }

    private val advice = AutocorrectAdvice(
        "мама", "мәмә", 1_000_000L, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
    )

    private fun prefix(text: String) = ImmutableUtf8Prefix.copyOf(text.toByteArray(Charsets.UTF_8))

    private fun computer(): CompositePrefixComputer =
        CompositePrefixComputer(AdvisingPrimary(advice), PersonalCandidateSource.EMPTY)

    @Test
    fun aWordOfTheCompanionDictionaryGetsNoAdvice() {
        val computer = computer()
        val asked = mutableListOf<String>()
        computer.companionLexicon = CompanionLexicon { word ->
            asked.add(word)
            word == "мама"
        }

        computer.lookup(prefix("мама"))

        assertNull(computer.lastAutocorrectAdvice)
        assertEquals(listOf("мама"), asked)
    }

    @Test
    fun aWordTheCompanionDoesNotKnowKeepsItsAdvice() {
        val computer = computer()
        computer.companionLexicon = CompanionLexicon { false }

        computer.lookup(prefix("мама"))

        assertSame(advice, computer.lastAutocorrectAdvice)
    }

    @Test
    fun withoutACompanionTheVerdictIsThePrimarys() {
        val computer = computer()

        computer.lookup(prefix("мама"))

        assertSame(advice, computer.lastAutocorrectAdvice)
    }

    @Test
    fun aFailingCompanionGivesNoAdvice() {
        val computer = computer()
        computer.companionLexicon = CompanionLexicon { throw IllegalStateException("broken") }

        computer.lookup(prefix("мама"))

        assertNull(computer.lastAutocorrectAdvice)
    }

    @Test
    fun theCompanionIsNotAskedWithoutAVerdict() {
        val computer = CompositePrefixComputer(AdvisingPrimary(null), PersonalCandidateSource.EMPTY)
        var asked = 0
        computer.companionLexicon = CompanionLexicon {
            asked++
            true
        }

        computer.lookup(prefix("мама"))

        assertNull(computer.lastAutocorrectAdvice)
        assertEquals(0, asked)
    }
}
