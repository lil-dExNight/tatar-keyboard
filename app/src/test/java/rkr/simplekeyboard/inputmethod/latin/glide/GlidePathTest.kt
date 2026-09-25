package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun copyIntoCapsAtTheTargetCapacity() {
        val source = GlidePath(16)
        repeat(16) { source.addPoint(it.toFloat(), 0f, 0f) }
        val target = GlidePath(8)
        assertEquals(8, source.copyInto(target))
        assertEquals(8, target.size)
        assertEquals(7f, target.xs[7], 0f)
    }

    @Test
    fun emptyPathHasZeroLengthAndZeroedEnds() {
        val path = GlidePath()
        assertEquals(0f, path.length(), 0f)
        assertEquals(0f, path.firstX, 0f)
        assertEquals(0f, path.lastY, 0f)
        assertTrue(path.size == 0)
    }

    /**
     * 2026-09-25 audit, F14: a NaN/Infinity sample would poison every distance the decoder
     * measures from the path, so it is refused at the gate — fail-closed, like the capacity cap.
     */
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
}
