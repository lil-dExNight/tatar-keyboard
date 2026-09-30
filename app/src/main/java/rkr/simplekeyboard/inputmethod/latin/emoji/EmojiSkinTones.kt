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
 * Which of the panel's neutral cells accept a skin-tone modifier, and how to compose the toned
 * form. Built from `assets/emoji/emoji_skin_v1.txt` (see `scripts/emoji_skin_pack.py`; the data
 * comes from the same Unicode `emoji-test.txt` as the panel asset).
 *
 * The panel asset carries no toned sequences: the grid shows one neutral cell per emoji, and the
 * five tones are behind a long press.
 *
 * The asset is data, not code: UTF-8, LF line endings, one base per line, three tab-separated
 * fields — the panel sequence, and the prefix and suffix a modifier slots between. The split
 * matters because a tone REPLACES U+FE0F: the grid draws `U+1F590 U+FE0F` but the toned form is
 * `U+1F590 U+1F3FB`, so the prefix is the sequence without its variation selector.
 *
 * [variantAt] indexes variants from 0: variant 0 is the neutral sequence exactly as the grid draws
 * it, variants 1..5 are the five tones in Unicode order. Composition allocates, so callers do it
 * once per pick and per popup open, never while drawing.
 */
class EmojiSkinTones private constructor(
    private val bases: HashMap<String, Int>,
    private val prefixes: Array<String>,
    private val suffixes: Array<String>,
) {
    val baseCount: Int get() = prefixes.size

    val isEmpty: Boolean get() = prefixes.isEmpty()

    /** True when [sequence] is a neutral cell that accepts a tone modifier. */
    fun hasTones(sequence: String): Boolean = bases.containsKey(sequence)

    /**
     * Variant [variant] of [sequence]: 0 is the neutral sequence itself, 1..[TONE_COUNT] are the
     * tones. Returns [sequence] unchanged for a sequence with no tones or an out-of-range variant,
     * so a caller can never produce a sequence Unicode does not define.
     */
    fun variantAt(sequence: String, variant: Int): String {
        if (variant <= 0) return sequence
        if (variant > TONE_COUNT) return sequence
        val index = bases[sequence] ?: return sequence
        val builder = StringBuilder(prefixes[index].length + 2 + suffixes[index].length)
        builder.append(prefixes[index])
        builder.appendCodePoint(FIRST_MODIFIER + (variant - 1))
        builder.append(suffixes[index])
        return builder.toString()
    }

    /** Every toned form of every base; used to widen the set the recents are allowed to record. */
    fun allTonedSequences(): Set<String> {
        val out = HashSet<String>(prefixes.size * TONE_COUNT * 2)
        for (base in bases.keys) {
            for (variant in 1..TONE_COUNT) {
                out.add(variantAt(base, variant))
            }
        }
        return out
    }

    companion object {
        /** The five Unicode skin-tone modifiers, U+1F3FB..U+1F3FF. */
        const val TONE_COUNT = 5

        /** Variants offered on a long press: the neutral cell plus the five tones. */
        const val VARIANT_COUNT = TONE_COUNT + 1

        private const val FIRST_MODIFIER = 0x1F3FB

        /** Upper bound on the length of a single asset line; anything longer is junk. */
        private const val MAX_LINE_CHARS = 64

        val EMPTY = EmojiSkinTones(HashMap(), emptyArray(), emptyArray())

        /**
         * A malformed line is dropped, a duplicate base is dropped, and a fully unreadable input
         * yields [EMPTY]; no exception ever escapes.
         */
        @JvmStatic
        fun parse(text: String): EmojiSkinTones =
            try {
                parseOrThrow(text)
            } catch (e: Exception) {
                EMPTY
            }

        /** Reads [input] as UTF-8 and parses it; an unreadable stream yields [EMPTY]. */
        @JvmStatic
        fun parse(input: InputStream): EmojiSkinTones {
            val text = try {
                input.reader(Charsets.UTF_8).readText()
            } catch (e: Exception) {
                return EMPTY
            }
            return parse(text)
        }

        private fun parseOrThrow(text: String): EmojiSkinTones {
            val bases = HashMap<String, Int>()
            val prefixes = ArrayList<String>()
            val suffixes = ArrayList<String>()
            for (rawLine in text.split('\n')) {
                val line = if (rawLine.endsWith('\r')) rawLine.dropLast(1) else rawLine
                if (line.isEmpty() || line.length > MAX_LINE_CHARS) continue
                val firstTab = line.indexOf('\t')
                if (firstTab <= 0) continue
                val secondTab = line.indexOf('\t', firstTab + 1)
                if (secondTab <= firstTab) continue
                if (line.indexOf('\t', secondTab + 1) >= 0) continue
                val sequence = line.substring(0, firstTab)
                val prefix = line.substring(firstTab + 1, secondTab)
                if (prefix.isEmpty()) continue
                if (bases.containsKey(sequence)) continue
                bases[sequence] = prefixes.size
                prefixes.add(prefix)
                suffixes.add(line.substring(secondTab + 1))
            }
            if (prefixes.isEmpty()) return EMPTY
            return EmojiSkinTones(bases, prefixes.toTypedArray(), suffixes.toTypedArray())
        }
    }
}
