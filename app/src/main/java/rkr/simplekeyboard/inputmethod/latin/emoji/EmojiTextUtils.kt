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

import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils

/**
 * Pure, Android-free text helper that measures the trailing emoji grapheme cluster so a single
 * backspace can delete it whole.
 *
 * The keyboard's own emoji set deliberately excludes the composite classes (skin tone, ZWJ, flags,
 * tags) — but deletion still has to cover them, because such text arrives from other keyboards and
 * from pasted content. Leaving a lone variation selector, half of a flag, or a base stripped of its
 * skin-tone modifier behind is exactly the "emoji fragment" defect this function exists to prevent.
 *
 * The cluster measurement runs on the keystroke path, including 50 ms backspace auto-repeat,
 * so it allocates nothing: it walks the [CharSequence] by index with [Character.codePointBefore],
 * never taking a substring, a regex, or a collection. It reads only the text it is handed, never
 * logs it, and keeps no copy of it. The second public entry point, [extractContextBeforeEmoji],
 * runs once per emoji PICK — a user-paced event off the keystroke path — and borrows the same
 * measurement.
 */
object EmojiTextUtils {

    /** ZERO WIDTH JOINER — glues the elements of a family/role/couple emoji together. */
    private const val ZWJ = 0x200D

    /** VARIATION SELECTOR-15: request the text (monochrome) presentation of the base. */
    private const val VS15 = 0xFE0E

    /** VARIATION SELECTOR-16: request the emoji (color) presentation of the base. */
    private const val VS16 = 0xFE0F

    /** COMBINING ENCLOSING KEYCAP — the box drawn around a digit, `#` or `*`. */
    private const val KEYCAP = 0x20E3

    /** CANCEL TAG (U+E007F): the terminator that closes a tag sequence such as a subdivision flag. */
    private const val TAG_TERMINATOR = 0xE007F

    /**
     * Hard cap on the size of a deletable cluster, in java-`char`. A cluster longer than this — a
     * pathological ZWJ chain or an over-long tag sequence — falls back to the ordinary code-point
     * delete. Deleting one code point from a broken monster is never worse than the alternative of
     * scanning an unbounded tail on every keystroke.
     */
    private const val MAX_CLUSTER_CHARS = 32

    /**
     * Returns how many java-`char` a single backspace should delete so that a trailing emoji
     * grapheme cluster disappears in one press, or `0` when the text does not end in an emoji.
     *
     * A `0` result means "not an emoji": the caller keeps its previous, byte-for-byte identical
     * behaviour (delete one code point). Ordinary text — letters (Tatar, Russian, Latin), digits,
     * spaces, a bare combining mark, an unpaired surrogate — therefore always returns `0`, so the
     * frozen non-emoji deletion is untouched.
     *
     * Covered cluster classes: a lone BMP emoji; a surrogate pair; a base plus VS15/VS16; a base
     * plus a skin-tone modifier (U+1F3FB–U+1F3FF); a ZWJ sequence of two or three base emoji; a
     * keycap (`digit`/`#`/`*` [+ VS16] + U+20E3); a pair of regional indicators (a flag); and a tag
     * sequence closed by U+E007F. Anything longer than [MAX_CLUSTER_CHARS] returns `0`.
     */
    @JvmStatic
    fun trailingEmojiClusterLength(text: CharSequence): Int =
        trailingEmojiClusterLengthEndingAt(text, text.length)

    /**
     * The [trailingEmojiClusterLength] measurement of the prefix `text[0, end)` — the identical
     * walk, bounded so a caller peeling a RUN of trailing clusters never re-reads the tail it
     * already stripped.
     */
    private fun trailingEmojiClusterLengthEndingAt(text: CharSequence, end: Int): Int {
        if (end == 0) return 0
        // Recognise the emoji element that ends the text. A negative result means the tail is
        // ordinary text, so we hand deletion straight back to the code-point path.
        var start = emojiElementStart(text, end)
        if (start < 0) return 0
        // Walk back over any ZWJ-joined elements sitting in front of it (couples, families, roles).
        while (start >= 1 && text[start - 1].code == ZWJ) {
            val previous = emojiElementStart(text, start - 1)
            // An orphan ZWJ with no emoji element before it is left in place with its joiner.
            if (previous < 0) break
            start = previous
            if (end - start > MAX_CLUSTER_CHARS) return 0
        }
        val clusterLength = end - start
        if (clusterLength > MAX_CLUSTER_CHARS) return 0
        return clusterLength
    }

    /**
     * The word an emoji pick was co-used with, read from the text before the cursor (feature C —
     * personal word→emoji learning), or null when there is none. The trailing emoji RUN is peeled
     * first: a pick made right after another emoji still belongs to the same word, so consecutive
     * clusters count as one boundary by design ("сәләм ☀️😊" → сәләм), and a text holding nothing
     * but emoji yields null.
     *
     * The word itself is read by the two rules the suggestion engine uses, chosen by what the
     * remainder ends with: whitespace means the NEXT_WORD context rule
     * ([TatarWordUtils.extractNextWordContext] — a run of U+0020, a non-final punctuation run
     * transparent, anything else silent), anything else means the emoji hugs its word and the plain
     * trailing-word rule applies ([TatarWordUtils.extractTrailingWord], "сәләм☀️" → сәләм).
     *
     * Unlike the lookup path there is no cache provenance to consult — the helper sees only the
     * text it is handed — so a word at the very start of the text is taken as whole (the same
     * stance [TatarWordUtils.extractTrailingWord] has always taken). A word the editor cache
     * truncated could at worst enter the pending counters, and two IDENTICAL truncated observations
     * of one (word, emoji) pair cannot recur from a window that slides with every keystroke, so
     * such a fragment can never graduate into the store.
     */
    @JvmStatic
    fun extractContextBeforeEmoji(text: CharSequence): String? {
        var end = text.length
        while (true) {
            val cluster = trailingEmojiClusterLengthEndingAt(text, end)
            if (cluster == 0) break
            end -= cluster
        }
        if (end == text.length || end == 0) return null
        val remainder = text.subSequence(0, end)
        val word = if (remainder[remainder.length - 1].isWhitespace()) {
            TatarWordUtils.extractNextWordContext(remainder, cacheReachedTextStart = true)
        } else {
            TatarWordUtils.extractTrailingWord(remainder)
        }
        return word.ifEmpty { null }
    }

