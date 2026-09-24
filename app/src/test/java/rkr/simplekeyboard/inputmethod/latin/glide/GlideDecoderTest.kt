package rkr.simplekeyboard.inputmethod.latin.glide

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * Unit tests for [GlideDecoder] on the fixture Tatar geometry with a small list-backed
 * inventory: decode mechanics, fail-closed edges, determinism, and the zero-allocation
 * discipline of the decode path.
 */
class GlideDecoderTest {

    private val geometry = GlideTestFixtures.tatarGeometry()

    private fun decoderOf(entries: List<Pair<String, Long>>): GlideDecoder =
        GlideDecoder(geometry, ListGlideInventory(entries))

    /** The ideal center-to-center path of [word] over the fixture geometry (no noise). */
    private fun idealPath(word: String, withLoop: Boolean = false): GlidePath {
        val path = GlidePath()
        var t = 0f
        var previous = -1
        for (offset in 0 until word.length) {
            val codePoint = word.codePointAt(offset)
            val key = geometry.keyIndexOfLetter(codePoint)
            check(key >= 0) { "fixture word has an unmappable letter" }
            if (withLoop && codePoint == previous) {
                val dx = geometry.halfWidth(key) / 2
                val dy = geometry.halfHeight(key) / 2
                path.addPoint(geometry.centerX(key) + dx, geometry.centerY(key) + dy, t); t += 8
                path.addPoint(geometry.centerX(key) + dx, geometry.centerY(key) - dy, t); t += 8
                path.addPoint(geometry.centerX(key) - dx, geometry.centerY(key) - dy, t); t += 8
                path.addPoint(geometry.centerX(key) - dx, geometry.centerY(key) + dy, t); t += 8
            } else {
                path.addPoint(geometry.centerX(key), geometry.centerY(key), t)
                t += 8
            }
            previous = codePoint
        }
        return path
    }

    private fun decodeWords(decoder: GlideDecoder, path: GlidePath): List<String> {
        val result = GlideResult()
        val count = decoder.decode(path, result)
        return (0 until count).map { result.words[it]!! }
    }

    @Test
    fun theIdealPathDecodesItsWordAsTop1() {
        val decoder = decoderOf(
            listOf(
                "сәләм" to 36L,
                "салым" to 7466L, // same start/end keys, different shape — must lose on shape
                "китап" to 200L,
                "алла" to 30L,
            ),
        )
        val words = decodeWords(decoder, idealPath("сәләм"))
        assertTrue(words.isNotEmpty())
        assertEquals("сәләм", words[0])
    }

    @Test
    fun shapeWinsOverFrequencyForDistinctPaths() {
        // The low-frequency exact match must outrank a high-frequency different-shape word.
        val decoder = decoderOf(
            listOf(
                "сәләм" to 1L,
                "салым" to 100_000L,
            ),
        )
        assertEquals("сәләм", decodeWords(decoder, idealPath("сәләм"))[0])
        assertEquals("салым", decodeWords(decoder, idealPath("салым"))[0])
    }

    @Test
    fun theLoopVariantRecoversALoopedGesture() {
        val decoder = decoderOf(
            listOf(
                "алла" to 30L,
                "алма" to 60L,
            ),
        )
        assertEquals("алла", decodeWords(decoder, idealPath("алла", withLoop = true))[0])
    }

    @Test
    fun emptyDegenerateAndPrunedInputsYieldNoCandidates() {
        val decoder = decoderOf(listOf("сәләм" to 36L, "салым" to 7466L))
        // Empty path.
        assertEquals(0, decodeWords(decoder, GlidePath()).size)
        // A single point is a tap, not a gesture.
        val tap = GlidePath()
        tap.addPoint(1000f, 1000f, 0f)
        assertEquals(0, decodeWords(decoder, tap).size)
        // A pruned-out input: a one-unit wiggle on a key no inventory word starts AND ends
        // near. Fail-closed means no candidates, not an exception.
        val key = geometry.keyIndexOfLetter('т'.code)
        val wiggle = GlidePath()
        wiggle.addPoint(geometry.centerX(key), geometry.centerY(key), 0f)
        wiggle.addPoint(geometry.centerX(key) + 1f, geometry.centerY(key), 8f)
        assertEquals(0, decodeWords(decoder, wiggle).size)
    }

