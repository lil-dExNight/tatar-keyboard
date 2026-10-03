/*
 * Copyright (C) 2013 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2025 Camille019
 * Copyright (C) 2023 Md. Rifat Hasan Jihan
 * Copyright (C) 2021 wittmane
 * Copyright (C) 2019 Emmanuel
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

package rkr.simplekeyboard.inputmethod.latin.inputlogic;

import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import rkr.simplekeyboard.inputmethod.event.Event;
import rkr.simplekeyboard.inputmethod.event.InputTransaction;
import rkr.simplekeyboard.inputmethod.latin.LatinIME;
import rkr.simplekeyboard.inputmethod.latin.RichInputConnection;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.common.StringUtils;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiTextUtils;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.InputTypeUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.RecapitalizeStatus;

/**
 * This class manages the input logic.
 */
public final class InputLogic {
    // Must be long enough for a deliberate double tap on space, but shorter than a pause
    // between sentences. Matches AOSP config_double_space_period_timeout; the system
    // double tap timeout (~300 ms) is too short for this gesture.
    private static final long DOUBLE_SPACE_PERIOD_TIMEOUT = 1100;

    // Appended to an accepted suggestion so the next word can be typed straight away.
    private static final String AUTO_SPACE = " ";

    // The value of mAutoSpaceCursor when no auto-space can be replaced.
    private static final int NO_AUTO_SPACE = -1;

    // TODO : Remove this member when we can.
    final LatinIME mLatinIME;

    // This has package visibility so it can be accessed from InputLogicHandler.
    public final RichInputConnection mConnection;
    private final RecapitalizeStatus mRecapitalizeStatus = new RecapitalizeStatus();

    // Time of the last committed space, for double-space-to-period detection.
    private long mLastSpaceDownTime;
    // Whether the last input was a double-space-to-period, for revert on backspace.
    private boolean mJustDoubleSpaced;
    // Cursor position right after an auto-space this class appended, or NO_AUTO_SPACE. A
    // punctuation mark typed exactly there replaces the space.
    private int mAutoSpaceCursor = NO_AUTO_SPACE;
    // Cursor position right after a glide commit, or NO_AUTO_SPACE. A letter or digit typed
    // exactly there gets a space before it, so typing on after a glide starts a new word.
    private int mPhantomSpaceCursor = NO_AUTO_SPACE;

    /**
     * Create a new instance of the input logic.
     * @param latinIME the instance of the parent LatinIME. We should remove this when we can.
     * dictionary.
     */
    public InputLogic(final LatinIME latinIME) {
        mLatinIME = latinIME;
        mConnection = new RichInputConnection(latinIME);
    }

    /**
     * Initializes the input logic for input in an editor.
     *
     * Call this when input starts or restarts in some editor (typically, in onStartInputView).
     */
    public void startInput() {
        mRecapitalizeStatus.disable(); // Do not perform recapitalize until the cursor is moved once
        // Double-space and auto-space state must not leak between editors.
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
        mAutoSpaceCursor = NO_AUTO_SPACE;
        mPhantomSpaceCursor = NO_AUTO_SPACE;
    }

    public void clearCaches() {
        mConnection.clearCaches();
    }

    /** See {@link RichInputConnection#clearTextCaches}. */
    public void clearTextCaches() {
        mConnection.clearTextCaches();
    }

    /**
     * Call this when the subtype changes.
     */
    public void onSubtypeChanged() {
        startInput();
    }

    /**
     * React to a string input.
     *
     * This is triggered by keys that input many characters at once, like the ".com" key or
     * some additional keys for example.
     *
     * @param settingsValues the current values of the settings.
     * @param event the input event containing the data.
     * @return the complete transaction object
     */
    public InputTransaction onTextInput(final SettingsValues settingsValues, final Event event) {
        final String rawText = event.getTextToCommit().toString();
        final InputTransaction inputTransaction = new InputTransaction(settingsValues);
        final String text = performSpecificTldProcessingOnTextInput(rawText);
        mConnection.commitText(text, 1);
        // The committed text (".com" key, paste) may itself end in ". " — a pending
        // double-space revert would corrupt it, so the state must be dropped.
        mJustDoubleSpaced = false;
        mAutoSpaceCursor = NO_AUTO_SPACE;
        mPhantomSpaceCursor = NO_AUTO_SPACE;
        // Space state must be updated before calling updateShiftState
        inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
        return inputTransaction;
    }

    /**
     * Consider an update to the cursor position. Evaluate whether this update has happened as
     * part of normal typing or whether it was an explicit cursor move by the user. In any case,
     * do the necessary adjustments.
     * @param newSelStart new selection start
     * @param newSelEnd new selection end
     */
    public void onUpdateSelection(final int newSelStart, final int newSelEnd) {
        if (newSelStart != mConnection.getExpectedSelectionStart()
                || newSelEnd != mConnection.getExpectedSelectionEnd()) {
            // The cursor moved in a way the keyboard did not cause (tap, arrow keys, app edit):
            // a pending double-space-to-period revert would target unrelated text, and a stale
            // space timestamp could trigger a period at the new position. Drop both, like AOSP
            // does in resetEntireInputState() on unexpected cursor moves.
            mJustDoubleSpaced = false;
            mLastSpaceDownTime = 0;
            mAutoSpaceCursor = NO_AUTO_SPACE;
            mPhantomSpaceCursor = NO_AUTO_SPACE;
        }
        mConnection.updateSelection(newSelStart, newSelEnd);
    }

    /**
     * Call after the keyboard itself moved the cursor or selection (space slide, delete swipe).
     * Those moves are expected, so {@link #onUpdateSelection} keeps the space state; this drops it.
     */
    public void onKeyboardCursorMove() {
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
        mAutoSpaceCursor = NO_AUTO_SPACE;
        mPhantomSpaceCursor = NO_AUTO_SPACE;
    }

    public void reloadTextCache() {
        mConnection.reloadTextCache();

        mRecapitalizeStatus.enable();
        mRecapitalizeStatus.stop();
    }

