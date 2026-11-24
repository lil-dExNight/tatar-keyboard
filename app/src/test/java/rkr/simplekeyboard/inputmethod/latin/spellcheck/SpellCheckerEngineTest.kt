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

package rkr.simplekeyboard.inputmethod.latin.spellcheck

import android.view.textservice.SuggestionsInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes

class SpellCheckerEngineTest {

    @Test
    fun dictionaryAcceptorProperAndPersonalTatarWordsAreAccepted() {
        for (word in listOf("бүген", "сәлам", "өчен", "җир")) {
            val source = FakeSource(tatarWords = setOf(word))
            assertAccepted(SpellCheckerEngine({ true }, { source }).checkWord(word, 4))
        }
        val personal = FakeSource(personalTatar = setOf("шәһәр"))
        assertAccepted(SpellCheckerEngine({ true }, { personal }).checkWord("шәһәр", 4))
    }

    @Test
    fun tatarTypoCarriesTypoAndRecommendedFlags() {
        val source = FakeSource(
            tatarWords = setOf("сәлам"),
            candidates = SpellCheckerTypoCandidates(listOf("сәлам"), 0),
        )
        val answer = SpellCheckerEngine({ true }, { source }).checkWord("сәламм", 4)

        assertEquals(
            SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or
                SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS,
            answer.attributes,
        )
        assertEquals(listOf("сәлам"), answer.suggestions)
    }

    @Test
    fun capitalizedTypoKeepsTheUsersCasingAndMixedCaseHasNoSuggestion() {
        val source = FakeSource(
            tatarWords = setOf("сәлам"),
            candidates = SpellCheckerTypoCandidates(listOf("сәлам"), 0),
        )
        val engine = SpellCheckerEngine({ true }, { source })

        assertEquals(listOf("Сәлам"), engine.checkWord("Сәламм", 4).suggestions)
        assertEquals(listOf("СӘЛАМ"), engine.checkWord("СӘЛАММ", 4).suggestions)
        assertTrue(engine.checkWord("сӘлАмМ", 4).suggestions.isEmpty())
    }

