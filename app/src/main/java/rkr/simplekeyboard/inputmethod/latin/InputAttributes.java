/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (C) 2017 Raimondas Rimkus
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

import android.text.InputType;
import android.util.Log;
import android.view.inputmethod.EditorInfo;

import rkr.simplekeyboard.inputmethod.latin.utils.InputTypeUtils;

/**
 * Class to hold attributes of the input field.
 */
public final class InputAttributes {
    private final String TAG = InputAttributes.class.getSimpleName();

    final public String mTargetApplicationPackageName;
    final public boolean mInputTypeNoAutoCorrect;
    final public boolean mIsPasswordField;
    final public boolean mShouldShowSuggestions;
    final public boolean mApplicationSpecifiedCompletionOn;
    final public boolean mShouldInsertSpacesAutomatically;
    /**
     * Whether the editor asked the IME not to learn from, or personalize for, what is typed in it
     * ({@link EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING}). Incognito browser tabs and the
     * "incognito keyboard" switch of messengers set it, usually on a field whose inputType is an
     * ordinary text one, so this is the only signal that distinguishes them.
     *
     * <p>Unlike every other attribute here it comes from {@code imeOptions} rather than from
     * {@code inputType}, and it is computed for every input class rather than for text fields
     * only.</p>
     *
     * <p>The constant was added in API 26 while this app supports API 24 upwards, but it is a
     * compile-time {@code static final int}, so its value is inlined at build time and nothing is
     * resolved at runtime; on API 24-25 no platform sets the bit and this is simply false.</p>
     */
    final public boolean mNoPersonalizedLearning;
    /**
     * True for {@link InputType#TYPE_TEXT_VARIATION_POSTAL_ADDRESS}. Used ONLY as a factor of the
     * E4c learning predicate: a street or a village name in Cyrillic passes every content filter
     * the personal dictionary has, so the field type itself is the only thing that can tell it
     * apart. Suggestions in such a field are unaffected.
     */
    final public boolean mIsPostalAddressField;
    /**
     * Whether the floating gesture preview should be disabled. If true, this should override the
     * corresponding keyboard settings preference, always suppressing the floating preview text.
     */
    final private int mInputType;

    public InputAttributes(final EditorInfo editorInfo, final boolean isFullscreenMode) {
        mTargetApplicationPackageName = null != editorInfo ? editorInfo.packageName : null;
        final int inputType = null != editorInfo ? editorInfo.inputType : 0;
        final int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        mInputType = inputType;
        // Deliberately computed before the early return of the non-text branch below: a field that
        // forbids personalized learning has to be recognised whatever its input class is.
        mNoPersonalizedLearning = readNoPersonalizedLearning(editorInfo);
        mIsPasswordField = InputTypeUtils.isPasswordInputType(inputType)
                || InputTypeUtils.isVisiblePasswordInputType(inputType);
        // E4c, LOCAL to the personal dictionary: a postal-address field is NOT part of
        // shouldSuppressSuggestions below, so suggestions stay on there exactly as before — only
        // LEARNING is blocked. Computed here, before the non-text early return, for the same reason
        // mNoPersonalizedLearning is: the answer must not depend on the input class.
        mIsPostalAddressField = InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
                == (inputType & InputType.TYPE_MASK_VARIATION);
        if (inputClass != InputType.TYPE_CLASS_TEXT) {
            // If we are not looking at a TYPE_CLASS_TEXT field, the following strange
            // cases may arise, so we do a couple sanity checks for them. If it's a
            // TYPE_CLASS_TEXT field, these special cases cannot happen, by construction
            // of the flags.
            if (null == editorInfo) {
                Log.w(TAG, "No editor info for this field. Bug?");
            } else if (InputType.TYPE_NULL == inputType) {
                // TODO: We should honor TYPE_NULL specification.
                Log.i(TAG, "InputType.TYPE_NULL is specified");
            } else if (inputClass == 0) {
                // TODO: is this check still necessary?
                Log.w(TAG, String.format("Unexpected input class: inputType=0x%08x"
                        + " imeOptions=0x%08x", inputType, editorInfo.imeOptions));
            }
            mShouldShowSuggestions = false;
            mInputTypeNoAutoCorrect = false;
            mApplicationSpecifiedCompletionOn = false;
            mShouldInsertSpacesAutomatically = false;
            return;
        }
        // inputClass == InputType.TYPE_CLASS_TEXT
        final int variation = inputType & InputType.TYPE_MASK_VARIATION;
        final boolean flagNoSuggestions =
                0 != (inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final boolean flagMultiLine =
                0 != (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final boolean flagAutoCorrect =
                0 != (inputType & InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        final boolean flagAutoComplete =
                0 != (inputType & InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE);

        // TODO: Have a helper method in InputTypeUtils
        // Make sure that passwords are not displayed in {@link SuggestionStripView}.
        final boolean shouldSuppressSuggestions = mIsPasswordField
                || InputTypeUtils.isEmailVariation(variation)
                || InputType.TYPE_TEXT_VARIATION_URI == variation
                || InputType.TYPE_TEXT_VARIATION_FILTER == variation
                || flagNoSuggestions
                || flagAutoComplete;
        mShouldShowSuggestions = !shouldSuppressSuggestions;

        mShouldInsertSpacesAutomatically = InputTypeUtils.isAutoSpaceFriendlyType(inputType);

        // If it's a browser edit field and auto correct is not ON explicitly, then
        // disable auto correction, but keep suggestions on.
        // If NO_SUGGESTIONS is set, don't do prediction.
        // If it's not multiline and the autoCorrect flag is not set, then don't correct
        mInputTypeNoAutoCorrect =
                (variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT && !flagAutoCorrect)
                || flagNoSuggestions
                || (!flagAutoCorrect && !flagMultiLine);

        mApplicationSpecifiedCompletionOn = flagAutoComplete && isFullscreenMode;
    }

    private static boolean readNoPersonalizedLearning(final EditorInfo editorInfo) {
        final int imeOptions = null != editorInfo ? editorInfo.imeOptions : 0;
        return 0 != (imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    }

    public boolean isSameInputType(final EditorInfo editorInfo) {
        // The personalized-learning flag takes part in the comparison because the caller uses this
        // to decide whether the settings may be reused as they are. An app that flips its own
        // "incognito keyboard" switch calls restartInput() on the same view with the same inputType
        // and only imeOptions changed; comparing inputType alone would keep the previous, permissive
        // attributes for a field that has just asked not to be personalized for.
        return editorInfo.inputType == mInputType
                && readNoPersonalizedLearning(editorInfo) == mNoPersonalizedLearning;
    }

    // Pretty print
    @Override
    public String toString() {
        // The target app's package name is deliberately NOT printed (2026-09-24 audit, finding
        // 10): this string is for debugging, and which app the user typed in is not the
        // keyboard's business to log.
        return String.format(
                "%s: inputType=0x%08x%s%s%s%s%s%s\n", getClass().getSimpleName(),
                mInputType,
                (mInputTypeNoAutoCorrect ? " noAutoCorrect" : ""),
                (mIsPasswordField ? " password" : ""),
                (mShouldShowSuggestions ? " shouldShowSuggestions" : ""),
                (mNoPersonalizedLearning ? " noPersonalizedLearning" : ""),
                (mApplicationSpecifiedCompletionOn ? " appSpecified" : ""),
                (mShouldInsertSpacesAutomatically ? " insertSpaces" : ""));
    }
}
