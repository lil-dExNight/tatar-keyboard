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

import kotlin.math.roundToInt

/**
 * One prompted-gesture trial: the privacy-frozen record of the debug gesture recorder. The
 * field set is contractual — the record contract test fails the build on any field outside
 * this allowlist:
 *
 * - [prompt]: the word the user was asked to trace, drawn from the bundled dictionary's
 *   top-frequency read — never anything the user typed;
 * - [pathX], [pathY]: the trace resampled to [SAMPLE_COUNT] arc-equidistant points, quantized
 *   to key-radius units (keyboard-view coordinates divided by the layout's key radius, rounded
 *   to three decimals);
 * - [dtMs]: per-point time deltas in milliseconds (the first point carries 0);
 * - [layoutId]: the active layout's id (for example "tt" or "ru");
 * - [densityBucket]: the screen's density bucket (nearest standard dpi);
 * - [top1Match]: whether the decoder's top candidate equaled the prompt.
 *
 * The record carries no field text, no absolute timestamp, and no device or user identifier.
 */
data class GestureRecord(
    val prompt: String,
    val pathX: FloatArray,
    val pathY: FloatArray,
    val dtMs: IntArray,
    val layoutId: String,
    val densityBucket: Int,
    val top1Match: Boolean,
) {
    init {
        require(pathX.size == SAMPLE_COUNT && pathY.size == SAMPLE_COUNT && dtMs.size == SAMPLE_COUNT) {
            "the path arrays hold exactly $SAMPLE_COUNT points"
        }
    }

    /** Serializes the record as one JSONL line, keys in [JSON_KEYS] order. */
    fun toJsonLine(): String {
        val out = StringBuilder(768)
        out.append("{\"prompt\":").appendJsonString(prompt)
        out.append(",\"path_x\":["); appendFloats(out, pathX)
        out.append("],\"path_y\":["); appendFloats(out, pathY)
        out.append("],\"dt_ms\":["); appendInts(out, dtMs)
        out.append("],\"layout_id\":").appendJsonString(layoutId)
        out.append(",\"density_bucket\":").append(densityBucket)
        out.append(",\"top1_match\":").append(if (top1Match) "true" else "false")
        out.append('}')
        return out.toString()
    }

    private fun appendFloats(out: StringBuilder, values: FloatArray) {
        for (i in values.indices) {
            if (i > 0) out.append(',')
            appendFixed3(out, values[i])
        }
    }

    private fun appendInts(out: StringBuilder, values: IntArray) {
        for (i in values.indices) {
            if (i > 0) out.append(',')
            out.append(values[i])
        }
    }

    companion object {
        /** Path resolution of every record: the trace is resampled to exactly this many points. */
        const val SAMPLE_COUNT = 50

        /** The exported JSON keys, in line order; the record contract test pins this list. */
        val JSON_KEYS = listOf(
            "prompt", "path_x", "path_y", "dt_ms", "layout_id", "density_bucket", "top1_match",
        )

        /** Quantization step of the exported coordinates: three decimals of a key radius. */
        internal fun appendFixed3(out: StringBuilder, value: Float) {
            val milli = (value * 1000f).roundToInt()
            if (milli < 0) {
                out.append('-')
            }
            val abs = if (milli < 0) -milli.toLong() else milli.toLong()
            out.append(abs / 1000).append('.')
            val frac = (abs % 1000).toInt()
            if (frac < 100) out.append('0')
            if (frac < 10) out.append('0')
            out.append(frac)
        }

        private fun StringBuilder.appendJsonString(value: String): StringBuilder {
            append('"')
            for (c in value) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append(String.format("\\u%04x", c.code))
                    else -> append(c)
                }
            }
            append('"')
            return this
        }
    }
}
