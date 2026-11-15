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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/** The byte-level codec of the glide index build: exact for well-formed input, safe otherwise. */
class GlideUtf8Test {

    private fun decodeAll(bytes: ByteArray): List<Int> {
        val codePoints = ArrayList<Int>()
        var offset = 0
        while (offset < bytes.size) {
            val packed = GlideUtf8.nextCodePoint(bytes, offset, bytes.size)
            codePoints.add((packed and 0xffff_ffffL).toInt())
            offset += (packed ushr 32).toInt()
        }
        return codePoints
    }

    @Test
    fun encodeMatchesTheJavaEncoderIncludingEdgeCases() {
        val words = listOf(
            "", "a", "һәм", "белән", "да", "КИТАП", "ёъ",
            " mixed CASE сүзләр 123 ", "\u00e9\u0301", // é + combining accent
            "emoji \uD83D\uDE00 tail", // supplementary plane
            "\uD83D", "\uDE00", "\uD83Dx", "x\uDE00", // unpaired surrogates
            "ߊ", "ࠀ", "ࠀ", // 1/2/3-byte boundaries
            String(Character.toChars(0x10000)), String(Character.toChars(0x10ffff)),
        )
        val encoder = GlideUtf8.Encoder()
        for (word in words) {
            val expected = word.toByteArray(Charsets.UTF_8)
            val count = encoder.encode(word)
            assertArrayEquals(
                "encode of ${word.toList().joinToString { it.code.toString(16) }}",
                expected, encoder.buffer.copyOf(count),
            )
            // The buffer can be larger than the word; the returned count bounds the content.
            assertEquals(expected.size, count)
        }
    }

    @Test
    fun encodeMatchesJavaOnFuzzedStrings() {
        val random = Random(7)
        val encoder = GlideUtf8.Encoder()
        repeat(2_000) {
            val length = random.nextInt(12)
            val builder = StringBuilder()
            repeat(length) {
                val choice = random.nextInt(8)
                when (choice) {
                    0 -> builder.append((random.nextInt(26) + 'a'.code).toChar())
                    1 -> builder.append((random.nextInt(32) + 0x430).toChar()) // Cyrillic
                    2 -> builder.append((random.nextInt(6) + 0x4d8).toChar()) // Tatar specials
                    3 -> builder.append((random.nextInt(0x500)).toChar()) // BMP random
                    4 -> builder.appendCodePoint(0x1f600 + random.nextInt(16))
                    5 -> builder.appendCodePoint(0x10000 + random.nextInt(1024))
                    6 -> builder.append((random.nextInt(26) + 'A'.code).toChar())
                    else -> builder.append((random.nextInt(0xd000)).toChar())
                }
            }
            val word = builder.toString()
            val expected = word.toByteArray(Charsets.UTF_8)
            val count = encoder.encode(word)
            assertArrayEquals("fuzzed encode mismatch", expected, encoder.buffer.copyOf(count))
        }
    }

    @Test
    fun decodeReproducesStringCodePointsExactly() {
        val words = listOf(
            "a", "һәм", "КИТАП", "ёъ", "\u00e9\u0301",
            "emoji \uD83D\uDE00 tail", String(Character.toChars(0x10ffff)),
        )
        for (word in words) {
            val expected = ArrayList<Int>()
            var i = 0
            while (i < word.length) {
                expected.add(word.codePointAt(i))
                i += Character.charCount(word.codePointAt(i))
            }
            assertEquals("decode of $word", expected, decodeAll(word.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun malformedSequencesDecodeToReplacementAndAdvanceOneByte() {
        // Each case: raw bytes to the exact decode (a malformed sequence is U+FFFD, advance 1).
        val cases = listOf(
            byteArrayOf(0x80.toByte()) to listOf(0xfffd), // stray continuation
            byteArrayOf(0xc0.toByte(), 0x81.toByte()) to listOf(0xfffd, 0xfffd), // C0 lead
            byteArrayOf(0xc1.toByte(), 0x81.toByte()) to listOf(0xfffd, 0xfffd), // overlong 'A'
            byteArrayOf(0xc2.toByte()) to listOf(0xfffd), // truncated 2-byte
            byteArrayOf(0xe0.toByte(), 0x80.toByte(), 0x80.toByte()) to
                listOf(0xfffd, 0xfffd, 0xfffd), // overlong 3-byte
            byteArrayOf(0xe0.toByte(), 0xa0.toByte()) to
                listOf(0xfffd, 0xfffd), // truncated after valid b1
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()) to
                listOf(0xfffd, 0xfffd, 0xfffd), // encoded surrogate
            byteArrayOf(0xf0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()) to
                listOf(0xfffd, 0xfffd, 0xfffd, 0xfffd), // overlong 4-byte
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()) to
                listOf(0xfffd, 0xfffd, 0xfffd, 0xfffd), // > U+10FFFF
            byteArrayOf(0xf5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()) to
                listOf(0xfffd, 0xfffd, 0xfffd, 0xfffd), // invalid lead F5
            byteArrayOf(0xc2.toByte(), 'a'.code.toByte()) to
                listOf(0xfffd, 'a'.code), // bad continuation: the 'a' survives
        )
        for ((bytes, expected) in cases) {
            assertEquals(
                "malformed ${bytes.joinToString { (it.toInt() and 0xff).toString(16) }}",
                expected, decodeAll(bytes),
            )
        }
        // A valid word with a malformed middle decodes around the bad byte, one byte at a time.
        val mixed = byteArrayOf('a'.code.toByte(), 0x80.toByte(), 'b'.code.toByte())
        assertEquals(listOf('a'.code, 0xfffd, 'b'.code), decodeAll(mixed))
    }
}