    /**
     * React to a code input. It may be a code point to insert, or a symbolic value that influences
     * the keyboard behavior.
     *
     * Typically, this is called whenever a key is pressed on the software keyboard. This is not
     * the entry point for gesture input; see the onBatchInput* family of functions for this.
     *
     * @param settingsValues the current settings values.
     * @param event the event to handle.
     * @return the complete transaction object
     */
    public InputTransaction onCodeInput(final SettingsValues settingsValues, final Event event) {
        final InputTransaction inputTransaction = new InputTransaction(settingsValues);

        Event currentEvent = event;
        while (null != currentEvent) {
            if (currentEvent.isConsumed()) {
                handleConsumedEvent(currentEvent);
            } else if (currentEvent.isFunctionalKeyEvent()) {
                handleFunctionalEvent(currentEvent, inputTransaction);
            } else {
                handleNonFunctionalEvent(currentEvent, inputTransaction);
            }
            currentEvent = currentEvent.mNextEvent;
        }
        return inputTransaction;
    }

    /**
     * Handle a consumed event.
     *
     * Consumed events represent events that have already been consumed, typically by the
     * combining chain.
     *
     * @param event The event to handle.
     */
    private void handleConsumedEvent(final Event event) {
        // A consumed event may have text to commit and an update to the composing state, so
        // we evaluate both. With some combiners, it's possible than an event contains both
        // and we enter both of the following if clauses.
        final CharSequence textToCommit = event.getTextToCommit();
        if (!TextUtils.isEmpty(textToCommit)) {
            mConnection.commitText(textToCommit, 1);
            // Committed combiner text invalidates a pending double-space revert and auto-space.
            mJustDoubleSpaced = false;
            mAutoSpaceCursor = NO_AUTO_SPACE;
            mPhantomSpaceCursor = NO_AUTO_SPACE;
        }
    }

    /**
     * Handle a functional key event.
     *
     * A functional event is a special key, like delete, shift, emoji, or the settings key.
     * Non-special keys are those that generate a single code point.
     * This includes all letters, digits, punctuation, separators, emoji. It excludes keys that
     * manage keyboard-related stuff like shift, language switch, settings, layout switch, or
     * any key that results in multiple code points like the ".com" key.
     *
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private void handleFunctionalEvent(final Event event, final InputTransaction inputTransaction) {
        switch (event.mKeyCode) {
            case Constants.CODE_DELETE:
                handleBackspaceEvent(event, inputTransaction);
                // Backspace is a functional key, but it affects the contents of the editor.
                break;
            case Constants.CODE_SHIFT:
                performRecapitalization(inputTransaction.mSettingsValues);
                inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
                break;
            case Constants.CODE_CAPSLOCK:
                // Note: Changing keyboard to shift lock state is handled in
                // {@link KeyboardSwitcher#onEvent(Event)}.
                break;
            case Constants.CODE_SYMBOL_SHIFT:
                // Note: Calling back to the keyboard on the symbol Shift key is handled in
                // {@link #onPressKey(int,int,boolean)} and {@link #onReleaseKey(int,boolean)}.
                break;
            case Constants.CODE_SWITCH_ALPHA_SYMBOL:
                // Note: Calling back to the keyboard on symbol key is handled in
                // {@link #onPressKey(int,int,boolean)} and {@link #onReleaseKey(int,boolean)}.
                break;
            case Constants.CODE_SETTINGS:
                onSettingsKeyPressed();
                break;
            case Constants.CODE_PASTE:
                // Before the paste, so the text change it causes already sees a dirty run.
                mLatinIME.onBeforeClipboardPaste();
                mConnection.pasteClipboard();
                break;
            case Constants.CODE_SELECT_ALL:
                mConnection.performContextMenuAction(android.R.id.selectAll);
                break;
            case Constants.CODE_CUT:
                mConnection.performContextMenuAction(android.R.id.cut);
                break;
            case Constants.CODE_COPY:
                mConnection.performContextMenuAction(android.R.id.copy);
                break;
            case Constants.CODE_PASTE_CONTEXT_MENU:
                mConnection.performContextMenuAction(android.R.id.paste);
                break;
            case Constants.CODE_CURSOR_LEFT:
                moveCursorFromEditMenu(-1);
                break;
            case Constants.CODE_CURSOR_RIGHT:
                moveCursorFromEditMenu(1);
                break;
            case Constants.CODE_ACTION_NEXT:
                performEditorAction(EditorInfo.IME_ACTION_NEXT);
                break;
            case Constants.CODE_ACTION_PREVIOUS:
                performEditorAction(EditorInfo.IME_ACTION_PREVIOUS);
                break;
            case Constants.CODE_LANGUAGE_SWITCH:
                handleLanguageSwitchKey();
                break;
            case Constants.CODE_EMOJI:
                // The emoji key never edits the editor: it only asks for the emoji panel to
                // replace the keyboard surface. The surface swap and insets happen there.
                mLatinIME.showEmojiPanel();
                break;
            case Constants.CODE_SHIFT_ENTER:
                sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON);
                // Shift + Enter is not supported in all devices
                break;
            default:
                throw new RuntimeException("Unknown key code : " + event.mKeyCode);
        }
    }

    /**
     * Handle an event that is not a functional event.
     *
     * These events are generally events that cause input, but in some cases they may do other
     * things like trigger an editor action.
     *
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private void handleNonFunctionalEvent(final Event event,
            final InputTransaction inputTransaction) {
        switch (event.mCodePoint) {
            case Constants.CODE_ENTER:
                final EditorInfo editorInfo = getCurrentInputEditorInfo();
                final int imeOptionsActionId =
                        InputTypeUtils.getImeOptionsActionIdFromEditorInfo(editorInfo);
                if (InputTypeUtils.IME_ACTION_CUSTOM_LABEL == imeOptionsActionId) {
                    // Either we have an actionLabel and we should performEditorAction with
                    // actionId regardless of its value.
                    performEditorAction(editorInfo.actionId);
                } else if (EditorInfo.IME_ACTION_NONE != imeOptionsActionId) {
                    // We didn't have an actionLabel, but we had another action to execute.
                    // EditorInfo.IME_ACTION_NONE explicitly means no action. In contrast,
                    // EditorInfo.IME_ACTION_UNSPECIFIED is the default value for an action, so it
                    // means there should be an action and the app didn't bother to set a specific
                    // code for it - presumably it only handles one. It does not have to be treated
                    // in any specific way: anything that is not IME_ACTION_NONE should be sent to
                    // performEditorAction.
                    performEditorAction(imeOptionsActionId);
                } else {
                    // No action label, and the action from imeOptions is NONE: this is a regular
                    // enter key that should input a carriage return.
                    handleNonSpecialCharacterEvent(event, inputTransaction);
                }
                break;
            default:
                handleNonSpecialCharacterEvent(event, inputTransaction);
                break;
        }
    }

    /**
     * Handle inputting a code point to the editor.
     *
     * Non-special keys are those that generate a single code point.
     * This includes all letters, digits, punctuation, separators, emoji. It excludes keys that
     * manage keyboard-related stuff like shift, language switch, settings, layout switch, or
     * any key that results in multiple code points like the ".com" key.
     *
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private void handleNonSpecialCharacterEvent(final Event event,
            final InputTransaction inputTransaction) {
        final int codePoint = event.mCodePoint;
        if (inputTransaction.mSettingsValues.isWordSeparator(codePoint)
                || Character.getType(codePoint) == Character.OTHER_SYMBOL) {
            handleSeparatorEvent(event, inputTransaction);
        } else {
            handleNonSeparatorEvent(event);
        }
    }

    /**
     * Handle a non-separator. A letter or digit typed right where a glide commit left the cursor
     * gets a space before it ("дөнья" + "а" gives "дөнья а"), in one batch edit.
     * @param event The event to handle.
     */
    private void handleNonSeparatorEvent(final Event event) {
        final boolean afterGlide = mPhantomSpaceCursor != NO_AUTO_SPACE
                && mPhantomSpaceCursor == mConnection.getExpectedSelectionStart();
        mJustDoubleSpaced = false;
        mAutoSpaceCursor = NO_AUTO_SPACE;
        mPhantomSpaceCursor = NO_AUTO_SPACE;
        if (afterGlide && Character.isLetterOrDigit(event.mCodePoint)
                && !mConnection.hasSelection()) {
            // A digit goes through commitText here, not as a key event: key events ignore the
            // batch, and the space and the digit must land together.
            mConnection.beginBatchEdit();
            try {
                mConnection.commitText(new StringBuilder(3).append(' ')
                        .appendCodePoint(event.mCodePoint), 1);
            } finally {
                mConnection.endBatchEdit();
            }
            return;
        }
        sendKeyCodePoint(event.mCodePoint);
    }

