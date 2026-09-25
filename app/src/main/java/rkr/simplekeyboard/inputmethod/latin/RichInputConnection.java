/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2024 wittmane
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

package rkr.simplekeyboard.inputmethod.latin;

import static android.content.ClipDescription.MIMETYPE_TEXT_HTML;
import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.common.StringUtils;
import rkr.simplekeyboard.inputmethod.latin.settings.SpacingAndPunctuations;
import rkr.simplekeyboard.inputmethod.latin.utils.CapsModeUtils;

/**
 * Enrichment class for InputConnection to simplify interaction and add functionality.
 *
 * This class serves as a wrapper to be able to simply add hooks to any calls to the underlying
 * InputConnection. It also keeps track of a number of things to avoid having to call upon IPC
 * all the time to find out what text is in the buffer, when we need it to determine caps mode
 * for example.
 */
public final class RichInputConnection {
    private static final String TAG = "RichInputConnection";
    private static final int INVALID_CURSOR_POSITION = -1;

    /**
     * This variable contains an expected value for the selection start position. This is where the
     * cursor or selection start may end up after all the keyboard-triggered updates have passed. We
     * keep this to compare it to the actual selection start to guess whether the move was caused by
     * a keyboard command or not.
     * It's not really the selection start position: the selection start may not be there yet, and
     * in some cases, it may never arrive there.
     */
    private int mExpectedSelStart = INVALID_CURSOR_POSITION; // in chars, not code points
    /**
     * The expected selection end.  Only differs from mExpectedSelStart if a non-empty selection is
     * expected.  The same caveats as mExpectedSelStart apply.
     */
    private int mExpectedSelEnd = INVALID_CURSOR_POSITION; // in chars, not code points
    /**
     * This contains the committed text immediately preceding the cursor and the composing
     * text, if any. It is refreshed when the cursor moves by calling upon the TextView.
     */
    private String mTextBeforeCursor = "";
    private String mTextAfterCursor = "";
    private String mTextSelection = "";

    /**
     * Audit 2026-09-02, C6: whether the before-cursor cache PROVABLY starts at the very start of
     * the editor's text. {@link #getCachedTextBeforeCursor} alone cannot say: the D1 fix
     * (docs/NEXTWORD-RACE.md) inferred it from the length — a full reload asks for exactly
     * {@link Constants#EDITOR_CONTENTS_CACHE_SIZE} chars, so a shorter answer reached the start —
     * but that inference breaks the moment a LOCAL mutation changes the length: a cursor swipe
     * re-slices the cached window to a few chars ({@link #setSelection}), a long backspace run
     * truncates it ({@link #deleteTextBeforeCursor}), and both leave a short cache that does NOT
     * start at the text start. A truncated word at index 0 would then be accepted as a whole
     * NEXT_WORD context.
     *
     * So this flag carries the knowledge from the one place that actually has it: the full
     * re-read, written only by {@link #onBeforeCursorCacheReloaded} (and cleared by
     * {@link #clearCaches}). Every local mutation PRESERVES it on purpose — they all move only the
     * CURSOR-side edge of the cached window (append/truncate at the cursor, re-slice inside the
     * window), so the window's start edge, the very thing the flag describes, is untouched by
     * them. Clearing it on a mutation would be wrong in both directions: it would un-prove the
     * empty field the user is typing their first word into (commitText), and it would re-"prove"
     * nothing. The ONE exception is the F3 truncation ({@link #appendToTextBeforeCursor},
     * 2026-09-25 audit): it cuts the window's HEAD to keep the cache bounded, which destroys the
     * start edge — and the flag clears at exactly that moment.
     */
    private boolean mCacheReachedTextStart = false;

    private final LatinIME mLatinIME;
    private InputConnection mIC;
    private int mNestLevel;
    private final ExecutorService mBackgroundThread;

    public RichInputConnection(final LatinIME latinIME) {
        mLatinIME = latinIME;
        mIC = null;
        mNestLevel = 0;
        mBackgroundThread = Executors.newSingleThreadExecutor();
    }

    public boolean isConnected() {
        return mIC != null;
    }

