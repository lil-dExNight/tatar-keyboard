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

import rkr.simplekeyboard.inputmethod.latin.common.Constants

/**
 * Everything the one-shot offer needs to know about the live IME, behind a seam so the decision
 * logic can be exercised by plain JVM tests (the two dialogs themselves are Android and are covered
 * by a source-contract test instead). Every method is called on the UI thread.
 */
interface OfferEnvironment {
    /**
     * The `pref_tatar_suggestions` value, read fresh rather than taken from `SettingsValues`: the
     * offer exists only for users who have suggestions off, and the unavailable message is
     * cancelled when the user turns them back off.
     */
    fun isSuggestionsSettingEnabled(): Boolean

    /** The active subtype is the Tatar one. */
    fun isTatarSubtypeActive(): Boolean

    /**
     * The input view is shown AND its window token is non-null. Both halves are one condition
     * because a dialog attached to the IME window cannot exist without a token.
     */
    fun isInputViewShownWithWindowToken(): Boolean

    /**
     * Whether this editor may be offered suggestions at all:
     * `mInputAttributes.mShouldShowSuggestions` (false in password, `NO_SUGGESTIONS`, e-mail and URI
     * fields) AND NOT `mInputAttributes.mNoPersonalizedLearning`
     * (`EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`, set by incognito browser tabs and by the
     * "incognito keyboard" switch of messengers — those fields carry an ordinary text inputType, so
     * the first half alone lets them through).
     *
     * Checked before any text is read, so such a field is never even looked at and the one-shot flag
     * is never spent on one.
     */
    fun editorAllowsSuggestions(): Boolean

    /** False before the user has unlocked the device (direct boot). */
    fun isUserUnlocked(): Boolean

    /** Another dialog (the subtype picker, or one of these two) is already on screen. */
    fun isAnotherDialogShowing(): Boolean

    /** The IME is suppressed because a hardware keyboard is attached. */
    fun isImeSuppressedByHardwareKeyboard(): Boolean

    /** A finger is dragging on the keyboard: a window must not pop up under a held finger. */
    fun isInDraggingFinger(): Boolean

    /**
     * The editor's cached text before the cursor. Read only after [editorAllowsSuggestions] has
     * returned true, and never logged.
     */
    fun cachedTextBeforeCursor(): CharSequence?
}

/**
 * The one-shot "offer spent" flag in device-protected preferences.
 *
 * One global key rather than one per language: a second language does not earn a second offer.
 */
interface OfferFlagStore {
    fun isOfferSpent(): Boolean

    /** Marks the offer spent durably. Called exactly once, before the dialog is shown. */
    fun spendOffer()
}

/** Shows the two modal dialogs over the IME window. */
interface OfferPresenter {
    /** The offer to turn Tatar suggestions on: two buttons, dismissible. */
    fun showEnableOffer()

    /** The one-shot "could not turn suggestions on" message: one dismiss button. */
    fun showUnavailableMessage()
}

/**
 * Decides whether and when the user is offered Tatar suggestions, and whether the one-shot
 * "could not be turned on" message is shown.
 *
 * Two independent one-shot events live here:
 * - the **offer**: shown at most once per installation, after the user commits a first word of at
 *   least [MIN_WORD_LETTERS] letters with the Tatar subtype active. The durable flag is written
 *   before the dialog appears, so no rotation, crash or reboot can bring it back (never showing it
 *   is acceptable; showing it twice is not);
 * - the **unavailable message**: shown at most once per process, only for a preparation an explicit
 *   enable asked for. Deferred, not dropped, when the surroundings are wrong; they are re-checked
 *   in the method that shows it.
 *
 * All methods are called on the UI thread.
 */
