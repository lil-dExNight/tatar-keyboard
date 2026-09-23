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
 * The emoji-search event routing of {@link LatinIME#onEvent} (T2 split, part 2 of 3): while
 * the search is open, key presses grow the query instead of reaching the editor. Static
 * methods taking the service, so the bodies moved here verbatim; the query itself lives on the
 * service ({@code LatinIME#mEmojiSearchQuery}) because pinned lifecycle code reads it there.
 */
final class LatinImeEmojiSearch {
    private LatinImeEmojiSearch() {
        // Static methods only.
    }

    /**
     * Routes one key press into the emoji-search query instead of into the editor, and returns true
     * when it did. This is the single seam that makes the keyboard type "into itself": while the
     * search is open the query grows here and {@link InputLogic} is never called, so no character
     * the user types while searching can reach the application's text field and no marked region is
     * ever started there. A backspace on an already-empty query means "leave the search".
     *
     * <p>The keyboard's own state machine still sees the event, so shift and the symbols/letters
     * switch behave exactly as they do while typing. Auto-caps is deliberately reported as OFF
     * ({@code 0}, no {@code TextUtils.CAP_MODE_*} bit): it is derived from the editor's text, which
     * the search never changes, so leaving it on would re-arm shift after every letter and turn the
     * whole query into capitals.
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

    /** Hands the current query text to the search bands, which re-run the match and redraw. */
    static void updateEmojiSearchView(final LatinIME ime) {
        final EmojiSearchQuery query = ime.mEmojiSearchQuery;
        if (query != null) {
            ime.mKeyboardSwitcher.setEmojiSearchQuery(query.text());
        }
    }
}
