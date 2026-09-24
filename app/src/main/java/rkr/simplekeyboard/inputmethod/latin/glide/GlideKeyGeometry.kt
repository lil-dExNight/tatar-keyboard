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

import java.text.Normalizer
import java.util.Arrays

/**
 * Letter -> key geometry table of one keyboard layout, in the keyboard's pixel (or grid-unit)
 * coordinate space. The glide analogue of the dictionary engine's `KeyNeighborTable`: built by a
 * pure function from plain [RawKey] records so it carries no Android type and runs in ordinary
 * JVM tests; the live `Keyboard` is adapted into [RawKey]s on the Android side
 * (`rkr.simplekeyboard.inputmethod.latin.suggestions.GlideKeyGeometryBuilder`), and not a single
 * letter or coordinate is hard-coded here.
 *
 * Letters are stored sorted by code point with parallel center/half-size arrays, so a lookup is
 * one primitive binary search and the extremity pruner's nearest-key scan reads flat arrays —
 * nothing boxes and nothing allocates per query.
 *
 * [keyRadius] is the minimum over all letter keys of min(width, height): the scale reference of
 * the location channel's standard deviation and of the length-pruning threshold. The minimum
 * (rather than the first key, as the reference implementation does) keeps the scale honest on
 * layouts whose rows mix key widths.
 */
class GlideKeyGeometry private constructor(
    private val letters: IntArray,
    private val centerXs: FloatArray,
    private val centerYs: FloatArray,
    private val halfWidths: FloatArray,
    private val halfHeights: FloatArray,
    val keyRadius: Float,
) {
    val keyCount: Int get() = letters.size
    val isEmpty: Boolean get() = letters.isEmpty()

    /** Key index of [codePoint] (already-normalized letter), or -1 when the layout has no such key. */
    fun keyIndexOfLetter(codePoint: Int): Int = Arrays.binarySearch(letters, codePoint)

    fun centerX(keyIndex: Int): Float = centerXs[keyIndex]
    fun centerY(keyIndex: Int): Float = centerYs[keyIndex]
    fun halfWidth(keyIndex: Int): Float = halfWidths[keyIndex]
    fun halfHeight(keyIndex: Int): Float = halfHeights[keyIndex]

    /**
     * Writes the indices of the [out].size keys whose centers are nearest to ([x], [y]) into
     * [out], nearest first, and returns how many were written (<= out.size, <= [keyCount]).
     * Distance ties go to the lower key index, so the result is deterministic. The selection is
     * a repeated strict-minimum scan over the flat arrays — O(slots x keys) with zero
     * allocations, which at 37 keys and 2 slots beats any scratch-buffer variant.
     */
    fun findClosestKeys(x: Float, y: Float, out: IntArray): Int {
        val wanted = minOf(out.size, letters.size)
        var previousDistance = -1f
        var previousKey = -1
        var count = 0
        while (count < wanted) {
            var bestKey = -1
            var bestDistance = Float.MAX_VALUE
            for (key in letters.indices) {
                val dx = centerXs[key] - x
                val dy = centerYs[key] - y
                val distance = dx * dx + dy * dy
                val beyondPrevious = distance > previousDistance ||
                    (distance == previousDistance && key > previousKey)
                if (beyondPrevious &&
                    (distance < bestDistance || (distance == bestDistance && key < bestKey))
                ) {
                    bestDistance = distance
                    bestKey = key
                }
            }
            if (bestKey < 0) break
            out[count] = bestKey
            count++
            previousDistance = bestDistance
            previousKey = bestKey
        }
        return count
    }

    /** One letter key as read from the live layout: the key's code and its visible rectangle. */
    class RawKey(
        val codePoint: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    companion object {
        /**
         * Builds the table from the keys of a single keyboard element. Every code is folded to
         * its NFC lower-case form; non-code-point and non-letter keys (shift, delete, space) are
         * dropped. A letter carried by two keys keeps the first rectangle — a defensive choice no
         * shipped layout exercises. An empty input yields an empty geometry (radius 0), which the
         * decoder treats fail-closed (no candidates).
         */
        fun build(keys: List<RawKey>): GlideKeyGeometry {
            val order = ArrayList<Int>(keys.size)
            val folded = IntArray(keys.size)
            for ((index, key) in keys.withIndex()) {
                val letter = normalizeLetterCodePoint(key.codePoint) ?: continue
                // De-duplicate by letter, first key wins.
                var present = false
                for (kept in order) {
                    if (folded[kept] == letter) {
                        present = true
                        break
                    }
                }
                if (present) continue
                folded[index] = letter
                order.add(index)
            }
            val count = order.size
            val letters = IntArray(count)
            val centerXs = FloatArray(count)
            val centerYs = FloatArray(count)
            val halfWidths = FloatArray(count)
            val halfHeights = FloatArray(count)
            // Sort key indices by folded letter with a plain insertion sort; `count` is the
            // layout's letter-key count (tens), so this is build-time trivial.
            val sorted = order.toIntArray()
            for (i in 1 until count) {
                val value = sorted[i]
                val letter = folded[value]
                var j = i - 1
                while (j >= 0 && folded[sorted[j]] > letter) {
                    sorted[j + 1] = sorted[j]
                    j--
                }
                sorted[j + 1] = value
            }
            var radius = Float.MAX_VALUE
            for (slot in 0 until count) {
                val key = keys[sorted[slot]]
                letters[slot] = folded[sorted[slot]]
                val width = key.right - key.left
                val height = key.bottom - key.top
                centerXs[slot] = (key.left + key.right) / 2.0f
                centerYs[slot] = (key.top + key.bottom) / 2.0f
                halfWidths[slot] = width / 2.0f
                halfHeights[slot] = height / 2.0f
                radius = minOf(radius, minOf(width, height).toFloat())
            }
            if (count == 0) radius = 0f
            return GlideKeyGeometry(letters, centerXs, centerYs, halfWidths, halfHeights, radius)
        }

        /**
         * Folds a raw key code to a single NFC lower-case letter code point, or null when it is
         * not a code point, expands to more than one code point once lower-cased, or is not a
         * letter. The same rule the engine's `KeyNeighborTable` applies (kept private there).
         */
        private fun normalizeLetterCodePoint(codePoint: Int): Int? {
            if (!Character.isValidCodePoint(codePoint)) return null
            val folded = Normalizer
                .normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFC)
                .lowercase()
            if (folded.codePointCount(0, folded.length) != 1) return null
            val normalized = folded.codePointAt(0)
            if (!Character.isLetter(normalized)) return null
            return normalized
        }
    }
}
