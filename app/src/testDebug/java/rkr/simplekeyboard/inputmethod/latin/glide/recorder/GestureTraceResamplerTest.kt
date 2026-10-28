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

package rkr.simplekeyboard.inputmethod.latin.glide.recorder

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath

/**
 * [GestureTraceResampler]: exactly 50 arc-equidistant points, key-radius quantization at three
 * decimals, per-point time deltas in whole milliseconds, and null on degenerate input.
 */
class GestureTraceResamplerTest {

    @Test
    fun aStraightLineResamplesToFiftyEquidistantPoints() {
        // (0,0) -> (98,0) px with 10 ms steps; the radius is 49 px, so the path spans exactly
        // 2 key-radius units and the 50 samples land 2/49 units apart.
        val path = GlidePath()
        for (i in 0..98) path.addPoint(i.toFloat(), 0f, i * 10f)
        val sample = GestureTraceResampler.resample(path, 49f, 50)
        assertNotNull(sample)
        sample!!
        assertEquals(50, sample.x.size)
        assertEquals(0f, sample.x[0], 1e-6f)
        assertEquals(2f, sample.x[49], 1e-6f)
        val spacing = 2f / 49f
        for (k in 1 until 50) {
            // The three-decimal quantization moves each point by at most half a milli-radius.
            assertEquals("point $k", spacing, sample.x[k] - sample.x[k - 1], 2e-3f)
            assertEquals(0f, sample.y[k], 1e-6f)
        }
    }

    @Test
    fun theEndpointsArePreservedOnACorneredPath() {
        val path = GlidePath()
        for (i in 0..50) path.addPoint(i.toFloat(), 0f, i * 10f)
        for (i in 1..50) path.addPoint(50f, i.toFloat(), (50 + i) * 10f)
        val sample = GestureTraceResampler.resample(path, 25f, 50)!!
        assertEquals(0f, sample.x[0], 1e-6f)
        assertEquals(0f, sample.y[0], 1e-6f)
        assertEquals(2f, sample.x[49], 1e-6f)
        assertEquals(2f, sample.y[49], 1e-6f)
    }

    @Test
    fun theTimeDeltasSumToTheGestureDuration() {
        val path = GlidePath()
        for (i in 0..20) path.addPoint(i * 5f, i * 2f, i * 47f)
        val sample = GestureTraceResampler.resample(path, 50f, 50)!!
        assertEquals(0, sample.dtMs[0])
        for (k in 1 until 50) {
            assertTrue("dt $k is nonnegative", sample.dtMs[k] >= 0)
        }
        val durationMs = 20 * 47
        assertTrue(
            "the deltas sum to the duration (rounding slack)",
            abs(sample.dtMs.sum() - durationMs) <= 50,
        )
    }

    @Test
    fun aTapLikePathDegeneratesToTheSinglePoint() {
        val single = GlidePath()
        single.addPoint(100f, 40f, 1000f)
        single.addPoint(100f, 40f, 1100f)
        val sample = GestureTraceResampler.resample(single, 50f, 50)!!
        for (k in 0 until 50) {
            assertEquals(2f, sample.x[k], 1e-6f)
            assertEquals(0.8f, sample.y[k], 1e-6f)
        }
        // The 100 ms duration is spread evenly: every delta is 100/49 rounded, never negative.
        assertTrue(abs(sample.dtMs.sum() - 100) <= 50)
    }

    @Test
    fun degenerateInputsAreRejected() {
        val empty = GlidePath()
        assertNull(GestureTraceResampler.resample(empty, 50f, 50))
        val path = GlidePath()
        path.addPoint(0f, 0f, 0f)
        assertNull(GestureTraceResampler.resample(path, 0f, 50))
        assertNull(GestureTraceResampler.resample(path, -1f, 50))
        assertNull(GestureTraceResampler.resample(path, 50f, 1))
    }
}
