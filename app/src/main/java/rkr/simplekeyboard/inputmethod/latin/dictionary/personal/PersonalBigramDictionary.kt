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
 * An immutable in-memory snapshot of one subtype's learned word pairs, with a read-only
 * per-context lookup. Parallel arrays, all ordered by the pair key ascending (normalized context,
 * then normalized successor, as unsigned UTF-8 bytes; the order [TpersbValidator] enforces):
 * - [contexts] — normalized context words (the lookup key; never displayed);
 * - [successorRawForms] — successors as the user typed them (what is shown and inserted);
 * - [successorNormalizedForms] — the parallel normalized forms used for ordering and dedup;
 * - [usageCounts] — accepted-prediction counters (taps);
 * - [frequencyCounts] — clean typed-observation counters.
 *
 * See [PersonalDictionary] for the privacy and read-only rules.
 */
class PersonalBigramDictionary private constructor(
    private val contexts: Array<String>,
    private val successorRawForms: Array<String>,
    private val successorNormalizedForms: Array<String>,
    private val usageCounts: IntArray,
    private val frequencyCounts: IntArray,
    val subtypeTag: String,
) {
    val size: Int
        get() = contexts.size

    val isEmpty: Boolean
        get() = contexts.isEmpty()

    /** The normalized context form at [index], for tests and callers that already hold an index. */
    fun contextAt(index: Int): String = contexts[index]

    /** The raw (as-typed) successor form at [index]. */
    fun successorRawFormAt(index: Int): String = successorRawForms[index]

    /** The normalized successor form at [index]. */
    fun successorNormalizedFormAt(index: Int): String = successorNormalizedForms[index]

    fun usageCountAt(index: Int): Int = usageCounts[index]
    fun frequencyCountAt(index: Int): Int = frequencyCounts[index]

    /**
     * The successors learned for [normalizedContext] (already normalized by the caller), ordered by
     * usage descending, frequency descending, then normalized form ascending (array order). A binary
     * search bounds the context range; only that range is scanned.
     */
    fun successorsFor(normalizedContext: String): List<PersonalCandidate> {
        if (isEmpty || normalizedContext.isEmpty()) return emptyList()
        val first = contextLowerBound(normalizedContext)
        if (first >= contexts.size || contexts[first] != normalizedContext) return emptyList()
        var end = first + 1
        while (end < contexts.size && contexts[end] == normalizedContext) end++
        val matches = ArrayList<Int>(end - first)
        for (index in first until end) matches.add(index)
        // Stable within-(usage, frequency) order preserves the successor-ascending array order as
        // the final tiebreak.
        matches.sortWith(
            compareByDescending<Int> { usageCounts[it] }
                .thenByDescending { frequencyCounts[it] }
                .thenBy { it },
        )
        return matches.map {
            PersonalCandidate(successorRawForms[it], successorNormalizedForms[it])
        }
    }

    /**
     * The index of the pair ([normalizedContext], [normalizedSuccessor]), or -1. A binary search,
     * see [PersonalDictionary.indexOfNormalized].
     */
    fun indexOfPair(normalizedContext: String, normalizedSuccessor: String): Int {
        var low = 0
        var high = contexts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val contextOrder = contexts[mid].compareTo(normalizedContext)
            val order = if (contextOrder != 0) {
                contextOrder
            } else {
                successorNormalizedForms[mid].compareTo(normalizedSuccessor)
            }
            if (order < 0) low = mid + 1 else high = mid
        }
        return if (low < contexts.size &&
            contexts[low] == normalizedContext &&
            successorNormalizedForms[low] == normalizedSuccessor
        ) {
            low
        } else {
            -1
        }
    }

    /** First index whose context form is >= [normalizedContext]; a plain binary lower bound. */
    private fun contextLowerBound(normalizedContext: String): Int {
        var low = 0
        var high = contexts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (contexts[mid] < normalizedContext) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        @JvmField
        val EMPTY = PersonalBigramDictionary(
            emptyArray(), emptyArray(), emptyArray(), IntArray(0), IntArray(0), "",
        )

        internal fun of(validated: ValidatedPersonalBigrams): PersonalBigramDictionary {
            if (validated.pairCount == 0) return EMPTY
            return PersonalBigramDictionary(
                validated.contexts.toTypedArray(),
                validated.successorRawForms.toTypedArray(),
                validated.successorNormalizedForms.toTypedArray(),
                validated.usageCounts.copyOf(),
                validated.frequencyCounts.copyOf(),
                validated.subtypeTag,
            )
        }
    }
}
