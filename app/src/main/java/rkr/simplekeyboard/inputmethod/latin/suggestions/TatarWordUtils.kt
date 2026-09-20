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

import java.text.Normalizer

/**
 * Pure text helpers shared by the Tatar suggestion controller and its editor surface.
 *
 * These functions never touch the InputConnection, never log text, and are deterministic so they
 * can be exercised by plain JVM unit tests. The normalization here MUST stay byte-for-byte
 * compatible with how the packed dictionary asset stores its words (see
 * scripts/dictionary_coverage.py::normalize_word and scripts/dictionary_pack.py). The asset words
 * are Unicode NFC then lower-cased; the on-disk index expects the caller to hand it pre-normalized
 * UTF-8 bytes ([TatarWordUtils.toLookupBytes]).
 */
object TatarWordUtils {

    /**
     * Hard cap on how much of the cached tail [endsWithWordOfAtLeast] is allowed to look at,
     * counted in CODE POINTS (so a tail made entirely of supplementary characters touches at most
     * twice as many `char`s). The cache can hold a whole paragraph, and this check runs on the
     * keystroke path, so the scan is bounded rather than proportional to the text.
     */
    private const val MAX_TAIL_SCAN = 64

    /**
     * Returns the maximal trailing run of word characters in [textBeforeCursor] as an EXACT span of
     * the raw text.
     *
     * A word character is a [Character.isLetter] letter or a combining mark bound to a preceding
     * base character. Marks have to continue the run because the frozen contract accepts
     * canonically decomposed (NFD) input: in NFD "й" is "и" + U+0306 and "ё" is "е" + U+0308, and
     * those marks are not letters, so a letters-only scan would cut the word short — or return ""
     * when the decomposed letter is the last thing typed. All three mark categories continue the
     * run: Mn covers the Cyrillic/Latin canonical decompositions we actually see, Mc appears in the
     * canonical decompositions of other scripts, and Me only ever decorates a preceding base. None
     * of them can stand alone, so none of them is a word boundary.
     *
     * The result stays a verbatim substring of [textBeforeCursor] and is never normalized here: the
     * commit path deletes exactly `prefix.length` raw characters before the cursor, so rewriting
     * the span would delete the wrong amount of text. NFC folding happens later, in
     * [normalizeForLookup].
     *
     * Returns "" when the input is null/empty, when the final character is not a word character, or
     * when the trailing run holds no letter at all (marks without a base letter are orphans, not a
     * word); leading orphan marks are trimmed off the span for the same reason. Only BMP letters
     * occur in Tatar Cyrillic, Latin, and Russian Cyrillic text, so char-based classification is
     * sufficient and matches the frozen contract.
     */
    @JvmStatic
    fun extractTrailingWord(textBeforeCursor: CharSequence?): String {
        if (textBeforeCursor == null) return ""
        val length = textBeforeCursor.length
        if (length == 0) return ""
        var start = length
        while (start > 0 && isWordCharacter(textBeforeCursor[start - 1])) {
            start--
        }
        // The scan stopped on a non-word character (or the start of the text), so marks sitting at
        // the head of the run have no base letter inside the run and are not part of the word.
        while (start < length && !Character.isLetter(textBeforeCursor[start])) {
            start++
        }
        if (start == length) return ""
        return textBeforeCursor.subSequence(start, length).toString()
    }

