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

/** Pure, fixed-size state and hit geometry for the three-cell Canvas strip. */
internal class SuggestionStripState {
    private val suggestions = arrayOfNulls<String>(CELL_COUNT)
    private var downCell = NO_CELL
    private var activePointerId = INVALID_POINTER_ID
    private var pointerInsideDownCell = false
    // P2 of Phase 3 (docs/ROADMAP-P3.md): the autocorrect preview's emphasized cell — the
    // correction the next separator would insert. [NO_CELL] on every ordinary band.
    private var emphasizedCell = NO_CELL

    fun setSuggestions(first: String?, second: String?, third: String?): Boolean {
        var changed = setSuggestion(0, first)
        changed = setSuggestion(1, second) || changed
        changed = setSuggestion(2, third) || changed
        // A fresh publication describes the whole band, emphasis included: it is re-applied
        // right after this, by the same owner call that publishes the words.
        changed = clearEmphasis() || changed
        return cancelGesture() || changed
    }

    fun clear(): Boolean = setSuggestions(null, null, null)

    fun suggestionAt(cell: Int): String? =
        if (cell in 0 until CELL_COUNT) suggestions[cell] else null

    fun isCellPopulated(cell: Int): Boolean = suggestionAt(cell) != null

    /** The emphasized cell, or [NO_CELL] when the band holds no correction to announce. */
    fun emphasizedCell(): Int = emphasizedCell

    fun isEmphasized(cell: Int): Boolean = cell == emphasizedCell && cell != NO_CELL

    /**
     * Marks [cell] emphasized (the preview's correction cell); [NO_CELL] clears. An empty or
     * out-of-range cell is refused outright — emphasis on nothing would underline a blank.
     * Returns true on a visual change.
     */
    fun setEmphasis(cell: Int): Boolean {
        if (cell == NO_CELL) return clearEmphasis()
        if (cell !in 0 until CELL_COUNT || !isCellPopulated(cell)) return false
        if (emphasizedCell == cell) return false
        emphasizedCell = cell
        return true
    }

    private fun clearEmphasis(): Boolean {
        if (emphasizedCell == NO_CELL) return false
        emphasizedCell = NO_CELL
        return true
    }

    /** True while at least one cell holds a word, i.e. the strip is not an empty band. */
    fun hasAnySuggestion(): Boolean {
        var cell = 0
        while (cell < CELL_COUNT) {
            if (suggestions[cell] != null) return true
            cell++
        }
        return false
    }

    fun cellAt(x: Float, y: Float, width: Int, height: Int): Int {
        if (x < 0f || x >= width.toFloat() || y < 0f || y >= height.toFloat()
            || width <= 0 || height <= 0
        ) {
            return NO_CELL
        }
        if (x < cellRight(0, width)) return 0
        if (x < cellRight(1, width)) return 1
        return 2
    }

    fun cellLeft(cell: Int, width: Int): Int = width * cell / CELL_COUNT

    fun cellRight(cell: Int, width: Int): Int = width * (cell + 1) / CELL_COUNT

    fun onDown(pointerId: Int, x: Float, y: Float, width: Int, height: Int): Boolean {
        val cell = cellAt(x, y, width, height)
        if (!isCellPopulated(cell)) {
            cancelGesture()
            return false
        }
        downCell = cell
        activePointerId = pointerId
        pointerInsideDownCell = true
        return true
    }

    fun onMove(pointerId: Int, x: Float, y: Float, width: Int, height: Int): Boolean {
        if (downCell == NO_CELL || pointerId != activePointerId) return false
        pointerInsideDownCell = cellAt(x, y, width, height) == downCell
        return true
    }

    /** Returns the clicked stable cell id or [NO_CELL], and always ends the gesture. */
    fun onUp(pointerId: Int, x: Float, y: Float, width: Int, height: Int): Int {
        if (downCell == NO_CELL || pointerId != activePointerId) {
            cancelGesture()
            return NO_CELL
        }
        val clicked = if (
            pointerInsideDownCell && cellAt(x, y, width, height) == downCell
        ) {
            downCell
        } else {
            NO_CELL
        }
        cancelGesture()
        return clicked
    }

    /** Ends the gesture only when Android reports that its active pointer went up. */
    fun onPointerUp(pointerId: Int): Boolean =
        pointerId == activePointerId && cancelGesture()

    fun cancelGesture(): Boolean {
        val changed = downCell != NO_CELL
            || activePointerId != INVALID_POINTER_ID
            || pointerInsideDownCell
        downCell = NO_CELL
        activePointerId = INVALID_POINTER_ID
        pointerInsideDownCell = false
        return changed
    }

    fun pressedCell(): Int = if (pointerInsideDownCell) downCell else NO_CELL

    fun hasActiveGesture(): Boolean = downCell != NO_CELL

    fun activePointerId(): Int = activePointerId

    private fun setSuggestion(cell: Int, value: String?): Boolean {
        val normalized = value?.takeUnless(String::isEmpty)
        if (suggestions[cell] == normalized) return false
        suggestions[cell] = normalized
        return true
    }

    companion object {
        const val CELL_COUNT = 3
        const val NO_CELL = -1
        const val INVALID_POINTER_ID = -1
        const val STRIP_HEIGHT_DP = 40
    }
}
