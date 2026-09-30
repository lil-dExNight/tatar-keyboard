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

import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec

/**
 * The subtype identifier constants and the per-language alphabets the personal dictionary is
 * keyed and filtered by.
 *
 * Everything the personal dictionary stores is keyed by a subtype identifier: the on-disk path,
 * the file name, the subtype tag inside the file, the snapshot and the alphabet filter. Each
 * language has its own store and there is no shared default, so words saved in one language never
 * appear in another. This file holds only data; which subtype has which alphabet is decided by the
 * `DictionaryArtifactSpec` registry, and a subtype without an alphabet has the feature off.
 */
object PersonalSubtypes {
    /** The Tatar Cyrillic subtype identifier ([rkr.simplekeyboard.inputmethod.latin.Subtype.getLocale]). */
    const val TATAR_RU = "tt_RU"

    /**
     * The Russian subtype identifier: plain `"ru"`, as `SubtypeLocaleUtils.LOCALE_RUSSIAN` builds
     * it. Not `"ru_RU"`: a store keyed by anything else would never be reached.
     */
    const val RUSSIAN = "ru"

    /**
     * The lowercase Tatar Cyrillic alphabet, identical to `TdictValidator.TATAR_ALPHABET`. Checks
     * run on the normalized (NFC lowercase) form, so "Гүзәл" passes as "гүзәл".
     */
    val TATAR_RU_ALPHABET: Set<Int> =
        "аәбвгдеёжҗзийклмнңоөпрстуүфхһцчшщъыьэюя".codePoints().toArray().toSet()

    /**
     * The lowercase Russian alphabet, identical to `scripts/dictionary_coverage.py::RUSSIAN_ALPHABET`.
     * «ё» is its own letter, not folded into «е», so the store keeps the user's spelling.
     */
    val RUSSIAN_ALPHABET: Set<Int> =
        "абвгдеёжзийклмнопрстуфхцчшщъыьэюя".codePoints().toArray().toSet()

    /**
     * The alphabet for [subtypeId], or null when the subtype declares none; then the personal
     * dictionary is off for it, with no fallback. A plain lookup in `DictionaryArtifactSpec`.
     */
    fun alphabetFor(subtypeId: String): Set<Int>? =
        DictionaryArtifactSpec.forSubtype(subtypeId)?.personalAlphabet

    /** True when [subtypeId] declares an alphabet, i.e. the personal dictionary may run for it. */
    fun isSupported(subtypeId: String): Boolean = alphabetFor(subtypeId) != null
}
