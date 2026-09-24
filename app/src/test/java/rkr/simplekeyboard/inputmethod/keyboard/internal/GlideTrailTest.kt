package rkr.simplekeyboard.inputmethod.keyboard.internal

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * Unit tests for [GlideTrail] (P7-5): ring semantics (overwrite-oldest, order preservation),
 * the age-windowed visibility, the fingertip-to-tail alpha ramp, and the zero-allocation
 * discipline of the feed and draw-read paths.
 */
class GlideTrailTest {

    @Test
    fun pointsComeOutOldestFirstUntilCapacity() {
        val trail = GlideTrail(capacity = 8)
        assertTrue(trail.isEmpty())
        for (i in 0 until 5) {
            trail.addPoint(i * 10f, i * 100f, i * 16f)
        }
        assertEquals(5, trail.size)
        for (i in 0 until 5) {
            assertEquals("x[$i]", i * 10f, trail.xAt(i), 0f)
            assertEquals("y[$i]", i * 100f, trail.yAt(i), 0f)
        }
    }

    @Test
    fun aFullRingOverwritesTheOldestPoint() {
        val trail = GlideTrail(capacity = 4)
        for (i in 0 until 7) {
            trail.addPoint(i.toFloat(), 0f, i.toFloat())
        }
        assertEquals("the size stays at capacity", 4, trail.size)
        // Points 0..2 are gone; 3..6 remain, oldest first.
        for (i in 0 until 4) {
            assertEquals("x[$i]", (i + 3).toFloat(), trail.xAt(i), 0f)
        }
    }

    @Test
    fun clearResetsTheRingForTheNextGesture() {
        val trail = GlideTrail(capacity = 4)
        for (i in 0 until 7) {
            trail.addPoint(i.toFloat(), 0f, i.toFloat())
        }
        trail.clear()
        assertTrue(trail.isEmpty())
        assertEquals(0, trail.size)
        // The ring must be reusable from a clean state: no stale offset leaks into the indices.
        trail.addPoint(42f, 24f, 1000f)
        assertEquals(42f, trail.xAt(0), 0f)
        assertEquals(24f, trail.yAt(0), 0f)
    }

    @Test
    fun onlyTheTailWindowIsVisible() {
        val trail = GlideTrail()
        // 30 points, 10 ms apart; the newest is at t = 290.
        for (i in 0 until 30) {
            trail.addPoint(i.toFloat(), 0f, i * 10f)
        }
        // The window is 150 ms: points with age > 150 (t < 140, i.e. indices 0..13) are hidden.
        assertEquals(14, trail.firstVisible())
        // A newer point shifts the window.
        trail.addPoint(30f, 0f, 300f)
        assertEquals(15, trail.firstVisible())
    }

    @Test
    fun alphaRampsFromTheTailEdgeToTheFingertip() {
        val trail = GlideTrail()
        // 11 points, 15 ms apart; the newest is at t = 150, the window is exactly full.
        for (i in 0 until 11) {
            trail.addPoint(i.toFloat(), 0f, i * 15f)
        }
        assertEquals("the fingertip is fully lit", GlideTrail.MAX_ALPHA, trail.alphaAt(10))
        assertEquals("the tail edge is transparent", 0, trail.alphaAt(0))
        // Strictly increasing toward the fingertip inside the window.
        var previous = -1
        for (i in trail.firstVisible() until trail.size) {
            val alpha = trail.alphaAt(i)
            assertTrue("alpha must increase toward the fingertip (i=$i)", alpha > previous)
            previous = alpha
        }
        // The midpoint of a full 150 ms window sits at half opacity.
        assertEquals(GlideTrail.MAX_ALPHA / 2, trail.alphaAt(5))
    }

    @Test
    fun aSinglePointGestureHasNothingToDraw() {
        val trail = GlideTrail()
        trail.addPoint(1f, 2f, 3f)
        assertEquals(0, trail.firstVisible())
        assertEquals("one point draws no segment", 1, trail.size - trail.firstVisible())
        assertEquals(GlideTrail.MAX_ALPHA, trail.alphaAt(0))
    }

    @Test
    fun feedAndDrawReadPathsAllocateNothing() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        val trail = GlideTrail()
        fun perGestureBytes(iterations: Int): Long {
            val before = threadBean.getThreadAllocatedBytes(threadId)
            for (g in 0 until iterations) {
                for (i in 0 until GlideTrail.DEFAULT_CAPACITY + 17) {
                    trail.addPoint(i.toFloat(), (i * 2).toFloat(), (g * 1000 + i * 8).toFloat())
                }
                // The draw-read path: window scan + per-segment reads.
                val first = trail.firstVisible()
                var acc = 0f
                for (i in first until trail.size - 1) {
                    acc += trail.xAt(i) + trail.yAt(i + 1) + trail.alphaAt(i + 1)
                }
                assertTrue(acc > 0f)
                trail.clear()
            }
            val after = threadBean.getThreadAllocatedBytes(threadId)
            return (after - before) / iterations
        }

        perGestureBytes(200) // warmup: JIT
        val bytes = perGestureBytes(2_000)
        assertEquals("bytes/gesture on the feed + draw-read path", 0L, bytes)
    }
}
