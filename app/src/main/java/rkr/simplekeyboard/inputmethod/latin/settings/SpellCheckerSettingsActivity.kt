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

package rkr.simplekeyboard.inputmethod.latin.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The settings entry the system spell-checker list opens: the subsystem metadata names this
 * component, so it must stay exported. It reads no extras and only forwards to the spell-checker
 * screen of [SettingsHostActivity].
 */
class SpellCheckerSettingsActivity : Activity() {
    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        val host = Intent(this, SettingsHostActivity::class.java)
                .putExtra(SettingsHostActivity.EXTRA_SCREEN,
                        SettingsHostActivity.Screen.SPELL_CHECKER.name)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(host)
        finish()
    }
}
