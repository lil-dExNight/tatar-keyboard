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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * Pure input filter for a word about to enter the personal dictionary. It applies the per-record
 * checks `TpersValidator` enforces, so an accepted word round-trips through the writer and the
 * validator.
 *
 * Filters and dedup use the normalized (NFC lowercase) form; the store saves the original
 * (as-entered) form. Content checks only: no I/O, no logging, no messages.
 */
internal object PersonalWordFilter {
    /** The NFC lowercase form used for sorting, dedup and search. */
    fun normalize(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /**
     * Returns the normalized form of [rawWord] if it is eligible for a subtype with [alphabet], or
     * null. Rejects mixed casing, a normalized length outside 3..24 code points, a combining mark
     * left after NFC, any code point outside the alphabet, and a raw form too long for the u8
     * `wordByteLength` field.
     */
    fun acceptedNormalizedForm(rawWord: String, alphabet: Set<Int>): String? {
        if (rawWord.isEmpty()) return null
        // RAW form: casing must not be MIXED (same rule as the validator's raw-form check).
        if (TatarWordUtils.classifyCasing(rawWord) == TatarWordUtils.PrefixCasing.MIXED) return null
        // The raw form is what goes on disk; its UTF-8 length must fit the u8 length field.
        if (rawWord.toByteArray(StandardCharsets.UTF_8).size > MAX_RAW_WORD_BYTES) return null

        val normalized = normalize(rawWord)
        val codePointCount = normalized.codePointCount(0, normalized.length)
        if (codePointCount < TpersFormat.MIN_WORD_CODE_POINTS ||
            codePointCount > TpersFormat.MAX_WORD_CODE_POINTS
        ) {
            return null
        }
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            if (isCombiningMark(codePoint)) return null
            if (codePoint !in alphabet) return null
            offset += Character.charCount(codePoint)
        }
        return normalized
    }

    private fun isCombiningMark(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    /** u8 length field: the raw form cannot exceed 255 UTF-8 bytes on disk. */
    private const val MAX_RAW_WORD_BYTES = 255
}
