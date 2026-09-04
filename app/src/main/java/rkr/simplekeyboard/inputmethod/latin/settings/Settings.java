/*
 * Copyright (C) 2013 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2024 wittmane
 * Copyright (C) 2019 Micha LaQua
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardTheme;
import rkr.simplekeyboard.inputmethod.latin.AudioAndHapticFeedbackManager;
import rkr.simplekeyboard.inputmethod.latin.InputAttributes;
import rkr.simplekeyboard.inputmethod.latin.RichInputMethodManager;

public final class Settings extends BroadcastReceiver implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = Settings.class.getSimpleName();
    public static final String ACTIVE_RESTRICTIONS = "active_restrictions";
    // Settings screens
    public static final String SCREEN_THEME = "screen_theme";
    // In the same order as xml/prefs.xml
    public static final String PREF_AUTO_CAP = "auto_cap";
    public static final String PREF_VIBRATE_ON = "vibrate_on";
    public static final String PREF_SOUND_ON = "sound_on";
    public static final String PREF_POPUP_ON = "popup_on";
    public static final String PREF_SHOW_LANGUAGE_SWITCH_KEY = "pref_show_language_switch_key";
    public static final String PREF_USE_ON_SCREEN = "pref_use_on_screen";
    public static final String PREF_ENABLE_IME_SWITCH = "pref_enable_ime_switch";
    public static final String PREF_ENABLED_SUBTYPES = "pref_enabled_subtypes";
    public static final String PREF_CURRENT_SUBTYPE = "pref_current_subtype";
    public static final String PREF_KEYPRESS_SOUND_VOLUME = "pref_keypress_sound_volume";
    public static final String PREF_KEY_LONGPRESS_TIMEOUT = "pref_key_longpress_timeout";
    public static final String PREF_KEYBOARD_HEIGHT = "pref_keyboard_height";
    public static final String PREF_BOTTOM_OFFSET_PORTRAIT = "pref_bottom_offset_portrait";
    public static final String PREF_KEYBOARD_COLOR = "pref_keyboard_color";
    public static final String PREF_SHOW_SPECIAL_CHARS = "pref_show_special_chars";
    public static final String PREF_SHOW_NUMBER_ROW = "pref_show_number_row";
    public static final String PREF_SHOW_EMOJI_KEY = "pref_show_emoji_key";
    public static final String PREF_SPACE_SWIPE = "pref_space_swipe";
    public static final String PREF_DELETE_SWIPE = "pref_delete_swipe";
    public static final String PREF_TATAR_SUGGESTIONS = "pref_tatar_suggestions";
    /**
     * The personal dictionary: one toggle for both reading and writing, default OFF (E4b). A
     * separate "remember typed words" switch is deliberately not introduced: two toggles give four
     * states, only three of which mean anything, and would force the user to be told the difference
     * between "do not remember" and "do not show".
     *
     * <p>Turning it off does NOT erase what was already saved — that is what the "Personal
     * dictionary" screen is for, and the settings text says so.
     */
    public static final String PREF_PERSONAL_DICTIONARY = "pref_personal_dictionary";
    /**
     * Autocorrection on a word separator (D3): its own toggle, default OFF, subordinate to
     * {@link #PREF_TATAR_SUGGESTIONS}.
     *
     * <p>Deliberately NOT merged into the suggestions switch: a suggestion offers, an autocorrection
     * changes what the user has already typed. The price of a mistake differs, and someone who
     * accepts the first is not obliged to accept the second.
     */
    public static final String PREF_TATAR_AUTOCORRECT = "pref_tatar_autocorrect";
    /**
     * Emoji suggestions in the NEXT_WORD band (mission 2 of {@code docs/EMOJI-SUGGEST-PLAN.md}):
     * their own toggle, default OFF, subordinate to {@link #PREF_TATAR_SUGGESTIONS} — the emoji
     * cell lives in the suggestion band, so without suggestions there is nowhere for it to appear.
     */
    public static final String PREF_EMOJI_SUGGESTIONS = "pref_emoji_suggestions";
    /**
     * One-shot marker: the offer to turn Tatar suggestions on has been made and is never made
     * again. Deliberately NOT part of {@link SettingsValues} — that object is rebuilt in full on
     * every change of every setting, and this value is read once per process and written once per
     * installation. Deliberately not an enterprise restriction either: it is a record of something
     * that happened, not a policy an administrator sets.
     */
    public static final String PREF_TATAR_SUGGESTIONS_OFFER_SPENT =
            "pref_tatar_suggestions_offer_spent";

    private static final float UNDEFINED_PREFERENCE_VALUE_FLOAT = -1.0f;
    private static final int UNDEFINED_PREFERENCE_VALUE_INT = -1;

    private Context mContext;
    private Resources mRes;
    private SharedPreferences mPrefs;
    private SettingsValues mSettingsValues;
    private RestrictionsManager mRestrictionsMgr;
    private final ReentrantLock mSettingsValuesLock = new ReentrantLock();

    private static final Settings sInstance = new Settings();

    public static Settings getInstance() {
        return sInstance;
    }

    public static void init(final Context context) {
        sInstance.onCreate(context);
    }

    private Settings() {
        // Intentional empty constructor for singleton.
    }

    private void onCreate(final Context context) {
        mContext = context;
        mRes = context.getResources();
        mPrefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        mRestrictionsMgr = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);
        loadRestrictions(mRestrictionsMgr, mPrefs);
        context.registerReceiver(this, new IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED));
    }

    public void onDestroy() {
        mPrefs.unregisterOnSharedPreferenceChangeListener(this);
        mContext.unregisterReceiver(this);
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences prefs, final String key) {
        mSettingsValuesLock.lock();
        try {
            if (mSettingsValues == null) {
                // TODO: Introduce a static function to register this class and ensure that
                // loadSettings must be called before "onSharedPreferenceChanged" is called.
                Log.w(TAG, "onSharedPreferenceChanged called before loadSettings.");
                return;
            }
            loadSettings(mSettingsValues.mInputAttributes);
        } finally {
            mSettingsValuesLock.unlock();
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        loadRestrictions(mRestrictionsMgr, mPrefs);
        onSharedPreferenceChanged(mPrefs, null);
        RichInputMethodManager.getInstance().reloadSubtypes(context);
    }

    public static Set<String> loadRestrictions(final RestrictionsManager restrictionsMgr, final SharedPreferences prefs) {
        final Bundle appRestrictions = restrictionsMgr.getApplicationRestrictions();
        final Set<String> restrictionKeys = appRestrictions.keySet();
        if (restrictionKeys.isEmpty()) {
            if (prefs.contains(ACTIVE_RESTRICTIONS)) {
                prefs.edit().remove(ACTIVE_RESTRICTIONS).apply();
            }
        } else {
            final SharedPreferences.Editor prefsEditor = prefs.edit();
            for (final String key : restrictionKeys) {
                switch (key) {
                    case PREF_ENABLED_SUBTYPES:
                        Log.i(TAG, "Loading restriction: " + key + "=" + appRestrictions.getString(key));
                        prefsEditor.putString(key, appRestrictions.getString(key));
                        break;
                    case SCREEN_THEME:
                        Log.i(TAG, "Loading restriction: " + key + "=" + appRestrictions.getString(key));
                        prefsEditor.putString(KeyboardTheme.KEYBOARD_THEME_KEY, appRestrictions.getString(key));
                        break;
                    case PREF_AUTO_CAP:
                    case PREF_SHOW_NUMBER_ROW:
                    case PREF_SHOW_SPECIAL_CHARS:
                    case PREF_SHOW_LANGUAGE_SWITCH_KEY:
                    case PREF_USE_ON_SCREEN:
                    case PREF_ENABLE_IME_SWITCH:
                    case PREF_DELETE_SWIPE:
                    case PREF_SPACE_SWIPE:
                    case PREF_VIBRATE_ON:
                    case PREF_SOUND_ON:
                    case PREF_POPUP_ON:
                        Log.i(TAG, "Loading restriction: " + key + "=" + appRestrictions.getBoolean(key));
                        prefsEditor.putBoolean(key, appRestrictions.getBoolean(key));
                        break;
                    case PREF_KEYPRESS_SOUND_VOLUME:
                    case PREF_KEYBOARD_HEIGHT:
                        Log.i(TAG, "Loading restriction: " + key + "=" + appRestrictions.getInt(key));
                        prefsEditor.putFloat(key, appRestrictions.getInt(key) / 100f);
                        break;
                    case PREF_KEY_LONGPRESS_TIMEOUT:
                    case PREF_BOTTOM_OFFSET_PORTRAIT:
                        Log.i(TAG, "Loading restriction: " + key + "=" + appRestrictions.getInt(key));
                        prefsEditor.putInt(key, appRestrictions.getInt(key));
                        break;
                    case PREF_KEYBOARD_COLOR:
                        Log.i(TAG, "Loading restriction: " + key + "=" + appRestrictions.getString(key));
                        String color = appRestrictions.getString(key);
                        // getString даёт null, если ограничение задано значением другого типа
                        // (или отсутствует): без этой проверки загрузка политик падала с NPE.
                        if (color != null && color.startsWith("#")) {
                            try {
                                color = "FF" + color.substring(1);
                                prefsEditor.putInt(key, Integer.parseUnsignedInt(color, 16));
                                break;
                            } catch (NumberFormatException ignored) {
                                // Значение не разбирается как цвет — падать на политике нельзя,
                                // поэтому ключ просто снимается ниже и остаётся дефолт.
                            }
                        }
                        prefsEditor.remove(key);
                        break;
                    case PREF_PERSONAL_DICTIONARY:
                        // ONE-DIRECTIONAL on purpose — see PersonalDictionaryRestriction. The
                        // generic boolean branch above would let a policy force the saving of typed
                        // words ON and lock the user out of turning it off.
                        final boolean personalPolicy = appRestrictions.getBoolean(key);
                        if (PersonalDictionaryRestriction.writesPreference(personalPolicy)) {
                            Log.i(TAG, "Loading restriction: " + key + "=" + personalPolicy);
                            prefsEditor.putBoolean(key,
                                    PersonalDictionaryRestriction.valueToWrite());
                        } else {
                            Log.i(TAG, "Ignoring permissive restriction: " + key);
                        }
                        break;
                    default:
                        Log.e(TAG, "Unhandled restriction: " + key);
                }
            }

            // A permissive personal-dictionary policy is dropped from the stored set, so the
            // settings row it would otherwise grey out stays live for the user.
            final Set<String> activeKeys = PersonalDictionaryRestriction.effectiveRestrictionKeys(
                    restrictionKeys,
                    restrictionKeys.contains(PREF_PERSONAL_DICTIONARY)
                            ? appRestrictions.getBoolean(PREF_PERSONAL_DICTIONARY) : null);
            prefsEditor.putStringSet(ACTIVE_RESTRICTIONS, activeKeys);
            prefsEditor.apply();
            return activeKeys;
        }
        return restrictionKeys;
    }

    public void loadSettings(final InputAttributes inputAttributes) {
        mSettingsValues = new SettingsValues(mPrefs, mRes, inputAttributes);
    }

    // TODO: Remove this method and add proxy method to SettingsValues.
    public SettingsValues getCurrent() {
        return mSettingsValues;
    }


    // Accessed from the settings interface, hence public
    public static boolean readKeypressSoundEnabled(final SharedPreferences prefs,
            final Resources res) {
        return prefs.getBoolean(PREF_SOUND_ON,
                res.getBoolean(R.bool.config_default_sound_enabled));
    }

    public static boolean readVibrationEnabled(final SharedPreferences prefs,
            final Resources res) {
        final boolean hasVibrator = AudioAndHapticFeedbackManager.getInstance().hasVibrator();
        return hasVibrator && prefs.getBoolean(PREF_VIBRATE_ON,
                res.getBoolean(R.bool.config_default_vibration_enabled));
    }

    public static boolean readKeyPreviewPopupEnabled(final SharedPreferences prefs,
            final Resources res) {
        final boolean defaultKeyPreviewPopup = res.getBoolean(
                R.bool.config_default_key_preview_popup);
        return prefs.getBoolean(PREF_POPUP_ON, defaultKeyPreviewPopup);
    }

    public static boolean readShowLanguageSwitchKey(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_SHOW_LANGUAGE_SWITCH_KEY, true);
    }

    public static boolean readUseOnScreenKeyboard(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_USE_ON_SCREEN, false);
    }

    public static boolean readEnableImeSwitch(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_ENABLE_IME_SWITCH, false);
    }

    public static boolean readShowSpecialChars(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_SHOW_SPECIAL_CHARS, true);
    }

    public static boolean readShowNumberRow(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_SHOW_NUMBER_ROW, false);
    }

    public static boolean readShowEmojiKey(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_SHOW_EMOJI_KEY, true);
    }

    public static boolean readSpaceSwipeEnabled(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_SPACE_SWIPE, true);
    }

    public static boolean readDeleteSwipeEnabled(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_DELETE_SWIPE, false);
    }

    public static boolean readTatarSuggestionsEnabled(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_TATAR_SUGGESTIONS, false);
    }

    /**
     * The personal dictionary is opt-in: default OFF, exactly like Tatar suggestions. This one
     * reader governs both showing personal words and saving them.
     */
    public static boolean readPersonalDictionaryEnabled(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_PERSONAL_DICTIONARY, false);
    }

    /**
     * Autocorrection is opt-in AND subordinate: it answers true only when Tatar suggestions are on
     * as well. Reading both here rather than at the call sites is what makes the subordination a
     * property of the setting instead of a rule every caller has to remember.
     */
    public static boolean readTatarAutocorrectEnabled(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_TATAR_AUTOCORRECT, false)
                && readTatarSuggestionsEnabled(prefs);
    }

    /**
     * Emoji suggestions default ON but stay subordinate, exactly like autocorrection: they answer
     * true only when Tatar suggestions are on as well (M4b — the opt-in default was never
     * discovered in the field; the master suggestions switch remains the real gate and is itself
     * opt-in). Reading both here rather than at the call sites is what makes the subordination a
     * property of the setting instead of a rule every caller has to remember.
     */
    public static boolean readEmojiSuggestionsEnabled(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_EMOJI_SUGGESTIONS, true)
                && readTatarSuggestionsEnabled(prefs);
    }

    /**
     * Turns Tatar suggestions on from outside the settings screen (the offer dialog). The live IME
     * picks the change up through its own preference listener, so the caller does nothing else.
     */
    public static void writeTatarSuggestionsEnabled(final SharedPreferences prefs,
            final boolean enabled) {
        prefs.edit().putBoolean(PREF_TATAR_SUGGESTIONS, enabled).apply();
    }

    public static boolean readTatarSuggestionsOfferSpent(final SharedPreferences prefs) {
        return prefs.getBoolean(PREF_TATAR_SUGGESTIONS_OFFER_SPENT, false);
    }

    /**
     * Spends the one-shot offer. Uses {@code commit()} rather than {@code apply()} on purpose: this
     * runs immediately before a modal dialog appears, and the whole point of the flag is that a
     * process death right after the dialog is shown must not bring the offer back. One boolean into
     * already-loaded device-protected preferences, once per installation, is worth the synchronous
     * write.
     */
    public static void writeTatarSuggestionsOfferSpent(final SharedPreferences prefs) {
        prefs.edit().putBoolean(PREF_TATAR_SUGGESTIONS_OFFER_SPENT, true).commit();
    }

    public static String readPrefSubtypes(final SharedPreferences prefs) {
        return prefs.getString(PREF_ENABLED_SUBTYPES, "");
    }

    public static void writePrefSubtypes(final SharedPreferences prefs, final String prefSubtypes) {
        prefs.edit().putString(PREF_ENABLED_SUBTYPES, prefSubtypes).apply();
    }

    public static String readPrefCurrentSubtype(final SharedPreferences prefs) {
        return prefs.getString(PREF_CURRENT_SUBTYPE, "");
    }

    public static void writePrefCurrentSubtype(final SharedPreferences prefs,
            final String prefSubtype) {
        prefs.edit().putString(PREF_CURRENT_SUBTYPE, prefSubtype).apply();
    }

    public static float readKeypressSoundVolume(final SharedPreferences prefs) {
        final float volume = prefs.getFloat(
                PREF_KEYPRESS_SOUND_VOLUME, UNDEFINED_PREFERENCE_VALUE_FLOAT);
        return (volume != UNDEFINED_PREFERENCE_VALUE_FLOAT) ? volume
                : readDefaultKeypressSoundVolume();
    }

    private static final float DEFAULT_KEYPRESS_SOUND_VOLUME = 0.5f;

    public static float readDefaultKeypressSoundVolume() {
        return DEFAULT_KEYPRESS_SOUND_VOLUME;
    }

    public static int readKeyLongpressTimeout(final SharedPreferences prefs,
            final Resources res) {
        final int milliseconds = prefs.getInt(
                PREF_KEY_LONGPRESS_TIMEOUT, UNDEFINED_PREFERENCE_VALUE_INT);
        return (milliseconds != UNDEFINED_PREFERENCE_VALUE_INT) ? milliseconds
                : readDefaultKeyLongpressTimeout(res);
    }

    public static int readDefaultKeyLongpressTimeout(final Resources res) {
        return res.getInteger(R.integer.config_default_longpress_key_timeout);
    }

    public static float readKeyboardHeight(final SharedPreferences prefs,
            final float defaultValue) {
        return prefs.getFloat(PREF_KEYBOARD_HEIGHT, defaultValue);
    }

    public static int readBottomOffsetPortrait(final SharedPreferences prefs) {
        return prefs.getInt(PREF_BOTTOM_OFFSET_PORTRAIT, DEFAULT_BOTTOM_OFFSET);
    }

    public static final int DEFAULT_BOTTOM_OFFSET = 0;

    public static int readKeyboardDefaultColor(final Context context) {
        final int[] keyboardThemeColors = context.getResources().getIntArray(R.array.keyboard_theme_colors);
        final int[] keyboardThemeIds = context.getResources().getIntArray(R.array.keyboard_theme_ids);
        final int themeId = getKeyboardTheme(context).mThemeId;
        for (int index = 0; index < keyboardThemeIds.length; index++) {
            if (themeId == keyboardThemeIds[index]) {
                return keyboardThemeColors[index];
            }
        }

        return Color.TRANSPARENT;
    }

    public static KeyboardTheme getKeyboardTheme(final Context context) {
        return KeyboardTheme.getKeyboardTheme(context);
    }

    public static int readKeyboardColor(final SharedPreferences prefs, final Context context) {
        return prefs.getInt(PREF_KEYBOARD_COLOR, readKeyboardDefaultColor(context));
    }

    public static void removeKeyboardColor(final SharedPreferences prefs) {
        prefs.edit().remove(PREF_KEYBOARD_COLOR).apply();
    }

    public static boolean readUseFullscreenMode(final Resources res) {
        return res.getBoolean(R.bool.config_use_fullscreen_mode);
    }

    public static boolean readHasHardwareKeyboard(final Configuration conf) {
        // The standard way of finding out whether we have a hardware keyboard. This code is taken
        // from InputMethodService#onEvaluateInputShown, which canonically determines this.
        // In a nutshell, we have a keyboard if the configuration says the type of hardware keyboard
        // is NOKEYS and if it's not hidden (e.g. folded inside the device).
        return conf.keyboard != Configuration.KEYBOARD_NOKEYS
                && conf.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES;
    }
}
