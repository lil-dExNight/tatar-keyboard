package rkr.simplekeyboard.inputmethod.latin.glide

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.text.Normalizer

/** Unit tests for [GlideWordIndex]: bucketing, ideal lengths, skips, memory estimate. */
class GlideWordIndexTest {

    private val geometry = GlideTestFixtures.tatarGeometry()

    private fun index(entries: List<Pair<String, Long>>): GlideWordIndex =
        GlideWordIndex.build(ListGlideInventory(entries), geometry)

    @Test
    fun pairBucketsPartitionTheIndexedWords() {
        val entries = listOf(
            "сәләм" to 100L,
            "салым" to 50L,
            "китап" to 200L,
            "алла" to 30L,
        )
        val index = index(entries)
        assertEquals(4, index.wordCount)
        assertEquals(0, index.skippedWordCount)
        // Every entry appears in exactly one bucket.
        val seen = sortedSetOf<Int>()
        val keyCount = geometry.keyCount
        for (start in 0 until keyCount) {
            for (end in 0 until keyCount) {
                for (position in index.pairRangeStart(start, end) until index.pairRangeEnd(start, end)) {
                    val entry = index.pairEntryAt(position)
                    assertTrue(entry in 0 until 4)
                    assertTrue(seen.add(entry))
                }
            }
        }
        assertEquals(4, seen.size)
    }

    @Test
    fun wordsWithUnmappableLettersAreSkipped() {
        val entries = listOf(
            "сәләм" to 100L,
            "сәләмx" to 60L, // x is not on the Tatar layout
            "китап" to 200L,
        )
        val index = index(entries)
        assertEquals(2, index.wordCount)
        assertEquals(1, index.skippedWordCount)
        assertEquals(200L, index.maxFrequency)
    }

    @Test
    fun wordsWithAnAliasLetterAreIndexedOnTheBaseKey() {
        val index = index(listOf("игътибар" to 10L, "ёлка" to 5L))
        assertEquals(2, index.wordCount)
        assertEquals(0, index.skippedWordCount)
        val hardSign = index.keySeqStart(0) + 2
        assertEquals(geometry.keyIndexOfLetter('ь'.code), index.keySeqAt(hardSign))
        assertEquals(geometry.keyIndexOfLetter('е'.code), index.keySeqAt(index.keySeqStart(1)))
    }

    @Test
    fun frequenciesAreKeptPerEntry() {
        val index = index(listOf("сәләм" to 36L, "салым" to 7466L))
        assertEquals(36L, index.frequencyAt(0))
        assertEquals(7466L, index.frequencyAt(1))
        assertEquals(7466L, index.maxFrequency)
    }

    @Test
    fun loopLengthIsMinusOneWithoutDoublesAndLongerWithThem() {
        val index = index(listOf("сәләм" to 1L, "алла" to 1L))
        assertEquals(-1f, index.loopLengthAt(0), 0f)
        assertTrue(index.loopLengthAt(1) > index.plainLengthAt(1))
    }

    @Test
    fun theTwinBitMarksDoubledWordsWhoseCollapsedSequenceIsNoOtherWord() {
        val index = index(
            listOf(
                "алла" to 1L, // twin ала present
                "ала" to 1L,
                "сәлләм" to 1L, // no сәләм: twinless
                "ааллаа" to 1L, // every run collapsed: twin ала
                "ммаммм" to 1L, // collapsed мам is absent: twinless
                "ееш" to 1L, // collapsed еш equals ёш on the same keys: alias twin
                "ёш" to 1L,
                "её" to 1L, // е and its alias ё fold onto one key: an alias fold, not a doubled letter
                "китап" to 1L, // no doubled key: never twinless
            ),
        )
        assertFalse(index.isTwinlessAt(0))
        assertFalse(index.isTwinlessAt(1))
        assertTrue(index.isTwinlessAt(2))
        assertFalse(index.isTwinlessAt(3))
        assertTrue(index.isTwinlessAt(4))
        assertFalse(index.isTwinlessAt(5))
        assertFalse(index.isTwinlessAt(6))
        assertFalse(index.isTwinlessAt(7))
        assertEquals(-1f, index.loopLengthAt(7), 0f)
        assertFalse(index.isTwinlessAt(8))
    }

    @Test
    fun theTwinBitFollowsATwinAddedByAPersonalWord() {
        val base = listOf("сәлләм" to 5L)
        assertTrue(index(base).isTwinlessAt(0))
        assertFalse(index(base + ("сәләм" to 1L)).isTwinlessAt(0))
    }

    @Test
    fun keySequenceRoundTripsThroughThePool() {
        val index = index(listOf("сәләм" to 1L))
        val letters = listOf('с', 'ә', 'л', 'ә', 'м')
        assertEquals(letters.size, index.keySeqEnd(0) - index.keySeqStart(0))
        for ((position, letter) in letters.withIndex()) {
            val key = geometry.keyIndexOfLetter(letter.code)
            assertEquals(key, index.keySeqAt(index.keySeqStart(0) + position))
        }
    }

