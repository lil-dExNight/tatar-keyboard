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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personal

/** Shared content checks for normalized personal-store words. */
internal object PersonalWordShapes {
    /**
     * Returns a constant rejection reason, or null when [normalized] has [minCodePoints] through
     * [maxCodePoints] code points, contains only [alphabet] letters, and uses U+002D only between
     * two letters. A null alphabet admits any Unicode letter for refused corrections. Combining
     * marks are rejected unless [allowCombiningMarks] keeps the refused-correction shape, where a
     * mark may only continue a letter. No punctuation is normalized or folded.
     */
    fun rejectionReason(
        normalized: String,
        alphabet: Set<Int>?,
        minCodePoints: Int,
        maxCodePoints: Int,
        allowCombiningMarks: Boolean = false,
    ): String? {
        val codePointCount = normalized.codePointCount(0, normalized.length)
        if (codePointCount < minCodePoints || codePointCount > maxCodePoints) {
            return "normalized word length out of bounds"
        }

        var offset = 0
        var previousWasLetter = false
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            if (codePoint == HYPHEN) {
                val nextOffset = offset + 1
                if (!previousWasLetter || nextOffset >= normalized.length) {
                    return "hyphen must be between two letters"
                }
                val next = normalized.codePointAt(nextOffset)
                if (!isAlphabetLetter(next, alphabet)) {
                    return "hyphen must be between two letters"
                }
                previousWasLetter = false
            } else if (isCombiningMark(codePoint)) {
                if (!allowCombiningMarks || !previousWasLetter) {
                    return "combining mark remains after NFC"
                }
            } else {
                if (!isAlphabetLetter(codePoint, alphabet)) {
                    return "word is outside the subtype alphabet"
                }
                previousWasLetter = true
            }
            offset += Character.charCount(codePoint)
        }
        return null
    }

    private fun isAlphabetLetter(codePoint: Int, alphabet: Set<Int>?): Boolean =
        if (alphabet == null) Character.isLetter(codePoint) else codePoint in alphabet

    private fun isCombiningMark(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    private const val HYPHEN = '-'.code
}
