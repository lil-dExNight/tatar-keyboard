/*
 * Copyright (C) 2014 The Android Open Source Project
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

package rkr.simplekeyboard.inputmethod.latin.utils;

import android.app.Dialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.Window;
import android.view.WindowManager;

import rkr.simplekeyboard.inputmethod.R;

public final class DialogUtils {
    private DialogUtils() {
        // This utility class is not publicly instantiable.
    }

    public static Context getPlatformDialogThemeContext(final Context context) {
        // Because {@link AlertDialog.Builder.create()} doesn't honor the specified theme with
        // createThemeContextWrapper=false, the result dialog box has unneeded paddings around it.
        return new ContextThemeWrapper(context, R.style.platformDialogTheme);
    }

    /**
     * Makes the dialog drop touches delivered while another window obscures it (tapjacking
     * protection: IME-attached dialogs float over other apps; the app's own layouts already set
     * {@code android:filterTouchesWhenObscured}).
     *
     * <p>The flag lives on views, so it is set on the decor view, which exists only once the dialog
     * is shown, hence the show listener. Install before {@code show()}; do not combine with another
     * OnShowListener on the same dialog.</p>
     */
    public static void filterObscuredTouches(final Dialog dialog) {
        dialog.setOnShowListener(d -> {
            final Window window = dialog.getWindow();
            if (window != null) {
                window.getDecorView().setFilterTouchesWhenObscured(true);
            }
        });
    }

    /**
     * Sets {@code FLAG_SECURE} on the dialog's own window (no screenshots, screen recording or
     * recents thumbnail). The settings host's activity-wide flag does not extend to dialog
     * windows, so dialogs that show personal content (a saved word or pair) set it themselves.
     *
     * <p>No show listener is needed: the window exists from construction and flags set before
     * {@code show()} apply, so this composes with {@link #filterObscuredTouches}.</p>
     */
    public static void securePersonalContent(final Dialog dialog) {
        final Window window = dialog.getWindow();
        if (window != null) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
