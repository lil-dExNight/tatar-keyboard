package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** Unit tests for [GlideResampler]: exact-N semantics, endpoint preservation, degenerate inputs. */
class GlideResamplerTest {

    @Test
    fun resampleProducesExactlyNPointsIncludingBothEndpoints() {
        val xs = floatArrayOf(0f, 100f, 100f, 200f)
        val ys = floatArrayOf(0f, 0f, 100f, 100f)
        val outX = FloatArray(50)
        val outY = FloatArray(50)
        val count = GlideResampler.resample(xs, ys, 4, outX, outY, 50)
        assertEquals(50, count)
        assertEquals(0f, outX[0], 0.0001f)
        assertEquals(0f, outY[0], 0.0001f)
        assertEquals(200f, outX[49], 0.5f)
        assertEquals(100f, outY[49], 0.5f)
    }

    @Test
    fun resampleIsEquidistantAlongAStraightPath() {
        val xs = floatArrayOf(0f, 300f)
        val ys = floatArrayOf(0f, 0f)
        val outX = FloatArray(50)
        val outY = FloatArray(50)
        assertEquals(50, GlideResampler.resample(xs, ys, 2, outX, outY, 50))
        val first = distance(outX, outY, 0)
        assertEquals(300f / 49f, first, 0.01f)
        for (i in 1 until 49) {
            assertEquals(first, distance(outX, outY, i), 0.01f)
        }
        // A corner path: the last sample sits exactly at the path end.
        val cornerX = floatArrayOf(0f, 100f, 100f)
        val cornerY = floatArrayOf(0f, 0f, 100f)
        assertEquals(50, GlideResampler.resample(cornerX, cornerY, 3, outX, outY, 50))
        assertEquals(100f, outX[49], 0.5f)
        assertEquals(100f, outY[49], 0.5f)
    }

    @Test
    fun resampleOfASinglePointRepeatsIt() {
        val xs = floatArrayOf(7f)
        val ys = floatArrayOf(-3f)
        val outX = FloatArray(10)
        val outY = FloatArray(10)
        assertEquals(10, GlideResampler.resample(xs, ys, 1, outX, outY, 10))
        for (i in 0 until 10) {
            assertEquals(7f, outX[i], 0f)
            assertEquals(-3f, outY[i], 0f)
        }
    }

    @Test
    fun resampleSkipsZeroLengthSegments() {
        // A doubled letter's plain ideal path visits the same center twice.
        val xs = floatArrayOf(0f, 50f, 50f, 100f)
        val ys = floatArrayOf(0f, 0f, 0f, 0f)
        val outX = FloatArray(11)
        val outY = FloatArray(11)
        assertEquals(11, GlideResampler.resample(xs, ys, 4, outX, outY, 11))
        for (i in 0 until 11) {
            assertEquals(i * 10f, outX[i], 0.5f)
        }
    }

    @Test
    fun resampleOfAnEmptyPathWritesNothing() {
        val outX = FloatArray(4) { -1f }
        val outY = FloatArray(4) { -1f }
        assertEquals(0, GlideResampler.resample(FloatArray(0), FloatArray(0), 0, outX, outY, 4))
        assertEquals(-1f, outX[0], 0f)
    }

    @Test
    fun normalizeCentersTheBoundingBoxAtOrigin() {
        val xs = floatArrayOf(10f, 30f)
        val ys = floatArrayOf(100f, 140f)
        val outX = FloatArray(2)
        val outY = FloatArray(2)
        GlideResampler.normalizeByBoxSide(xs, ys, 2, 10f, 30f, 100f, 140f, outX, outY)
        // The longest side is 40 (y); the bbox center maps to the origin.
        assertEquals(-0.25f, outX[0], 0.0001f)
        assertEquals(0.25f, outX[1], 0.0001f)
        assertEquals(-0.5f, outY[0], 0.0001f)
        assertEquals(0.5f, outY[1], 0.0001f)
    }

    @Test
    fun normalizeOfACoincidentPathIsFinite() {
        val xs = floatArrayOf(5f, 5f, 5f)
        val ys = floatArrayOf(5f, 5f, 5f)
        val outX = FloatArray(3)
        val outY = FloatArray(3)
        GlideResampler.normalizeByBoxSide(xs, ys, 3, 5f, 5f, 5f, 5f, outX, outY)
        for (i in 0 until 3) {
            assertTrue(outX[i].isFinite())
            assertTrue(outY[i].isFinite())
        }
    }

    private fun distance(xs: FloatArray, ys: FloatArray, i: Int): Float {
        val dx = xs[i + 1] - xs[i]
        val dy = ys[i + 1] - ys[i]
        return sqrt(dx * dx + dy * dy)
    }
}
