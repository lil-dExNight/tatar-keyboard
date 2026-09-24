package rkr.simplekeyboard.inputmethod.keyboard.internal

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * Unit tests for [GlideTrail] (P7-5; grown in P7-7): ring semantics (overwrite-oldest, order
 * preservation), the age-windowed visibility, the fingertip-to-tail alpha ramp, the post-lift
 * fade-out math, and the zero-allocation discipline of the feed and draw-read paths (fade
 * frames included).
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
        // 60 points, 10 ms apart; the newest is at t = 590.
        for (i in 0 until 60) {
            trail.addPoint(i.toFloat(), 0f, i * 10f)
        }
        // The window is 300 ms (P7-7): points with age > 300 (t < 290, i.e. indices 0..28) hide.
        assertEquals(29, trail.firstVisible())
        // A newer point shifts the window.
        trail.addPoint(60f, 0f, 600f)
        assertEquals(30, trail.firstVisible())
    }

    @Test
    fun alphaRampsFromTheTailEdgeToTheFingertip() {
        val trail = GlideTrail()
        // 21 points, 15 ms apart; the newest is at t = 300, the window is exactly full.
        for (i in 0 until 21) {
            trail.addPoint(i.toFloat(), 0f, i * 15f)
        }
        assertEquals("the fingertip is fully lit", GlideTrail.MAX_ALPHA, trail.alphaAt(20))
        assertEquals("the tail edge is transparent", 0, trail.alphaAt(0))
        // Strictly increasing toward the fingertip inside the window.
        var previous = -1
        for (i in trail.firstVisible() until trail.size) {
            val alpha = trail.alphaAt(i)
            assertTrue("alpha must increase toward the fingertip (i=$i)", alpha > previous)
            previous = alpha
        }
        // The midpoint of a full 300 ms window sits at half opacity.
        assertEquals(GlideTrail.MAX_ALPHA / 2, trail.alphaAt(10))
    }

    @Test
    fun aSinglePointGestureHasNothingToDraw() {
        val trail = GlideTrail()
        trail.addPoint(1f, 2f, 3f)
        assertEquals(0, trail.firstVisible())
        assertEquals("one point draws no segment", 1, trail.size - trail.firstVisible())
        assertEquals(GlideTrail.MAX_ALPHA, trail.alphaAt(0))
    }

    // --- P7-7: the post-lift fade-out ----------------------------------------------------------

    @Test
    fun theFadeDecaysToZeroOverFadeOutMs() {
        val trail = GlideTrail()
        for (i in 0 until 10) {
            trail.addPoint(i.toFloat(), 0f, i * 16f)
        }
        assertEquals("a running gesture is not fading", 1f, trail.fadeFactor(10_000f), 0f)
        trail.startFadeOut(1_000f)
        assertTrue(trail.fadingOut)
        assertEquals(1f, trail.fadeFactor(1_000f), 0f)
        assertEquals(0.5f, trail.fadeFactor(1_000f + GlideTrail.FADE_OUT_MS / 2), 0.001f)
        assertEquals(0f, trail.fadeFactor(1_000f + GlideTrail.FADE_OUT_MS), 0f)
        assertTrue(trail.isFadeDone(1_000f + GlideTrail.FADE_OUT_MS))
        assertTrue("the ring is frozen for the fade", trail.size == 10)
    }

    @Test
    fun anEmptyTrailFadesNothing() {
        val trail = GlideTrail()
        trail.startFadeOut(1_000f)
        assertTrue("an empty ring never starts a fade", !trail.fadingOut)
        assertEquals(1f, trail.fadeFactor(1_100f), 0f)
        assertTrue(!trail.isFadeDone(10_000f))
    }

    @Test
    fun aNewGestureDropsAFadingRingWithoutABridge() {
        val trail = GlideTrail()
        for (i in 0 until 10) {
            trail.addPoint(i.toFloat(), 0f, i * 16f)
        }
        trail.startFadeOut(1_000f)
        // The next gesture's first point: the stale ring must not bridge into the new trail.
        trail.addPoint(500f, 500f, 2_000f)
        assertTrue(!trail.fadingOut)
        assertEquals(1, trail.size)
        assertEquals(500f, trail.xAt(0), 0f)
        assertEquals(1f, trail.fadeFactor(2_000f), 0f)
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

    @Test
    fun fadeFramesAllocateNothing() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        val trail = GlideTrail()
        for (i in 0 until GlideTrail.DEFAULT_CAPACITY) {
            trail.addPoint(i.toFloat(), (i * 2).toFloat(), i * 4f)
        }
        trail.startFadeOut(500f)
        fun perFrameBytes(iterations: Int): Long {
            val before = threadBean.getThreadAllocatedBytes(threadId)
            for (frame in 0 until iterations) {
                // The view's fade frame, read-side: done check, fade factor, window scan, alpha.
                val now = 500f + frame * 4f
                if (trail.isFadeDone(now)) break
                val factor = trail.fadeFactor(now)
                val first = trail.firstVisible()
                var acc = 0
                for (i in first until trail.size - 1) {
                    acc += (trail.alphaAt(i + 1) * factor).toInt()
                }
                assertTrue(acc >= 0)
            }
            val after = threadBean.getThreadAllocatedBytes(threadId)
            return (after - before) / iterations
        }
        perFrameBytes(200) // warmup: JIT
        val bytes = perFrameBytes(5_000)
        assertEquals("bytes/fade-frame", 0L, bytes)
    }
}
