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
 * window; after the window the touch can never become a glide (a slow press is a long-press
 * candidate, not a truncated glide). Constants here are already in pixels — the caller
 * (PointerTracker) converts the density-dependent values; this class stays unit-pure and
 * JVM-testable.
 *
 * States: IDLE (no touch) → TRACKING (eligible letter-key down) → ARMED | REJECTED.
 * ARMED and REJECTED are sticky until [onUpOrCancel]; [cancelGlide] (a second finger's
 * touch-down, a phantom up) drops ARMED/TRACKING to REJECTED so the gesture can never deliver.
 * The machine allocates nothing and holds no Android type.
 */
class GlideGestureDecider(
    private val distanceThresholdPx: Float,
    private val velocityThresholdPxPerMs: Float,
    private val maxDetectTimeMs: Long = DEFAULT_MAX_DETECT_TIME_MS,
) {
    enum class State { IDLE, TRACKING, ARMED, REJECTED }

    var state: State = State.IDLE
        private set

    val isTracking: Boolean get() = state == State.TRACKING
    val isArmed: Boolean get() = state == State.ARMED

    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L

    /**
     * Starts a touch. [eligible] is the caller's verdict on the down key (a letter key with the
     * glide preference on); an ineligible start can never arm.
     */
    fun onDown(x: Float, y: Float, timeMs: Long, eligible: Boolean) {
        downX = x
        downY = y
        downTimeMs = timeMs
        state = if (eligible) State.TRACKING else State.REJECTED
    }

    /** Feeds one move sample (historical batch points included, in time order). */
    fun onMove(x: Float, y: Float, timeMs: Long): State {
        if (state != State.TRACKING) return state
        val elapsed = timeMs - downTimeMs
        if (elapsed > maxDetectTimeMs) {
            state = State.REJECTED
            return state
        }
        val dx = x - downX
        val dy = y - downY
        val distance = sqrt(dx * dx + dy * dy)
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

    /** Multi-touch or a phantom up: the gesture is cancelled, never delivered. */
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
    }
}
