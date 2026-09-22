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
 * Ownership of the "boolean eligible -> active subtype identifier" seam moved to E4a-1 after phase
 * D2 was cancelled (see PROPOSALS.md, "Сквозное решение: всё новое ключуется активным subtype",
 * amendment of 2026-07-27). Everything the personal dictionary introduces is keyed by a subtype
 * identifier from the very first version — the on-disk path, the file name, the subtypeTag inside
 * the file, the in-memory snapshot and the alphabet filter — and there is no shared "default"
 * store. For a subtype with no declared alphabet the feature is off entirely.
 *
 * The literal `"tt_RU"` used to live twice, in `SuggestionsController.SUBTYPE_ID` and in
 * `LatinIME.isTatarSuggestionsEligible()`, kept in sync by hand; then [alphabetFor] carried a
 * second hand-maintained `when` mirroring the artifact registry's language list. T5 (ROADMAP
 * Phase 1, docs/ROADMAP-P1.md) closed that mirror: this file now holds only DATA (the tag
 * constants, the alphabet sets), and every PREDICATE — [alphabetFor] here, the suggestions
 * eligibility in LatinIME, the settings screens — resolves through the ONE registry,
 * `DictionaryArtifactSpec`: each entry carries its personal alphabet, and [alphabetFor] is a
 * pure registry lookup. A source-contract test (`PersonalSubtypeRegistryContractTest`) pins the
 * agreement so a second hand-maintained list cannot drift back in.
 *
 * That the format carried a language tag from version 1 is what made the second language free:
 * [RUSSIAN] simply declares its own alphabet and gets its own store, its own file
 * (`personal-ru-s1-f1.tpers`) and its own snapshot, with no migration and without a single Tatar
 * word moving. Personal dictionaries are per language on purpose, and there is still no shared
 * "default" store: a name the user saved while writing Tatar has no business appearing in the band
 * while they write Russian, and the alphabet filter of each store says so by construction.
 */
object PersonalSubtypes {
    /** The Tatar Cyrillic subtype identifier ([rkr.simplekeyboard.inputmethod.latin.Subtype.getLocale]). */
    const val TATAR_RU = "tt_RU"

    /**
     * The Russian subtype identifier — plain `"ru"`, exactly what
     * `SubtypeLocaleUtils.LOCALE_RUSSIAN` builds the subtype with. NOT `"ru_RU"`: the subtype the
     * user actually gets carries the bare language tag, and a store keyed by anything else would
     * simply never be reached.
     */
    const val RUSSIAN = "ru"

    /**
     * The lowercase Tatar Cyrillic alphabet, identical to the code-point set enforced by
     * `TdictValidator.TATAR_ALPHABET` for the packed dictionary asset. Alphabet checks run on the
     * NORMALIZED (NFC lowercase) form of a word, so only lowercase letters are listed: the raw form
     * "Гүзәл" is accepted because its normalized form "гүзәл" is spelled entirely from this set.
     */
    val TATAR_RU_ALPHABET: Set<Int> =
        "аәбвгдеёжҗзийклмнңоөпрстуүфхһцчшщъыьэюя".codePoints().toArray().toSet()

    /**
     * The lowercase Russian alphabet, all 33 letters, identical to the set
     * `scripts/dictionary_coverage.py::RUSSIAN_ALPHABET` filters the packed Russian asset by.
     *
     * «ё» is a letter of its own here, not folded into «е». Folding would be a decision about the
     * user's own spelling: someone who reaches for the long press to write «ещё» means «ещё», and
     * a store that silently kept «еще» would hand that spelling back to them forever.
     */
    val RUSSIAN_ALPHABET: Set<Int> =
        "абвгдеёжзийклмнопрстуфхцчшщъыьэюя".codePoints().toArray().toSet()

    /**
     * The alphabet for [subtypeId], or null when the subtype declares none. A null alphabet means
     * the personal dictionary is off for that subtype: there is no fallback to another language.
     *
     * T5 (docs/ROADMAP-P1.md): this is a PURE LOOKUP in the artifact registry
     * (`DictionaryArtifactSpec`) — the ONE list of shipped languages. The alphabets above are
     * data the registry entries reference; which subtype maps to which language is decided there,
     * never by a second enumeration here, so adding a language is a one-entry change.
     */
    fun alphabetFor(subtypeId: String): Set<Int>? =
        DictionaryArtifactSpec.forSubtype(subtypeId)?.personalAlphabet

    /** True when [subtypeId] declares an alphabet, i.e. the personal dictionary may run for it. */
    fun isSupported(subtypeId: String): Boolean = alphabetFor(subtypeId) != null
}
