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
 * coordinates. Built by a pure function from plain [RawKey] records, so it holds no Android type
 * and runs in JVM tests; `latin.suggestions.GlideKeyGeometryBuilder` adapts the live `Keyboard`.
 * Letters are sorted by code point with parallel center/half-size arrays: a lookup is one
 * binary search and the nearest-key scan reads flat arrays, with no boxing or allocation.
 *
 * [keyRadius] is the minimum of min(width, height) over all letter keys: the scale of the
 * location channel's sigma and of the length-pruning threshold. The reference implementation
 * uses the first key; the minimum stays correct on layouts whose rows mix key widths.
 */
class GlideKeyGeometry private constructor(
    private val letters: IntArray,
    private val centerXs: FloatArray,
    private val centerYs: FloatArray,
    private val halfWidths: FloatArray,
    private val halfHeights: FloatArray,
    private val aliasLetters: IntArray,
    private val aliasKeys: IntArray,
    val keyRadius: Float,
) {
    val keyCount: Int get() = letters.size
    val isEmpty: Boolean get() = letters.isEmpty()

    /** Number of alias letters; alias [slot]'s letter and key are [aliasLetterAt] and [aliasKeyAt]. */
    val aliasCount: Int get() = aliasLetters.size

    fun aliasLetterAt(slot: Int): Int = aliasLetters[slot]
    fun aliasKeyAt(slot: Int): Int = aliasKeys[slot]

    /**
     * Key index of [codePoint] (already-normalized letter): its own key, else the base key of an
     * alias, else -1.
     */
    fun keyIndexOfLetter(codePoint: Int): Int {
        val own = Arrays.binarySearch(letters, codePoint)
        if (own >= 0) return own
        val alias = Arrays.binarySearch(aliasLetters, codePoint)
        return if (alias >= 0) aliasKeys[alias] else -1
    }

    fun centerX(keyIndex: Int): Float = centerXs[keyIndex]
    fun centerY(keyIndex: Int): Float = centerYs[keyIndex]
    fun halfWidth(keyIndex: Int): Float = halfWidths[keyIndex]
    fun halfHeight(keyIndex: Int): Float = halfHeights[keyIndex]

    /**
     * True when [other] has the same letters at the same rectangles and the same aliases, so a
     * decoder built for one decodes identically with the other.
     */
    fun sameLayoutAs(other: GlideKeyGeometry): Boolean =
        this === other || (
            keyRadius == other.keyRadius &&
                letters.contentEquals(other.letters) &&
                aliasLetters.contentEquals(other.aliasLetters) &&
                aliasKeys.contentEquals(other.aliasKeys) &&
                centerXs.contentEquals(other.centerXs) &&
                centerYs.contentEquals(other.centerYs) &&
                halfWidths.contentEquals(other.halfWidths) &&
                halfHeights.contentEquals(other.halfHeights)
            )

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

    /**
     * One letter key as read from the live layout: the key's code, its visible rectangle and the
     * codes of its long-press keys (digits and markers included; [build] keeps letters only).
     */
    class RawKey(
        val codePoint: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val moreKeyCodePoints: IntArray = IntArray(0),
    )

    companion object {
        /**
         * The base key of an alias letter that is the long press of more than one key: on the
         * Russian layout `һ` sits on `г` and `х`, `ә` on `а` and `э`. A letter on several keys
         * without an entry here, or whose entry names none of those keys, gets no alias.
         */
        internal val ALIAS_BASES: Map<Int, Int> = mapOf(
            0x04BB to 0x0445, // һ on х
            0x04D9 to 0x0430, // ә on а
        )

        /**
         * Builds the table from the keys of a single keyboard element. Every code is folded to
         * its NFC lower-case form; non-code-point and non-letter keys (shift, delete, space) are
         * dropped. A letter carried by two keys keeps the first rectangle (no shipped layout has
         * one). A normalized long-press letter without a key of its own becomes an alias of its
         * base key; digits and other non-letters never do. An empty input yields an empty
         * geometry (radius 0), for which the decoder returns no candidates.
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
            val (aliasLetters, aliasKeys) = buildAliases(keys, letters)
            return GlideKeyGeometry(
                letters, centerXs, centerYs, halfWidths, halfHeights, aliasLetters, aliasKeys,
                radius,
            )
        }

        /**
         * The sorted alias table: every normalized long-press letter absent from [letters],
         * mapped to the key index of the one key carrying it, or through [ALIAS_BASES] when
         * several keys carry it. Build-time only, so plain collections are fine here.
         */
        private fun buildAliases(keys: List<RawKey>, letters: IntArray): Pair<IntArray, IntArray> {
            val bases = java.util.TreeMap<Int, java.util.TreeSet<Int>>()
            for (key in keys) {
                val baseLetter = normalizeLetterCodePoint(key.codePoint) ?: continue
                val baseKey = Arrays.binarySearch(letters, baseLetter)
                if (baseKey < 0) continue
                for (moreKey in key.moreKeyCodePoints) {
                    val alias = normalizeLetterCodePoint(moreKey) ?: continue
                    if (Arrays.binarySearch(letters, alias) >= 0) continue
                    bases.getOrPut(alias) { java.util.TreeSet() }.add(baseKey)
                }
            }
            val aliasLetters = ArrayList<Int>(bases.size)
            val aliasKeys = ArrayList<Int>(bases.size)
            for ((alias, candidates) in bases) {
                val key = if (candidates.size == 1) {
                    candidates.first()
                } else {
                    val named = ALIAS_BASES[alias] ?: continue
                    val namedKey = Arrays.binarySearch(letters, named)
                    if (namedKey < 0 || namedKey !in candidates) continue
                    namedKey
                }
                aliasLetters.add(alias)
                aliasKeys.add(key)
            }
            return aliasLetters.toIntArray() to aliasKeys.toIntArray()
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
