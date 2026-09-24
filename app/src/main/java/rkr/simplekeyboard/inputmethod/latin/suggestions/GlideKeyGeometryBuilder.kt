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
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry

/**
 * Adapts a live [Keyboard] into a [GlideKeyGeometry]. Mirrors [KeyNeighborTableBuilder]: this is
 * the single place layout data crosses the package boundary into the glide decoder — the
 * geometry always comes from the built keyboard, no letter and no coordinate is hard-coded, and
 * the crossing carries numbers only.
 *
 * Only the alphabet element is a valid source (`KeyboardId.isAlphabetKeyboard()`); anything else
 * (symbols, shifted element) builds an empty geometry, which the decoder treats fail-closed.
 * P7-1 note: nothing calls this yet — P7-2/P7-3 wire it to the live keyboard.
 */
object GlideKeyGeometryBuilder {
    @JvmStatic
    fun fromKeyboard(keyboard: Keyboard): GlideKeyGeometry {
        if (!keyboard.mId.isAlphabetKeyboard()) {
            return GlideKeyGeometry.build(emptyList())
        }
        val raw = ArrayList<GlideKeyGeometry.RawKey>(keyboard.sortedKeys.size)
        for (key in keyboard.sortedKeys) {
            raw.add(
                GlideKeyGeometry.RawKey(key.code, key.x, key.y, key.x + key.width, key.y + key.height),
            )
        }
        return GlideKeyGeometry.build(raw)
    }
}
