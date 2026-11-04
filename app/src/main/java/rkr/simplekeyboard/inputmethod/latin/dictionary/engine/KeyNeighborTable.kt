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
 * no letter or key pair is hard-coded here.
 *
 * It holds the long-press partners of edit class #1 (from the layout's `moreKeys`), the node
 * set that serves as the alphabet of edit class #4, and the geometric adjacency of the letter
 * keys (a center-distance rule over the published key rectangles) that feeds the DL-1 rank
 * key's geometry-neighbor bonus — see [FuzzyEditPolicy.geometryNeighborBonus].
 *
 * The table carries the [subtypeId] it was built for, but the engine uses it unconditionally:
 * variant letters of a foreign alphabet match no dictionary blocks, so sharing the active
 * layout's table is safe. Partners are stored in sorted [IntArray]s searched by primitive binary
 * search, so the hot path never boxes a code point.
 */
class KeyNeighborTable private constructor(
    val subtypeId: String,
    private val partnerKeys: IntArray,
    private val partnerValues: Array<IntArray>,
    /** Every distinct code point that is a node of the table (keys plus more-key-only letters). */
    val nodes: IntArray,
    /** Letter keys actually read from the layout (more-key-only letters not counted). */
    val letterKeyCount: Int,
    /** Letters that have at least one geometric neighbor, sorted ascending. */
    private val geoKeys: IntArray,
    /** Geo-neighbor lists parallel to [geoKeys], each sorted ascending. */
    private val geoValues: Array<IntArray>,
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
     * True when the keys of [first] and [second] are geometric neighbors: their centers are
     * within [GEOMETRIC_NEIGHBOR_RADIUS_SQUARED] of each other in per-axis normalized units
     * (see [build]). Only layout letter keys carry a rectangle, so a more-key-only letter (ё, ъ)
     * never joins a geometric pair. Two binary searches, no allocation: safe on the hot path.
     */
    fun areGeometricNeighbors(first: Int, second: Int): Boolean {
        val index = Arrays.binarySearch(geoKeys, first)
        return index >= 0 && Arrays.binarySearch(geoValues[index], second) >= 0
    }

    /**
     * One alphabet key as read from the live layout, before normalization. [moreKeyCodePoints] are
     * the codes of the key's more-keys (long-press partners). [left], [top], [right] and [bottom]
     * are the key's rectangle in the keyboard's coordinates; the all-zero default (a key without
     * geometry) joins no geometric pair.
     */
    class RawKey(
        val codePoint: Int,
        val moreKeyCodePoints: IntArray,
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
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
         *
         * The geometric relation connects two letter keys whose centers are within one radius of
         * each other, measured per axis in units of the pair's summed half-extents:
         * `(dx / (hw1 + hw2))² + (dy / (hh1 + hh2))² <= GEOMETRIC_NEIGHBOR_RADIUS_SQUARED`.
         * Because every axis is normalized by the keys' own extents — themselves percent
         * fractions of the keyboard's width and height — the relation depends only on the layout,
         * never on the device's resolution or aspect. Integer pixel rounding never removes a true
         * pair, but on very small keyboards (about 320 px wide, with small heights) it can let
         * the nearest false pair join. Keys without a real rectangle (the RawKey default) join
         * no pair.
         */
        fun build(
            subtypeId: String,
            isAlphabetElement: Boolean,
            keys: List<RawKey>,
        ): KeyNeighborTable {
            if (!isAlphabetElement) {
                return KeyNeighborTable(
                    subtypeId, IntArray(0), emptyArray(), IntArray(0), 0, IntArray(0), emptyArray(),
                )
            }
            val pairs = HashMap<Int, MutableSet<Int>>()
            val nodeSet = sortedSetOf<Int>()
            var letterKeys = 0
            // The rectangle of each letter key, first declaration winning (a code point appears
            // on at most one key of a shipped layout; the guard only keeps a duplicate from
            // moving the pair's center).
            val rectByLetter = HashMap<Int, IntArray>()
            for (key in keys) {
                val base = normalizeLetterCodePoint(key.codePoint) ?: continue
                letterKeys++
                nodeSet.add(base)
                if (key.right > key.left && key.bottom > key.top && base !in rectByLetter) {
                    rectByLetter[base] = intArrayOf(key.left, key.top, key.right, key.bottom)
                }
                for (rawMoreKey in key.moreKeyCodePoints) {
                    val partner = normalizeLetterCodePoint(rawMoreKey) ?: continue
                    if (partner == base) continue
                    nodeSet.add(partner)
                    // Symmetrize: the layout only stores base -> partner, never the reverse.
                    pairs.getOrPut(base) { HashSet() }.add(partner)
                    pairs.getOrPut(partner) { HashSet() }.add(base)
                }
            }
            val partnerKeys = pairs.keys.toIntArray().also { it.sort() }
            val partnerValues = Array(partnerKeys.size) { index ->
                pairs.getValue(partnerKeys[index]).toIntArray().also { it.sort() }
            }
            val geoLetters = rectByLetter.keys.toIntArray().also { it.sort() }
            val geoPairs = HashMap<Int, MutableSet<Int>>()
            for (firstIndex in geoLetters.indices) {
                val firstRect = rectByLetter.getValue(geoLetters[firstIndex])
                val firstCx = (firstRect[0] + firstRect[2]) / 2.0
                val firstCy = (firstRect[1] + firstRect[3]) / 2.0
                val firstHalfWidth = (firstRect[2] - firstRect[0]) / 2.0
                val firstHalfHeight = (firstRect[3] - firstRect[1]) / 2.0
                for (secondIndex in firstIndex + 1 until geoLetters.size) {
                    val secondRect = rectByLetter.getValue(geoLetters[secondIndex])
                    val secondCx = (secondRect[0] + secondRect[2]) / 2.0
                    val secondCy = (secondRect[1] + secondRect[3]) / 2.0
                    val nx = (firstCx - secondCx) /
                        (firstHalfWidth + (secondRect[2] - secondRect[0]) / 2.0)
                    val ny = (firstCy - secondCy) /
                        (firstHalfHeight + (secondRect[3] - secondRect[1]) / 2.0)
                    if (nx * nx + ny * ny <= GEOMETRIC_NEIGHBOR_RADIUS_SQUARED) {
                        geoPairs.getOrPut(geoLetters[firstIndex]) { HashSet() }
                            .add(geoLetters[secondIndex])
                        geoPairs.getOrPut(geoLetters[secondIndex]) { HashSet() }
                            .add(geoLetters[firstIndex])
                    }
                }
            }
            val geoKeys = geoPairs.keys.toIntArray().also { it.sort() }
            val geoValues = Array(geoKeys.size) { index ->
                geoPairs.getValue(geoKeys[index]).toIntArray().also { it.sort() }
            }
            return KeyNeighborTable(
                subtypeId, partnerKeys, partnerValues, nodeSet.toIntArray(), letterKeys,
                geoKeys, geoValues,
            )
        }

        /**
         * The geometric-neighbor radius, squared, in the per-pair normalized units of [build]
         * (2.07 = 1.439²). The feasibility window over the shipped layouts — the Tatar 5- and
         * 6-row variants and the Russian 4- and 5-row ones — is bounded below by the widest true
         * neighbor at 2.007 (the Tatar extra-row letter two home-row keys over, e.g. ү↔н) and
         * above by the nearest false pair at 2.136 (the same letters one key further apart,
         * e.g. ү↔к), leaving a margin of about 3% on both sides. The same-row neighbors the
         * window also admits (squared distance 1.25–1.56) are the physically closest keys of the
         * layout, exactly the slips the bonus exists for.
         */
        private const val GEOMETRIC_NEIGHBOR_RADIUS_SQUARED = 2.07

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
