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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WordDeleteFlick.shouldFire]: the word-delete flick needs a fast, predominantly horizontal,
 * leftward translation of at least [WordDeleteFlick.TRIGGER_KEY_WIDTHS] key widths. Everything
 * else stays the select-to-delete drag or the held-key auto-repeat.
 */
class WordDeleteFlickTest {

    private val keyWidth = 72

    @Test
    fun aCleanLeftFlickFires() {
        assertTrue(WordDeleteFlick.shouldFire(dxFromDown = -2 * keyWidth, dyFromDown = 10,
            elapsedMs = 120, keyWidthPx = keyWidth))
    }

    @Test
    fun theTriggerDistanceIsOneAndAHalfKeyWidths() {
        val trigger = (WordDeleteFlick.TRIGGER_KEY_WIDTHS * keyWidth).toInt()
        assertTrue(WordDeleteFlick.shouldFire(-trigger, 0, 100, keyWidth))
        assertFalse("just under the trigger stays a drag",
            WordDeleteFlick.shouldFire(-(trigger - 1), 0, 100, keyWidth))
    }

    @Test
    fun aSlowTranslationNeverFires() {
        assertFalse("crossing the distance after the flick window is a selection drag",
            WordDeleteFlick.shouldFire(-3 * keyWidth, 0,
                WordDeleteFlick.MAX_FLICK_MS + 1, keyWidth))
        assertTrue("the window edge still fires",
            WordDeleteFlick.shouldFire(-3 * keyWidth, 0, WordDeleteFlick.MAX_FLICK_MS, keyWidth))
    }

    @Test
    fun rightwardMovementNeverFires() {
        assertFalse(WordDeleteFlick.shouldFire(3 * keyWidth, 0, 100, keyWidth))
        assertFalse(WordDeleteFlick.shouldFire(0, 0, 100, keyWidth))
    }

    @Test
    fun theSwipeMustStayPredominantlyHorizontal() {
        assertFalse("a 45-degree diagonal is a vertical-ish move, not a flick",
            WordDeleteFlick.shouldFire(-keyWidth, dyFromDown = keyWidth, elapsedMs = 100,
                keyWidthPx = keyWidth))
        assertFalse("the same holds for a downward diagonal",
            WordDeleteFlick.shouldFire(-keyWidth, dyFromDown = -keyWidth, elapsedMs = 100,
                keyWidthPx = keyWidth))
        assertTrue("a shallow drift inside the cone still fires",
            WordDeleteFlick.shouldFire(-2 * keyWidth, dyFromDown = keyWidth / 2, elapsedMs = 100,
                keyWidthPx = keyWidth))
    }

    @Test
    fun aDegenerateKeyWidthNeverFires() {
        assertFalse(WordDeleteFlick.shouldFire(-1000, 0, 100, keyWidthPx = 0))
    }
}