    @Test
    fun retainedByteEstimateCoversTheFlatArrays() {
        val index = index(listOf("сәләм" to 1L, "китап" to 2L))
        // 37 x 37 CSR offsets alone are ~5.5 KB; the estimate must be positive and bounded
        // by a sane multiple of the entry count.
        assertTrue(index.retainedByteEstimate > 37 * 37 * 4)
        assertTrue(index.retainedByteEstimate < 1_000_000)
    }

    @Test
    fun emptyInventoryBuildsAnEmptyIndex() {
        val index = index(emptyList())
        assertEquals(0, index.wordCount)
        assertEquals(0, index.maxFrequency)
    }

    @Test
    fun moreThan127KeysAreRejectedAtBuild() {
        val geometry = geometryOf(syntheticLetters(128))
        assertEquals(128, geometry.keyCount)
        assertThrows(IllegalArgumentException::class.java) {
            GlideWordIndex.build(ListGlideInventory(listOf("a" to 1L)), geometry)
        }
    }

    @Test
    fun highKeyIndicesRoundTripThroughThePool() {
        val letters = syntheticLetters(127)
        val word = buildString { for (key in 64..126) appendCodePoint(letters[key]) }
        val index =
            GlideWordIndex.build(ListGlideInventory(listOf(word to 1L)), geometryOf(letters))
        assertEquals(1, index.wordCount)
        assertEquals(63, index.keySeqEnd(0) - index.keySeqStart(0))
        for (key in 64..126) {
            assertEquals(key, index.keySeqAt(index.keySeqStart(0) + key - 64))
        }
    }

    @Test
    fun bucketOrderMatchesTheStableFrequencySort() {
        val entries = List(96) { entry ->
            val word = buildString {
                append('а')
                for (bit in 0 until 7) append(if (entry and (1 shl bit) == 0) 'л' else 'м')
                append('а')
            }
            val frequency = when (entry % 9) {
                0 -> 4_000_000_000L
                1, 2, 3 -> 50L
                else -> (entry * 97L % 10_000L) + 1L
            }
            word to frequency
        }
        val frequencies = IntArray(entries.size) { entries[it].second.toInt() }
        val expected = IntArray(entries.size) { it }
        for (i in 1 until expected.size) {
            val entry = expected[i]
            val frequency = frequencies[entry]
            var j = i - 1
            while (j >= 0 &&
                (frequencies[expected[j]] < frequency ||
                    (frequencies[expected[j]] == frequency && expected[j] > entry))
            ) {
                expected[j + 1] = expected[j]
                j--
            }
            expected[j + 1] = entry
        }

        val index = index(entries)
        val key = geometry.keyIndexOfLetter('а'.code)
        val actual = IntArray(entries.size) {
            index.pairEntryAt(index.pairRangeStart(key, key) + it)
        }
        assertArrayEquals(expected, actual)
    }

    @Test
    fun bucketSortAllocatesOneScratchBoundedByTheLargestBucket() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val bucketSize = 48
        val bucketCount = 12
        val entries = IntArray(bucketSize * bucketCount) { (it * 37) % (bucketSize * bucketCount) }
        val offsets = IntArray(bucketCount + 1) { it * bucketSize }
        val frequencies = IntArray(entries.size) { it * 17 % 101 }

        repeat(100) { GlideWordIndex.sortPairBuckets(entries, offsets, frequencies) }
        val before = threadBean.getThreadAllocatedBytes(threadId)
        repeat(1_000) { GlideWordIndex.sortPairBuckets(entries, offsets, frequencies) }
        val bytesPerSort = (threadBean.getThreadAllocatedBytes(threadId) - before) / 1_000
        assertTrue(
            "sort bytes/call: $bytesPerSort",
            bytesPerSort <= bucketSize * 4L + 64L,
        )
    }

    /** The first [count] code points the geometry's letter filter keeps unchanged. */
    private fun syntheticLetters(count: Int): IntArray {
        val letters = ArrayList<Int>(count)
        var codePoint = 'a'.code
        while (letters.size < count) {
            val folded = Normalizer
                .normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFC)
                .lowercase()
            if (folded.codePointCount(0, folded.length) == 1 &&
                folded.codePointAt(0) == codePoint &&
                Character.isLetter(codePoint)
            ) {
                letters.add(codePoint)
            }
            codePoint++
        }
        return letters.toIntArray()
    }

    /** A geometry of one key per letter; key index `i` carries `letters[i]`. */
    private fun geometryOf(letters: IntArray): GlideKeyGeometry =
        GlideKeyGeometry.build(letters.map { GlideKeyGeometry.RawKey(it, 0, 0, 10, 10) })
}
