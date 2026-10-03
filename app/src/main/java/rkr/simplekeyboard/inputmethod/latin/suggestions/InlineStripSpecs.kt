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

/**
 * The pure half of inline autofill: the strip geometry promised to the platform and the
 * field-level gate. No framework types, so plain JVM tests pin the math; the API-30 glue that
 * turns these numbers into platform objects is [InlineAutofillBinder].
 */
object InlineStripSpecs {
    /** One spec per strip cell; inline content replaces the strip's words cell for cell. */
    const val CELL_COUNT = SuggestionStripState.CELL_COUNT

    /**
     * One cell's promised size. The strip's grid is fixed, so the minimum and the maximum are the
     * same size and the class carries a single width and height.
     */
    class CellSizePx(val widthPx: Int, val heightPx: Int)

    /**
     * The strip width to promise: the measured strip when it has been laid out, else the display
     * width. The request can arrive before the input view exists (a cold start straight into an
     * autofill field), and the strip always spans the full window, which is the display's width.
     */
    fun effectiveStripWidthPx(measuredStripWidthPx: Int, displayWidthPx: Int): Int =
        if (measuredStripWidthPx > 0) measuredStripWidthPx else displayWidthPx

    /**
     * The cells' exact sizes in strip order, empty when either dimension is not positive. The
     * integer division mirrors [SuggestionStripState.cellLeft]/[SuggestionStripState.cellRight], so
     * the promised spec is the size the host lays the cell out at; the rounding remainder lands in
     * the last cell either way.
     */
    fun cellSizesPx(stripWidthPx: Int, stripHeightPx: Int): List<CellSizePx> {
        if (stripWidthPx <= 0 || stripHeightPx <= 0) return emptyList()
        val cells = ArrayList<CellSizePx>(CELL_COUNT)
        var cell = 0
        while (cell < CELL_COUNT) {
            val left = stripWidthPx * cell / CELL_COUNT
            val right = stripWidthPx * (cell + 1) / CELL_COUNT
            cells.add(CellSizePx(right - left, stripHeightPx))
            cell++
        }
        return cells
    }
}

/**
 * The field-level gate of the inline-autofill path. The API floor is 30: the platform classes do
 * not exist below it, and the system never calls the entry points there. A password field is
 * refused on our side too, independent of what the platform already filters.
 */
object InlineAutofillGate {
    const val MIN_API_LEVEL = 30

    /**
     * [fieldIsPassword] is the caller's reading of the live field. Both entry points run after
     * the field's startInput, so the EditorInfo is the field's own and a missing one refuses:
     * nothing is hosted where the field cannot be proven clean.
     */
    @JvmStatic
    fun mayHost(apiLevel: Int, fieldIsPassword: Boolean): Boolean =
        apiLevel >= MIN_API_LEVEL && !fieldIsPassword
}
