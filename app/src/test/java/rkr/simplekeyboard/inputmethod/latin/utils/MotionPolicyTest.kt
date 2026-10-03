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
 * The pure half of [MotionPolicy]: whether hand-rolled cosmetic animations may play, decided
 * from the animator scale alone. The Android half ([MotionPolicy.of], the resolver read) is
 * pinned at source level by MotionPolicySourceContractTest.
 */
class MotionPolicyTest {

    @Test
    fun aZeroScaleDisablesAnimations() {
        assertFalse(MotionPolicy(0f).animationsEnabled)
        assertFalse(MotionPolicy(0f).durationScale > 0f)
    }

    @Test
    fun aNegativeScaleDisablesAnimations() {
        // Reachable only through adb, but treated as "off", never as a duration multiplier.
        assertFalse(MotionPolicy(-1f).animationsEnabled)
    }

    @Test
    fun anyPositiveScaleKeepsAnimationsEnabled() {
        // Only the zero case is gated; a slowed scale still animates, as the settings screen
        // transitions do.
        assertTrue(MotionPolicy(0.5f).animationsEnabled)
        assertTrue(MotionPolicy(1f).animationsEnabled)
        assertTrue(MotionPolicy(2f).animationsEnabled)
    }
}
