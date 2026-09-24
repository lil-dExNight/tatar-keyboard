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

package rkr.simplekeyboard.inputmethod.latin.glide

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Path resampling and bounding-box normalization, the shared front-end of the glide scoring
 * channels. Both functions write into caller-provided scratch and allocate nothing.
 *
 * The algorithmic ideas (arc-length resampling, bounding-box normalization by the longest side)
 * follow the SHARK2-style statistical classifier as implemented by FlorisBoard's
 * `StatisticalGlideTypingClassifier` (Apache-2.0, (C) the FlorisBoard contributors); the code is
 * written fresh for this engine's scratch-buffer discipline. One deliberate deviation: the
 * reference resampler targets approximately N points and reads past the end as zeros, while this
 * one always produces EXACTLY [numPoints] points — the pointwise distance sums of the two
 * channels then compare aligned samples on both sides, including both endpoints.
 */
object GlideResampler {

    /**
     * Resamples the polyline ([xs], [ys], [size]) into exactly [numPoints] equidistant points
     * along the path's arc length (both endpoints included), writing them into
     * ([outX], [outY]) which must hold at least [numPoints] entries. Returns [numPoints], or 0
     * for an empty input (fail-closed: nothing is written).
     *
     * A zero-length path (all points coincident) degenerates to the single point repeated, so a
     * tap-like input resamples cleanly instead of dividing by zero. Zero-length interior segments
     * (a doubled letter's plain ideal path visits the same center twice) are skipped.
     */
    fun resample(
        xs: FloatArray,
        ys: FloatArray,
        size: Int,
        outX: FloatArray,
        outY: FloatArray,
        numPoints: Int,
    ): Int {
        if (size == 0) return 0
        var total = 0f
        for (i in 1 until size) {
            val dx = xs[i] - xs[i - 1]
            val dy = ys[i] - ys[i - 1]
            total += sqrt(dx * dx + dy * dy)
        }
        if (total <= 0f || numPoints == 1) {
            for (k in 0 until numPoints) {
                outX[k] = xs[0]
                outY[k] = ys[0]
            }
            return numPoints
        }
        val step = total / (numPoints - 1)
        outX[0] = xs[0]
        outY[0] = ys[0]
        var segment = 0
        var segmentBase = 0f // arc length at the start of `segment`
        for (k in 1 until numPoints) {
            val target = step * k
            var segmentLength = segmentLength(xs, ys, size, segment)
            // Advance while the whole segment is consumed. Note base+length >= target can hold
            // with length == 0 only when target <= base, which the strictly increasing targets
            // exclude — so the interpolation below never divides by zero.
            while (segmentBase + segmentLength < target) {
                segmentBase += segmentLength
                segment++
                if (segment >= size - 1) break
                segmentLength = segmentLength(xs, ys, size, segment)
            }
            if (segment >= size - 1) {
                // Float accumulation overshoot: the remaining points sit at the path's end.
                for (rest in k until numPoints) {
                    outX[rest] = xs[size - 1]
                    outY[rest] = ys[size - 1]
                }
                return numPoints
            }
            val t = (target - segmentBase) / segmentLength
            outX[k] = xs[segment] + (xs[segment + 1] - xs[segment]) * t
            outY[k] = ys[segment] + (ys[segment + 1] - ys[segment]) * t
        }
        return numPoints
    }

    /**
     * Bounding-box normalization against a GIVEN bbox: translates the bbox center to the origin
     * and scales by the longest side (guarded against a zero side), so the shape channel compares
     * gesture SHAPES independent of where on the keyboard and at what size they were drawn. The
     * decoder passes the RAW path's bbox (computed from the recorded points) — the resampled
     * samples' own bbox could miss an unsampled extremal vertex (P7-4).
     */
    fun normalizeByBoxSide(
        xs: FloatArray,
        ys: FloatArray,
        size: Int,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        outX: FloatArray,
        outY: FloatArray,
    ) {
        val longestSide = max(max(maxX - minX, maxY - minY), 0.00001f)
        val centroidX = ((maxX - minX) / 2 + minX) / longestSide
        val centroidY = ((maxY - minY) / 2 + minY) / longestSide
        for (i in 0 until size) {
            outX[i] = xs[i] / longestSide - centroidX
            outY[i] = ys[i] / longestSide - centroidY
        }
    }

    private fun segmentLength(xs: FloatArray, ys: FloatArray, size: Int, segment: Int): Float {
        if (segment >= size - 1) return 0f
        val dx = xs[segment + 1] - xs[segment]
        val dy = ys[segment + 1] - ys[segment]
        return sqrt(dx * dx + dy * dy)
    }
}
