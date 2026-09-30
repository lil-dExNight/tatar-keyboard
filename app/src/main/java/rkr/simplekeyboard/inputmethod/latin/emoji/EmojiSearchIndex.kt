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

import java.io.InputStream

/**
 * The emoji-search index: an immutable, Android-free table of "sequence -> name + keywords" built
 * from `assets/emoji/emoji_search_v1.txt` (see `scripts/emoji_search_pack.py` and the NOTICE beside
 * the asset; the data is CLDR annotations, Russian first and English after it).
 *
 * The asset is data, not code: UTF-8, LF line endings, one entry per line, three tab-separated
 * fields — the sequence exactly as it appears in `emoji_set_v1.txt`, the Russian short name, and
 * the space-separated union of the Russian and English keywords, all lowercased by the generator.
 * Because field 1 comes from the panel asset, the index can only ever name emoji the panel is able
 * to draw; entries the running device has no glyph for are dropped on top of that by [filterTo].
 *
 * Matching is word-prefix: a query matches an entry when some word of its name or of its keywords
 * starts with the query. Results are ordered by how directly they matched — the name itself first,
 * then a word of the name, then a keyword — and within a rank by the set's own order. There is no
 * regular expression and no per-query allocation after the first search: the three rank buckets are
 * reused [IntArray]s.
 */
