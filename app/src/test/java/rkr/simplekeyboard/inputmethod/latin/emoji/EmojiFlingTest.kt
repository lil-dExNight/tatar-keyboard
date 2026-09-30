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

package rkr.simplekeyboard.inputmethod.latin.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fling physics boundary, exercised on the plain JVM: the scroll clamp and the
 * fling-or-tap decision that the [EmojiPanelView] delegates to on release.
 */
class EmojiFlingTest {

    // --- clampScroll ---------------------------------------------------------------------------

    @Test
    fun clampScrollHoldsTheInclusiveRange() {
        assertEquals(0, EmojiFling.clampScroll(-5, 100))
        assertEquals(0, EmojiFling.clampScroll(0, 100))
        assertEquals(50, EmojiFling.clampScroll(50, 100))
        assertEquals(100, EmojiFling.clampScroll(100, 100))
        assertEquals(100, EmojiFling.clampScroll(150, 100))
    }

    @Test
    fun clampScrollReturnsZeroWhenThereIsNothingToScroll() {
        assertEquals(0, EmojiFling.clampScroll(50, 0))
        assertEquals(0, EmojiFling.clampScroll(50, -1))
        assertEquals(0, EmojiFling.clampScroll(-50, 0))
    }

    // --- shouldFling ---------------------------------------------------------------------------

    @Test
    fun aReleaseThatNeverScrolledIsNeverAFling() {
        assertFalse(EmojiFling.shouldFling(wasScrolling = false, velocityY = 5000f, minFlingVelocity = 50, maxScroll = 100))
    }

    @Test
    fun contentThatCannotMoveNeverFlings() {
        assertFalse(EmojiFling.shouldFling(wasScrolling = true, velocityY = 5000f, minFlingVelocity = 50, maxScroll = 0))
    }

    @Test
    fun aSlowReleaseBelowTheMinimumSettlesInPlace() {
        assertFalse(EmojiFling.shouldFling(wasScrolling = true, velocityY = 10f, minFlingVelocity = 50, maxScroll = 100))
    }

    @Test
    fun aFastReleaseInEitherDirectionFlings() {
        assertTrue(EmojiFling.shouldFling(wasScrolling = true, velocityY = 5000f, minFlingVelocity = 50, maxScroll = 100))
        assertTrue(EmojiFling.shouldFling(wasScrolling = true, velocityY = -5000f, minFlingVelocity = 50, maxScroll = 100))
    }

    @Test
    fun exactlyAtTheMinimumFlingVelocityFlings() {
        assertTrue(EmojiFling.shouldFling(wasScrolling = true, velocityY = 50f, minFlingVelocity = 50, maxScroll = 100))
        assertTrue(EmojiFling.shouldFling(wasScrolling = true, velocityY = -50f, minFlingVelocity = 50, maxScroll = 100))
    }

    // --- isTap ---------------------------------------------------------------------------------

    @Test
    fun aReleaseIsATapExactlyWhenItNeverScrolled() {
        assertTrue(EmojiFling.isTap(wasScrolling = false))
        assertFalse(EmojiFling.isTap(wasScrolling = true))
    }
}