    /**
     * Handle input of a separator code point.
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private void handleSeparatorEvent(final Event event, final InputTransaction inputTransaction) {
        // The auto-space can be replaced only by the very next key, typed at the same position.
        final boolean afterAutoSpace = mAutoSpaceCursor != NO_AUTO_SPACE
                && mAutoSpaceCursor == mConnection.getExpectedSelectionStart();
        mAutoSpaceCursor = NO_AUTO_SPACE;
        mPhantomSpaceCursor = NO_AUTO_SPACE;
        if (event.mCodePoint == Constants.CODE_SPACE) {
            if (TatarWordUtils.swallowsSpaceAtAutoSpace(afterAutoSpace,
                    mConnection.hasSelection(), mConnection.getCodePointBeforeCursor())) {
                // "сүз " + " " stays "сүз ": the auto-space already is the space. It counts as a
                // typed space, so a quick second one still gives "сүз. ".
                mJustDoubleSpaced = false;
                mLastSpaceDownTime = SystemClock.uptimeMillis();
                inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
                return;
            }
            if (tryDoubleSpacePeriod(inputTransaction.mSettingsValues)) {
                inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
                return;
            }
        } else {
            mJustDoubleSpaced = false;
            if (afterAutoSpace && TatarWordUtils.swapsWithAutoSpace(event.mCodePoint)
                    && !mConnection.hasSelection()
                    && mConnection.getCodePointBeforeCursor() == Constants.CODE_SPACE) {
                // "сүз " + "," gives "сүз, ": the mark takes the space's place and the space
                // moves after it, in one batch. The moved space stays replaceable, so "?!" or
                // "..." typed in a row stay together.
                mConnection.beginBatchEdit();
                try {
                    mConnection.deleteTextBeforeCursor(1);
                    mConnection.commitText(new StringBuilder(2).appendCodePoint(event.mCodePoint)
                            .append(' '), 1);
                } finally {
                    mConnection.endBatchEdit();
                }
                mAutoSpaceCursor = mConnection.getExpectedSelectionStart();
                inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
                return;
            }
        }
        sendKeyCodePoint(event.mCodePoint);

        inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
    }

    /**
     * Replace a quick second space with a period followed by a space, like AOSP does.
     *
     * Only triggers when the previous space was committed less than
     * {@link #DOUBLE_SPACE_PERIOD_TIMEOUT} ago, the field is not a password field, and the
     * cursor is preceded by exactly one space that follows a letter or digit.
     *
     * @param settingsValues the current settings values.
     * @return whether the period was committed (the space event is then fully handled).
     */
    private boolean tryDoubleSpacePeriod(final SettingsValues settingsValues) {
        final long now = SystemClock.uptimeMillis();
        if (now - mLastSpaceDownTime < DOUBLE_SPACE_PERIOD_TIMEOUT
                && settingsValues.mInputAttributes.mIsGeneralTextInput
                && mConnection.getCodePointBeforeCursor() == Constants.CODE_SPACE
                && Character.isLetterOrDigit(mConnection.getCodePointBeforeCursor(1))) {
            mConnection.beginBatchEdit();
            // The batch closes in finally, so an exception from a dying editor cannot leave the
            // nest level stuck. The exception itself still propagates.
            try {
                mConnection.deleteTextBeforeCursor(1);
                mConnection.commitText(". ", 1);
            } finally {
                mConnection.endBatchEdit();
            }
            mJustDoubleSpaced = true;
            mLastSpaceDownTime = 0;
            return true;
        }
        mLastSpaceDownTime = now;
        mJustDoubleSpaced = false;
        return false;
    }

