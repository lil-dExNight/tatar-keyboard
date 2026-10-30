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
 * once on the engine worker, when a gesture arms or when an unprepared decode needs it, and
 * immutable afterwards. It makes
 * extremity pruning (the two keys nearest the gesture's start x the two nearest its end) a
 * range lookup instead of a dictionary scan.
 *
 * Layout (all flat arrays, no per-word objects):
 *  - [keySeqStarts]/[keySeqPool]: each indexed word's letter sequence as key indices (one
 *    unsigned byte per key), so a survivor's ideal path needs no materialized word;
 *  - [sameLetterAsPrevious]: a bit per key-pool position, set when the letter at that position
 *    is the same letter as at the position before — known only while the word's letters are
 *    still unfolded, so a letter followed by its alias (е then ё, one key) is NOT a doubled
 *    letter; the loop detour fires on this bit, never on key adjacency alone;
 *  - [frequencies]: the word's raw frequency, and [maxFrequency] across indexed words (the
 *    frequency channel's normalizer);
 *  - [plainLengths]/[loopLengths]: the word's ideal-path arc length, plain and with the
 *    doubled-letter loop detour (-1 when the word has no doubled letter) — the length channel
 *    of the pruner is then two float reads per candidate;
 *  - [pairOffsets]/[pairEntries]: CSR buckets keyed by firstKey x lastKey, entry indices in
 *    frequency-descending order with dictionary-order ties;
 *  - [twinless]: a bit per entry, set on a word with a doubled letter whose collapsed key
 *    sequence is no other indexed word, so its plain path belongs to nobody else.
 *
 * Words with a letter that has neither a key nor an alias on the layout (see
 * [GlideKeyGeometry.build]) are not indexed and can never be glide candidates; [skippedWordCount]
 * counts them. [retainedByteEstimate] reports the index's heap size.
 */
