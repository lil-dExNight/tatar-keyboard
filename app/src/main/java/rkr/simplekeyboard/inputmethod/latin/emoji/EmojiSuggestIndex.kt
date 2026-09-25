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
 * The emoji-suggest table: an immutable, Android-free mapping of "(language, finished word) ->
 * emoji" built from `assets/emoji/emoji_suggest_v1.txt` (see `scripts/emoji_suggest_pack.py` and
 * the NOTICE beside the asset; the data is curated, Russian and Tatar only, mission 1 of
 * `docs/EMOJI-SUGGEST-PLAN.md`).
 *
 * The asset is data, not code: UTF-8, LF line endings, one entry per line, three tab-separated
 * fields — the language tag (`ru`/`tt`), the word in the exact normalized form
 * `TatarWordUtils.normalizeForLookup` produces (NFC, invariant-locale lowercase — the same form
 * the dictionary and the bigram table are keyed by), and the emoji sequence exactly as it appears
 * in `emoji_set_v1.txt`. Because field 3 comes from the panel asset, the table can only ever name
 * emoji the panel is able to draw; entries the running device has no glyph for are dropped on top
 * of that by [filterTo].
 *
 * A lookup is two hash-map reads and allocates nothing itself; the caller pays one
 * `normalizeForLookup` per completed word, the same normalize the NEXT_WORD bigram lookup already
 * performs. This is a suggestion, not a correction: a miss is silent and the strip simply shows
 * what it would have shown anyway.
 */
class EmojiSuggestIndex private constructor(
    private val byLanguage: Map<String, Map<String, String>>,
) {
    val entryCount: Int = byLanguage.values.sumOf { it.size }

    val isEmpty: Boolean get() = entryCount == 0

    /**
     * The emoji mapped to ([language], [normalizedWord]), or null. [normalizedWord] must already
     * be in the asset's form: NFC lowercase, exactly what `TatarWordUtils.normalizeForLookup`
     * returns. Allocation-free.
     */
    fun lookup(language: String, normalizedWord: String): String? =
        byLanguage[language]?.get(normalizedWord)

    /**
     * Every distinct emoji the table maps to. Allocates; used once at load time by the glyph-probe
     * filter, never on the suggestion path.
     */
    fun distinctEmoji(): Set<String> {
        val all = LinkedHashSet<String>()
        for (words in byLanguage.values) {
            all.addAll(words.values)
        }
        return all
    }

    /**
     * Returns a copy of this index holding only the emoji in [available] — the set the glyph probe
     * already accepted — so the strip can never offer an emoji the device cannot render.
     */
    fun filterTo(available: Set<String>): EmojiSuggestIndex {
        if (available.isEmpty()) return EMPTY
        val keptLanguages = LinkedHashMap<String, Map<String, String>>()
        for ((language, words) in byLanguage) {
            val keptWords = LinkedHashMap<String, String>()
            for ((word, emoji) in words) {
                if (emoji in available) keptWords[word] = emoji
            }
            if (keptWords.isNotEmpty()) keptLanguages[language] = keptWords
        }
        if (keptLanguages.isEmpty()) return EMPTY
        return EmojiSuggestIndex(keptLanguages)
    }

    companion object {
        /** Upper bound on the length of a single asset line; anything longer is junk. */
        private const val MAX_LINE_CHARS = 512

        /**
         * Hard cap on the parsed record count (2026-09-25 audit, F16, mirrors SentStartIndex):
         * the packer ships under 4k entries, so tens of thousands mean junk — parsing stops at
         * the cap instead of letting a corrupt asset grow the map without bound.
         */
        private const val MAX_RECORDS = 4096

        val EMPTY = EmojiSuggestIndex(emptyMap())

        /**
         * The asset language of a subtype identifier: `tt_RU` -> `tt`, `ru` -> `ru`. A subtype
         * whose language ships no table (English) maps to a tag that simply has no entries, so the
         * lookup misses silently and no special-casing is needed at the call site.
         */
        fun assetLanguageOf(subtypeId: String): String = subtypeId.substringBefore('_')

        /**
         * Fail-closed parser. A malformed line is dropped, a duplicate (language, word) key keeps
         * its first mapping, input past [MAX_RECORDS] records is truncated, and a fully
         * unreadable input yields [EMPTY]; no exception ever escapes.
         */
        @JvmStatic
        fun parse(text: String): EmojiSuggestIndex =
            try {
                parseOrThrow(text)
            } catch (e: Exception) {
                EMPTY
            }

        /** Reads [input] as UTF-8 and parses it; an unreadable stream yields [EMPTY]. */
        /**
         * C2 of `docs/ROADMAP-P8-PLAN.md`: parses AND filters in one pass. The load path used to
         * build the whole table, ask the glyph probe about every distinct emoji, then build a
         * SECOND filtered table — so its peak held two copies of ~0.5 MB for a table whose steady
         * state is one. With [emojiFilter] the rejected records are never inserted, and the peak
         * equals the steady state. The predicate is called once per DISTINCT emoji sequence
         * (memoized here), exactly as often as the old `distinctEmoji()` pass called it.
         */
        @JvmStatic
        fun parse(input: InputStream, emojiFilter: (String) -> Boolean): EmojiSuggestIndex {
            val text = try {
                input.reader(Charsets.UTF_8).readText()
            } catch (e: Exception) {
                return EMPTY
            }
            val verdicts = HashMap<String, Boolean>()
            return parseOrThrowFiltered(text) { emoji ->
                verdicts.getOrPut(emoji) { emojiFilter(emoji) }
            }
        }

        @JvmStatic
        fun parse(input: InputStream): EmojiSuggestIndex {
            val text = try {
                input.reader(Charsets.UTF_8).readText()
            } catch (e: Exception) {
                return EMPTY
            }
            return parse(text)
        }

        private fun parseOrThrow(text: String): EmojiSuggestIndex =
                parseOrThrowFiltered(text) { true }

        private fun parseOrThrowFiltered(
            text: String,
            keepEmoji: (String) -> Boolean,
        ): EmojiSuggestIndex {
            val byLanguage = LinkedHashMap<String, LinkedHashMap<String, String>>()
            var records = 0
            for (rawLine in text.split('\n')) {
                val line = if (rawLine.endsWith('\r')) rawLine.dropLast(1) else rawLine
                if (line.isEmpty() || line.length > MAX_LINE_CHARS) continue
                val firstTab = line.indexOf('\t')
                if (firstTab <= 0) continue
                val secondTab = line.indexOf('\t', firstTab + 1)
                if (secondTab <= firstTab + 1 || secondTab >= line.length - 1) continue
                if (line.indexOf('\t', secondTab + 1) >= 0) continue
                val emoji = line.substring(secondTab + 1)
                if (!keepEmoji(emoji)) continue
                val words = byLanguage.getOrPut(line.substring(0, firstTab)) { LinkedHashMap() }
                val word = line.substring(firstTab + 1, secondTab)
                if (words.containsKey(word)) continue
                words[word] = emoji
                if (++records >= MAX_RECORDS) break
            }
            if (byLanguage.isEmpty()) return EMPTY
            return EmojiSuggestIndex(byLanguage)
        }
    }
}
