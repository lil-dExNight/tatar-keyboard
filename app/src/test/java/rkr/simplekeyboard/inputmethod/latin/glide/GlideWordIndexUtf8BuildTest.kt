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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The byte-level build must be behavior-preserving: a UTF-8-serving inventory (the dictionary's
 * direct walk, which allocates no String per entry) and the String-serving default fallback
 * produce the SAME index, entry for entry and bit for bit — including mixed casing, alias
 * letters, doubled letters, unmappable letters and malformed UTF-8.
 */
class GlideWordIndexUtf8BuildTest {

    /** The old world: only [forEachWord]; the interface default re-encodes for the build. */
    private class StringListInventory(
        private val words: List<String>,
        private val frequencies: LongArray,
    ) : GlideWordInventory {
        override val entryCount: Int get() = words.size
        override fun wordAt(index: Int): String = words[index]
        override fun forEachWord(visitor: GlideWordVisitor) {
            for (index in words.indices) visitor.visit(words[index], frequencies[index])
        }
    }

    /** The new world: the raw stored bytes served directly (what TdictGlideInventory overrides). */
    private class DirectUtf8Inventory(
        private val rawWords: List<ByteArray>,
        private val frequencies: LongArray,
    ) : GlideWordInventory {
        override val entryCount: Int get() = rawWords.size
        override fun wordAt(index: Int): String = String(rawWords[index], Charsets.UTF_8)
        override fun forEachWord(visitor: GlideWordVisitor) {
            for (index in rawWords.indices) {
                visitor.visit(String(rawWords[index], Charsets.UTF_8), frequencies[index])
            }
        }

        override fun forEachWordUtf8(visitor: GlideWordUtf8Visitor) {
            for (index in rawWords.indices) {
                visitor.visit(rawWords[index], rawWords[index].size, frequencies[index])
            }
        }
    }

    private fun geometry(): GlideKeyGeometry =
        GlideKeyGeometry.build(GlideTestFixtures.tatarRawKeys())

    /** Every observable of the index, compared between the two build paths. */
    private fun assertSameIndex(expected: GlideWordIndex, actual: GlideWordIndex) {
        val keyCount = geometry().keyCount
        assertEquals("wordCount", expected.wordCount, actual.wordCount)
        assertEquals("skippedWordCount", expected.skippedWordCount, actual.skippedWordCount)
        assertEquals("maxFrequency", expected.maxFrequency, actual.maxFrequency)
        assertEquals("retainedByteEstimate", expected.retainedByteEstimate, actual.retainedByteEstimate)
        val entries = expected.wordCount + expected.skippedWordCount
        for (entry in 0 until entries) {
            val start = expected.keySeqStart(entry)
            val end = expected.keySeqEnd(entry)
            assertEquals("keySeqStart($entry)", start, actual.keySeqStart(entry))
            assertEquals("keySeqEnd($entry)", end, actual.keySeqEnd(entry))
            for (position in start until end) {
                assertEquals("keySeqAt($position)", expected.keySeqAt(position), actual.keySeqAt(position))
                assertEquals(
                    "sameLetter($position)",
                    expected.sameLetterAsPreviousAt(position), actual.sameLetterAsPreviousAt(position),
                )
            }
            assertEquals("frequency($entry)", expected.frequencyAt(entry), actual.frequencyAt(entry))
            assertEquals("plainLength($entry)", expected.plainLengthAt(entry), actual.plainLengthAt(entry))
            assertEquals("loopLength($entry)", expected.loopLengthAt(entry), actual.loopLengthAt(entry))
            assertEquals(
                "normative($entry)",
                expected.normativeDurationMsAt(entry), actual.normativeDurationMsAt(entry),
            )
            assertEquals("twinless($entry)", expected.isTwinlessAt(entry), actual.isTwinlessAt(entry))
        }
        for (first in 0 until keyCount) {
            for (last in 0 until keyCount) {
                val start = expected.pairRangeStart(first, last)
                val end = expected.pairRangeEnd(first, last)
                assertEquals("bucketStart($first,$last)", start, actual.pairRangeStart(first, last))
                assertEquals("bucketEnd($first,$last)", end, actual.pairRangeEnd(first, last))
                for (position in start until end) {
                    assertEquals(
                        "bucketEntry($position)",
                        expected.pairEntryAt(position), actual.pairEntryAt(position),
                    )
                }
            }
        }
    }

