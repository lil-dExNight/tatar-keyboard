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
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSearchQuery;
import rkr.simplekeyboard.inputmethod.latin.inputlogic.InputLogic;

/**
 * The emoji-search event routing of {@link LatinIME#onEvent}: while the search is open, key
 * presses grow the query instead of reaching the editor. The query itself lives on the service
 * ({@code LatinIME#mEmojiSearchQuery}) because the lifecycle code reads it there.
 */
final class LatinImeEmojiSearch {
    private LatinImeEmojiSearch() {
        // Static methods only.
    }

    /**
     * Routes one key press into the emoji-search query instead of into the editor, and returns true
     * when it did. While the search is open {@link InputLogic} is never called, so nothing typed in
     * the search reaches the application's text field. A backspace on an empty query closes the
     * search.
     *
     * <p>The keyboard state machine still sees the event, so shift and the symbols/letters switch
     * work as usual. Auto-caps is reported as off ({@code 0}, no {@code TextUtils.CAP_MODE_*} bit):
     * it is derived from the editor's text, so leaving it on would capitalize every query letter.
     */
    static boolean maybeRouteToEmojiSearch(final LatinIME ime, final Event event) {
        final EmojiSearchQuery query = ime.mEmojiSearchQuery;
        if (query == null || !ime.mKeyboardSwitcher.isEmojiSearchShown()) {
            return false;
        }
        final boolean changed;
        if (event.mKeyCode == Constants.CODE_DELETE) {
            if (!query.backspace()) {
                ime.onEmojiSearchClosed();
                ime.mKeyboardSwitcher.onEvent(event, ime.getCurrentAutoCapsState(),
                        ime.getCurrentRecapitalizeState());
                return true;
            }
            changed = true;
        } else if (event.mCodePoint != Event.NOT_A_CODE_POINT) {
            changed = query.appendCodePoint(event.mCodePoint);
        } else {
            // Delete is handled above; every other key that carries no code point (the language
            // key, the emoji key) is left to the ordinary path so the search never swallows it.
            return false;
        }
        if (changed) {
            updateEmojiSearchView(ime);
        }
        ime.mKeyboardSwitcher.onEvent(event, ime.getCurrentAutoCapsState(),
                ime.getCurrentRecapitalizeState());
        return true;
    }

    /** See {@link KeyboardSwitcher#setEmojiSearchQuery}. */
    static void updateEmojiSearchView(final LatinIME ime) {
        final EmojiSearchQuery query = ime.mEmojiSearchQuery;
        if (query != null) {
            ime.mKeyboardSwitcher.setEmojiSearchQuery(query.text());
        }
    }
}