    /**
     * Handle a press on the backspace key.
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private void handleBackspaceEvent(final Event event, final InputTransaction inputTransaction) {
        // In many cases after backspace, we need to update the shift state. Normally we need
        // to do this right away to avoid the shift state being out of date in case the user types
        // backspace then some other character very fast. However, in the case of backspace key
        // repeat, this can lead to flashiness when the cursor flies over positions where the
        // shift state should be updated, so if this is a key repeat, we update after a small delay.
        // Then again, even in the case of a key repeat, if the cursor is at start of text, it
        // can't go any further back, so we can update right away even if it's a key repeat.
        final int shiftUpdateKind =
                event.isKeyRepeat() && mConnection.getExpectedSelectionStart() > 0
                ? InputTransaction.SHIFT_UPDATE_LATER : InputTransaction.SHIFT_UPDATE_NOW;
        inputTransaction.requireShiftUpdate(shiftUpdateKind);
        mAutoSpaceCursor = NO_AUTO_SPACE;
        mPhantomSpaceCursor = NO_AUTO_SPACE;

        if (mConnection.hasSelection()) {
            mJustDoubleSpaced = false;
            mConnection.deleteSelectedText();
        } else {
            if (mJustDoubleSpaced
                    && mConnection.getCodePointBeforeCursor() == Constants.CODE_SPACE
                    && mConnection.getCodePointBeforeCursor(1) == Constants.CODE_PERIOD) {
                // Revert the double-space-to-period: restore the two spaces.
                mConnection.beginBatchEdit();
                try {
                    mConnection.deleteTextBeforeCursor(2);
                    mConnection.commitText("  ", 1);
                } finally {
                    mConnection.endBatchEdit();
                }
                mJustDoubleSpaced = false;
                return;
            }
            mJustDoubleSpaced = false;
            // A single backspace must delete a trailing emoji grapheme cluster whole rather than
            // leaving a fragment behind (a lone variation selector, half of a flag, a base stripped
            // of its skin-tone modifier). The length is measured purely from the already-cached
            // before-cursor text, so this adds no new IPC to the editor; the text is read, measured
            // and dropped, never stored or logged.
            final int emojiClusterLength = EmojiTextUtils.trailingEmojiClusterLength(
                    mConnection.getCachedTextBeforeCursor());
            if (emojiClusterLength > 0) {
                mConnection.deleteTextBeforeCursor(emojiClusterLength);
            } else {
                final int codePointBeforeCursor = mConnection.getCodePointBeforeCursor();
                if (codePointBeforeCursor == Constants.NOT_A_CODE) {
                    sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL);
                } else {
                    final int numChars = Character.isSupplementaryCodePoint(codePointBeforeCursor) ? 2 : 1;
                    mConnection.deleteTextBeforeCursor(numChars);
                }
            }
        }
    }

    /**
     * Deletes the last word before the cursor for the word-delete flick: the run of whitespace
     * right before the cursor plus the word before it, in one batch edit. A selection opened by
     * the swipe itself collapses back to its anchor end first — the flick deletes a word, never
     * a selection.
     *
     * A plain deletion gets no RevertWindow entry: deleted text is retyped, not reverted, and the
     * undo affordance stays specific to autocorrections.
     *
     * @return the number of chars deleted, or 0 when nothing was deleted (nothing deletable
     *         before the cursor, or no live connection).
     */
    public int deleteWordBeforeCursor() {
        if (mConnection.hasSelection()) {
            final int end = mConnection.getExpectedSelectionEnd();
            mConnection.setSelection(end, end);
        }
        final int deleteLength = TatarWordUtils.wordDeleteLengthBeforeCursor(
                mConnection.getCachedTextBeforeCursor(), mConnection.cacheReachedTextStart());
        if (deleteLength <= 0) {
            return 0;
        }
        mConnection.beginBatchEdit();
        // Connection check after opening the batch; see replaceTrailingWord.
        final boolean connected = mConnection.isConnected();
        try {
            if (connected) {
                mConnection.deleteTextBeforeCursor(deleteLength);
            }
        } finally {
            mConnection.endBatchEdit();
        }
        if (!connected) {
            return 0;
        }
        // Same housekeeping as a backspace: no double-space or auto-space state survives.
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
        mAutoSpaceCursor = NO_AUTO_SPACE;
        mPhantomSpaceCursor = NO_AUTO_SPACE;
        return deleteLength;
    }