    /**
     * E5d NEXT_WORD context extraction (PROPOSALS.md, "Контракт текста" amendment, 2026-08-17,
     * пункт 1): the word immediately before a trailing run of one-or-more U+0020, or "" if there is
     * no such run right at the cursor.
     *
     * The separator is deliberately narrower than [isWordCharacter]'s complement: it is EXACTLY one
     * or more U+0020 and nothing else. A newline, tab, NBSP, or any punctuation right before the
     * cursor yields "" — not because those characters cannot end a word, but because the contract
     * reserves NEXT_WORD for the plain "finished a word, pressed space" moment and deliberately
     * excludes sentence starts and positions right after punctuation (the bigram table is a
     * word-context table and stays one; sentence starts are answered separately from the P4
     * sentence-start table — [isSentenceStartContext] — and this exclusion still doubles as the
     * thing that keeps NEXT_WORD out of the auto-capitalization codepath). This function IS that
     * exclusion: it is a side effect of the separator rule, not a second check layered on top of it.
     *
     * The word itself is [extractTrailingWord] of the text before the separator run — the exact same
     * word-boundary algorithm PREFIX mode uses, so "context word" and "prefix" agree on what a word
     * is. Both the separator run and the word must fit inside [textBeforeCursor] without touching its
     * start: if either reaches index 0, the true word may have been cut off by the caller's cache
     * limit ([rkr.simplekeyboard.inputmethod.latin.common.Constants.EDITOR_CONTENTS_CACHE_SIZE],
     * 1024 characters) rather than genuinely starting there, and "" is returned because a possibly
     * truncated context word cannot be trusted for a lookup.
     *
     * The single-argument overload cannot tell a truncated cache from a genuinely short field, so it
     * treats both the same, conservatively. [extractNextWordContext] with `cacheReachedTextStart` is
     * the same extraction for a caller that CAN tell ([RichInputConnection]'s cache is filled with a
     * request for the full 1024-character window, so a shorter result provably starts at the start of
     * the text — docs/NEXTWORD-RACE.md): the boundary guard then applies only to a genuinely full
     * cache, and the first word of an empty field — which always sits at index 0 without being
     * truncated — is extracted like any other.
     */
    @JvmStatic
    fun extractNextWordContext(textBeforeCursor: CharSequence?): String =
        extractNextWordContext(textBeforeCursor, cacheReachedTextStart = false)

    /**
     * The two-argument form of [extractNextWordContext]: [cacheReachedTextStart] true means the
     * caller knows the given text is NOT cut off at index 0 (the cache window reached the start of
     * the field), so a word touching index 0 is whole and may be returned. The separator-run guard
     * is unaffected either way: an all-spaces cache has no word in it regardless.
     */
    @JvmStatic
    fun extractNextWordContext(textBeforeCursor: CharSequence?, cacheReachedTextStart: Boolean): String {
        if (textBeforeCursor == null) return ""
        val length = textBeforeCursor.length
        var separatorStart = length
        while (separatorStart > 0 && textBeforeCursor[separatorStart - 1] == ' ') {
            separatorStart--
        }
        if (separatorStart == length) return "" // no trailing space run at all
        if (separatorStart == 0) return "" // the separator itself reaches the cache boundary
        val word = extractTrailingWord(textBeforeCursor.subSequence(0, separatorStart))
        if (word.isEmpty()) return ""
        // The word reaching index 0 is only suspicious when the cache may have cut it off.
        if (!cacheReachedTextStart && separatorStart - word.length == 0) return ""
        return word
    }

    /**
     * The P4 sentence-start detection (docs/TT-SUGGESTIONS.md): true when the cursor sits where a
     * new sentence begins, i.e. the text before it either IS the start of the field or ends in a
     * run of sentence-ending punctuation ('.', '!', '?', '…') followed by one or more U+0020.
     *
     * This amends the frozen "no prediction after punctuation" contract deliberately and narrowly:
     * it is a DETECTOR, not a relaxation of [extractNextWordContext] — the bigram table is still
     * never consulted at these positions (a sentence boundary resets the context), and the set of
     * sentence-ending characters is exactly the four above. Anything else before the space run —
     * a word, a comma, a quote, a closing parenthesis — is not a sentence start. The punctuation
     * run must directly follow a letter ("сүз. ", "нәрсә?! ") or open the field: a digit+period
     * ("5. ") is a number, not a sentence end. Closing quotes/brackets after the period ("сүз.» ")
     * are an accepted miss — over-matching would offer sentence starts mid-sentence, which is the
     * worse direction.
     *
     * [cacheReachedTextStart] carries the same provenance as in [extractNextWordContext]
     * (docs/NEXTWORD-RACE.md): an empty or all-spaces cache, and a punctuation run touching index
     * 0, are only trusted when the cache provably reached the start of the text — a truncated
     * window may hide the word that actually precedes. The single-argument overload cannot tell,
     * so it treats those conservatively (false), while mid-text cases like "сүз. " need no
     * provenance: the period is visible right where it matters.
     *
     * Allocation-free; the scans are bounded by the cache size
     * ([rkr.simplekeyboard.inputmethod.latin.common.Constants.EDITOR_CONTENTS_CACHE_SIZE]).
     */
    @JvmStatic
    fun isSentenceStartContext(textBeforeCursor: CharSequence?): Boolean =
        isSentenceStartContext(textBeforeCursor, cacheReachedTextStart = false)

