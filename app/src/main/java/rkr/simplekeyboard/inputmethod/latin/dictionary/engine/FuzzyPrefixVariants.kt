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

/** Maps a code point to its chat substitution, or to itself when no substitution is defined. */
fun interface ChatSubstitutionTable {
    fun replacementOf(codePoint: Int): Int
}

/**
 * Pure generator of typo-recovery prefix variants. It works on Unicode code points and re-encodes
 * each variant to UTF-8, because a byte-level edit of a two-byte Cyrillic letter would produce
 * invalid UTF-8 and an empty block scan.
 *
 * The families: edit class #1 (long-press partners) and the DL-1 tier — full substitution,
 * deletion, insertion and transposition — whose generators report the edited position, because
 * the probe path narrows its search by the typed prefix up to that position.
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
     * VariantConsumer that also receives the edited position. The DL-1 tier generators use it:
     * the probe path narrows its search by the typed word's prefix up to that position.
     */
    fun interface PositionedVariantConsumer {
        fun onVariant(position: Int, variantUtf8: ByteArray, length: Int)
    }

    /**
     * VariantConsumer of the substitution family: on top of the edited position it receives the
     * typed code point and the letter replacing it, so the engine can tag a geometric-neighbor
     * substitution in the rank key (see FuzzyEditPolicy.geometryNeighborBonus).
     */
    fun interface SubstitutionVariantConsumer {
        fun onVariant(
            position: Int,
            typedCodePoint: Int,
            replacementCodePoint: Int,
            variantUtf8: ByteArray,
            length: Int,
        )
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
    ): Int {
        val codePointCount = decodeCodePoints(prefixUtf8, prefixLength, codePointScratch)
        if (codePointCount < 0) return -1
        var emitted = 0
        for (position in 0 until codePointCount) {
            val original = codePointScratch[position]
            val partners = table.longPressPartnersOf(original) ?: continue
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
        consumer: SubstitutionVariantConsumer,
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
                consumer.onVariant(position, original, letter, variantScratch, length)
                emitted++
            }
            // Restore this position before moving on, so exactly one letter differs per variant.
            codePointScratch[position] = original
        }
        return emitted
    }

    /**
     * Emits every subset of at least two chat substitutions when there are at most six eligible
     * positions, in ascending subset-mask order. With more eligible positions, emits only the
     * variant that substitutes all of them. The reported position is the first substituted index.
     *
     * Returns the emitted count, or -1 on malformed input, short scratch, output overflow, or
     * budget overflow. [codePointScratch] is restored to the decoded prefix before every return.
     */
    fun generateChatSubstitutionVariants(
        prefixUtf8: ByteArray,
        prefixLength: Int,
        table: ChatSubstitutionTable,
        codePointScratch: IntArray,
        variantScratch: ByteArray,
        eligiblePositionScratch: IntArray,
        originalCodePointScratch: IntArray,
        maxVariants: Int,
        consumer: PositionedVariantConsumer,
    ): Int {
        if (prefixLength < 0 || prefixLength > prefixUtf8.size || maxVariants < 0) return -1
        val codePointCount = decodeCodePoints(prefixUtf8, prefixLength, codePointScratch)
        if (codePointCount < 0) return -1
        if (eligiblePositionScratch.size < codePointCount ||
            originalCodePointScratch.size < codePointCount
        ) {
            return -1
        }
        for (position in 0 until codePointCount) {
            originalCodePointScratch[position] = codePointScratch[position]
        }
        try {
            var eligibleCount = 0
            for (position in 0 until codePointCount) {
                val original = originalCodePointScratch[position]
                val replacement = table.replacementOf(original)
                if (replacement == original) continue
                if (utf8Length(replacement) < 0) return -1
                eligiblePositionScratch[eligibleCount++] = position
                codePointScratch[position] = replacement
            }
            if (eligibleCount <= 1) return 0
            if (eligibleCount <= 6) {
                val subsetLimit = 1 shl eligibleCount
                val variantCount = subsetLimit - eligibleCount - 1
                if (variantCount > maxVariants) return -1
                var emitted = 0
                for (mask in 1 until subsetLimit) {
                    if (Integer.bitCount(mask) < 2) continue
                    val length = encodeChatSubstitutionVariant(
                        codePointScratch,
                        originalCodePointScratch,
                        eligiblePositionScratch,
                        codePointCount,
                        eligibleCount,
                        mask,
                        variantScratch,
                    )
                    if (length < 0) return -1
                    val first = Integer.numberOfTrailingZeros(mask)
                    consumer.onVariant(eligiblePositionScratch[first], variantScratch, length)
                    emitted++
                }
                return emitted
            }
            if (maxVariants < 1) return -1
            val length = encodeChatSubstitutionVariant(
                codePointScratch,
                originalCodePointScratch,
                eligiblePositionScratch,
                codePointCount,
                eligibleCount,
                -1,
                variantScratch,
            )
            if (length < 0) return -1
            consumer.onVariant(eligiblePositionScratch[0], variantScratch, length)
            return 1
        } finally {
            for (position in 0 until codePointCount) {
                codePointScratch[position] = originalCodePointScratch[position]
            }
        }
    }

    /**
     * DL-1 tier, deletion family: remove one code point, in every position (n variants).
     * Position order ascending. The variant at position p shares the typed prefix's first p
     * code points, so the consumer can probe within the p-narrowed range.
     *
     * [codePointScratch] holds the decoded prefix and is restored before every position, so the
     * scratch always carries the typed sequence between variants. Returns the number of variants
     * emitted, or -1 on malformed input or budget overflow (the caller drops the whole level).
     */
    fun generateDeletionVariants(
        prefixUtf8: ByteArray,
        prefixLength: Int,
        codePointScratch: IntArray,
        variantScratch: ByteArray,
        maxVariants: Int,
        consumer: PositionedVariantConsumer,
    ): Int {
        val codePointCount = decodeCodePoints(prefixUtf8, prefixLength, codePointScratch)
        if (codePointCount < 0) return -1
        var emitted = 0
        for (position in 0 until codePointCount) {
            if (emitted >= maxVariants) return -1
            val removed = codePointScratch[position]
            for (index in position + 1 until codePointCount) {
                codePointScratch[index - 1] = codePointScratch[index]
            }
            val length = encodeCodePoints(codePointScratch, codePointCount - 1, variantScratch)
            consumer.onVariant(position, variantScratch, length)
            emitted++
            for (index in codePointCount - 1 downTo position + 1) {
                codePointScratch[index] = codePointScratch[index - 1]
            }
            codePointScratch[position] = removed
        }
        return emitted
    }

    /**
     * DL-1 tier, insertion family: insert every letter of the layout's alphabet at every gap
     * ((n + 1) x alphabet variants, position ascending, then the alphabet's order). [alphabet] is
     * the layout-derived node set (`KeyNeighborTable.nodes`), never a hard-coded list. The end
     * position p = n shares the whole typed prefix, so the caller's probe ranges must run to
     * index n. [codePointScratch] must hold n + 1 code points.
     *
     * Returns the number of variants emitted, or -1 on malformed input, a short scratch, or
     * budget overflow (the caller drops the whole level).
     */
    fun generateInsertionVariants(
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
        if (codePointCount + 1 > codePointScratch.size) return -1
        var emitted = 0
        for (position in 0..codePointCount) {
            // Open slot [position]: the tail moves one code point right.
            for (index in codePointCount - 1 downTo position) {
                codePointScratch[index + 1] = codePointScratch[index]
            }
            for (letter in alphabet) {
                if (emitted >= maxVariants) {
                    for (index in position until codePointCount) {
                        codePointScratch[index] = codePointScratch[index + 1]
                    }
                    return -1
                }
                codePointScratch[position] = letter
                val length = encodeCodePoints(codePointScratch, codePointCount + 1, variantScratch)
                consumer.onVariant(position, variantScratch, length)
                emitted++
            }
            // Close the slot: the next position starts from the decoded prefix again.
            for (index in position until codePointCount) {
                codePointScratch[index] = codePointScratch[index + 1]
            }
        }
        return emitted
    }

    /**
     * DL-1 tier, transposition family: swap each pair of adjacent code points (at most n - 1
     * variants, pivot ascending). A swap of equal letters reproduces the typed prefix and is not
     * emitted. The variant at pivot p shares the typed prefix's first p code points, so the
     * consumer can probe within the p-narrowed range.
     *
     * Returns the number of variants emitted, or -1 on malformed input or budget overflow (the
     * caller drops the whole level).
     */
    fun generateTranspositionVariants(
        prefixUtf8: ByteArray,
        prefixLength: Int,
        codePointScratch: IntArray,
        variantScratch: ByteArray,
        maxVariants: Int,
        consumer: PositionedVariantConsumer,
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
            consumer.onVariant(position, variantScratch, length)
            emitted++
            codePointScratch[position] = first
            codePointScratch[position + 1] = second
        }
        return emitted
    }

    /** Encodes one selected subset without changing either code-point buffer. */
    private fun encodeChatSubstitutionVariant(
        replacements: IntArray,
        originals: IntArray,
        eligiblePositions: IntArray,
        codePointCount: Int,
        eligibleCount: Int,
        substitutionMask: Int,
        out: ByteArray,
    ): Int {
        var outputPosition = 0
        var eligibleIndex = 0
        for (position in 0 until codePointCount) {
            var codePoint = originals[position]
            if (eligibleIndex < eligibleCount && eligiblePositions[eligibleIndex] == position) {
                if (substitutionMask < 0 || (substitutionMask and (1 shl eligibleIndex)) != 0) {
                    codePoint = replacements[position]
                }
                eligibleIndex++
            }
            val byteCount = utf8Length(codePoint)
            if (byteCount < 0 || outputPosition > out.size - byteCount) return -1
            when (byteCount) {
                1 -> {
                    out[outputPosition] = codePoint.toByte()
                }
                2 -> {
                    out[outputPosition] = (0xc0 or (codePoint shr 6)).toByte()
                    out[outputPosition + 1] = (0x80 or (codePoint and 0x3f)).toByte()
                }
                3 -> {
                    out[outputPosition] = (0xe0 or (codePoint shr 12)).toByte()
                    out[outputPosition + 1] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
                    out[outputPosition + 2] = (0x80 or (codePoint and 0x3f)).toByte()
                }
                4 -> {
                    out[outputPosition] = (0xf0 or (codePoint shr 18)).toByte()
                    out[outputPosition + 1] = (0x80 or ((codePoint shr 12) and 0x3f)).toByte()
                    out[outputPosition + 2] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
                    out[outputPosition + 3] = (0x80 or (codePoint and 0x3f)).toByte()
                }
            }
            outputPosition += byteCount
        }
        return outputPosition
    }

    private fun utf8Length(codePoint: Int): Int = when (codePoint) {
        in 0..0x7f -> 1
        in 0x80..0x7ff -> 2
        in 0x800..0xd7ff -> 3
        in 0xe000..0xffff -> 3
        in 0x10000..0x10ffff -> 4
        else -> -1
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
            val minimum = when (continuationCount) {
                0 -> 0
                1 -> 0x80
                2 -> 0x800
                else -> 0x10000
            }
            if (value < minimum || value > 0x10ffff || value in 0xd800..0xdfff) return -1
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
