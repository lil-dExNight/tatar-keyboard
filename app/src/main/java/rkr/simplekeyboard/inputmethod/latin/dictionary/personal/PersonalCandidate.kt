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

/**
 * One personal-dictionary match: the form to SHOW ([rawForm], as the user saved it) together with
 * the form to COMPARE by ([normalizedForm]).
 *
 * Both are needed by the three-class merge (E4b): the display form carries the user's own casing,
 * while duplicate detection against dictionary candidates and the exact-word exclusion are defined
 * on the normalized form ("Контракт текста", правка 3 из E4a-1).
 *
 * NOT a Kotlin `data class`, and [toString] is overridden: this type carries the user's word, and a
 * synthesised `toString` would print it at the first interpolation.
 */
class PersonalCandidate(val rawForm: String, val normalizedForm: String) {
    /** Deliberately says nothing: the user's word must never reach a log or an exception message. */
    override fun toString(): String = "PersonalCandidate"
}

/**
 * The personal side of the merge, as seen by the engine. Kept as a seam so the composite computer
 * is testable without a file, a snapshot or Android.
 */
fun interface PersonalCandidateSource {
    /**
     * Matches for [normalizedPrefix] in the personal order — usage count descending, then
     * normalized form ascending — with the record equal to the prefix already excluded.
     */
    fun candidatesFor(normalizedPrefix: String): List<PersonalCandidate>

    /**
     * True when this source cannot produce anything at all (feature off, locked device, empty
     * dictionary). The merge checks it FIRST so a disabled personal dictionary costs the lookup path
     * nothing — not even decoding the prefix bytes into a String.
     */
    fun isEmpty(): Boolean = false

    /**
     * True when [normalizedWord] is ITSELF a personal record — the membership test D3 needs to leave
     * the user's own words alone. Distinct from [candidatesFor], which deliberately EXCLUDES the
     * record equal to the prefix because it must never suggest what is already typed.
     *
     * Defaults to false so a source written before D3 keeps compiling and simply vetoes nothing.
     */
    fun containsNormalized(normalizedWord: String): Boolean = false

    /**
     * The immutable snapshot the GLIDE decode side indexes (docs/GLIDE-PERSONAL.md): the whole
     * personal dictionary, read once per decoder rebuild on the engine worker. A glide decode
     * never walks prefix matches — it needs the entries themselves, and only when the snapshot's
     * identity changes, so the per-gesture cost of an unchanged personal dictionary is one
     * reference read.
     *
     * Defaults to [PersonalDictionary.EMPTY] so a source written before the glide integration
     * keeps compiling and adds no glide candidates.
     */
    fun glideSnapshot(): PersonalDictionary = PersonalDictionary.EMPTY

    companion object {
        /** The source used whenever the personal dictionary is off or unavailable. */
        @JvmField
        val EMPTY: PersonalCandidateSource = object : PersonalCandidateSource {
            override fun candidatesFor(normalizedPrefix: String): List<PersonalCandidate> =
                emptyList()

            override fun isEmpty(): Boolean = true
        }
    }
}

/**
 * The production source: reads whatever immutable snapshot [snapshot] currently returns. The
 * snapshot itself is published by the personal store on its own worker, so this only ever reads a
 * `@Volatile` reference — no I/O, no lock, no checksum on the engine thread.
 */
class SnapshotPersonalCandidateSource(
    private val snapshot: () -> PersonalDictionary,
) : PersonalCandidateSource {
    override fun candidatesFor(normalizedPrefix: String): List<PersonalCandidate> =
        snapshot().lookupCandidates(normalizedPrefix)

    override fun isEmpty(): Boolean = snapshot().isEmpty

    override fun containsNormalized(normalizedWord: String): Boolean =
        snapshot().indexOfNormalized(normalizedWord) >= 0

    override fun glideSnapshot(): PersonalDictionary = snapshot()
}