    @Test
    fun emptyGeometryAndEmptyInventoryFailClosed() {
        val inventory = ListGlideInventory(listOf("сәләм" to 36L))
        val noKeys = GlideDecoder(GlideKeyGeometry.build(emptyList()), inventory)
        assertEquals(0, decodeWords(noKeys, idealPath("сәләм")).size)
        val noWords = decoderOf(emptyList())
        assertEquals(0, decodeWords(noWords, idealPath("сәләм")).size)
    }

    @Test
    fun decodeIsDeterministic() {
        val decoder = decoderOf(
            listOf(
                "сәләм" to 36L,
                "салым" to 7466L,
                "алла" to 30L,
                "алма" to 60L,
                "китап" to 200L,
            ),
        )
        val path = idealPath("сәләм")
        val first = GlideResult()
        val second = GlideResult()
        decoder.decode(path, first)
        decoder.decode(path, second)
        assertEquals(first.count, second.count)
        for (i in 0 until first.count) {
            assertEquals(first.words[i], second.words[i])
            assertEquals(first.scores[i].toBits(), second.scores[i].toBits())
        }
    }

    @Test
    fun extremityPruningVisitsOnlyTheMatchingBuckets() {
        val decoder = decoderOf(
            listOf(
                "сәләм" to 36L,
                "китап" to 200L,
                "алла" to 30L,
                "өткән" to 90L,
                "йолдыз" to 60L,
            ),
        )
        decodeWords(decoder, idealPath("сәләм"))
        // Only words whose first key is among the 2 nearest to the start AND whose last key is
        // among the 2 nearest to the end are visited: strictly fewer than the whole inventory.
        assertTrue(decoder.lastCandidateCount in 1 until 5)
        assertTrue(decoder.lastScoredCount <= decoder.lastCandidateCount)
    }

    @Test
    fun pathBuffersDropPointsBeyondCapacity() {
        val path = GlidePath()
        repeat(GlidePath.MAX_POINTS) { assertTrue(path.addPoint(it.toFloat(), 0f, it.toFloat())) }
        assertFalse(path.addPoint(0f, 0f, 0f))
        assertEquals(GlidePath.MAX_POINTS, path.size)
        path.clear()
        assertEquals(0, path.size)
    }

    // The G3 discipline on the fixture: the decode machinery allocates nothing after warmup;
    // the only per-call allocation is the result materialization (the surfaced strings).
    @Test
    fun decodeAllocationsAfterWarmup() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        val decoder = decoderOf(
            listOf(
                "сәләм" to 36L,
                "салым" to 7466L,
                "алла" to 30L,
                "алма" to 60L,
                "китап" to 200L,
            ),
        )
        val result = GlideResult()
        val wordPath = idealPath("сәләм")
        // A pruned-out path (no inventory word starts and ends near this key): the decode runs
        // the full machinery and returns zero candidates.
        val prunedPath = GlidePath()
        val wiggleKey = geometry.keyIndexOfLetter('т'.code)
        prunedPath.addPoint(geometry.centerX(wiggleKey), geometry.centerY(wiggleKey), 0f)
        prunedPath.addPoint(geometry.centerX(wiggleKey) + 1f, geometry.centerY(wiggleKey), 8f)

        fun perCallBytes(path: GlidePath, iterations: Int): Long {
            val before = threadBean.getThreadAllocatedBytes(threadId)
            for (i in 0 until iterations) decoder.decode(path, result)
            val after = threadBean.getThreadAllocatedBytes(threadId)
            return (after - before) / iterations
        }

        // Warm the lazy index build, JIT and the result slots.
        perCallBytes(wordPath, 2_000)
        perCallBytes(prunedPath, 2_000)

        val prunedBytes = perCallBytes(prunedPath, 20_000)
        assertEquals("the pruned-out decode returned candidates", 0, result.count)
        assertTrue("machinery bytes/call on a pruned path: $prunedBytes", prunedBytes <= 8L)

        val wordBytes = perCallBytes(wordPath, 20_000)
        assertTrue("the ideal path returned no candidates", result.count > 0)
        // The result materialization is bounded: at most TOP_N strings with their decoded
        // backing arrays (measured well under 256 B/word for these fixture words).
        assertTrue(
            "bytes/call with results: $wordBytes (bound = ${GlideDecoder.TOP_N} strings)",
            wordBytes <= GlideDecoder.TOP_N * 256L,
        )
    }
}
