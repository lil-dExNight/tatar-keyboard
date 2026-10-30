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

package rkr.simplekeyboard.inputmethod.accessibility

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.keyboard.Key
import rkr.simplekeyboard.inputmethod.keyboard.Keyboard
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardId
import rkr.simplekeyboard.inputmethod.latin.common.Constants
import rkr.simplekeyboard.inputmethod.latin.utils.InputTypeUtils
import rkr.simplekeyboard.inputmethod.latin.utils.LocaleResourceUtils

/**
 * Maps a key to its spoken (TalkBack) description, following the AOSP
 * KeyCodeDescriptionMapper pattern: functional keys resolve to
 * spoken_description_* resources by key code (shift additionally by the
 * keyboard element, enter by the editor's IME action with a custom action
 * label taking priority), the six Tatar letters resolve to explicit
 * spoken_letter_* resources by codepoint (TTS coverage for them is not
 * guaranteed), and everything else falls back to the key label, which the
 * TTS of the active locale reads on its own.
 */
object KeyDescriptionMapper {

    // Lowercase Tatar codepoints; the uppercase forms are derived via
    // Character.isUpperCase/toLowerCase because shifted layouts upcase the
    // key code itself, not just the label.
    private val TATAR_LETTERS = mapOf(
        0x04D9 to R.string.spoken_letter_04d9, // ә
        0x04E9 to R.string.spoken_letter_04e9, // ө
        0x04AF to R.string.spoken_letter_04af, // ү
        0x0497 to R.string.spoken_letter_0497, // җ
        0x04A3 to R.string.spoken_letter_04a3, // ң
        0x04BB to R.string.spoken_letter_04bb, // һ
    )

    fun getDescription(context: Context, keyboard: Keyboard, key: Key): String {
        val code = key.code

        if (isTypedCharacterCode(code) && obscuresPasswordKeys(context, keyboard)) {
            return context.getString(R.string.spoken_description_dot)
        }
        TATAR_LETTERS[code]?.let { return context.getString(it) }
        if (Character.isUpperCase(code)) {
            TATAR_LETTERS[Character.toLowerCase(code)]?.let {
                return context.getString(
                    R.string.spoken_description_upper_case, context.getString(it))
            }
        }

        val resId = when (code) {
            Constants.CODE_SHIFT -> when (keyboard.mId.mElementId) {
                KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED,
                KeyboardId.ELEMENT_ALPHABET_AUTOMATIC_SHIFTED,
                -> R.string.spoken_description_shift_shifted
                KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED ->
                    R.string.spoken_description_caps_lock
                KeyboardId.ELEMENT_SYMBOLS -> R.string.spoken_description_symbols_shift
                KeyboardId.ELEMENT_SYMBOLS_SHIFTED ->
                    R.string.spoken_description_symbols_shift_shifted
                else -> R.string.spoken_description_shift
            }
            Constants.CODE_DELETE -> R.string.spoken_description_delete
            Constants.CODE_TAB -> R.string.spoken_description_tab
            Constants.CODE_SPACE -> {
                // A blind user cannot glance at the spacebar hint, so the
                // description always names the current input language
                // ("Space, Татарча") — same source and same own-locale
                // rendering as the visual hint painted by MainKeyboardView.
                return context.getString(
                    R.string.spoken_description_space_language,
                    LocaleResourceUtils.getLanguageDisplayNameInLocale(
                        keyboard.mId.mSubtype.locale))
            }
            Constants.CODE_ENTER -> {
                // A custom action label (e.g. a web form's own label) wins.
                key.label?.takeIf { it.isNotEmpty() }?.let { return it }
                when (keyboard.mId.imeAction()) {
                    EditorInfo.IME_ACTION_SEARCH -> R.string.spoken_description_search
                    EditorInfo.IME_ACTION_GO -> R.string.spoken_description_action_go
                    EditorInfo.IME_ACTION_SEND -> R.string.spoken_description_action_send
                    EditorInfo.IME_ACTION_NEXT -> R.string.spoken_description_action_next
                    EditorInfo.IME_ACTION_PREVIOUS ->
                        R.string.spoken_description_action_previous
                    EditorInfo.IME_ACTION_DONE -> R.string.spoken_description_action_done
                    else -> R.string.spoken_description_return
                }
            }
            Constants.CODE_SHIFT_ENTER -> R.string.spoken_description_return
            Constants.CODE_SWITCH_ALPHA_SYMBOL -> when (keyboard.mId.mElementId) {
                KeyboardId.ELEMENT_SYMBOLS,
                KeyboardId.ELEMENT_SYMBOLS_SHIFTED,
                -> R.string.spoken_description_to_alpha
                else -> R.string.spoken_description_to_symbol
            }
            Constants.CODE_LANGUAGE_SWITCH -> R.string.spoken_description_language_switch
            Constants.CODE_EMOJI -> R.string.spoken_description_emoji
            Constants.CODE_SETTINGS -> R.string.spoken_description_settings
            Constants.CODE_OUTPUT_TEXT -> return key.outputText
                ?: context.getString(R.string.spoken_description_unknown)
            else -> return key.label?.takeIf { it.isNotEmpty() }
                ?: context.getString(R.string.spoken_description_unknown)
        }
        return context.getString(resId)
    }

    /**
     * Whether a key types visible text into the field: a letter, digit or symbol, or an output
     * text key. Functional keys (negative codes) and tab, space and enter reveal nothing.
     */
    fun isTypedCharacterCode(code: Int): Boolean = when (code) {
        Constants.CODE_OUTPUT_TEXT -> true
        Constants.CODE_TAB, Constants.CODE_SPACE, Constants.CODE_ENTER -> false
        else -> code > 0
    }

    /**
     * Whether a password key must be described as a dot. Android 7.x reads key descriptions
     * aloud through the speaker unless the user allowed spoken passwords or wears headphones,
     * the rule AOSP LatinIME follows there; Android 8 and later leave that decision to the
     * screen reader, so full descriptions are given.
     */
    fun shouldObscurePasswordKey(
        sdkInt: Int,
        hiddenPasswordField: Boolean,
        speakPasswords: Boolean,
        headphonesOn: Boolean,
    ): Boolean = sdkInt < Build.VERSION_CODES.O && hiddenPasswordField && !speakPasswords &&
        !headphonesOn

    @Suppress("DEPRECATION") // The setting and the headset queries are what Android 7.x offers.
    private fun obscuresPasswordKeys(context: Context, keyboard: Keyboard): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return false
        val inputType = keyboard.mId.mEditorInfo?.inputType ?: return false
        if (!InputTypeUtils.isPasswordInputType(inputType)) return false
        val speakPasswords = Settings.Secure.getInt(
            context.contentResolver, Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD, 0) != 0
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val headphonesOn = audio != null && (audio.isWiredHeadsetOn || audio.isBluetoothA2dpOn)
        return shouldObscurePasswordKey(Build.VERSION.SDK_INT, true, speakPasswords, headphonesOn)
    }
}
