/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
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

import android.media.AudioManager
import android.view.View
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.latin.AudioAndHapticFeedbackManager

/**
 * The "Key press" settings screen and the three seek-bar value proxies (T2 part 3,
 * docs/ROADMAP-P6.md), moved verbatim out of [SettingsHostActivity]. The proxies were ported 1:1
 * from KeyPressSettingsFragment and AppearanceSettingsFragment in the wave-S2 redesign and are
 * unchanged here; [bottomOffsetProxy] lives with them even though the Appearance screen (pinned to
 * the activity by KeyboardHeightPreferenceTest's neighbours) is its caller.
 *
 * They are `internal` extension functions on the activity, so every call site kept its exact text.
 * The one mechanical qualification the move required: the companion's percentage constant is
 * referenced as `SettingsHostActivity.PERCENTAGE_FLOAT` from here (companion members do not
 * resolve unqualified through an extension receiver).
 */

internal fun SettingsHostActivity.buildKeyPressScreen() {
    val rows = ArrayList<View>()
    if (AudioAndHapticFeedbackManager.getInstance().hasVibrator()) {
        rows.add(switchRow(Settings.PREF_VIBRATE_ON,
                resources.getBoolean(R.bool.config_default_vibration_enabled),
                R.string.vibrate_on_keypress, R.string.vibrate_on_keypress_summary))
    }
    val soundDefault = resources.getBoolean(R.bool.config_default_sound_enabled)
    var volumeRow: View? = null
    rows.add(switchRow(Settings.PREF_SOUND_ON, soundDefault,
            R.string.sound_on_keypress, R.string.sound_on_keypress_summary) { checked ->
        volumeRow?.let {
            setRowEnabled(it,
                    checked && !isRestricted(Settings.PREF_KEYPRESS_SOUND_VOLUME))
        }
    })
    val volume = valueRow(Settings.PREF_KEYPRESS_SOUND_VOLUME,
            R.string.prefs_keypress_sound_volume_settings,
            0, 100, 0, keypressSoundVolumeProxy())
    volumeRow = volume
    rows.add(volume)
    rows.add(switchRow(Settings.PREF_POPUP_ON,
            resources.getBoolean(R.bool.config_default_key_preview_popup),
            R.string.popup_on_keypress, R.string.popup_on_keypress_summary))
    rows.add(valueRow(Settings.PREF_KEY_LONGPRESS_TIMEOUT,
            R.string.prefs_key_longpress_timeout_settings,
            resources.getInteger(R.integer.config_min_longpress_timeout),
            resources.getInteger(R.integer.config_max_longpress_timeout),
            resources.getInteger(R.integer.config_longpress_timeout_step),
            keyLongpressTimeoutProxy()))
    addCard(rows)
    // android:dependency="sound_on" from the legacy screen. A managed
    // restriction on the volume key must win over the dependency.
    setRowEnabled(volume, prefs.getBoolean(Settings.PREF_SOUND_ON, soundDefault)
            && !isRestricted(Settings.PREF_KEYPRESS_SOUND_VOLUME))
}

internal fun SettingsHostActivity.keypressSoundVolumeProxy() = object : SeekBarDialogHelper.ValueProxy {
    override fun readValue(key: String): Int =
            (Settings.readKeypressSoundVolume(prefs) * SettingsHostActivity.PERCENTAGE_FLOAT).toInt()

    override fun readDefaultValue(key: String): Int =
            (Settings.readDefaultKeypressSoundVolume() * SettingsHostActivity.PERCENTAGE_FLOAT).toInt()

    override fun writeValue(value: Int, key: String) {
        prefs.edit().putFloat(key, value / SettingsHostActivity.PERCENTAGE_FLOAT).apply()
    }

    override fun writeDefaultValue(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun getValueText(value: Int): String =
            if (value < 0) getString(R.string.settings_system_default)
            else value.toString()

    override fun feedbackValue(value: Int) {
        AudioAndHapticFeedbackManager.getInstance().playSoundEffect(
                AudioManager.FX_KEYPRESS_STANDARD, value / SettingsHostActivity.PERCENTAGE_FLOAT)
    }
}

internal fun SettingsHostActivity.keyLongpressTimeoutProxy() = object : SeekBarDialogHelper.ValueProxy {
    override fun readValue(key: String): Int =
            Settings.readKeyLongpressTimeout(prefs, resources)

    override fun readDefaultValue(key: String): Int =
            Settings.readDefaultKeyLongpressTimeout(resources)

    override fun writeValue(value: Int, key: String) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun writeDefaultValue(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun getValueText(value: Int): String =
            getString(R.string.abbreviation_unit_milliseconds, value)

    override fun feedbackValue(value: Int) {}
}

internal fun SettingsHostActivity.bottomOffsetProxy() = object : SeekBarDialogHelper.ValueProxy {
    override fun readValue(key: String): Int =
            Settings.readBottomOffsetPortrait(prefs)

    override fun readDefaultValue(key: String): Int = Settings.DEFAULT_BOTTOM_OFFSET

    override fun writeValue(value: Int, key: String) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun writeDefaultValue(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun getValueText(value: Int): String =
            if (value < 0) getString(R.string.settings_system_default)
            else getString(R.string.abbreviation_unit_dp, value)

    override fun feedbackValue(value: Int) {}
}
