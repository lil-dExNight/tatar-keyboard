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

/**
 * Counts Unicode code points in the first [length] bytes of a UTF-8 [bytes] buffer by counting the
 * lead bytes (every byte outside the continuation range `0x80..0xBF`).
 *
 * The engine works in UTF-8, where Tatar Cyrillic occupies two bytes per letter, so a naive
 * `byteCount >= 3` test would misjudge a two-letter Cyrillic prefix. This counts code points
 * directly off the bytes without decoding into a String and without allocating.
 */
internal fun countCodePointsByLeadBytes(bytes: ByteArray, length: Int): Int {
    var count = 0
    for (index in 0 until length) {
        val value = bytes[index].toInt() and 0xff
        if (value < 0x80 || value > 0xbf) count++
    }
    return count
}

/**
 * Pure generator of typo-recovery prefix variants. It works on Unicode code points and re-encodes
 * each variant to UTF-8, because a byte-level edit of a two-byte Cyrillic letter would produce
 * invalid UTF-8 and an empty block scan.
 *
 * Nothing is allocated per variant: the caller supplies reusable scratch buffers and receives each
 * variant through [VariantConsumer] as `(bytes, length)`.
 */
internal object FuzzyPrefixVariants {
    /** Receives each generated variant as UTF-8 bytes in a shared scratch buffer plus its length. */
    fun interface VariantConsumer {
        fun onVariant(variantUtf8: ByteArray, length: Int)
    }

    /**
     * VariantConsumer that also receives the substituted position. Only the class #4 generator
     * uses it: the probe path narrows its search by the typed word's prefix up to that position.
     */
    fun interface PositionedVariantConsumer {
        fun onVariant(position: Int, variantUtf8: ByteArray, length: Int)
    }

    /**
     * Edit class #1: replace one letter with a long-press partner, in every position that has one.
     *
     * Decodes the first [prefixLength] bytes of [prefixUtf8] into [codePointScratch], then for every
     * position whose code point has long-press partners in [table] emits one variant per partner —
     * the prefix with that single position replaced, re-encoded to UTF-8 into [variantScratch].
     *
     * Returns the number of variants emitted, or -1 when the prefix could not be decoded or when
     * [maxVariants] would be exceeded. On -1 the caller drops the whole typo-recovery level rather
     * than keep a partial set.
     */
    fun generateLongPressVariants(
        prefixUtf8: ByteArray,
        prefixLength: Int,
        table: KeyNeighborTable,
        codePointScratch: IntArray,
        variantScratch: ByteArray,
        maxVariants: Int,
        consumer: VariantConsumer,
    ): Int = generateSubstitutionVariants(
        prefixUtf8, prefixLength, table, codePointScratch, variantScratch, maxVariants, consumer,
        geometric = false,
    )

    /**
     * Edit class #2: replace one letter with a geometric keyboard neighbor, in every position that
     * has one. Same contract as [generateLongPressVariants], drawing on
     * [KeyNeighborTable.geometricNeighborsOf] instead of the long-press partners.
     */
    fun generateGeometricVariants(
        prefixUtf8: ByteArray,
        prefixLength: Int,
        table: KeyNeighborTable,
        codePointScratch: IntArray,
        variantScratch: ByteArray,
        maxVariants: Int,
        consumer: VariantConsumer,
    ): Int = generateSubstitutionVariants(
        prefixUtf8, prefixLength, table, codePointScratch, variantScratch, maxVariants, consumer,
        geometric = true,
    )

    /**
     * Edit class #3: swap two adjacent letters, in every adjacent pair of the prefix. Swaps of two
     * identical code points are skipped (they reproduce the prefix). Returns the variant count, or
     * -1 on undecodable input or when [maxVariants] would be exceeded.
     */
    fun generateTranspositionVariants(
        prefixUtf8: ByteArray,
        prefixLength: Int,
        codePointScratch: IntArray,
        variantScratch: ByteArray,
        maxVariants: Int,
        consumer: VariantConsumer,
    ): Int {
        val codePointCount = decodeCodePoints(prefixUtf8, prefixLength, codePointScratch)
        if (codePointCount < 0) return -1
        var emitted = 0
        for (position in 0 until codePointCount - 1) {
            val first = codePointScratch[position]
            val second = codePointScratch[position + 1]
            if (first == second) continue
            if (emitted >= maxVariants) return -1
            codePointScratch[position] = second
            codePointScratch[position + 1] = first
            val length = encodeCodePoints(codePointScratch, codePointCount, variantScratch)
            consumer.onVariant(variantScratch, length)
            // Restore the pair before moving on, so exactly one adjacent swap differs per variant.
            codePointScratch[position] = first
            codePointScratch[position + 1] = second
            emitted++
        }
        return emitted
    }

