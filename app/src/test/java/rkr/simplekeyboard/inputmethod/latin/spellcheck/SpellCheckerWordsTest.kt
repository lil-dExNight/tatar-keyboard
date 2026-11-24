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

import org.junit.Assert.assertEquals
import org.junit.Test

/** The words a sentence answer is built from: positions and lengths the framework underlines. */
class SpellCheckerWordsTest {

    private fun spans(text: String) = SpellCheckerWords.wordSpans(text)
        .map { it.start to it.length }

    @Test
    fun tatarWordsKeepTheirOffsetsAcrossPunctuation() {
        assertEquals(listOf(0 to 5, 7 to 5), spans("Сәлам, дөнья!"))
    }

    @Test
    fun digitsStayInTheirWordAndSeparatorsSplit() {
        assertEquals(listOf(0 to 5, 6 to 2, 9 to 7), spans("API24 ул Android"))
    }

    @Test
    fun noWordMeansNoAnswerAndATrailingWordIsAnswered() {
        assertEquals(emptyList<Pair<Int, Int>>(), spans("..."))
        assertEquals(listOf(0 to 3, 4 to 3), spans("сүз көн"))
    }

    @Test
    fun urlsEmailsAndEmojiTokensStayWhole() {
        val tokens = listOf("https://мәктәп.татар,", "кирешмә@мәктәп.татар", "сәлам😀дөнья")
        val text = tokens.joinToString(" ")

        assertEquals(tokens, SpellCheckerWords.wordSpans(text).map {
            text.substring(it.start, it.start + it.length)
        })
        assertEquals(emptyList<Pair<Int, Int>>(), spans("😀"))
    }

    @Test
    fun compoundsAndCombiningMarksKeepOneSpan() {
        assertEquals(listOf(0 to 10), spans("азык-төлек"))
        val decomposed = "и\u0306ем"
        assertEquals(listOf(0 to decomposed.length), spans(decomposed))
    }
}