    public void beginBatchEdit() {
        if (++mNestLevel == 1) {
            mIC = mLatinIME.getCurrentInputConnection();
            if (isConnected()) {
                mIC.beginBatchEdit();
            }
        } else {
            Log.e(TAG, "Nest level too deep : " + mNestLevel);
        }
    }

    public void endBatchEdit() {
        if (mNestLevel <= 0) Log.e(TAG, "Batch edit not in progress!"); // TODO: exception instead
        if (--mNestLevel == 0 && isConnected()) {
            mIC.endBatchEdit();
        }
    }

    public void updateSelection(final int newSelStart, final int newSelEnd) {
        if (newSelStart > newSelEnd) {
            // 2026-09-25 audit, F7: a host may report an inverted selection (start > end). Kept
            // as-is it makes performRecapitalization substring a negative range. Normalize at
            // this choke point — every selection report funnels through here.
            mExpectedSelStart = newSelEnd;
            mExpectedSelEnd = newSelStart;
            return;
        }
        mExpectedSelStart = newSelStart;
        mExpectedSelEnd = newSelEnd;
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void setTextAroundCursor(final SurroundingText textAroundCursor) {
        if (null == textAroundCursor) {
            Log.e(TAG, "Unable get text around cursor.");
            applyTextAroundCursor("", 0, 0);
            return;
        }
        if (!applyTextAroundCursor(textAroundCursor.getText(),
                textAroundCursor.getSelectionStart(), textAroundCursor.getSelectionEnd())) {
            Log.e(TAG, "Text around cursor carries an out-of-range selection.");
        }
    }

    /**
     * 2026-09-25 audit, F4: the index arithmetic of {@link #setTextAroundCursor}, split out and
     * fail-closed. A host that reports a selection outside its own text (negative, inverted, or
     * past the end) used to crash the IME with a StringIndexOutOfBoundsException from the
     * subSequence calls; such a report now gets the same empty-cache treatment as a null
     * SurroundingText and the method answers false (the caller logs — this method stays
     * Android-free so the JVM tests can drive it). Package-private for those tests.
     */
    /* package */ boolean applyTextAroundCursor(final CharSequence text, final int selectionStart,
            final int selectionEnd) {
        if (null == text || selectionStart < 0 || selectionEnd < selectionStart
                || selectionEnd > text.length()) {
            onBeforeCursorCacheReloaded("");
            mTextSelection = "";
            mTextAfterCursor = "";
            return false;
        }
        onBeforeCursorCacheReloaded(text.subSequence(0, selectionStart).toString());
        mTextSelection = text.subSequence(selectionStart, selectionEnd).toString();
        mTextAfterCursor = text.subSequence(selectionEnd, text.length()).toString();
        return true;
    }

    /**
     * The single writer of the before-cursor cache together with its provenance (C6): called on
     * every FULL re-read of the cache — from {@link #setTextAroundCursor} and from the pre-S
     * branch of {@link #reloadTextCache()} — and never from a local mutation, which is the whole
     * invariant (see the field). Package-private so the JVM tests can drive the same bookkeeping
     * the Android reload paths run; the window-size rule itself lives here, exactly once.
     */
    void onBeforeCursorCacheReloaded(final String textBeforeCursor) {
        mTextBeforeCursor = textBeforeCursor;
        // The reload asks for exactly EDITOR_CONTENTS_CACHE_SIZE chars before the cursor, so a
        // shorter answer provably reached the start of the text; an answer of exactly the window
        // size may or may not have — fail closed.
        mCacheReachedTextStart = textBeforeCursor.length() < Constants.EDITOR_CONTENTS_CACHE_SIZE;
    }

    /**
     * Reload the cached text from the EditorInfo.
     */
    public void reloadTextCache(final EditorInfo editorInfo, final boolean restarting) {
        mIC = mLatinIME.getCurrentInputConnection();

        if (mExpectedSelStart != INVALID_CURSOR_POSITION && mExpectedSelEnd != INVALID_CURSOR_POSITION
            && !restarting) {
            // Updated by onUpdateSelection, don't override as editorInfo might be invalid
            // If restarting, onStartInputView was called instead of onUpdateSelection
            return;
        }
        updateSelection(editorInfo.initialSelStart, editorInfo.initialSelEnd);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            final SurroundingText textAroundCursor = editorInfo
                    .getInitialSurroundingText(Constants.EDITOR_CONTENTS_CACHE_SIZE, Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
            setTextAroundCursor(textAroundCursor);
            mLatinIME.mHandler.postUpdateShiftState();
        } else {
            reloadTextCache();
        }
    }

    /**
     * 2026-09-25 audit, F10: a reload costs IPC, and {@code onUpdateSelection} asks for one on
     * every cursor move. At most one reload is ever in flight; a request that arrives while one
     * runs folds into {@link #mReloadRequestedWhileInFlight} and produces exactly one follow-up
     * ({@link #finishReloadTextCache}) — bursts coalesce without ever leaving the cache stale.
     * Both flags are UI-thread confined ({@link #reloadTextCache} itself and the completion
     * posted back to the IME handler run there).
     */
    private boolean mReloadInFlight = false;
    private boolean mReloadRequestedWhileInFlight = false;

    /**
     * Reload the cached text from the InputConnection.
     */
    public void reloadTextCache() {
        mIC = mLatinIME.getCurrentInputConnection();
        if (!isConnected()) {
            return;
        }
        if (mReloadInFlight) {
            mReloadRequestedWhileInFlight = true;
            return;
        }
        mReloadInFlight = true;
        // To check if selection changed before text was retrieved
        final int expectedSelStart = mExpectedSelStart;
        final int expectedSelEnd = mExpectedSelEnd;

        mBackgroundThread.execute(() -> {
            boolean applyPosted = false;
            try {
                if (!isConnected()) {
                    return;
                }
                // F10: the staleness check BEFORE the IPC — a reload the selection has already
                // moved past never touches the editor. (S+ pays a binder round trip for
                // getSurroundingText, and the pre-fix order paid it before checking anything.)
                if (expectedSelStart != mExpectedSelStart || expectedSelEnd != mExpectedSelEnd) {
                    Log.w(TAG, "Selection range modified before the reload reached the editor.");
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    final SurroundingText textAroundCursor =
                            mIC.getSurroundingText(Constants.EDITOR_CONTENTS_CACHE_SIZE, Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
                    // F8: the result is APPLIED on the UI thread, where every mutation of the
                    // expected selection happens — the re-check there serializes with those
                    // mutations, so a stale read can no longer win the race and overwrite fresh
                    // state (the pre-fix code checked and wrote on this background thread).
                    mLatinIME.mHandler.post(() -> {
                        if (expectedSelStart != mExpectedSelStart || expectedSelEnd != mExpectedSelEnd) {
                            Log.w(TAG, "Selection range modified before thread completion.");
                        } else {
                            setTextAroundCursor(textAroundCursor);

                            // All callbacks that need text before cursor are here
                            mLatinIME.mHandler.postUpdateShiftState();
                            mLatinIME.mHandler.postRefreshSuggestionBand();
                        }
                        finishReloadTextCache();
                    });
                    applyPosted = true;
                } else {
                    final CharSequence textBeforeCursor = mIC.getTextBeforeCursor(Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
                    if (expectedSelStart != mExpectedSelStart) {
                        Log.w(TAG, "Selection start modified before thread completion.");
                        return;
                    }
                    if (null == textBeforeCursor) {
                        Log.e(TAG, "Unable get text before cursor.");
                        mLatinIME.mHandler.post(() -> {
                            if (expectedSelStart != mExpectedSelStart || expectedSelEnd != mExpectedSelEnd) {
                                Log.w(TAG, "Selection range modified before thread completion.");
                            } else {
                                onBeforeCursorCacheReloaded("");
                            }
                            finishReloadTextCache();
                        });
                        applyPosted = true;
                        return;
                    }
                    final String beforeCursor = textBeforeCursor.toString();

                    final CharSequence textAfterCursor = mIC.getTextAfterCursor(Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
                    if (expectedSelEnd != mExpectedSelEnd) {
                        Log.w(TAG, "Selection end modified before thread completion.");
                        return;
                    }
                    if (null == textAfterCursor) {
                        Log.e(TAG, "Unable get text after cursor.");
                    }
                    final String afterCursor =
                            null == textAfterCursor ? "" : textAfterCursor.toString();

                    final String selection;
                    if (hasSelection()) {
                        final CharSequence textSelection = mIC.getSelectedText(0);
                        if (expectedSelStart != mExpectedSelStart || expectedSelEnd != mExpectedSelEnd) {
                            Log.w(TAG, "Selection range modified before thread completion.");
                            return;
                        }
                        if (null == textSelection) {
                            Log.e(TAG, "Unable get text selection.");
                        }
                        selection = null == textSelection ? "" : textSelection.toString();
                    } else {
                        selection = "";
                    }

                    // F8: ONE atomic apply on the UI thread. The pre-fix code wrote the
                    // before-cache first and the rest after two more IPCs, so a staleness
                    // detection mid-way left a half-applied cache behind — the lost update the
                    // audit describes. The suggestion band is re-derived only at the end because
                    // it is decided by the text on BOTH sides of the cursor (a letter right
                    // after it means no candidates at all); every dropped apply above leaves the
                    // cache untouched on purpose and deliberately asks for no re-derivation.
                    mLatinIME.mHandler.post(() -> {
                        if (expectedSelStart != mExpectedSelStart || expectedSelEnd != mExpectedSelEnd) {
                            Log.w(TAG, "Selection range modified before thread completion.");
                        } else {
                            onBeforeCursorCacheReloaded(beforeCursor);
                            mTextAfterCursor = afterCursor;
                            mTextSelection = selection;

                            // All callbacks that need text before cursor are here
                            mLatinIME.mHandler.postUpdateShiftState();
                            mLatinIME.mHandler.postRefreshSuggestionBand();
                        }
                        finishReloadTextCache();
                    });
                    applyPosted = true;
                }
            } finally {
                if (!applyPosted) {
                    // The reload ends without applying (stale, disconnected, or a dying editor's
                    // RuntimeException): the in-flight flag must still clear, and a request that
                    // arrived meanwhile must still produce its one follow-up.
                    mLatinIME.mHandler.post(this::finishReloadTextCache);
                }
            }
        });
    }

    /**
     * UI-thread completion of every background reload, applied or not (F8/F10): clears the
     * in-flight flag and runs the single follow-up reload that coalesced requests folded into.
     */
    private void finishReloadTextCache() {
        mReloadInFlight = false;
        if (mReloadRequestedWhileInFlight) {
            mReloadRequestedWhileInFlight = false;
            reloadTextCache();
        }
    }

    public void clearCaches() {
        Log.i(TAG, "Clearing text caches.");
        mExpectedSelStart = INVALID_CURSOR_POSITION;
        mExpectedSelEnd = INVALID_CURSOR_POSITION;
        mTextBeforeCursor = "";
        mTextSelection = "";
        mTextAfterCursor = "";
        // No cache at all proves nothing about the text start (C6).
        mCacheReachedTextStart = false;
    }

    /**
     * Calls {@link InputConnection#commitText(CharSequence, int)}.
     *
     * @param text The text to commit. This may include styles.
     * @param newCursorPosition The new cursor position around the text.
     */
    public void commitText(final CharSequence text, final int newCursorPosition) {
        RichInputMethodManager.getInstance().resetSubtypeCycleOrder();
        appendToTextBeforeCursor(text);
        // TODO: the following is exceedingly error-prone. Right now when the cursor is in the
        // middle of the composing word mComposingText only holds the part of the composing text
        // that is before the cursor, so this actually works, but it's terribly confusing. Fix this.
        if (hasCursorPosition()) {
            mExpectedSelStart += text.length();
            mExpectedSelEnd = mExpectedSelStart;
        }
        if (isConnected()) {
            mIC.commitText(text, newCursorPosition);
        }
    }

    /**
     * 2026-09-25 audit, F3: every append to the before-cursor cache funnels here so the window
     * stays bounded — without this the cache grew with every committed char and every
     * sendKeyEvent append forever (a megabyte-long paste stayed resident in full). The cache
     * keeps the TAIL of {@link Constants#EDITOR_CONTENTS_CACHE_SIZE} chars, the size a full
     * reload asks for; cutting the head destroys the text-start edge, so the C6 provenance flag
     * clears at exactly that moment. Package-private for the JVM tests; touches only plain
     * fields, never the editor.
     */
    /* package */ void appendToTextBeforeCursor(final CharSequence text) {
        final String combined = mTextBeforeCursor + text;
        if (combined.length() <= Constants.EDITOR_CONTENTS_CACHE_SIZE) {
            mTextBeforeCursor = combined;
            return;
        }
        mTextBeforeCursor =
                combined.substring(combined.length() - Constants.EDITOR_CONTENTS_CACHE_SIZE);
        mCacheReachedTextStart = false;
    }

    public CharSequence getSelectedText() {
        return mTextSelection;
    }

    public boolean canDeleteCharacters() {
        return mExpectedSelStart > 0;
    }

    /**
     * Gets the caps modes we should be in after this specific string.
     *
     * This returns a bit set of TextUtils#CAP_MODE_*, masked by the inputType argument.
     * This method also supports faking an additional space after the string passed in argument,
     * to support cases where a space will be added automatically, like in phantom space
     * state for example.
     * Note that for English, we are using American typography rules (which are not specific to
     * American English, it's just the most common set of rules for English).
     *
     * @param inputType a mask of the caps modes to test for.
     * @param spacingAndPunctuations the values of the settings to use for locale and separators.
     * @return the caps modes that should be on as a set of bits
     */
    public int getCursorCapsMode(final int inputType, final SpacingAndPunctuations spacingAndPunctuations) {
        mIC = mLatinIME.getCurrentInputConnection();
        if (!isConnected()) {
            return Constants.TextUtils.CAP_MODE_OFF;
        }
        // This never calls InputConnection#getCapsMode - in fact, it's a static method that
        // never blocks or initiates IPC.
        // TODO: don't call #toString() here. Instead, all accesses to
        // mCommittedTextBeforeComposingText should be done on the main thread.
        return CapsModeUtils.getCapsMode(mTextBeforeCursor, inputType,
                spacingAndPunctuations);
    }

    /**
     * Returns the cached text before the cursor without any IPC to the editor.
     *
     * <p>Like {@link #getCursorCapsMode}, this reads only the local cache and never calls
     * {@link android.view.inputmethod.InputConnection}. Must be called on the UI thread. The
     * returned text is potentially sensitive and must never be logged.
     *
     * @return the cached before-cursor text, or the empty string if it is not available.
     */
    public CharSequence getCachedTextBeforeCursor() {
        // Never initiates IPC; reads only the local cache. Do not log the returned value.
        return mTextBeforeCursor == null ? "" : mTextBeforeCursor;
    }

    /**
     * Whether the before-cursor cache provably starts at the very start of the editor's text —
     * the knowledge a full reload had when it filled the cache (C6, see the field). Reads no
     * editor state and never initiates IPC, exactly like {@link #getCachedTextBeforeCursor}; the
     * two are meant to be consumed together.
     */
    public boolean cacheReachedTextStart() {
        return mCacheReachedTextStart;
    }

    /**
     * Returns the cached text after the cursor without any IPC to the editor.
     *
     * <p>Same contract as {@link #getCachedTextBeforeCursor}: reads only the local cache, never
     * calls {@link android.view.inputmethod.InputConnection}, and must be called on the UI thread.
     * The returned text is potentially sensitive and must never be logged.
     *
     * @return the cached after-cursor text, or the empty string if it is not available.
     */
    public CharSequence getCachedTextAfterCursor() {
        // Never initiates IPC; reads only the local cache. Do not log the returned value.
        return mTextAfterCursor == null ? "" : mTextAfterCursor;
    }

    public int getCodePointBeforeCursor() {
        final int length = mTextBeforeCursor.length();
        if (length < 1) return Constants.NOT_A_CODE;
        return Character.codePointBefore(mTextBeforeCursor, length);
    }

    /**
     * Gets the code point at the given offset before the cursor, reading only the local cache.
     *
     * @param offsetCodePoints how many code points to skip back from the cursor; 0 is equivalent
     * to {@link #getCodePointBeforeCursor()}.
     */
    public int getCodePointBeforeCursor(final int offsetCodePoints) {
        int index = mTextBeforeCursor.length();
        for (int i = 0; i < offsetCodePoints; i++) {
            if (index < 1) return Constants.NOT_A_CODE;
            index -= Character.isSupplementaryCodePoint(
                    Character.codePointBefore(mTextBeforeCursor, index)) ? 2 : 1;
        }
        if (index < 1) return Constants.NOT_A_CODE;
        return Character.codePointBefore(mTextBeforeCursor, index);
    }

    public void replaceText(final int startPosition, final int endPosition, CharSequence text) {
        if (mExpectedSelStart != mExpectedSelEnd) {
            Log.e(TAG, "replaceText called with text range selected");
            return;
        }
        if (mExpectedSelStart != startPosition) {
            Log.e(TAG, "replaceText called with range not starting with current cursor position");
            return;
        }

        final int numCharsSelected = endPosition - startPosition;
        final String textAfterCursor = mTextAfterCursor;
        if (textAfterCursor.length() < numCharsSelected) {
            Log.e(TAG, "replaceText called with range longer than current text");
            return;
        }

        // 2026-09-25 audit, F6: refresh and check the connection BEFORE mutating the cache —
        // with a dead editor this used to NPE on a stale mIC after the cache had already been
        // changed, reporting an edit no editor received.
        mIC = mLatinIME.getCurrentInputConnection();
        if (!isConnected()) {
            return;
        }
        mTextAfterCursor = text + textAfterCursor.substring(numCharsSelected);

        RichInputMethodManager.getInstance().resetSubtypeCycleOrder();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mIC.replaceText(startPosition, endPosition, text, 0, null);
        } else {
            mIC.deleteSurroundingText(0, numCharsSelected);
            mIC.commitText(text, 0);
        }
    }

    public void deleteTextBeforeCursor(final int numChars) {
        String textBeforeCursor = mTextBeforeCursor;
        if (!textBeforeCursor.isEmpty() && textBeforeCursor.length() >= numChars) {
            mTextBeforeCursor = textBeforeCursor.substring(0, textBeforeCursor.length() - numChars);
        }
        if (hasCursorPosition()) {
            if (mExpectedSelStart >= numChars) {
                mExpectedSelStart -= numChars;
            }
            // Deleting before the cursor always leaves it collapsed: every other mutator of this
            // class does the same. Leaving the end behind made the keyboard believe a selection
            // was active after a plain backspace, which rejected taps on suggestions and made
            // onUpdateSelection mistake our own backspace for an external cursor move.
            mExpectedSelEnd = mExpectedSelStart;
        }

        if (isConnected()) {
            mIC.deleteSurroundingText(numChars, 0);
        }
    }

    public void deleteSelectedText() {
        if (mExpectedSelStart == mExpectedSelEnd) {
            Log.e(TAG, "deleteSelectedText called with text range not selected");
            return;
        }

        beginBatchEdit();
        // 2026-09-25 audit, F5: the batch closes in finally even when the editor dies mid-edit
        // — a skipped endBatchEdit would stick the nest level forever. Nothing is caught: the
        // exception propagates exactly as before.
        try {
            // F6: beginBatchEdit() is what refreshes the connection from the framework; a dead
            // editor gets no edit at all rather than a cache-only mutation (the same doctrine
            // the commit paths follow).
            if (!isConnected()) {
                return;
            }
            final int selectionLength = mExpectedSelEnd - mExpectedSelStart;
            mTextSelection = "";
            setSelection(mExpectedSelStart, mExpectedSelStart);
            mIC.deleteSurroundingText(0, selectionLength);
        } finally {
            endBatchEdit();
        }
    }

    public void performEditorAction(final int actionId) {
        mIC = mLatinIME.getCurrentInputConnection();
        if (isConnected()) {
            mIC.performEditorAction(actionId);
        }
    }

    /**
     * 2026-09-25 audit, F1: below this length the clipboard text is committed through the normal
     * text-input path; at or above it the commit would parcel the whole string into the editor
     * process, and a large enough clip (~0.5 MB and up) kills the IME with a
     * TransactionTooLargeException. Large pastes fall through to the editor's own context-menu
     * paste instead — the editor pulls the clipboard itself and no parcel copy crosses the
     * binder. 64 Ki chars is an order of magnitude under the binder's ~1 MB transaction cap.
     */
    private static final int MAX_DIRECT_PASTE_CHARS = 64 * 1024;

    /**
     * The F1 threshold decision, package-private and Android-free so the JVM tests can pin the
     * boundary: only a non-empty clip strictly below {@link #MAX_DIRECT_PASTE_CHARS} is committed
     * by the IME itself.
     */
    /* package */ static boolean shouldCommitPasteDirectly(final CharSequence pasteData) {
        return pasteData != null && pasteData.length() > 0
                && pasteData.length() < MAX_DIRECT_PASTE_CHARS;
    }

    public void pasteClipboard() {
        final ClipboardManager clipboard = (ClipboardManager) mLatinIME.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            final ClipData clipData = clipboard.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() == 1) {
                final String mimeType = clipData.getDescription().getMimeType(0);
                if (MIMETYPE_TEXT_PLAIN.equals(mimeType) || MIMETYPE_TEXT_HTML.equals(mimeType)) {
                    final CharSequence pasteData = clipData.getItemAt(0).getText();
                    if (shouldCommitPasteDirectly(pasteData)) {
                        mLatinIME.onTextInput(pasteData.toString());
                        return;
                    }
                }
            }
        }

        // 2026-09-25 audit, F6: refresh the connection before talking to the editor — the field
        // can hold a stale (or null) connection from an earlier batch.
        mIC = mLatinIME.getCurrentInputConnection();
        if (!isConnected()) {
            return;
        }
        mIC.performContextMenuAction(android.R.id.paste);
    }

    public void sendKeyEvent(final KeyEvent keyEvent) {
        RichInputMethodManager.getInstance().resetSubtypeCycleOrder();
        if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
            // This method is only called for enter or backspace when speaking to old applications
            // (target SDK <= 15 (Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)), or for digits.
            // When talking to new applications we never use this method because it's inherently
            // racy and has unpredictable results, but for backward compatibility we continue
            // sending the key events for only Enter and Backspace because some applications
            // mistakenly catch them to do some stuff.
            switch (keyEvent.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER:
                appendToTextBeforeCursor("\n");
                if (hasCursorPosition()) {
                    mExpectedSelStart += 1;
                    mExpectedSelEnd = mExpectedSelStart;
                }
                break;
            case KeyEvent.KEYCODE_UNKNOWN:
                if (null != keyEvent.getCharacters()) {
                    appendToTextBeforeCursor(keyEvent.getCharacters());
                    if (hasCursorPosition()) {
                        mExpectedSelStart += keyEvent.getCharacters().length();
                        mExpectedSelEnd = mExpectedSelStart;
                    }
                }
                break;
            case KeyEvent.KEYCODE_DEL:
                break;
            default:
                final String text = StringUtils.newSingleCodePointString(keyEvent.getUnicodeChar());
                appendToTextBeforeCursor(text);
                if (hasCursorPosition()) {
                    mExpectedSelStart += text.length();
                    mExpectedSelEnd = mExpectedSelStart;
                }
                break;
            }
        }
        if (isConnected()) {
            mIC.sendKeyEvent(keyEvent);
        }
    }

