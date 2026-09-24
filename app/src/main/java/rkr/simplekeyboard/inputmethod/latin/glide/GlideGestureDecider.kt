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
 * The glide-vs-tap decision state machine (P7-2 of docs/GLIDE-PLAN.md): from the touch-down
 * point and the stream of move samples, decide whether the gesture is a glide over letter keys.
 *
 * The thresholds follow FlorisBoard's `GlideTypingGesture.Detector` (Apache-2.0, (C) the
 * FlorisBoard contributors): the gesture is a glide once it has travelled more than one key
 * width from the touch-down point at a velocity above 0.10 dp/ms, decided within a 500 ms
 * window. Constants here are already in pixels — the caller (PointerTracker) converts the
 * density-dependent values; this class stays unit-pure and JVM-testable.
 *
 * One deliberate improvement over the reference (the 2026-09-24 field report, see
 * docs/ROADMAP-P7.md): the window and the velocity anchor at the FIRST sample that leaves a
 * small slop around the touch-down point, not at the touch-down itself. The reference measures
 * both from the down event, so a user who rests a finger on the first key for even ~300-500 ms
 * before moving is rejected forever — "зажимаю букву и начинаю вести её в сторону второй — не
 * работает". While no sample exceeds the slop the machine stays TRACKING regardless of elapsed
 * time: a still finger is a long-press candidate, not a rejected glide. The distance threshold
 * is unchanged (one key width from down). An immediately-swiped gesture anchors on its first
 * move sample and behaves as before.
 *
 * States: IDLE (no touch) → TRACKING (eligible letter-key down) → ARMED | REJECTED.
 * ARMED and REJECTED are sticky until [onUpOrCancel]; [cancelGlide] (a second finger's
 * touch-down, a phantom up, a long-press panel opening) drops TRACKING/ARMED to REJECTED so the
 * gesture can never deliver. The machine allocates nothing and holds no Android type.
 */
class GlideGestureDecider(
    private val distanceThresholdPx: Float,
    private val velocityThresholdPxPerMs: Float,
    private val maxDetectTimeMs: Long = DEFAULT_MAX_DETECT_TIME_MS,
    private val slopPx: Float = 0f,
) {
    enum class State { IDLE, TRACKING, ARMED, REJECTED }

    var state: State = State.IDLE
        private set

    val isTracking: Boolean get() = state == State.TRACKING
    val isArmed: Boolean get() = state == State.ARMED

    private var downX = 0f
    private var downY = 0f
    // The first-move anchor: set by the first sample that leaves the slop. Meaningless before.
    private var anchorTimeMs = 0L
    private var anchored = false

    /**
     * Starts a touch. [eligible] is the caller's verdict on the down key (a letter key with the
     * glide preference on); an ineligible start can never arm.
     */
    fun onDown(x: Float, y: Float, timeMs: Long, eligible: Boolean) {
        downX = x
        downY = y
        anchored = false
        anchorTimeMs = timeMs
        state = if (eligible) State.TRACKING else State.REJECTED
    }

    /** Feeds one move sample (historical batch points included, in time order). */
    fun onMove(x: Float, y: Float, timeMs: Long): State {
        if (state != State.TRACKING) return state
        val dx = x - downX
        val dy = y - downY
        val distance = sqrt(dx * dx + dy * dy)
        if (!anchored) {
            // Resting within the slop: no clock runs — a still finger is a long-press
            // candidate, not a rejected glide.
            if (distance <= slopPx) return state
            anchored = true
            anchorTimeMs = timeMs
        }
        val elapsed = timeMs - anchorTimeMs
        if (elapsed > maxDetectTimeMs) {
            state = State.REJECTED
            return state
        }
        if (distance > distanceThresholdPx &&
            distance / max(elapsed, 1L).toFloat() > velocityThresholdPxPerMs
        ) {
            state = State.ARMED
        }
        return state
    }

    /** The touch ended; the machine returns to IDLE for the next one. */
    fun onUpOrCancel() {
        state = State.IDLE
    }

    /** Multi-touch, a phantom up, a fired long-press: the gesture is cancelled, never delivered. */
    fun cancelGlide() {
        if (state == State.TRACKING || state == State.ARMED) {
            state = State.REJECTED
        }
    }

    companion object {
        /** FlorisBoard's detection window: after this, a touch can no longer become a glide. */
        const val DEFAULT_MAX_DETECT_TIME_MS = 500L

        /** FlorisBoard's velocity threshold: 0.10 dp/ms. */
        const val VELOCITY_THRESHOLD_DP_PER_MS = 0.10f

        /** FlorisBoard's distance threshold: one key width (the caller passes it in px). */

        /**
         * The first-move anchor slop as a fraction of the key width (the caller passes the px).
         * Sized just above the platform's 8 dp touch slop — a resting finger's tremor never
         * crosses it — and far below the half-width of a key, so the anchor still lands on the
         * first key: 0.25 x 98 px ≈ 8.9 dp on the reference 440 dpi phone.
         */
        const val DEFAULT_SLOP_KEY_WIDTH_FRACTION = 0.25f
    }
}
