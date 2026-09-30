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
 * It holds the long-press partners of edit class #1 (from the layout's `moreKeys`) and the node
 * set that serves as the alphabet of edit class #4.
 *
 * The table carries the [subtypeId] it was built for, so the engine can refuse a typo-recovery
 * pass when it does not match the requesting language. Partners are stored in sorted [IntArray]s
 * searched by primitive binary search, so the hot path never boxes a code point.
 */
class KeyNeighborTable private constructor(
    val subtypeId: String,
    private val partnerKeys: IntArray,
    private val partnerValues: Array<IntArray>,
    /** Every distinct code point that is a node of the table (keys plus more-key-only letters). */
    val nodes: IntArray,
    /** Letter keys actually read from the layout (more-key-only letters not counted). */
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
     * One alphabet key as read from the live layout, before normalization. [moreKeyCodePoints] are
     * the codes of the key's more-keys (long-press partners).
     */
    class RawKey(
        val codePoint: Int,
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
                return KeyNeighborTable(subtypeId, IntArray(0), emptyArray(), IntArray(0), 0)
            }
            val pairs = HashMap<Int, MutableSet<Int>>()
            val nodeSet = sortedSetOf<Int>()
            var letterKeys = 0
            for (key in keys) {
                val base = normalizeLetterCodePoint(key.codePoint) ?: continue
                letterKeys++
                nodeSet.add(base)
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
            return KeyNeighborTable(subtypeId, partnerKeys, partnerValues, nodeSet.toIntArray(), letterKeys)
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