class SuggestionsOfferController(
    private val environment: OfferEnvironment,
    private val flags: OfferFlagStore,
    private val presenter: OfferPresenter,
) {
    /** Mirror of the durable flag, read once, so the per-keystroke check is one field read. */
    private var offerSpent: Boolean = flags.isOfferSpent()

    // Deferred unavailable message. Neither field survives the process, by design: another
    // preparation needs another OFF -> ON transition, so a durable flag would buy nothing.
    private var unavailableMessagePending: Boolean = false
    private var unavailableMessageShown: Boolean = false

    /**
     * True while the offer can still be shown. The keystroke path reads this first, so once the
     * offer is spent a keypress costs one field read and nothing else.
     */
    fun isOfferPending(): Boolean = !offerSpent

    /**
     * A key press has just been committed to the editor.
     *
     * The only trigger of the offer, and only for a key press that finished a word (see
     * [isWordFinishingKeyPress]). Showing the keyboard is not a trigger: opening it without typing
     * shows no intent to type Tatar. Nothing is remembered between fields; an unfinished word is
     * recounted from the editor cache at the next separator.
     *
     * @param codePoint the committed code point, or `Event.NOT_A_CODE_POINT` for a functional key.
     * @param isWordSeparator the editor's own classification of [codePoint].
     */
    fun onKeyPressCommitted(codePoint: Int, isWordSeparator: Boolean) {
        if (offerSpent) return
        if (!isWordFinishingKeyPress(codePoint, isWordSeparator)) return
        // The offer is for users who do not have the feature; anyone who already turned it on knows.
        if (environment.isSuggestionsSettingEnabled()) return
        if (!isEnvironmentReady()) return
        // Read the text last, so a field that forbids suggestions is never read at all.
        if (
            !TatarWordUtils.endsWithWordOfAtLeast(
                environment.cachedTextBeforeCursor(),
                MIN_WORD_LETTERS,
            )
        ) {
            return
        }
        // Spend the flag on the decision to show, not on the answer: a crash or a rotation between
        // the write and the answer must not resurrect the offer.
        offerSpent = true
        flags.spendOffer()
        presenter.showEnableOffer()
    }

    /**
     * A dictionary preparation that an explicit enable requested ended `Unavailable`.
     *
     * At most one message per preparation attempt and at most one per process. The message is shown
     * right away when the surroundings allow it, and deferred to the next input-view boundary
     * otherwise; it is never turned into a silent failure.
     */
    fun onDictionaryUnavailableAfterExplicitEnable() {
        if (unavailableMessageShown) return
        unavailableMessagePending = true
        showUnavailableMessageIfPossible()
    }

    /** An input view has started: the boundary at which a deferred message gets another chance. */
    fun onInputViewStarted() {
        showUnavailableMessageIfPossible()
    }

    /**
     * An ON -> OFF transition of the setting was observed.
     *
     * The one condition that cancels a deferred message instead of deferring it further: the user
     * withdrew the attempt the message reports on. A later enable starts a fresh attempt with its
     * own message.
     */
    fun onSuggestionsSettingDisabled() {
        unavailableMessagePending = false
    }

    /**
     * Shows the deferred message if it may be shown now.
     *
     * The conditions are evaluated here, in the method that calls [OfferPresenter], not only where
     * the message was queued: meanwhile the user may have moved into a password field, rotated the
     * screen, opened the subtype picker or turned the setting off, and a dialog in the wrong field
     * is worse than no message.
     */
    private fun showUnavailableMessageIfPossible() {
        if (!unavailableMessagePending) return
        if (!environment.isSuggestionsSettingEnabled()) {
            // The cancelling condition, mirroring the first condition of the offer.
            unavailableMessagePending = false
            return
        }
        // Any of the seven environment conditions only defers: the message stays pending.
        if (!isEnvironmentReady()) return
        unavailableMessagePending = false
        unavailableMessageShown = true
        presenter.showUnavailableMessage()
    }

    /**
     * The seven conditions for a modal dialog over the IME window, shared by the offer and the
     * message. The offer's own conditions (setting off, flag unspent) are checked by its caller.
     */
    private fun isEnvironmentReady(): Boolean =
        environment.isTatarSubtypeActive() &&
            environment.isInputViewShownWithWindowToken() &&
            environment.editorAllowsSuggestions() &&
            environment.isUserUnlocked() &&
            !environment.isAnotherDialogShowing() &&
            !environment.isImeSuppressedByHardwareKeyboard() &&
            !environment.isInDraggingFinger()

    companion object {
        /**
         * How many letters a finished word must have before the offer is made. Matches the minimum
         * prefix length suggestions need.
         */
        const val MIN_WORD_LETTERS = 3

        /**
         * True when this key press finished a word, which is the only moment the offer may be made.
         *
         * [isWordSeparator] is necessary but not sufficient. Enter and Tab are ordinary code points
         * ([Constants.CODE_ENTER] is `'\n'`, [Constants.CODE_TAB] is `'\t'`) listed in
         * `symbols_word_separators`, but the host app often answers them by hiding the keyboard,
         * and `hideWindow()` would dismiss the dialog in the same frame after the flag was spent.
         * Delete and the language key carry `Event.NOT_A_CODE_POINT`, which is never a separator.
         */
        @JvmStatic
        fun isWordFinishingKeyPress(codePoint: Int, isWordSeparator: Boolean): Boolean =
            isWordSeparator &&
                codePoint != Constants.CODE_ENTER &&
                codePoint != Constants.CODE_TAB
    }
}
