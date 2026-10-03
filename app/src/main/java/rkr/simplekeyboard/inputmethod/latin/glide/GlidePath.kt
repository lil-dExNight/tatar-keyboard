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
 * space; `t` is the sample timestamp in milliseconds — the decoder reads only the gesture's total
 * duration from it (the speed channel).
 *
 * Points past the capacity are dropped ([addPoint] returns false); the leading [MAX_POINTS]
 * samples cover far more finger travel than any real word.
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
     * Appends one sample; returns false and drops the point when the buffer is full or the
     * sample is not finite (a NaN/Infinity coordinate would corrupt every distance the decoder
     * measures on this path).
     */
    fun addPoint(x: Float, y: Float, t: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || !t.isFinite()) return false
        if (size >= capacity) return false
        xs[size] = x
        ys[size] = y
        ts[size] = t
        size++
        return true
    }

    fun clear() {
        size = 0
    }

    /**
     * Copies the recorded points into [target] (its previous content is discarded) and returns
     * the number of points copied (capped at the target's capacity). Used when a glide path
     * crosses a thread boundary: the tracker's buffer is live, so the receiver snapshots it.
     */
    fun copyInto(target: GlidePath): Int {
        target.clear()
        val count = minOf(size, target.capacity)
        System.arraycopy(xs, 0, target.xs, 0, count)
        System.arraycopy(ys, 0, target.ys, 0, count)
        System.arraycopy(ts, 0, target.ts, 0, count)
        target.size = count
        return count
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