    /**
     * Set the selection of the text editor.
     *
     * Calls through to {@link InputConnection#setSelection(int, int)}.
     *
     * @param start the character index where the selection should start.
     * @param end the character index where the selection should end.
     * valid when setting the selection or when retrieving the text cache at that point, or
     * invalid arguments were passed.
     */
    public void setSelection(int start, int end) {
        if (start < 0 || end < 0 || start > end) {
            return;
        }
        if (mExpectedSelStart == start && mExpectedSelEnd == end) {
            return;
        }

        final int textStart = mExpectedSelStart - mTextBeforeCursor.length();
        final String textRange = mTextBeforeCursor + mTextSelection + mTextAfterCursor;
        if (textRange.length() >= end - textStart && start - textStart >= 0 && textStart >= 0) {
            // The cached window may not cover the requested range (a reload has not caught up
            // yet) — skip the re-slice in that case; the next reload replaces the whole window.
            mTextBeforeCursor = textRange.substring(0, start - textStart);
            mTextSelection = textRange.substring(start - textStart, end - textStart);
            mTextAfterCursor = textRange.substring(end - textStart);
        }

        RichInputMethodManager.getInstance().resetSubtypeCycleOrder();

        mExpectedSelStart = start;
        mExpectedSelEnd = end;
        if (isConnected()) {
            mIC.setSelection(start, end);
        }
    }

