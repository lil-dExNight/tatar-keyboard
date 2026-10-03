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
 * The undo-autocorrect window of [SuggestionsController]: at most one replacement is armed (made,
 * its separator still on its way to the editor) and at most one is revertable (a backspace may
 * still undo it). The editor calls that perform the replacement and its undo stay in the
 * controller; a source-contract test pins their call sites there.
 */
internal class RevertWindow {

    /**
     * One replacement, as far as the undo is concerned. There is no history: at most one of these
     * exists at a time and it is dropped, never stacked.
     *
     * [requiresAutocorrectGate] is true for a statistical correction (the undo dies with the
     * autocorrect switch) and false for a text-shortcut expansion (a user-managed pair has no
     * switch; its undo works whenever the window is open).
     */
    internal class Replacement(
        val typedForm: String,
        val insertedForm: String,
        val separator: String,
        val sessionId: Long,
        val requiresAutocorrectGate: Boolean,
    ) {
        /** Deliberately mute: this object carries the user's text. */
        override fun toString(): String = "Replacement"
    }

    /**
     * A replacement that has been made but whose separator has not been committed yet. It survives
     * EXACTLY ONE [advance] — the one carrying that separator, which is part of the same user
     * action — and becomes [revertable] there. Every later event finds [revertable] and drops it.
     */
    private var armedReplacement: Replacement? = null

    /** The one replacement a backspace may still undo. Null means the window has closed. */
    private var revertable: Replacement? = null

    /**
     * Arms a replacement that has just been committed; whatever was revertable before is dropped —
     * the window holds at most one undo.
     */
    fun arm(typedForm: String, insertedForm: String, separatorCodePoint: Int, sessionId: Long,
            requiresAutocorrectGate: Boolean) {
        armedReplacement = Replacement(
            typedForm, insertedForm, separatorString(separatorCodePoint), sessionId,
            requiresAutocorrectGate,
        )
        revertable = null
    }

    /**
     * The revertable replacement without taking it, or null when the window is closed. The strip's
     * keep-typed cell reads it to paint and to recognize its own tap; the undo itself still goes
     * through [take].
     */
    fun peek(): Replacement? = revertable

    /**
     * Takes the revertable replacement out, closing the whole window: the state is dropped BEFORE
     * the editor is asked to do anything, so a refused undo cannot be retried and the second
     * backspace deletes a character like any other.
     */
    fun take(): Replacement? {
        val replacement = revertable
        revertable = null
        armedReplacement = null
        return replacement
    }

    /**
     * Moves the undo window forward by one text change.
     *
     * A replacement is armed while its own separator is still on its way to the editor; that one
     * change completes it. Any other text change (a typed character, an accepted suggestion, a
     * deletion) closes the window.
     */
    fun advance(sessionId: Long) {
        val armed = armedReplacement
        if (armed != null) {
            armedReplacement = null
            revertable = if (armed.sessionId == sessionId) armed else null
            return
        }
        revertable = null
    }

    /** Drops the undo window outright: a field, subtype, selection or setting boundary. */
    fun clear() {
        armedReplacement = null
        revertable = null
    }

    private fun separatorString(codePoint: Int): String =
        if (Character.isValidCodePoint(codePoint)) String(Character.toChars(codePoint)) else ""
}
