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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import java.io.InputStream

/**
 * The sentence-start table: an immutable, Android-free, frequency-ordered list of the words Tatar
 * sentences most often start with, read from `assets/dictionaries/tatar_sentstart_v1.txt` (built
 * by `scripts/sentstart_pack.py`; Leipzig-derived data, CC BY 4.0, see the NOTICE beside it).
 *
 * Format: UTF-8, LF line endings, a `#` comment header, then one `word<TAB>freq` row per record,
 * sorted by frequency descending, then word ascending. The file order is the ranking, so a lookup
 * is "the first [maxOut] rows". The packer guarantees every word is in the bundled Tatar
 * dictionary in its normalized form (NFC lowercase), the key form of the dictionary and bigram
 * table. There is no per-word lookup. A broken asset is [EMPTY], and the strip shows nothing at a
 * sentence start.
 */
class SentStartIndex private constructor(
    private val words: List<String>,
) : SentStartSource {
    val entryCount: Int get() = words.size

    val isEmpty: Boolean get() = words.isEmpty()

    /**
     * The top [maxOut] sentence-start words in ranking order. Called only when the strip is
     * painted, never in a lookup loop, so the small list view it may allocate is acceptable.
     */
    override fun topWords(maxOut: Int): List<String> =
        if (maxOut >= words.size) words else words.subList(0, maxOut)

    companion object {
        /** Upper bound on the length of a single asset line; anything longer is junk. */
        private const val MAX_LINE_CHARS = 512

        /** Hard cap on the record count; the packer ships the top ~64, so thousands mean junk. */
        private const val MAX_RECORDS = 4096

        val EMPTY = SentStartIndex(emptyList())

        /**
         * Fail-closed parser. Comment (`#`) and blank lines are skipped; a malformed line is
         * dropped, a duplicate word keeps its first (higher-ranked) row, and a fully unreadable
         * input yields [EMPTY]; no exception ever escapes.
         */
        @JvmStatic
        fun parse(text: String): SentStartIndex =
            try {
                parseOrThrow(text)
            } catch (e: Exception) {
                EMPTY
            }

        /** Reads [input] as UTF-8 and parses it; an unreadable stream yields [EMPTY]. */
        @JvmStatic
        fun parse(input: InputStream): SentStartIndex {
            val text = try {
                input.reader(Charsets.UTF_8).readText()
            } catch (e: Exception) {
                return EMPTY
            }
            return parse(text)
        }

        private fun parseOrThrow(text: String): SentStartIndex {
            val words = ArrayList<String>()
            val seen = HashSet<String>()
            for (rawLine in text.split('\n')) {
                val line = if (rawLine.endsWith('\r')) rawLine.dropLast(1) else rawLine
                if (line.isEmpty() || line.length > MAX_LINE_CHARS) continue
                if (line.startsWith('#')) continue
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab >= line.length - 1) continue
                if (line.indexOf('\t', tab + 1) >= 0) continue
                val word = line.substring(0, tab)
                // The frequency is ballast at runtime (the order already ranks), but a row whose
                // frequency is not a positive number is not a row the packer wrote.
                val frequency = line.substring(tab + 1).toIntOrNull() ?: continue
                if (frequency <= 0) continue
                if (!seen.add(word)) continue
                words.add(word)
                if (words.size >= MAX_RECORDS) break
            }
            if (words.isEmpty()) return EMPTY
            return SentStartIndex(words)
        }
    }
}

/**
 * What the suggestion strip needs from the sentence-start feature: the top words of the table.
 * Implementations must be immutable and safe to call on the UI thread.
 */
fun interface SentStartSource {
    /** The top [maxOut] sentence-start words in ranking order; empty when unusable. */
    fun topWords(maxOut: Int): List<String>
}
