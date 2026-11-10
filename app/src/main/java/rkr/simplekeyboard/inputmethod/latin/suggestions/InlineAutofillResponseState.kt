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

/** Tracks one response's callback completion and the child order of its non-null cells. */
internal class InlineAutofillResponseState(cellCount: Int) {
    private val hostedIndices = IntArray(cellCount)
    private var remaining = cellCount
    private var hostedCount = 0
    private var completedMask = 0L

    /** Child position of the last non-null completion; consumed immediately by the host view. */
    var lastInsertionIndex = -1
        private set

    init {
        require(cellCount in 1..64)
    }

    /** Returns true when the last callback completes a response with no views at all. */
    fun complete(cellIndex: Int, hasView: Boolean): Boolean {
        check(cellIndex in hostedIndices.indices)
        lastInsertionIndex = -1
        val completionBit = 1L shl cellIndex
        if (completedMask and completionBit != 0L) return false
        completedMask = completedMask or completionBit
        remaining--
        if (!hasView) return remaining == 0 && hostedCount == 0

        var insertion = hostedCount
        for (position in 0 until hostedCount) {
            if (cellIndex < hostedIndices[position]) {
                insertion = position
                break
            }
        }
        for (position in hostedCount downTo insertion + 1) {
            hostedIndices[position] = hostedIndices[position - 1]
        }
        hostedIndices[insertion] = cellIndex
        hostedCount++
        lastInsertionIndex = insertion
        return false
    }

    /** Response index hosted at [childPosition], used to verify view ordering without Android. */
    fun responseIndexAt(childPosition: Int): Int {
        check(childPosition in 0 until hostedCount)
        return hostedIndices[childPosition]
    }
}
