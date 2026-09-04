/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2024 wittmane
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

package rkr.simplekeyboard.inputmethod.keyboard;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.event.Event;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardLayoutSet.KeyboardLayoutSetException;
import rkr.simplekeyboard.inputmethod.keyboard.internal.KeyboardState;
import rkr.simplekeyboard.inputmethod.keyboard.internal.KeyboardTextsSet;
import rkr.simplekeyboard.inputmethod.latin.InputView;
import rkr.simplekeyboard.inputmethod.latin.LatinIME;
import rkr.simplekeyboard.inputmethod.latin.RichInputMethodManager;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiPanelView;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSearchIndex;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSearchView;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSkinTones;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSetSnapshot;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;
import rkr.simplekeyboard.inputmethod.latin.utils.CapsModeUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.LanguageOnSpacebarUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.RecapitalizeStatus;
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils;

public final class KeyboardSwitcher implements KeyboardState.SwitchActions,
        EmojiPanelView.Listener, EmojiSearchView.Listener {
    private static final String TAG = KeyboardSwitcher.class.getSimpleName();

    private MainKeyboardView mKeyboardView;

    /**
     * The user's "Bottom offset" in px, as last handed to the keyboard geometry. The letter
     * keyboard lifts its rows by it; the emoji panel has to reserve the same strip, or it fills
     * the space the user deliberately freed and the surface jumps when the two swap
     * (docs/DEVICE-RESEARCH-GEOMETRY.md, Р-1). Kept here because {@link #showEmojiPanel} runs long
     * after {@link #loadKeyboard} and has no SettingsValues of its own.
     */
    private int mKeyboardBottomOffset;
    private InputView mCurrentInputView;
    private boolean mEmojiPanelShown;
    private boolean mEmojiSearchShown;

    /** Held so a panel created after the table arrived (or recreated later) still gets it. */
    private EmojiSkinTones mEmojiSkinTones;
    private LatinIME mLatinIME;
    private RichInputMethodManager mRichImm;

    private KeyboardState mState;

    private KeyboardLayoutSet mKeyboardLayoutSet;
    // TODO: The following {@link KeyboardTextsSet} should be in {@link KeyboardLayoutSet}.
    private final KeyboardTextsSet mKeyboardTextsSet = new KeyboardTextsSet();

    private KeyboardTheme mKeyboardTheme;
    private Context mThemeContext;

    private static final KeyboardSwitcher sInstance = new KeyboardSwitcher();

    public static KeyboardSwitcher getInstance() {
        return sInstance;
    }

    private KeyboardSwitcher() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final LatinIME latinIme) {
        sInstance.initInternal(latinIme);
    }

    private void initInternal(final LatinIME latinIme) {
        mLatinIME = latinIme;
        mRichImm = RichInputMethodManager.getInstance();
        mState = new KeyboardState(this);
    }

    public void updateKeyboardTheme() {
        final boolean themeUpdated = updateKeyboardThemeAndContextThemeWrapper(
                mLatinIME, KeyboardTheme.getKeyboardTheme(mLatinIME));
        if (themeUpdated && mKeyboardView != null) {
            mLatinIME.setInputView(onCreateInputView());
        }
    }

    public void onConfigurationChanged() {
        mKeyboardTheme = KeyboardTheme.getKeyboardTheme(mLatinIME);
        mThemeContext = new ContextThemeWrapper(mLatinIME, mKeyboardTheme.mStyleId);
        KeyboardLayoutSet.onKeyboardThemeChanged();
        if (mKeyboardView != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Live color pallet reloading doesn't work, need to rerender the View
            mLatinIME.setInputView(onCreateInputView());
        }
    }

    private boolean updateKeyboardThemeAndContextThemeWrapper(final Context context,
            final KeyboardTheme keyboardTheme) {
        if (mThemeContext == null || !keyboardTheme.equals(mKeyboardTheme)) {
            mKeyboardTheme = keyboardTheme;
            mThemeContext = new ContextThemeWrapper(context, keyboardTheme.mStyleId);
            KeyboardLayoutSet.onKeyboardThemeChanged();
            return true;
        }
        return false;
    }

    public void loadKeyboard(final EditorInfo editorInfo, final SettingsValues settingsValues,
            final int currentAutoCapsState, final int currentRecapitalizeState) {
        final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(
                mThemeContext, editorInfo);
        final Resources res = mThemeContext.getResources();
        final int keyboardWidth = mLatinIME.getMaxWidth();
        final int keyboardHeight = ResourceUtils.getKeyboardHeight(res, settingsValues);
        final int keyboardBottomOffset = ResourceUtils.getKeyboardBottomOffset(res, settingsValues);
        mKeyboardBottomOffset = keyboardBottomOffset;
        builder.setKeyboardTheme(mKeyboardTheme.mThemeId);
        builder.setKeyboardGeometry(keyboardWidth, keyboardHeight, keyboardBottomOffset);
        builder.setSubtype(mRichImm.getCurrentSubtype());
        builder.setLanguageSwitchKeyEnabled(mLatinIME.shouldShowLanguageSwitchKey());
        builder.setShowSpecialChars(settingsValues.mShowSpecialChars);
        builder.setShowNumberRow(settingsValues.mShowNumberRow);
        builder.setShowEmojiKey(settingsValues.mShowEmojiKey);
        mKeyboardLayoutSet = builder.build();
        try {
            mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState);
            mKeyboardTextsSet.setLocale(mRichImm.getCurrentSubtype().getLocaleObject(),
                    mThemeContext);
        } catch (KeyboardLayoutSetException e) {
            Log.w(TAG, "loading keyboard failed: " + e.mKeyboardId, e.getCause());
        }
    }

    public void onHideWindow() {
        if (mKeyboardView != null) {
            mKeyboardView.onHideWindow();
        }
    }

    private void setKeyboard(
            final int keyboardId,
            final KeyboardSwitchState toggleState) {
        final SettingsValues currentSettingsValues = Settings.getInstance().getCurrent();
        setMainKeyboardFrame(currentSettingsValues, toggleState);
        // TODO: pass this object to setKeyboard instead of getting the current values.
        final MainKeyboardView keyboardView = mKeyboardView;
        final Keyboard oldKeyboard = keyboardView.getKeyboard();
        final Keyboard newKeyboard = mKeyboardLayoutSet.getKeyboard(keyboardId);
        keyboardView.setKeyboard(newKeyboard);
        // Suppress the key preview popup in password fields so typed characters are not
        // exposed on screen, regardless of the user preference.
        keyboardView.setKeyPreviewPopupEnabled(
                currentSettingsValues.mKeyPreviewPopupOn
                        && !currentSettingsValues.mInputAttributes.mIsPasswordField,
                currentSettingsValues.mKeyPreviewPopupDismissDelay);
        final boolean subtypeChanged = (oldKeyboard == null)
                || !newKeyboard.mId.mSubtype.equals(oldKeyboard.mId.mSubtype);
        final int languageOnSpacebarFormatType = LanguageOnSpacebarUtils
                .getLanguageOnSpacebarFormatType(newKeyboard.mId.mSubtype);
        keyboardView.startDisplayLanguageOnSpacebar(subtypeChanged, languageOnSpacebarFormatType);
    }

    public Keyboard getKeyboard() {
        if (mKeyboardView != null) {
            return mKeyboardView.getKeyboard();
        }
        return null;
    }

    // TODO: Remove this method. Come up with a more comprehensive way to reset the keyboard layout
    // when a keyboard layout set doesn't get reloaded in LatinIME.onStartInputViewInternal().
    public void resetKeyboardStateToAlphabet(final int currentAutoCapsState,
            final int currentRecapitalizeState) {
        mState.onResetKeyboardStateToAlphabet(currentAutoCapsState, currentRecapitalizeState);
    }

    public void onPressKey(final int code, final boolean isSinglePointer,
            final int currentAutoCapsState, final int currentRecapitalizeState) {
        mState.onPressKey(code, isSinglePointer, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onReleaseKey(final int code, final boolean withSliding,
            final int currentAutoCapsState, final int currentRecapitalizeState) {
        mState.onReleaseKey(code, withSliding, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onFinishSlidingInput(final int currentAutoCapsState,
            final int currentRecapitalizeState) {
        mState.onFinishSlidingInput(currentAutoCapsState, currentRecapitalizeState);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetManualShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetManualShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetAutomaticShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetAutomaticShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_AUTOMATIC_SHIFTED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetShiftLockedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetShiftLockedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setSymbolsKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_SYMBOLS, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setSymbolsShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_SYMBOLS_SHIFTED, KeyboardSwitchState.SYMBOLS_SHIFTED);
    }

    public boolean isImeSuppressedByHardwareKeyboard(
            final SettingsValues settingsValues,
            final KeyboardSwitchState toggleState) {
        return settingsValues.mHasHardwareKeyboard && toggleState == KeyboardSwitchState.HIDDEN;
    }

    private void setMainKeyboardFrame(
            final SettingsValues settingsValues,
            final KeyboardSwitchState toggleState) {
        // While the emoji panel is the active surface, MainKeyboardView must stay hidden even if a
        // loadKeyboard/setKeyboard runs underneath it: the two surfaces are never visible at once.
        // The search keeps the letter keyboard on screen — that is the whole point of it — so only
        // the panel proper hides MainKeyboardView.
        final int visibility = ((mEmojiPanelShown && !mEmojiSearchShown)
                || isImeSuppressedByHardwareKeyboard(settingsValues, toggleState))
                ? View.GONE : View.VISIBLE;
        mKeyboardView.setVisibility(visibility);
    }

    public enum KeyboardSwitchState {
        HIDDEN(-1),
        SYMBOLS_SHIFTED(KeyboardId.ELEMENT_SYMBOLS_SHIFTED),
        OTHER(-1);

        final int mKeyboardId;

        KeyboardSwitchState(int keyboardId) {
            mKeyboardId = keyboardId;
        }
    }

    public KeyboardSwitchState getKeyboardSwitchState() {
        if (mEmojiPanelShown) {
            // The panel is a visible surface, so the IME is not hidden even though
            // MainKeyboardView is GONE. Returning HIDDEN here would let the framework clear the
            // touchable region (and would suppress the IME under a physical keyboard), dropping
            // touches on the panel into the application behind it.
            return KeyboardSwitchState.OTHER;
        }
        boolean hidden = mKeyboardLayoutSet == null
                || mKeyboardView == null
                || !mKeyboardView.isShown();
        if (hidden) {
            return KeyboardSwitchState.HIDDEN;
        } else if (isShowingKeyboardId(KeyboardId.ELEMENT_SYMBOLS_SHIFTED)) {
            return KeyboardSwitchState.SYMBOLS_SHIFTED;
        }
        return KeyboardSwitchState.OTHER;
    }

    // Future method for requesting an updating to the shift state.
    @Override
    public void requestUpdatingShiftState(final int autoCapsFlags, final int recapitalizeMode) {
        if (DEBUG_ACTION) {
            Log.d(TAG, "requestUpdatingShiftState: "
                    + " autoCapsFlags=" + CapsModeUtils.flagsToString(autoCapsFlags)
                    + " recapitalizeMode=" + RecapitalizeStatus.modeToString(recapitalizeMode));
        }
        mState.onUpdateShiftState(autoCapsFlags, recapitalizeMode);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void startDoubleTapShiftKeyTimer() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "startDoubleTapShiftKeyTimer");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        if (keyboardView != null) {
            keyboardView.startDoubleTapShiftKeyTimer();
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void cancelDoubleTapShiftKeyTimer() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "setAlphabetKeyboard");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        if (keyboardView != null) {
            keyboardView.cancelDoubleTapShiftKeyTimer();
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public boolean isInDoubleTapShiftKeyTimeout() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "isInDoubleTapShiftKeyTimeout");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        return keyboardView != null && keyboardView.isInDoubleTapShiftKeyTimeout();
    }

    /**
     * Updates state machine to figure out when to automatically switch back to the previous mode.
     */
    public void onEvent(final Event event, final int currentAutoCapsState,
            final int currentRecapitalizeState) {
        mState.onEvent(event, currentAutoCapsState, currentRecapitalizeState);
    }

    public boolean isShowingKeyboardId(int... keyboardIds) {
        if (mKeyboardView == null || !mKeyboardView.isShown()) {
            return false;
        }
        int activeKeyboardId = mKeyboardView.getKeyboard().mId.mElementId;
        for (int keyboardId : keyboardIds) {
            if (activeKeyboardId == keyboardId) {
                return true;
            }
        }
        return false;
    }

    public boolean isShowingMoreKeysPanel() {
        return mKeyboardView.isShowingMoreKeysPanel();
    }

    public View getVisibleKeyboardView() {
        if (mEmojiPanelShown && !mEmojiSearchShown && mCurrentInputView != null) {
            final View panel = mCurrentInputView.getEmojiPanelView();
            if (panel != null) {
                return panel;
            }
        }
        return mKeyboardView;
    }

    public boolean isEmojiPanelShown() {
        return mEmojiPanelShown;
    }

    /**
     * Replaces the keyboard surface with the emoji panel. Called from the emoji key's functional
     * event; it never edits the editor. The panel is sized to the current keyboard height so the
     * content top inset is unchanged, and MainKeyboardView goes {@code GONE} so the two surfaces
     * are never visible at once.
     */
    public void showEmojiPanel(final EmojiSetSnapshot snapshot) {
        if (mKeyboardView == null || mCurrentInputView == null) {
            return;
        }
        final EmojiPanelView panel = mCurrentInputView.showEmojiPanel(
                mKeyboardView.getHeight(), snapshot);
        if (panel == null) {
            return;
        }
        panel.setKeyboardBottomOffsetPx(mKeyboardBottomOffset);
        panel.setListener(this);
        if (mEmojiSkinTones != null) {
            panel.setSkinTones(mEmojiSkinTones);
        }
        mEmojiPanelShown = true;
        mKeyboardView.setVisibility(View.GONE);
    }

    /**
     * Binds the skin-tone table to the panel. Kept here rather than passed through
     * {@link #showEmojiPanel}: the table is read once per process and outlives every show.
     */
    public void bindEmojiSkinTones(final EmojiSkinTones tones) {
        mEmojiSkinTones = tones;
        if (mCurrentInputView == null) {
            return;
        }
        final EmojiPanelView panel = mCurrentInputView.getEmojiPanelView();
        if (panel != null) {
            panel.setSkinTones(tones);
        }
    }

    public boolean isEmojiSearchShown() {
        return mEmojiSearchShown;
    }

    /**
     * Enters the emoji search: the grid steps aside, the letter keyboard comes back and the two
     * search bands take the suggestion strip's place. The panel keeps its snapshot and its scroll,
     * so leaving the search puts back exactly the grid the user came from.
     */
    public void showEmojiSearch(final EmojiSearchIndex index) {
        if (mCurrentInputView == null || !mEmojiPanelShown) {
            return;
        }
        final EmojiSearchView search = mCurrentInputView.enterEmojiSearch(index);
        if (search == null) {
            return;
        }
        search.setListener(this);
        mEmojiSearchShown = true;
        setMainKeyboardFrame(Settings.getInstance().getCurrent(), KeyboardSwitchState.OTHER);
    }

    /** Hands the current query text to the search bands, which re-run the match and redraw. */
    public void setEmojiSearchQuery(final String query) {
        if (!mEmojiSearchShown || mCurrentInputView == null) {
            return;
        }
        final EmojiSearchView search = mCurrentInputView.getEmojiSearchView();
        if (search != null) {
            search.setQuery(query);
        }
    }

    /** Leaves the emoji search and shows the emoji panel again; a no-op when no search is open. */
    public void leaveEmojiSearch() {
        if (!mEmojiSearchShown) {
            return;
        }
        mEmojiSearchShown = false;
        if (mCurrentInputView != null) {
            mCurrentInputView.leaveEmojiSearch();
        }
        if (mKeyboardView != null) {
            setMainKeyboardFrame(Settings.getInstance().getCurrent(), KeyboardSwitchState.OTHER);
        }
    }

    /** Hides the emoji panel and restores the keyboard surface through the normal visibility path. */
    public void hideEmojiPanel() {
        if (!mEmojiPanelShown) {
            return;
        }
        mEmojiPanelShown = false;
        if (mEmojiSearchShown) {
            mEmojiSearchShown = false;
            if (mCurrentInputView != null) {
                mCurrentInputView.hideEmojiSearch();
            }
        }
        if (mCurrentInputView != null) {
            mCurrentInputView.hideEmojiPanel();
        }
        if (mKeyboardView != null) {
            setMainKeyboardFrame(Settings.getInstance().getCurrent(), KeyboardSwitchState.OTHER);
        }
        // Persist the recent-emoji list at most once per hide (only if it changed), off the UI thread.
        if (mLatinIME != null) {
            mLatinIME.onEmojiPanelHidden();
        }
    }

    // Implements {@link EmojiPanelView.Listener}. The "АБВ" key returns to the letters.
    @Override
    public void onEmojiPanelBackToKeyboard() {
        hideEmojiPanel();
    }

    // Implements {@link EmojiPanelView.Listener}. Delete routes through the ordinary code-input
    // path, so the emoji-cluster-aware backspace from E2a applies here too.
    @Override
    public void onEmojiPanelDelete() {
        if (mLatinIME != null) {
            mLatinIME.onCodeInput(Constants.CODE_DELETE, Constants.NOT_A_COORDINATE,
                    Constants.NOT_A_COORDINATE, false);
        }
    }

    // Implements {@link EmojiPanelView.Listener}. The search pill opens the emoji-search mode,
    // where the letter keyboard comes back and the query is typed into the keyboard itself.
    @Override
    public void onEmojiPanelSearch() {
        if (mLatinIME != null) {
            mLatinIME.onEmojiSearchRequested();
        }
    }

    // Implements {@link EmojiPanelView.Listener}. Insertion goes solely through the ordinary
    // text-input path; the panel never commits text itself. The use is then recorded in the
    // recent-emoji list through the gated, serialized controller path.
    @Override
    public void onEmojiPanelPick(final String sequence) {
        if (mLatinIME != null) {
            mLatinIME.onTextInput(sequence);
            mLatinIME.onEmojiInserted(sequence);
        }
    }

    // Implements {@link EmojiSearchView.Listener}. Leaving the search brings the grid back.
    @Override
    public void onEmojiSearchClosed() {
        if (mLatinIME != null) {
            mLatinIME.onEmojiSearchClosed();
        }
    }

    // Implements {@link EmojiSearchView.Listener}. A picked result travels the very same path as a
    // panel cell: the ordinary text-input route, then the gated recent-emoji recording.
    @Override
    public void onEmojiSearchPick(final String sequence) {
        if (mLatinIME != null) {
            mLatinIME.onTextInput(sequence);
            mLatinIME.onEmojiInserted(sequence);
        }
    }

    public MainKeyboardView getMainKeyboardView() {
        return mKeyboardView;
    }

    public void deallocateMemory() {
        if (mKeyboardView != null) {
            mKeyboardView.cancelAllOngoingEvents();
            mKeyboardView.deallocateMemory();
        }
        releaseEmojiPanelCaches();
    }

    /**
     * Frees the emoji panel's bound snapshot and per-layout caches under memory pressure
     * ({@code MSG_DEALLOCATE_MEMORY}, 10 s) or when input finished. The panel holds no offscreen
     * {@code Bitmap}, so there is nothing else to free; the controller keeps the single prepared
     * snapshot for the process, so the next show re-binds it. Skipped while the panel is the shown
     * surface so it never blanks a live grid.
     */
    public void releaseEmojiPanelCaches() {
        if (mEmojiPanelShown || mEmojiSearchShown || mCurrentInputView == null) {
            return;
        }
        final EmojiPanelView panel = mCurrentInputView.getEmojiPanelView();
        if (panel != null) {
            panel.releaseSnapshotCaches();
        }
    }

    public View onCreateInputView() {
        if (mKeyboardView != null) {
            mKeyboardView.closing();
        }

        updateKeyboardThemeAndContextThemeWrapper(
                mLatinIME, KeyboardTheme.getKeyboardTheme(mLatinIME /* context */));
        final InputView currentInputView = (InputView) LayoutInflater.from(mThemeContext).inflate(
                R.layout.input_view, null);

        mKeyboardView = currentInputView.findViewById(R.id.keyboard_view);
        mKeyboardView.setKeyboardActionListener(mLatinIME);
        mCurrentInputView = currentInputView;
        // The "panel is open" state never survives an input-view recreation (rotation, theme or
        // height change): the panel closes and the letters come back.
        mEmojiPanelShown = false;
        mEmojiSearchShown = false;
        return currentInputView;
    }
}
