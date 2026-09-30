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

import rkr.simplekeyboard.inputmethod.keyboard.Keyboard
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable

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
            raw.add(KeyNeighborTable.RawKey(key.code, partnerCodePoints))
        }
        return KeyNeighborTable.build(subtypeId, true, raw)
    }
}
