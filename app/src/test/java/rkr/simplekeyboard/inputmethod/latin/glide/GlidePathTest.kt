package rkr.simplekeyboard.inputmethod.latin.glide

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/** Unit tests for [GlidePath] basics and the cross-thread snapshot copy. */
class GlidePathTest {

    @Test
    fun lengthAccumulatesSegmentDistances() {
        val path = GlidePath()
        path.addPoint(0f, 0f, 0f)
        path.addPoint(3f, 4f, 10f)
        path.addPoint(3f, 4f, 20f)
        path.addPoint(6f, 8f, 30f)
        assertEquals(10f, path.length(), 0.0001f)
        assertEquals(0f, path.firstX, 0f)
        assertEquals(6f, path.lastX, 0f)
        assertEquals(8f, path.lastY, 0f)
    }

    @Test
    fun copyIntoSnapshotsAllArrays() {
        val source = GlidePath()
        for (i in 0 until 100) {
            source.addPoint(i.toFloat(), (2 * i).toFloat(), (8 * i).toFloat())
        }
        val target = GlidePath()
        target.addPoint(-1f, -1f, -1f)
        assertEquals(100, source.copyInto(target))
        assertEquals(100, target.size)
        for (i in 0 until 100) {
            assertEquals(i.toFloat(), target.xs[i], 0f)
            assertEquals((2 * i).toFloat(), target.ys[i], 0f)
            assertEquals((8 * i).toFloat(), target.ts[i], 0f)
        }
        // The copy is a snapshot: editing the source must not leak into the target.
        source.clear()
        source.addPoint(999f, 999f, 999f)
        assertEquals(100, target.size)
        assertEquals(0f, target.xs[0], 0f)
    }

    @Test
    fun copyIntoThinsAtTheTargetCapacity() {
        val source = GlidePath(16)
        repeat(16) { source.addPoint(it.toFloat(), 0f, it.toFloat()) }
        val target = GlidePath(8)
        assertEquals(8, source.copyInto(target))
        assertEquals(8, target.size)
        assertEquals(0f, target.xs[0], 0f)
        assertEquals(15f, target.xs[7], 0f)
        assertEquals(15f, target.ts[7] - target.ts[0], 0f)
    }

    @Test
    fun emptyPathHasZeroLengthAndZeroedEnds() {
        val path = GlidePath()
        assertEquals(0f, path.length(), 0f)
        assertEquals(0f, path.firstX, 0f)
        assertEquals(0f, path.lastY, 0f)
        assertTrue(path.size == 0)
    }

    /** Non-finite samples would poison every distance measured from the path. */
    @Test
    fun nonFinitePointsAreRefusedFailClosed() {
        val path = GlidePath()
        assertTrue(path.addPoint(1f, 2f, 0f))
        assertFalse(path.addPoint(Float.NaN, 2f, 1f))
        assertFalse(path.addPoint(1f, Float.POSITIVE_INFINITY, 1f))
        assertFalse(path.addPoint(1f, 2f, Float.NaN))
        assertFalse(path.addPoint(1f, Float.NEGATIVE_INFINITY, 1f))

        assertEquals("the poisoned samples never entered the buffer", 1, path.size)
        assertTrue(path.length().isFinite())
        assertEquals(1f, path.firstX, 0f)
        assertEquals(1f, path.lastX, 0f)

        // And a valid point after the junk still lands.
        assertTrue(path.addPoint(4f, 6f, 10f))
        assertEquals(2, path.size)
        assertEquals(5f, path.length(), 0.0001f)
    }

    @Test
    fun eventTimesAreRelativeBeforeNarrowingAndExactDuplicatesAreSkipped() {
        val offsets = longArrayOf(0L, 1L shl 24, 1L shl 27, 1L shl 30)
        val expected = GlidePath()
        for ((index, offset) in offsets.withIndex()) {
            val path = GlidePath()
            assertTrue(path.addPoint(10f, 20f, offset, offset))
            assertTrue(path.addPoint(30f, 40f, offset + 1_025L, offset))
            assertFalse(path.addPoint(30f, 40f, offset + 1_025L, offset))
            assertEquals(2, path.size)
            assertEquals(0f, path.ts[0], 0f)
            assertEquals(1_025f, path.ts[1], 0f)
            // A stationary lift at a later time is not a duplicate and extends the duration.
            assertTrue(path.addPoint(30f, 40f, offset + 2_049L, offset))
            assertEquals(2_049f, path.ts[2] - path.ts[0], 0f)
            if (index == 0) {
                path.copyInto(expected)
            } else {
                for (i in 0 until path.size) {
                    assertEquals(expected.ts[i].toBits(), path.ts[i].toBits())
                }
            }
        }
    }

    @Test
    fun overflowPreservesBothEndsAndDurationForDensePaths() {
        for (sampleCount in intArrayOf(513, 1_024, 2_048)) {
            val path = GlidePath()
            for (i in 0 until sampleCount) {
                val y = if (i % 32 < 16) (i % 16).toFloat() else (16 - i % 16).toFloat()
                assertTrue(path.addPoint(i.toFloat(), y, i.toFloat()))
            }
            assertEquals(GlidePath.MAX_POINTS, path.size)
            assertEquals(0f, path.xs[0], 0f)
            assertEquals(0f, path.ys[0], 0f)
            assertEquals(0f, path.ts[0], 0f)
            assertEquals((sampleCount - 1).toFloat(), path.xs[path.size - 1], 0f)
            assertEquals((sampleCount - 1).toFloat(), path.ts[path.size - 1], 0f)
        }
    }

    @Test
    fun overflowRemovesTheLeastArcLengthSignificantInteriorPoint() {
        val path = GlidePath(3)
        path.addPoint(0f, 0f, 0f)
        path.addPoint(1f, 0f, 1f)
        path.addPoint(2f, 0f, 2f)
        path.addPoint(2f, 100f, 3f)
        assertEquals(3, path.size)
        assertEquals(2f, path.xs[1], 0f)
        assertEquals(0f, path.ys[1], 0f)
        assertEquals(100f, path.ys[2], 0f)
    }

    @Test
    fun overflowThinningAllocatesNothing() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val path = GlidePath()
        fun addSamples(count: Int, start: Int) {
            for (i in 0 until count) {
                val sample = start + i
                path.addPoint(sample.toFloat(), (sample % 17).toFloat(), sample.toFloat())
            }
        }
        addSamples(4_096, 0)
        val before = threadBean.getThreadAllocatedBytes(threadId)
        addSamples(2_048, 4_096)
        val after = threadBean.getThreadAllocatedBytes(threadId)
        assertEquals("bytes while thinning a full path", 0L, after - before)
    }
}
