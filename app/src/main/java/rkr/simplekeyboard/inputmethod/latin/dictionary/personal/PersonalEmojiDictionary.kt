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

import java.nio.charset.StandardCharsets

/**
 * An immutable in-memory snapshot of one subtype's personal (word, emoji) entries, with a
 * read-only per-word lookup — the emoji sibling of [PersonalBigramDictionary]. Parallel arrays,
 * all ordered by the entry key ascending (the normalized word first, then the emoji cluster, both
 * compared as unsigned UTF-8 bytes — the same order [TpersemValidator] enforces on disk):
 * - [words] — normalized words (the lookup key; also what the settings list shows);
 * - [emojiClusters] — the raw emoji clusters (what is shown and inserted);
 * - [usageCounts] — accepted-suggestion counters (taps);
 * - [frequencyCounts] — clean co-usage observation counters.
 *
 * NOT a Kotlin `data class`: it carries the user's words, and a synthesised `toString` would print
 * them at the first interpolation. This class writes nothing to disk; the atomic writer and the
 * LRU eviction live in the store package.
 */
class PersonalEmojiDictionary private constructor(
    private val words: Array<String>,
    private val emojiClusters: Array<String>,
    private val usageCounts: IntArray,
    private val frequencyCounts: IntArray,
    val subtypeTag: String,
) {
    val size: Int
        get() = words.size

    val isEmpty: Boolean
        get() = words.isEmpty()

    /** The normalized word at [index], for the settings list and callers that hold an index. */
    fun wordAt(index: Int): String = words[index]

    /** The raw emoji cluster at [index]. */
    fun emojiAt(index: Int): String = emojiClusters[index]

    fun usageCountAt(index: Int): Int = usageCounts[index]
    fun frequencyCountAt(index: Int): Int = frequencyCounts[index]

    /**
     * The top emoji learned for [normalizedWord] — the one the strip's emoji tail cell offers — by
     * the pinned personal order: usage count descending, then frequency count descending, then the
     * array order within the word (emoji bytes ascending, the final tiebreak). The word is matched
     * on its NORMALIZED form only — the caller normalizes, exactly as the bigram-table lookup
     * already does for the static successors. A binary search bounds the contiguous word range, and
     * the scan touches only THIS word's entries.
     */
    fun emojiFor(normalizedWord: String): String? {
        if (isEmpty || normalizedWord.isEmpty()) return null
        val first = wordLowerBound(normalizedWord)
        if (first >= words.size || words[first] != normalizedWord) return null
        var best = first
        var index = first + 1
        while (index < words.size && words[index] == normalizedWord) {
            // Strict improvement keeps the EARLIEST index on a tie — the array order within one
            // word is emoji-ascending, which is the ranking's final tiebreak.
            if (usageCounts[index] > usageCounts[best] ||
                (usageCounts[index] == usageCounts[best] &&
                    frequencyCounts[index] > frequencyCounts[best])
            ) {
                best = index
            }
            index++
        }
        return emojiClusters[best]
    }

    /**
     * Every emoji learned for [normalizedWord], in the same pinned order [emojiFor] takes the top
     * of: usage descending, frequency descending, emoji bytes ascending. The mirror of
     * [PersonalBigramDictionary.successorsFor] for the word's emoji.
     */
    fun emojisFor(normalizedWord: String): List<String> {
        if (isEmpty || normalizedWord.isEmpty()) return emptyList()
        val first = wordLowerBound(normalizedWord)
        if (first >= words.size || words[first] != normalizedWord) return emptyList()
        var end = first + 1
        while (end < words.size && words[end] == normalizedWord) end++
        val matches = ArrayList<Int>(end - first)
        for (index in first until end) matches.add(index)
        // Stable within-(usage, frequency) order preserves the emoji-ascending array order as the
        // final tiebreak — the pinned order is usage desc, frequency desc, key asc.
        matches.sortWith(
            compareByDescending<Int> { usageCounts[it] }
                .thenByDescending { frequencyCounts[it] }
                .thenBy { it },
        )
        return matches.map { emojiClusters[it] }
    }

    /**
     * The index of the entry ([normalizedWord], [emoji]) in the parallel arrays, or -1. A BINARY
     * search over the entry key, for the same reason [PersonalDictionary]'s membership test is
     * one. The word half compares as a String — alphabet words are all BMP, where string order and
     * unsigned-byte order coincide; the emoji half compares as unsigned UTF-8 bytes, because a
     * variation selector (BMP, U+FE0E/U+FE0F) and a supplementary modifier inside a cluster order
     * differently in UTF-16 units than in bytes, and the array is sorted by bytes.
     */
    fun indexOfEntry(normalizedWord: String, emoji: String): Int {
        var low = 0
        var high = words.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val wordOrder = words[mid].compareTo(normalizedWord)
            val order = if (wordOrder != 0) {
                wordOrder
            } else {
                compareUnsignedBytes(emojiClusters[mid], emoji)
            }
            if (order < 0) low = mid + 1 else high = mid
        }
        return if (low < words.size &&
            words[low] == normalizedWord &&
            emojiClusters[low] == emoji
        ) {
            low
        } else {
            -1
        }
    }

    /** First index whose word is >= [normalizedWord]; a plain binary lower bound. */
    private fun wordLowerBound(normalizedWord: String): Int {
        var low = 0
        var high = words.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (words[mid] < normalizedWord) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        @JvmField
        val EMPTY = PersonalEmojiDictionary(
            emptyArray(), emptyArray(), IntArray(0), IntArray(0), "",
        )

        internal fun of(validated: ValidatedPersonalEmoji): PersonalEmojiDictionary {
            if (validated.entryCount == 0) return EMPTY
            return PersonalEmojiDictionary(
                validated.words.toTypedArray(),
                validated.emojiClusters.toTypedArray(),
                validated.usageCounts.copyOf(),
                validated.frequencyCounts.copyOf(),
                validated.subtypeTag,
            )
        }

        private fun compareUnsignedBytes(first: String, second: String): Int {
            val a = first.toByteArray(StandardCharsets.UTF_8)
            val b = second.toByteArray(StandardCharsets.UTF_8)
            val count = minOf(a.size, b.size)
            for (index in 0 until count) {
                val difference = (a[index].toInt() and 0xff) - (b[index].toInt() and 0xff)
                if (difference != 0) return difference
            }
            return a.size - b.size
        }
    }
}
