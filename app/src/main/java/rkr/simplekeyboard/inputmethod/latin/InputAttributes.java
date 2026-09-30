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
     * {@link EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING}, set by incognito browser tabs and
     * messenger "incognito keyboard" switches. Read from {@code imeOptions}, not {@code inputType},
     * for every input class. The API 26 constant is inlined at compile time; on API 24-25 it is
     * never set.
     */
    final public boolean mNoPersonalizedLearning;
    /**
     * True for {@link InputType#TYPE_TEXT_VARIATION_POSTAL_ADDRESS}. Only stops personal
     * dictionary learning (a street name passes every content filter); suggestions are unaffected.
     */
    final public boolean mIsPostalAddressField;
    final private int mInputType;

    public InputAttributes(final EditorInfo editorInfo, final boolean isFullscreenMode) {
        mTargetApplicationPackageName = null != editorInfo ? editorInfo.packageName : null;
        final int inputType = null != editorInfo ? editorInfo.inputType : 0;
        final int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        mInputType = inputType;
        // Computed before the non-text early return: the flag applies to every input class.
        mNoPersonalizedLearning = readNoPersonalizedLearning(editorInfo);
        mIsPasswordField = InputTypeUtils.isPasswordInputType(inputType)
                || InputTypeUtils.isVisiblePasswordInputType(inputType);
        // Not part of shouldSuppressSuggestions below: an address field keeps suggestions and only
        // blocks learning. Computed before the non-text early return, like the flag above.
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
        // The learning flag is compared too: an app that toggles its "incognito keyboard" switch
        // restarts input with the same inputType and only imeOptions changed.
        return editorInfo.inputType == mInputType
                && readNoPersonalizedLearning(editorInfo) == mNoPersonalizedLearning;
    }

    // Pretty print
    @Override
    public String toString() {
        // The target app's package name is not printed: which app the user types in is not
        // something the keyboard logs.
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
