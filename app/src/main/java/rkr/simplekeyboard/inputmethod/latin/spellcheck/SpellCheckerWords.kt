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

/** One word of a submitted sentence, as UTF-16 offset and length. */
internal data class WordSpan(val start: Int, val length: Int)

/**
 * Splits a submitted sentence into the words its answers are per-word. A word is a run of letters
 * and digits; whitespace, punctuation and symbols separate words and are never answered, so
 * nothing but a word can be underlined.
 */
internal object SpellCheckerWords {

    fun wordSpans(text: CharSequence): List<WordSpan> {
        val spans = ArrayList<WordSpan>()
        var wordStart = -1
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            if (Character.isLetterOrDigit(codePoint)) {
                if (wordStart < 0) {
                    wordStart = offset
                }
            } else if (wordStart >= 0) {
                spans.add(WordSpan(wordStart, offset - wordStart))
                wordStart = -1
            }
            offset += Character.charCount(codePoint)
        }
        if (wordStart >= 0) {
            spans.add(WordSpan(wordStart, text.length - wordStart))
        }
        return spans
    }
}