    /**
     * Reads a single emoji element backward from [end] (exclusive) and returns its start index, or
     * `-1` when the code point ending at [end] does not begin a recognisable emoji element.
     *
     * An "element" is one base plus the modifiers that bind to it: a tag sequence, a keycap, a
     * variation selector, a skin-tone modifier, a regional-indicator pair, or a bare emoji base.
     * The terminal code point selects which shape to parse, so each branch consumes exactly the
     * characters that belong to that element and nothing more.
     */
    private fun emojiElementStart(text: CharSequence, end: Int): Int {
        if (end <= 0) return -1
        val last = Character.codePointBefore(text, end)
        val lastLen = Character.charCount(last)
        return when {
            last == TAG_TERMINATOR -> tagSequenceStart(text, end)
            last == KEYCAP -> keycapSequenceStart(text, end)
            last == VS15 || last == VS16 -> variationSequenceStart(text, end)
            isSkinToneModifier(last) -> skinToneSequenceStart(text, end)
            isRegionalIndicator(last) -> regionalIndicatorStart(text, end)
            isEmojiBase(last) -> end - lastLen
            else -> -1
        }
    }

    /** `base + tag-chars* + U+E007F`; requires an emoji base in front of the tag run. */
    private fun tagSequenceStart(text: CharSequence, end: Int): Int {
        // The terminator itself is supplementary (2 chars); step over it first.
        var i = end - Character.charCount(TAG_TERMINATOR)
        while (i > 0) {
            val cp = Character.codePointBefore(text, i)
            if (!isTagChar(cp)) break
            i -= Character.charCount(cp)
        }
        if (i <= 0) return -1
        val base = Character.codePointBefore(text, i)
        if (!isEmojiBase(base)) return -1
        return i - Character.charCount(base)
    }

    /** `(digit | '#' | '*') + [VS16] + U+20E3`. */
    private fun keycapSequenceStart(text: CharSequence, end: Int): Int {
        // The keycap mark is BMP; the optional VS16 before it is BMP too.
        var i = end - 1
        if (i > 0 && Character.codePointBefore(text, i) == VS16) {
            i -= 1
        }
        if (i <= 0) return -1
        val base = Character.codePointBefore(text, i)
        if (!isKeycapBase(base)) return -1
        return i - 1
    }

    /** `base + (VS15 | VS16)`; the selector proves the preceding code point is the base. */
    private fun variationSequenceStart(text: CharSequence, end: Int): Int {
        val i = end - 1
        if (i <= 0) return -1
        val base = Character.codePointBefore(text, i)
        return i - Character.charCount(base)
    }

    /** `emoji-base + skin-tone-modifier`; a modifier with no emoji base is its own element. */
    private fun skinToneSequenceStart(text: CharSequence, end: Int): Int {
        val i = end - 2 // every skin-tone modifier is supplementary.
        if (i > 0) {
            val base = Character.codePointBefore(text, i)
            if (isEmojiBase(base)) return i - Character.charCount(base)
        }
        return end - 2
    }

    /** A flag is a pair of regional indicators; a lone indicator stands as its own element. */
    private fun regionalIndicatorStart(text: CharSequence, end: Int): Int {
        val i = end - 2 // every regional indicator is supplementary.
        if (i > 0) {
            val previous = Character.codePointBefore(text, i)
            if (isRegionalIndicator(previous)) return i - 2
        }
        return end - 2
    }

    private fun isSkinToneModifier(codePoint: Int): Boolean = codePoint in 0x1F3FB..0x1F3FF

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

    private fun isTagChar(codePoint: Int): Boolean = codePoint in 0xE0020..0xE007E

    private fun isKeycapBase(codePoint: Int): Boolean =
        codePoint == 0x23 || codePoint == 0x2A || codePoint in 0x30..0x39

    /**
     * A deliberately broad emoji-base test over the pictographic blocks.
     *
     * Over-inclusion inside these ranges is harmless: a bare supplementary symbol deletes as two
     * `char` down either path, so classifying it as emoji changes nothing. The ranges must stay
     * clear of ordinary text — Latin, Cyrillic (Tatar and Russian included), digits and combining
     * marks all live well below U+2300 — so those never match here and keep returning `0`.
     */
    private fun isEmojiBase(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF || // emoji, symbols, pictographs, transport, flags-as-RIs
            codePoint in 0x2600..0x27BF || // Misc Symbols + Dingbats (☺, ❤, ✂ …)
            codePoint in 0x2B00..0x2BFF || // Misc Symbols and Arrows (⭐, ⬛ …)
            codePoint in 0x2300..0x23FF // Misc Technical (⌚, ⌛, ⏰ …)
}
