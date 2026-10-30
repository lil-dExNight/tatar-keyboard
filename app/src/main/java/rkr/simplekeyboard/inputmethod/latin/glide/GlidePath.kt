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

import kotlin.math.sqrt

/**
 * A recorded glide path: fixed-capacity parallel point buffers (x, y, t) that never allocate
 * after construction. `x`/`y` are pixel (or grid-unit) coordinates in the keyboard's coordinate
 * space; `t` is the sample timestamp in milliseconds — the decoder reads total duration for the
 * speed channel and segment durations for the per-key dwell mask.
 *
 * On overflow, the interior point with the smallest arc-length reduction is removed. This keeps
 * the buffer bounded while preserving the first point, last point and total gesture duration.
 *
 * An instance is confined to one thread (the decoder's is the engine worker), so the buffers
 * need no synchronization.
 */
class GlidePath(val capacity: Int = MAX_POINTS) {
    val xs = FloatArray(capacity)
    val ys = FloatArray(capacity)
    val ts = FloatArray(capacity)

    var size = 0
        private set

    /**
     * Appends one sample, thinning an interior point first when full. Returns false for a
     * non-finite sample or zero capacity.
     */
    fun addPoint(x: Float, y: Float, t: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || !t.isFinite()) return false
        if (size >= capacity) {
            when (size) {
                0 -> return false
                // These capacities cannot retain an interior sample; keep whichever ends fit.
                1 -> size = 0
                2 -> size = 1
                else -> thinOnePoint()
            }
        }
        xs[size] = x
        ys[size] = y
        ts[size] = t
        size++
        return true
    }

    /** Records a relative timestamp in Long precision and skips an exact duplicate of the last sample. */
    fun addPoint(x: Float, y: Float, eventTime: Long, gestureStartTime: Long): Boolean {
        val t = (eventTime - gestureStartTime).toFloat()
        val last = size - 1
        if (last >= 0 && xs[last] == x && ys[last] == y && ts[last] == t) return false
        return addPoint(x, y, t)
    }

    /** Removes the interior point whose removal least reduces the polyline's arc length. */
    private fun thinOnePoint() {
        var removal = 1
        var lowestCost = Float.POSITIVE_INFINITY
        for (i in 1 until size - 1) {
            val beforeX = xs[i] - xs[i - 1]
            val beforeY = ys[i] - ys[i - 1]
            val afterX = xs[i + 1] - xs[i]
            val afterY = ys[i + 1] - ys[i]
            val directX = xs[i + 1] - xs[i - 1]
            val directY = ys[i + 1] - ys[i - 1]
            val cost = sqrt(beforeX * beforeX + beforeY * beforeY) +
                sqrt(afterX * afterX + afterY * afterY) -
                sqrt(directX * directX + directY * directY)
            if (cost < lowestCost) {
                lowestCost = cost
                removal = i
            }
        }
        val count = size - removal - 1
        System.arraycopy(xs, removal + 1, xs, removal, count)
        System.arraycopy(ys, removal + 1, ys, removal, count)
        System.arraycopy(ts, removal + 1, ts, removal, count)
        size--
    }

    fun clear() {
        size = 0
    }

    /**
     * Copies into [target], discarding its previous content and using its thinning rule when
     * smaller. Used to snapshot the tracker's live buffer before it crosses a thread boundary.
     */
    fun copyInto(target: GlidePath): Int {
        target.clear()
        if (size > target.capacity) {
            for (i in 0 until size) target.addPoint(xs[i], ys[i], ts[i])
            return target.size
        }
        System.arraycopy(xs, 0, target.xs, 0, size)
        System.arraycopy(ys, 0, target.ys, 0, size)
        System.arraycopy(ts, 0, target.ts, 0, size)
        target.size = size
        return size
    }

    /** Total arc length of the polyline through the recorded points. */
    fun length(): Float {
        var total = 0f
        for (i in 1 until size) {
            val dx = xs[i] - xs[i - 1]
            val dy = ys[i] - ys[i - 1]
            total += sqrt(dx * dx + dy * dy)
        }
        return total
    }

    val firstX: Float get() = if (size > 0) xs[0] else 0f
    val firstY: Float get() = if (size > 0) ys[0] else 0f
    val lastX: Float get() = if (size > 0) xs[size - 1] else 0f
    val lastY: Float get() = if (size > 0) ys[size - 1] else 0f

    companion object {
        /** Matches the reference implementations' gesture buffers (FlorisBoard uses 500). */
        const val MAX_POINTS = 512
    }
}
