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

/**
 * The UTF-8 codec of the glide index's byte-level word walk. It lets [GlideWordIndex.build]
 * consume a dictionary's stored bytes directly, without materializing a [String] per entry.
 *
 * Decode follows the well-formed UTF-8 table (Unicode, Table 3-7): overlong sequences,
 * surrogate encodings, stray continuation bytes and truncated sequences are all malformed.
 * A malformed sequence decodes to U+FFFD and advances ONE byte. This matches the observable
 * behavior of the old String-based build for every input: the String decoder also replaces
 * malformed input with U+FFFD, and U+FFFD maps to no key, so a malformed word is skipped in
 * both worlds. Well-formed input decodes to exactly the code points `String.codePointAt`
 * yields, so index contents are bit-identical.
 */
internal object GlideUtf8 {

    /**
     * Decodes the code point starting at [offset] (exclusive upper bound [limit]).
     * Returns `(byteCount.toLong() shl 32) or codePoint`; the code point is U+FFFD with a byte
     * count of 1 for a malformed sequence. Never allocates.
     */
    fun nextCodePoint(bytes: ByteArray, offset: Int, limit: Int): Long {
        val b0 = bytes[offset].toInt() and 0xff
        if (b0 < 0x80) return pack(1, b0)
        if (b0 < 0xc2) return pack(1, REPLACEMENT) // continuation byte or overlong lead C0/C1
        if (b0 < 0xe0) {
            if (offset + 1 >= limit) return pack(1, REPLACEMENT)
            val b1 = bytes[offset + 1].toInt() and 0xff
            if (!isContinuation(b1)) return pack(1, REPLACEMENT)
            return pack(2, ((b0 and 0x1f) shl 6) or (b1 and 0x3f))
        }
        if (b0 < 0xf0) {
            if (offset + 2 >= limit) return pack(1, REPLACEMENT)
            val b1 = bytes[offset + 1].toInt() and 0xff
            val b2 = bytes[offset + 2].toInt() and 0xff
            // E0 excludes overlong; ED excludes encoded surrogates.
            val b1Ok = when (b0) {
                0xe0 -> b1 in 0xa0..0xbf
                0xed -> b1 in 0x80..0x9f
                else -> isContinuation(b1)
            }
            if (!b1Ok || !isContinuation(b2)) return pack(1, REPLACEMENT)
            return pack(3, ((b0 and 0x0f) shl 12) or ((b1 and 0x3f) shl 6) or (b2 and 0x3f))
        }
        if (b0 < 0xf5) {
            if (offset + 3 >= limit) return pack(1, REPLACEMENT)
            val b1 = bytes[offset + 1].toInt() and 0xff
            val b2 = bytes[offset + 2].toInt() and 0xff
            val b3 = bytes[offset + 3].toInt() and 0xff
            // F0 excludes overlong; F4 caps the range at U+10FFFF.
            val b1Ok = when (b0) {
                0xf0 -> b1 in 0x90..0xbf
                0xf4 -> b1 in 0x80..0x8f
                else -> isContinuation(b1)
            }
            if (!b1Ok || !isContinuation(b2) || !isContinuation(b3)) return pack(1, REPLACEMENT)
            return pack(
                4,
                ((b0 and 0x07) shl 18) or ((b1 and 0x3f) shl 12) or
                    ((b2 and 0x3f) shl 6) or (b3 and 0x3f),
            )
        }
        return pack(1, REPLACEMENT)
    }

    private fun isContinuation(byte: Int): Boolean = byte in 0x80..0xbf

    private fun pack(byteCount: Int, codePoint: Int): Long =
        (byteCount.toLong() shl 32) or (codePoint.toLong() and 0xffff_ffffL)

    private const val REPLACEMENT = 0xfffd

    /**
     * The [GlideWordInventory.forEachWordUtf8] fallback's encoder: one reusable scratch buffer
     * for the whole walk, so a String-sourced inventory pays no per-entry array either.
     * Not thread-safe; an encoder belongs to one build walk.
     */
    class Encoder(initialCapacity: Int = 256) {
        var buffer = ByteArray(initialCapacity)
            private set

        /**
         * Encodes [word] as UTF-8 into [buffer] (grown as needed) and returns the byte count.
         * The bytes stay valid until the next [encode] call. An unpaired surrogate encodes as
         * `?` (0x3F), which maps to no key — the old build skipped such a word too, because the
         * surrogate code point itself maps to no key.
         */
        fun encode(word: String): Int {
            // At most 3 bytes per UTF-16 unit (a surrogate pair's 4 bytes fit in its 2x3).
            val required = word.length * 3
            if (buffer.size < required) buffer = ByteArray(required)
            val out = buffer
            var i = 0
            var count = 0
            while (i < word.length) {
                val first = word[i]
                val codePoint: Int
                if (first.isHighSurrogate() && i + 1 < word.length &&
                    word[i + 1].isLowSurrogate()
                ) {
                    codePoint = Character.toCodePoint(first, word[i + 1])
                    i += 2
                } else if (first.isSurrogate()) {
                    // Unpaired surrogate: see the doc comment.
                    out[count++] = 0x3f
                    i++
                    continue
                } else {
                    codePoint = first.code
                    i++
                }
                when {
                    codePoint < 0x80 -> out[count++] = codePoint.toByte()
                    codePoint < 0x800 -> {
                        out[count++] = (0xc0 or (codePoint shr 6)).toByte()
                        out[count++] = (0x80 or (codePoint and 0x3f)).toByte()
                    }
                    codePoint < 0x10000 -> {
                        out[count++] = (0xe0 or (codePoint shr 12)).toByte()
                        out[count++] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
                        out[count++] = (0x80 or (codePoint and 0x3f)).toByte()
                    }
                    else -> {
                        out[count++] = (0xf0 or (codePoint shr 18)).toByte()
                        out[count++] = (0x80 or ((codePoint shr 12) and 0x3f)).toByte()
                        out[count++] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
                        out[count++] = (0x80 or (codePoint and 0x3f)).toByte()
                    }
                }
            }
            return count
        }
    }
}
