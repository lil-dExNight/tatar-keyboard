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
 * The per-dictionary glide index: a start/end key-pair index over the inventory's words, built
 * ONCE (lazily, on first decode — the engine worker is a background thread) and immutable
 * afterwards. This is what makes extremity pruning (the two nearest keys to the gesture's start
 * x the two nearest to its end) a range lookup instead of a dictionary scan.
 *
 * Layout (all flat arrays, no per-word objects):
 *  - [keySeqStarts]/[keySeqPool]: each indexed word's letter sequence as key indices, so a
 *    survivor's ideal path is regenerated per decode without materializing the word;
 *  - [frequencies]: the word's raw frequency, and [maxFrequency] across indexed words (the
 *    frequency channel's normalizer);
 *  - [plainLengths]/[loopLengths]: the word's ideal-path arc length, plain and with the
 *    doubled-letter loop detour (-1 when the word has no doubled letter) — the length channel
 *    of the pruner is then two float reads per candidate;
 *  - [pairOffsets]/[pairEntries]: CSR buckets keyed by firstKey x lastKey, entry indices in
 *    dictionary order.
 *
 * Words carrying a letter the layout has no key for (on the Tatar layout: the more-key-only
 * letters) are NOT indexed — they can never be a glide candidate (fail-closed; the skip count
 * is in [skippedWordCount]). The index publishes a retained-bytes estimate for the
 * "measure and document" requirement of the glide plan.
 */
