package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [GlideWordIndex]: bucketing, ideal lengths, skips, memory estimate. */
class GlideWordIndexTest {

    private val geometry = GlideTestFixtures.tatarGeometry()

    private fun index(entries: List<Pair<String, Long>>): GlideWordIndex =
        GlideWordIndex.build(ListGlideInventory(entries), geometry)

    @Test
    fun pairBucketsPartitionTheIndexedWordsInDictionaryOrder() {
        val entries = listOf(
            "сәләм" to 100L,
            "салым" to 50L,
            "китап" to 200L,
            "алла" to 30L,
        )
        val index = index(entries)
        assertEquals(4, index.wordCount)
        assertEquals(0, index.skippedWordCount)
        // Every entry appears in exactly one bucket, in ascending entry order per bucket.
        val seen = sortedSetOf<Int>()
        val keyCount = geometry.keyCount
        for (start in 0 until keyCount) {
            for (end in 0 until keyCount) {
                var previous = -1
                for (position in index.pairRangeStart(start, end) until index.pairRangeEnd(start, end)) {
                    val entry = index.pairEntryAt(position)
                    assertTrue(entry in 0 until 4)
                    assertTrue(seen.add(entry))
                    assertTrue(entry > previous)
                    previous = entry
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
}
