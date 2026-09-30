/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2021 wittmane
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

package rkr.simplekeyboard.inputmethod.latin.settings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * The app's exported settings entry point, kept only as a forwarder to
 * {@link SettingsHostActivity}: method.xml (android:settingsActivity),
 * LatinIME#launchSettings and any launcher shortcuts saved by users all
 * point at this component name, so the class must stay.
 *
 * The former PreferenceActivity stack is gone. Some OEM settings apps
 * still launch this activity with an
 * EXTRA_SHOW_FRAGMENT ("_:show_fragment") extra naming one of the old
 * fragments; those extras are intentionally ignored — every request simply
 * opens the root of the View-based settings UI.
 */
public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(final Bundle savedState) {
        super.onCreate(savedState);
        final Intent host = new Intent(this, SettingsHostActivity.class);
        // Reuse a host instance already in this task (repeated taps on the
        // keyboard's settings key) instead of stacking a second one.
        host.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(host);
        finish();
    }
}
