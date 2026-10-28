/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.glide

/**
 * A completed glide gesture, published for the debug build's gesture recorder. The release build
 * never installs a sink, so the read below is a null check and nothing leaves the device. The
 * sink gets the path and the decode's top candidate; a sink must never persist the word.
 */
interface GlideGestureSink {

    fun onGlideGesture(
        path: GlidePath,
        keyRadiusPx: Float,
        layoutId: String,
        decodedTop1: String?,
    )

    companion object {
        /** The active sink; null in release builds and while no recorder is armed. */
        @Volatile
        var current: GlideGestureSink? = null
    }
}
