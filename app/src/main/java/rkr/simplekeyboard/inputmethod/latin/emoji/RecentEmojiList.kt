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

package rkr.simplekeyboard.inputmethod.latin.emoji

/**
 * Pure, Android-free MRU model of the "recent emoji" list, together with its fail-closed codec.
 *
 * Rules:
 *  - most-recently used first: the last inserted sequence is always at index 0;
 *  - re-inserting a sequence already in the list moves it to the front and never duplicates it;
 *  - the list holds at most [MAX_ENTRIES] entries; a 25th insertion evicts the last (oldest) one;
 *  - the summed length of every entry never exceeds [MAX_CHARS] `char`; this second, independent
 *    safeguard truncates the tail so a future, larger emoji set cannot silently bloat the file.
 *
 * The stored form is the entries joined by [SEPARATOR] (U+001F, an ASCII unit separator that can
 * never appear inside an emoji sequence). Reading is fail-closed: any malformed, duplicated,
 * over-length, over-count or over-budget content collapses to the empty list rather than throwing.
 *
 * Every rule here is exercised on the plain JVM without a device.
 */
internal class RecentEmojiList private constructor(val entries: List<String>) {

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Returns the list after [sequence] was used. The sequence moves to the front, is never
     * duplicated, and the result is capped at [MAX_ENTRIES] entries and [MAX_CHARS] total `char`.
     * Using an empty sequence is a no-op.
     */
    fun used(sequence: String): RecentEmojiList {
        if (sequence.isEmpty()) return this
        val next = ArrayList<String>(minOf(entries.size + 1, MAX_ENTRIES))
        next.add(sequence)
        var total = sequence.length
        for (existing in entries) {
            if (existing == sequence) continue
            if (next.size >= MAX_ENTRIES) break
            if (total + existing.length > MAX_CHARS) break
            next.add(existing)
            total += existing.length
        }
        return RecentEmojiList(next)
    }

    /** Drops entries absent from [available] while preserving MRU order. */
    fun filteredTo(available: Set<String>): RecentEmojiList {
        if (entries.isEmpty()) return this
        var allPresent = true
        for (entry in entries) {
            if (!available.contains(entry)) {
                allPresent = false
                break
            }
        }
        if (allPresent) return this
        val kept = ArrayList<String>(entries.size)
        for (entry in entries) {
            if (available.contains(entry)) kept.add(entry)
        }
        return if (kept.isEmpty()) EMPTY else RecentEmojiList(kept)
    }

    /** Serializes to the on-disk form: entries joined by [SEPARATOR]. */
    fun serialize(): String = entries.joinToString(SEPARATOR.toString())

    override fun equals(other: Any?): Boolean = other is RecentEmojiList && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "RecentEmojiList(${entries.size})"

    companion object {
        const val MAX_ENTRIES = 24
        const val MAX_CHARS = 512
        const val SEPARATOR = '\u001F'

        /** A single sequence longer than this is junk; the base set's longest is 3 `char`. */
        private const val MAX_SEQUENCE_CHARS = 64

        val EMPTY = RecentEmojiList(emptyList())

        /** Builds a list from [entries] in MRU order (first argument ends up at the front). */
        fun of(vararg entries: String): RecentEmojiList {
            var list = EMPTY
            for (index in entries.indices.reversed()) {
                list = list.used(entries[index])
            }
            return list
        }

        /**
         * Parses the stored form, fail-closed. A null or empty medium yields [EMPTY]; any malformed
         * token, duplicate, over-length token, over-count list or over-budget content also yields
         * [EMPTY]. No exception escapes.
         */
        fun deserialize(raw: String?): RecentEmojiList {
            if (raw.isNullOrEmpty()) return EMPTY
            return try {
                val parts = raw.split(SEPARATOR)
                if (parts.size > MAX_ENTRIES) return EMPTY
                val seen = HashSet<String>(parts.size * 2)
                var total = 0
                for (part in parts) {
                    if (!isValidSequence(part)) return EMPTY
                    if (!seen.add(part)) return EMPTY
                    total += part.length
                    if (total > MAX_CHARS) return EMPTY
                }
                RecentEmojiList(parts.toList())
            } catch (_: Throwable) {
                EMPTY
            }
        }

        private fun isValidSequence(sequence: String): Boolean {
            if (sequence.isEmpty() || sequence.length > MAX_SEQUENCE_CHARS) return false
            for (index in sequence.indices) {
                if (Character.isWhitespace(sequence[index])) return false
            }
            return true
        }
    }
}
