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

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import java.text.Normalizer
import java.util.Arrays

/**
 * Immutable key-adjacency table derived from the live keyboard layout.
 *
 * Built by a pure function ([build]) from a plain list of [RawKey]s, so it carries no Android type
 * and runs in plain JVM tests. The Android side reads the keyboard (`Keyboard.getSortedKeys()`,
 * `Key.getMoreKeys()`) in [rkr.simplekeyboard.inputmethod.latin.suggestions.KeyNeighborTableBuilder];
 * no letter, key pair or coordinate is hard-coded here.
 *
 * Two relations: edit class #1, long-press partners from the layout's `moreKeys`; edit class #2,
 * geometric neighbors derived from the key rectangles.
 *
 * The table carries the [subtypeId] it was built for, so the engine can refuse a typo-recovery
 * pass when it does not match the requesting language. Partners are stored in sorted [IntArray]s
 * searched by primitive binary search, so the hot path never boxes a code point.
 */
class KeyNeighborTable private constructor(
    val subtypeId: String,
    private val partnerKeys: IntArray,
    private val partnerValues: Array<IntArray>,
    private val geometricKeys: IntArray,
    private val geometricValues: Array<IntArray>,
    /** Every distinct code point that is a node of the table (keys plus more-key-only letters). */
    val nodes: IntArray,
    /** Letter keys with geometry actually read from the layout. */
    val letterKeyCount: Int,
) {

    val isEmpty: Boolean get() = nodes.isEmpty()

    /**
     * Long-press partners of [codePoint] (edit class #1), or null when it has none. The array is
     * shared and must not be mutated: it is read on the hot lookup path.
     */
    fun longPressPartnersOf(codePoint: Int): IntArray? {
        val index = Arrays.binarySearch(partnerKeys, codePoint)
        return if (index >= 0) partnerValues[index] else null
    }

    /**
     * Geometric neighbors of [codePoint] (edit class #2, see the rule in [build]), or null when it
     * has none. The array is shared and must not be mutated.
     */
    fun geometricNeighborsOf(codePoint: Int): IntArray? {
        val index = Arrays.binarySearch(geometricKeys, codePoint)
        return if (index >= 0) geometricValues[index] else null
    }

    /**
     * One alphabet key as read from the live layout, before normalization. [moreKeyCodePoints] are
     * the codes of the key's more-keys (long-press partners); the rectangle is in pixels.
     */
    class RawKey(
        val codePoint: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val moreKeyCodePoints: IntArray,
    )

    companion object {
        /**
         * Builds the table from the keys of one keyboard element.
         *
         * The table is empty unless [isAlphabetElement] is true: on the shifted element the codes
         * are upper case and on symbols they are not letters, so only the alphabet element
         * (`KeyboardId.isAlphabetKeyboard()`) is a valid source.
         *
         * Every code is folded to NFC lower case; non-letter keys are dropped. Long-press pairs in
         * the layout are one-directional and duplicated (one diacritic letter is declared on two
         * base keys, with no back link), so the map is symmetrized and de-duplicated; otherwise
         * half the diacritic pairs would never fire.
         */
        fun build(
            subtypeId: String,
            isAlphabetElement: Boolean,
            keys: List<RawKey>,
        ): KeyNeighborTable {
            if (!isAlphabetElement) {
                return KeyNeighborTable(
                    subtypeId, IntArray(0), emptyArray(), IntArray(0), emptyArray(), IntArray(0), 0,
                )
            }
            val pairs = HashMap<Int, MutableSet<Int>>()
            val nodeSet = sortedSetOf<Int>()
            var letterKeys = 0
            // Parallel geometry records for the letter keys, in encounter order. Only keys that fold
            // to a letter get a record; more-key-only letters have no geometry and take part in
            // edit class #1 only.
            val geomCode = ArrayList<Int>(keys.size)
            val geomLeft = ArrayList<Int>(keys.size)
            val geomTop = ArrayList<Int>(keys.size)
            val geomRight = ArrayList<Int>(keys.size)
            for (key in keys) {
                val base = normalizeLetterCodePoint(key.codePoint) ?: continue
                letterKeys++
                nodeSet.add(base)
                geomCode.add(base)
                geomLeft.add(key.left)
                geomTop.add(key.top)
                geomRight.add(key.right)
                for (rawMoreKey in key.moreKeyCodePoints) {
                    val partner = normalizeLetterCodePoint(rawMoreKey) ?: continue
                    if (partner == base) continue
                    nodeSet.add(partner)
                    // Symmetrize: the layout only stores base -> partner, never the reverse.
                    pairs.getOrPut(base) { HashSet() }.add(partner)
                    pairs.getOrPut(partner) { HashSet() }.add(base)
                }
            }
            val geoPairs = computeGeometricPairs(geomCode, geomLeft, geomTop, geomRight)
            val partnerKeys = pairs.keys.toIntArray().also { it.sort() }
            val partnerValues = Array(partnerKeys.size) { index ->
                pairs.getValue(partnerKeys[index]).toIntArray().also { it.sort() }
            }
            val geometricKeys = geoPairs.keys.toIntArray().also { it.sort() }
            val geometricValues = Array(geometricKeys.size) { index ->
                geoPairs.getValue(geometricKeys[index]).toIntArray().also { it.sort() }
            }
            return KeyNeighborTable(
                subtypeId, partnerKeys, partnerValues, geometricKeys, geometricValues,
                nodeSet.toIntArray(), letterKeys,
            )
        }

        /**
         * Edit class #2 relation: keys of the same row that touch horizontally, plus keys of an
         * adjacent row whose horizontal overlap is more than 35% of the narrower key's width.
         *
         * "Same row" is the same top coordinate; "adjacent row" is a difference of exactly one in
         * the rank of the distinct top coordinates, so a missing row cannot make non-adjacent rows
         * neighbors. "Touch horizontally" is a shared vertical edge (`right == left`), so two
         * coincident rectangles (the degenerate geometry of the class #1 test fixtures) are not
         * neighbors. The 35% check is integer arithmetic (`100*overlap > 35*minWidth`), identical on
         * every host and reproducible by the offline model.
         */
        private fun computeGeometricPairs(
            code: List<Int>,
            left: List<Int>,
            top: List<Int>,
            right: List<Int>,
        ): HashMap<Int, MutableSet<Int>> {
            val geoPairs = HashMap<Int, MutableSet<Int>>()
            val distinctTops = top.toSortedSet().toIntArray()
            val size = code.size
            for (i in 0 until size) {
                val rankI = Arrays.binarySearch(distinctTops, top[i])
                for (j in i + 1 until size) {
                    val ci = code[i]
                    val cj = code[j]
                    if (ci == cj) continue
                    val rankJ = Arrays.binarySearch(distinctTops, top[j])
                    val connected = when {
                        rankI == rankJ ->
                            right[i] == left[j] || right[j] == left[i]
                        rankI == rankJ + 1 || rankJ == rankI + 1 -> {
                            val overlap = minOf(right[i], right[j]) - maxOf(left[i], left[j])
                            if (overlap <= 0) {
                                false
                            } else {
                                val widthI = right[i] - left[i]
                                val widthJ = right[j] - left[j]
                                val minWidth = minOf(widthI, widthJ)
                                100L * overlap > 35L * minWidth
                            }
                        }
                        else -> false
                    }
                    if (connected) {
                        geoPairs.getOrPut(ci) { HashSet() }.add(cj)
                        geoPairs.getOrPut(cj) { HashSet() }.add(ci)
                    }
                }
            }
            return geoPairs
        }

        /**
         * Folds a raw key code to a single NFC lower-case letter code point, or null when it is not
         * a code point, expands to more than one code point once lower-cased, or is not a letter.
         */
        private fun normalizeLetterCodePoint(codePoint: Int): Int? {
            if (!Character.isValidCodePoint(codePoint)) return null
            val folded = Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFC)
                .lowercase()
            if (folded.codePointCount(0, folded.length) != 1) return null
            val normalized = folded.codePointAt(0)
            if (!Character.isLetter(normalized)) return null
            return normalized
        }
    }
}