    /**
     * Edit class #4: replace one letter with every letter of the layout's alphabet, in every
     * position (full single substitution). [alphabet] is the layout-derived node set
     * (`KeyNeighborTable.nodes`), never a hard-coded list. Order: position ascending, then code
     * point ascending; the position's own letter is skipped.
     *
     * Probe-first: it emits n × alphabet candidates, and the consumer answers each with a cheap
     * existence probe (binary search, no range scan) and range-scans only the survivors. So
     * [maxVariants] bounds the probes, not the survivors; the survivor cap is the caller's
     * (TdictPrefixIndex.MAX_FUZZY_VARIANTS).
     *
     * Returns the number of variants emitted, or -1 when the prefix could not be decoded or when
     * [maxVariants] would be exceeded (the caller then drops the whole typo-recovery level).
     */
    fun generateFullSubstitutionVariants(
        prefixUtf8: ByteArray,
        prefixLength: Int,
        alphabet: IntArray,
        codePointScratch: IntArray,
        variantScratch: ByteArray,
        maxVariants: Int,
        consumer: PositionedVariantConsumer,
    ): Int {
        val codePointCount = decodeCodePoints(prefixUtf8, prefixLength, codePointScratch)
        if (codePointCount < 0) return -1
        var emitted = 0
        for (position in 0 until codePointCount) {
            val original = codePointScratch[position]
            for (letter in alphabet) {
                if (letter == original) continue
                if (emitted >= maxVariants) {
                    codePointScratch[position] = original
                    return -1
                }
                codePointScratch[position] = letter
                val length = encodeCodePoints(codePointScratch, codePointCount, variantScratch)
                consumer.onVariant(position, variantScratch, length)
                emitted++
            }
            // Restore this position before moving on, so exactly one letter differs per variant.
            codePointScratch[position] = original
        }
        return emitted
    }

    /** Shared body of the two substitution classes; [geometric] picks the neighbor source. */
    private fun generateSubstitutionVariants(
        prefixUtf8: ByteArray,
        prefixLength: Int,
        table: KeyNeighborTable,
        codePointScratch: IntArray,
        variantScratch: ByteArray,
        maxVariants: Int,
        consumer: VariantConsumer,
        geometric: Boolean,
    ): Int {
        val codePointCount = decodeCodePoints(prefixUtf8, prefixLength, codePointScratch)
        if (codePointCount < 0) return -1
        var emitted = 0
        for (position in 0 until codePointCount) {
            val original = codePointScratch[position]
            val partners =
                if (geometric) table.geometricNeighborsOf(original) else table.longPressPartnersOf(original)
            if (partners == null) continue
            for (partner in partners) {
                if (emitted >= maxVariants) {
                    codePointScratch[position] = original
                    return -1
                }
                codePointScratch[position] = partner
                val length = encodeCodePoints(codePointScratch, codePointCount, variantScratch)
                consumer.onVariant(variantScratch, length)
                emitted++
            }
            // Restore this position before moving on, so exactly one letter differs per variant.
            codePointScratch[position] = original
        }
        return emitted
    }

    /** Scalar UTF-8 decode into [out]; returns the code-point count, or -1 on malformed input. */
    private fun decodeCodePoints(bytes: ByteArray, length: Int, out: IntArray): Int {
        var index = 0
        var count = 0
        while (index < length) {
            val first = bytes[index].toInt() and 0xff
            val leading: Int
            val continuationCount: Int
            when {
                first <= 0x7f -> {
                    leading = first
                    continuationCount = 0
                }
                first in 0xc2..0xdf -> {
                    leading = first and 0x1f
                    continuationCount = 1
                }
                first in 0xe0..0xef -> {
                    leading = first and 0x0f
                    continuationCount = 2
                }
                first in 0xf0..0xf4 -> {
                    leading = first and 0x07
                    continuationCount = 3
                }
                else -> return -1
            }
            if (index + continuationCount >= length) return -1
            var value = leading
            for (offset in 1..continuationCount) {
                val continuation = bytes[index + offset].toInt() and 0xff
                if (continuation < 0x80 || continuation > 0xbf) return -1
                value = (value shl 6) or (continuation and 0x3f)
            }
            if (count >= out.size) return -1
            out[count++] = value
            index += continuationCount + 1
        }
        return count
    }

    /** Encodes the first [count] code points of [codePoints] into [out] as UTF-8; returns length. */
    private fun encodeCodePoints(codePoints: IntArray, count: Int, out: ByteArray): Int {
        var position = 0
        for (index in 0 until count) {
            val codePoint = codePoints[index]
            when {
                codePoint <= 0x7f -> {
                    out[position++] = codePoint.toByte()
                }
                codePoint <= 0x7ff -> {
                    out[position++] = (0xc0 or (codePoint shr 6)).toByte()
                    out[position++] = (0x80 or (codePoint and 0x3f)).toByte()
                }
                codePoint <= 0xffff -> {
                    out[position++] = (0xe0 or (codePoint shr 12)).toByte()
                    out[position++] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
                    out[position++] = (0x80 or (codePoint and 0x3f)).toByte()
                }
                else -> {
                    out[position++] = (0xf0 or (codePoint shr 18)).toByte()
                    out[position++] = (0x80 or ((codePoint shr 12) and 0x3f)).toByte()
                    out[position++] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
                    out[position++] = (0x80 or (codePoint and 0x3f)).toByte()
                }
            }
        }
        return position
    }
}
