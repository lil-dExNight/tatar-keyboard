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

/**
 * The visual tail of an armed glide (docs/ROADMAP-P7.md, P7-5): a fixed-capacity ring of the
 * most recent finger positions that the main keyboard view draws as a fading polyline under
 * the fingertip. Pure data — no android imports — so the ring semantics and the fade math are
 * JVM-testable; the view owns the Paint and the Canvas calls.
 *
 * Unlike [rkr.simplekeyboard.inputmethod.latin.glide.GlidePath] (the decoder's input, which
 * drops points past its capacity — a truncated path still decodes), the trail OVERWRITES the
 * oldest point when full: a stale tail pixel is strictly worse than a dropped one, and the
 * decoder's fail-closed argument does not apply to a cosmetic layer.
 *
 * The fade is age-relative to the NEWEST recorded point, not to a wall clock: the view redraws
 * only when a new point arrives, so "now" and the newest sample are the same instant, and the
 * JVM tests need no clock injection. A finger that stops moving freezes its tail (no new
 * points, no invalidates) — the same behavior as the reference keyboards.
 *
 * P7-7 (2026-09-25): the tail grew (300 ms / 96 points — it reads as a trail, not a stub), and
 * the lift no longer erases it instantly: [startFadeOut] freezes the ring and the draw alpha
 * then decays to zero over [FADE_OUT_MS] of wall time (the view passes SystemClock in — the one
 * wall-clock read of the class, confined to the fade). A new gesture's first point clears the
 * fading ring ([addPoint] resets the fade); [isFadeDone] tells the view when to stop
 * re-invalidating.
 *
 * Zero-allocation after construction: parallel primitive arrays, index arithmetic only.
 */
class GlideTrail(val capacity: Int = DEFAULT_CAPACITY) {
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val ts = FloatArray(capacity)

    /** Ring position of the oldest point. */
    private var start = 0

    var size = 0
        private set

    /** P7-7: the lift started the post-gesture fade; the ring is frozen meanwhile. */
    var fadingOut = false
        private set

    /** Wall-clock millisecond the fade started at (view: SystemClock.uptimeMillis as float). */
    private var fadeStartMs = 0f

    /**
     * Appends one sample, overwriting the oldest when the ring is full. A point that arrives
     * while a fade is running belongs to the NEXT gesture: the stale ring is dropped first, so
     * the new trail never draws a bridge from the old gesture's last point.
     */
    fun addPoint(x: Float, y: Float, t: Float) {
        if (fadingOut) {
            clear()
        }
        if (size < capacity) {
            val index = (start + size) % capacity
            xs[index] = x
            ys[index] = y
            ts[index] = t
            size++
        } else {
            xs[start] = x
            ys[start] = y
            ts[start] = t
            start = (start + 1) % capacity
        }
    }

    fun clear() {
        size = 0
        start = 0
        fadingOut = false
    }

    fun isEmpty(): Boolean = size == 0

    /**
     * The lift: the ring freezes and starts fading. An empty ring fades nothing (the view's
     * no-op contract for non-glide touches is preserved).
     */
    fun startFadeOut(nowMs: Float) {
        if (size == 0) return
        fadingOut = true
        fadeStartMs = nowMs
    }

    /** The global fade multiplier: 1 while the gesture runs, decaying to 0 over [FADE_OUT_MS]. */
    fun fadeFactor(nowMs: Float): Float {
        if (!fadingOut) return 1f
        val elapsed = nowMs - fadeStartMs
        if (elapsed <= 0f) return 1f
        if (elapsed >= FADE_OUT_MS) return 0f
        return 1f - elapsed / FADE_OUT_MS
    }

    /** True when the fade ran its course — the view clears the ring and stops re-invalidating. */
    fun isFadeDone(nowMs: Float): Boolean = fadingOut && nowMs - fadeStartMs >= FADE_OUT_MS

    /**
     * The oldest-first index of the first point inside the visible tail window (age at most
     * [TAIL_MS] from the newest point). Points before it are older than the tail and are not
     * drawn. Touch timestamps arrive non-decreasing, so a linear scan from the oldest point
     * stops at the window edge.
     */
    fun firstVisible(): Int {
        if (size == 0) return 0
        val newest = ts[ringIndex(size - 1)]
        var i = 0
        while (i < size && newest - ts[ringIndex(i)] > TAIL_MS) {
            i++
        }
        return i
    }

    /** Oldest-first accessors; [i] in 0 until [size]. */
    fun xAt(i: Int): Float = xs[ringIndex(i)]

    fun yAt(i: Int): Float = ys[ringIndex(i)]

    /**
     * The draw alpha of the point at oldest-first index [i]: [MAX_ALPHA] at the fingertip
     * (the newest point), fading linearly to 0 at the [TAIL_MS] edge.
     */
    fun alphaAt(i: Int): Int {
        val newest = ts[ringIndex(size - 1)]
        val age = newest - ts[ringIndex(i)]
        if (age >= TAIL_MS) return 0
        return (MAX_ALPHA * (1f - age / TAIL_MS)).toInt()
    }

    private fun ringIndex(i: Int): Int = (start + i) % capacity

    companion object {
        /** ~300 ms of a 60 fps gesture at the usual 5–10 ms sampling cadence, with margin. */
        const val DEFAULT_CAPACITY = 96

        /** How far behind the fingertip the visible tail reaches (P7-7: doubled to 300 ms). */
        const val TAIL_MS = 300f

        /** P7-7: the post-lift fade-out duration. */
        const val FADE_OUT_MS = 250f

        /** Peak opacity at the fingertip; the base color comes from the theme. */
        const val MAX_ALPHA = 0x66
    }
}
