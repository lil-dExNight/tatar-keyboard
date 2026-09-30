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

package rkr.simplekeyboard.inputmethod.keyboard.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * KeyPreviewView.getTextWidth() reuses a single growing scratch buffer instead of
 * allocating `new float[len]` on every key preview. These tests pin the contract of the
 * buffer helpers without android.graphics.TextPaint (unavailable in JVM unit tests):
 * repeated measurements with varying text lengths must not corrupt results — a buffer left
 * longer than the last text carries stale tail entries that must never be summed.
 */
class KeyPreviewViewTextWidthsTest {

    @Test
    fun bufferGrowsToFitLongerText() {
        var buffer = FloatArray(0)
        buffer = KeyPreviewView.ensureWidthsCapacity(buffer, 2)
        assertEquals(2, buffer.size)
        val grown = KeyPreviewView.ensureWidthsCapacity(buffer, 10)
        assertEquals(10, grown.size)
    }

    @Test
    fun bufferIsReusedWhenAlreadyBigEnough() {
        val buffer = FloatArray(10)
        assertSame(buffer, KeyPreviewView.ensureWidthsCapacity(buffer, 10))
        assertSame(buffer, KeyPreviewView.ensureWidthsCapacity(buffer, 3))
    }

    @Test
    fun repeatedMeasurementsWithVaryingLengthsGiveCorrectSums() {
        // Simulates the paint.getTextWidths() + sum loop over a reused buffer.
        var buffer = FloatArray(0)

        buffer = KeyPreviewView.ensureWidthsCapacity(buffer, 2)
        buffer[0] = 4f; buffer[1] = 6f
        assertEquals(10f, KeyPreviewView.sumWidths(buffer, 2), 0f)

        buffer = KeyPreviewView.ensureWidthsCapacity(buffer, 10)
        for (i in 0 until 10) buffer[i] = 1f
        assertEquals(10f, KeyPreviewView.sumWidths(buffer, 10), 0f)

        // Shorter text after a longer one: the buffer keeps stale tail entries,
        // the sum must cover only the freshly written prefix.
        buffer[0] = 2f; buffer[1] = 3f; buffer[2] = 5f
        assertEquals(10f, KeyPreviewView.sumWidths(buffer, 3), 0f)
    }
}