    @Test
    fun prepareQueuesOneSourceLoadAndRequestsAnswerFromTheReadyState() {
        val queue = ArrayDeque<Runnable>()
        val source = FakeSource(tatarWords = setOf("сәлам"),
            candidates = SpellCheckerTypoCandidates(listOf("сәлам"), 0))
        var loads = 0
        val engine = SpellCheckerEngine({ true }, { loads++; source }) { task ->
            queue.addLast(task)
        }
        engine.prepare()

        assertEquals(1, queue.size)
        assertEquals(0, loads)

        assertAccepted(engine.checkWord("сәламм", 4))
        assertEquals(0, loads)
        assertEquals(1, queue.size)

        queue.removeFirst().run()
        assertEquals(1, loads)
        assertEquals(listOf("сәлам"), engine.checkWord("сәламм", 4).suggestions)
        assertEquals(1, loads)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun failedSourceLoadIsRetriedOnTheNextRequest() {
        val source = FakeSource(tatarWords = setOf("сәлам"),
            candidates = SpellCheckerTypoCandidates(listOf("сәлам"), 0))
        var loads = 0
        val engine = SpellCheckerEngine({ true }, {
            loads++
            if (loads == 1) null else source
        })

        assertAccepted(engine.checkWord("сәламм", 4))
        assertEquals(listOf("сәлам"), engine.checkWord("сәламм", 4).suggestions)
        assertEquals(2, loads)
    }

    @Test
    fun suggestionsUseOnlyTypoSliceInEngineOrderAndSkipInvalidAndTypedWords() {
        val source = FakeSource(
            tatarWords = setOf("сәлам", "өчен"),
            candidates = SpellCheckerTypoCandidates(
                listOf("бүген", "сәлам", "эчке", "сәлам", "сәламм", "өчен"),
                exactCount = 1,
            ),
        )
        val answer = SpellCheckerEngine({ true }, { source }).checkWord("сәламм", 8)

        assertEquals(listOf("сәлам", "өчен"), answer.suggestions)
        assertEquals(1, source.typoLookups)
    }

    @Test
    fun requestedLimitIsAppliedAfterFilteringAndZeroLimitHasNoRecommendations() {
        val source = FakeSource(
            tatarWords = setOf("сәлам", "өчен"),
            candidates = SpellCheckerTypoCandidates(listOf("эчке", "сәлам", "өчен"), 0),
        )
        val engine = SpellCheckerEngine({ true }, { source })

        assertEquals(listOf("сәлам"), engine.checkWord("сәламм", 1).suggestions)
        val zero = engine.checkWord("сәламм", 0)
        assertEquals(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, zero.attributes)
        assertTrue(zero.suggestions.isEmpty())
        assertFalse(zero.hasRecommendedSuggestions)
    }

    @Test
    fun latinDigitsUrlsEmailsEmojiAndMixedScriptsAreNeverFlagged() {
        val source = FakeSource()
        val engine = SpellCheckerEngine({ true }, { source })
        for (word in listOf(
            "Latin", "API24", "https://мәктәп.татар", "кирешмә@мәктәп.татар",
            "сәлам😀", "сәлам⌚", "сәلامabc", "сәلامب", "ә1",
        )) {
            assertAccepted(word, engine.checkWord(word, 4))
        }
        assertEquals(0, source.typoLookups)
    }

    @Test
    fun tatarLookingUrlDoesNotMakeNeighboringUnknownWordsTypos() {
        val engine = SpellCheckerEngine({ true }, { FakeSource() })

        val checked = engine.checkSentence("https://мәктәп.татар фыва", 4)

        assertEquals(2, checked.answers.size)
        checked.answers.forEach(::assertAccepted)
    }

    @Test
    fun tatarSpecificLetterForcesCheckingAndRussianWordIsAccepted() {
        val source = FakeSource(tatarWords = setOf("бүген"), russianWords = setOf("привет"))
        val checked = SpellCheckerEngine({ true }, { source })
            .checkSentence("бүген привет фыва", 4)

        assertAccepted(checked.answers[0])
        assertAccepted(checked.answers[1])
        assertTypo(checked.answers[2])
    }

    @Test
    fun specificLettersAreCaseFoldedBeforeSentenceClassification() {
        val source = FakeSource(tatarWords = setOf("бүген"))
        val checked = SpellCheckerEngine({ true }, { source }).checkSentence("БҮГЕН фыва", 4)

        assertAccepted(checked.answers[0])
        assertTypo(checked.answers[1])
    }

    @Test
    fun russianPersonalWordIsAcceptedInTatarSentence() {
        val source = FakeSource(tatarWords = setOf("бүген"), personalRussian = setOf("привет"))
        val checked = SpellCheckerEngine({ true }, { source })
            .checkSentence("бүген привет фыва", 4)

        assertAccepted(checked.answers[0])
        assertAccepted(checked.answers[1])
        assertTypo(checked.answers[2])
    }

    @Test
    fun mostKnownTatarWordsClassifyWithoutSpecificLetters() {
        val source = FakeSource(tatarWords = setOf("мин", "килдем"))
        val checked = SpellCheckerEngine({ true }, { source })
            .checkSentence("мин килдем фыва", 4)

        assertAccepted(checked.answers[0])
        assertAccepted(checked.answers[1])
        assertTypo(checked.answers[2])
    }

    @Test
    fun unknownWordsDoNotEnterTheLanguageDenominator() {
        val tatar = FakeSource(tatarWords = setOf("мин"))
        val checked = SpellCheckerEngine({ true }, { tatar }).checkSentence("мин фыва абвгд", 4)
        assertAccepted(checked.answers[0])
        assertTypo(checked.answers[1])
        assertTypo(checked.answers[2])

        val noneKnown = SpellCheckerEngine({ true }, { FakeSource() })
            .checkSentence("фыва абвгд", 4)
        noneKnown.answers.forEach(::assertAccepted)
    }

    @Test
    fun russianSentenceIsNotChecked() {
        val source = FakeSource(russianWords = setOf("это", "русский"))
        val checked = SpellCheckerEngine({ true }, { source })
            .checkSentence("это русский фыва", 4)

        checked.answers.forEach(::assertAccepted)
        assertEquals(0, source.typoLookups)
    }

    @Test
    fun wordOnlyUnknownWithoutSpecificLetterIsAcceptedButSpecificLetterIsChecked() {
        val engine = SpellCheckerEngine({ true }, { FakeSource() })

        assertAccepted(engine.checkWord("абвгд", 4))
        assertTypo(engine.checkWord("абвгү", 4))
    }

    @Test
    fun validRussianAndPersonalWordsAreAcceptedByWordOnlyCallback() {
        val source = FakeSource(russianWords = setOf("привет"), personalTatar = setOf("сәлам"))
        val engine = SpellCheckerEngine({ true }, { source })

        assertAccepted(engine.checkWord("привет", 4))
        assertAccepted(engine.checkWord("сәлам", 4))
    }

    @Test
    fun acceptedWordDoesNotRunTypoRecovery() {
        val source = FakeSource(tatarWords = setOf("бүген"))
        val engine = SpellCheckerEngine({ true }, { source })

        assertAccepted(engine.checkWord("бүген", 4))
        assertEquals(0, source.typoLookups)
    }

    @Test
    fun personalDictionarySwitchControlsTatarWordsAndCorrections() {
        var personalEnabled = false
        val source = FakeSource(
            personalTatar = setOf("шәһәр"),
            candidates = SpellCheckerTypoCandidates(listOf("шәһәр"), 0),
        )
        val engine = SpellCheckerEngine(
            enabled = { true },
            loadSource = { source },
            personalDictionaryEnabled = { personalEnabled },
        )

        assertTypo(engine.checkWord("шәһәр", 4))
        val correction = engine.checkWord("шәһәрр", 4)
        assertTypo(correction)
        assertTrue(correction.suggestions.isEmpty())
        assertEquals(0, source.personalTatarLookups)

        personalEnabled = true
        assertAccepted(engine.checkWord("шәһәр", 4))
        assertEquals(listOf("шәһәр"), engine.checkWord("шәһәрр", 4).suggestions)
        assertTrue(source.personalTatarLookups > 0)
    }

    @Test
    fun personalDictionarySwitchControlsRussianWordsInTatarSentence() {
        var personalEnabled = false
        val source = FakeSource(
            tatarWords = setOf("бүген"),
            personalRussian = setOf("привет"),
        )
        val engine = SpellCheckerEngine(
            enabled = { true },
            loadSource = { source },
            personalDictionaryEnabled = { personalEnabled },
        )

        val disabled = engine.checkSentence("бүген привет фыва", 4)
        assertAccepted(disabled.answers[0])
        assertTypo(disabled.answers[1])
        assertTypo(disabled.answers[2])
        assertEquals(0, source.personalRussianLookups)

        personalEnabled = true
        val enabled = engine.checkSentence("бүген привет фыва", 4)
        assertAccepted(enabled.answers[0])
        assertAccepted(enabled.answers[1])
        assertTypo(enabled.answers[2])
        assertTrue(source.personalRussianLookups > 0)
    }

    @Test
    fun settingsOffAcceptsEveryWordWithoutLoadingDictionaries() {
        var enabled = false
        var loads = 0
        val source = FakeSource()
        val engine = SpellCheckerEngine({ enabled }, { loads++; source })

        assertAccepted(engine.checkWord("бүген", 4))
        val sentence = engine.checkSentence("бүген фыва", 4)
        sentence.answers.forEach(::assertAccepted)
        assertEquals(0, loads)

        enabled = true
        assertTypo(engine.checkWord("бүген", 4))
        assertEquals(1, loads)
    }

    @Test
    fun settingsSwitchIsReadForEveryRequest() {
        var enabled = true
        val source = FakeSource(tatarWords = setOf("сәлам"),
            candidates = SpellCheckerTypoCandidates(listOf("сәлам"), 0))
        val engine = SpellCheckerEngine({ enabled }, { source })

        assertTypo(engine.checkWord("сәламм", 4))
        enabled = false
        assertAccepted(engine.checkWord("сәламм", 4))
        assertEquals(1, source.typoLookups)
        enabled = true
        assertTypo(engine.checkWord("сәламм", 4))
        assertEquals(2, source.typoLookups)
    }

    @Test
    fun tatarWordSkipsRussianLookup() {
        val source = FakeSource(tatarWords = setOf("бүген"))
        val engine = SpellCheckerEngine({ true }, { source })

        assertAccepted(engine.checkWord("бүген", 4))
        assertEquals(0, source.russianLookups)
        assertEquals(0, source.personalRussianLookups)
    }

    @Test
    fun sessionClassifiesEachSubmittedTextIndependently() {
        val source = FakeSource(tatarWords = setOf("бүген"), russianWords = setOf("это"))
        val session = SpellCheckerSession(SpellCheckerEngine({ true }, { source }))

        val tatar = session.sentence(SpellCheckerRequest("бүген фыва", 3, 7), 4)
        val russian = session.sentence(SpellCheckerRequest("это фыва", 9, 11), 4)

        assertTypo(tatar.words[1].answer)
        russian.words.forEach { assertAccepted(it.answer) }
    }

    @Test
    fun cookieSequenceAndOneAnswerPerWordArePreserved() {
        val source = FakeSource(tatarWords = setOf("сәлам", "дөнья"))
        val session = SpellCheckerSession(SpellCheckerEngine({ true }, { source }))
        val response = session.sentence(SpellCheckerRequest("Сәлам, дөнья!", 17, 23), 4)

        assertEquals(2, response.words.size)
        assertEquals(listOf(0 to 5, 7 to 5), response.words.map { it.span.start to it.span.length })
        response.words.forEach {
            assertEquals(17, it.cookie)
            assertEquals(23, it.sequence)
            assertAccepted(it.answer)
        }
        val word = session.suggestions(SpellCheckerRequest("сүз", 31, 37), 4)
        assertEquals(31, word.cookie)
        assertEquals(37, word.sequence)
    }

    private fun assertAccepted(answer: SpellCheckerAnswer) {
        assertSame(SpellCheckerAnswer.ACCEPTED, answer)
        assertTrue(answer.suggestions.isEmpty())
    }

    private fun assertAccepted(word: String, answer: SpellCheckerAnswer) {
        assertEquals(word, SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, answer.attributes)
        assertTrue(word, answer.suggestions.isEmpty())
    }

    private fun assertTypo(answer: SpellCheckerAnswer) {
        assertTrue(answer.attributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0)
        assertEquals(0, answer.attributes and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY)
    }

    private class FakeSource(
        private val tatarWords: Set<String> = emptySet(),
        private val russianWords: Set<String> = emptySet(),
        private val personalTatar: Set<String> = emptySet(),
        private val personalRussian: Set<String> = emptySet(),
        private val candidates: SpellCheckerTypoCandidates =
            SpellCheckerTypoCandidates(emptyList(), 0),
    ) : SpellCheckerWordSource {
        var typoLookups = 0
            private set
        var russianLookups = 0
            private set
        var personalRussianLookups = 0
            private set
        var personalTatarLookups = 0
            private set

        override fun tatarBaseWord(normalizedWord: String): Boolean = normalizedWord in tatarWords

        override fun russianBaseWord(normalizedWord: String): Boolean {
            russianLookups++
            return normalizedWord in russianWords
        }

        override fun personalWord(subtypeId: String, normalizedWord: String): Boolean =
            when (subtypeId) {
                PersonalSubtypes.TATAR_RU -> {
                    personalTatarLookups++
                    normalizedWord in personalTatar
                }
                PersonalSubtypes.RUSSIAN -> {
                    personalRussianLookups++
                    normalizedWord in personalRussian
                }
                else -> false
            }

        override fun typoCandidates(normalizedWord: String): SpellCheckerTypoCandidates {
            typoLookups++
            return candidates
        }
    }
}