    /** The two-argument form of [isSentenceStartContext]; see its contract for the rules. */
    @JvmStatic
    fun isSentenceStartContext(textBeforeCursor: CharSequence?, cacheReachedTextStart: Boolean): Boolean {
        if (textBeforeCursor == null) return false
        val length = textBeforeCursor.length
        var separatorStart = length
        while (separatorStart > 0 && textBeforeCursor[separatorStart - 1] == ' ') {
            separatorStart--
        }
        if (separatorStart == 0) {
            // Empty text, or nothing but spaces: a genuine field start only when the cache
            // provably reached the start of the text.
            return cacheReachedTextStart
        }
        if (separatorStart == length) return false // no trailing U+0020 run at all
        var punctStart = separatorStart
        while (punctStart > 0 && isSentenceEndingPunctuation(textBeforeCursor[punctStart - 1])) {
            punctStart--
        }
        if (punctStart == separatorStart) return false // the space run follows no sentence end
        if (punctStart == 0) return cacheReachedTextStart // the run opens the field, or hides a cut
        return Character.isLetter(textBeforeCursor[punctStart - 1])
    }

    /** The four sentence-ending characters of the P4 contract: '.', '!', '?', '…' (U+2026). */
    private fun isSentenceEndingPunctuation(ch: Char): Boolean =
        ch == '.' || ch == '!' || ch == '?' || ch == '…'

    /**
     * True when the text before the cursor ends in a word that holds at least [minLetters] letters,
     * ignoring whatever non-word characters trail it.
     *
     * This is the "the user has just finished a real word" test behind the one-shot offer to turn
     * Tatar suggestions on: the offer fires on a committed word separator, so the tail normally ends
     * with the separator that was just typed and the word sits in front of it. Several separators in
     * a row ("сүз!.. ") are skipped the same way, and a tail that ends in a letter (an editor whose
     * cache has not caught up with the separator yet) is simply measured as it stands — the trailing
     * run is the only thing this function looks at.
     *
     * Counting matches [extractTrailingWord] so the same text never qualifies here and fails there:
     * combining marks continue the run but are not counted as letters, and letters are
     * [Character.isLetter] CODE POINTS, so a supplementary letter counts once rather than twice.
     * Letter class is deliberately not narrowed to the Tatar alphabet — the intent to type Tatar is
     * proven by the active tt_RU subtype, not by which letters the word happens to contain.
     *
     * Allocates nothing and reads at most [MAX_TAIL_SCAN] code points of the tail, counting up to
     * [minLetters] and no further, because this runs on the keystroke path. A tail longer than that
     * budget returns false: an offer that never appears is the accepted failure direction.
     */
    @JvmStatic
    fun endsWithWordOfAtLeast(textBeforeCursor: CharSequence?, minLetters: Int): Boolean {
        if (textBeforeCursor == null) return false
        var index = textBeforeCursor.length
        var scanned = 0
        // Step over the separators that ended the word.
        while (index > 0 && scanned < MAX_TAIL_SCAN) {
            val codePoint = Character.codePointBefore(textBeforeCursor, index)
            if (isWordCharacter(codePoint)) break
            index -= Character.charCount(codePoint)
            scanned++
        }
        var letters = 0
        while (index > 0 && scanned < MAX_TAIL_SCAN && letters < minLetters) {
            val codePoint = Character.codePointBefore(textBeforeCursor, index)
            if (!isWordCharacter(codePoint)) break
            if (Character.isLetter(codePoint)) letters++
            index -= Character.charCount(codePoint)
            scanned++
        }
        return letters >= minLetters
    }

    /**
     * True when the text right after the cursor continues the word the cursor sits in, i.e. when
     * the cursor is INSIDE a word rather than at its end.
     *
     * The frozen contract clears the results when a Tatar letter follows the cursor, because
     * replacing the trailing word would then splice the suggestion into the middle of the user's
     * text. This test is deliberately wider than "Tatar letter": any [Character.isLetter] letter
     * (Latin and Russian included) and any combining mark counts, because being fail-closed can
     * only cost a suggestion, while being too narrow corrupts text. A leading combining mark means
     * the cursor is inside a canonically decomposed character, which is inside a word too.
     *
     * Reads the FIRST CODE POINT, not the first char, so a supplementary letter is classified
     * correctly instead of being seen as a lone (caseless, non-letter) surrogate.
     */
    @JvmStatic
    fun startsWithWordCharacter(textAfterCursor: CharSequence?): Boolean {
        if (textAfterCursor == null || textAfterCursor.isEmpty()) return false
        return isWordCharacter(Character.codePointAt(textAfterCursor, 0))
    }

