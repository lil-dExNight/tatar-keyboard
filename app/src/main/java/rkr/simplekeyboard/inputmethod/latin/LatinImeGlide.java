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

/**
 * Whole-word undo for glide typing: a backspace pressed immediately after a glide commit deletes
 * the whole committed word (and the leading space the commit prepended) instead of one character,
 * as Gboard does. Same shape and follow-ups as
 * {@link LatinImeAutocorrect#maybeRevertTatarAutocorrection}.
 */
final class LatinImeGlide {

    private LatinImeGlide() {
        // Static namespace only.
    }

    /**
     * Routes the gesture-undo before the ordinary backspace. Returns true when it fired, in which
     * case the key press is fully handled and the ordinary backspace path never runs.
     *
     * <p>After a successful undo the follow-ups of a backspace still run: the shift state is
     * recomputed, the suggestion strip is rebuilt, and the keyboard state machine sees the key press.
     */
    static boolean maybeUndoGlideCommit(final LatinIME ime, final Event event) {
        if (ime.mSuggestionsController == null || event.mKeyCode != Constants.CODE_DELETE) {
            return false;
        }
        if (!ime.mSuggestionsController.maybeUndoGlideCommit()) {
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
