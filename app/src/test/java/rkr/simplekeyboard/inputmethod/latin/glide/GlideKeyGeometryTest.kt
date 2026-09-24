package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [GlideKeyGeometry]: build normalization, lookups, nearest-key scan. */
class GlideKeyGeometryTest {

    private fun raw(codePoint: Char, left: Int, top: Int, right: Int, bottom: Int) =
        GlideKeyGeometry.RawKey(codePoint.code, left, top, right, bottom)

    @Test
    fun buildFoldsAndDropsNonLetters() {
        val geometry = GlideKeyGeometry.build(
            listOf(
                raw('А', 0, 0, 100, 100), // folds to lowercase
                raw('б', 100, 0, 200, 100),
                raw('5', 200, 0, 300, 100), // not a letter: dropped
                raw(' ', 300, 0, 400, 100), // not a letter: dropped
            ),
        )
        assertEquals(2, geometry.keyCount)
        assertTrue(geometry.keyIndexOfLetter('а'.code) >= 0)
        assertTrue(geometry.keyIndexOfLetter('б'.code) >= 0)
        assertTrue(geometry.keyIndexOfLetter('5'.code) < 0)
        assertEquals(100f, geometry.keyRadius, 0.0001f)
    }

    @Test
    fun findClosestKeysOrdersByDistanceWithDeterministicTies() {
        val geometry = GlideKeyGeometry.build(
            listOf(
                raw('а', 0, 0, 100, 100), // center (50, 50)
                raw('б', 100, 0, 200, 100), // center (150, 50)
                raw('в', 200, 0, 300, 100), // center (250, 50)
            ),
        )
        val out = IntArray(2)
        assertEquals(2, geometry.findClosestKeys(55f, 55f, out))
        assertEquals(geometry.keyIndexOfLetter('а'.code), out[0])
        assertEquals(geometry.keyIndexOfLetter('б'.code), out[1])
        // A point exactly between two keys: the lower key index wins the tie.
        val tied = IntArray(2)
        geometry.findClosestKeys(100f, 50f, tied)
        assertEquals(geometry.keyIndexOfLetter('а'.code), tied[0])
    }

    @Test
    fun findClosestKeysClampsToTheKeyCount() {
        val geometry = GlideKeyGeometry.build(listOf(raw('а', 0, 0, 100, 100)))
        val out = IntArray(4) { -1 }
        assertEquals(1, geometry.findClosestKeys(0f, 0f, out))
        assertEquals(0, out[0])
    }

    @Test
    fun emptyGeometryIsEmptyWithZeroRadius() {
        val geometry = GlideKeyGeometry.build(emptyList())
        assertTrue(geometry.isEmpty)
        assertEquals(0f, geometry.keyRadius, 0f)
        assertEquals(0, geometry.findClosestKeys(0f, 0f, IntArray(2)))
    }

    @Test
    fun tatarFixtureGeometryCoversTheThirtySevenLetterKeys() {
        val geometry = GlideTestFixtures.tatarGeometry()
        assertEquals(37, geometry.keyCount)
        assertEquals(GlideTestFixtures.tatarKeyRadius().toFloat(), geometry.keyRadius, 0.0001f)
        // Row 0 sits above row 1.
        assertTrue(
            geometry.centerY(geometry.keyIndexOfLetter('ә'.code)) <
                geometry.centerY(geometry.keyIndexOfLetter('й'.code)),
        )
    }
}
