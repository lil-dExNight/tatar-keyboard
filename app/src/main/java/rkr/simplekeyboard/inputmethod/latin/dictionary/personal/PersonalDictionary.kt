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
 * An immutable in-memory snapshot of one subtype's personal dictionary, with a read-only prefix
 * search. Three parallel arrays, all ordered by the normalized form ascending:
 * - [rawForms] — words as the user entered them (what is shown and inserted);
 * - [normalizedForms] — the NFC lowercase forms used for search, dedup and equality;
 * - [usageCounts] — per-word usage counters.
 *
 * A plain class without a generated `toString`, because it carries the user's words. Read-only:
 * writing and LRU eviction live in the store package.
 */
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
     * The usage counter at [index] (learned observations plus accepted suggestions), shown beside
     * the word on the personal dictionary screen.
     */
    fun usageCountAt(index: Int): Int = usageCounts[index]

    /**
     * Returns the raw forms whose normalized form starts with [normalizedPrefix], excluding a record
     * equal to the prefix: a personal "Гүзәл" is excluded when "гүзәл" is typed, "гүзәллек" is kept.
     * Ordered by usage count descending, then normalized form ascending (the personal order).
     */
    fun lookupRawForms(normalizedPrefix: String): List<String> =
        lookupCandidates(normalizedPrefix).map(PersonalCandidate::rawForm)

    /**
     * The same search as [lookupRawForms], but each match carries both forms: the raw one to show
     * and the normalized one to compare by (see [PersonalCandidate]).
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
     * The index of [normalized] in the normalized forms, or -1. A binary search: it runs on the UI
     * thread's long-press timer, where a linear scan over all entries is too slow.
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
