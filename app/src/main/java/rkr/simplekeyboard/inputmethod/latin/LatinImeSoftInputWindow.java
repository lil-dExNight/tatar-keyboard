/*
 * Copyright (C) 2011 The Android Open Source Project
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

package rkr.simplekeyboard.inputmethod.latin;

import android.content.SharedPreferences;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowInsetsController;

import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewLayoutUtils;

/**
 * The soft-input window's layout and appearance helpers of {@link LatinIME} (T2 split, part 2
 * of 3): the input-area height/gravity bookkeeping, the insets-changed relayout, and the
 * navigation-bar colour that follows the keyboard theme. Static methods taking the service, so
 * the bodies moved here verbatim.
 */
final class LatinImeSoftInputWindow {
    private LatinImeSoftInputWindow() {
        // Static methods only.
    }

    static void updateSoftInputWindowLayoutParameters(final LatinIME ime) {
        // Override layout parameters to expand {@link SoftInputWindow} to the entire screen.
        // See {@link InputMethodService#setinputView(View)} and
        // {@link SoftInputWindow#updateWidthHeight(WindowManager.LayoutParams)}.
        final Window window = ime.getWindow().getWindow();
        ViewLayoutUtils.updateLayoutHeightOf(window, LayoutParams.MATCH_PARENT);
        // This method may be called before {@link #setInputView(View)}.
        if (ime.mInputView != null) {
            // In non-fullscreen mode, {@link InputView} and its parent inputArea should expand to
            // the entire screen and be placed at the bottom of {@link SoftInputWindow}.
            // In fullscreen mode, these shouldn't expand to the entire screen and should be
            // coexistent with {@link #mExtractedArea} above.
            // See {@link InputMethodService#setInputView(View) and
            // com.android.internal.R.layout.input_method.xml.
            final int layoutHeight = ime.isFullscreenMode()
                    ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT;
            final View inputArea = window.findViewById(android.R.id.inputArea);
            ViewLayoutUtils.updateLayoutHeightOf(inputArea, layoutHeight);
            ViewLayoutUtils.updateLayoutGravityOf(inputArea, Gravity.BOTTOM);
            ViewLayoutUtils.updateLayoutHeightOf(ime.mInputView, layoutHeight);
        }
    }

    static void onInputGeometryChanged(final LatinIME ime) {
        if (ime.mInputView == null) {
            return;
        }
        ime.mInputView.requestLayout();
        ime.mInputView.requestApplyInsets();
        final Window window = ime.getWindow().getWindow();
        if (window != null) {
            window.getDecorView().requestLayout();
            window.getDecorView().requestApplyInsets();
        }
    }

    static void setNavigationBarColor(final LatinIME ime) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final Window window = ime.getWindow().getWindow();
            if (window == null) {
                return;
            }
            final SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(ime);
            final int keyboardColor = Settings.readKeyboardColor(prefs, ime);
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
