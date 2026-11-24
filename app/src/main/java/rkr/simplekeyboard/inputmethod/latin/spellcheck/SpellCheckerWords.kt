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

import java.util.Locale

/** One word of a submitted sentence, as UTF-16 offset and length. */
internal data class WordSpan(val start: Int, val length: Int)

/**
 * Splits a submitted sentence into the words its answers are per-word. Letters, digits, combining
 * marks and an internal hyphen form a word; whitespace and punctuation separate words. A token
 * holding a URL, an e-mail address or an emoji stays one span, so none of its parts is flagged.
 */
internal object SpellCheckerWords {

    fun wordSpans(text: CharSequence): List<WordSpan> {
        val spans = ArrayList<WordSpan>()
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            if (isSpace(codePoint)) {
                offset += Character.charCount(codePoint)
                continue
            }
            val tokenStart = offset
            while (offset < text.length) {
                val next = Character.codePointAt(text, offset)
                if (isSpace(next)) break
                offset += Character.charCount(next)
            }
            val token = text.subSequence(tokenStart, offset)
            if (SpellCheckerText.isProtectedToken(token)) {
                spans.add(WordSpan(tokenStart, token.length))
            } else {
                addWordSpans(token, tokenStart, spans)
            }
        }
        return spans
    }

    private fun addWordSpans(token: CharSequence, tokenStart: Int, spans: MutableList<WordSpan>) {
        var wordStart = -1
        var offset = 0
        while (offset < token.length) {
            val codePoint = Character.codePointAt(token, offset)
            val width = Character.charCount(codePoint)
            val type = Character.getType(codePoint)
            val isMark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            val internalHyphen = codePoint == '-'.code &&
                wordStart >= 0 && offset + width < token.length &&
                Character.isLetter(Character.codePointAt(token, offset + width))
            when {
                Character.isLetterOrDigit(codePoint) -> {
                    if (wordStart < 0) wordStart = offset
                }
                isMark -> {
                    // A mark continues the preceding base letter; an orphan mark is skipped.
                }
                internalHyphen -> {
                    // Kept inside the word.
                }
                else -> {
                    if (wordStart >= 0) {
                        spans.add(WordSpan(tokenStart + wordStart, offset - wordStart))
                        wordStart = -1
                    }
                }
            }
            offset += width
        }
        if (wordStart >= 0) {
            spans.add(WordSpan(tokenStart + wordStart, token.length - wordStart))
        }
    }

    private fun isSpace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
}

/** Text shapes the spell checker accepts without a dictionary verdict. */
internal object SpellCheckerText {
    private val URL_PADDING = charArrayOf(
        '(', ')', '[', ']', '{', '}', '<', '>', '"', '\'', '«', '»', ',', ';', ':',
        '!', '?', '.', '…',
    )
    private val DOMAIN = Regex(
        """^(?:[\p{L}\p{N}-]+\.)+[\p{L}]{2,}(?::\d+)?(?:[/?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )

    fun isProtectedToken(token: CharSequence): Boolean {
        var hasWordCharacter = false
        var hasEmoji = false
        var offset = 0
        while (offset < token.length) {
            val codePoint = Character.codePointAt(token, offset)
            if (Character.isLetterOrDigit(codePoint)) hasWordCharacter = true
            if (isEmoji(codePoint)) hasEmoji = true
            offset += Character.charCount(codePoint)
        }
        if (!hasWordCharacter) return false
        return hasEmoji || looksLikeUrlOrEmail(token.toString())
    }

    fun looksLikeUrlOrEmail(text: String): Boolean {
        if (text.indexOf('@') < 0 && text.indexOf('.') < 0 &&
            text.indexOf(':') < 0 && text.indexOf('/') < 0
        ) {
            return false
        }
        val candidate = text.trim(URL_PADDING)
        if (candidate.isEmpty()) return false
        val at = candidate.indexOf('@')
        if (at > 0 && at < candidate.length - 1) return true
        val lower = candidate.lowercase(Locale.ROOT)
        if (lower.contains("://") || lower.startsWith("www.")) return true
        return DOMAIN.matches(lower)
    }

    fun isEmoji(codePoint: Int): Boolean =
        codePoint == 0x00a9 || codePoint == 0x00ae || codePoint == 0x203c ||
            codePoint == 0x2049 || codePoint == 0x2122 || codePoint == 0x2139 ||
            codePoint == 0x200d || codePoint == 0xfe0f || codePoint == 0x20e3 ||
            codePoint in 0x1f000..0x1faff ||
            codePoint in 0x2190..0x23ff ||
            codePoint in 0x2500..0x27bf ||
            codePoint in 0x2b00..0x2bff ||
            codePoint in 0xe0020..0xe007f

    private fun CharSequence.trim(chars: CharArray): String {
        var start = 0
        var end = length
        while (start < end && this[start] in chars) start++
        while (end > start && this[end - 1] in chars) end--
        return substring(start, end)
    }
}
