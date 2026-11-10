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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineAutofillResponseStateTest {

    @Test
    fun outOfOrderInflatesKeepTheirResponseOrder() {
        val state = InlineAutofillResponseState(3)

        assertFalse(state.complete(2, hasView = true))
        assertEquals(0, state.lastInsertionIndex)
        assertFalse(state.complete(0, hasView = true))
        assertEquals(0, state.lastInsertionIndex)
        assertFalse(state.complete(1, hasView = true))
        assertEquals(1, state.lastInsertionIndex)

        assertEquals(listOf(0, 1, 2), List(3, state::responseIndexAt))
    }

    @Test
    fun nullCellsLeaveTheOtherViewsInResponseOrder() {
        val state = InlineAutofillResponseState(4)

        state.complete(3, hasView = true)
        state.complete(1, hasView = true)
        state.complete(0, hasView = false)
        assertFalse(state.complete(2, hasView = true))

        assertEquals(listOf(1, 2, 3), List(3, state::responseIndexAt))
    }

    @Test
    fun onlyTheLastAllNullInflateRequestsWordStripRestoration() {
        val state = InlineAutofillResponseState(3)

        assertFalse(state.complete(2, hasView = false))
        assertFalse(state.complete(0, hasView = false))
        assertTrue(state.complete(1, hasView = false))
    }

    @Test
    fun repeatedCompletionsDoNotConsumeCellsOrInsertViewsTwice() {
        val state = InlineAutofillResponseState(3)

        assertFalse(state.complete(0, hasView = true))
        assertEquals(0, state.lastInsertionIndex)
        assertFalse(state.complete(0, hasView = true))
        assertEquals(-1, state.lastInsertionIndex)
        assertFalse(state.complete(0, hasView = false))
        assertFalse(state.complete(2, hasView = false))
        assertFalse("a mixed response must not restore the strip", state.complete(1, hasView = true))
        assertEquals(1, state.lastInsertionIndex)
        assertFalse(state.complete(1, hasView = true))
        assertEquals(-1, state.lastInsertionIndex)
        assertFalse(state.complete(1, hasView = false))
        assertFalse(state.complete(2, hasView = false))

        assertEquals(listOf(0, 1), List(2, state::responseIndexAt))
    }

    @Test
    fun duplicateViewsCannotChangeAnAllNullResponseOrRestoreTwice() {
        val state = InlineAutofillResponseState(3)

        assertFalse(state.complete(0, hasView = false))
        assertFalse(state.complete(0, hasView = true))
        assertEquals(-1, state.lastInsertionIndex)
        assertFalse(state.complete(1, hasView = false))
        assertFalse(state.complete(1, hasView = true))
        assertEquals(-1, state.lastInsertionIndex)
        assertTrue(state.complete(2, hasView = false))
        assertFalse("a duplicate completion cannot restore again", state.complete(2, hasView = false))
        assertFalse(state.complete(2, hasView = true))
        assertEquals(-1, state.lastInsertionIndex)
    }

    @Test
    fun oneInflatedViewPreventsWordStripRestoration() {
        val state = InlineAutofillResponseState(3)

        state.complete(0, hasView = false)
        state.complete(2, hasView = false)
        assertFalse(state.complete(1, hasView = true))
        assertEquals(listOf(1), List(1, state::responseIndexAt))
    }
}
