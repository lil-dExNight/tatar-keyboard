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

package rkr.simplekeyboard.inputmethod.latin;

import rkr.simplekeyboard.inputmethod.event.Event;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils;

/**
 * The separator-time hooks of {@link LatinIME#onEvent}, which run before the input logic sees the
 * event: a separator about to finish a word may replace it first — with the user's saved
 * text-shortcut expansion, else with a statistical correction — and a backspace right after a
 * replacement undoes it. The decisions live in {@code SuggestionsController}.
 */
final class LatinImeAutocorrect {
    private LatinImeAutocorrect() {
        // Static methods only.
    }

    /**
     * Replaces the word a separator is about to finish, before that separator reaches the input
     * logic.
     *
     * <p>Before, not after: at this point the editor has a trailing word with a collapsed cursor
     * behind it, which is what an accepted suggestion needs, so the replacement is the same delete +
     * commit, and the separator then takes the ordinary path (auto-space, double-space, shift).
     *
     * <p>The code point must be a word separator of the current layout and an autocorrect
     * separator ({@link TatarWordUtils#isAutocorrectSeparator}: space or punctuation). Enter and Tab
     * are left alone. The controller decides whether anything is replaced. The text
     * shortcut (the user's own pair) is tried before the statistical correction.
     */
    static void maybeAutocorrectTatarWord(final LatinIME ime, final Event event) {
        if (ime.mSuggestionsController == null) {
            return;
        }
        final int codePoint = event.mCodePoint;
        if (codePoint == Event.NOT_A_CODE_POINT
                || !TatarWordUtils.isAutocorrectSeparator(codePoint)
                || !ime.mSettings.getCurrent().isWordSeparator(codePoint)) {
            return;
        }
        if (ime.mSuggestionsController.maybeExpandShortcutBeforeSeparator(codePoint)) {
            return;
        }
        ime.mSuggestionsController.maybeAutocorrectBeforeSeparator(codePoint);
    }

    /**
     * A backspace pressed immediately after an autocorrection restores what the user typed instead
     * of deleting a character. Returns true when it did; the ordinary backspace path then never runs.
     *
     * <p>After a successful undo the follow-ups of a backspace still run: the shift state is
     * recomputed, the suggestion strip is rebuilt from the new text, and the keyboard state machine
     * sees the key press. The suggestions offer is skipped: a delete carries
     * {@link Event#NOT_A_CODE_POINT}, which is never a word separator, so it would be a no-op.
     */
    static boolean maybeRevertTatarAutocorrection(final LatinIME ime, final Event event) {
        if (ime.mSuggestionsController == null || event.mKeyCode != Constants.CODE_DELETE) {
            return false;
        }
        if (!ime.mSuggestionsController.maybeRevertAutocorrect()) {
            return false;
        }
        ime.mKeyboardSwitcher.requestUpdatingShiftState(ime.getCurrentAutoCapsState(),
                ime.getCurrentRecapitalizeState());
        ime.mSuggestionsController.onTextChanged();
        ime.mKeyboardSwitcher.onEvent(event, ime.getCurrentAutoCapsState(),
                ime.getCurrentRecapitalizeState());
        return true;
    }
}