    /**
     * Moves the cursor one step for the edit menu's arrows. With a selection the cursor collapses
     * to the edge in the arrow's direction, like a DPAD key; without one it steps one unicode
     * character. setSelection is the primary path: it is synchronous and keeps the text caches in
     * sync, unlike key events, which cross a different binder and ignore batch edits (see
     * {@link #sendDownUpKeyEvent}). Only an editor that reports no cursor position gets key
     * events.
     */
    private void moveCursorFromEditMenu(final int direction) {
        int steps = direction;
        if (TextUtils.getLayoutDirectionFromLocale(mLatinIME.getCurrentLayoutLocale())
                == View.LAYOUT_DIRECTION_RTL) {
            steps = -steps;
        }
        if (!mConnection.hasCursorPosition()) {
            sendDownUpKeyEvent(steps < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
            mLatinIME.onSuggestionsAffectingCursorMove();
            return;
        }
        if (mConnection.hasSelection()) {
            final int edge = steps < 0 ? mConnection.getExpectedSelectionStart()
                    : mConnection.getExpectedSelectionEnd();
            mConnection.setSelection(edge, edge);
        } else {
            steps = mConnection.getUnicodeSteps(steps, true);
            if (steps == 0) {
                return;
            }
            final int position = mConnection.getExpectedSelectionEnd() + steps;
            mConnection.setSelection(position, position);
        }
        mLatinIME.onSuggestionsAffectingCursorMove();
    }

    /**
     * Handle a press on the language switch key (the "globe key")
     */
    private void handleLanguageSwitchKey() {
        mLatinIME.switchToNextSubtype();
    }

    /**
     * Performs a recapitalization event: with a selection, the selection's case cycles; with no
     * selection, the case of the word right before the cursor does.
     */
    private void performRecapitalization(final SettingsValues settingsValues) {
        if (!mRecapitalizeStatus.mIsEnabled()) {
            return; // Recapitalize is disabled for now
        }
        if (mConnection.hasSelection()) {
            performSelectionRecapitalization();
            return;
        }
        performTrailingWordCaseCycle(settingsValues);
    }

    /**
     * Cycles the case of the current selection through the rotation in {@link RecapitalizeStatus}.
     */
    private void performSelectionRecapitalization() {
        final int selectionStart = mConnection.getExpectedSelectionStart();
        final int selectionEnd = mConnection.getExpectedSelectionEnd();
        final int numCharsSelected = selectionEnd - selectionStart;
        // An inverted selection would take a negative substring below. updateSelection already
        // normalizes it; this is a second check.
        if (numCharsSelected < 0) {
            return;
        }
        if (numCharsSelected > Constants.MAX_CHARACTERS_FOR_RECAPITALIZATION) {
            // We bail out if we have too many characters for performance reasons. We don't want
            // to suck possibly multiple-megabyte data.
            return;
        }
        // If we have a recapitalize in progress, use it; otherwise, start a new one.
        if (!mRecapitalizeStatus.isStarted()
                || !mRecapitalizeStatus.isSetAt(selectionStart, selectionEnd)) {
            final CharSequence selectedText = mConnection.getSelectedText();
            if (TextUtils.isEmpty(selectedText)) return; // Race condition with the input connection
            mRecapitalizeStatus.start(selectionStart, selectionEnd, selectedText.toString(), mLatinIME.getCurrentLayoutLocale());
            // We trim leading and trailing whitespace.
            mRecapitalizeStatus.trim();
        }
        mConnection.beginBatchEdit();
        try {
            mConnection.setSelection(selectionStart, selectionStart);
            mRecapitalizeStatus.rotate();
            mConnection.replaceText(selectionStart, selectionEnd, mRecapitalizeStatus.getRecapitalizedString());
            mConnection.setSelection(mRecapitalizeStatus.getNewCursorStart(), mRecapitalizeStatus.getNewCursorEnd());
        } finally {
            mConnection.endBatchEdit();
        }
    }

    /**
     * No selection: cycles the case of the word right before the cursor (lowercase, Capitalized,
     * ALL CAPS; states that would not change the word are skipped by the rotation). The cursor
     * stays collapsed at the word end, so typing continues normally and the next shift press
     * cycles the same word again. Never in password fields; when nothing usable stands before the
     * cursor the press stays a plain shift.
     */
    private void performTrailingWordCaseCycle(final SettingsValues settingsValues) {
        if (settingsValues.mInputAttributes.mIsPasswordField) {
            return;
        }
        final CharSequence beforeCursor = mConnection.getCachedTextBeforeCursor();
        final int wordLength = TatarWordUtils.caseCycleWordLength(beforeCursor,
                mConnection.getCachedTextAfterCursor(), mConnection.cacheReachedTextStart());
        final int cursor = mConnection.getExpectedSelectionStart();
        if (wordLength <= 0 || cursor < wordLength) {
            return;
        }
        final int wordStart = cursor - wordLength;
        final String word = beforeCursor.subSequence(
                beforeCursor.length() - wordLength, beforeCursor.length()).toString();
        mRecapitalizeStatus.start(wordStart, cursor, word, mLatinIME.getCurrentLayoutLocale());
        mRecapitalizeStatus.rotate();
        final String cycled = mRecapitalizeStatus.getRecapitalizedString();
        if (cycled.equals(word)) {
            // A word without cased letters cannot change: the press stays a plain shift, and the
            // status must not claim a rotation it never made.
            mRecapitalizeStatus.stop();
            return;
        }
        mConnection.beginBatchEdit();
        // Connection check after opening the batch; see replaceTrailingWord.
        final boolean connected = mConnection.isConnected();
        try {
            if (connected) {
                // replaceText works on the range after the cursor, so the cursor first collapses
                // to the word start; the cycled word then replaces it and the cursor lands back
                // at the word end. One batch: the editor sees a single transaction.
                mConnection.setSelection(wordStart, wordStart);
                mConnection.replaceText(wordStart, cursor, cycled);
                mConnection.setSelection(wordStart + cycled.length(), wordStart + cycled.length());
            }
        } finally {
            mConnection.endBatchEdit();
        }
        if (!connected) {
            mRecapitalizeStatus.stop();
            return;
        }
        // The after-state follows the collapsed cursor: the shift visual still tracks the result.
        mRecapitalizeStatus.collapseAfterRangeToEnd();
    }

    /**
     * Gets the current auto-caps state, factoring in the space state.
     *
     * This method tries its best to do this in the most efficient possible manner. It avoids
     * getting text from the editor if possible at all.
     * This is called from the KeyboardSwitcher (through a trampoline in LatinIME) because it
     * needs to know auto caps state to display the right layout.
     *
     * @param settingsValues the relevant settings values
     * @return a caps mode from TextUtils.CAP_MODE_* or Constants.TextUtils.CAP_MODE_OFF.
     */
    public int getCurrentAutoCapsState(final SettingsValues settingsValues) {
        if (!settingsValues.mAutoCap) {
            return Constants.TextUtils.CAP_MODE_OFF;
        }

        final EditorInfo ei = getCurrentInputEditorInfo();
        if (ei == null) return Constants.TextUtils.CAP_MODE_OFF;
        final int inputType = ei.inputType;
        // Warning: this depends on mSpaceState, which may not be the most current value. If
        // mSpaceState gets updated later, whoever called this may need to be told about it.
        return mConnection.getCursorCapsMode(inputType, settingsValues.mSpacingAndPunctuations);
    }

    /**
     * Commits a suggestion chosen from the Tatar suggestion strip, replacing the trailing word.
     *
     * <p>This is a stale-tap-safe operation: it re-derives the trailing word from the local
     * cache and only performs the edit if it still matches {@code expectedPrefix}. If anything
     * has changed since the suggestion was shown (selection present, cursor moved into a word,
     * word edited), no edit is made and {@code false} is returned. All work happens on the UI
     * thread and reads only the cached text around the cursor (no IPC to recompute the word).
     *
     * <p>The accepted word is committed with a trailing space so the user can type the next word
     * right away, unless the text after the cursor already separates it
     * ({@link TatarWordUtils#needsAutoSpace}).
     *
     * @param expectedPrefix the trailing word the suggestion was computed for.
     * @param suggestion the replacement text to commit.
     * @return {@code true} if the replacement was committed, {@code false} otherwise (no edit).
     */
    public boolean commitChosenSuggestion(final String expectedPrefix, final String suggestion) {
        return replaceTrailingWord(expectedPrefix, suggestion, true /* withAutoSpace */);
    }

    /**
     * Commits an autocorrection through {@link #replaceTrailingWord}, without the auto-space.
     *
     * <p>The separator that triggered the correction has not been committed yet; it follows through
     * the ordinary input path and supplies the separation. Adding a space here would produce
     * "сүз  ,".
     *
     * @param expectedPrefix the trailing word the verdict was computed for.
     * @param replacement the dictionary word to put in its place.
     * @return {@code true} if the replacement was committed, {@code false} otherwise (no edit).
     */
    public boolean commitTatarAutocorrection(final String expectedPrefix,
            final String replacement) {
        return replaceTrailingWord(expectedPrefix, replacement, false /* withAutoSpace */);
    }

    /**
     * Replaces the trailing word for both an accepted suggestion and an autocorrection: one delete
     * plus {@code commitText} inside one batch edit, never composing text.
     *
     * <p>Re-checks against the cache: a collapsed selection, a cursor not inside a word, and a
     * trailing word still equal to {@code expectedPrefix}. The delete length comes from that
     * verified word; any mismatch cancels the edit.
     */
    private boolean replaceTrailingWord(final String expectedPrefix, final String replacement,
            final boolean withAutoSpace) {
        if (TextUtils.isEmpty(expectedPrefix) || TextUtils.isEmpty(replacement)) {
            return false;
        }
        if (mConnection.hasSelection()) {
            return false;
        }
        if (TatarWordUtils.startsWithWordCharacter(mConnection.getCachedTextAfterCursor())) {
            // Cursor inside a word. The controller already shows no candidates here; this second
            // check stops an out-of-sync strip from splicing a word into the user's text.
            return false;
        }
        final String currentWord =
                TatarWordUtils.extractTrailingWord(mConnection.getCachedTextBeforeCursor());
        if (!expectedPrefix.equals(currentWord)) {
            // Stale tap: the trailing word no longer matches. Do not edit.
            return false;
        }
        // The space goes into the same commitText: a second commit would show the word without
        // its space for one frame and cost another IPC round trip.
        final boolean appendsAutoSpace = withAutoSpace
                && TatarWordUtils.needsAutoSpace(mConnection.getCachedTextAfterCursor());
        final String textToCommit = appendsAutoSpace ? replacement + AUTO_SPACE : replacement;
        mConnection.beginBatchEdit();
        // Opening the batch refreshes the connection, so the connection is checked only now. If
        // the editor went away since the strip was drawn, nothing is edited and false is returned:
        // RichInputConnection would otherwise update its cache for an edit no editor received.
        // The batch is closed on both paths. The other edit paths below follow the same rule.
        final boolean connected = mConnection.isConnected();
        try {
            if (connected) {
                mConnection.deleteTextBeforeCursor(expectedPrefix.length());
                mConnection.commitText(textToCommit, 1);
            }
        } finally {
            mConnection.endBatchEdit();
        }
        if (!connected) {
            return false;
        }
        // An inserted space must not arm double-space-to-period: the next space press behaves
        // like a first one (otherwise "сүзләр " + space would become "сүзләр. "). The edit also
        // invalidates a pending double-space revert. Both hold for the autocorrection path too.
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
        mAutoSpaceCursor = appendsAutoSpace
                ? mConnection.getExpectedSelectionStart() : NO_AUTO_SPACE;
        mPhantomSpaceCursor = NO_AUTO_SPACE;
        return true;
    }

    /**
     * Undoes the autocorrection that was made immediately before this backspace, with the same
     * delete + {@code commitText} in one batch edit and no composing text.
     *
     * <p>The text right before the cursor must be {@code insertedForm + separator}. This suffix
     * match is the position check (an offset could match again after unrelated edits). Otherwise
     * nothing is edited and {@code false} is returned; the backspace then deletes a character.
     *
     * @param insertedForm the word this keyboard put there.
     * @param separator the separator committed right after it.
     * @param typedForm what the user had actually typed.
     * @return {@code true} if the original input was restored, {@code false} otherwise (no edit).
     */
    public boolean revertTatarAutocorrection(final String insertedForm, final String separator,
            final String typedForm) {
        if (TextUtils.isEmpty(insertedForm) || TextUtils.isEmpty(typedForm)) {
            return false;
        }
        if (mConnection.hasSelection()) {
            return false;
        }
        if (TatarWordUtils.startsWithWordCharacter(mConnection.getCachedTextAfterCursor())) {
            // Same checks as replaceTrailingWord.
            return false;
        }
        final String inserted = insertedForm + separator;
        if (!endsWith(mConnection.getCachedTextBeforeCursor(), inserted)) {
            return false;
        }
        mConnection.beginBatchEdit();
        // Connection check after opening the batch; see replaceTrailingWord.
        final boolean connected = mConnection.isConnected();
        try {
            if (connected) {
                mConnection.deleteTextBeforeCursor(inserted.length());
                mConnection.commitText(typedForm + separator, 1);
            }
        } finally {
            mConnection.endBatchEdit();
        }
        if (!connected) {
            return false;
        }
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
        mAutoSpaceCursor = NO_AUTO_SPACE;
        mPhantomSpaceCursor = NO_AUTO_SPACE;
        return true;
    }

    /**
     * Commits a next-word prediction. A separate method from {@link #replaceTrailingWord} because
     * it deletes nothing: each kind of result has its own commit path.
     *
     * <p>Re-checks against the cache: collapsed selection, no letter after the cursor, an empty
     * trailing word (otherwise the user typed after the request and the tap is stale), and the
     * context word, re-extracted the same way, equal to {@code expectedContextWord}. The word is
     * inserted with the auto-space rule of an accepted suggestion.
     *
     * <p>An empty context word means a sentence-start prediction; the position must then still be a
     * sentence start, or an empty context would also match after ", ".
     *
     * @param expectedContextWord the context word the prediction was computed for; empty only for
     *        a sentence-start prediction.
     * @param suggestion the predicted word to insert.
     * @return {@code true} if the word was committed, {@code false} otherwise (no edit).
     */
    public boolean commitPredictedWord(final String expectedContextWord, final String suggestion) {
        if (expectedContextWord == null || TextUtils.isEmpty(suggestion)) {
            return false;
        }
        if (mConnection.hasSelection()) {
            return false;
        }
        if (TatarWordUtils.startsWithWordCharacter(mConnection.getCachedTextAfterCursor())) {
            // Same checks as replaceTrailingWord.
            return false;
        }
        if (!TatarWordUtils.extractTrailingWord(mConnection.getCachedTextBeforeCursor()).isEmpty()) {
            // The prefix is no longer empty: the user typed after the request was built. Stale
            // tap; do not edit.
            return false;
        }
        // The same extraction the request was built with, including whether the cache reaches
        // the start of the text, so a prediction for the first word of a field is not refused.
        final String liveContext =
                TatarWordUtils.extractNextWordContext(mConnection.getCachedTextBeforeCursor(),
                        mConnection.cacheReachedTextStart());
        if (!expectedContextWord.equals(liveContext)) {
            // Stale tap: the context word no longer matches. Do not edit.
            return false;
        }
        if (expectedContextWord.isEmpty()
                && !TatarWordUtils.isSentenceStartContext(mConnection.getCachedTextBeforeCursor(),
                        mConnection.cacheReachedTextStart())) {
            // An empty context is valid only at a sentence start; anywhere else the tap is
            // stale. Do not edit.
            return false;
        }
        // The space goes into the same commitText, as in replaceTrailingWord.
        final boolean appendsAutoSpace =
                TatarWordUtils.needsAutoSpace(mConnection.getCachedTextAfterCursor());
        final String textToCommit = appendsAutoSpace ? suggestion + AUTO_SPACE : suggestion;
        mConnection.beginBatchEdit();
        // Connection check after opening the batch; see replaceTrailingWord.
        final boolean connected = mConnection.isConnected();
        try {
            if (connected) {
                mConnection.commitText(textToCommit, 1);
            }
        } finally {
            mConnection.endBatchEdit();
        }
        if (!connected) {
            return false;
        }
        // No double-space arming and no stale revert; see replaceTrailingWord.
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
        mAutoSpaceCursor = appendsAutoSpace
                ? mConnection.getExpectedSelectionStart() : NO_AUTO_SPACE;
        mPhantomSpaceCursor = NO_AUTO_SPACE;
        return true;
    }

    /** Allocation-free suffix test over the cached text; never logs or copies what it reads. */
    private static boolean endsWith(final CharSequence text, final String suffix) {
        if (text == null) {
            return false;
        }
        final int suffixLength = suffix.length();
        final int offset = text.length() - suffixLength;
        if (suffixLength == 0 || offset < 0) {
            return false;
        }
        for (int index = 0; index < suffixLength; index++) {
            if (text.charAt(offset + index) != suffix.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Commits a glide-typed word. Same re-checks as {@link #commitPredictedWord} except the
     * sentence-start requirement for an empty context: a glide ends at the current cursor, so the
     * context equality check is enough (and a glide after "сүз ? " must still commit).
     *
     * <p>The gesture is stale, and nothing is edited, when the live trailing word differs from
     * {@code expectedTrailingWord} or the cursor from {@code expectedCursor}: a letter or a mark
     * typed between the lift and the decode result. A cache that is empty although the cursor is
     * past the text start cannot tell whether a space is needed, so the commit is refused and the
     * cache reloaded; the gesture can be redone.
     *
     * <p>A glide adds no trailing space. One space is prepended when
     * {@link TatarWordUtils#glideNeedsLeadingSpace} holds (after a word, a digit, a mark such as
     * "," or a closing quote), so "сүз," gives "сүз, дөнья" and consecutive glides "сәләм дөнья". The new cursor arms
     * the phantom space ({@link #handleNonSeparatorEvent}).
     *
     * @param expectedContextWord the context word captured when the gesture was delivered.
     * @param suggestion the decoded word to insert.
     * @param expectedTrailingWord the trailing word captured at the gesture, "" when none.
     * @param expectedCursor the cursor captured at the gesture, or -1 to skip that check.
     * @return {@code GLIDE_COMMIT_*}: refused (no edit), bare, or committed with a space
     *         prepended; the undo needs the distinction.
     */
    public int commitGlideWord(final String expectedContextWord, final String suggestion,
            final String expectedTrailingWord, final int expectedCursor) {
        if (expectedContextWord == null || expectedTrailingWord == null
                || TextUtils.isEmpty(suggestion)) {
            return 0; // GLIDE_COMMIT_REFUSED
        }
        if (mConnection.hasSelection()) {
            return 0;
        }
        if (TatarWordUtils.startsWithWordCharacter(mConnection.getCachedTextAfterCursor())) {
            return 0;
        }
        final CharSequence beforeCursor = mConnection.getCachedTextBeforeCursor();
        final int cursor = mConnection.getExpectedSelectionStart();
        if (cursor > 0 && beforeCursor.length() == 0 && !mConnection.cacheReachedTextStart()) {
            // Text stands before the cursor but the cache does not hold it (a reload in flight or
            // not yet run after a refocus): committing now could glue the word to it.
            mConnection.reloadTextCache();
            return 0;
        }
        if (expectedCursor != -1 && expectedCursor != cursor) {
            // An edit between the lift and the decode result moved the cursor. Do not edit.
            return 0;
        }
        if (!expectedTrailingWord.equals(TatarWordUtils.extractTrailingWord(beforeCursor))) {
            // A letter typed after the lift changed the trailing word. Do not edit.
            return 0;
        }
        final String liveContext = TatarWordUtils.extractNextWordContext(beforeCursor,
                mConnection.cacheReachedTextStart());
        if (!expectedContextWord.equals(liveContext)) {
            // The text moved between the lift and the decode's completion. Do not edit.
            return 0;
        }
        // The leading space goes into the same commitText.
        final boolean prepend = TatarWordUtils.glideNeedsLeadingSpace(beforeCursor);
        final String textToCommit = prepend ? AUTO_SPACE + suggestion : suggestion;
        mConnection.beginBatchEdit();
        // Connection check after opening the batch; see replaceTrailingWord.
        final boolean connected = mConnection.isConnected();
        try {
            if (connected) {
                mConnection.commitText(textToCommit, 1);
            }
        } finally {
            mConnection.endBatchEdit();
        }
        if (!connected) {
            return 0;
        }
        // No double-space arming and no stale revert; see replaceTrailingWord.
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
        mAutoSpaceCursor = NO_AUTO_SPACE;
        mPhantomSpaceCursor = mConnection.getExpectedSelectionStart();
        return prepend ? 2 : 1; // GLIDE_COMMIT_PREPENDED : GLIDE_COMMIT_BARE
    }

    /**
     * Replaces the word a glide just committed with the tapped alternative, in place: no space is
     * added or removed, and a leading space the commit prepended ({@code prependedSpace}) is kept.
     * The trailing word must be {@code committedWord}, with that space before it if expected;
     * otherwise nothing is edited. The new cursor arms the phantom space, as the commit does.
     *
     * @param committedWord the word the glide lift committed.
     * @param alternative the alternative shown in the strip and tapped.
     * @param prependedSpace whether the commit prepended a leading space.
     * @return {@code true} if the replacement happened, {@code false} otherwise (no edit).
     */
    public boolean replaceGlideLiftedWord(final String committedWord, final String alternative,
            final boolean prependedSpace) {
        if (TextUtils.isEmpty(committedWord) || TextUtils.isEmpty(alternative)) {
            return false;
        }
        if (mConnection.hasSelection()) {
            return false;
        }
        if (TatarWordUtils.startsWithWordCharacter(mConnection.getCachedTextAfterCursor())) {
            // As in the other edit paths: never splice into the user's word.
            return false;
        }
        final CharSequence beforeCursor = mConnection.getCachedTextBeforeCursor();
        if (!TatarWordUtils.extractTrailingWord(beforeCursor).equals(committedWord)) {
            return false;
        }
        final String suffix = (prependedSpace ? AUTO_SPACE : "") + committedWord;
        if (!endsWith(beforeCursor, suffix)) {
            return false;
        }
        final String textToCommit = (prependedSpace ? AUTO_SPACE : "") + alternative;
        mConnection.beginBatchEdit();
        // Connection check after opening the batch; see replaceTrailingWord.
        final boolean connected = mConnection.isConnected();
        try {
            if (connected) {
                mConnection.deleteTextBeforeCursor(suffix.length());
                mConnection.commitText(textToCommit, 1);
            }
        } finally {
            mConnection.endBatchEdit();
        }
        if (!connected) {
            return false;
        }
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
        mAutoSpaceCursor = NO_AUTO_SPACE;
        mPhantomSpaceCursor = mConnection.getExpectedSelectionStart();
        return true;
    }

    /**
     * Whole-word undo of a glide commit: one backspace right after it deletes the committed word,
     * including a leading space the commit prepended ("сүз, дөнья" → "сүз,"), and commits nothing
     * back. Same position check as {@link #replaceGlideLiftedWord}.
     *
     * @param committedWord the word the glide lift committed (or its current replacement).
     * @param prependedSpace whether the commit prepended a leading space.
     * @return {@code true} if the word was deleted, {@code false} otherwise (no edit).
     */
    public boolean deleteGlideLiftedWord(final String committedWord, final boolean prependedSpace) {
        if (TextUtils.isEmpty(committedWord)) {
            return false;
        }
        if (mConnection.hasSelection()) {
            return false;
        }
        if (TatarWordUtils.startsWithWordCharacter(mConnection.getCachedTextAfterCursor())) {
            return false;
        }
        final CharSequence beforeCursor = mConnection.getCachedTextBeforeCursor();
        if (!TatarWordUtils.extractTrailingWord(beforeCursor).equals(committedWord)) {
            return false;
        }
        final String suffix = (prependedSpace ? AUTO_SPACE : "") + committedWord;
        if (!endsWith(beforeCursor, suffix)) {
            return false;
        }
        mConnection.beginBatchEdit();
        final boolean connected = mConnection.isConnected();
        try {
            if (connected) {
                mConnection.deleteTextBeforeCursor(suffix.length());
            }
        } finally {
            mConnection.endBatchEdit();
        }
        if (!connected) {
            return false;
        }
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
        mAutoSpaceCursor = NO_AUTO_SPACE;
        mPhantomSpaceCursor = NO_AUTO_SPACE;
        return true;
    }

    public int getCurrentRecapitalizeState() {
        if (!mRecapitalizeStatus.isStarted()
                || !mRecapitalizeStatus.isSetAt(mConnection.getExpectedSelectionStart(),
                        mConnection.getExpectedSelectionEnd())) {
            // Not recapitalizing at the moment
            return RecapitalizeStatus.NOT_A_RECAPITALIZE_MODE;
        }
        return mRecapitalizeStatus.getCurrentMode();
    }

    /**
     * @return the editor info for the current editor
     */
    private EditorInfo getCurrentInputEditorInfo() {
        return mLatinIME.getCurrentInputEditorInfo();
    }

    /**
     * @param actionId the action to perform
     */
    private void performEditorAction(final int actionId) {
        mConnection.performEditorAction(actionId);
    }

    /**
     * Perform the processing specific to inputting TLDs.
     *
     * Some keys input a TLD (specifically, the ".com" key) and this warrants some specific
     * processing. First, if this is a TLD, we ignore PHANTOM spaces -- this is done by type
     * of character in onCodeInput, but since this gets inputted as a whole string we need to
     * do it here specifically. Then, if the last character before the cursor is a period, then
     * we cut the dot at the start of ".com". This is because humans tend to type "www.google."
     * and then press the ".com" key and instinctively don't expect to get "www.google..com".
     *
     * @param text the raw text supplied to onTextInput
     * @return the text to actually send to the editor
     */
    private String performSpecificTldProcessingOnTextInput(final String text) {
        if (text.length() <= 1 || text.charAt(0) != Constants.CODE_PERIOD
                || !Character.isLetter(text.charAt(1))) {
            // Not a tld: do nothing.
            return text;
        }
        final int codePointBeforeCursor = mConnection.getCodePointBeforeCursor();
        // If no code point, #getCodePointBeforeCursor returns NOT_A_CODE_POINT.
        if (Constants.CODE_PERIOD == codePointBeforeCursor) {
            return text.substring(1);
        }
        return text;
    }

    /**
     * Handle a press on the settings key.
     */
    private void onSettingsKeyPressed() {
        mLatinIME.launchSettings();
    }

    /**
     * Sends a DOWN key event followed by an UP key event to the editor.
     *
     * If possible at all, avoid using this method. It causes all sorts of race conditions with
     * the text view because it goes through a different, asynchronous binder. Also, batch edits
     * are ignored for key events. Use the normal software input methods instead.
     *
     * @param keyCode the key code to send inside the key event.
     */
    public void sendDownUpKeyEvent(final int keyCode) {
        sendDownUpKeyEvent(keyCode, 0);
    }

    public void sendDownUpKeyEvent(final int keyCode, final int metaState) {
        final long eventTime = SystemClock.uptimeMillis();
        mConnection.sendKeyEvent(new KeyEvent(eventTime, eventTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
        mConnection.sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), eventTime,
                KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
    }

    /**
     * Sends a code point to the editor, using the most appropriate method.
     *
     * Normally we send code points with commitText, but there are some cases (where backward
     * compatibility is a concern for example) where we want to use deprecated methods.
     *
     * @param codePoint the code point to send.
     */
    // TODO: replace these two parameters with an InputTransaction
    private void sendKeyCodePoint(final int codePoint) {
        // TODO: Remove this special handling of digit letters.
        // For backward compatibility. See {@link InputMethodService#sendKeyChar(char)}.
        if (codePoint >= '0' && codePoint <= '9') {
            sendDownUpKeyEvent(codePoint - '0' + KeyEvent.KEYCODE_0);
            return;
        }

        mConnection.commitText(StringUtils.newSingleCodePointString(codePoint), 1);
    }
}
