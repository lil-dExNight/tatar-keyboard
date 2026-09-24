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

/**
 * The injected seams [SuggestionsController] is driven through: the editor surface, the tap
 * listener, the two live setting gates, the UI-thread poster and the dictionary-unavailability
 * listener. Pure declarations, extracted verbatim from `SuggestionsController.kt`
 * (ROADMAP Phase 6, T2). [StripSurface] deliberately stays in `SuggestionsController.kt`: a
 * source-contract test pins its default no-op emphasis seam to that file.
 */

/** Fired when the user taps a suggestion in the strip (UI thread). */
fun interface SuggestionTapListener {
    fun onTap(suggestion: String)
}

/** Editor seam backed by RichInputConnection's cache. All methods are called on the UI thread. */
interface EditorSurface {
    fun cachedWordBeforeCursor(): String
    fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean
    fun hasKnownCursor(): Boolean

    /**
     * True when the cursor sits inside a word, i.e. the text right after it starts with a letter
     * (or with a combining mark, which can only continue one). Typing in the middle of a word is
     * not supported by the frozen contract: the results are cleared instead, because replacing the
     * trailing word would splice the suggestion into the user's text.
     */
    fun hasLetterAfterCursor(): Boolean

    /**
     * The SECOND insertion path of the frozen text contract (D3): replaces the trailing word
     * [expectedPrefix] with [replacement] through the very same explicit delete-by-code-points plus
     * `commitText` in ONE batch edit that an accepted suggestion goes through, and with the same
     * re-checks. It differs from [commitSuggestion] in one thing only — no trailing auto-space,
     * because the separator the user just pressed is committed right after it by the ordinary input
     * path.
     *
     * Returns false without editing anything if any check fails. Defaults to false so an editor
     * surface written before D3 keeps compiling and simply never autocorrects.
     */
    fun replaceTypedWord(expectedPrefix: String, replacement: String): Boolean = false

    /**
     * Undoes the last autocorrection: where [insertedForm] + [separator] stands immediately before
     * the cursor, puts [typedForm] + [separator] back, in one batch edit.
     *
     * The suffix match IS the position check the contract asks for, and a stricter one than an
     * offset: an offset can coincide again after unrelated edits, the exact text cannot. Returns
     * false without editing anything when the text before the cursor is no longer what the
     * replacement left there. Defaults to false, like [replaceTypedWord].
     */
    fun revertTypedWord(insertedForm: String, separator: String, typedForm: String): Boolean = false

    /**
     * E5d NEXT_WORD context extraction from the live cache (PROPOSALS.md, "Контракт текста"
     * amendment, 2026-08-17): the word immediately before a trailing run of one-or-more U+0020 right
     * at the cursor, or "" if there is none — with the ROADMAP Phase 1 (P4, docs/ROADMAP-P1.md)
     * amendment: when the space run follows non-final punctuation (exactly ',', ';', ':'), the
     * context is the word BEFORE the punctuation run ("сүз, " → "сүз"). Defaults to "" so an editor
     * surface written before E5d keeps compiling and NEXT_WORD simply never fires.
     */
    fun cachedNextWordContext(): String = ""

    /**
     * P1 of Phase 2 (docs/ROADMAP-P2.md): the committed word immediately BEFORE the trailing
     * completed word — the context half of a just-typed pair «A B ». Read from the live cache at
     * the moment the trailing word has just become empty, so the tail ends with the separator
     * that completed B; the word before that separator is B and the word before B is A. "" when
     * there is no such word, or when the cache cannot prove it (a window cut off before the text
     * start). Defaults to "" so an editor surface written before P1 keeps compiling and personal
     * bigrams simply never observe a pair.
     */
    fun cachedWordBeforeTrailingWord(): String = ""

    /**
     * P4 sentence-start detection over the live cache (docs/TT-SUGGESTIONS.md): true when the
     * cursor sits where a new sentence begins — the start of the field, or sentence-ending
     * punctuation followed by space(s) — by [TatarWordUtils.isSentenceStartContext]'s exact rules,
     * cache-start provenance included. Defaults to false so an editor surface written before P4
     * keeps compiling and simply never shows sentence-start predictions.
     */
    fun isAtSentenceStart(): Boolean = false

    /**
     * The THIRD insertion path of the frozen text contract (E5d): commits a predicted next word.
     * Unlike [commitSuggestion] and [replaceTypedWord], this one deletes NOTHING — NEXT_WORD only
     * ever fires on an empty prefix, so there is nothing trailing to remove; it only inserts, with
     * the same auto-space rule an accepted suggestion uses.
     *
     * Re-checked against the live cache: collapsed selection, no letter right after the cursor (the
     * same two checks the other two paths make), an EMPTY trailing word (a non-empty one means the
     * user typed something after the request was built — the tap is stale), and the live context
     * word re-extracted by [cachedNextWordContext]'s own algorithm matching [expectedContextWord]
     * exactly. P4 adds one case to that equality: at a sentence start the context word is EMPTY on
     * both sides, and the production path then additionally requires the live position to still be
     * a sentence start ([isAtSentenceStart]), so a tap after ", " commits nothing. Defaults to
     * false, like [replaceTypedWord] and [revertTypedWord].
     */
    fun commitPredictedWord(expectedContextWord: String, suggestion: String): Boolean = false
}

/**
 * Reads the live value of `PREF_TATAR_AUTOCORRECT`. A seam, so the controller needs no preferences
 * and JVM tests can flip the setting between two keystrokes exactly as a user can.
 */
fun interface AutocorrectGate {
    fun isOn(): Boolean
}

/**
 * Reads the live value of `PREF_GLIDE_TYPING` (P7-3, docs/GLIDE-PLAN.md) — the exact same seam
 * shape as [AutocorrectGate]. Read at every gesture, so flipping the setting takes effect on the
 * next glide without restarting anything.
 */
fun interface GlideGate {
    fun isOn(): Boolean
}

/**
 * Reads the keyboard's current shift state for the glide commit's casing rule (P7-3): shifted
 * (manual or automatic) means the committed word is capitalized, exactly as the letters of a
 * typed word would have been. A seam, so JVM tests can flip shift between two gestures.
 */
fun interface ShiftStateGate {
    fun isShifted(): Boolean
}

/**
 * Reads the live value of `PREF_EMOJI_SUGGESTIONS` — the exact same seam shape as
 * [AutocorrectGate], for the emoji cell of the NEXT_WORD band (mission 2 of
 * docs/EMOJI-SUGGEST-PLAN.md). Read on every fill, so flipping the setting takes effect on the
 * next band without restarting anything.
 */
fun interface EmojiSuggestGate {
    fun isOn(): Boolean
}

/**
 * Marshals a [Runnable] onto the UI thread. Production wraps an [android.os.Handler]; JVM tests
 * inject a synchronous poster so no real Handler is needed.
 */
fun interface UiPoster {
    fun post(runnable: Runnable)
}

/**
 * Notified when a dictionary preparation that an *explicit* enable asked for ended
 * [PreparationResult.Unavailable].
 *
 * Only explicit enables are reported. A preparation started by the controller becoming eligible for
 * the first time was never asked for by the user, so failing it silently is the right answer; a
 * preparation started by an observed OFF -> ON transition answers a switch the user just flipped,
 * and leaving that unanswered would look like the setting simply did nothing.
 */
fun interface DictionaryUnavailableListener {
    fun onDictionaryUnavailableAfterExplicitEnable()
}
