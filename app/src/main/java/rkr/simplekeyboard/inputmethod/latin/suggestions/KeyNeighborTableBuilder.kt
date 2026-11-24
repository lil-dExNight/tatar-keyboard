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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import android.content.Context
import android.view.ContextThemeWrapper
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
import rkr.simplekeyboard.inputmethod.keyboard.Keyboard
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardId
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardLayoutSet
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardTheme
import rkr.simplekeyboard.inputmethod.latin.Subtype
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils
import rkr.simplekeyboard.inputmethod.latin.utils.SubtypeLocaleUtils

/**
 * Adapts a live [Keyboard] into a [KeyNeighborTable]. This is the single place the layout data
 * crosses the package boundary into the dictionary engine.
 *
 * It reads the code and long-press partners of each key straight off the built keyboard —
 * no letter and no key pair is hard-coded. Only the alphabet element is a valid source
 * (`KeyboardId.isAlphabetKeyboard()`); the pure builder returns an empty table for anything else.
 * Nothing here logs a key code: this crossing carries numbers only.
 */
object KeyNeighborTableBuilder {
    @JvmStatic
    fun fromKeyboard(keyboard: Keyboard, subtypeId: String): KeyNeighborTable {
        val isAlphabet = keyboard.mId.isAlphabetKeyboard()
        if (!isAlphabet) {
            return KeyNeighborTable.build(subtypeId, false, emptyList())
        }
        val raw = ArrayList<KeyNeighborTable.RawKey>()
        for (key in keyboard.sortedKeys) {
            val moreKeys = key.moreKeys
            val partnerCodePoints =
                if (moreKeys == null) IntArray(0) else IntArray(moreKeys.size) { moreKeys[it].mCode }
            // The rectangle feeds the geometric adjacency of the DL-1 neighbor bonus, the same
            // crossing GlideKeyGeometryBuilder makes for the glide decoder.
            raw.add(
                KeyNeighborTable.RawKey(
                    key.code, partnerCodePoints,
                    key.x, key.y, key.x + key.width, key.y + key.height,
                ),
            )
        }
        return KeyNeighborTable.build(subtypeId, true, raw)
    }

    /**
     * Builds the table from the shipped Tatar alphabet layout without creating a keyboard view.
     * KeyboardLayoutSet serializes its process cache, so this is safe off the main thread.
     */
    @JvmStatic
    fun fromTatarLayout(context: Context): KeyNeighborTable {
        val themedContext = ContextThemeWrapper(
            PreferenceManagerCompat.getDeviceContext(context),
            R.style.KeyboardTheme_Tatar,
        )
        val resources = themedContext.resources
        val subtype = Subtype(
            PersonalSubtypes.TATAR_RU,
            SubtypeLocaleUtils.LAYOUT_TATAR,
            0,
            false,
            resources,
        )
        val metrics = resources.displayMetrics
        val layoutSet = KeyboardLayoutSet.Builder(themedContext, null)
            .setKeyboardTheme(KeyboardTheme.THEME_ID_TATAR)
            .setKeyboardGeometry(
                metrics.widthPixels,
                ResourceUtils.getDefaultKeyboardHeight(resources),
                0,
            )
            .setSubtype(subtype)
            .setLanguageSwitchKeyEnabled(false)
            .setShowSpecialChars(true)
            .setShowNumberRow(false)
            .setShowEmojiKey(false)
            .build()
        return fromKeyboard(layoutSet.getKeyboard(KeyboardId.ELEMENT_ALPHABET),
            PersonalSubtypes.TATAR_RU)
    }
}