    public int getExpectedSelectionStart() {
        return mExpectedSelStart;
    }

    public int getExpectedSelectionEnd() {
        return mExpectedSelEnd;
    }

    /**
     * @return whether there is a selection currently active.
     */
    public boolean hasSelection() {
        return mExpectedSelEnd != mExpectedSelStart;
    }

    public boolean hasCursorPosition() {
        return mExpectedSelStart != INVALID_CURSOR_POSITION && mExpectedSelEnd != INVALID_CURSOR_POSITION;
    }

    /**
     * Some chars, such as emoji consist of 2 chars (surrogate pairs). We should treat them as one character.
     * Some chars are joined with ZERO WIDTH JOINER (U+200D), pairs need to be counted
     */
    public int getUnicodeSteps(int chars, boolean rightSidePointer) {
        int steps = 0;
        if (chars < 0) {
            CharSequence charsBeforeCursor = rightSidePointer && hasSelection() ?
                    getSelectedText() :
                    mTextBeforeCursor;
            // 2026-09-25 audit, F11: this was a `== ""` reference comparison. Not `.isEmpty()`:
            // CharSequence.isEmpty() is a default method that exists only from API 35 (checked
            // against the API-34 android.jar) and this app ships minSdk 24 with no core
            // desugaring — length() == 0 is the equivalent that runs everywhere.
            if (charsBeforeCursor == null || charsBeforeCursor.length() == 0) {
                return chars;
            }
            for (int i = charsBeforeCursor.length() - 1; i >= 0 && chars < 0; i--, steps--) {
                // F11: the boundary is i >= 1 — charAt(i - 1) is valid there. The old i > 1
                // skipped the ZWJ check at index 1, so a cached window that starts mid-cluster
                // (a ZWJ at index 0) was split: the step stopped short and left the dangling
                // joiner behind the cursor.
                if (i >= 1 && charsBeforeCursor.charAt(i - 1) == '\u200d') {
                    continue;
                }
                if (charsBeforeCursor.charAt(i) == '\u200d') {
                    continue;
                }
                if (Character.isSurrogate(charsBeforeCursor.charAt(i)) &&
                        !Character.isHighSurrogate(charsBeforeCursor.charAt(i))) {
                    continue;
                }
                chars++;
            }
        } else if (chars > 0) {
            CharSequence charsAfterCursor = !rightSidePointer && hasSelection() ?
                    getSelectedText() :
                    mTextAfterCursor;
            if (charsAfterCursor == null || charsAfterCursor.length() == 0) {
                return chars;
            }
            for (int i = 0; i < charsAfterCursor.length() && chars > 0; i++, steps++) {
                if (i < charsAfterCursor.length() - 1 && charsAfterCursor.charAt(i + 1) == '\u200d') {
                    continue;
                }
                if (charsAfterCursor.charAt(i) == '\u200d') {
                    continue;
                }
                if (Character.isHighSurrogate(charsAfterCursor.charAt(i))) {
                    continue;
                }
                chars--;
            }
        }
        return steps;
    }
}