class GlideWordIndex private constructor(
    val wordCount: Int,
    val skippedWordCount: Int,
    val maxFrequency: Long,
    private val keyCount: Int,
    private val keySeqStarts: IntArray,
    private val keySeqPool: ByteArray,
    private val sameLetterAsPrevious: LongArray,
    private val frequencies: IntArray,
    private val plainLengths: FloatArray,
    private val loopLengths: FloatArray,
    private val pairOffsets: IntArray,
    private val pairEntries: IntArray,
    private val twinless: LongArray,
) {
    /** Estimated retained heap of the index, in bytes (array headers excluded). */
    val retainedByteEstimate: Long
        get() = keySeqStarts.size * 4L + keySeqPool.size + sameLetterAsPrevious.size * 8L +
            frequencies.size * 4L + plainLengths.size * 4L + loopLengths.size * 4L +
            pairOffsets.size * 4L + pairEntries.size * 4L + twinless.size * 8L

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
    fun keySeqAt(position: Int): Int = keySeqPool[position].toInt() and 0xff

    /**
     * True when the letter at pool [position] is the same letter as at the previous position of
     * its word — the only position where the loop detour fires. Two different letters folded to
     * one key by an alias (е then ё) do not set the bit.
     */
    fun sameLetterAsPreviousAt(position: Int): Boolean =
        (sameLetterAsPrevious[position ushr 6] ushr (position and 63)) and 1L != 0L

    /** Raw frequency of [entry] (schema stores strictly positive u32 values). */
    fun frequencyAt(entry: Int): Long = frequencies[entry].toLong() and 0xffff_ffffL

    /** Plain ideal-path length of [entry] in geometry units. */
    fun plainLengthAt(entry: Int): Float = plainLengths[entry]

    /** Looped ideal-path length of [entry], or -1 when the word has no doubled letter. */
    fun loopLengthAt(entry: Int): Float = loopLengths[entry]

    /**
     * True when [entry] has a doubled letter and no indexed word has its key sequence with every
     * run of one key collapsed to a single visit.
     */
    fun isTwinlessAt(entry: Int): Boolean =
        (twinless[entry ushr 6] ushr (entry and 63)) and 1L != 0L

    companion object {
        /** Most keys a geometry may hold: the pool stores a key index per signed byte. */
        private const val MAX_KEY_COUNT = 127

        /**
         * Builds the index over [inventory] for [geometry]. Two walks of the inventory would
         * halve the transient footprint; the single walk below keeps per-entry records in
         * exact-sized primitive arrays (the entry count is known up front) and grows only the
         * key-sequence pool — simpler and bounded by the dictionary size either way.
         */
        fun build(inventory: GlideWordInventory, geometry: GlideKeyGeometry): GlideWordIndex {
            val entryCount = inventory.entryCount
            val keyCount = geometry.keyCount
            // The cap keeps every key index in the signed byte's positive range, so no pool
            // read can ever leak a sign extension.
            if (keyCount > MAX_KEY_COUNT) {
                throw IllegalArgumentException("glide key geometry has more than 127 keys")
            }
            val keySeqStarts = IntArray(entryCount + 1)
            val frequencies = IntArray(entryCount)
            var pool = ByteArray(entryCount * 4)
            var sameLetter = LongArray((entryCount * 4 ushr 6) + 1)
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
                var previousLetter = -1
                val start = poolSize
                while (i < word.length) {
                    val codePoint = word.codePointAt(i)
                    i += Character.charCount(codePoint)
                    val letter = Character.toLowerCase(codePoint)
                    val key = geometry.keyIndexOfLetter(letter)
                    if (key < 0) {
                        mappable = false
                        break
                    }
                    if (poolSize + 1 > pool.size) {
                        pool = pool.copyOf(pool.size * 2)
                        sameLetter = sameLetter.copyOf((pool.size ushr 6) + 1)
                    }
                    // The doubled-letter bit lives beside the pool position and compares the
                    // LETTERS, before the alias fold maps them to keys: a letter followed by its
                    // alias (е then ё) shares the key but is not a doubled letter. Written
                    // unconditionally, so a rolled-back word's stale bits never leak into the
                    // next word's positions.
                    val bit = 1L shl (poolSize and 63)
                    if (letter == previousLetter) {
                        sameLetter[poolSize ushr 6] = sameLetter[poolSize ushr 6] or bit
                    } else {
                        sameLetter[poolSize ushr 6] = sameLetter[poolSize ushr 6] and bit.inv()
                    }
                    pool[poolSize++] = key.toByte()
                    previousLetter = letter
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
            val sameLetterAsPrevious = sameLetter.copyOf((poolSize + 63) ushr 6)

            // CSR offsets from the pair counts.
            val pairOffsets = IntArray(keyCount * keyCount + 1)
            for (pair in 0 until keyCount * keyCount) {
                pairOffsets[pair + 1] = pairOffsets[pair] + pairCounts[pair]
            }
            // Fill the CSR table in entry order, so every bucket stays in dictionary order,
            // THEN sort each bucket by frequency descending (ties keep the dictionary order):
            // the decode visits candidates best-frequency-first, which lets the fail-fast
            // frequency bound reject most of them unscored (the prune never changes the result;
            // per-candidate scoring dominates the decode cost). The visit order stays
            // deterministic, and the top-N tie-break is by entry index regardless.
            val pairEntries = IntArray(wordCount)
            val cursors = IntArray(keyCount * keyCount)
            for (pair in 0 until keyCount * keyCount) cursors[pair] = pairOffsets[pair]
            val plainLengths = FloatArray(entryCount)
            val loopLengths = FloatArray(entryCount) { -1f }
            for (entry in 0 until entryCount) {
                val start = keySeqStarts[entry]
                val end = keySeqStarts[entry + 1]
                if (end <= start) continue // a skipped (unmappable) word: an empty range
                val firstKey = keySeqPool[start].toInt() and 0xff
                val lastKey = keySeqPool[end - 1].toInt() and 0xff
                pairEntries[cursors[firstKey * keyCount + lastKey]++] = entry
                plainLengths[entry] =
                    idealLength(keySeqPool, sameLetterAsPrevious, start, end, geometry, false)
                val loopLength =
                    idealLength(keySeqPool, sameLetterAsPrevious, start, end, geometry, true)
                if (loopLength >= 0f) loopLengths[entry] = loopLength
            }
            sortPairBuckets(pairEntries, pairOffsets, frequencies)
            val twinless = twinlessBits(keySeqStarts, keySeqPool, loopLengths, entryCount)
            return GlideWordIndex(
                wordCount, skipped, maxFrequency, keyCount, keySeqStarts, keySeqPool,
                sameLetterAsPrevious, frequencies, plainLengths, loopLengths, pairOffsets,
                pairEntries, twinless,
            )
        }

        /** Sorts every bucket by frequency descending, with entry index as the stable tie-break. */
        internal fun sortPairBuckets(
            pairEntries: IntArray,
            pairOffsets: IntArray,
            frequencies: IntArray,
        ) {
            var largestBucket = 0
            for (pair in 0 until pairOffsets.size - 1) {
                largestBucket = maxOf(largestBucket, pairOffsets[pair + 1] - pairOffsets[pair])
            }
            if (largestBucket < 2) return
            val scratch = IntArray(largestBucket)
            for (pair in 0 until pairOffsets.size - 1) {
                stableMergeSort(
                    pairEntries, scratch, frequencies, pairOffsets[pair], pairOffsets[pair + 1],
                )
            }
        }

        private fun stableMergeSort(
            entries: IntArray,
            scratch: IntArray,
            frequencies: IntArray,
            from: Int,
            to: Int,
        ) {
            if (to - from < 2) return
            val middle = (from + to) ushr 1
            stableMergeSort(entries, scratch, frequencies, from, middle)
            stableMergeSort(entries, scratch, frequencies, middle, to)
            if (!precedes(entries[middle], entries[middle - 1], frequencies)) return

            var left = from
            var right = middle
            var output = 0
            while (left < middle && right < to) {
                if (precedes(entries[right], entries[left], frequencies)) {
                    scratch[output++] = entries[right++]
                } else {
                    scratch[output++] = entries[left++]
                }
            }
            while (left < middle) scratch[output++] = entries[left++]
            while (right < to) scratch[output++] = entries[right++]
            for (i in 0 until output) entries[from + i] = scratch[i]
        }

        private fun precedes(left: Int, right: Int, frequencies: IntArray): Boolean =
            frequencies[left] > frequencies[right] ||
                (frequencies[left] == frequencies[right] && left < right)

        /**
         * The twin bit set. Every indexed entry without a doubled letter goes into an
         * open-addressing table keyed by the hash of its key sequence (linear probing, load
         * <= 1/2); every doubled entry then probes with the hash of its collapsed sequence, and a
         * hit counts only after a full comparison. Key sequences rather than strings, so alias
         * twins and personal words count too.
         */
        private fun twinlessBits(
            keySeqStarts: IntArray,
            keySeqPool: ByteArray,
            loopLengths: FloatArray,
            entryCount: Int,
        ): LongArray {
            val bits = LongArray((entryCount + 63) ushr 6)
            var plainCount = 0
            var doubledCount = 0
            for (entry in 0 until entryCount) {
                if (keySeqStarts[entry + 1] <= keySeqStarts[entry]) continue
                if (loopLengths[entry] >= 0f) doubledCount++ else plainCount++
            }
            if (doubledCount == 0) return bits
            var capacity = 16
            while (capacity < plainCount * 2) capacity = capacity shl 1
            val mask = capacity - 1
            val table = IntArray(capacity) { -1 }
            for (entry in 0 until entryCount) {
                val start = keySeqStarts[entry]
                val end = keySeqStarts[entry + 1]
                if (end <= start || loopLengths[entry] >= 0f) continue
                var slot = collapsedHash(keySeqPool, start, end) and mask
                while (table[slot] >= 0) slot = (slot + 1) and mask
                table[slot] = entry
            }
            for (entry in 0 until entryCount) {
                val start = keySeqStarts[entry]
                val end = keySeqStarts[entry + 1]
                if (end <= start || loopLengths[entry] < 0f) continue
                var slot = collapsedHash(keySeqPool, start, end) and mask
                var twin = false
                while (table[slot] >= 0) {
                    val candidate = table[slot]
                    if (collapsedEquals(
                            keySeqPool, start, end,
                            keySeqStarts[candidate], keySeqStarts[candidate + 1],
                        )
                    ) {
                        twin = true
                        break
                    }
                    slot = (slot + 1) and mask
                }
                if (!twin) bits[entry ushr 6] = bits[entry ushr 6] or (1L shl (entry and 63))
            }
            return bits
        }

        /** FNV-1a over the key sequence with every run of one key visited once. */
        private fun collapsedHash(keySeq: ByteArray, start: Int, end: Int): Int {
            var hash = -0x7ee3623b // 0x811C9DC5
            var previous = -1
            for (position in start until end) {
                val key = keySeq[position].toInt() and 0xff
                if (key == previous) continue
                hash = (hash xor key) * 0x01000193
                previous = key
            }
            return hash xor (hash ushr 16)
        }

        /** True when [start, end) collapsed equals the run-free sequence [twinStart, twinEnd). */
        private fun collapsedEquals(
            keySeq: ByteArray,
            start: Int,
            end: Int,
            twinStart: Int,
            twinEnd: Int,
        ): Boolean {
            var twin = twinStart
            var previous = -1
            for (position in start until end) {
                val key = keySeq[position].toInt() and 0xff
                if (key == previous) continue
                if (twin >= twinEnd || (keySeq[twin].toInt() and 0xff) != key) return false
                twin++
                previous = key
            }
            return twin == twinEnd
        }

        /**
         * The ideal-path arc length of one word's key sequence; with [withLoops], every doubled
         * letter contributes its four-point loop detour (returns -1 instead when the word has no
         * doubled letter, so the pruner never matches the same length twice). The detour fires
         * only where [sameLetter] marks the position: two different letters sharing a key through
         * an alias (е then ё) never draw a loop.
         */
        private fun idealLength(
            keySeq: ByteArray,
            sameLetter: LongArray,
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
                val key = keySeq[position].toInt() and 0xff
                val x = geometry.centerX(key)
                val y = geometry.centerY(key)
                if (previousKey >= 0) {
                    if (withLoops &&
                        (sameLetter[position ushr 6] ushr (position and 63)) and 1L != 0L
                    ) {
                        hasDouble = true
                        // The detour: previous point -> 4 loop corners (bottom-right, top-right,
                        // top-left, bottom-left), replacing the doubled letter's center visit, as
                        // in the reference implementation. The path resumes
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
