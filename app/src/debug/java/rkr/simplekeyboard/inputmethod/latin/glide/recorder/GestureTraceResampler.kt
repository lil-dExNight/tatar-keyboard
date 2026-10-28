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
import kotlin.math.sqrt
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath

/**
 * Turns a raw gesture into the record's fixed-shape path: exactly [GestureRecord.SAMPLE_COUNT]
 * arc-equidistant points (both endpoints included, the same segment walk the decoder's
 * resampler runs) with the timestamps linearly interpolated along the arc, coordinates divided
 * by the layout's key radius and rounded to three decimals, and the per-point time deltas in
 * whole milliseconds (the first point carries 0).
 */
internal object GestureTraceResampler {

    /** The resampled path: [x]/[y] in key-radius units, [dtMs] per-point deltas. */
    class Sample(val x: FloatArray, val y: FloatArray, val dtMs: IntArray)

    /**
     * Resamples [path] into [numPoints] points. Returns null on a degenerate input: an empty
     * path or a nonpositive key radius — the caller discards the trial instead of recording a
     * malformed path.
     *
     * A zero-length path (a tap-like input) degenerates to the single point repeated, with the
     * duration spread evenly over the samples.
     */
    fun resample(path: GlidePath, keyRadiusPx: Float, numPoints: Int): Sample? {
        if (numPoints < 2) return null
        if (path.size == 0) return null
        if (!keyRadiusPx.isFinite() || keyRadiusPx <= 0f) return null
        val xs = path.xs
        val ys = path.ys
        val ts = path.ts
        val size = path.size
        val outX = FloatArray(numPoints)
        val outY = FloatArray(numPoints)
        val outT = FloatArray(numPoints)

        // Cumulative arc length per input point.
        val cum = FloatArray(size)
        var total = 0f
        for (i in 1 until size) {
            val dx = xs[i] - xs[i - 1]
            val dy = ys[i] - ys[i - 1]
            total += sqrt(dx * dx + dy * dy)
            cum[i] = total
        }

        if (total <= 0f) {
            val t0 = ts[0]
            val span = ts[size - 1] - t0
            for (k in 0 until numPoints) {
                outX[k] = xs[0]
                outY[k] = ys[0]
                outT[k] = t0 + span * k / (numPoints - 1)
            }
        } else {
            var segment = 0
            for (k in 0 until numPoints) {
                val d = total * k / (numPoints - 1)
                while (segment + 1 < size - 1 && cum[segment + 1] < d) segment++
                // Zero-length interior segments are skipped by the walk; guard the division
                // for the final segment landing exactly on a repeated point.
                val segLen = cum[segment + 1] - cum[segment]
                val frac = if (segLen > 0f) (d - cum[segment]) / segLen else 0f
                outX[k] = xs[segment] + frac * (xs[segment + 1] - xs[segment])
                outY[k] = ys[segment] + frac * (ys[segment + 1] - ys[segment])
                outT[k] = ts[segment] + frac * (ts[segment + 1] - ts[segment])
            }
        }

        val dt = IntArray(numPoints)
        for (k in 1 until numPoints) {
            dt[k] = (outT[k] - outT[k - 1]).roundToInt().coerceAtLeast(0)
        }
        for (k in 0 until numPoints) {
            outX[k] = (outX[k] / keyRadiusPx * 1000f).roundToInt() / 1000f
            outY[k] = (outY[k] / keyRadiusPx * 1000f).roundToInt() / 1000f
        }
        return Sample(outX, outY, dt)
    }
}
