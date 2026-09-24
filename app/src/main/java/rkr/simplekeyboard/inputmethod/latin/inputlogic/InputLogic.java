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
import rkr.simplekeyboard.inputmethod.latin.utils.SubtypeLocaleUtils;

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

    // TODO : Remove this member when we can.
    final LatinIME mLatinIME;

    // This has package visibility so it can be accessed from InputLogicHandler.
    public final RichInputConnection mConnection;
    private final RecapitalizeStatus mRecapitalizeStatus = new RecapitalizeStatus();

    // Time of the last committed space, for double-space-to-period detection.
    private long mLastSpaceDownTime;
    // Whether the last input was a double-space-to-period, for revert on backspace.
    private boolean mJustDoubleSpaced;

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
        // Double-space state must not leak between editors.
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
    }

    public void clearCaches() {
        mConnection.clearCaches();
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
        }
        mConnection.updateSelection(newSelStart, newSelEnd);
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
            // Committed combiner text invalidates a pending double-space revert.
            mJustDoubleSpaced = false;
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
                performRecapitalization();
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
                mConnection.pasteClipboard();
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
     * Handle a non-separator.
     * @param event The event to handle.
     */
    private void handleNonSeparatorEvent(final Event event) {
        mJustDoubleSpaced = false;
        sendKeyCodePoint(event.mCodePoint);
    }

    /**
     * Handle input of a separator code point.
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private void handleSeparatorEvent(final Event event, final InputTransaction inputTransaction) {
        if (event.mCodePoint == Constants.CODE_SPACE) {
            if (tryDoubleSpacePeriod(inputTransaction.mSettingsValues)) {
                inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
                return;
            }
        } else {
            mJustDoubleSpaced = false;
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
                && !settingsValues.mInputAttributes.mIsPasswordField
                && mConnection.getCodePointBeforeCursor() == Constants.CODE_SPACE
                && Character.isLetterOrDigit(mConnection.getCodePointBeforeCursor(1))) {
            mConnection.beginBatchEdit();
            mConnection.deleteTextBeforeCursor(1);
            mConnection.commitText(". ", 1);
            mConnection.endBatchEdit();
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

        if (mConnection.hasSelection()) {
            mJustDoubleSpaced = false;
            mConnection.deleteSelectedText();
        } else {
            if (mJustDoubleSpaced
                    && mConnection.getCodePointBeforeCursor() == Constants.CODE_SPACE
                    && mConnection.getCodePointBeforeCursor(1) == Constants.CODE_PERIOD) {
                // Revert the double-space-to-period: restore the two spaces.
                mConnection.beginBatchEdit();
                mConnection.deleteTextBeforeCursor(2);
                mConnection.commitText("  ", 1);
                mConnection.endBatchEdit();
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
     * Handle a press on the language switch key (the "globe key")
     */
    private void handleLanguageSwitchKey() {
        mLatinIME.switchToNextSubtype();
    }

    /**
     * Performs a recapitalization event.
     */
    private void performRecapitalization() {
        if (!mConnection.hasSelection() || !mRecapitalizeStatus.mIsEnabled()) {
            return; // No selection or recapitalize is disabled for now
        }
        final int selectionStart = mConnection.getExpectedSelectionStart();
        final int selectionEnd = mConnection.getExpectedSelectionEnd();
        final int numCharsSelected = selectionEnd - selectionStart;
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
        mConnection.setSelection(selectionStart, selectionStart);
        mRecapitalizeStatus.rotate();
        mConnection.replaceText(selectionStart, selectionEnd, mRecapitalizeStatus.getRecapitalizedString());
        mConnection.setSelection(mRecapitalizeStatus.getNewCursorStart(), mRecapitalizeStatus.getNewCursorEnd());
        mConnection.endBatchEdit();
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
     * @param layoutSetName the name of the current keyboard layout set
     * @return a caps mode from TextUtils.CAP_MODE_* or Constants.TextUtils.CAP_MODE_OFF.
     */
    public int getCurrentAutoCapsState(final SettingsValues settingsValues,
                                       final String layoutSetName) {
        if (!settingsValues.mAutoCap || !layoutUsesAutoCaps(layoutSetName)) {
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
     * Commits an autocorrection (D3): the SECOND insertion path of the frozen text contract, and
     * the same mechanism as the first one — this method exists only to say "no auto-space" and
     * delegates the edit itself to {@link #replaceTrailingWord}.
     *
     * <p>The separator that triggered the correction has not been committed yet; it follows through
     * the ordinary input path a moment later and supplies the separation an accepted suggestion has
     * to bring with it. Adding a space here would produce "сүз  ,".
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
     * THE single place where a Tatar word is replaced in the editor: both insertion paths of the
     * frozen text contract — the accepted suggestion and the autocorrection — go through this one
     * explicit delete-by-code-points plus {@code commitText} inside ONE batch edit. No composing
     * text is ever set, on either path.
     *
     * <p>The re-checks below belong to both paths verbatim, which is the point of sharing them:
     * a collapsed selection, a cursor that is not inside a word, and a live trailing word that still
     * equals {@code expectedPrefix}. The deletion length is taken from that verified word, so it can
     * only ever cover whole code points; a mismatch cancels the action entirely rather than editing
     * part of it.
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
            // Cursor inside a word. The controller already refuses to show candidates in this
            // state; this is the second, fail-closed line of defense against a desynchronized
            // strip, because replacing the trailing word here would splice the suggestion into
            // the middle of the user's text.
            return false;
        }
        final String currentWord =
                TatarWordUtils.extractTrailingWord(mConnection.getCachedTextBeforeCursor());
        if (!expectedPrefix.equals(currentWord)) {
            // Stale tap: the trailing word no longer matches. Do not edit.
            return false;
        }
        // The space rides along inside the SAME commitText: a second commit would show the word
        // without its space for one frame and would cost another IPC round trip for nothing.
        final String textToCommit =
                withAutoSpace
                        && TatarWordUtils.needsAutoSpace(mConnection.getCachedTextAfterCursor())
                        ? replacement + AUTO_SPACE : replacement;
        mConnection.beginBatchEdit();
        // beginBatchEdit() is what refreshes the connection from the framework, so this is the
        // earliest the question can be asked. "No connection" means the editor went away between
        // the band being painted and the tap. The edit below would still RUN in that state —
        // RichInputConnection updates its own text cache before it checks the connection — and this
        // method would then return true over an edit that reached no editor at all: the caller
        // unbinds the candidates and clears the band for text that does not exist, and every later
        // decision taken from the cache (the trailing word, the letter after the cursor, the suffix
        // an undo matches) is taken from fiction. Nothing is touched instead, and false is the
        // truth. The batch is still closed on this path, exactly once, like on the other one.
        final boolean connected = mConnection.isConnected();
        if (connected) {
            mConnection.deleteTextBeforeCursor(expectedPrefix.length());
            mConnection.commitText(textToCommit, 1);
        }
        mConnection.endBatchEdit();
        if (!connected) {
            return false;
        }
        // A space this code inserted must not arm the double-space-to-period gesture: the next
        // real space press has to behave like a first one, exactly as it does after a typed
        // letter. Leaving mLastSpaceDownTime armed would turn "сүзләр " + space into "сүзләр. "
        // whenever the user had pressed space less than DOUBLE_SPACE_PERIOD_TIMEOUT ago. This is
        // also an edit, so a pending revert no longer describes the text before the cursor. It
        // holds for the autocorrection path too, which commits no space of its own: the space the
        // user is about to press must still behave like a first one.
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
        return true;
    }

    /**
     * Undoes the autocorrection that was made immediately before this backspace (D3), through the
     * same explicit delete + {@code commitText} in one batch edit, and without composing text.
     *
     * <p>What must stand right before the cursor is {@code insertedForm + separator} — the exact
     * text the replacement and the separator behind it left there. That suffix match IS the position
     * check the contract asks for and a stronger one than an offset: an offset can coincide again
     * after unrelated edits, this text cannot. If anything else changed the text, nothing is edited
     * and {@code false} is returned; the caller has already dropped the undo state by then, so the
     * backspace simply deletes a character like any other.
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
            // Same list as the replacement path: the contract adds checks to it, it removes none.
            return false;
        }
        final String inserted = insertedForm + separator;
        if (!endsWith(mConnection.getCachedTextBeforeCursor(), inserted)) {
            return false;
        }
        mConnection.beginBatchEdit();
        // The same guard the two commit paths above carry (25c1ae28): beginBatchEdit() is what
        // refreshes the connection from the framework, so this is the earliest the question can
        // be asked. An editor that went away between the replacement and this backspace gets no
        // edit and a false answer — the cache-only mutation would otherwise be reported as a
        // success and the caller would treat fiction as the text before the cursor. The batch is
        // still closed on this path, exactly once, like on the other one.
        final boolean connected = mConnection.isConnected();
        if (connected) {
            mConnection.deleteTextBeforeCursor(inserted.length());
            mConnection.commitText(typedForm + separator, 1);
        }
        mConnection.endBatchEdit();
        if (!connected) {
            return false;
        }
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
        return true;
    }

    /**
     * Commits a predicted next word (E5d): the THIRD insertion path of the frozen text contract, and
     * a SEPARATE method rather than a branch inside {@link #replaceTrailingWord} — NEXT_WORD deletes
     * NOTHING (PROPOSALS.md, "Контракт текста" amendment, 2026-08-17, "Отдельный путь коммита"),
     * while every path through {@link #replaceTrailingWord} always deletes {@code expectedPrefix}
     * first; a shared method conditioned on "delete or not" would be one method doing two different
     * jobs behind one signature, exactly the shape the frozen contract's "one commit path per kind of
     * result" rule exists to prevent.
     *
     * <p>Re-checks, all against the LIVE cache: collapsed selection, no letter right after the cursor
     * (the same two checks {@link #replaceTrailingWord} makes), an EMPTY trailing word (NEXT_WORD is
     * only ever requested when the prefix is empty, so a non-empty one here means the user typed
     * something after the request was built, and the tap is stale), and the live context word —
     * re-extracted by the exact algorithm that built the request — matching {@code
     * expectedContextWord}. Deletes zero characters; inserts {@code suggestion} with the same
     * auto-space rule an accepted suggestion uses.
     *
     * <p>P4 (docs/TT-SUGGESTIONS.md) adds one case to the context check: at a sentence start the
     * bound context is EMPTY — the sentence-start table answers where there is no previous word —
     * and the equality check then passes only if the live context is empty too, with one
     * additional requirement: the live position must still be a sentence start by the exact
     * detector the request was built with. Without it an empty expected context would match any
     * context-free position (after ", " just as after ". "), and a stale tap would edit there.
     *
     * @param expectedContextWord the context word the prediction was computed for; empty only for
     *        a sentence-start prediction (P4).
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
            // Same list as replaceTrailingWord: the contract adds checks to it, it removes none.
            return false;
        }
        if (!TatarWordUtils.extractTrailingWord(mConnection.getCachedTextBeforeCursor()).isEmpty()) {
            // The prefix is no longer empty: the user typed something after the request was built,
            // and NEXT_WORD only ever applies to an empty prefix. Stale tap; do not edit.
            return false;
        }
        // The same extraction the request was built with, cache-boundary knowledge included
        // (docs/NEXTWORD-RACE.md): a tap on a first-word-of-field prediction must re-derive the
        // same context the controller saw, or it would be refused as stale. The knowledge is the
        // connection's provenance flag (audit 2026-09-02, C6), never a length re-derivation.
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
            // An empty context binds only at a sentence start (P4); the position no longer being
            // one (or never having been one — a context-free mid-sentence position matches the
            // equality check too) means the tap is stale. Do not edit.
            return false;
        }
        // The space rides along inside the SAME commitText, exactly like the other two paths.
        final String textToCommit =
                TatarWordUtils.needsAutoSpace(mConnection.getCachedTextAfterCursor())
                        ? suggestion + AUTO_SPACE : suggestion;
        mConnection.beginBatchEdit();
        // beginBatchEdit() is what refreshes the connection from the framework, so this is the
        // earliest the question can be asked. "No connection" means the editor went away between
        // the band being painted and the tap. The edit below would still RUN in that state —
        // RichInputConnection updates its own text cache before it checks the connection — and this
        // method would then return true over an edit that reached no editor at all: the caller
        // unbinds the candidates and clears the band for text that does not exist, and every later
        // decision taken from the cache (the trailing word, the letter after the cursor, the suffix
        // an undo matches) is taken from fiction. Nothing is touched instead, and false is the
        // truth. The batch is still closed on this path, exactly once, like on the other one.
        final boolean connected = mConnection.isConnected();
        if (connected) {
            mConnection.commitText(textToCommit, 1);
        }
        mConnection.endBatchEdit();
        if (!connected) {
            return false;
        }
        // Same reasoning as replaceTrailingWord: a space this code inserted must not arm the
        // double-space-to-period gesture, and a pending revert no longer describes the text before
        // the cursor after this edit.
        mJustDoubleSpaced = false;
        mLastSpaceDownTime = 0;
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

    private boolean layoutUsesAutoCaps(final String layoutSetName) {
        switch (layoutSetName) {
            case SubtypeLocaleUtils.LAYOUT_ARABIC:
            case SubtypeLocaleUtils.LAYOUT_BENGALI:
            case SubtypeLocaleUtils.LAYOUT_BENGALI_AKKHOR:
            case SubtypeLocaleUtils.LAYOUT_BENGALI_UNIJOY:
            case SubtypeLocaleUtils.LAYOUT_FARSI:
            case SubtypeLocaleUtils.LAYOUT_GEORGIAN:
            case SubtypeLocaleUtils.LAYOUT_HEBREW:
            case SubtypeLocaleUtils.LAYOUT_HINDI:
            case SubtypeLocaleUtils.LAYOUT_HINDI_COMPACT:
            case SubtypeLocaleUtils.LAYOUT_KANNADA:
            case SubtypeLocaleUtils.LAYOUT_KHMER:
            case SubtypeLocaleUtils.LAYOUT_LAO:
            case SubtypeLocaleUtils.LAYOUT_MALAYALAM:
            case SubtypeLocaleUtils.LAYOUT_MARATHI:
            case SubtypeLocaleUtils.LAYOUT_NEPALI_ROMANIZED:
            case SubtypeLocaleUtils.LAYOUT_NEPALI_TRADITIONAL:
            case SubtypeLocaleUtils.LAYOUT_TAMIL:
            case SubtypeLocaleUtils.LAYOUT_TELUGU:
            case SubtypeLocaleUtils.LAYOUT_THAI:
            case SubtypeLocaleUtils.LAYOUT_URDU:
                return false;
            default:
                return true;
        }
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
