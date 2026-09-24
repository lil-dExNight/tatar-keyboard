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
 * Ideal-path writer: turns a word's key sequence (as stored in [GlideWordIndex]) into the
 * polyline a perfect user would draw — key centers in order, with the doubled-letter loop
 * detour (bottom-right, top-right, top-left, bottom-left around the center at a quarter of the
 * key's width/height) when [withLoops] is set.
 *
 * The geometry of the loop detour is pinned identical to the offline gesture generator
 * (`scripts/glide_pack.py`) and to [GlideWordIndex]'s precomputed ideal lengths, so the decoder,
 * the pruner and the calibration harness all reason about the same ideal path.
 */
internal object GlideIdealPaths {

    /**
     * Writes the ideal polyline of [entry] into ([outX], [outY]) and returns the point count.
     * The caller's scratch must hold [MAX_POINTS] entries; a word whose ideal path would exceed
     * it returns -1 (fail-closed: the word is skipped, never truncated mid-shape).
     *
     * [stats] (when non-null) receives the polyline's bounding box during the same walk —
     * [minX, maxX, minY, maxY] — and [segLenOut] (when non-null) the segment lengths (one sqrt
     * per segment, reused by the scoring walk instead of recomputed per output point), so the
     * decoder's fused scoring pass needs no second read.
     */
    fun write(
        index: GlideWordIndex,
        entry: Int,
        geometry: GlideKeyGeometry,
        withLoops: Boolean,
        outX: FloatArray,
        outY: FloatArray,
        stats: FloatArray? = null,
        segLenOut: FloatArray? = null,
        invSegLenOut: FloatArray? = null,
    ): Int {
        val start = index.keySeqStart(entry)
        val end = index.keySeqEnd(entry)
        var count = 0
        var previousKey = -1
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (position in start until end) {
            val key = index.keySeqAt(position)
            val x = geometry.centerX(key)
            val y = geometry.centerY(key)
            if (withLoops && key == previousKey) {
                if (count + 4 > outX.size) return -1
                val dx = geometry.halfWidth(key) / 2
                val dy = geometry.halfHeight(key) / 2
                outX[count] = x + dx
                outY[count] = y + dy
                outX[count + 1] = x + dx
                outY[count + 1] = y - dy
                outX[count + 2] = x - dx
                outY[count + 2] = y - dy
                outX[count + 3] = x - dx
                outY[count + 3] = y + dy
                count += 4
                // The bbox is over the WRITTEN points: the loop corners reach past the center.
                if (x + dx > maxX) maxX = x + dx
                if (x - dx < minX) minX = x - dx
                if (y + dy > maxY) maxY = y + dy
                if (y - dy < minY) minY = y - dy
            } else {
                if (count + 1 > outX.size) return -1
                outX[count] = x
                outY[count] = y
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            previousKey = key
        }
        if (segLenOut != null) {
            for (i in 1 until count) {
                val dx = outX[i] - outX[i - 1]
                val dy = outY[i] - outY[i - 1]
                segLenOut[i - 1] = sqrt(dx * dx + dy * dy)
            }
            if (invSegLenOut != null) {
                // The scoring walk interpolates with a multiply, not a per-point divide.
                for (i in 0 until count - 1) {
                    invSegLenOut[i] = if (segLenOut[i] > 0f) 1f / segLenOut[i] else 0f
                }
            }
        }
        if (stats != null) {
            stats[0] = minX
            stats[1] = maxX
            stats[2] = minY
            stats[3] = maxY
        }
        return count
    }

    /**
     * Worst-case ideal-path size: every letter doubled adds 4 loop points instead of 1 center.
     * Dictionary words cap at 128 UTF-8 bytes (~64 Cyrillic letters), so 5 x 64 is the bound.
     */
    const val MAX_POINTS = 5 * 64
}