    /**
     * True when a suggestion committed at the cursor must carry a trailing space, i.e. when the
     * text right after the cursor does not already provide the separation the next word needs.
     *
     * Accepting a suggestion appends the space in the same commit so the user can keep typing the
     * next word without reaching for the space bar. The space is left out in the two cases where it
     * would be wrong:
     * - a space is already there — [Character.isWhitespace] covers tab/newline and
     *   [Character.isSpaceChar] covers the non-breaking space, and only their union means "the
     *   separator already exists";
     * - punctuation follows the cursor and has to hug the word: "сүз," must never become "сүз ,".
     *   Classification goes through the Unicode punctuation categories (Pc/Pd/Ps/Pe/Pi/Pf/Po)
     *   rather than a hardcoded ASCII list, so the typography Tatar and Russian actually use — «»,
     *   the em dash, the ellipsis, curly quotes — is covered too.
     *
     * Math symbols (Sm: "+", "=") are deliberately NOT treated as hugging punctuation, because they
     * are written with spaces around them ("2 + 2"). Anything else — a digit, an emoji, a letter —
     * also keeps the space: a space too many costs one backspace, a space too few costs the feature
     * its whole point.
     *
     * Reads the FIRST CODE POINT, exactly like [startsWithWordCharacter], so a supplementary
     * character is classified as itself instead of as a lone (uncategorized) surrogate.
     */
    @JvmStatic
    fun needsAutoSpace(textAfterCursor: CharSequence?): Boolean {
        if (textAfterCursor == null || textAfterCursor.isEmpty()) return true
        val codePoint = Character.codePointAt(textAfterCursor, 0)
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) return false
        return !isHuggingPunctuation(codePoint)
    }

    /**
     * True for the separators an autocorrection (D3) may fire on: the contract says «пробел или
     * пунктуация», and this is that sentence and nothing more.
     *
     * [Character.isSpaceChar] covers the plain space and the non-breaking space; the punctuation
     * categories are the very ones [needsAutoSpace] already uses, so «сүз,» and «сүз —» end a word
     * here for exactly the reason they hug it there.
     *
     * Enter and Tab are deliberately NOT separators for this purpose, even though they arrive as
     * ordinary code points ('\n', '\t') and both are listed in `symbols_word_separators`.
     * [Character.isWhitespace] would have swept them in; [Character.isSpaceChar] does not. The
     * reason is not taste: Enter may perform an editor action instead of committing anything, so a
     * replacement made just before it would edit a field that is being submitted — with nothing
     * committed after the word, and usually no field left to revert in.
     *
     * The caller ALSO requires the code point to be a word separator of the live layout
     * (`SettingsValues.isWordSeparator`); the two conditions are a conjunction, never an
     * alternative.
     */
    @JvmStatic
    fun isAutocorrectSeparator(codePoint: Int): Boolean =
        Character.isSpaceChar(codePoint) || isHuggingPunctuation(codePoint)

    /** True for the punctuation categories that must stay glued to the word in front of them. */
    private fun isHuggingPunctuation(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt()
    }

    /** True for letters and for the non-standalone combining marks that attach to them. */
    private fun isWordCharacter(ch: Char): Boolean = isWordCharacter(ch.code)

    /** Code-point flavour of [isWordCharacter]; the char flavour delegates here. */
    private fun isWordCharacter(codePoint: Int): Boolean {
        if (Character.isLetter(codePoint)) return true
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    /**
     * Normalizes [word] to the exact form used by the dictionary asset: Unicode NFC followed by
     * invariant-locale lower casing. This mirrors the Python pipeline
     * `unicodedata.normalize("NFC", word).lower()`. [String.lowercase] uses the invariant locale,
     * which matches Python's locale-independent `str.lower()` for Cyrillic and Tatar-specific
     * letters.
     */
    @JvmStatic
    fun normalizeForLookup(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC).lowercase()

    /** Encodes an already-normalized lookup key as UTF-8, the byte form the index expects. */
    @JvmStatic
    fun toLookupBytes(normalized: String): ByteArray = normalized.toByteArray(Charsets.UTF_8)

    /**
     * The casing shape of the prefix the user actually typed, as classified by [classifyCasing].
     *
     * The dictionary ASSET stores NFC lowercase words only, so for asset (and fuzzy) candidates the
     * shape is what re-applies the user's capitalization ([applyCasing]) — the displayed and the
     * inserted form always share it. The PERSONAL dictionary instead stores each word in its
     * original form and uses the NFC lowercase form only for sorting, dedup, filters and search
     * (see the "Контракт текста" amendment of 2026-07-27); at a LOWER prefix a personal record is
     * shown in its stored casing, so [applyCasing] is not applied to it.
     */
    enum class PrefixCasing {
        /** No uppercase letter: candidates are shown exactly as the dictionary stores them. */
        LOWER,

        /** A single uppercase letter first (possibly the only letter), the rest lowercase. */
        INITIAL_CAPS,

        /** Two or more uppercase letters and no lowercase letter. */
        ALL_CAPS,

        /** Any other mix of cases; the frozen contract requires 0 results for it. */
        MIXED,
    }

    /**
     * Classifies the casing of the RAW (unnormalized) prefix per the frozen text contract:
     * all-lowercase -> [PrefixCasing.LOWER]; one uppercase letter, or a leading uppercase letter
     * followed only by lowercase ones -> [PrefixCasing.INITIAL_CAPS]; two or more uppercase letters
     * with no lowercase letter -> [PrefixCasing.ALL_CAPS]; anything else -> [PrefixCasing.MIXED],
     * for which the caller must publish 0 results.
     *
     * Caseless characters are ignored rather than treated as lowercase, so a decomposed (NFD)
     * prefix classifies the same as its composed form — the combining marks it carries are neither
     * upper- nor lowercase. An empty prefix is [PrefixCasing.LOWER]: nothing was capitalized, so
     * the safe default is to leave candidates untouched.
     */
    @JvmStatic
    fun classifyCasing(rawPrefix: String): PrefixCasing {
        var upperCount = 0
        var lowerCount = 0
        var firstCasedIsUpper = false
        var sawCased = false
        for (ch in rawPrefix) {
            val isUpper = Character.isUpperCase(ch)
            val isLower = Character.isLowerCase(ch)
            // Caseless characters (combining marks above all) are skipped outright: counting them
            // as lowercase would turn a decomposed "Й" into mixed case.
            if (!isUpper && !isLower) continue
            if (!sawCased) {
                sawCased = true
                firstCasedIsUpper = isUpper
            }
            if (isUpper) upperCount++ else lowerCount++
        }
        if (upperCount == 0) return PrefixCasing.LOWER
        if (upperCount >= 2 && lowerCount == 0) return PrefixCasing.ALL_CAPS
        // Exactly one uppercase letter, and it opens the word: "С" and "Сүз" are both Initial Caps.
        // A lone uppercase later in the word ("сҮз") is mixed case and yields no results.
        if (upperCount == 1 && firstCasedIsUpper) return PrefixCasing.INITIAL_CAPS
        return PrefixCasing.MIXED
    }

    /**
     * Re-applies the user's [casing] to a [candidate] taken from the dictionary (NFC lowercase).
     *
     * Called only AFTER ranking, on the candidates that are actually about to be shown, because
     * ranking runs on the normalized lowercase forms. Casing uses the invariant locale, exactly
     * like [normalizeForLookup], so it stays in step with the Python packing pipeline.
     *
     * [PrefixCasing.MIXED] must never reach this function: the frozen contract requires 0 results
     * for mixed case, so the caller drops the candidates earlier. It is handled as a pass-through
     * rather than as a throw because an exception on the tap path would take the keyboard down.
     */
    @JvmStatic
    fun applyCasing(candidate: String, casing: PrefixCasing): String = when (casing) {
        PrefixCasing.LOWER, PrefixCasing.MIXED -> candidate
        // Uppercase the first character as a string: some casing maps expand to several characters,
        // and going through String.uppercase() keeps that (and the invariant locale) correct.
        PrefixCasing.INITIAL_CAPS ->
            if (candidate.isEmpty()) candidate
            else candidate.substring(0, 1).uppercase() + candidate.substring(1)
        PrefixCasing.ALL_CAPS -> candidate.uppercase()
    }
}