class GlideWordIndex private constructor(
    val wordCount: Int,
    val skippedWordCount: Int,
    val maxFrequency: Long,
    private val keyCount: Int,
    private val keySeqStarts: IntArray,
    private val keySeqPool: ByteArray,
    private val frequencies: IntArray,
    private val plainLengths: FloatArray,
    private val loopLengths: FloatArray,
    private val pairOffsets: IntArray,
    private val pairEntries: IntArray,
) {
    /** Estimated retained heap of the index, in bytes (array headers excluded). */
    val retainedByteEstimate: Long
        get() = keySeqStarts.size * 4L + keySeqPool.size + frequencies.size * 4L +
            plainLengths.size * 4L + loopLengths.size * 4L + pairOffsets.size * 4L +
            pairEntries.size * 4L

    /** Start (inclusive) of the pair bucket's entry range in the CSR table. */
    fun pairRangeStart(startKeyIndex: Int, endKeyIndex: Int): Int =
        pairOffsets[startKeyIndex * keyCount + endKeyIndex]

    /** End (exclusive) of the pair bucket's entry range in the CSR table. */
    fun pairRangeEnd(startKeyIndex: Int, endKeyIndex: Int): Int =
        pairOffsets[startKeyIndex * keyCount + endKeyIndex + 1]

    /** Dictionary entry index at [position] of the CSR table. */
    fun pairEntryAt(position: Int): Int = pairEntries[position]

    /** Start (inclusive) of [entry]'s key sequence in the pool; skipped words have an empty range. */
    fun keySeqStart(entry: Int): Int = keySeqStarts[entry]

    /** End (exclusive) of [entry]'s key sequence in the pool. */
    fun keySeqEnd(entry: Int): Int = keySeqStarts[entry + 1]

    /** The key index at [position] of the key-sequence pool. */
    fun keySeqAt(position: Int): Int = keySeqPool[position].toInt()

    /** Raw frequency of [entry] (schema stores strictly positive u32 values). */
    fun frequencyAt(entry: Int): Long = frequencies[entry].toLong() and 0xffff_ffffL

    /** Plain ideal-path length of [entry] in geometry units. */
    fun plainLengthAt(entry: Int): Float = plainLengths[entry]

    /** Looped ideal-path length of [entry], or -1 when the word has no doubled letter. */
    fun loopLengthAt(entry: Int): Float = loopLengths[entry]

    companion object {
        /**
         * Builds the index over [inventory] for [geometry]. Two walks of the inventory would
         * halve the transient footprint; the single walk below keeps per-entry records in
         * exact-sized primitive arrays (the entry count is known up front) and grows only the
         * key-sequence pool — simpler and bounded by the dictionary size either way.
         */
        fun build(inventory: GlideWordInventory, geometry: GlideKeyGeometry): GlideWordIndex {
            val entryCount = inventory.entryCount
            val keyCount = geometry.keyCount
            val keySeqStarts = IntArray(entryCount + 1)
            val frequencies = IntArray(entryCount)
            var pool = ByteArray(entryCount * 4)
            var poolSize = 0
            var wordCount = 0
            var skipped = 0
            var maxFrequency = 0L
            val pairCounts = IntArray(keyCount * keyCount)
            inventory.forEachWord { word, frequency ->
                val entry = wordCount + skipped
                // The letter sequence as key indices; a word with an unmappable letter is not
                // indexable and gets an EMPTY sequence range (start == end), which no bucket
                // references — the CSR table only ever holds indexed entries.
                var mappable = true
                var firstKey = -1
                var lastKey = -1
                var i = 0
                var letters = 0
                val start = poolSize
                while (i < word.length) {
                    val codePoint = word.codePointAt(i)
                    i += Character.charCount(codePoint)
                    val key = geometry.keyIndexOfLetter(Character.toLowerCase(codePoint))
                    if (key < 0) {
                        mappable = false
                        break
                    }
                    if (poolSize + 1 > pool.size) {
                        pool = pool.copyOf(pool.size * 2)
                    }
                    pool[poolSize++] = key.toByte()
                    if (firstKey < 0) firstKey = key
                    lastKey = key
                    letters++
                }
                if (mappable && letters > 0) {
                    keySeqStarts[entry] = start
                    frequencies[entry] = frequency.toInt()
                    if (frequency > maxFrequency) maxFrequency = frequency
                    pairCounts[firstKey * keyCount + lastKey]++
                    wordCount++
                } else {
                    // Roll back the partial sequence: the empty range marks the skip.
                    poolSize = start
                    keySeqStarts[entry] = start
                    skipped++
                }
            }
            keySeqStarts[entryCount] = poolSize
            val keySeqPool = pool.copyOf(poolSize)

            // CSR offsets from the pair counts.
            val pairOffsets = IntArray(keyCount * keyCount + 1)
            for (pair in 0 until keyCount * keyCount) {
                pairOffsets[pair + 1] = pairOffsets[pair] + pairCounts[pair]
            }
            // Fill the CSR table in entry order, so every bucket stays in dictionary order,
            // THEN sort each bucket by frequency descending (ties keep the dictionary order):
            // the decode visits candidates best-frequency-first, which lets the fail-fast
            // frequency bound reject most of them unscored (a verdict-exact prune — the P7-4
            // device profile showed per-candidate scoring dominates the decode cost). The visit
            // order stays deterministic, and the top-N tie-break is by entry index regardless.
            val pairEntries = IntArray(wordCount)
            val cursors = IntArray(keyCount * keyCount)
            for (pair in 0 until keyCount * keyCount) cursors[pair] = pairOffsets[pair]
            val plainLengths = FloatArray(entryCount)
            val loopLengths = FloatArray(entryCount) { -1f }
            for (entry in 0 until entryCount) {
                val start = keySeqStarts[entry]
                val end = keySeqStarts[entry + 1]
                if (end <= start) continue // a skipped (unmappable) word: an empty range
                val firstKey = keySeqPool[start].toInt()
                val lastKey = keySeqPool[end - 1].toInt()
                pairEntries[cursors[firstKey * keyCount + lastKey]++] = entry
                plainLengths[entry] = idealLength(keySeqPool, start, end, geometry, false)
                val loopLength = idealLength(keySeqPool, start, end, geometry, true)
                if (loopLength >= 0f) loopLengths[entry] = loopLength
            }
            // Insertion sort within each bucket: buckets average ~80 entries and the pass is
            // once per dictionary, off every hot path.
            for (pair in 0 until keyCount * keyCount) {
                val from = pairOffsets[pair]
                val to = pairOffsets[pair + 1]
                for (i in from + 1 until to) {
                    val entry = pairEntries[i]
                    val frequency = frequencies[entry]
                    var j = i - 1
                    while (j >= from &&
                        (frequencies[pairEntries[j]] < frequency ||
                            (frequencies[pairEntries[j]] == frequency && pairEntries[j] > entry))
                    ) {
                        pairEntries[j + 1] = pairEntries[j]
                        j--
                    }
                    pairEntries[j + 1] = entry
                }
            }
            return GlideWordIndex(
                wordCount, skipped, maxFrequency, keyCount, keySeqStarts, keySeqPool,
                frequencies, plainLengths, loopLengths, pairOffsets, pairEntries,
            )
        }

        /**
         * The ideal-path arc length of one word's key sequence; with [withLoops], every doubled
         * letter contributes its four-point loop detour (returns -1 instead when the word has no
         * doubled letter, so the pruner never matches the same length twice).
         */
        private fun idealLength(
            keySeq: ByteArray,
            start: Int,
            end: Int,
            geometry: GlideKeyGeometry,
            withLoops: Boolean,
        ): Float {
            var total = 0f
            var hasDouble = false
            var previousKey = -1
            var previousX = 0f
            var previousY = 0f
            for (position in start until end) {
                val key = keySeq[position].toInt()
                val x = geometry.centerX(key)
                val y = geometry.centerY(key)
                if (previousKey >= 0) {
                    if (withLoops && key == previousKey) {
                        hasDouble = true
                        // The detour: previous point -> 4 loop corners (bottom-right, top-right,
                        // top-left, bottom-left), replacing the doubled letter's center visit —
                        // the reference implementation's "pool/poll trick". The path resumes
                        // from the last corner, so a following letter's segment starts there.
                        val dx = geometry.halfWidth(key) / 2
                        val dy = geometry.halfHeight(key) / 2
                        val x0 = x + dx
                        val y0 = y + dy
                        total += distance(previousX, previousY, x0, y0)
                        total += dy + dy // BR -> TR
                        total += dx + dx // TR -> TL
                        total += dy + dy // TL -> BL
                        previousX = x - dx
                        previousY = y + dy
                    } else {
                        total += distance(previousX, previousY, x, y)
                        previousX = x
                        previousY = y
                    }
                } else {
                    previousX = x
                    previousY = y
                }
                previousKey = key
            }
            return if (withLoops && !hasDouble) -1f else total
        }

        private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            return sqrt(dx * dx + dy * dy)
        }
    }
}
