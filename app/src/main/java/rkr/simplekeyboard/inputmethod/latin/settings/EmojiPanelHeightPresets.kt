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

import kotlin.math.abs

/**
 * The "Emoji panel height" Appearance row (docs/EMOJI-PANEL-SPACE-2026-09-28.md, item B), modelled
 * on [KeyboardHeightPresets]: three named presets stored as one float scale of the keyboard box in
 * `pref_emoji_panel_height`.
 *
 * `same` is exactly the pre-setting behavior — the panel's box IS the keyboard's box, strip bonus
 * included, and no ceiling is consulted, so the same-box invariant holds bit for bit. `larger`
 * scales the box by 1.2 and `max` takes it to the platform ceiling — the 46%p-of-screen
 * `config_max_keyboard_height` fraction, passed in by the caller because this holder is pure.
 * [MAX_SCALE] is deliberately not a measured factor: it is any scale far past the ceiling, and the
 * clamp in [applyTo], not the number, is what "max" means. A managed restriction writes an integer
 * percent through the same float (100 = same, 120 = larger, 10000 = max); percents above 100 apply
 * verbatim, scaled and clamped, while percents at or below 100 floor at `same` — the panel is never
 * smaller than the keyboard box.
 *
 * Pure value holder with no Android imports: the mapping is unit-tested on the JVM.
 */
object EmojiPanelHeightPresets {
    const val SAME_SCALE = 1.0f
    const val LARGER_SCALE = 1.2f

    /** Far past any real ceiling: the clamp, not the number, is the setting. */
    const val MAX_SCALE = 100f

    /** Display order of the dialog choices; the settings row maps them to labels by index. */
    val SCALES: List<Float> = listOf(SAME_SCALE, LARGER_SCALE, MAX_SCALE)

    /** Index of the factory state in [SCALES] — must sit on [SAME_SCALE]. */
    const val DEFAULT_INDEX = 0

    /** The preset a stored scale falls on, or -1 for a value no preset owns (a restriction's percent). */
    fun indexForScale(scale: Float): Int {
        for (index in SCALES.indices) {
            if (abs(SCALES[index] - scale) < EPSILON) {
                return index
            }
        }
        return -1
    }

    /**
     * The panel box height for [scale] applied to [panelHeightPx] (keyboard height plus the
     * suggestion-strip bonus, already final). `same` returns it untouched — never clamped — so the
     * default path is exactly the old invariant; anything above scales and is capped at
     * [maxHeightPx]. A non-positive cap means "no ceiling known" and only the scale applies.
     */
    @JvmStatic
    fun applyTo(panelHeightPx: Int, maxHeightPx: Int, scale: Float): Int = when {
        scale <= SAME_SCALE -> panelHeightPx
        maxHeightPx <= 0 -> (panelHeightPx * scale).toInt()
        else -> minOf((panelHeightPx * scale).toInt(), maxHeightPx)
    }

    /** Wider than float-identity to absorb the percent arithmetics a restriction can write. */
    private const val EPSILON = 0.001f
}
