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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GestureRecord.toJsonLine]: the exported key set is exactly the privacy allowlist, values
 * survive the round trip (fixed three-decimal coordinates, whole-millisecond deltas, escaped
 * prompt), and one record is one line.
 */
class GestureRecordJsonTest {

    private fun record(
        prompt: String = "китап",
        x0: Float = 1.234f,
        top1Match: Boolean = true,
    ): GestureRecord {
        val x = FloatArray(GestureRecord.SAMPLE_COUNT) { x0 + it * 0.001f }
        val y = FloatArray(GestureRecord.SAMPLE_COUNT) { 0.5f }
        val dt = IntArray(GestureRecord.SAMPLE_COUNT) { if (it == 0) 0 else 12 }
        return GestureRecord(prompt, x, y, dt, "tt", 480, top1Match)
    }

    @Test
    fun theJsonKeysAreExactlyTheAllowlist() {
        val keys = Regex(""""(\w+)":""")
            .findAll(record().toJsonLine())
            .map { it.groupValues[1] }
            .toList()
        assertEquals(GestureRecord.JSON_KEYS, keys)
    }

    @Test
    fun oneRecordIsOneLine() {
        val line = record().toJsonLine()
        assertFalse(line.contains('\n'))
        assertTrue(line.startsWith("{"))
        assertTrue(line.endsWith("}"))
    }

    @Test
    fun theCoordinatesAreFixedThreeDecimals() {
        val line = record().toJsonLine()
        assertTrue(line.contains("\"path_x\":[1.234,1.235,1.236"))
        assertTrue(line.contains("\"path_y\":[0.500"))
        // Negative values keep the sign and the milli grid.
        val negative = GestureRecord(
            "w",
            FloatArray(GestureRecord.SAMPLE_COUNT) { -2.5f },
            FloatArray(GestureRecord.SAMPLE_COUNT),
            IntArray(GestureRecord.SAMPLE_COUNT),
            "tt", 480, true,
        ).toJsonLine()
        assertTrue(negative.contains("\"path_x\":[-2.500,-2.500"))
        assertTrue(negative.contains("\"path_y\":[0.000"))
    }

    @Test
    fun theDeltasAreWholeMilliseconds() {
        val line = record().toJsonLine()
        assertTrue(line.contains("\"dt_ms\":[0,12,12"))
        assertTrue(line.contains("12]"))
    }

    @Test
    fun thePromptIsEscaped() {
        val line = record(prompt = "a\"b\\c").toJsonLine()
        assertTrue(line.contains("\"prompt\":\"a\\\"b\\\\c\""))
    }

    @Test
    fun theMatchFlagSerializesBothWays() {
        assertTrue(record(top1Match = true).toJsonLine().contains("\"top1_match\":true"))
        assertTrue(record(top1Match = false).toJsonLine().contains("\"top1_match\":false"))
    }
}