class EmojiSearchIndex private constructor(
    private val sequences: Array<String>,
    private val names: Array<String>,
    private val keywords: Array<String>,
) {
    /** Rank buckets, reused across queries so a keystroke allocates nothing. */
    private val exactRank = IntArray(MAX_RESULTS)
    private val nameRank = IntArray(MAX_RESULTS)
    private val keywordRank = IntArray(MAX_RESULTS)
    private var exactCount = 0
    private var nameCount = 0
    private var keywordCount = 0

    /** The results of the last [search], in order. */
    private val results = IntArray(MAX_RESULTS)
    private var resultCount = 0

    val entryCount: Int get() = sequences.size

    val isEmpty: Boolean get() = sequences.isEmpty()

    /**
     * Runs [query] against the index and keeps at most [MAX_RESULTS] hits. A blank query clears the
     * results. Returns the number of results, also readable through [resultCount] and [resultAt].
     */
    fun search(query: String): Int {
        exactCount = 0
        nameCount = 0
        keywordCount = 0
        resultCount = 0
        val normalized = normalize(query)
        if (normalized.isEmpty()) return 0
        var index = 0
        while (index < sequences.size) {
            val name = names[index]
            when {
                name.startsWith(normalized) -> if (exactCount < MAX_RESULTS) exactRank[exactCount++] = index
                startsWordWith(name, normalized) -> if (nameCount < MAX_RESULTS) nameRank[nameCount++] = index
                startsWordWith(keywords[index], normalized) ->
                    if (keywordCount < MAX_RESULTS) keywordRank[keywordCount++] = index
            }
            if (exactCount >= MAX_RESULTS) break
            index++
        }
        var out = 0
        var bucket = 0
        while (bucket < 3 && out < MAX_RESULTS) {
            val source = when (bucket) {
                0 -> exactRank
                1 -> nameRank
                else -> keywordRank
            }
            val count = when (bucket) {
                0 -> exactCount
                1 -> nameCount
                else -> keywordCount
            }
            var i = 0
            while (i < count && out < MAX_RESULTS) {
                results[out++] = source[i]
                i++
            }
            bucket++
        }
        resultCount = out
        return out
    }

    fun resultCount(): Int = resultCount

    /** The sequence of the [position]-th result of the last [search]. */
    fun resultAt(position: Int): String =
        if (position in 0 until resultCount) sequences[results[position]] else ""

    /** The Russian short name of the [position]-th result; used by the accessibility delegate. */
    fun resultNameAt(position: Int): String =
        if (position in 0 until resultCount) names[results[position]] else ""

    /**
     * The Russian short name of [sequence] itself, or null when the index does not hold it. A
     * linear scan, allocation-free; used off the keystroke path, when a strip cell that shows an
     * emoji needs a spoken label for TalkBack.
     */
    fun nameOf(sequence: String): String? {
        var index = 0
        while (index < sequences.size) {
            if (sequences[index] == sequence) return names[index]
            index++
        }
        return null
    }

    /**
     * Returns a copy of this index holding only the sequences in [available] — the set the glyph
     * probe already accepted — so the search can never offer an emoji the device cannot render.
     */
    fun filterTo(available: Set<String>): EmojiSearchIndex {
        if (available.isEmpty()) return EMPTY
        val keptSequences = ArrayList<String>(sequences.size)
        val keptNames = ArrayList<String>(sequences.size)
        val keptKeywords = ArrayList<String>(sequences.size)
        var index = 0
        while (index < sequences.size) {
            if (sequences[index] in available) {
                keptSequences.add(sequences[index])
                keptNames.add(names[index])
                keptKeywords.add(keywords[index])
            }
            index++
        }
        if (keptSequences.isEmpty()) return EMPTY
        return EmojiSearchIndex(
            keptSequences.toTypedArray(),
            keptNames.toTypedArray(),
            keptKeywords.toTypedArray(),
        )
    }

    companion object {
        /** Upper bound on the results one query returns; the strip shows what fits and scrolls. */
        const val MAX_RESULTS = 60

        /** Upper bound on the length of a single asset line; anything longer is junk. */
        private const val MAX_LINE_CHARS = 512

        val EMPTY = EmojiSearchIndex(emptyArray(), emptyArray(), emptyArray())

        /** Lowercases and collapses a query the same way the generator normalized the asset. */
        fun normalize(query: String): String = query.trim().lowercase()

        /**
         * True when some word of [haystack] starts with [prefix]. Words are separated by single
         * spaces, which is what the generator writes. Allocation-free.
         */
        fun startsWordWith(haystack: String, prefix: String): Boolean {
            if (prefix.isEmpty() || prefix.length > haystack.length) return false
            var position = 0
            while (position >= 0) {
                if (haystack.startsWith(prefix, position)) return true
                position = haystack.indexOf(' ', position)
                if (position < 0) return false
                position++
                if (position >= haystack.length) return false
            }
            return false
        }

        /**
         * A malformed line is dropped, a duplicate sequence is dropped, and a fully unreadable
         * input yields [EMPTY]; no exception ever escapes.
         */
        @JvmStatic
        fun parse(text: String): EmojiSearchIndex =
            try {
                parseOrThrow(text)
            } catch (e: Exception) {
                EMPTY
            }

        /** Reads [input] as UTF-8 and parses it; an unreadable stream yields [EMPTY]. */
        @JvmStatic
        fun parse(input: InputStream): EmojiSearchIndex {
            val text = try {
                input.reader(Charsets.UTF_8).readText()
            } catch (e: Exception) {
                return EMPTY
            }
            return parse(text)
        }

        private fun parseOrThrow(text: String): EmojiSearchIndex {
            val sequences = ArrayList<String>()
            val names = ArrayList<String>()
            val keywords = ArrayList<String>()
            val seen = HashSet<String>()
            for (rawLine in text.split('\n')) {
                val line = if (rawLine.endsWith('\r')) rawLine.dropLast(1) else rawLine
                if (line.isEmpty() || line.length > MAX_LINE_CHARS) continue
                val firstTab = line.indexOf('\t')
                if (firstTab <= 0) continue
                val secondTab = line.indexOf('\t', firstTab + 1)
                if (secondTab <= firstTab + 1 || secondTab >= line.length - 1) continue
                val sequence = line.substring(0, firstTab)
                if (line.indexOf('\t', secondTab + 1) >= 0) continue
                if (!seen.add(sequence)) continue
                sequences.add(sequence)
                names.add(line.substring(firstTab + 1, secondTab))
                keywords.add(line.substring(secondTab + 1))
            }
            if (sequences.isEmpty()) return EMPTY
            return EmojiSearchIndex(
                sequences.toTypedArray(),
                names.toTypedArray(),
                keywords.toTypedArray(),
            )
        }
    }
}
