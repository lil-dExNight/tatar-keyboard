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

/**
 * The one-handed (compact) mode: the key grid scales to [WIDTH_SCALE] of the keyboard width and
 * docks to the chosen side; the strip the scale frees on the other side keeps the keyboard
 * background and answers no touch. The dock is pure geometry — the keyboard builder adds the
 * freed width to the side padding opposite the grid — so keys, hit boxes and the balloon clamp
 * band follow on their own, while the suggestion strip and the emoji panel sit outside the key
 * grid and keep the full width. Stored as the `pref_one_handed_side` int and applied on the
 * keyboard's next rebuild, like the keyboard-height preset.
 *
 * Pure value holder with no Android imports, so the math is unit-tested on the JVM.
 */
object OneHandedMode {
    const val SIDE_OFF = 0
    const val SIDE_LEFT = 1
    const val SIDE_RIGHT = 2

    /** The factory state; [SIDES] is the settings dialog's display order. */
    const val SIDE_DEFAULT = SIDE_OFF
    val SIDES: List<Int> = listOf(SIDE_OFF, SIDE_LEFT, SIDE_RIGHT)

    /**
     * The grid's share of the keyboard width: the scale of the compact keyboard-height preset,
     * so the two "compact" choices shrink the board by the same factor.
     */
    const val WIDTH_SCALE = 0.85f

    /** The fifth Tatar row's key pitch as a fraction of the grid: six keys at 16.667%p. */
    const val FIFTH_ROW_KEY_FRACTION = 0.16667f

    /**
     * Floor for the fifth-row key pitch under the scale: the pitch of the narrowest key the
     * Tatar layout ships at full width on the 720 px device-test target — the 8.711%p keys of
     * the third letter row. The dock must not squeeze the extra row's keys below a key size the
     * layout already asks thumbs to hit.
     */
    const val MIN_FIFTH_ROW_KEY_WIDTH_PX = 62.7f

    /** Guards against a damaged or foreign pref value; an unknown side must read as off. */
    @JvmStatic
    fun isValidSide(side: Int): Boolean = side in SIDE_OFF..SIDE_RIGHT

    /**
     * The scale the dock applies on a screen of [fullWidthPx]: [WIDTH_SCALE], clamped up so the
     * fifth-row key pitch never drops below [MIN_FIFTH_ROW_KEY_WIDTH_PX], and 1 (the grid is the
     * full width and the dock degenerates) when even that cannot hold. The keyboard's side
     * paddings and the horizontal gap cancel (2 × 0.870%p ≈ 1.739%p), so a key's pitch is its
     * fraction of the grid width.
     */
    @JvmStatic
    fun effectiveScale(side: Int, fullWidthPx: Int): Float {
        if (side != SIDE_LEFT && side != SIDE_RIGHT) return 1f
        if (fullWidthPx <= 0) return 1f
        val floorScale = MIN_FIFTH_ROW_KEY_WIDTH_PX / (FIFTH_ROW_KEY_FRACTION * fullWidthPx)
        return minOf(1f, maxOf(WIDTH_SCALE, floorScale))
    }

    /** The key grid's width in px; the freed strip is [fullWidthPx] minus it. */
    @JvmStatic
    fun gridWidthPx(fullWidthPx: Int, side: Int): Int =
            Math.round(fullWidthPx * effectiveScale(side, fullWidthPx))

    /**
     * The padding the dock adds on each side, left first: the freed strip joins the side padding
     * opposite the grid. Off (or an invalid side) adds nothing; the two hands mirror each other.
     */
    @JvmStatic
    fun dockPaddingsPx(fullWidthPx: Int, side: Int): IntArray {
        val extra = fullWidthPx - gridWidthPx(fullWidthPx, side)
        return when (side) {
            SIDE_LEFT -> intArrayOf(0, extra)
            SIDE_RIGHT -> intArrayOf(extra, 0)
            else -> intArrayOf(0, 0)
        }
    }
}
