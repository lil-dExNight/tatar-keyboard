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
import android.os.UserManager;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowInsetsController;
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
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.inputlogic.InputLogic;
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec;
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaries;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalForget;
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalLearning;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiPanelController;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSearchIndex;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSearchQuery;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSkinTones;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSetSnapshot;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSurface;
import rkr.simplekeyboard.inputmethod.latin.emoji.RecentEmojiGate;
import rkr.simplekeyboard.inputmethod.latin.emoji.RecentEmojiGateState;
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
import rkr.simplekeyboard.inputmethod.latin.suggestions.MappedEngineHandle;
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules;
import rkr.simplekeyboard.inputmethod.latin.suggestions.OfferEnvironment;
import rkr.simplekeyboard.inputmethod.latin.suggestions.OfferFlagStore;
import rkr.simplekeyboard.inputmethod.latin.suggestions.OfferPresenter;
import rkr.simplekeyboard.inputmethod.latin.suggestions.ResultCallback;
import rkr.simplekeyboard.inputmethod.latin.suggestions.StripSurface;
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionStripView;
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionTapListener;
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionsController;
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionsOfferController;
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ApplicationUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.DialogUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.LeakGuardHandlerWrapper;
import rkr.simplekeyboard.inputmethod.latin.utils.LocaleResourceUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewLayoutUtils;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIME extends InputMethodService implements KeyboardActionListener,
        RichInputMethodManager.SubtypeChangedListener {
    static final String TAG = LatinIME.class.getSimpleName();
    private static final boolean TRACE = false;

    private static final int EXTENDED_TOUCHABLE_REGION_HEIGHT = 100;
    private static final int PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT = 2;
    private static final int PENDING_IMS_CALLBACK_DURATION_MILLIS = 800;
    static final long DELAY_DEALLOCATE_MEMORY_MILLIS = TimeUnit.SECONDS.toMillis(10);

    final Settings mSettings;
    private Locale mLocale;
    final InputLogic mInputLogic = new InputLogic(this /* LatinIME */);

    // TODO: Move these {@link View}s to {@link KeyboardSwitcher}.
    private View mInputView;
    private final Rect mVisibleInputBounds = new Rect();

    private RichInputMethodManager mRichImm;
    final KeyboardSwitcher mKeyboardSwitcher;

    private AlertDialog mOptionsDialog;

    // Optional opt-in Tatar suggestions controller. Null until set up in onCreate().
    private SuggestionsController mSuggestionsController;

    // Owns the emoji panel's single-per-process snapshot. Null until set up in onCreate().
    private EmojiPanelController mEmojiPanelController;

    /**
     * The emoji-search query while the search is open, and null otherwise. It holds every key press
     * made during the search; not one of them reaches {@link InputLogic} or the editor.
     */
    private EmojiSearchQuery mEmojiSearchQuery;

    /**
     * The auto-caps state reported to the keyboard while the emoji search is open: no
     * {@code TextUtils.CAP_MODE_*} bit at all. See {@link #maybeRouteToEmojiSearch}.
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
     * Held in a field on purpose, and not registered as an anonymous lambda: SharedPreferencesImpl
     * keeps its listeners in a WeakHashMap, so a listener without a strong reference is collected
     * by the GC at an arbitrary later moment and this channel dies without a single symptom.
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener mSuggestionsSettingListener =
            this::onSuggestionsSettingMaybeChanged;

    public final UIHandler mHandler = new UIHandler(this);

    public static final class UIHandler extends LeakGuardHandlerWrapper<LatinIME> {
        // NO MESSAGE ID HERE MAY BE ZERO, and MSG_UPDATE_SHIFT_STATE is 3 rather than 0 for
        // exactly that reason.
        //
        // {@link Handler#post(Runnable)} enqueues an ordinary Message whose {@code what} is 0 and
        // whose {@code callback} is the runnable. {@link Handler#removeMessages(int)} matches on
        // {@code what} ALONE and ignores the callback, so the {@code removeMessages(0)} inside
        // postUpdateShiftState() used to delete every pending posted Runnable of this handler along
        // with its own message. Six places post such runnables — among them the suggestion engine's
        // result delivery (SuggestionsController's UiPoster), the emoji panel's poster and four
        // dialogs — and postUpdateShiftState() runs at the end of every editor text-cache reload,
        // i.e. after practically every keystroke. The result was a suggestion band that went blank
        // and stayed blank for as long as the reload kept winning the race: see docs/SUGGEST-DIES.md.
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
         * Asks the suggestion band to re-derive itself once the cursor has settled and the editor
         * text cache behind it is current again.
         *
         * Posted rather than called inline, from the two places that know the cache is (or is about
         * to be) correct: the keyboard's own cursor gestures, whose
         * {@link RichInputConnection#setSelection} keeps the cache in step as it moves, and the
         * completion of the asynchronous cache reload that an EXTERNAL cursor move triggers. Both
         * may fire for one and the same move, so the message coalesces: the band is re-derived at
         * most once per looper turn, and {@link SuggestionsController#onCursorMoveSettled} is itself
         * a no-op unless the band is genuinely left unbound.
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
        // Registered last: everything the handler touches exists by now, so it can never observe a
        // half-built service.
        mLastKnownTatarSuggestionsEnabled = Settings.readTatarSuggestionsEnabled(mDevicePrefs);
        mDevicePrefs.registerOnSharedPreferenceChangeListener(mSuggestionsSettingListener);

        // Register to receive ringer mode change.
        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mRingerModeChangeReceiver, filter);
    }

    private void loadSettings() {
        mLocale = mRichImm.getCurrentSubtype().getLocaleObject();
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        final InputAttributes inputAttributes = new InputAttributes(editorInfo, isFullscreenMode());
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
                // E4d: the long press is wired next to the tap, on the same strip instance, so a
                // strip created later (the band appears lazily) gets both or neither.
                strip.setOnSuggestionLongPressListener(
                        (cellId, suggestion) -> {
                            // An emoji-suggest cell holds no word, so there is nothing to forget:
                            // the long press is answered with silence rather than with a
                            // "not a saved word" dialog about a picture.
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
                    // A tap does not go through an InputTransaction, so nothing would recompute
                    // the auto-caps state the way updateStateAfterInputTransaction() does after a
                    // typed character. The committed word (and its trailing space) can change it —
                    // ". " right before the cursor means the next letter is a sentence start — so
                    // refresh it here with the very same call.
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
                    // Same reason as the accepted suggestion above: the replacement happens outside
                    // an InputTransaction, so the auto-caps state is refreshed with the very same
                    // call. The separator that follows requests its own update a moment later;
                    // doing it here too keeps the two insertion paths identical.
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
            public String cachedNextWordContext() {
                // docs/NEXTWORD-RACE.md + audit 2026-09-02 C6: whether the cache starts at the
                // start of the text is PROVENANCE, carried by the connection from the moment of
                // the full reload — not re-derived from the length, which local mutations (a
                // cursor swipe re-slicing the window, a long backspace run) make lie. A word
                // sitting at index 0 of a cache that reached the text start is whole.
                return TatarWordUtils.INSTANCE.extractNextWordContext(
                        mInputLogic.mConnection.getCachedTextBeforeCursor(),
                        mInputLogic.mConnection.cacheReachedTextStart());
            }

            @Override
            public boolean isAtSentenceStart() {
                // P4 (docs/TT-SUGGESTIONS.md): the same cache and the same cache-start provenance
                // as cachedNextWordContext above — the detector, not a re-derivation, decides.
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
                    // Same reason as the other two insertion paths above: commitPredictedWord runs
                    // outside an InputTransaction, so the auto-caps state is refreshed with the very
                    // same call used everywhere else.
                    mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                            getCurrentRecapitalizeState());
                }
                return committed;
            }
        };

        // Runs on the controller's background executor. The catalog is the one the controller
        // already owns: no second store and no throwaway executor are built per engine start. It is
        // null until a preparation request has actually created the storage, and a null catalog
        // simply yields no engine — the strip stays GONE and plain typing is untouched.
        // subtypeId names the language the controller is starting an engine for; every language
        // has its own catalog and its own personal store, so both are resolved from it and never
        // from a constant.
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
            // The personal side of the merge (E4b). The gate is read on every lookup rather than
            // baked in here, so turning the setting off stops personal candidates on the next
            // keystroke without restarting the engine, its lease or its mapping. Reading is bound to
            // the active subtype: personal words of one language can never surface in another.
            final PersonalCandidateSource personalCandidates =
                    PersonalDictionaries.sourceFor(this, subtypeId,
                            () -> Settings.readPersonalDictionaryEnabled(mDevicePrefs));
            // P3 (docs/TT-SUGGESTIONS.md): the Tatar word-form rules ride the same per-language
            // seam as the personal source — the artifact registry, not a call-site string,
            // decides, and the rule follows the family's language tag across repacks. The Russian
            // engine is started with null and never applies Tatar rules.
            final DictionaryArtifactSpec dictionaryArtifact = DictionaryArtifactSpec.forSubtype(subtypeId);
            final boolean tatarEngine = dictionaryArtifact != null
                    && PersonalSubtypes.TATAR_RU.equals(dictionaryArtifact.getLanguageTag());
            final TatarSuffixRules suffixRules = tatarEngine ? TatarSuffixRules.INSTANCE : null;
            // TT-TYPO-NEXT Phases B/C/C2 (docs/TT-TYPO-NEXT.md): the fuzzy pass is per-engine via
            // FuzzyEditPolicy. The Tatar engine ships TATAR — class #1 (long-press) plus class #4
            // (probe-first full single substitution, gated on an empty exact pass at >= 4 code
            // points) with the same-length bonus — the configuration the corrected C2 gates
            // measured and passed (2026-09-20). The Russian engine is started with null —
            // FuzzyEditPolicy.DEFAULT, bit-identical to the pre-Phase-B behavior.
            final FuzzyEditPolicy fuzzyEditPolicy = tatarEngine ? FuzzyEditPolicy.TATAR : null;
            // TT-NEXTWORD-FILL (docs/TT-NEXTWORD-FILL.md): every shipped-language engine (the
            // artifact registry decides) gets the global top-frequency fallback for its NEXT_WORD
            // slot — the factory builds the pool from the engine's OWN dictionary, so the Tatar
            // engine falls back to Tatar top words and the Russian one to Russian top words. A
            // fill-only change: the fallback never displaces bigram successors, word forms or the
            // emoji tail, and it never fires before the bigram source is attached.
            final FallbackWordsFactory fallbackWordsFactory = dictionaryArtifact != null
                    ? GlobalTopFrequencyFallbackFactory.INSTANCE : null;
            return MappedEngineHandle.start(catalog, resultCallback, personalCandidates, suffixRules,
                    fuzzyEditPolicy, fallbackWordsFactory);
        };

        mSuggestionsController = new SuggestionsController(
                this, stripSurface, editorSurface, mHandler, engineFactory);
        // E4c: clean completions become writes ONLY through this sink, and only when all five
        // factors hold at the moment of the event. isSuggestionsEligible() already carries
        // three of them — the field allows suggestions, it does not ask us not to personalize, and
        // an absent editorInfo is not eligible at all — so what is added here is the personal
        // dictionary setting, the unlock state and the postal-address exclusion.
        // The sink resolves the subtype at the moment of the event, not at construction: a word
        // completed on the Russian layout belongs in the Russian personal store, and the sink is
        // built once for the service's whole lifetime.
        mSuggestionsController.setCompletionSink(PersonalLearning.sinkFor(
                this, this::activeDictionarySubtype, this::mayLearnPersonalWords));
        // D3: read live off the already-rebuilt SettingsValues, which carries the subordination to
        // the suggestions switch, so flipping either setting takes effect on the next separator
        // without restarting the engine or touching its lease.
        mSuggestionsController.setAutocorrectGate(
                () -> mSettings.getCurrent().mTatarAutocorrectEnabled);
        // Emoji suggestions (mission 2 of docs/EMOJI-SUGGEST-PLAN.md): same live-read seam, same
        // subordination to the suggestions switch, carried by SettingsValues.
        mSuggestionsController.setEmojiSuggestGate(
                () -> mSettings.getCurrent().mEmojiSuggestEnabled);
        mSuggestionsController.onCreate();
        // Erasing words on the settings screen must unbind whatever the band is showing right now:
        // the screen and the IME live in the same process, so the store notifies us directly. The
        // callback arrives on the store's worker, hence the hop to the UI thread.
        PersonalDictionaries.setErasureListener(() -> mHandler.post(() -> {
            final SuggestionsController controller = mSuggestionsController;
            if (controller != null) {
                controller.onPersonalDictionaryErased();
            }
        }));
        // B2. The saved words could not be read, so the store set the file aside and the list the
        // user sees is empty through no act of theirs. Same hop for the same reason: the notice comes
        // from the store's worker.
        PersonalDictionaries.setQuarantineListener(
                () -> mHandler.post(this::showPersonalDictionaryUnreadableDialog));
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
                // its reserved height and visibility are unchanged and the D1 auto-space contract
                // is untouched (the panel inserts only through onTextInput and the strip is inert
                // while the panel is shown).
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
                updateEmojiSearchView();
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
        // The three-factor gate for updating the recent-emoji list, re-read on every store attempt:
        //   mShouldShowSuggestions (already covers password, visible password, e-mail, URI, filter,
        //   NO_SUGGESTIONS and autocomplete) AND UserManager.isUserUnlocked() AND
        //   NOT mNoPersonalizedLearning (the field reused from E1, IME_FLAG_NO_PERSONALIZED_LEARNING).
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
                    shouldShowSuggestions, userUnlocked, noPersonalizedLearning);
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
                        // An editor that set IME_FLAG_NO_PERSONALIZED_LEARNING gets no offer at
                        // all. Incognito fields usually carry an ordinary text inputType, so
                        // mShouldShowSuggestions is true for them and this is the only condition
                        // that stops the dialog. Because the controller checks the environment
                        // before it reads anything, the one-shot flag is not spent and the text of
                        // such a field is never looked at either.
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
     * Shows the one-shot offer to turn Tatar suggestions on.
     *
     * The durable flag has already been spent by {@link SuggestionsOfferController} before this
     * method was called, so neither answer writes it: cancelling by touching outside the dialog or
     * with the back button is allowed and means exactly what "Not now" means. Nothing here touches
     * the user's text, and the suggestion strip is not involved at all — with the setting off it
     * stays GONE before, during and after the dialog.
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
     * True when [text] holds at least one letter. Used to tell an emoji-suggest cell apart from a
     * word cell: every word the band can show is BMP Cyrillic, and a supplementary letter would
     * simply read as two non-letters — "emoji", the safe direction for a forget-word gesture.
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
     * "Forget «X»?" for a word the personal dictionary holds (E4d).
     *
     * <p>Long-pressing a cell that shows an ordinary dictionary word does nothing at all: the lookup
     * below simply finds no personal entry. The word is looked up by its NORMALIZED form against the
     * published snapshot, never by the string on screen — the shown string has already been through
     * applyCasing for an INITIAL_CAPS or ALL_CAPS prefix, and a search by it would silently miss the
     * saved spelling exactly when the user typed in capitals.</p>
     */
    private void showForgetPersonalWordDialog(final String shownWord) {
        if (!Settings.readPersonalDictionaryEnabled(mDevicePrefs)) {
            // The DEFAULT state of the keyboard: the personal dictionary ships off, so without this
            // the long press is silent for every user who never turned it on — which is everyone,
            // until they do. Answered with the one thing they can act on.
            showPersonalDictionaryOffDialog();
            return;
        }
        final String subtypeId = activeDictionarySubtype();
        if (subtypeId == null) {
            // The active layout keeps no personal dictionary, so nothing here was ever the user's.
            showNotASavedWordDialog();
            return;
        }
        final String savedForm = PersonalForget.savedFormOf(this, subtypeId, shownWord);
        if (savedForm == null) {
            // An ordinary dictionary word. Nothing can be forgotten here, but the gesture still gets
            // an answer: the user cannot tell their own saved words apart from the dictionary's by
            // looking at the band, so a silent long press reads as "long press is broken" rather
            // than "this word is not yours".
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
     * A modal dialog rather than a Toast: it does not depend on the platform's limits on toasts from
     * a background process, and it is dismissed by the same {@link #hideWindow()} as every other
     * dialog here. The single acknowledging button is labelled by the platform, because this phase's
     * string set is fixed and a plain acknowledgement needs no wording of its own. The body names no
     * file, no failure code, no size and no cause: none of that is anything the user could act on,
     * and all of it would be alarming inside someone else's app.
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
     * Says that saved words are switched off, so a long press has nothing it could forget.
     *
     * The personal dictionary ships OFF, so this is the answer almost every long press gets until
     * the person turns it on — and the switch is the one action they can take, so the message names
     * it. Same shape and the same window attachment as the other notices here.
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
     * Says that the long-pressed word is not one of the user's own saved words.
     *
     * Same shape and same reasoning as {@link #showSuggestionsUnavailableDialog()}: a dialog rather
     * than a Toast, because a toast from a background process is at the platform's discretion and
     * this is the only answer the gesture will ever get. The body names no word — the message can
     * be shown over any app, and what the person typed must not appear on top of someone else's
     * screen.
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
     * Says that emoji are not available in this process, so the key that was just pressed — or the
     * search pill that was just tapped — has nothing to open.
     *
     * Answered on EVERY press rather than once: the key stays on the keyboard and the person will
     * press it again, and a one-shot notice would put the silence straight back. Same shape and the
     * same window attachment as the other notices here; the body names no file and no cause.
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
     * Says that a word the user asked to forget is still saved.
     *
     * Same shape and same reasoning as {@link #showSuggestionsUnavailableDialog()}: a dialog rather
     * than a Toast, because a toast from a background process is at the platform's discretion and
     * this is the only notice the user will ever get — the personal-dictionary subsystem may not
     * log, and nothing else waits for the result of the write. The body names no word, no file and
     * no cause; the word is the one thing that must not appear here, since the message can be shown
     * over any app.
     *
     * <p>Arrives from the store's worker through the handler, so by the time it runs the keyboard
     * window may be gone; both null checks below are the ordinary answer to that.</p>
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
     * Tells the user, once, that the saved words could not be read — so the empty list is explained
     * rather than merely appearing. Same shape and the same window attachment as
     * {@link #showPersonalForgetFailedDialog()}, and a dialog for the same reason.
     *
     * <p>The notice is CONSUMED here, after both window checks pass and immediately before the
     * dialog is shown, not when it was raised: the store opens from a background executor and from
     * the settings screen, so it can be raised with no keyboard window up. Clearing it any earlier
     * would spend the one message on nobody, which is the silence this whole register is about. It is
     * consumed exactly once, so a second input view start does not repeat it.</p>
     *
     * <p>The body names no word, no file and no cause. It may be shown over any app.</p>
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
        // Audit 2026-09-02, C5: these dialogs float over OTHER apps' windows, so they must not
        // accept a touch delivered while something obscures them.
        DialogUtils.filterObscuredTouches(dialog);
    }

    /**
     * Device-protected preferences changed.
     *
     * Tolerates a null key, which is how {@link Settings#onReceive} notifies its own listener, and
     * reacts only to an actual transition of the one value this service watches — writing the
     * one-shot offer flag, or any unrelated setting, must not be mistaken for the user flipping the
     * suggestions switch. Nothing expensive happens here: the controller closes or reopens
     * eligibility immediately and leaves the blocking engine teardown to the next lifecycle
     * boundary.
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
     * The subtype whose dictionary should answer right now, or null when the active layout ships
     * none.
     *
     * This is the ONE place the app decides which language it is suggesting in. It reads the live
     * subtype and asks {@link DictionaryArtifactSpec#forSubtype} whether a dictionary exists for it,
     * so adding a third language is adding a spec — never another branch here.
     */
    private String activeDictionarySubtype() {
        final String locale = mRichImm.getCurrentSubtype().getLocale();
        return DictionaryArtifactSpec.forSubtype(locale) == null ? null : locale;
    }

    /**
     * Computes whether opt-in word suggestions may run for the current field and subtype.
     */
    private boolean isSuggestionsEligible() {
        return isSuggestionsEligible(mSettings.getCurrent().mTatarSuggestionsEnabled);
    }

    /**
     * Same computation with the setting value supplied by the caller.
     *
     * The preference listener needs this overload: it and {@link Settings} are two listeners on the
     * same SharedPreferences instance, the platform does not order them, so {@link SettingsValues}
     * may still carry the previous value at the moment the change reaches this service.
     */
    private boolean isSuggestionsEligible(final boolean suggestionsEnabled) {
        final SettingsValues settingsValues = mSettings.getCurrent();
        return suggestionsEnabled
                && activeDictionarySubtype() != null
                && settingsValues.mInputAttributes.mShouldShowSuggestions
                // IME_FLAG_NO_PERSONALIZED_LEARNING closes eligibility outright: the strip reserves
                // no band and not a single prefix reaches the engine in such a field, even for a
                // user who has turned Tatar suggestions on everywhere else.
                && !settingsValues.mInputAttributes.mNoPersonalizedLearning
                && mInputLogic.mConnection.hasCursorPosition();
    }

    /**
     * The E4c learning predicate — one predicate, five factors, shared by every write path
     * (noteCompletion, the eventual learn, the accepted-suggestion counter and the pending flush).
     *
     * <p>Two of them deserve a note. {@code isUserUnlocked()} is not about an extra record: before
     * the first unlock the snapshot is empty by construction, and writing is whole-file, so the
     * first write to fire would build a file out of "empty plus one word" and atomically replace the
     * user's real dictionary with it. The window is real — a directBootAware app has input fields
     * before the unlock. The postal-address exclusion is local to this predicate on purpose: such a
     * field is NOT part of shouldSuppressSuggestions, so suggestions there behave exactly as before,
     * and only learning is blocked. TYPE_TEXT_VARIATION_PERSON_NAME is deliberately NOT excluded —
     * names are precisely what this feature is for.</p>
     */
    private boolean mayLearnPersonalWords() {
        if (!isSuggestionsEligible()) {
            return false;
        }
        if (!Settings.readPersonalDictionaryEnabled(mDevicePrefs)) {
            return false;
        }
        final UserManager userManager = getSystemService(UserManager.class);
        if (userManager == null || !userManager.isUserUnlocked()) {
            return false;
        }
        return !mSettings.getCurrent().mInputAttributes.mIsPostalAddressField;
    }

    // The key-neighbor table for the fuzzy suggestion pass is derived from the live keyboard and
    // cached by KeyboardId: rebuilding it on every onStartInput would repeat the same work for the
    // same layout. This is the first time layout data crosses into the dictionary engine.
    private KeyboardId mNeighborTableKeyboardId;
    private KeyNeighborTable mNeighborTable;

    /**
     * Rebuilds (or reuses) the key-neighbor table from the current keyboard and hands it to the
     * suggestion controller. A null table — non-alphabet layout, ineligible field, or no built
     * keyboard yet — disables the fuzzy pass without touching the exact suggestions. Cheap enough
     * to call on every lifecycle boundary because it is memoized by KeyboardId.
     */
    private void updateKeyNeighbors() {
        if (mSuggestionsController == null) {
            return;
        }
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        final String subtypeId = activeDictionarySubtype();
        KeyNeighborTable table = null;
        if (keyboard != null && keyboard.mId.isAlphabetKeyboard() && subtypeId != null
                && isSuggestionsEligible()) {
            // KeyboardId carries the subtype in its equals/hashCode, so this memo is per layout AND
            // per language: switching layouts rebuilds the table instead of handing the engine the
            // neighbours of the layout the user just left.
            if (keyboard.mId.equals(mNeighborTableKeyboardId) && mNeighborTable != null) {
                table = mNeighborTable;
            } else {
                table = KeyNeighborTableBuilder.fromKeyboard(keyboard, subtypeId);
                mNeighborTableKeyboardId = keyboard.mId;
                mNeighborTable = table;
            }
        }
        mSuggestionsController.updateKeyNeighbors(table);
    }

    @Override
    public void onDestroy() {
        // Dropped first: the listener holds this service, and the store outlives it (it is
        // process-wide). Leaving it registered would keep a destroyed IME reachable.
        PersonalDictionaries.setErasureListener(null);
        PersonalDictionaries.setQuarantineListener(null);
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
        // The input view is being (re)created (rotation, theme or height change): a deferred show
        // for the old view must not fire. The panel's "was open" state never survives recreation.
        abandonEmojiSearch();
        if (mEmojiPanelController != null) {
            mEmojiPanelController.onInputViewRecreated();
        }
        return mKeyboardSwitcher.onCreateInputView();
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
            ((InputView) view).setInsetsChangedListener(this::onInputGeometryChanged);
        }
        updateSoftInputWindowLayoutParameters();
        view.requestApplyInsets();
    }

    private void onInputGeometryChanged() {
        if (mInputView == null) {
            return;
        }
        mInputView.requestLayout();
        mInputView.requestApplyInsets();
        final Window window = getWindow().getWindow();
        if (window != null) {
            window.getDecorView().requestLayout();
            window.getDecorView().requestApplyInsets();
        }
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
        mInputLogic.clearCaches();
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
            mSuggestionsController.onSubtypeChanged(
                    isSuggestionsEligible(), activeDictionarySubtype());
            updateKeyNeighbors();
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
        Log.i(TAG, "Starting input. Cursor position = "
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

            // Some applications call onStartInputView without updating EditorInfo. In these cases
            // selection will be incorrect.
            mInputLogic.mConnection.reloadTextCache(editorInfo, restarting);
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
                    isSuggestionsEligible(), activeDictionarySubtype());
            updateKeyNeighbors();
        }
        if (mEmojiPanelController != null) {
            // A new editor session: a deferred emoji-panel show armed for the previous one must not
            // fire now.
            mEmojiPanelController.onEditorSessionChanged();
            abandonEmojiSearch();
        }
        if (mSuggestionsOffer != null) {
            // The boundary at which a deferred "could not turn suggestions on" message gets another
            // chance. It is not a trigger for the offer itself: showing the keyboard proves nothing
            // about wanting to type Tatar.
            mSuggestionsOffer.onInputViewStarted();
        }
        if (PersonalDictionaries.hasPendingQuarantineNotice()) {
            // The same boundary, for the same reason: a notice raised while no window was up would
            // otherwise be dropped, and the user would be left with an empty list and no explanation.
            mHandler.post(this::showPersonalDictionaryUnreadableDialog);
        }

        if (TRACE) Debug.startMethodTracing("/data/trace/latinime");
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (isInputViewShown())
            setNavigationBarColor();
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        // A hidden window must not resurrect a dead emoji search on the next show: without this
        // reset the switcher still flags the search as open while the query is already dropped,
        // leaving a visible but dead search band (M4c). Both calls are no-ops when the panel
        // never opened.
        abandonEmojiSearch();
        mKeyboardSwitcher.hideEmojiPanel();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    void onFinishInputInternal() {
        super.onFinishInput();

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

        Log.i(TAG, "Update Selection. Cursor position = " + newSelStart + "," + newSelEnd);

        final boolean externalMove =
                newSelStart != mInputLogic.mConnection.getExpectedSelectionStart()
                        || newSelEnd != mInputLogic.mConnection.getExpectedSelectionEnd();

        mInputLogic.onUpdateSelection(newSelStart, newSelEnd);
        if (externalMove && mSuggestionsController != null) {
            mSuggestionsController.onSelectionChanged();
        }
        if (isInputViewShown()) {
            mInputLogic.reloadTextCache();

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
        updateSoftInputWindowLayoutParameters();
    }

    private void updateSoftInputWindowLayoutParameters() {
        // Override layout parameters to expand {@link SoftInputWindow} to the entire screen.
        // See {@link InputMethodService#setinputView(View)} and
        // {@link SoftInputWindow#updateWidthHeight(WindowManager.LayoutParams)}.
        final Window window = getWindow().getWindow();
        ViewLayoutUtils.updateLayoutHeightOf(window, LayoutParams.MATCH_PARENT);
        // This method may be called before {@link #setInputView(View)}.
        if (mInputView != null) {
            // In non-fullscreen mode, {@link InputView} and its parent inputArea should expand to
            // the entire screen and be placed at the bottom of {@link SoftInputWindow}.
            // In fullscreen mode, these shouldn't expand to the entire screen and should be
            // coexistent with {@link #mExtractedArea} above.
            // See {@link InputMethodService#setInputView(View) and
            // com.android.internal.R.layout.input_method.xml.
            final int layoutHeight = isFullscreenMode()
                    ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT;
            final View inputArea = window.findViewById(android.R.id.inputArea);
            ViewLayoutUtils.updateLayoutHeightOf(inputArea, layoutHeight);
            ViewLayoutUtils.updateLayoutGravityOf(inputArea, Gravity.BOTTOM);
            ViewLayoutUtils.updateLayoutHeightOf(mInputView, layoutHeight);
        }
    }

    int getCurrentAutoCapsState() {
        if (mEmojiSearchQuery != null) {
            // While the emoji search is open the keys type into the query, not into the editor, so
            // auto-caps has nothing to derive from: the editor's text never changes and shift would
            // be re-armed after every letter, turning the whole query into capitals. Reporting no
            // CAP_MODE bit here covers every path that asks — a key press, a layout switch, a
            // keyboard reload — with one answer.
            return NO_AUTO_CAPS;
        }
        return mInputLogic.getCurrentAutoCapsState(mSettings.getCurrent(),
                mRichImm.getCurrentSubtype().getKeyboardLayoutSet());
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
            hapticTickFeedback();
        } else {
            final boolean moved = steps != 0;
            for (; steps < 0; steps++)
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
            for (; steps > 0; steps--)
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
            hapticTickFeedback();
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
            hapticTickFeedback();
        } else {
            final boolean deleted = steps != 0;
            for (; steps < 0; steps++)
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL);
            hapticTickFeedback();
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
        mInputLogic.reloadTextCache();
        // Only reached after an actual cursor slide, and the reload lands asynchronously, so the
        // strip must not keep offering candidates bound to the pre-slide cache.
        onSuggestionsAffectingCursorMove();
    }

    /**
     * Invalidates the suggestion strip after the keyboard itself moved the cursor or the
     * selection (space slide, delete swipe). Those gestures go through
     * {@link RichInputConnection}, which updates the expected selection as it goes, so
     * {@link #onUpdateSelection} sees no external move, and it returns early anyway while a
     * cursor-move gesture is running. Without this direct notification the strip would keep
     * showing (and accepting taps on) words computed for the position the cursor has left.
     * Cheap and idempotent: it only bumps the session and clears the band.
     */
    private void onSuggestionsAffectingCursorMove() {
        if (mSuggestionsController != null) {
            mSuggestionsController.onSelectionChanged();
            // Clearing the band is only half of what a cursor move needs: the cursor has stopped
            // somewhere, and wherever that is the band must describe it. Without this the strip
            // stays blank until the next keystroke even though the cursor sits at the end of a word
            // the dictionary answers.
            mHandler.postRefreshSuggestionBand();
        }
    }

    /**
     * Re-derives the suggestion band after a cursor move has settled. Posted by
     * {@link UIHandler#postRefreshSuggestionBand}, never called directly.
     *
     * The emoji panel and the emoji search route through
     * {@link SuggestionsController#onSelectionChanged} to get a band that stays empty for as long as
     * they are up, so neither may be re-derived out from under: while either is shown the band is
     * left exactly as they left it.
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
        if (maybeRouteToEmojiSearch(event)) {
            return;
        }
        if (maybeRevertTatarAutocorrection(event)) {
            return;
        }
        maybeAutocorrectTatarWord(event);
        final InputTransaction completeInputTransaction =
                mInputLogic.onCodeInput(mSettings.getCurrent(), event);
        updateStateAfterInputTransaction(completeInputTransaction);
        maybeOfferTatarSuggestions(event);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    /**
     * Corrects the word a separator is about to finish (D3), BEFORE that separator reaches the input
     * logic.
     *
     * <p>Before, not after, on purpose: at this instant the editor is in exactly the state an
     * accepted suggestion needs — a trailing word with a collapsed cursor right behind it — so the
     * correction is the same single delete + commit, and the separator then travels the ordinary
     * path with the auto-space rule, the double-space gesture and the shift update all untouched.
     *
     * <p>The two conditions are a conjunction: the code point must be a word separator of the live
     * layout AND one of the separators D3 fires on at all
     * ({@link TatarWordUtils#isAutocorrectSeparator}, i.e. «пробел или пунктуация»). Everything else
     * — including Enter and Tab, which are word separators too — is left alone. The controller
     * decides whether anything is actually replaced; this method only recognizes the moment.
     */
    private void maybeAutocorrectTatarWord(final Event event) {
        if (mSuggestionsController == null) {
            return;
        }
        final int codePoint = event.mCodePoint;
        if (codePoint == Event.NOT_A_CODE_POINT
                || !TatarWordUtils.isAutocorrectSeparator(codePoint)
                || !mSettings.getCurrent().isWordSeparator(codePoint)) {
            return;
        }
        mSuggestionsController.maybeAutocorrectBeforeSeparator(codePoint);
    }

    /**
     * A backspace pressed immediately after an autocorrection restores what the user typed instead
     * of deleting a character (D3). Returns true when it did, in which case the key press is fully
     * handled and the ordinary backspace path never runs.
     *
     * <p>What follows a successful revert is exactly what a backspace does apart from the deletion:
     * the shift state is recomputed (the restored word can change auto-caps), the band is re-derived
     * from the new text, and the keyboard's own state machine still sees the key press.
     * {@link #maybeOfferTatarSuggestions} is deliberately skipped — a delete carries
     * {@link Event#NOT_A_CODE_POINT}, which is never a word separator, so the call would be a no-op.
     */
    private boolean maybeRevertTatarAutocorrection(final Event event) {
        if (mSuggestionsController == null || event.mKeyCode != Constants.CODE_DELETE) {
            return false;
        }
        if (!mSuggestionsController.maybeRevertAutocorrect()) {
            return false;
        }
        mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
        mSuggestionsController.onTextChanged();
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        return true;
    }

    /**
     * Offers Tatar suggestions once, right after the user has finished their first real word.
     *
     * The first check is the offer's own in-memory mirror of its one-shot flag, so once the offer has
     * been made a keypress costs a single field read: no text is read, no preference is touched and
     * the code point is not even classified. Which key presses count as finishing a word is decided
     * by {@link SuggestionsOfferController#isWordFinishingKeyPress}: being a word separator is
     * necessary but not sufficient, because Enter and Tab arrive as ordinary code points ('\n' and
     * '\t') and both are listed in symbols_word_separators. Delete and the language key are the
     * events that really do carry {@link Event#NOT_A_CODE_POINT}, which is never a separator.
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
        // False means the panel will not show in THIS process: the snapshot could not be built and
        // the preparation is never retried. Discarding that answer left a key that is drawn on the
        // keyboard, takes the press and does nothing, for as long as the process lives.
        if (!mEmojiPanelController.onEmojiKeyPressed()) {
            showEmojiUnavailableDialog();
        }
    }

    /**
     * The search pill inside the emoji panel was tapped and no search can be opened.
     *
     * Same register as {@link #showEmojiUnavailableDialog()} and for the same reason: the verdict
     * "the index is unusable" is cached for the life of the process, so without this the pill stays
     * painted and stays dead.
     */
    public void onEmojiSearchUnavailable() {
        showEmojiUnavailableDialog();
    }

    /**
     * An emoji was inserted from the panel (a grid tap, including a tap inside the Recent tab). The
     * text was already committed through {@link #onTextInput(String)}; this records the use of the
     * sequence in the recent-emoji list. Recording is gated and serialized inside the controller.
     */
    public void onEmojiInserted(final String sequence) {
        if (mEmojiPanelController != null) {
            mEmojiPanelController.onEmojiInserted(sequence);
        }
    }

    /**
     * The search pill in the emoji panel was tapped. The panel closes, the letter keyboard comes
     * back and every key press is routed into the emoji-search query instead of into the editor
     * until the search is left again.
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
    private boolean maybeRouteToEmojiSearch(final Event event) {
        final EmojiSearchQuery query = mEmojiSearchQuery;
        if (query == null || !mKeyboardSwitcher.isEmojiSearchShown()) {
            return false;
        }
        final boolean changed;
        if (event.mKeyCode == Constants.CODE_DELETE) {
            if (!query.backspace()) {
                onEmojiSearchClosed();
                mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(),
                        getCurrentRecapitalizeState());
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
            updateEmojiSearchView();
        }
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        return true;
    }

    /** Hands the current query text to the search bands, which re-run the match and redraw. */
    private void updateEmojiSearchView() {
        final EmojiSearchQuery query = mEmojiSearchQuery;
        if (query != null) {
            mKeyboardSwitcher.setEmojiSearchQuery(query.text());
        }
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

    private void hapticAndAudioFeedback(final int code, final int repeatCount) {
        final MainKeyboardView keyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (keyboardView != null && keyboardView.isInDraggingFinger()) {
            // No need to feedback while finger is dragging.
            return;
        }
        if (repeatCount > 0) {
            if (code == Constants.CODE_DELETE && !mInputLogic.mConnection.canDeleteCharacters()) {
                // No need to feedback when repeat delete key will have no effect.
                return;
            }
            // TODO: Use event time that the last feedback has been generated instead of relying on
            // a repeat count to thin out feedback.
            if (repeatCount % PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT == 0) {
                return;
            }
        }
        final AudioAndHapticFeedbackManager feedbackManager = AudioAndHapticFeedbackManager.getInstance();
        if (repeatCount == 0) {
            // TODO: Reconsider how to perform haptic feedback when repeating key.
            feedbackManager.performHapticFeedback(keyboardView);
        }
        feedbackManager.performAudioFeedback(code);
    }

    private void hapticTickFeedback() {
        final AudioAndHapticFeedbackManager feedbackManager = AudioAndHapticFeedbackManager.getInstance();
        feedbackManager.performTickFeedback();
    }

    // Callback of the {@link KeyboardActionListener}. This is called when a key is depressed;
    // release matching call is {@link #onReleaseKey(int,boolean)} below.
    @Override
    public void onPressKey(final int primaryCode, final int repeatCount,
            final boolean isSinglePointer) {
        mKeyboardSwitcher.onPressKey(primaryCode, isSinglePointer, getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
        hapticAndAudioFeedback(primaryCode, repeatCount);
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

    private void setNavigationBarColor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final Window window = getWindow().getWindow();
            if (window == null) {
                return;
            }
            final SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(this);
            final int keyboardColor = Settings.readKeyboardColor(prefs, this);
            window.setNavigationBarColor(keyboardColor);
            window.setNavigationBarContrastEnforced(false);
            final int flag = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (ResourceUtils.isBrightColor(keyboardColor)) {
                window.getInsetsController().setSystemBarsAppearance(flag, flag);
            } else {
                window.getInsetsController().setSystemBarsAppearance(0, flag);
            }
        }
    }
}
