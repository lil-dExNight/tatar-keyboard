package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [GlideKeyGeometry]: build normalization, lookups, nearest-key scan. */
class GlideKeyGeometryTest {

    private fun raw(codePoint: Char, left: Int, top: Int, right: Int, bottom: Int, more: String = "") =
        GlideKeyGeometry.RawKey(codePoint.code, left, top, right, bottom, more.codePoints().toArray())

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
    fun sameLayoutIgnoresLetterCaseAndKeyOrderButNotRectanglesOrLetters() {
        val lower = listOf(raw('а', 0, 0, 100, 100), raw('б', 100, 0, 200, 100))
        val base = GlideKeyGeometry.build(lower)
        val shifted = GlideKeyGeometry.build(
            listOf(raw('Б', 100, 0, 200, 100), raw('А', 0, 0, 100, 100), raw(' ', 0, 100, 200, 200)),
        )
        val moved = GlideKeyGeometry.build(listOf(raw('а', 0, 0, 100, 100), raw('б', 101, 0, 201, 100)))
        val taller = GlideKeyGeometry.build(listOf(raw('а', 0, 0, 100, 100), raw('б', 100, 0, 200, 101)))
        val otherLetter = GlideKeyGeometry.build(listOf(raw('а', 0, 0, 100, 100), raw('в', 100, 0, 200, 100)))
        val fewer = GlideKeyGeometry.build(listOf(raw('а', 0, 0, 100, 100)))

        assertTrue(base.sameLayoutAs(base))
        assertTrue(base.sameLayoutAs(GlideKeyGeometry.build(lower)))
        assertTrue(base.sameLayoutAs(shifted))
        assertTrue(shifted.sameLayoutAs(base))
        assertFalse(base.sameLayoutAs(moved))
        assertFalse(base.sameLayoutAs(taller))
        assertFalse(base.sameLayoutAs(otherLetter))
        assertFalse(base.sameLayoutAs(fewer))
        assertTrue(
            GlideTestFixtures.tatarGeometry().sameLayoutAs(GlideTestFixtures.tatarGeometry()),
        )
    }

    @Test
    fun longPressLettersWithoutAKeyResolveToTheirBaseKey() {
        val geometry = GlideTestFixtures.tatarGeometry()
        assertEquals(37, geometry.keyCount)
        assertEquals(geometry.keyIndexOfLetter('ь'.code), geometry.keyIndexOfLetter('ъ'.code))
        assertEquals(geometry.keyIndexOfLetter('е'.code), geometry.keyIndexOfLetter('ё'.code))
        // The fifth row gives the Tatar letters keys of their own, so on the Tatar layout they
        // are never aliased; ъ and ё are the only aliases.
        assertTrue(geometry.keyIndexOfLetter('ә'.code) != geometry.keyIndexOfLetter('а'.code))
        assertTrue(geometry.keyIndexOfLetter('һ'.code) != geometry.keyIndexOfLetter('х'.code))
        assertTrue(geometry.keyIndexOfLetter('ү'.code) != geometry.keyIndexOfLetter('у'.code))
        assertEquals(2, geometry.aliasCount)
        assertEquals('ъ'.code, geometry.aliasLetterAt(0))
        assertEquals('ё'.code, geometry.aliasLetterAt(1))
        assertEquals(geometry.keyIndexOfLetter('ь'.code), geometry.aliasKeyAt(0))
    }

    @Test
    fun digitsAndMarkersOnLongPressNeverBecomeAliases() {
        val geometry = GlideKeyGeometry.build(
            listOf(raw('а', 0, 0, 100, 100, "1ә%"), raw('б', 100, 0, 200, 100, "2")),
        )
        assertEquals(1, geometry.aliasCount)
        assertEquals('ә'.code, geometry.aliasLetterAt(0))
        assertEquals(-1, geometry.keyIndexOfLetter('1'.code))
        assertEquals(-1, geometry.keyIndexOfLetter('2'.code))
        assertEquals(-1, geometry.keyIndexOfLetter('%'.code))
        for (digit in '0'..'9') {
            assertEquals(-1, GlideTestFixtures.tatarGeometry().keyIndexOfLetter(digit.code))
            assertEquals(-1, GlideTestFixtures.russianGeometry().keyIndexOfLetter(digit.code))
        }
    }

    @Test
    fun sameLayoutDiffersWhenOnlyTheAliasesDiffer() {
        val plain = GlideKeyGeometry.build(listOf(raw('а', 0, 0, 100, 100), raw('б', 100, 0, 200, 100)))
        val aliasOnA = GlideKeyGeometry.build(listOf(raw('а', 0, 0, 100, 100, "ә"), raw('б', 100, 0, 200, 100)))
        val aliasOnB = GlideKeyGeometry.build(listOf(raw('а', 0, 0, 100, 100), raw('б', 100, 0, 200, 100, "ә")))
        val shiftedAliasOnA = GlideKeyGeometry.build(
            listOf(raw('А', 0, 0, 100, 100, "Ә"), raw('Б', 100, 0, 200, 100)),
        )
        assertFalse(plain.sameLayoutAs(aliasOnA))
        assertFalse(aliasOnA.sameLayoutAs(aliasOnB))
        assertTrue(aliasOnA.sameLayoutAs(shiftedAliasOnA))
    }

    @Test
    fun russianAliasesOnTwoKeysResolveThroughTheBaseTableWhateverTheKeyOrder() {
        val keys = GlideTestFixtures.russianRawKeys()
        for (order in listOf(keys, keys.reversed())) {
            val geometry = GlideKeyGeometry.build(order)
            assertEquals(geometry.keyIndexOfLetter('х'.code), geometry.keyIndexOfLetter('һ'.code))
            assertEquals(geometry.keyIndexOfLetter('а'.code), geometry.keyIndexOfLetter('ә'.code))
            assertEquals(geometry.keyIndexOfLetter('у'.code), geometry.keyIndexOfLetter('ү'.code))
            assertEquals(geometry.keyIndexOfLetter('н'.code), geometry.keyIndexOfLetter('ң'.code))
            assertEquals(geometry.keyIndexOfLetter('о'.code), geometry.keyIndexOfLetter('ө'.code))
            assertEquals(geometry.keyIndexOfLetter('ж'.code), geometry.keyIndexOfLetter('җ'.code))
            assertEquals(geometry.keyIndexOfLetter('е'.code), geometry.keyIndexOfLetter('ё'.code))
            assertEquals(geometry.keyIndexOfLetter('ь'.code), geometry.keyIndexOfLetter('ъ'.code))
            assertEquals(8, geometry.aliasCount)
        }
    }

    @Test
    fun aLetterOnTwoKeysWithoutABaseEntryGetsNoAlias() {
        // ө is not in ALIAS_BASES; һ is, but names х, which is none of its keys here.
        val geometry = GlideKeyGeometry.build(
            listOf(
                raw('а', 0, 0, 100, 100, "өһ"),
                raw('б', 100, 0, 200, 100, "өһ"),
                raw('в', 200, 0, 300, 100, "ү"),
            ),
        )
        assertEquals(-1, geometry.keyIndexOfLetter('ө'.code))
        assertEquals(-1, geometry.keyIndexOfLetter('һ'.code))
        assertEquals(geometry.keyIndexOfLetter('в'.code), geometry.keyIndexOfLetter('ү'.code))
        assertEquals(1, geometry.aliasCount)
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
