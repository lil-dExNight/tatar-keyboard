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
 * listener, the live setting gates, the UI-thread poster and the dictionary-unavailability
 * listener. [StripSurface] stays in `SuggestionsController.kt`: a source-contract test pins its
 * default no-op emphasis seam to that file.
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
     * (or with a combining mark, which can only continue one). Suggestions are not offered in the
     * middle of a word: the results are cleared instead, because replacing the trailing word
     * would splice the suggestion into the user's text.
     */
    fun hasLetterAfterCursor(): Boolean

    /**
     * Autocorrect insertion path: replaces the trailing word [expectedPrefix] with [replacement]
     * using the same delete-by-code-points plus `commitText` batch edit and re-checks as
     * [commitSuggestion], but without the auto-space: the separator the user just pressed follows
     * through the ordinary input path. Returns false without editing if any check fails (default).
     */
    fun replaceTypedWord(expectedPrefix: String, replacement: String): Boolean = false

    /**
     * Undoes the last autocorrection: where [insertedForm] + [separator] stands immediately before
     * the cursor, puts [typedForm] + [separator] back, in one batch edit.
     *
     * The suffix match is the position check, and stricter than an offset: an offset can coincide
     * again after unrelated edits, the exact text cannot. Returns false without editing when the
     * text before the cursor is no longer what the replacement left there (default).
     */
    fun revertTypedWord(insertedForm: String, separator: String, typedForm: String): Boolean = false

    /**
     * NEXT_WORD context from the live cache. See [TatarWordUtils.extractNextWordContext] for the rule.
     * Defaults to "", so NEXT_WORD never fires.
     */
    fun cachedNextWordContext(): String = ""

    /**
     * The committed word before the just-completed trailing word: A in a just-typed "A B ". Read
     * when the trailing word has just become empty, so the tail ends with the separator after B.
     * "" when there is no such word or the cache cannot prove it (a window cut off before the
     * text start). Defaults to "", so no pair is ever learned.
     */
    fun cachedWordBeforeTrailingWord(): String = ""

    /**
     * True when the cursor sits where a new sentence begins (field start, or sentence-ending
     * punctuation followed by spaces), by [TatarWordUtils.isSentenceStartContext]'s rules,
     * cache-start provenance included. Defaults to false: no sentence-start predictions.
     */
    fun isAtSentenceStart(): Boolean = false

    /**
     * Commits a predicted next word. Unlike [commitSuggestion] and [replaceTypedWord] it deletes
     * nothing (NEXT_WORD fires only on an empty prefix) and only inserts, with the same auto-space
     * rule as an accepted suggestion.
     *
     * Re-checked against the live cache: collapsed selection, no letter right after the cursor, an
     * empty trailing word (otherwise the tap is stale), and the live context word from
     * [cachedNextWordContext] equal to [expectedContextWord]. When both are empty (a sentence
     * start), the live position must still be a sentence start ([isAtSentenceStart]), so a tap
     * after ", " commits nothing. Defaults to false.
     */
    fun commitPredictedWord(expectedContextWord: String, suggestion: String): Boolean = false

    /**
     * Commit path of a glide lift: [commitPredictedWord]'s live re-checks without the
     * sentence-start requirement for an empty context. A gesture ends at the cursor the user is
     * looking at, so there is no stale strip to guard against, and an empty-context position that
     * is not a sentence start ("сүз ? ") is a valid glide target.
     *
     * A glide commits no auto-space: the gesture is typing, not accepting a suggestion. When the
     * cursor stands right after a word character, one space is prepended (gliding word after word
     * produces "сәләм дөнья"); after whitespace, punctuation or at a field start nothing is.
     * [chainedAfter] is the only trailing word tolerated: the word the previous glide of the chain
     * committed (still in its undo window). Any other trailing word is a half-typed prefix and
     * refuses the commit.
     *
     * Returns [GLIDE_COMMIT_REFUSED] (no edit, the default), [GLIDE_COMMIT_BARE] or
     * [GLIDE_COMMIT_PREPENDED]; the undo needs to know whether the chain space was inserted.
     */
    fun commitGlideWord(expectedContextWord: String, suggestion: String, chainedAfter: String?): Int =
        GLIDE_COMMIT_REFUSED

    /**
     * Replaces the word a glide just committed with the tapped alternative, in place: no space is
     * added or removed, and [prependedSpace] says whether the committed text carried the chain
     * space. The suffix match ([committedWord] right before the cursor) is the position check, as
     * in [revertTypedWord]; a stale tap edits nothing. Returns false without editing (default).
     */
    fun replaceGlideLiftedWord(committedWord: String, alternative: String, prependedSpace: Boolean): Boolean = false

    /**
     * Whole-word undo of a glide lift: one backspace right after the lift deletes the committed
     * word, including the chain space when [prependedSpace] is set, and commits nothing back.
     * Same position check as [replaceGlideLiftedWord]; false without an edit on failure (default).
     */
    fun deleteGlideLiftedWord(committedWord: String, prependedSpace: Boolean): Boolean = false

    companion object {
        /** [commitGlideWord] refused: nothing was edited. */
        const val GLIDE_COMMIT_REFUSED = 0

        /** [commitGlideWord] committed the bare word (no chain space needed). */
        const val GLIDE_COMMIT_BARE = 1

        /** [commitGlideWord] committed " " + word (the cursor stood right after a word). */
        const val GLIDE_COMMIT_PREPENDED = 2
    }
}

/**
 * Reads the live value of `PREF_TATAR_AUTOCORRECT`. A seam, so the controller needs no preferences
 * and JVM tests can flip the setting between two keystrokes exactly as a user can.
 */
fun interface AutocorrectGate {
    fun isOn(): Boolean
}

/**
 * Reads the live value of `PREF_GLIDE_TYPING`, like [AutocorrectGate]. Read at every gesture, so
 * flipping the setting takes effect on the next glide.
 */
fun interface GlideGate {
    fun isOn(): Boolean
}

/**
 * Reads the keyboard's shift state for the glide commit's casing: shift (manual or automatic)
 * gives [TatarWordUtils.PrefixCasing.INITIAL_CAPS], Caps Lock gives
 * [TatarWordUtils.PrefixCasing.ALL_CAPS], otherwise [TatarWordUtils.PrefixCasing.LOWER]. A seam,
 * so JVM tests can flip shift.
 */
fun interface ShiftStateGate {
    fun glideCasing(): TatarWordUtils.PrefixCasing
}

/**
 * Reads the live value of `PREF_EMOJI_SUGGESTIONS`, like [AutocorrectGate], for the emoji cell of
 * the NEXT_WORD strip. Read on every fill, so flipping the setting takes effect on the next strip.
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
 * Only explicit enables are reported. A preparation started because the controller became eligible
 * was never asked for by the user and may fail silently; one started by an OFF -> ON transition
 * answers a switch the user just flipped, and silence would look like the setting did nothing.
 */
fun interface DictionaryUnavailableListener {
    fun onDictionaryUnavailableAfterExplicitEnable()
}