    private fun assertBuildsAgree(rawWords: List<ByteArray>) {
        val frequencies = LongArray(rawWords.size) { index -> 4_000_000L / (index + 1) + 1 }
        val stringWords = rawWords.map { String(it, Charsets.UTF_8) }
        val geometry = geometry()
        val viaStrings = GlideWordIndex.build(StringListInventory(stringWords, frequencies), geometry)
        val viaBytes = GlideWordIndex.build(DirectUtf8Inventory(rawWords, frequencies), geometry)
        assertSameIndex(viaStrings, viaBytes)
    }

    @Test
    fun realisticWordsBuildIdentically() {
        val words = listOf(
            "һәм", "белән", "да", "дә", "бу", "ул", "дип", "өчен", "китап", "балалар",
            "мәктәп", "эш", "фикер", "сүз", "йорт", "җан", "һава", "төп", "яз", "уз",
            "алла", "килле", "тел", "ее", "её", "ёе", "ъ", "ь", "э", "а",
            "Китап", "БАЛАЛАР", "Ике", "Җан", "Юл",
            "x123", "1", "abc", "", "сүз" + "ә".repeat(300),
            "emoji\uD83D\uDE00", // supplementary plane: no key, skipped in both worlds
        )
        assertBuildsAgree(words.map { it.toByteArray(Charsets.UTF_8) })
    }

    @Test
    fun malformedUtf8WordsAreSkippedIdentically() {
        val malformed = listOf(
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xc1.toByte(), 0x81.toByte()), // overlong 'A': must NOT become a letter
            byteArrayOf(0xc2.toByte()),
            byteArrayOf(0xe0.toByte(), 0xa0.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf('a'.code.toByte(), 0x80.toByte(), 'b'.code.toByte()),
            byteArrayOf(0xd0.toByte(), 0xba.toByte(), 0xc1.toByte(), 0x81.toByte()), // "к" + bad
        )
        // Valid words around the malformed ones, so the index is non-trivial on both paths.
        val words = listOf("китап".toByteArray(Charsets.UTF_8)) + malformed +
            listOf("балалар".toByteArray(Charsets.UTF_8))
        assertBuildsAgree(words)
        // And every malformed word is unindexable (replacement chars map to no key).
        val frequencies = LongArray(words.size) { 10L }
        val geometry = geometry()
        val index = GlideWordIndex.build(DirectUtf8Inventory(words, frequencies), geometry)
        assertEquals("only the two valid words index", 2, index.wordCount)
        assertEquals(words.size - 2, index.skippedWordCount)
    }

    @Test
    fun fuzzedWordsBuildIdentically() {
        val alphabet = "әөүҗңһйцукенгшщзхъфывапролджэячсмитьбюё"
        val random = Random(42)
        val words = ArrayList<ByteArray>(400)
        repeat(400) {
            val length = 1 + random.nextInt(12)
            val builder = StringBuilder()
            repeat(length) {
                when (random.nextInt(10)) {
                    0 -> builder.append((random.nextInt(26) + 'a'.code).toChar())
                    1 -> builder.appendCodePoint(0x1f600 + random.nextInt(8))
                    2, 3 -> builder.append(alphabet[random.nextInt(alphabet.length)].uppercaseChar())
                    else -> builder.append(alphabet[random.nextInt(alphabet.length)])
                }
            }
            words.add(builder.toString().toByteArray(Charsets.UTF_8))
        }
        assertBuildsAgree(words)
    }

    @Test
    fun theDefaultFallbackAddsNoPerEntryArray() {
        // The fallback contract: ONE shared scratch buffer for the whole walk, so a String-based
        // inventory (personal words) does not regress by a byte[] per entry.
        val visits = ArrayList<ByteArray>()
        val inventory = StringListInventory(listOf("китап", "балалар", "һәм"), LongArray(3) { 5L })
        inventory.forEachWordUtf8 { bytes, length, _ ->
            assertTrue(length > 0)
            visits.add(bytes)
        }
        assertTrue(
            "the same scratch instance serves every visit",
            visits.all { it === visits.first() },
        )
    }
}
