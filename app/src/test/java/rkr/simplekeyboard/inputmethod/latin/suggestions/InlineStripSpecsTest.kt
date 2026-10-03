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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The inline-autofill spec math: one honestly sized spec per strip cell. */
class InlineStripSpecsTest {

    @Test
    fun anExactThirdGivesThreeEqualCells() {
        val cells = InlineStripSpecs.cellSizesPx(300, 44)
        assertEquals(InlineStripSpecs.CELL_COUNT, cells.size)
        for (cell in cells) {
            assertEquals(100, cell.widthPx)
            assertEquals(44, cell.heightPx)
        }
    }

    @Test
    fun theRoundingRemainderFollowsTheStripCellBoundaries() {
        // The same boundaries as SuggestionStripState.cellLeft/cellRight: 0, 133, 266, 400.
        val cells = InlineStripSpecs.cellSizesPx(400, 44)
        assertEquals(listOf(133, 133, 134), cells.map { it.widthPx })
        assertEquals(400, cells.sumOf { it.widthPx })
    }

    @Test
    fun theCellCountIsTheStrips() {
        assertEquals(SuggestionStripState.CELL_COUNT, InlineStripSpecs.CELL_COUNT)
        assertEquals(3, InlineStripSpecs.CELL_COUNT)
    }

    @Test
    fun aNonPositiveDimensionPromisesNothing() {
        assertTrue(InlineStripSpecs.cellSizesPx(0, 44).isEmpty())
        assertTrue(InlineStripSpecs.cellSizesPx(300, 0).isEmpty())
        assertTrue(InlineStripSpecs.cellSizesPx(-5, 44).isEmpty())
    }

    @Test
    fun theMeasuredStripWidthWinsOverTheDisplayFallback() {
        assertEquals(720, InlineStripSpecs.effectiveStripWidthPx(720, 1080))
        assertEquals(1080, InlineStripSpecs.effectiveStripWidthPx(0, 1080))
        assertEquals(0, InlineStripSpecs.effectiveStripWidthPx(0, 0))
    }
}
