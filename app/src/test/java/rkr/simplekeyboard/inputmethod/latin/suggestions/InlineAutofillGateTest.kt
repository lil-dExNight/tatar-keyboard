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

/** The inline-autofill field gate: the API floor is 30; password fields host chips like any
 *  other field — the content comes from the user's own autofill service and is only hosted. */
class InlineAutofillGateTest {

    @Test
    fun theApiFloorIs30() {
        assertEquals(30, InlineAutofillGate.MIN_API_LEVEL)
        assertFalse(InlineAutofillGate.mayHost(29))
        assertTrue(InlineAutofillGate.mayHost(30))
        assertTrue(InlineAutofillGate.mayHost(34))
    }

    @Test
    fun belowTheFloorNothingIsHosted() {
        assertFalse(InlineAutofillGate.mayHost(24))
    }
}
