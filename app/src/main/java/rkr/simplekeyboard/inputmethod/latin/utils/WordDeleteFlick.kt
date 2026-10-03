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

package rkr.simplekeyboard.inputmethod.latin.utils

/**
 * The pure trigger decision for the word-delete flick: a fast leftward swipe that starts on the
 * delete key deletes the whole last word, while a slow drag stays the precise select-to-delete
 * swipe. Kept free of Android types so the decision runs in plain JVM tests.
 *
 * Disambiguation: the long-press auto-repeat fires on held time without lateral movement (its
 * first shot comes at the key-repeat start timeout, and any movement cancels the repeat timers);
 * the flick fires on sufficient lateral translation reached quickly. A drag that crosses the
 * distance slowly never fires: the user is selecting characters to delete, not flicking.
 */
object WordDeleteFlick {

    /**
     * Leftward translation that triggers the flick, as a fraction of the keyboard's most common
     * key width. Deliberately well past one key: the select-to-delete swipe feeds on smaller
     * translations, so the trigger must not sit inside a careful drag's first characters. On the
     * reference device this is about a fifth of the keyboard width.
     */
    const val TRIGGER_KEY_WIDTHS = 1.5f

    /**
     * How quickly the trigger distance must be reached, counting from the touch down. Below the
     * key-repeat start timeout, so a delete held long enough to be already repeating no longer
     * qualifies as a flick.
     */
    const val MAX_FLICK_MS = 300L

    /**
     * Slope discipline: the swipe must stay predominantly horizontal. |dy| * SLOPE_DIVISOR <= |dx|
     * keeps the direction inside roughly a 26-degree cone around the horizontal axis.
     */
    private const val SLOPE_DIVISOR = 2

    /**
     * True when the gesture is a word-delete flick: leftward by at least
     * [TRIGGER_KEY_WIDTHS] key widths, predominantly horizontal, within [MAX_FLICK_MS] of the
     * touch down. Rightward, vertical or slow translations never fire.
     */
    @JvmStatic
    fun shouldFire(dxFromDown: Int, dyFromDown: Int, elapsedMs: Long, keyWidthPx: Int): Boolean {
        if (keyWidthPx <= 0) return false
        if (elapsedMs > MAX_FLICK_MS) return false
        val triggerPx = TRIGGER_KEY_WIDTHS * keyWidthPx
        if (dxFromDown > -triggerPx) return false
        return kotlin.math.abs(dyFromDown) * SLOPE_DIVISOR <= -dxFromDown
    }
}
