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

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionStripStateTest {
    private val width = 300
    private val height = 40
    private val activePointer = 7

    @Test
    fun emptyResultsKeepAllThreeCellsInert() {
        val state = SuggestionStripState()

        assertFalse(state.onDown(activePointer, 0f, 20f, width, height))
        assertFalse(state.onDown(activePointer, 100f, 20f, width, height))
        assertFalse(state.onDown(activePointer, 299f, 20f, width, height))
        assertEquals(SuggestionStripState.NO_CELL, state.pressedCell())
        repeat(SuggestionStripState.CELL_COUNT) { assertNull(state.suggestionAt(it)) }
    }

    @Test
    fun populatedCellsUseStableLeftCenterRightIdsAndEmptyCellsStayInert() {
        val state = SuggestionStripState()
        state.setSuggestions("бер", null, "өч")

        assertEquals("бер", state.suggestionAt(0))
        assertNull(state.suggestionAt(1))
        assertEquals("өч", state.suggestionAt(2))
        assertTrue(state.onDown(activePointer, 0f, 20f, 302, height))
        assertEquals(0, state.onUp(activePointer, 99f, 20f, 302, height))
        assertFalse(state.onDown(activePointer, 151f, 20f, 302, height))
        assertTrue(state.onDown(activePointer, 301f, 20f, 302, height))
        assertEquals(2, state.onUp(activePointer, 301f, 20f, 302, height))
    }

    @Test
    fun nonDivisibleWidthsHaveContiguousFullCoverage() {
        val state = SuggestionStripState()
        for (width in 3..503) {
            assertEquals(0, state.cellLeft(0, width))
            assertEquals(width, state.cellRight(2, width))
            assertEquals(state.cellRight(0, width), state.cellLeft(1, width))
            assertEquals(state.cellRight(1, width), state.cellLeft(2, width))
            for (x in 0 until width) {
                val cell = state.cellAt(x.toFloat(), 20f, width, height)
                assertTrue(cell in 0 until SuggestionStripState.CELL_COUNT)
                assertTrue(x >= state.cellLeft(cell, width))
                assertTrue(x < state.cellRight(cell, width))
            }
            assertEquals(SuggestionStripState.NO_CELL, state.cellAt(-1f, 20f, width, height))
            assertEquals(
                SuggestionStripState.NO_CELL,
                state.cellAt(width.toFloat(), 20f, width, height),
            )
        }
    }

    @Test
    fun dragOutsideOriginalCellCancelsTapButReentryRestoresIt() {
        val state = SuggestionStripState()
        state.setSuggestions("бер", "ике", "өч")

        assertTrue(state.onDown(activePointer, 50f, 20f, width, height))
        assertTrue(state.onMove(activePointer, 150f, 20f, width, height))
        assertEquals(SuggestionStripState.NO_CELL, state.pressedCell())
        assertEquals(
            SuggestionStripState.NO_CELL,
            state.onUp(activePointer, 50f, 20f, width, height),
        )

        assertTrue(state.onDown(activePointer, 50f, 20f, width, height))
        assertTrue(state.onMove(activePointer, 150f, 20f, width, height))
        assertTrue(state.onMove(activePointer, 80f, 20f, width, height))
        assertEquals(0, state.pressedCell())
        assertEquals(0, state.onUp(activePointer, 80f, 20f, width, height))
    }

    @Test
    fun verticalExitCancelsReleaseAndVerticalReentryRestoresOriginalCell() {
        val state = SuggestionStripState()
        state.setSuggestions("бер", "ике", "өч")

        assertTrue(state.onDown(activePointer, 50f, 20f, width, height))
        assertTrue(state.onMove(activePointer, 50f, -1f, width, height))
        assertEquals(SuggestionStripState.NO_CELL, state.pressedCell())
        assertEquals(
            SuggestionStripState.NO_CELL,
            state.onUp(activePointer, 50f, -1f, width, height),
        )

        assertTrue(state.onDown(activePointer, 50f, 20f, width, height))
        assertTrue(state.onMove(activePointer, 50f, height.toFloat(), width, height))
        assertEquals(SuggestionStripState.NO_CELL, state.pressedCell())
        assertTrue(state.onMove(activePointer, 50f, 20f, width, height))
        assertEquals(0, state.pressedCell())
        assertEquals(0, state.onUp(activePointer, 50f, 20f, width, height))
    }

    @Test
    fun pointerUpOnlyCancelsWhenItBelongsToTheActivePointer() {
        val state = SuggestionStripState()
        val otherPointer = 19
        state.setSuggestions("бер", "ике", "өч")

        assertTrue(state.onDown(activePointer, 150f, 20f, width, height))
        assertFalse(state.onPointerUp(otherPointer))
        assertEquals(activePointer, state.activePointerId())
        assertEquals(1, state.pressedCell())
        assertEquals(1, state.onUp(activePointer, 150f, 20f, width, height))

        assertTrue(state.onDown(activePointer, 150f, 20f, width, height))
        assertTrue(state.onPointerUp(activePointer))
        assertEquals(SuggestionStripState.INVALID_POINTER_ID, state.activePointerId())
        assertEquals(SuggestionStripState.NO_CELL, state.pressedCell())
        assertEquals(
            SuggestionStripState.NO_CELL,
            state.onUp(otherPointer, 150f, 20f, width, height),
        )
    }

    @Test
    fun clearImmediatelyDropsContentsAndCancelsGesture() {
        val state = SuggestionStripState()
        state.setSuggestions("бер", "ике", "өч")
        assertTrue(state.onDown(activePointer, 150f, 20f, width, height))

        assertTrue(state.clear())

        assertEquals(SuggestionStripState.NO_CELL, state.pressedCell())
        assertEquals(
            SuggestionStripState.NO_CELL,
            state.onUp(activePointer, 150f, 20f, width, height),
        )
        repeat(SuggestionStripState.CELL_COUNT) { assertNull(state.suggestionAt(it)) }
    }

    @Test
    fun identicalPublicationDoesNotReportVisualChange() {
        val state = SuggestionStripState()
        assertTrue(state.setSuggestions("бер", "ике", null))
        assertFalse(state.setSuggestions("бер", "ике", null))
        assertTrue(state.onDown(activePointer, 50f, 20f, width, height))
        assertTrue(state.setSuggestions("бер", "ике", null))
        assertEquals(SuggestionStripState.NO_CELL, state.pressedCell())
        assertTrue(state.setSuggestions("бер", "ике", "өч"))
    }

    @Test
    fun hotHitAndGestureStateMachineAllocatesZeroBytesAfterWarmup() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true
        val state = SuggestionStripState()
        state.setSuggestions("бер", "ике", "өч")
        repeat(100_000) {
            state.onDown(activePointer, 150f, 20f, 360, height)
            state.onMove(activePointer, 151f, 20f, 360, height)
            state.onUp(activePointer, 151f, 20f, 360, height)
        }

        val threadId = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(100_000) {
            state.onDown(activePointer, 150f, 20f, 360, height)
            state.onMove(activePointer, 151f, 20f, 360, height)
            state.onUp(activePointer, 151f, 20f, 360, height)
        }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before

        assertEquals(0L, allocated)
    }

    @Test
    fun hasAnySuggestionTracksTheEmptyBandTransition() {
        val state = SuggestionStripState()
        assertFalse(state.hasAnySuggestion())

        state.setSuggestions("бер", null, null)
        assertTrue(state.hasAnySuggestion())

        // Empty strings are normalized to absent cells, so they keep the band empty.
        state.setSuggestions("", "", "")
        assertFalse(state.hasAnySuggestion())

        state.setSuggestions(null, null, "өч")
        assertTrue(state.hasAnySuggestion())

        state.clear()
        assertFalse(state.hasAnySuggestion())
    }

    @Test
    fun stripHeightContractIsExactlyFortyDp() {
        assertEquals(40, SuggestionStripState.STRIP_HEIGHT_DP)
        assertEquals(3, SuggestionStripState.CELL_COUNT)
    }

    @Test
    fun emphasisOnlySticksToPopulatedCellsAndReportsChange() {
        val state = SuggestionStripState()
        state.setSuggestions("китәп", "китап", null)

        assertEquals(SuggestionStripState.NO_CELL, state.emphasizedCell())
        assertFalse(state.isEmphasized(1))

        // A populated cell accepts the marker; setting it twice reports no new change.
        assertTrue(state.setEmphasis(1))
        assertTrue(state.isEmphasized(1))
        assertEquals(1, state.emphasizedCell())
        assertFalse(state.setEmphasis(1))

        // An empty cell refuses: the marker stays where it was. So does an out-of-range index.
        assertFalse(state.setEmphasis(2))
        assertEquals(1, state.emphasizedCell())
        assertFalse(state.setEmphasis(7))
        assertEquals(1, state.emphasizedCell())
        // NO_CELL is the explicit clear.
        assertTrue(state.setEmphasis(SuggestionStripState.NO_CELL))
        assertEquals(SuggestionStripState.NO_CELL, state.emphasizedCell())
        assertFalse(state.isEmphasized(1))
    }

    @Test
    fun everyPublicationResetsTheEmphasisMarker() {
        val state = SuggestionStripState()
        state.setSuggestions("китәп", "китап", null)
        assertTrue(state.setEmphasis(1))

        // A fresh publication describes the whole band, marker included — even one whose words
        // happen to be identical, so the reset itself counts as the visual change.
        assertTrue(state.setSuggestions("китәп", "китап", null))
        assertEquals(SuggestionStripState.NO_CELL, state.emphasizedCell())

        // And once nothing is marked, an identical republish is again a no-op.
        assertFalse(state.setSuggestions("китәп", "китап", null))

        assertTrue(state.setEmphasis(0))
        assertTrue(state.clear())
        assertEquals(SuggestionStripState.NO_CELL, state.emphasizedCell())
    }

    @Test
    fun emphasisChangesAllocateZeroBytesAfterWarmup() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true
        val state = SuggestionStripState()
        state.setSuggestions("китәп", "китап", null)
        repeat(100_000) {
            state.setEmphasis(1)
            state.setEmphasis(SuggestionStripState.NO_CELL)
        }

        val threadId = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(100_000) {
            state.setEmphasis(1)
            state.setEmphasis(SuggestionStripState.NO_CELL)
        }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before

        assertEquals(0L, allocated)
    }
}
