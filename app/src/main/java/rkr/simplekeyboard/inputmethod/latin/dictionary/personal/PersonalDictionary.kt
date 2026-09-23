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

import androidx.annotation.Keep

/**
 * An immutable in-memory snapshot of one subtype's personal dictionary, with a read-only prefix
 * search. Three parallel arrays, all ordered by the normalized form ascending:
 * - [rawForms] — words as the user entered them (what is shown and inserted);
 * - [normalizedForms] — the NFC lowercase forms used for search, dedup and equality;
 * - [usageCounts] — per-word usage counters.
 *
 * NOT a Kotlin `data class`: it carries the user's words, and a synthesised `toString` would print
 * them at the first interpolation. This class writes nothing to disk — E4a-1 is a read-only path;
 * atomic writing and LRU eviction are E4a-2.
 */
@Keep
class PersonalDictionary private constructor(
    private val rawForms: Array<String>,
    private val normalizedForms: Array<String>,
    private val usageCounts: IntArray,
    val subtypeTag: String,
) {
    val size: Int
        get() = rawForms.size

    val isEmpty: Boolean
        get() = rawForms.isEmpty()

    /** The raw (as-entered) form at [index], for tests and callers that already hold an index. */
    fun rawFormAt(index: Int): String = rawForms[index]

    /** The normalized (NFC lowercase) form at [index]. */
    fun normalizedFormAt(index: Int): String = normalizedForms[index]

    /**
     * The usage counter at [index] — how often the word earned its place (learned observations
     * plus accepted suggestions). The "Personal dictionary" screen shows it beside the word (U7
     * of Phase 2, docs/ROADMAP-P2.md); the lookup path orders by it and never needs to read it
     * one at a time.
     */
    fun usageCountAt(index: Int): Int = usageCounts[index]

    /**
     * Returns the raw forms whose NORMALIZED form starts with [normalizedPrefix], EXCLUDING any
     * record whose normalized form is exactly equal to the prefix. Equality is on the normalized
     * form, never on raw bytes ("Контракт текста" edit 3): a personal "Гүзәл" is excluded when the
     * user has already typed "гүзәл", but "гүзәллек" is kept.
     *
     * Ordered by usage count descending, then by normalized form ascending (the personal order).
     * The final three-class merge with the dictionary asset and the exact-vs-fuzzy ranking are
     * E4b; this is the read-only building block only.
     */
    fun lookupRawForms(normalizedPrefix: String): List<String> =
        lookupCandidates(normalizedPrefix).map(PersonalCandidate::rawForm)

    /**
     * The same search as [lookupRawForms], but each match carries BOTH forms: the raw one to show
     * and the normalized one to compare by. The three-class merge (E4b) needs both — duplicate
     * detection against dictionary candidates is defined on the normalized form, while the cell
     * shows the form the user saved.
     */
    fun lookupCandidates(normalizedPrefix: String): List<PersonalCandidate> {
        if (isEmpty || normalizedPrefix.isEmpty()) return emptyList()
        val start = lowerBound(normalizedPrefix)
        var index = start
        val matches = ArrayList<Int>()
        while (index < normalizedForms.size && normalizedForms[index].startsWith(normalizedPrefix)) {
            if (normalizedForms[index] != normalizedPrefix) matches.add(index)
            index++
        }
        if (matches.isEmpty()) return emptyList()
        // Stable within-usage order preserves the normalized-ascending array order as the tiebreak.
        matches.sortWith(
            compareByDescending<Int> { usageCounts[it] }.thenBy { it },
        )
        return matches.map { PersonalCandidate(rawForms[it], normalizedForms[it]) }
    }

    /**
     * The index of [normalized] in the parallel array of normalized forms, or -1. A BINARY search,
     * as the E4d contract requires — the array is sorted by that very form, and a linear scan over
     * up to 2 000 entries would run on the UI thread's long-press timer.
     */
    fun indexOfNormalized(normalized: String): Int {
        val index = lowerBound(normalized)
        return if (index < normalizedForms.size && normalizedForms[index] == normalized) index else -1
    }

    /** First index whose normalized form is >= [key]; a plain binary lower bound. */
    private fun lowerBound(key: String): Int {
        var low = 0
        var high = normalizedForms.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (normalizedForms[mid] < key) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        @JvmField
        val EMPTY = PersonalDictionary(emptyArray(), emptyArray(), IntArray(0), "")

        internal fun of(validated: ValidatedPersonalDictionary): PersonalDictionary {
            if (validated.entryCount == 0) return EMPTY
            return PersonalDictionary(
                validated.rawForms.toTypedArray(),
                validated.normalizedForms.toTypedArray(),
                validated.usageCounts.copyOf(),
                validated.subtypeTag,
            )
        }
    }
}
