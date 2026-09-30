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

package rkr.simplekeyboard.inputmethod.latin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A plain backspace goes through [RichInputConnection.deleteTextBeforeCursor], which must move
 * both `mExpectedSelStart` and `mExpectedSelEnd`, as every other cursor-moving mutator does. If
 * only the start moves, then until the framework's next `onUpdateSelection` the keyboard believes
 * a one-character selection is active: a suggestion tap is refused, and the keyboard's own edit is
 * taken for an external cursor move. The delete-then-commit paths hide this because `commitText`
 * repairs the pair one line later; a bare backspace does not.
 *
 * No Android objects are touched: `deleteTextBeforeCursor` only reads `isConnected()` (false with a
 * null connection) and both `updateSelection` and `hasSelection` are plain field arithmetic.
 */
class BackspaceSelectionDesyncTest {

    /** The keyboard's own backspace must leave a collapsed cursor, not a phantom selection. */
    @Test
    fun backspaceLeavesTheCursorCollapsed() {
        val connection = RichInputConnection(null)
        // Cursor sitting collapsed at offset 10, exactly as it is after typing "какоц".
        connection.updateSelection(10, 10)

        // One plain backspace: InputLogic.handleBackspaceEvent -> deleteTextBeforeCursor(1),
        // with no commitText behind it.
        connection.deleteTextBeforeCursor(1)

        assertEquals("selection start moved back", 9, connection.expectedSelectionStart)
        assertEquals("selection end must follow the start", 9, connection.expectedSelectionEnd)
        assertFalse(
            "after a plain backspace the keyboard believes text is selected",
            connection.hasSelection(),
        )
    }

    /**
     * Consequence 1. `InputLogic.replaceTrailingWord` refuses outright when
     * `hasSelection()` is true, and that is the single gate every accepted
     * suggestion passes through. With a phantom selection the tap is rejected before the trailing
     * word is even looked at: the cell highlights, the text does not change.
     */
    @Test
    fun aTapIsNotRejectedByAPhantomSelectionAfterBackspace() {
        val connection = RichInputConnection(null)
        connection.updateSelection(15, 15)

        connection.deleteTextBeforeCursor(1)

        assertFalse(
            "replaceTrailingWord() returns false at its hasSelection() gate, so the tap commits nothing",
            connection.hasSelection(),
        )
    }

    /**
     * Consequence 2. `LatinIME.onUpdateSelection` classifies a cursor move as EXTERNAL
     * by comparing both ends against the expected pair. The framework
     * reports the keyboard's own backspace as a collapsed cursor at 9,9 — which no longer matches
     * the expected 9,10 — so the keyboard's own edit is mistaken for the user tapping elsewhere.
     */
    @Test
    fun theKeyboardsOwnBackspaceIsNotReportedAsAnExternalCursorMove() {
        val connection = RichInputConnection(null)
        connection.updateSelection(10, 10)

        connection.deleteTextBeforeCursor(1)

        // What the framework actually reports for a one-character delete at offset 10.
        val reportedSelStart = 9
        val reportedSelEnd = 9
        val externalMove = reportedSelStart != connection.expectedSelectionStart ||
            reportedSelEnd != connection.expectedSelectionEnd

        assertFalse(
            "the keyboard's own backspace is classified as an external cursor move, " +
                "which drops the in-flight lookup and clears the band",
            externalMove,
        )
    }
}
