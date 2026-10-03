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

package rkr.simplekeyboard.inputmethod.latin.utils

import android.content.Context
import android.provider.Settings

/**
 * The system animator scale, read once and kept for the lifetime of one gesture or one panel
 * session — the read is a resolver query, so it never repeats per frame. WCAG 2.3.3: when the
 * scale is 0 (an accessibility setting or Battery Saver zeroes it), a hand-rolled cosmetic
 * animation must not play. Direct manipulation feedback (the live glide trail under the finger,
 * a drag) is not an animation and is never gated.
 *
 * The decision half is pure and JVM-testable; [of] is its Android half.
 */
class MotionPolicy(
    /** The `Settings.Global.ANIMATOR_DURATION_SCALE` value at read time; 0 means no animations. */
    val durationScale: Float,
) {
    /** False when the scale is 0: a cosmetic animation gets a zero duration or an instant end. */
    val animationsEnabled: Boolean = durationScale > 0f

    companion object {
        /** Reads the current scale; call once per gesture or per panel open, never per frame. */
        @JvmStatic
        fun of(context: Context): MotionPolicy = MotionPolicy(
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ),
        )
    }
}
