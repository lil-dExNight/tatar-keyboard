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

package rkr.simplekeyboard.inputmethod.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceTrackpadStateTest {
    private val state = SpaceTrackpadState()

    @Test
    fun longPressStartsHorizontalCursorMovement() {
        assertTrue(SpaceTrackpadState.canStart(
            true, false, true))
        assertTrue(state.onLongPressed(100, true))
        assertTrue(state.isActive())
        assertFalse(state.isSelecting())

        assertEquals(2, state.onMove(125, 10))
        assertEquals(0, state.onMove(129, 10))
        assertEquals(-1, state.onMove(109, 10))
        assertFalse(state.onLongPressed(109, true))
    }

    @Test
    fun secondPointerOwnsSelectionUntilItLifts() {
        state.onLongPressed(100, true)

        assertTrue(state.onSecondaryPointerDown(7))
        assertTrue(state.isSelecting())
        assertTrue(state.onSecondaryPointerDown(8))
        assertFalse("a third pointer cannot steal the anchor",
            state.onSecondaryPointerUp(8))
        assertTrue(state.isSelecting())

        assertTrue(state.onSecondaryPointerUp(7))
        assertFalse(state.isSelecting())
        assertTrue("the first pointer still moves the cursor", state.isActive())
        assertEquals(1, state.onMove(110, 10))
    }

    @Test
    fun cancelClearsBothPointerRolesAndAllowsANewGesture() {
        state.onLongPressed(100, true)
        state.onSecondaryPointerDown(7)

        state.finish()

        assertFalse(state.isActive())
        assertFalse(state.isSelecting())
        assertEquals(0, state.onMove(150, 10))
        assertFalse(state.onSecondaryPointerDown(8))
        assertFalse(state.onSecondaryPointerUp(7))
        assertTrue(state.onLongPressed(150, true))
    }

    @Test
    fun unknownCursorRejectsTrackpadButLeavesQuickSlideIndependent() {
        assertFalse(SpaceTrackpadState.canStart(
            false, false, true))
        assertFalse(state.onLongPressed(100, false))
        assertFalse(state.isActive())
        assertEquals("idle state never consumes a quick-slide move", 0, state.onMove(140, 10))
    }

    @Test
    fun passwordFieldRejectsTrackpadAndSelection() {
        assertFalse(SpaceTrackpadState.canStart(
            true, true, true))
        assertFalse(state.onLongPressed(100, false))
        assertFalse(state.onSecondaryPointerDown(7))
        assertFalse(state.isActive())
    }

    @Test
    fun disabledSpaceSwipeKeepsLongPressOutOfTrackpad() {
        assertFalse(SpaceTrackpadState.canStart(
            true, false, false))
    }
}
