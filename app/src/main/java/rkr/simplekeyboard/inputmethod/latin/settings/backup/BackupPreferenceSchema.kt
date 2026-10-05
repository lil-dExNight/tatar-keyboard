/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.settings.backup

import rkr.simplekeyboard.inputmethod.keyboard.KeyboardTheme
import rkr.simplekeyboard.inputmethod.latin.settings.Settings

/**
 * The preference-key schema of the settings document in a backup archive: every key the current
 * build stores, with the value type it is stored as.
 *
 * A key the table does not know is dropped on import: an old backup legitimately carries
 * since-removed keys, and dropping them keeps both directions compatible. A known key carrying a
 * wrong-typed value fails the whole import instead: the app never writes one, so it marks a
 * hand-crafted file, and applying it would plant a type confusion into the live preferences.
 */
object BackupPreferenceSchema {

    /** The SharedPreferences value type a key is stored as; no known key holds a long or a set. */
    enum class ValueType { BOOLEAN, INT, FLOAT, STRING }

    val KEY_TYPES: Map<String, ValueType> = mapOf(
        // Booleans.
        Settings.PREF_AUTO_CAP to ValueType.BOOLEAN,
        Settings.PREF_VIBRATE_ON to ValueType.BOOLEAN,
        Settings.PREF_SOUND_ON to ValueType.BOOLEAN,
        Settings.PREF_POPUP_ON to ValueType.BOOLEAN,
        Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY to ValueType.BOOLEAN,
        Settings.PREF_USE_ON_SCREEN to ValueType.BOOLEAN,
        Settings.PREF_ENABLE_IME_SWITCH to ValueType.BOOLEAN,
        Settings.PREF_SHOW_SPECIAL_CHARS to ValueType.BOOLEAN,
        Settings.PREF_SHOW_NUMBER_ROW to ValueType.BOOLEAN,
        Settings.PREF_SHOW_EMOJI_KEY to ValueType.BOOLEAN,
        Settings.PREF_SPACE_SWIPE to ValueType.BOOLEAN,
        Settings.PREF_DELETE_SWIPE to ValueType.BOOLEAN,
        Settings.PREF_GLIDE_TYPING to ValueType.BOOLEAN,
        Settings.PREF_TATAR_SUGGESTIONS to ValueType.BOOLEAN,
        Settings.PREF_PERSONAL_DICTIONARY to ValueType.BOOLEAN,
        Settings.PREF_TATAR_AUTOCORRECT to ValueType.BOOLEAN,
        Settings.PREF_EMOJI_SUGGESTIONS to ValueType.BOOLEAN,
        Settings.PREF_INCOGNITO_MODE to ValueType.BOOLEAN,
        Settings.PREF_TATAR_SUGGESTIONS_OFFER_SPENT to ValueType.BOOLEAN,
        // Ints.
        Settings.PREF_KEY_LONGPRESS_TIMEOUT to ValueType.INT,
        Settings.PREF_ONE_HANDED_SIDE to ValueType.INT,
        Settings.PREF_BOTTOM_OFFSET_PORTRAIT to ValueType.INT,
        // The packed color int the restriction loader writes.
        Settings.PREF_KEYBOARD_COLOR to ValueType.INT,
        // Floats.
        Settings.PREF_KEYPRESS_SOUND_VOLUME to ValueType.FLOAT,
        Settings.PREF_KEYBOARD_HEIGHT to ValueType.FLOAT,
        Settings.PREF_EMOJI_PANEL_HEIGHT to ValueType.FLOAT,
        // Strings.
        Settings.PREF_ENABLED_SUBTYPES to ValueType.STRING,
        Settings.PREF_CURRENT_SUBTYPE to ValueType.STRING,
        // The theme id as a decimal string, as KeyboardTheme.saveKeyboardThemeId writes it.
        KeyboardTheme.KEYBOARD_THEME_KEY to ValueType.STRING,
    )

    /**
     * [values] with every unknown key dropped.
     * @throws BackupPreferencesXml.PreferencesXmlException on a known key with a wrong value type.
     */
    fun applyTo(values: Map<String, Any>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>(values.size)
        for ((key, value) in values) {
            val expected = KEY_TYPES[key] ?: continue
            val matches = when (expected) {
                ValueType.BOOLEAN -> value is Boolean
                ValueType.INT -> value is Int
                ValueType.FLOAT -> value is Float
                ValueType.STRING -> value is String
            }
            if (!matches) {
                throw BackupPreferencesXml.PreferencesXmlException("preference value type mismatch")
            }
            out[key] = value
        }
        return out
    }
}
