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

/**
 * U6 of Phase 5 (docs/ROADMAP-P5.md): the "Keyboard height" row offers three named presets
 * instead of the inherited twenty-one-step seek bar. The preset is still stored as the same
 * `pref_keyboard_height` float the seek bar wrote, so the whole downstream pipeline
 * (SettingsValues.mKeyboardHeightScale → ResourceUtils.getKeyboardHeight →
 * KeyboardLayoutSet geometry → KeyboardBuilder row fractions) is untouched: every key height,
 * hit box, popup preview and the emoji panel's height derive from the occupied keyboard
 * height and scale on their own.
 *
 * A float written by the old seek bar (say 1.30) keeps applying — nothing the user had is
 * re-interpreted. The row shows such a value as a plain percent until a preset is picked,
 * from which moment the stored float is one of these exact constants again.
 *
 * Pure value holder with no Android imports: the mapping is unit-tested on the JVM.
 */
object KeyboardHeightPresets {
    const val COMPACT_SCALE = 0.85f
    const val DEFAULT_SCALE = 1.0f
    const val TALL_SCALE = 1.15f

    /** Display order of the dialog choices; the settings row maps them to labels by index. */
    val SCALES: List<Float> = listOf(COMPACT_SCALE, DEFAULT_SCALE, TALL_SCALE)

    /** Index of the factory state in [SCALES] — must sit on [DEFAULT_SCALE]. */
    const val DEFAULT_INDEX = 1

    /** The preset a stored scale falls on, or -1 for a value no preset owns (a seek-bar era one). */
    fun indexForScale(scale: Float): Int {
        for (index in SCALES.indices) {
            if (kotlin.math.abs(SCALES[index] - scale) < EPSILON) {
                return index
            }
        }
        return -1
    }

    /** Wider than float-identity to absorb the 0.05-step granularity the seek bar could write. */
    private const val EPSILON = 0.001f
}
