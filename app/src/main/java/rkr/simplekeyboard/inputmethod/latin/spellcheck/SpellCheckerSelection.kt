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

package rkr.simplekeyboard.inputmethod.latin.spellcheck

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Whether the system has selected this app's spell checker. The framework publishes no getter for
 * the selection, so the two secure settings the system keeps it in are read by their platform
 * names; a failed or restricted read counts as "not selected" instead of an error.
 */
object SpellCheckerSelection {

    private const val SELECTED_SPELL_CHECKER = "selected_spell_checker"
    private const val SPELL_CHECKER_ENABLED = "spell_checker_enabled"

    fun isSelected(context: Context): Boolean {
        val flattened = try {
            Settings.Secure.getString(context.contentResolver, SELECTED_SPELL_CHECKER)
        } catch (e: Exception) {
            return false
        } ?: return false
        val selected = ComponentName.unflattenFromString(flattened) ?: return false
        if (selected != ComponentName(context, TatarSpellCheckerService::class.java)) {
            return false
        }
        return try {
            Settings.Secure.getInt(context.contentResolver, SPELL_CHECKER_ENABLED, 1) != 0
        } catch (e: Exception) {
            false
        }
    }
}
