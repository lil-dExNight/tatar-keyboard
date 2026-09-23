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
 * The D3 autocorrect interception seam of {@link LatinIME#onEvent} (T2 split, part 2 of 3):
 * the two probes that run BEFORE the input logic sees the event — a separator about to finish
 * a word may replace it first, and a backspace right after a replacement undoes it. Both are
 * static and take the service, so the bodies moved here verbatim; the decision itself lives in
 * {@code SuggestionsController}.
 */
final class LatinImeAutocorrect {
    private LatinImeAutocorrect() {
        // Static methods only.
    }

    /**
     * Corrects the word a separator is about to finish (D3), BEFORE that separator reaches the input
     * logic.
     *
     * <p>Before, not after, on purpose: at this instant the editor is in exactly the state an
     * accepted suggestion needs — a trailing word with a collapsed cursor right behind it — so the
     * correction is the same single delete + commit, and the separator then travels the ordinary
     * path with the auto-space rule, the double-space gesture and the shift update all untouched.
     *
     * <p>The two conditions are a conjunction: the code point must be a word separator of the live
     * layout AND one of the separators D3 fires on at all
     * ({@link TatarWordUtils#isAutocorrectSeparator}, i.e. «пробел или пунктуация»). Everything else
     * — including Enter and Tab, which are word separators too — is left alone. The controller
     * decides whether anything is actually replaced; this method only recognizes the moment.
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
        ime.mSuggestionsController.maybeAutocorrectBeforeSeparator(codePoint);
    }

    /**
     * A backspace pressed immediately after an autocorrection restores what the user typed instead
     * of deleting a character (D3). Returns true when it did, in which case the key press is fully
     * handled and the ordinary backspace path never runs.
     *
     * <p>What follows a successful revert is exactly what a backspace does apart from the deletion:
     * the shift state is recomputed (the restored word can change auto-caps), the band is re-derived
     * from the new text, and the keyboard's own state machine still sees the key press. The
     * suggestions offer is deliberately skipped — a delete carries
     * {@link Event#NOT_A_CODE_POINT}, which is never a word separator, so the call would be a no-op.
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
