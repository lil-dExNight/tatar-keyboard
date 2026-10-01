/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2021 wittmane
 * Copyright (C) 2021 Maarten Trompper
 * Copyright (C) 2019 Micha LaQua
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

import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.Debug;
import android.os.IBinder;
import android.os.Message;
import android.os.Trace;
import android.os.UserManager;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import kotlin.jvm.functions.Function2;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.compat.EditorInfoCompatUtils;
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat;
import rkr.simplekeyboard.inputmethod.event.Event;
import rkr.simplekeyboard.inputmethod.event.InputTransaction;
import rkr.simplekeyboard.inputmethod.keyboard.Keyboard;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardActionListener;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardId;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardSwitcher;
import rkr.simplekeyboard.inputmethod.keyboard.MainKeyboardView;
import rkr.simplekeyboard.inputmethod.keyboard.PointerTracker;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.inputlogic.InputLogic;
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec;
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramSource;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalEmojiSource;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramDictionaries;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramLearning;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaries;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalEmojiDictionaries;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalEmojiEventSink;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalEmojiLearning;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalForget;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalLearning;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalLearningGates;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiPanelController;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSearchIndex;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSearchQuery;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSkinTones;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSetSnapshot;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSurface;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiTextUtils;
import rkr.simplekeyboard.inputmethod.latin.emoji.RecentEmojiGate;
import rkr.simplekeyboard.inputmethod.latin.emoji.RecentEmojiGateState;
import rkr.simplekeyboard.inputmethod.latin.emoji.SharedEmojiSearchIndex;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;
import rkr.simplekeyboard.inputmethod.latin.suggestions.EditorSurface;
import rkr.simplekeyboard.inputmethod.latin.suggestions.EngineHandle;
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.FallbackWordsFactory;
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.FuzzyEditPolicy;
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.GlobalTopFrequencyFallbackFactory;
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable;
import rkr.simplekeyboard.inputmethod.latin.suggestions.KeyNeighborTableBuilder;
import rkr.simplekeyboard.inputmethod.latin.suggestions.GlideKeyGeometryBuilder;
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry;
import rkr.simplekeyboard.inputmethod.latin.suggestions.MappedEngineHandle;
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules;
import rkr.simplekeyboard.inputmethod.latin.suggestions.OfferEnvironment;
import rkr.simplekeyboard.inputmethod.latin.suggestions.OfferFlagStore;
import rkr.simplekeyboard.inputmethod.latin.suggestions.OfferPresenter;
import rkr.simplekeyboard.inputmethod.latin.suggestions.PersonalEmojiSink;
import rkr.simplekeyboard.inputmethod.latin.suggestions.ResultCallback;
import rkr.simplekeyboard.inputmethod.latin.suggestions.StripSurface;
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionStripView;
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionTapListener;
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionsController;
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionsOfferController;
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ApplicationUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.DialogUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.InputTypeUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.LeakGuardHandlerWrapper;
import rkr.simplekeyboard.inputmethod.latin.utils.LocaleResourceUtils;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIME extends InputMethodService implements KeyboardActionListener,
        RichInputMethodManager.SubtypeChangedListener {
    static final String TAG = LatinIME.class.getSimpleName();
    private static final boolean TRACE = false;

    private static final int EXTENDED_TOUCHABLE_REGION_HEIGHT = 100;
    private static final int PENDING_IMS_CALLBACK_DURATION_MILLIS = 800;
    static final long DELAY_DEALLOCATE_MEMORY_MILLIS = TimeUnit.SECONDS.toMillis(10);

    final Settings mSettings;
    private Locale mLocale;
    final InputLogic mInputLogic = new InputLogic(this /* LatinIME */);

    // TODO: Move these {@link View}s to {@link KeyboardSwitcher}.
    // Package-visible for the extracted soft-input window helpers (LatinImeSoftInputWindow).
    View mInputView;
    private final Rect mVisibleInputBounds = new Rect();

    private RichInputMethodManager mRichImm;
    final KeyboardSwitcher mKeyboardSwitcher;

    private AlertDialog mOptionsDialog;

    // Optional opt-in Tatar suggestions controller. Null until set up in onCreate().
    // Package-visible for the extracted service helpers (LatinImeAutocorrect & co.).
    SuggestionsController mSuggestionsController;

    // Owns the emoji panel's single-per-process snapshot. Null until set up in onCreate().
    private EmojiPanelController mEmojiPanelController;

    // The single learned-emoji sink, built in setUpSuggestionsController() and shared by both
    // event sources: a panel/search pick (onEmojiInserted) and the strip's emoji cell.
    private PersonalEmojiEventSink mPersonalEmojiLearningSink;

    /**
     * The emoji-search query while the search is open, and null otherwise. It holds every key press
     * made during the search; not one of them reaches {@link InputLogic} or the editor.
     *
     * <p>Package-visible for {@link LatinImeEmojiSearch}.</p>
     */
    EmojiSearchQuery mEmojiSearchQuery;

    /**
     * The auto-caps state reported to the keyboard while the emoji search is open: no
     * {@code TextUtils.CAP_MODE_*} bit at all. See
     * {@link LatinImeEmojiSearch#maybeRouteToEmojiSearch}.
     */
    private static final int NO_AUTO_CAPS = 0;

    // Decides the one-shot offer to turn Tatar suggestions on. Null until set up in onCreate().
    private SuggestionsOfferController mSuggestionsOffer;

    // Device-protected preferences, the only storage this IME reads: they are readable before the
    // user unlocks the device, which is what makes the keyboard usable in direct boot at all.
    private SharedPreferences mDevicePrefs;

    // Last value of PREF_TATAR_SUGGESTIONS this service has seen, so the listener below can tell an
    // actual transition from the many notifications that carry no change for us.
    private boolean mLastKnownTatarSuggestionsEnabled;

    /**
     * Held in a field, not registered as an anonymous lambda: SharedPreferencesImpl keeps
     * listeners in a WeakHashMap, so an unreferenced listener would be silently collected.
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener mSuggestionsSettingListener =
            this::onSuggestionsSettingMaybeChanged;

    public final UIHandler mHandler = new UIHandler(this);

    public static final class UIHandler extends LeakGuardHandlerWrapper<LatinIME> {
        // No message id may be 0: Handler.post(Runnable) enqueues a message with what == 0, and
        // removeMessages(int) matches on what alone, so removing id 0 would also drop every posted
        // Runnable (suggestion results, emoji panel, dialogs).
        private static final int MSG_UPDATE_SHIFT_STATE = 3;
        private static final int MSG_PENDING_IMS_CALLBACK = 1;
        private static final int MSG_REFRESH_SUGGESTION_BAND = 2;
        private static final int MSG_DEALLOCATE_MEMORY = 9;

        public UIHandler(final LatinIME ownerInstance) {
            super(ownerInstance);
        }

        @Override
        public void handleMessage(final Message msg) {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) {
                return;
            }
            final KeyboardSwitcher switcher = latinIme.mKeyboardSwitcher;
            switch (msg.what) {
            case MSG_UPDATE_SHIFT_STATE:
                switcher.requestUpdatingShiftState(latinIme.getCurrentAutoCapsState(),
                        latinIme.getCurrentRecapitalizeState());
                break;
            case MSG_REFRESH_SUGGESTION_BAND:
                latinIme.refreshSuggestionBandAfterCursorMove();
                break;
            case MSG_DEALLOCATE_MEMORY:
                latinIme.deallocateMemory();
                break;
            }
        }

        public void postUpdateShiftState() {
            removeMessages(MSG_UPDATE_SHIFT_STATE);
            sendMessage(obtainMessage(MSG_UPDATE_SHIFT_STATE));
        }

        /**
         * Asks the suggestion strip to refresh once the cursor has settled and the text cache is
         * current. Posted from the keyboard's cursor gestures and from the completion of the cache
         * reload after an external cursor move; both may fire for one move, so the message
         * coalesces. {@link SuggestionsController#onCursorMoveSettled} is a no-op unless needed.
         */
        public void postRefreshSuggestionBand() {
            removeMessages(MSG_REFRESH_SUGGESTION_BAND);
            sendMessage(obtainMessage(MSG_REFRESH_SUGGESTION_BAND));
        }

        public void postDeallocateMemory() {
            sendMessageDelayed(obtainMessage(MSG_DEALLOCATE_MEMORY),
                    DELAY_DEALLOCATE_MEMORY_MILLIS);
        }

        public void cancelDeallocateMemory() {
            removeMessages(MSG_DEALLOCATE_MEMORY);
        }

        public boolean hasPendingDeallocateMemory() {
            return hasMessages(MSG_DEALLOCATE_MEMORY);
        }

        // Working variables for the following methods.
        private boolean mIsOrientationChanging;
        private boolean mPendingSuccessiveImsCallback;
        private boolean mHasPendingStartInput;
        private boolean mHasPendingFinishInputView;
        private boolean mHasPendingFinishInput;
        private EditorInfo mAppliedEditorInfo;

        private void resetPendingImsCallback() {
            mHasPendingFinishInputView = false;
            mHasPendingFinishInput = false;
            mHasPendingStartInput = false;
        }

        private void executePendingImsCallback(final LatinIME latinIme, final EditorInfo editorInfo,
                boolean restarting) {
            if (mHasPendingFinishInputView) {
                latinIme.onFinishInputViewInternal(mHasPendingFinishInput);
            }
            if (mHasPendingFinishInput) {
                latinIme.onFinishInputInternal();
            }
            if (mHasPendingStartInput) {
                latinIme.onStartInputInternal(editorInfo, restarting);
            }
            resetPendingImsCallback();
        }

        public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the second onStartInput after orientation changed.
                mHasPendingStartInput = true;
            } else {
                if (mIsOrientationChanging && restarting) {
                    // This is the first onStartInput after orientation changed.
                    mIsOrientationChanging = false;
                    mPendingSuccessiveImsCallback = true;
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputInternal(editorInfo, restarting);
                }
            }
        }

        public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)
                    && KeyboardId.equivalentEditorInfoForKeyboard(editorInfo, mAppliedEditorInfo)) {
                // Typically this is the second onStartInputView after orientation changed.
                resetPendingImsCallback();
            } else {
                if (mPendingSuccessiveImsCallback) {
                    // This is the first onStartInputView after orientation changed.
                    mPendingSuccessiveImsCallback = false;
                    resetPendingImsCallback();
                    sendMessageDelayed(obtainMessage(MSG_PENDING_IMS_CALLBACK),
                            PENDING_IMS_CALLBACK_DURATION_MILLIS);
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputViewInternal(editorInfo, restarting);
                    mAppliedEditorInfo = editorInfo;
                }
                cancelDeallocateMemory();
            }
        }

        public void onFinishInputView(final boolean finishingInput) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInputView after orientation changed.
                mHasPendingFinishInputView = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    latinIme.onFinishInputViewInternal(finishingInput);
                    mAppliedEditorInfo = null;
                }
                if (!hasPendingDeallocateMemory()) {
                    postDeallocateMemory();
                }
            }
        }

        public void onFinishInput() {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInput after orientation changed.
                mHasPendingFinishInput = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, null, false);
                    latinIme.onFinishInputInternal();
                }
            }
        }
    }

    public LatinIME() {
        super();
        mSettings = Settings.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
    }

    @Override
    public void onCreate() {
        // Trace section for IME initialization. Trace sections wrap only coarse spans, never
        // per-frame code.
        Trace.beginSection("TT#onCreate");
        try {
            Settings.init(this);
            RichInputMethodManager.init(this);
            mRichImm = RichInputMethodManager.getInstance();
            mRichImm.setSubtypeChangeHandler(this);
            KeyboardSwitcher.init(this);
            AudioAndHapticFeedbackManager.init(this);
            super.onCreate();

            // TODO: Resolve mutual dependencies of {@link #loadSettings()} and
            // {@link #resetDictionaryFacilitatorIfNecessary()}.
            loadSettings();

            mDevicePrefs = PreferenceManagerCompat.getDeviceSharedPreferences(this);
            setUpSuggestionsController();
            setUpSuggestionsOffer();
            setUpEmojiPanelController();
            // Registered last: everything the handler touches exists by now, so it can never
            // observe a half-built service.
            mLastKnownTatarSuggestionsEnabled = Settings.readTatarSuggestionsEnabled(mDevicePrefs);
            mDevicePrefs.registerOnSharedPreferenceChangeListener(mSuggestionsSettingListener);

            // Register to receive ringer mode change.
            final IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            registerReceiver(mRingerModeChangeReceiver, filter);
        } finally {
            Trace.endSection();
        }
    }

    private void loadSettings() {
        mLocale = mRichImm.getCurrentSubtype().getLocaleObject();
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        final InputAttributes inputAttributes = new InputAttributes(editorInfo);
        mSettings.loadSettings(inputAttributes);
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues);
    }

    /**
     * Builds the opt-in Tatar suggestions controller and wires thin adapters over the input
     * view strip and the editor. The strip adapter resolves {@link #mInputView} lazily at call
     * time because the input view does not exist yet when this runs during onCreate().
     */
    private void setUpSuggestionsController() {
        final StripSurface stripSurface = new StripSurface() {
            @Override
            public void showSuggestions(final String first, final String second,
                    final String third) {
                final InputView inputView = getInputViewForSuggestions();
                if (inputView != null) {
                    inputView.showSuggestionStrip(first, second, third);
                }
            }

            @Override
            public void setSpokenCellLabels(final String first, final String second,
                    final String third) {
                final InputView inputView = getInputViewForSuggestions();
                if (inputView != null) {
                    inputView.setSuggestionStripSpokenLabels(first, second, third);
                }
            }

            @Override
            public void setEmphasizedCell(final int cell) {
                final InputView inputView = getInputViewForSuggestions();
                if (inputView != null) {
                    inputView.setSuggestionStripEmphasis(cell);
                }
            }

            @Override
            public void reserve() {
                final InputView inputView = getInputViewForSuggestions();
                if (inputView != null) {
                    inputView.reserveSuggestionStrip();
                }
            }

            @Override
            public void hideSuggestions() {
                final InputView inputView = getInputViewForSuggestions();
                if (inputView != null) {
                    inputView.clearAndHideSuggestionStrip();
                }
            }

            @Override
            public void setTapListener(final SuggestionTapListener listener) {
                final InputView inputView = getInputViewForSuggestions();
                if (inputView == null) {
                    return;
                }
                final SuggestionStripView strip = inputView.getOrCreateSuggestionStripView();
                if (strip == null) {
                    return;
                }
                strip.setOnSuggestionClickListener(
                        (cellId, suggestion) -> listener.onTap(suggestion));
                // The long press is wired next to the tap, on the same strip instance, so a
                // lazily created strip gets both or neither.
                strip.setOnSuggestionLongPressListener(
                        (cellId, suggestion) -> {
                            // An emoji cell holds no word, so there is nothing to forget and
                            // no "not a saved word" dialog is shown.
                            if (!containsAnyLetter(suggestion)) {
                                return;
                            }
                            showForgetPersonalWordDialog(suggestion);
                        });
            }
        };

        final EditorSurface editorSurface = new EditorSurface() {
            @Override
            public String cachedWordBeforeCursor() {
                return TatarWordUtils.INSTANCE.extractTrailingWord(
                        mInputLogic.mConnection.getCachedTextBeforeCursor());
            }

            @Override
            public boolean commitSuggestion(final String expectedPrefix,
                    final String suggestion) {
                final boolean committed =
                        mInputLogic.commitChosenSuggestion(expectedPrefix, suggestion);
                if (committed) {
                    // A tap does not go through an InputTransaction, so the auto-caps state is
                    // refreshed here: the committed word and its space can change it. The other
                    // editor-surface commits below do the same.
                    mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                            getCurrentRecapitalizeState());
                }
                return committed;
            }

            @Override
            public boolean hasKnownCursor() {
                return mInputLogic.mConnection.hasCursorPosition();
            }

            @Override
            public boolean hasLetterAfterCursor() {
                return TatarWordUtils.INSTANCE.startsWithWordCharacter(
                        mInputLogic.mConnection.getCachedTextAfterCursor());
            }

            @Override
            public boolean replaceTypedWord(final String expectedPrefix,
                    final String replacement) {
                final boolean replaced =
                        mInputLogic.commitTatarAutocorrection(expectedPrefix, replacement);
                if (replaced) {
                    // Refresh auto-caps, as for commitSuggestion. The separator that follows
                    // requests its own update too.
                    mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                            getCurrentRecapitalizeState());
                }
                return replaced;
            }

            @Override
            public boolean revertTypedWord(final String insertedForm, final String separator,
                    final String typedForm) {
                return mInputLogic.revertTatarAutocorrection(insertedForm, separator, typedForm);
            }

            @Override
            public boolean replaceGlideLiftedWord(final String committedWord,
                    final String alternative, final boolean prependedSpace) {
                final boolean replaced =
                        mInputLogic.replaceGlideLiftedWord(committedWord, alternative,
                                prependedSpace);
                if (replaced) {
                    // Refresh auto-caps, as for commitSuggestion.
                    mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                            getCurrentRecapitalizeState());
                }
                return replaced;
            }

            @Override
            public boolean deleteGlideLiftedWord(final String committedWord,
                    final boolean prependedSpace) {
                final boolean deleted =
                        mInputLogic.deleteGlideLiftedWord(committedWord, prependedSpace);
                if (deleted) {
                    mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                            getCurrentRecapitalizeState());
                }
                return deleted;
            }

            @Override
            public String cachedNextWordContext() {
                // Whether the cache starts at the start of the text comes from the connection's
                // flag, not from the cache length (see RichInputConnection#cacheReachedTextStart).
                return TatarWordUtils.INSTANCE.extractNextWordContext(
                        mInputLogic.mConnection.getCachedTextBeforeCursor(),
                        mInputLogic.mConnection.cacheReachedTextStart());
            }

            @Override
            public String cachedWordBeforeTrailingWord() {
                // Same cache and cache-start flag as cachedNextWordContext, read at the moment of
                // completion, so a learned word pair uses the text the editor holds now.
                return TatarWordUtils.INSTANCE.extractWordBeforeTrailingWord(
                        mInputLogic.mConnection.getCachedTextBeforeCursor(),
                        mInputLogic.mConnection.cacheReachedTextStart());
            }

            @Override
            public boolean isAtSentenceStart() {
                // Same cache and cache-start flag as cachedNextWordContext.
                return TatarWordUtils.INSTANCE.isSentenceStartContext(
                        mInputLogic.mConnection.getCachedTextBeforeCursor(),
                        mInputLogic.mConnection.cacheReachedTextStart());
            }

            @Override
            public boolean commitPredictedWord(final String expectedContextWord,
                    final String suggestion) {
                final boolean committed =
                        mInputLogic.commitPredictedWord(expectedContextWord, suggestion);
                if (committed) {
                    // Refresh auto-caps, as for commitSuggestion.
                    mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                            getCurrentRecapitalizeState());
                }
                return committed;
            }

            @Override
            public int cursorPosition() {
                return mInputLogic.mConnection.getExpectedSelectionStart();
            }

            @Override
            public boolean glideStartsSentence() {
                // Same cache and cache-start flag as isAtSentenceStart. The setting and the
                // field's sentence-caps flag are what the shift state's auto-caps reads, so the
                // word is capitalized exactly where a typed space would have shifted the keyboard.
                final EditorInfo editorInfo = getCurrentInputEditorInfo();
                return mSettings.getCurrent().mAutoCap && editorInfo != null
                        && (editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
                        && TatarWordUtils.INSTANCE.glideStartsSentence(
                                mInputLogic.mConnection.getCachedTextBeforeCursor(),
                                mInputLogic.mConnection.cacheReachedTextStart());
            }

            @Override
            public int commitGlideWord(final String expectedContextWord,
                    final String suggestion, final String expectedTrailingWord,
                    final int expectedCursor) {
                final int result = mInputLogic.commitGlideWord(expectedContextWord, suggestion,
                        expectedTrailingWord, expectedCursor);
                if (result != GLIDE_COMMIT_REFUSED) {
                    // Refresh auto-caps, as for commitSuggestion.
                    mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                            getCurrentRecapitalizeState());
                }
                return result;
            }
        };

        // Runs on the controller's background executor and uses the controller's own catalog. A
        // null catalog (storage not prepared yet) yields no engine: the strip stays GONE. Each
        // language (subtypeId) has its own catalog and personal dictionary.
        final Function2<String, ResultCallback, EngineHandle> engineFactory =
                (subtypeId, resultCallback) -> {
            final SuggestionsController controller = mSuggestionsController;
            if (controller == null) {
                return null;
            }
            final PublishedDictionaryCatalog catalog = controller.engineCatalog(subtypeId);
            if (catalog == null) {
                return null;
            }
            // Personal dictionary words. The setting is read on every lookup, so turning it off
            // takes effect on the next keystroke without restarting the engine. Personal sources
            // are bound to their language and never surface in another.
            final PersonalCandidateSource personalCandidates =
                    PersonalDictionaries.sourceFor(this, subtypeId,
                            () -> Settings.readPersonalDictionaryEnabled(mDevicePrefs));
            // Learned word pairs for next-word prediction, with the same live setting and
            // per-language binding as the words above.
            final PersonalBigramSource personalBigrams =
                    PersonalBigramDictionaries.sourceFor(this, subtypeId,
                            () -> Settings.readPersonalDictionaryEnabled(mDevicePrefs));
            // Tatar word-form rules, only for the engine whose dictionary is tagged Tatar in the
            // artifact registry. The Russian engine gets null.
            final DictionaryArtifactSpec dictionaryArtifact = DictionaryArtifactSpec.forSubtype(subtypeId);
            final boolean tatarEngine = dictionaryArtifact != null
                    && PersonalSubtypes.TATAR_RU.equals(dictionaryArtifact.getLanguageTag());
            final TatarSuffixRules suffixRules = tatarEngine ? TatarSuffixRules.INSTANCE : null;
            // Typo recovery policy: the Tatar engine uses FuzzyEditPolicy.TATAR; the Russian
            // engine gets null, which means FuzzyEditPolicy.DEFAULT.
            final FuzzyEditPolicy fuzzyEditPolicy = tatarEngine ? FuzzyEditPolicy.TATAR : null;
            // Every shipped-language engine gets a top-frequency fallback for next-word
            // prediction, built from its own dictionary. It only fills empty cells and never
            // displaces pair successors, word forms or the emoji cell.
            final FallbackWordsFactory fallbackWordsFactory = dictionaryArtifact != null
                    ? GlobalTopFrequencyFallbackFactory.INSTANCE : null;
            return MappedEngineHandle.start(catalog, resultCallback, personalCandidates, suffixRules,
                    fuzzyEditPolicy, fallbackWordsFactory, personalBigrams);
        };

        mSuggestionsController = new SuggestionsController(
                this, stripSurface, editorSurface, mHandler, engineFactory);
        // Learning sinks: completed words, word pairs and emoji are written only through these
        // sinks, only when mayLearnPersonalWords() holds at the moment of the event. Each sink
        // resolves the language per event, so a word typed on the Russian layout goes to the
        // Russian personal dictionary.
        mSuggestionsController.setCompletionSink(PersonalLearning.sinkFor(
                this, this::activeDictionarySubtype, this::mayLearnPersonalWords));
        // Word pairs, under the same predicate.
        mSuggestionsController.setPairCompletionSink(PersonalBigramLearning.sinkFor(
                this, this::activeDictionarySubtype, this::mayLearnPersonalWords));
        // Whether a pair's context word is in the bundled dictionary, asked by the pair store
        // on its worker before a pair is kept (the personal-dictionary check lives in
        // PersonalBigramDictionaries). Without a controller the answer is false.
        PersonalBigramDictionaries.setContextMembershipProbe((subtypeId, normalizedContext) -> {
            final SuggestionsController controller = mSuggestionsController;
            return controller != null && controller.engineContainsWord(subtypeId, normalizedContext);
        });
        // Read live from SettingsValues, which already requires suggestions to be on, so either
        // setting takes effect on the next separator without restarting the engine.
        mSuggestionsController.setAutocorrectGate(
                () -> mSettings.getCurrent().mTatarAutocorrectEnabled);
        // Emoji suggestions: read live the same way.
        mSuggestionsController.setEmojiSuggestGate(
                () -> mSettings.getCurrent().mEmojiSuggestEnabled);
        // A tap on the strip's emoji cell updates the recent emoji. Wired to the recents-only
        // onStripEmojiInserted, not onEmojiInserted: the controller already reports the tap to the
        // learned-emoji sink (see onStripEmojiInserted).
        mSuggestionsController.setEmojiInsertionSink(this::onStripEmojiInserted);
        // Learned emoji: one sink for the service's lifetime, under the same predicate. The
        // controller gets an adapter; onEmojiInserted() calls the sink directly for panel and
        // search picks.
        final PersonalEmojiEventSink personalEmojiLearning = PersonalEmojiLearning.sinkFor(
                this, this::activeDictionarySubtype, this::mayLearnPersonalWords);
        mPersonalEmojiLearningSink = personalEmojiLearning;
        mSuggestionsController.setPersonalEmojiSink(new PersonalEmojiSink() {
            @Override
            public void noteObservation(final String contextWord, final String emojiSequence) {
                personalEmojiLearning.noteObservation(contextWord, emojiSequence);
            }

            @Override
            public void noteUse(final String contextWord, final String emojiSequence) {
                personalEmojiLearning.noteUse(contextWord, emojiSequence);
            }

            @Override
            public void onInputFinished() {
                personalEmojiLearning.onInputFinished();
            }
        });
        // Learned emoji outrank the bundled table for the strip's emoji cell. The language is
        // resolved per query and the setting is read on every lookup. Pause learning does not
        // affect reads.
        mSuggestionsController.setPersonalEmojiSource(new PersonalEmojiSource() {
            @Override
            public String emojiFor(final String normalizedWord) {
                final String subtypeId = activeDictionarySubtype();
                if (subtypeId == null) {
                    return null;
                }
                return PersonalEmojiDictionaries.sourceFor(LatinIME.this, subtypeId,
                        () -> Settings.readPersonalDictionaryEnabled(mDevicePrefs))
                        .emojiFor(normalizedWord);
            }

            @Override
            public boolean isEmpty() {
                // A per-query resolver has no fixed emptiness: every lookup answers live.
                return false;
            }
        });
        // Glide typing: the setting is read live and does not depend on the suggestions setting.
        // The shift-state gate cases a glide word as typed letters would be: shift capitalizes
        // it, Caps Lock types it in capitals.
        mSuggestionsController.setGlideGate(
                () -> mSettings.getCurrent().mGlideTypingEnabled);
        mSuggestionsController.setGlideShiftStateGate(() -> {
            final Keyboard current = mKeyboardSwitcher.getKeyboard();
            if (current == null || current.mId == null) {
                return TatarWordUtils.PrefixCasing.LOWER;
            }
            final int elementId = current.mId.mElementId;
            if (elementId == KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED) {
                return TatarWordUtils.PrefixCasing.ALL_CAPS;
            }
            if (elementId == KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED
                    || elementId == KeyboardId.ELEMENT_ALPHABET_AUTOMATIC_SHIFTED) {
                return TatarWordUtils.PrefixCasing.INITIAL_CAPS;
            }
            return TatarWordUtils.PrefixCasing.LOWER;
        });
        // A refused glide gives the cursor gestures' tick: one pulse, none with vibration off.
        mSuggestionsController.setGlideRefusalFeedback(LatinImeKeyFeedback::hapticTickFeedback);
        mSuggestionsController.onCreate();
        // Erasing words on the settings screen must unbind what the strip shows: the screen and
        // the IME share the process, so the store notifies us directly, on its worker thread.
        PersonalDictionaries.setErasureListener(() -> mHandler.post(() -> {
            final SuggestionsController controller = mSuggestionsController;
            if (controller != null) {
                controller.onPersonalDictionaryErased();
            }
        }));
        // The same for erased word pairs: a deleted pair still shown could otherwise be tapped.
        PersonalBigramDictionaries.setErasureListener(() -> mHandler.post(() -> {
            final SuggestionsController controller = mSuggestionsController;
            if (controller != null) {
                controller.onPersonalDictionaryErased();
            }
        }));
        // The same for erased learned emoji.
        PersonalEmojiDictionaries.setErasureListener(() -> mHandler.post(() -> {
            final SuggestionsController controller = mSuggestionsController;
            if (controller != null) {
                controller.onPersonalDictionaryErased();
            }
        }));
        // The saved words could not be read and the store quarantined the file, so the user is
        // told why the list is empty. The notice comes from the store's worker thread.
        PersonalDictionaries.setQuarantineListener(
                () -> mHandler.post(this::showPersonalDictionaryUnreadableDialog));
        // The same notice for the word-pairs file, with its own message.
        PersonalBigramDictionaries.setQuarantineListener(
                () -> mHandler.post(this::showPersonalBigramsUnreadableDialog));
        // The same notice for the learned-emoji file, with its own message.
        PersonalEmojiDictionaries.setQuarantineListener(
                () -> mHandler.post(this::showPersonalEmojiUnreadableDialog));
    }

    /**
     * Wires the emoji panel controller. It builds nothing eagerly: the asset is not read and no
     * glyph is probed here — the single per-process snapshot preparation is started only on the
     * first emoji key press, on a background executor, never in onCreate() and never on the UI
     * thread.
     */
    private void setUpEmojiPanelController() {
        final EmojiSurface emojiSurface = new EmojiSurface() {
            @Override
            public void showPanel(final EmojiSetSnapshot snapshot) {
                // Empty the suggestion strip through the idempotent path when the panel appears;
                // its reserved height and visibility stay unchanged, and the panel inserts only
                // through onTextInput.
                if (mSuggestionsController != null) {
                    mSuggestionsController.onSelectionChanged();
                }
                mKeyboardSwitcher.showEmojiPanel(snapshot);
            }

            @Override
            public void bindSkinTones(final EmojiSkinTones tones) {
                mKeyboardSwitcher.bindEmojiSkinTones(tones);
            }

            @Override
            public void showEmojiSearch(final EmojiSearchIndex index) {
                mEmojiSearchQuery = new EmojiSearchQuery();
                mKeyboardSwitcher.showEmojiSearch(index);
                // The letters come back with shift unlatched: a query is not a sentence.
                mKeyboardSwitcher.requestUpdatingShiftState(NO_AUTO_CAPS,
                        getCurrentRecapitalizeState());
                LatinImeEmojiSearch.updateEmojiSearchView(LatinIME.this);
            }

            @Override
            public void onEmojiSearchUnavailable() {
                LatinIME.this.onEmojiSearchUnavailable();
            }

            @Override
            public void refreshAfterRecentsCleared(final EmojiSetSnapshot base) {
                // The recents were cleared while the panel is open: re-bind the base snapshot (which
                // carries no recents category) through the same show path, so the Recent tab
                // disappears without recreating the input view. When the panel is not shown, do
                // nothing — the next open will read the now-empty medium.
                if (mKeyboardSwitcher.isEmojiPanelShown()) {
                    mKeyboardSwitcher.showEmojiPanel(base);
                }
            }
        };
        // The gate for the recent emoji, re-read on every store attempt:
        //   mShouldShowSuggestions (covers password, visible password, e-mail, URI, filter,
        //   NO_SUGGESTIONS and autocomplete) AND UserManager.isUserUnlocked() AND
        //   NOT mNoPersonalizedLearning (IME_FLAG_NO_PERSONALIZED_LEARNING) AND no keyguard
        //   AND NOT pause learning (incognito). The keyguard also hides the Recent tab.
        final RecentEmojiGate recentGate = () -> {
            final SettingsValues settingsValues = mSettings.getCurrent();
            final boolean shouldShowSuggestions = settingsValues != null
                    && settingsValues.mInputAttributes.mShouldShowSuggestions;
            // Fail-closed when settings are missing: treat the field as no-personalized-learning.
            final boolean noPersonalizedLearning = settingsValues == null
                    || settingsValues.mInputAttributes.mNoPersonalizedLearning;
            final UserManager userManager =
                    (UserManager) getSystemService(Context.USER_SERVICE);
            final boolean userUnlocked = userManager != null && userManager.isUserUnlocked();
            return new RecentEmojiGateState(
                    shouldShowSuggestions, userUnlocked, noPersonalizedLearning,
                    isKeyguardLocked(), Settings.readIncognitoModeEnabled(mDevicePrefs));
        };
        mEmojiPanelController = new EmojiPanelController(this, emojiSurface, mHandler, recentGate);
    }

    /**
     * Builds the one-shot offer to turn Tatar suggestions on and wires it to the live IME.
     *
     * All the decision logic lives in {@link SuggestionsOfferController}; what stays here is the
     * environment it asks about, the durable one-shot flag and the two dialogs, because those are
     * exactly the parts that cannot exist without Android.
     */
    private void setUpSuggestionsOffer() {
        final OfferEnvironment environment = new OfferEnvironment() {
            @Override
            public boolean isSuggestionsSettingEnabled() {
                // Read straight from the preferences rather than from SettingsValues: this is also
                // called from the preference listener, where SettingsValues may not have been
                // rebuilt yet.
                return Settings.readTatarSuggestionsEnabled(mDevicePrefs);
            }

            @Override
            public boolean isTatarSubtypeActive() {
                // Any layout with a dictionary: the offer is about the suggestion strip, and the
                // strip now answers in Russian as well as in Tatar.
                return activeDictionarySubtype() != null;
            }

            @Override
            public boolean isInputViewShownWithWindowToken() {
                if (!isInputViewShown()) {
                    return false;
                }
                final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
                return mainKeyboardView != null && mainKeyboardView.getWindowToken() != null;
            }

            @Override
            public boolean editorAllowsSuggestions() {
                final SettingsValues settingsValues = mSettings.getCurrent();
                return settingsValues != null
                        && settingsValues.mInputAttributes.mShouldShowSuggestions
                        // No offer in an IME_FLAG_NO_PERSONALIZED_LEARNING field (incognito
                        // fields often have an ordinary text inputType). The environment is
                        // checked first, so the one-shot flag is not spent and the text is not read.
                        && !settingsValues.mInputAttributes.mNoPersonalizedLearning;
            }

            @Override
            public boolean isUserUnlocked() {
                final UserManager userManager =
                        (UserManager) getSystemService(Context.USER_SERVICE);
                // A service that cannot be reached counts as locked: not showing the offer is the
                // accepted failure direction, showing it twice is not.
                return userManager != null && userManager.isUserUnlocked();
            }

            @Override
            public boolean isAnotherDialogShowing() {
                return isShowingOptionDialog();
            }

            @Override
            public boolean isImeSuppressedByHardwareKeyboard() {
                return LatinIME.this.isImeSuppressedByHardwareKeyboard();
            }

            @Override
            public boolean isInDraggingFinger() {
                final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
                return mainKeyboardView != null && mainKeyboardView.isInDraggingFinger();
            }

            @Override
            public CharSequence cachedTextBeforeCursor() {
                // Local cache only, never IPC, and never logged.
                return mInputLogic.mConnection.getCachedTextBeforeCursor();
            }
        };

        final OfferFlagStore flagStore = new OfferFlagStore() {
            @Override
            public boolean isOfferSpent() {
                return Settings.readTatarSuggestionsOfferSpent(mDevicePrefs);
            }

            @Override
            public void spendOffer() {
                Settings.writeTatarSuggestionsOfferSpent(mDevicePrefs);
            }
        };

        final OfferPresenter presenter = new OfferPresenter() {
            @Override
            public void showEnableOffer() {
                showSuggestionsOfferDialog();
            }

            @Override
            public void showUnavailableMessage() {
                showSuggestionsUnavailableDialog();
            }
        };

        mSuggestionsOffer = new SuggestionsOfferController(environment, flagStore, presenter);
        if (mSuggestionsController != null) {
            mSuggestionsController.setDictionaryUnavailableListener(
                    mSuggestionsOffer::onDictionaryUnavailableAfterExplicitEnable);
        }
    }

    /**
     * Shows the one-shot offer to turn Tatar suggestions on. {@link SuggestionsOfferController}
     * has already spent the one-shot flag, so neither answer writes it; cancelling means "Not now".
     * The user's text and the suggestion strip are not touched.
     */
    private void showSuggestionsOfferDialog() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            return;
        }
        final IBinder windowToken = mainKeyboardView.getWindowToken();
        if (windowToken == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(
                DialogUtils.getPlatformDialogThemeContext(this))
                .setTitle(R.string.tatar_suggestions_offer_title)
                .setMessage(R.string.tatar_suggestions_offer_message)
                .setPositiveButton(R.string.tatar_suggestions_offer_enable,
                        (di, which) -> Settings.writeTatarSuggestionsEnabled(mDevicePrefs, true))
                .setNegativeButton(R.string.tatar_suggestions_offer_dismiss, null)
                .create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        attachDialogToInputWindow(dialog, windowToken);
        // Same field the subtype picker uses, so hideWindow() dismisses this dialog and clears the
        // reference through the one existing path instead of a second mechanism.
        mOptionsDialog = dialog;
        dialog.show();
    }

    /**
     * True when [text] holds at least one letter; tells an emoji cell from a word cell. Strip
     * words are BMP Cyrillic; a supplementary letter would read as an emoji, the safe side.
     */
    private static boolean containsAnyLetter(final String text) {
        for (int index = 0; index < text.length(); index++) {
            if (Character.isLetter(text.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * "Forget «X»?" for a word the personal dictionary holds.
     *
     * <p>The word is looked up by its normalized form, not by the shown string, which may have
     * been capitalized to match the typed prefix. Dialog rules: see
     * {@link #showSuggestionsUnavailableDialog()}.</p>
     */
    private void showForgetPersonalWordDialog(final String shownWord) {
        if (!Settings.readPersonalDictionaryEnabled(mDevicePrefs)) {
            // The personal dictionary is off by default, so tell the user how to turn it on
            // instead of ignoring the long press.
            showPersonalDictionaryOffDialog();
            return;
        }
        final String subtypeId = activeDictionarySubtype();
        if (subtypeId == null) {
            // The active layout has no personal dictionary.
            showNotASavedWordDialog();
            return;
        }
        final String savedForm = PersonalForget.savedFormOf(this, subtypeId, shownWord);
        if (savedForm == null) {
            // A bundled dictionary word. Saved words look the same in the strip, so answer
            // instead of ignoring the long press.
            showNotASavedWordDialog();
            return;
        }
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            return;
        }
        final IBinder windowToken = mainKeyboardView.getWindowToken();
        if (windowToken == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(
                DialogUtils.getPlatformDialogThemeContext(this))
                .setTitle(getString(R.string.personal_dictionary_forget_title, savedForm))
                .setPositiveButton(R.string.personal_dictionary_delete, (di, which) ->
                        PersonalForget.confirmForget(this, subtypeId, shownWord,
                                () -> mHandler.post(this::showPersonalForgetFailedDialog)))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        attachDialogToInputWindow(dialog, windowToken);
        mOptionsDialog = dialog;
        dialog.show();
    }

    /**
     * Shows the one-shot message saying that Tatar suggestions could not be turned on.
     *
     * <p>Rules for all notices below: a modal dialog attached to the input window, not a Toast
     * (toasts from a background process are at the platform's discretion; {@link #hideWindow()}
     * dismisses dialogs), with the platform's OK button. The body names no word, file or cause:
     * it can appear over any app.</p>
     */
    private void showSuggestionsUnavailableDialog() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            return;
        }
        final IBinder windowToken = mainKeyboardView.getWindowToken();
        if (windowToken == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(
                DialogUtils.getPlatformDialogThemeContext(this))
                .setMessage(R.string.tatar_suggestions_unavailable)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        attachDialogToInputWindow(dialog, windowToken);
        mOptionsDialog = dialog;
        dialog.show();
    }

    /**
     * Says that saved words are turned off, so a long press has nothing to forget, and names the
     * setting that turns them on. Dialog rules: see {@link #showSuggestionsUnavailableDialog()}.
     */
    private void showPersonalDictionaryOffDialog() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            return;
        }
        final IBinder windowToken = mainKeyboardView.getWindowToken();
        if (windowToken == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(
                DialogUtils.getPlatformDialogThemeContext(this))
                .setMessage(R.string.personal_dictionary_off_nothing_to_forget)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        attachDialogToInputWindow(dialog, windowToken);
        mOptionsDialog = dialog;
        dialog.show();
    }

    /**
     * Says that the long-pressed word is not one of the user's saved words. Dialog rules: see {@link #showSuggestionsUnavailableDialog()}.
     */
    private void showNotASavedWordDialog() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            return;
        }
        final IBinder windowToken = mainKeyboardView.getWindowToken();
        if (windowToken == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(
                DialogUtils.getPlatformDialogThemeContext(this))
                .setMessage(R.string.personal_dictionary_not_saved)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        attachDialogToInputWindow(dialog, windowToken);
        mOptionsDialog = dialog;
        dialog.show();
    }

    /**
     * Says that emoji are not available, so the emoji key or search cell has nothing to open.
     * Shown on every press, not once, since the key stays on the keyboard. Dialog rules: see {@link #showSuggestionsUnavailableDialog()}.
     */
    private void showEmojiUnavailableDialog() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            return;
        }
        final IBinder windowToken = mainKeyboardView.getWindowToken();
        if (windowToken == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(
                DialogUtils.getPlatformDialogThemeContext(this))
                .setMessage(R.string.emoji_unavailable)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        attachDialogToInputWindow(dialog, windowToken);
        mOptionsDialog = dialog;
        dialog.show();
    }

    /**
     * Says that a word the user asked to forget is still saved. Dialog rules: see {@link #showSuggestionsUnavailableDialog()}.
     * Posted from the store's worker, so the keyboard window may be gone by then.
     */
    private void showPersonalForgetFailedDialog() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            return;
        }
        final IBinder windowToken = mainKeyboardView.getWindowToken();
        if (windowToken == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(
                DialogUtils.getPlatformDialogThemeContext(this))
                .setMessage(R.string.personal_dictionary_delete_failed)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        attachDialogToInputWindow(dialog, windowToken);
        mOptionsDialog = dialog;
        dialog.show();
    }

    /**
     * Tells the user, once, that the saved words could not be read, so the empty list is explained.
     * Dialog rules: see {@link #showSuggestionsUnavailableDialog()}.
     *
     * <p>The notice is consumed only after both window checks pass: it can be raised while no
     * keyboard window is up (background open, settings screen), and consuming it earlier would
     * lose it. Consumed once, so it is not repeated on the next input view start.</p>
     */
    private void showPersonalDictionaryUnreadableDialog() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            return;
        }
        final IBinder windowToken = mainKeyboardView.getWindowToken();
        if (windowToken == null) {
            return;
        }
        if (!PersonalDictionaries.consumeQuarantineNotice()) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(
                DialogUtils.getPlatformDialogThemeContext(this))
                .setMessage(R.string.personal_dictionary_unreadable)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        attachDialogToInputWindow(dialog, windowToken);
        mOptionsDialog = dialog;
        dialog.show();
    }

    /**
     * The word-pairs version of {@link #showPersonalDictionaryUnreadableDialog()}, with the same
     * rules.
     */
    private void showPersonalBigramsUnreadableDialog() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            return;
        }
        final IBinder windowToken = mainKeyboardView.getWindowToken();
        if (windowToken == null) {
            return;
        }
        if (!PersonalBigramDictionaries.consumeQuarantineNotice()) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(
                DialogUtils.getPlatformDialogThemeContext(this))
                .setMessage(R.string.personal_bigrams_unreadable)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        attachDialogToInputWindow(dialog, windowToken);
        mOptionsDialog = dialog;
        dialog.show();
    }

    /**
     * The learned-emoji version of {@link #showPersonalDictionaryUnreadableDialog()}, with the same
     * rules.
     */
    private void showPersonalEmojiUnreadableDialog() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            return;
        }
        final IBinder windowToken = mainKeyboardView.getWindowToken();
        if (windowToken == null) {
            return;
        }
        if (!PersonalEmojiDictionaries.consumeQuarantineNotice()) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(
                DialogUtils.getPlatformDialogThemeContext(this))
                .setMessage(R.string.personal_emoji_unreadable)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        attachDialogToInputWindow(dialog, windowToken);
        mOptionsDialog = dialog;
        dialog.show();
    }

    /**
     * Attaches a dialog to the IME window exactly the way the subtype picker does. Without the
     * window token and the attached-dialog type the window manager refuses a dialog owned by an
     * input method; without FLAG_ALT_FOCUSABLE_IM the keyboard and the dialog fight over input.
     */
    private void attachDialogToInputWindow(final AlertDialog dialog, final IBinder windowToken) {
        final Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        final WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = windowToken;
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        // These dialogs float over other apps, so they drop touches while something obscures
        // them.
        DialogUtils.filterObscuredTouches(dialog);
    }

    /**
     * Device-protected preferences changed. Accepts a null key (how {@link Settings#onReceive}
     * notifies) and reacts only to a real change of the suggestions setting. Cheap: the engine
     * teardown waits for the next lifecycle boundary.
     */
    private void onSuggestionsSettingMaybeChanged(final SharedPreferences prefs, final String key) {
        if (key != null && !Settings.PREF_TATAR_SUGGESTIONS.equals(key)) {
            return;
        }
        final boolean enabled = Settings.readTatarSuggestionsEnabled(prefs);
        if (enabled == mLastKnownTatarSuggestionsEnabled) {
            return;
        }
        mLastKnownTatarSuggestionsEnabled = enabled;
        if (mSuggestionsController == null) {
            return;
        }
        if (enabled) {
            mSuggestionsController.onSuggestionsSettingEnabled(
                    isSuggestionsEligible(true), activeDictionarySubtype());
            updateKeyNeighbors();
        } else {
            mSuggestionsController.onSuggestionsSettingDisabled();
            mSuggestionsController.updateKeyNeighbors(null);
            if (mSuggestionsOffer != null) {
                mSuggestionsOffer.onSuggestionsSettingDisabled();
            }
        }
    }

    private InputView getInputViewForSuggestions() {
        return (mInputView instanceof InputView) ? (InputView) mInputView : null;
    }

    /**
     * The subtype whose dictionary should answer right now, or null when the active layout has
     * none. The only place that decides the suggestion language: a new language needs only a new
     * {@link DictionaryArtifactSpec}.
     */
    private String activeDictionarySubtype() {
        final String locale = mRichImm.getCurrentSubtype().getLocale();
        return DictionaryArtifactSpec.forSubtype(locale) == null ? null : locale;
    }

    /**
     * Whether [editorInfo] is a password-type field. Same check as
     * {@link InputAttributes#mIsPasswordField}, computed from the EditorInfo so it can run before
     * loadSettings(), while {@link SettingsValues} still describe the previous field.
     */
    private static boolean isPasswordField(final EditorInfo editorInfo) {
        final int inputType = editorInfo.inputType;
        return InputTypeUtils.isPasswordInputType(inputType)
                || InputTypeUtils.isVisiblePasswordInputType(inputType);
    }

    /**
     * The same check for the field currently bound. A null EditorInfo (no input bound) answers
     * false: the reload it gates would find no connection and return early anyway.
     */
    private boolean isCurrentFieldPasswordField() {
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        return editorInfo != null && isPasswordField(editorInfo);
    }

    /**
     * Whether the keyguard is shown (lock screen quick reply, PIN entry), also after the first
     * unlock. Nothing learned is shown or learned there. A missing KeyguardManager counts as locked.
     */
    private boolean isKeyguardLocked() {
        final KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
        return keyguardManager == null || keyguardManager.isKeyguardLocked();
    }

    /**
     * Computes whether opt-in word suggestions may run for the current field and subtype.
     */
    private boolean isSuggestionsEligible() {
        return isSuggestionsEligible(mSettings.getCurrent().mTatarSuggestionsEnabled);
    }

    /**
     * Same computation with the setting value supplied by the caller: in the preference listener
     * {@link SettingsValues} may still hold the previous value (listeners are not ordered).
     */
    private boolean isSuggestionsEligible(final boolean suggestionsEnabled) {
        final SettingsValues settingsValues = mSettings.getCurrent();
        return suggestionsEnabled
                && activeDictionarySubtype() != null
                && settingsValues.mInputAttributes.mShouldShowSuggestions
                // IME_FLAG_NO_PERSONALIZED_LEARNING: no strip and no engine queries in the field.
                && !settingsValues.mInputAttributes.mNoPersonalizedLearning
                && mInputLogic.mConnection.hasCursorPosition()
                // The strip would offer learned words on the lock screen.
                && !isKeyguardLocked();
    }

    /**
     * Whether glide typing may run: the field checks of {@link #isSuggestionsEligible}, but with
     * the glide setting instead of the suggestions setting (glide works with suggestions off).
     */
    private boolean isGlideEligible() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        return settingsValues.mGlideTypingEnabled
                && activeDictionarySubtype() != null
                && settingsValues.mInputAttributes.mShouldShowSuggestions
                && !settingsValues.mInputAttributes.mNoPersonalizedLearning
                && mInputLogic.mConnection.hasCursorPosition()
                && !isKeyguardLocked();
    }

    /**
     * The single learning predicate, used by every write path of the word, word-pair and emoji
     * sinks. The conjunction itself is {@link PersonalLearningGates#mayLearn}.
     *
     * <p>Unlocked: before the first unlock the store is empty and writes replace the whole file,
     * so a write would replace the real dictionary with one word. Postal address: blocks learning
     * only; suggestions stay on. TYPE_TEXT_VARIATION_PERSON_NAME is deliberately NOT excluded:
     * names are what the feature is for. Pause learning (incognito) blocks writes, never reads.</p>
     */
    private boolean mayLearnPersonalWords() {
        final UserManager userManager = getSystemService(UserManager.class);
        // A missing UserManager means locked, not open.
        final boolean unlocked = userManager != null && userManager.isUserUnlocked();
        return PersonalLearningGates.mayLearn(
                isSuggestionsEligible(),
                Settings.readPersonalDictionaryEnabled(mDevicePrefs),
                unlocked,
                mSettings.getCurrent().mInputAttributes.mIsPostalAddressField,
                Settings.readIncognitoModeEnabled(mDevicePrefs));
    }

    // The key-neighbor table for typo recovery, derived from the live keyboard and cached by
    // KeyboardId so the same layout is not rebuilt on every onStartInput.
    private KeyboardId mNeighborTableKeyboardId;
    private KeyNeighborTable mNeighborTable;

    // The glide typing key geometry of the current layout. It is kept while a rebuilt geometry
    // has the same content, so the engine keeps its decoder and word index across keyboard ids
    // that differ only in shift state, editor action or other flags that do not move keys.
    private GlideKeyGeometry mGlideGeometry;

    /**
     * Rebuilds (or reuses) the key-neighbor table from the current keyboard and hands it to the
     * suggestion controller. A null table (non-alphabet layout, ineligible field, no keyboard yet)
     * disables typo recovery only. Cheap on every lifecycle boundary thanks to the KeyboardId cache.
     */
    private void updateKeyNeighbors() {
        if (mSuggestionsController == null) {
            PointerTracker.setGlideAvailable(false);
            return;
        }
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        final String subtypeId = activeDictionarySubtype();
        KeyNeighborTable table = null;
        if (keyboard != null && keyboard.mId.isAlphabetKeyboard() && subtypeId != null
                && isSuggestionsEligible()) {
            // KeyboardId includes the subtype in equals/hashCode, so the cache is per layout and
            // per language.
            if (keyboard.mId.equals(mNeighborTableKeyboardId) && mNeighborTable != null) {
                table = mNeighborTable;
            } else {
                table = KeyNeighborTableBuilder.fromKeyboard(keyboard, subtypeId);
                mNeighborTableKeyboardId = keyboard.mId;
                mNeighborTable = table;
            }
        }
        mSuggestionsController.updateKeyNeighbors(table);

        // The glide geometry is updated at the same time but gated by isGlideEligible(), which
        // does not depend on the suggestions setting. A null geometry disables glide decoding.
        GlideKeyGeometry geometry = null;
        if (keyboard != null && keyboard.mId.isAlphabetKeyboard() && subtypeId != null
                && isGlideEligible()) {
            geometry = GlideKeyGeometryBuilder.fromKeyboard(keyboard);
            if (mGlideGeometry != null && mGlideGeometry.sameLayoutAs(geometry)) {
                geometry = mGlideGeometry;
            } else {
                mGlideGeometry = geometry;
            }
        }
        mSuggestionsController.updateGlideGeometry(geometry);
        // Without a geometry a slide over letters stays ordinary sliding key input.
        PointerTracker.setGlideAvailable(geometry != null);
    }

    @Override
    public void onDestroy() {
        // Dropped first: the listener holds this service, and the store outlives it (it is
        // process-wide). Leaving it registered would keep a destroyed IME reachable.
        PersonalDictionaries.setErasureListener(null);
        PersonalDictionaries.setQuarantineListener(null);
        PersonalBigramDictionaries.setErasureListener(null);
        PersonalBigramDictionaries.setQuarantineListener(null);
        PersonalBigramDictionaries.setContextMembershipProbe(null);
        PersonalEmojiDictionaries.setErasureListener(null);
        PersonalEmojiDictionaries.setQuarantineListener(null);
        if (mSuggestionsController != null) {
            mSuggestionsController.onDestroy();
        }
        if (mEmojiPanelController != null) {
            mEmojiPanelController.onDestroy();
        }
        if (mDevicePrefs != null) {
            mDevicePrefs.unregisterOnSharedPreferenceChangeListener(mSuggestionsSettingListener);
        }
        mSettings.onDestroy();
        unregisterReceiver(mRingerModeChangeReceiver);
        super.onDestroy();
    }

    private boolean isImeSuppressedByHardwareKeyboard() {
        final KeyboardSwitcher switcher = KeyboardSwitcher.getInstance();
        return !onEvaluateInputViewShown() && switcher.isImeSuppressedByHardwareKeyboard(
                mSettings.getCurrent(), switcher.getKeyboardSwitchState());
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        final boolean useOnScreen = super.onEvaluateInputViewShown();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return useOnScreen;
        } else {
            return useOnScreen || mSettings.getCurrent().mUseOnScreen;
        }
    }

    @Override
    public void onConfigurationChanged(final Configuration conf) {
        SettingsValues settingsValues = mSettings.getCurrent();
        if (settingsValues.mHasHardwareKeyboard != Settings.readHasHardwareKeyboard(conf)) {
            // If the state of having a hardware keyboard changed, then we want to reload the
            // settings to adjust for that.
            // TODO: we should probably do this unconditionally here, rather than only when we
            // have a change in hardware keyboard configuration.
            loadSettings();
        }

        mKeyboardSwitcher.onConfigurationChanged();

        super.onConfigurationChanged(conf);
    }

    @Override
    public View onCreateInputView() {
        // Trace section for input-view construction, including the first keyboard load.
        Trace.beginSection("TT#createInputView");
        try {
            // The input view is being (re)created (rotation, theme or height change): a deferred
            // show for the old view must not fire. The panel's "was open" state never survives
            // recreation.
            abandonEmojiSearch();
            if (mEmojiPanelController != null) {
                mEmojiPanelController.onInputViewRecreated();
            }
            return mKeyboardSwitcher.onCreateInputView();
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void setInputView(final View view) {
        if (mInputView instanceof InputView) {
            if (mInputView != view) {
                ((InputView) mInputView).release();
            } else {
                ((InputView) mInputView).setInsetsChangedListener(null);
            }
        }
        super.setInputView(view);
        mInputView = view;
        if (view instanceof InputView) {
            ((InputView) view).setInsetsChangedListener(
                    () -> LatinImeSoftInputWindow.onInputGeometryChanged(this));
        }
        LatinImeSoftInputWindow.updateSoftInputWindowLayoutParameters(this);
        view.requestApplyInsets();
    }

    @Override
    public void setCandidatesView(final View view) {
        // To ensure that CandidatesView will never be set.
    }

    @Override
    public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInput(editorInfo, restarting);
    }

    @Override
    public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInputView(editorInfo, restarting);
    }

    @Override
    public void onFinishInputView(final boolean finishingInput) {
        // A hide that keeps the session keeps the selection (see clearTextCaches).
        if (finishingInput) {
            mInputLogic.clearCaches();
        } else {
            mInputLogic.clearTextCaches();
        }
        mRichImm.resetSubtypeCycleOrder();
        mHandler.onFinishInputView(finishingInput);
    }

    @Override
    public void onFinishInput() {
        mHandler.onFinishInput();
    }

    @Override
    public void onCurrentSubtypeChanged(final boolean userInitiated) {
        mInputLogic.onSubtypeChanged();
        loadKeyboard();
        if (mSuggestionsController != null) {
            // The geometry goes first: onSubtypeChanged may refresh the strip at once for a
            // warm engine, and that lookup must already see the new layout's neighbor table.
            updateKeyNeighbors();
            mSuggestionsController.onSubtypeChanged(
                    isSuggestionsEligible(), activeDictionarySubtype(), isGlideEligible());
        }
        if (userInitiated) {
            announceCurrentLanguageForAccessibility();
        }
    }

    /**
     * Announces the newly selected input language after a user-initiated subtype switch
     * (globe key or the fork's subtype picker). The name is rendered in its own locale
     * ("Татарча" / "Русская" / "English") — the same source as the spacebar hint and the
     * space key's spoken description. Programmatic subtype changes (hint-locale switch at
     * field start, subtype removal in settings) arrive with userInitiated=false and stay
     * silent, so opening a field never spams an announcement.
     */
    private void announceCurrentLanguageForAccessibility() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            return;
        }
        mainKeyboardView.announceLanguageForAccessibility(
                LocaleResourceUtils.getLanguageDisplayNameInLocale(
                        mRichImm.getCurrentSubtype().getLocale()));
    }

    void onStartInputInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInput(editorInfo, restarting);

        // If the primary hint language does not match the current subtype language, then try
        // to switch to the primary hint language.
        // TODO: Support all the locales in EditorInfo#hintLocales.
        final Locale primaryHintLocale = EditorInfoCompatUtils.getPrimaryHintLocale(editorInfo);
        if (primaryHintLocale == null) {
            return;
        }
        mRichImm.setCurrentSubtype(primaryHintLocale);
    }

    void onStartInputViewInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInputView(editorInfo, restarting);

        // Switch to the null consumer to handle cases leading to early exit below, for which we
        // also wouldn't be consuming gesture data.
        final KeyboardSwitcher switcher = mKeyboardSwitcher;
        switcher.updateKeyboardTheme();
        final MainKeyboardView mainKeyboardView = switcher.getMainKeyboardView();
        // If we are starting input in a different text field from before, we'll have to reload
        // settings, so currentSettingsValues can't be final.
        SettingsValues currentSettingsValues = mSettings.getCurrent();

        if (editorInfo == null) {
            Log.e(TAG, "Null EditorInfo in onStartInputView()");
            return;
        }
        // Gated like the trace below: cursor positions of the user's text are not logged.
        if (TRACE) Log.i(TAG, "Starting input. Cursor position = "
                + editorInfo.initialSelStart + "," + editorInfo.initialSelEnd +
                " Restarting = " + restarting);

        // In landscape mode, this method gets called without the input view being created.
        if (mainKeyboardView == null) {
            return;
        }

        final boolean inputTypeChanged = !currentSettingsValues.isSameInputType(editorInfo);
        final boolean isDifferentTextField = !restarting || inputTypeChanged;

        // The EditorInfo might have a flag that affects fullscreen mode.
        // Note: This call should be done by InputMethodService?
        updateFullscreenMode();

        // ALERT: settings have not been reloaded and there is a chance they may be stale.
        // In the practice, if it is, we should have gotten onConfigurationChanged so it should
        // be fine, but this is horribly confusing and must be fixed AS SOON AS POSSIBLE.

        // In some cases the input connection has not been reset yet and we can't access it. In
        // this case we will need to call loadKeyboard() later, when it's accessible, so that we
        // can go into the correct mode, so we need to do some housekeeping here.
        if (!isImeSuppressedByHardwareKeyboard()) {
            // The app calling setText() has the effect of clearing the composing
            // span, so we should reset our state unconditionally, even if restarting is true.
            // We also tell the input logic about the combining rules for the current subtype, so
            // it can adjust its combiners if needed.
            mInputLogic.startInput();

            if (isPasswordField(editorInfo)) {
                // Privacy: a password field's text is never read into the cache. Clearing also
                // evicts the previous field's text (a field switch skips onFinishInputView).
                // Typed text still reaches the cache through local edits, so auto-caps works.
                mInputLogic.clearCaches();
            } else {
                // Some applications call onStartInputView without updating EditorInfo. In these
                // cases selection will be incorrect.
                mInputLogic.mConnection.reloadTextCache(editorInfo, restarting);
            }
        }

        if (isDifferentTextField ||
                !currentSettingsValues.hasSameOrientation(getResources().getConfiguration())) {
            loadSettings();
        }
        if (isDifferentTextField) {
            mainKeyboardView.closing();
            currentSettingsValues = mSettings.getCurrent();

            switcher.loadKeyboard(editorInfo, currentSettingsValues, getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
        } else {
            // TODO: Come up with a more comprehensive way to reset the keyboard layout when
            // a keyboard layout set doesn't get reloaded in this method.
            switcher.resetKeyboardStateToAlphabet(getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
        }

        if (mSuggestionsController != null) {
            mSuggestionsController.onStartInput(
                    isSuggestionsEligible(), activeDictionarySubtype(), isGlideEligible());
            updateKeyNeighbors();
        }
        if (mEmojiPanelController != null) {
            // A new editor session: a deferred emoji-panel show for the previous one must not
            // fire now.
            mEmojiPanelController.onEditorSessionChanged();
            abandonEmojiSearch();
        }
        if (mSuggestionsOffer != null) {
            // A deferred "could not turn suggestions on" message gets another chance here. This
            // does not trigger the offer itself.
            mSuggestionsOffer.onInputViewStarted();
        }
        if (PersonalDictionaries.hasPendingQuarantineNotice()) {
            // A quarantine notice raised while no window was up is shown now.
            mHandler.post(this::showPersonalDictionaryUnreadableDialog);
        }
        if (PersonalBigramDictionaries.hasPendingQuarantineNotice()) {
            // The word-pairs file has its own pending notice and message.
            mHandler.post(this::showPersonalBigramsUnreadableDialog);
        }
        if (PersonalEmojiDictionaries.hasPendingQuarantineNotice()) {
            // The learned-emoji file has its own pending notice and message.
            mHandler.post(this::showPersonalEmojiUnreadableDialog);
        }

        if (TRACE) Debug.startMethodTracing("/data/trace/latinime");
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (isInputViewShown())
            LatinImeSoftInputWindow.setNavigationBarColor(this);
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        // Close the emoji search and panel, or the next show would bring back a search whose
        // query is already dropped. Both calls are no-ops when the panel never opened.
        abandonEmojiSearch();
        mKeyboardSwitcher.hideEmojiPanel();
        // Privacy: clear the editor text cache. A hide without onFinishInputView (lock screen,
        // home gesture) would otherwise keep the field's text in memory until the next field.
        // The session outlives the window, so the selection is kept (see clearTextCaches).
        mInputLogic.clearTextCaches();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    void onFinishInputInternal() {
        super.onFinishInput();

        // Privacy, as in onWindowHidden: the detached editor's text must not stay in the cache
        // (onFinishInputView does not always accompany this call).
        mInputLogic.clearCaches();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    void onFinishInputViewInternal(final boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (mSuggestionsController != null) {
            mSuggestionsController.onFinishInput();
        }
        abandonEmojiSearch();
        if (mEmojiPanelController != null) {
            mEmojiPanelController.onFinishInputView();
        }
        // The panel frees its bound snapshot and layout caches when input finishes, too (it holds
        // no offscreen Bitmap); the controller keeps the single prepared snapshot for a re-bind.
        mKeyboardSwitcher.releaseEmojiPanelCaches();
    }

    protected void deallocateMemory() {
        mKeyboardSwitcher.deallocateMemory();
        // The glide word indexes and the emoji indexes are released on the same idle timer. Each
        // engine drops them on its own worker, so this UI-thread call only enqueues; everything
        // is rebuilt on next use.
        if (mSuggestionsController != null) {
            mSuggestionsController.releaseGlideIndexes();
            mSuggestionsController.releaseEmojiSuggest();
        }
        if (mEmojiPanelController != null) {
            mEmojiPanelController.releaseSearchIndex();
        }
        SharedEmojiSearchIndex.releaseProcessWide();
    }

    @Override
    public void onUpdateSelection(final int oldSelStart, final int oldSelEnd,
            final int newSelStart, final int newSelEnd,
            final int composingSpanStart, final int composingSpanEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                composingSpanStart, composingSpanEnd);
        final MainKeyboardView keyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (keyboardView != null && keyboardView.isInCursorMove()) {
            return;
        }

        // Gated like the trace in onStartInputViewInternal: cursor positions are not logged.
        if (TRACE) Log.i(TAG, "Update Selection. Cursor position = " + newSelStart + "," + newSelEnd);

        final boolean externalMove =
                newSelStart != mInputLogic.mConnection.getExpectedSelectionStart()
                        || newSelEnd != mInputLogic.mConnection.getExpectedSelectionEnd();

        mInputLogic.onUpdateSelection(newSelStart, newSelEnd);
        if (externalMove && mSuggestionsController != null) {
            mSuggestionsController.onSelectionChanged();
        }
        if (isInputViewShown()) {
            // Privacy: a password field's text is never re-read (see onStartInputViewInternal).
            // The shift update below uses the local cache, so it runs either way.
            if (!isCurrentFieldPasswordField()) {
                mInputLogic.reloadTextCache();
            }

            mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
        }
    }

    @Override
    public void hideWindow() {
        mKeyboardSwitcher.onHideWindow();

        if (TRACE) Debug.stopMethodTracing();
        if (isShowingOptionDialog()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        super.hideWindow();
    }

    @Override
    public void onComputeInsets(final InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        // This method may be called before {@link #setInputView(View)}.
        if (mInputView == null) {
            return;
        }
        final View visibleKeyboardView = mKeyboardSwitcher.getVisibleKeyboardView();
        if (visibleKeyboardView == null) {
            return;
        }
        final int inputHeight = mInputView.getHeight();
        if (isImeSuppressedByHardwareKeyboard() && !visibleKeyboardView.isShown()) {
            // If there is a hardware keyboard and a visible software keyboard view has been hidden,
            // no visual element will be shown on the screen.
            outInsets.contentTopInsets = inputHeight;
            outInsets.visibleTopInsets = inputHeight;
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
            outInsets.touchableRegion.setEmpty();
            return;
        }
        boolean hasTruthfulBounds = false;
        if (mInputView instanceof InputView) {
            hasTruthfulBounds = ((InputView) mInputView).getVisibleInputBounds(
                    visibleKeyboardView, mVisibleInputBounds);
        }
        if (!hasTruthfulBounds) {
            final int visibleTopY = inputHeight - visibleKeyboardView.getHeight();
            mVisibleInputBounds.set(0, visibleTopY,
                    visibleKeyboardView.getWidth(), inputHeight);
        }
        final int visibleTopY = Math.max(0,
                Math.min(inputHeight, mVisibleInputBounds.top));
        // Need to set expanded touchable region only if a keyboard view is being shown.
        if (visibleKeyboardView.isShown()) {
            final boolean showingMoreKeys = mKeyboardSwitcher.isShowingMoreKeysPanel();
            final int touchLeft = showingMoreKeys ? 0 : mVisibleInputBounds.left;
            final int touchTop = showingMoreKeys ? 0 : visibleTopY;
            final int touchRight = showingMoreKeys
                    ? Math.max(mInputView.getWidth(), visibleKeyboardView.getWidth())
                    : mVisibleInputBounds.right;
            final int touchBottom = Math.max(inputHeight, mVisibleInputBounds.bottom)
                    // Extend touchable region below the keyboard.
                    + EXTENDED_TOUCHABLE_REGION_HEIGHT;
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
            outInsets.touchableRegion.set(touchLeft, touchTop, touchRight, touchBottom);
        }
        outInsets.contentTopInsets = visibleTopY;
        outInsets.visibleTopInsets = visibleTopY;
    }

    @Override
    public boolean onShowInputRequested(final int flags, final boolean configChange) {
        if (isImeSuppressedByHardwareKeyboard()) {
            return true;
        }
        return super.onShowInputRequested(flags, configChange);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        if (isImeSuppressedByHardwareKeyboard()) {
            // If there is a hardware keyboard, disable full screen mode.
            return false;
        }
        // Reread resource value here, because this method is called by the framework as needed.
        final boolean isFullscreenModeAllowed = Settings.readUseFullscreenMode(getResources());
        if (super.onEvaluateFullscreenMode() && isFullscreenModeAllowed) {
            // TODO: Remove this hack. Actually we should not really assume NO_EXTRACT_UI
            // implies NO_FULLSCREEN. However, the framework mistakenly does.  i.e. NO_EXTRACT_UI
            // without NO_FULLSCREEN doesn't work as expected. Because of this we need this
            // hack for now.  Let's get rid of this once the framework gets fixed.
            final EditorInfo ei = getCurrentInputEditorInfo();
            return !(ei != null && ((ei.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0));
        }
        return false;
    }

    @Override
    public void updateFullscreenMode() {
        super.updateFullscreenMode();
        LatinImeSoftInputWindow.updateSoftInputWindowLayoutParameters(this);
    }

    int getCurrentAutoCapsState() {
        if (mEmojiSearchQuery != null) {
            // While the emoji search is open keys type into the query, so auto-caps derived from
            // the editor text would capitalize every letter. Every caller gets no CAP_MODE bit.
            return NO_AUTO_CAPS;
        }
        return mInputLogic.getCurrentAutoCapsState(mSettings.getCurrent());
    }

    int getCurrentRecapitalizeState() {
        return mInputLogic.getCurrentRecapitalizeState();
    }

    @Override
    public boolean onCustomRequest(final int requestCode) {
        switch (requestCode) {
            case Constants.CUSTOM_CODE_SHOW_INPUT_METHOD_PICKER:
                return showInputMethodPicker();
        }
        return false;
    }

    private boolean showInputMethodPicker() {
        if (isShowingOptionDialog()) {
            return false;
        }
        mOptionsDialog = mRichImm.showSubtypePicker(this,
                mKeyboardSwitcher.getMainKeyboardView().getWindowToken(), this);
        return mOptionsDialog != null;
    }

    public Locale getCurrentLayoutLocale() {
        return mLocale;
    }

    @Override
    public void onMoveCursorPointer(int steps) {
        if (mInputLogic.mConnection.hasCursorPosition()) {
            if (TextUtils.getLayoutDirectionFromLocale(getCurrentLayoutLocale()) == View.LAYOUT_DIRECTION_RTL)
                steps = -steps;

            steps = mInputLogic.mConnection.getUnicodeSteps(steps, true);
            if (steps == 0) {
                return;
            }
            final int end = mInputLogic.mConnection.getExpectedSelectionEnd() + steps;
            final int start = mInputLogic.mConnection.hasSelection() ? mInputLogic.mConnection.getExpectedSelectionStart() : end;
            mInputLogic.mConnection.setSelection(start, end);
            LatinImeKeyFeedback.hapticTickFeedback();
        } else {
            final boolean moved = steps != 0;
            for (; steps < 0; steps++)
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
            for (; steps > 0; steps--)
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
            LatinImeKeyFeedback.hapticTickFeedback();
            if (!moved) {
                return;
            }
        }
        onSuggestionsAffectingCursorMove();
    }

    @Override
    public void onMoveDeletePointer(int steps) {
        if (mInputLogic.mConnection.hasCursorPosition()) {
            steps = mInputLogic.mConnection.getUnicodeSteps(steps, false);
            if (steps == 0) {
                return;
            }
            final int end = mInputLogic.mConnection.getExpectedSelectionEnd();
            final int start = mInputLogic.mConnection.getExpectedSelectionStart() + steps;
            mInputLogic.mConnection.setSelection(start, end);
            LatinImeKeyFeedback.hapticTickFeedback();
        } else {
            final boolean deleted = steps != 0;
            for (; steps < 0; steps++)
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL);
            LatinImeKeyFeedback.hapticTickFeedback();
            if (!deleted) {
                return;
            }
        }
        onSuggestionsAffectingCursorMove();
    }

    @Override
    public void onUpWithDeletePointerActive() {
        if (mInputLogic.mConnection.hasSelection()) {
            mInputLogic.mConnection.deleteSelectedText();
            onSuggestionsAffectingCursorMove();
        }
    }

    @Override
    public void onUpWithSpacePointerActive() {
        // Privacy: a password field's text is never re-read (see onStartInputViewInternal).
        if (!isCurrentFieldPasswordField()) {
            mInputLogic.reloadTextCache();
        }
        // Only reached after an actual cursor slide, and the reload lands asynchronously, so the
        // strip must not keep offering candidates bound to the pre-slide cache.
        onSuggestionsAffectingCursorMove();
    }

    /**
     * Handles a cursor or selection move made by the keyboard itself (space slide, delete swipe):
     * drops the double-space and auto-space state and invalidates the suggestion strip.
     * {@link #onUpdateSelection} does not see these as external moves, so without this both would
     * keep state for the old position. Idempotent.
     */
    private void onSuggestionsAffectingCursorMove() {
        mInputLogic.onKeyboardCursorMove();
        if (mSuggestionsController != null) {
            mSuggestionsController.onSelectionChanged();
            // Then refresh the strip for the new position, or it stays blank until the next
            // keystroke.
            mHandler.postRefreshSuggestionBand();
        }
    }

    /**
     * Refreshes the suggestion strip after a cursor move has settled. Posted by
     * {@link UIHandler#postRefreshSuggestionBand}, never called directly. Skipped while the emoji
     * panel or search is shown: they keep the strip empty.
     */
    private void refreshSuggestionBandAfterCursorMove() {
        if (mSuggestionsController == null) {
            return;
        }
        if (mKeyboardSwitcher.isEmojiPanelShown() || mEmojiSearchQuery != null) {
            return;
        }
        mSuggestionsController.onCursorMoveSettled();
    }

    private boolean isShowingOptionDialog() {
        return mOptionsDialog != null && mOptionsDialog.isShowing();
    }

    public void switchToNextSubtype() {
        final IBinder token = getWindow().getWindow().getAttributes().token;
        mRichImm.switchToNextInputMethod(token, !shouldSwitchToOtherInputMethods(token));
    }

    // TODO: Instead of checking for alphabetic keyboard here, separate keycodes for
    // alphabetic shift and shift while in symbol layout and get rid of this method.
    private int getCodePointForKeyboard(final int codePoint) {
        if (Constants.CODE_SHIFT == codePoint) {
            final Keyboard currentKeyboard = mKeyboardSwitcher.getKeyboard();
            if (null != currentKeyboard && currentKeyboard.mId.isAlphabetKeyboard()) {
                return codePoint;
            }
            return Constants.CODE_SYMBOL_SHIFT;
        }
        return codePoint;
    }

    // Implementation of {@link KeyboardActionListener}.
    @Override
    public void onCodeInput(final int codePoint, final int x, final int y,
            final boolean isKeyRepeat) {
        final Event event = createSoftwareKeypressEvent(getCodePointForKeyboard(codePoint), isKeyRepeat);
        onEvent(event);
    }

    // This method is public for testability of LatinIME, but also in the future it should
    // completely replace #onCodeInput.
    public void onEvent(final Event event) {
        if (LatinImeEmojiSearch.maybeRouteToEmojiSearch(this, event)) {
            return;
        }
        if (LatinImeAutocorrect.maybeRevertTatarAutocorrection(this, event)) {
            return;
        }
        if (LatinImeGlide.maybeUndoGlideCommit(this, event)) {
            return;
        }
        LatinImeAutocorrect.maybeAutocorrectTatarWord(this, event);
        final InputTransaction completeInputTransaction =
                mInputLogic.onCodeInput(mSettings.getCurrent(), event);
        updateStateAfterInputTransaction(completeInputTransaction);
        maybeOfferTatarSuggestions(event);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    /**
     * Offers Tatar suggestions once, right after the user has finished their first real word.
     *
     * Once the offer has been made, a key press costs one field read (the in-memory copy of the
     * one-shot flag). {@link SuggestionsOfferController#isWordFinishingKeyPress} decides which key
     * presses finish a word: a word separator is required but not enough, since Enter and Tab are
     * separators too.
     */
    private void maybeOfferTatarSuggestions(final Event event) {
        if (mSuggestionsOffer == null || !mSuggestionsOffer.isOfferPending()) {
            return;
        }
        mSuggestionsOffer.onKeyPressCommitted(event.mCodePoint,
                mSettings.getCurrent().isWordSeparator(event.mCodePoint));
    }

    // A helper method to split the code point and the key code. Ultimately, they should not be
    // squashed into the same variable, and this method should be removed.
    // public for testing, as we don't want to copy the same logic into test code
    public static Event createSoftwareKeypressEvent(final int keyCodeOrCodePoint, final boolean isKeyRepeat) {
        final int keyCode;
        final int codePoint;
        if (keyCodeOrCodePoint <= 0) {
            keyCode = keyCodeOrCodePoint;
            codePoint = Event.NOT_A_CODE_POINT;
        } else {
            keyCode = Event.NOT_A_KEY_CODE;
            codePoint = keyCodeOrCodePoint;
        }
        return Event.createSoftwareKeypressEvent(codePoint, keyCode, isKeyRepeat);
    }

    // Called from PointerTracker through the KeyboardActionListener interface
    @Override
    public void onTextInput(final String rawText) {
        // TODO: have the keyboard pass the correct key code when we need it.
        final Event event = Event.createSoftwareTextEvent(rawText, Constants.CODE_OUTPUT_TEXT);
        final InputTransaction completeInputTransaction =
                mInputLogic.onTextInput(mSettings.getCurrent(), event);
        updateStateAfterInputTransaction(completeInputTransaction);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    // Called from PointerTracker through the KeyboardActionListener interface
    @Override
    public void onFinishSlidingInput() {
        // User finished sliding input.
        mKeyboardSwitcher.onFinishSlidingInput(getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
    }

    /**
     * A glide gesture completed on the letter keys. The controller decodes the path on the engine
     * worker and commits the result; the path is copied before this call returns. A no-op with
     * glide typing off; with suggestions off the word is still committed without the strip.
     */
    @Override
    public void onGlideInput(
            final rkr.simplekeyboard.inputmethod.latin.glide.GlidePath path) {
        final SuggestionsController controller = mSuggestionsController;
        if (controller != null) {
            controller.onGlideInput(path);
        }
    }

    /** Called by the paste key right before it inserts the clipboard; nothing pasted is learned. */
    public void onBeforeClipboardPaste() {
        if (mSuggestionsController != null) {
            mSuggestionsController.onClipboardPaste();
        }
    }

    /**
     * The emoji key was pressed. The emoji panel controller decides what happens: it starts the
     * one-shot snapshot preparation on the first press and shows the panel once (or immediately, if
     * the snapshot is already built). If preparation failed or produced no drawable entries, the
     * key is a no-op and ordinary typing is unaffected. The surface swap and insets are handled by
     * the keyboard switcher once the controller asks to show the panel.
     */
    public void showEmojiPanel() {
        if (mEmojiPanelController == null) {
            return;
        }
        // False means the panel cannot show in this process (the snapshot failed and is never
        // retried), so tell the user instead of ignoring the press.
        if (!mEmojiPanelController.onEmojiKeyPressed()) {
            showEmojiUnavailableDialog();
        }
    }

    /**
     * The search cell in the emoji panel's tab row was tapped and no search can be opened.
     *
     * Shows {@link #showEmojiUnavailableDialog()}: an unusable index is cached for the life of
     * the process, so the cell would otherwise do nothing.
     */
    public void onEmojiSearchUnavailable() {
        showEmojiUnavailableDialog();
    }

    /**
     * An emoji was inserted from the panel (a grid tap, including a tap inside the Recent tab) or
     * from the emoji search. The text was already committed; this records the use of the sequence
     * in the recent emoji (gated inside the controller) and in the learned emoji. The strip's
     * emoji cell uses {@link #onStripEmojiInserted} instead.
     */
    public void onEmojiInserted(final String sequence) {
        if (mEmojiPanelController != null) {
            mEmojiPanelController.onEmojiInserted(sequence);
        }
        // Learned emoji: the word before the just-committed emoji is the context
        // ("сәләм ☀️" → сәләм; no word, nothing learned). The sink's own predicate decides whether
        // anything may be learned (pause learning included).
        final PersonalEmojiEventSink sink = mPersonalEmojiLearningSink;
        if (sink != null) {
            final String word = EmojiTextUtils.extractContextBeforeEmoji(
                    mInputLogic.mConnection.getCachedTextBeforeCursor());
            if (word != null) {
                sink.noteObservation(word, sequence);
            }
        }
    }

    /**
     * The strip's emoji cell was tapped and committed: records the recent emoji only. The
     * controller reports the tap to the learned-emoji sink itself, with the strip's context word;
     * doing it here too would count one tap twice.
     */
    private void onStripEmojiInserted(final String sequence) {
        if (mEmojiPanelController != null) {
            mEmojiPanelController.onEmojiInserted(sequence);
        }
    }

    /**
     * The search cell in the emoji panel's tab row was tapped. The panel closes, the letter
     * keyboard comes back and every key press is routed into the emoji-search query instead of
     * into the editor until the search is left again.
     */
    public void onEmojiSearchRequested() {
        if (mEmojiPanelController != null) {
            mEmojiPanelController.onSearchRequested();
        }
    }

    /**
     * The emoji search was left — through the "✕" key, a backspace on an empty query, or any
     * lifecycle event that abandons it. The query is dropped and the emoji grid comes back.
     */
    public void onEmojiSearchClosed() {
        if (mEmojiSearchQuery == null) {
            return;
        }
        mEmojiSearchQuery = null;
        mKeyboardSwitcher.leaveEmojiSearch();
    }

    /**
     * Abandons an open emoji search without touching the surfaces; used by the lifecycle events
     * that tear the input view down or move to another editor, where the keyboard switcher already
     * resets its own state.
     */
    private void abandonEmojiSearch() {
        mEmojiSearchQuery = null;
    }

    /**
     * The emoji panel was hidden. The recent-emoji list is persisted at most once per hide, and only
     * when it changed; the write runs on the controller's background executor, never on the UI thread.
     */
    public void onEmojiPanelHidden() {
        if (mEmojiPanelController != null) {
            mEmojiPanelController.onPanelHidden();
        }
    }

    private void loadKeyboard() {
        // Since we are switching languages, the most urgent thing is to let the keyboard graphics
        // update. LoadKeyboard does that, but we need to wait for buffer flip for it to be on
        // the screen. Anything we do right now will delay this, so wait until the next frame
        // before we do the rest, like reopening dictionaries and updating suggestions. So we
        // post a message.
        loadSettings();
        if (mKeyboardSwitcher.getMainKeyboardView() != null) {
            // Reload keyboard because the current language has been changed.
            mKeyboardSwitcher.loadKeyboard(getCurrentInputEditorInfo(), mSettings.getCurrent(),
                    getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        }
    }

    /**
     * After an input transaction has been executed, some state must be updated. This includes
     * the shift state of the keyboard and suggestions. This method looks at the finished
     * inputTransaction to find out what is necessary and updates the state accordingly.
     * @param inputTransaction The transaction that has been executed.
     */
    private void updateStateAfterInputTransaction(final InputTransaction inputTransaction) {
        switch (inputTransaction.getRequiredShiftUpdate()) {
        case InputTransaction.SHIFT_UPDATE_LATER:
            mHandler.postUpdateShiftState();
            break;
        case InputTransaction.SHIFT_UPDATE_NOW:
            mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
            break;
        default: // SHIFT_NO_UPDATE
        }

        if (mSuggestionsController != null) {
            mSuggestionsController.onTextChanged();
        }
    }

    // Callback of the {@link KeyboardActionListener}. This is called when a key is depressed;
    // release matching call is {@link #onReleaseKey(int,boolean)} below.
    @Override
    public void onPressKey(final int primaryCode, final int repeatCount,
            final boolean isSinglePointer) {
        mKeyboardSwitcher.onPressKey(primaryCode, isSinglePointer, getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
        LatinImeKeyFeedback.hapticAndAudioFeedback(this, primaryCode, repeatCount);
    }

    // Callback of the {@link KeyboardActionListener}. This is called when a key is released;
    // press matching call is {@link #onPressKey(int,int,boolean)} above.
    @Override
    public void onReleaseKey(final int primaryCode, final boolean withSliding) {
        mKeyboardSwitcher.onReleaseKey(primaryCode, withSliding, getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
    }

    // receive ringer mode change.
    private final BroadcastReceiver mRingerModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged();
            }
        }
    };

    public void launchSettings() {
        requestHideSelf(0);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        final Intent intent = new Intent();
        intent.setClass(LatinIME.this, SettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    @Override
    protected void dump(final FileDescriptor fd, final PrintWriter fout, final String[] args) {
        super.dump(fd, fout, args);

        final Printer p = new PrintWriterPrinter(fout);
        p.println("LatinIME state :");
        p.println("  VersionCode = " + ApplicationUtils.getVersionCode(this));
        p.println("  VersionName = " + ApplicationUtils.getVersionName(this));
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        final int keyboardMode = keyboard != null ? keyboard.mId.mMode : -1;
        p.println("  Keyboard mode = " + keyboardMode);
    }

    public boolean shouldSwitchToOtherInputMethods(final IBinder token) {
        // TODO: Revisit here to reorganize the settings. Probably we can/should use different
        // strategy once the implementation of
        // {@link InputMethodManager#shouldOfferSwitchingToNextInputMethod} is defined well.
        if (!mSettings.getCurrent().mImeSwitchEnabled) {
            return false;
        }
        return mRichImm.shouldOfferSwitchingToOtherInputMethods(token);
    }

    public boolean shouldShowLanguageSwitchKey() {
        if (mSettings.getCurrent().isLanguageSwitchKeyDisabled()) {
            return false;
        }
        if (mRichImm.hasMultipleEnabledSubtypes()) {
            return true;
        }

        final IBinder token = getWindow().getWindow().getAttributes().token;
        if (token == null) {
            return false;
        }
        return shouldSwitchToOtherInputMethods(token);
    }
}
