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

import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath

/**
 * The recorder core: a state machine around the ring buffer with one hard rule — discard by
 * default. A gesture is recorded only while armed (the user passed the opt-in dialog in this
 * debug build), only in prompted-word mode ([currentPrompt] is a [GesturePromptSource] word),
 * and only the allowlisted [GestureRecord] fields are written.
 *
 * The core is pure Kotlin (no Android imports) so the contract tests exercise it on the JVM;
 * the Android shell ([GestureRecorderController]) resolves the storage directory, the density
 * and the consent persistence.
 *
 * All entry points are synchronized: gestures arrive on the UI thread while the management
 * screen can act from its own, and the ring buffer is the shared state.
 */
class GestureRecorder(private val buffer: GestureRingBuffer) {

    /** What became of a gesture handed to [onGesture]. */
    enum class Outcome {
        /** Not armed, or no prompt was pending: nothing was written. */
        DISCARDED,

        /** The gesture was malformed (empty path, nonpositive key radius): nothing was written. */
        REJECTED,

        /** One [GestureRecord] line was appended to the ring buffer. */
        RECORDED,
    }

    var armed = false
        private set

    private var prompts: GesturePromptSource? = null
    private var prompt: String? = null

    /**
     * Points the recorder at the active layout's prompt pool (the bundled dictionary's
     * top-frequency read). A layout switch replaces the pool and draws a fresh prompt.
     */
    @Synchronized
    fun setPromptSource(source: GesturePromptSource?) {
        prompts = source
        prompt = if (armed) source?.next() else null
    }

    /**
     * Arms or disarms the recorder. Arming requires a non-empty prompt pool — without a
     * dictionary-backed prompt there is no prompted-word mode, so the call fails and the
     * recorder stays disarmed.
     */
    @Synchronized
    fun setArmed(armed: Boolean): Boolean {
        if (armed && (prompts == null || prompts?.isEmpty == true)) return false
        this.armed = armed
        prompt = if (armed) prompts?.next() else null
        return true
    }

    /** The word the user is currently asked to trace; null while disarmed. */
    @Synchronized
    fun currentPrompt(): String? = prompt

    /**
     * The one gesture entry point. [decodedTop1] is the decoder's top candidate for the same
     * gesture (null on an empty decode); it is compared against the prompt, never stored. The
     * prompt advances on every gesture processed while armed, recorded or rejected.
     */
    @Synchronized
    fun onGesture(
        path: GlidePath,
        keyRadiusPx: Float,
        layoutId: String,
        densityBucket: Int,
        decodedTop1: String?,
    ): Outcome {
        if (!armed) return Outcome.DISCARDED
        val currentPrompt = prompt ?: return Outcome.DISCARDED
        val sample = GestureTraceResampler.resample(path, keyRadiusPx, GestureRecord.SAMPLE_COUNT)
        if (sample == null) {
            prompt = prompts?.next()
            return Outcome.REJECTED
        }
        val record = GestureRecord(
            prompt = currentPrompt,
            pathX = sample.x,
            pathY = sample.y,
            dtMs = sample.dtMs,
            layoutId = layoutId,
            densityBucket = densityBucket,
            top1Match = decodedTop1 != null && decodedTop1.equals(currentPrompt, ignoreCase = true),
        )
        prompt = prompts?.next()
        return if (buffer.append(record.toJsonLine())) Outcome.RECORDED else Outcome.REJECTED
    }

    @Synchronized
    fun recordCount(): Int = buffer.size

    /** Discards every buffered record. */
    @Synchronized
    fun discardAll(): Boolean = buffer.clear()

    /** The buffered lines, oldest first, for the export path. */
    @Synchronized
    fun snapshot(): List<String> = buffer.snapshot()

    companion object {
        private val DENSITY_BUCKETS = intArrayOf(120, 160, 213, 240, 320, 480, 640)

        /** The record's density bucket: the nearest standard dpi bucket to [densityDpi]. */
        fun densityBucket(densityDpi: Int): Int {
            var best = DENSITY_BUCKETS[0]
            for (bucket in DENSITY_BUCKETS) {
                if (kotlin.math.abs(bucket - densityDpi) < kotlin.math.abs(best - densityDpi)) {
                    best = bucket
                }
            }
            return best
        }
    }
}
