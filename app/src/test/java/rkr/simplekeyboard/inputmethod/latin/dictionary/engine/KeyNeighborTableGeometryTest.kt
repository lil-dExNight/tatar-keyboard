package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edit class #2 source: geometric neighbours derived by [KeyNeighborTable] from the raw key
 * geometry, exercised through the reconstructed live Tatar layout ([E3bTestFixtures]).
 *
 * TT-TYPO-NEXT Phase B (docs/TT-TYPO-NEXT.md): the fixture now models the device geometry with
 * the horizontal gap subtracted from every key (KeyboardRow/Key), so the relation holds the 32
 * cross-row pairs a real build derives — the 33 same-row "touching" pairs of the pre-Phase-B
 * edge-to-edge model never exist on device (`right < left` for every same-row pair).
 */
class KeyNeighborTableGeometryTest {
    private val table = E3bTestFixtures.tatarNeighborTable()

    @Test
    fun degenerateGeometryProducesNoGeometricNeighbour() {
        // The E3a fixture puts every key at the same 0..10 rectangle: coincident rectangles do not
        // "touch" (no shared edge) and share one row, so no geometric neighbour is derived. This is
        // exactly what keeps the E3a (class #1) tests unaffected by the E3b geometric pass.
        val degenerate = E3aTestFixtures.tatarNeighborTable()
        for (letter in listOf('а', 'к', 'ә', 'о', 'т')) {
            assertNull(degenerate.geometricNeighborsOf(letter.code))
        }
    }

    @Test
    fun geometricNeighboursAreSymmetricAndSortedByCodePoint() {
        // "к" sits in the second letter row; its same-row neighbours (у, е) are separated from it
        // by the horizontal gap and never "touch" on device, so only the vertically overlapping
        // keys of the adjacent rows (ө above, а below) are its geometric neighbours — sorted by
        // code point.
        assertArrayEquals(
            intArrayOf('а'.code, 'ө'.code),
            table.geometricNeighborsOf('к'.code),
        )
        // Symmetry: ө lists к, and к lists ө.
        assertTrue(table.geometricNeighborsOf('ө'.code)!!.contains('к'.code))
        assertTrue(table.geometricNeighborsOf('к'.code)!!.contains('ө'.code))
    }

    @Test
    fun theTypoMotivatingPairSurvivesOnTheDeviceGeometry() {
        // The TT-TYPO-NEXT target: ц and ә sit in adjacent rows with ~79 % overlap, so the pair
        // survives the gap — this is the pair that turns "сцләм" into "сәләм".
        assertTrue(table.geometricNeighborsOf('ц'.code)!!.contains('ә'.code))
        assertTrue(table.geometricNeighborsOf('ә'.code)!!.contains('ц'.code))
    }

    @Test
    fun sameRowKeysAreNeverNeighboursBecauseTheGapSeparatesThem() {
        // Adjacent keys of one row (у/к, к/е, ш/щ, ...) shared an edge only in the pre-Phase-B
        // edge-to-edge model; on device the horizontal gap sits between them.
        assertTrue(table.geometricNeighborsOf('к'.code)!!.none { it == 'у'.code || it == 'е'.code })
        val neighbours = table.geometricNeighborsOf('щ'.code)
        assertTrue(neighbours != null && neighbours.isNotEmpty())
        assertTrue(neighbours!!.none { it == 'ш'.code || it == 'з'.code })
    }

    @Test
    fun fifthRowLettersAreGeometricallyConnectedToTheAlphabet() {
        // The whole point of the 35%-overlap rule: the wide fifth-row keys (ә ө ү җ ң һ) overlap the
        // narrower first-row keys and so keep a geometric link to the rest of the alphabet, instead
        // of being neighbours only of one another.
        for (fifth in listOf('ә', 'ө', 'ү', 'җ', 'ң', 'һ')) {
            val neighbours = table.geometricNeighborsOf(fifth.code)
            assertTrue("no geometric neighbour for $fifth", neighbours != null && neighbours.isNotEmpty())
            val hasNonFifthRow = neighbours!!.any { it !in "әөүҗңһ".map(Char::code) }
            assertTrue("$fifth is only connected to the fifth row", hasNonFifthRow)
        }
    }

    @Test
    fun theRelationHasThirtySevenKeysAndThirtyTwoUndirectedPairs() {
        // 37 = 6 + 11 + 11 + 9 letter keys (rowkeys_tatar*.xml); the node set also carries the two
        // more-key-only letters ё and ъ (no geometry of their own) for a total of 39 nodes. On the
        // device-true geometry the relation holds exactly the 32 cross-row pairs (Phase B,
        // docs/TT-TYPO-NEXT.md), with avg fan-out 64/37 = 1.73 and max 3 (н → р, җ, ү).
        assertEquals(37, table.letterKeyCount)
        assertEquals(39, table.nodes.size)
        val undirected = HashSet<Set<Int>>()
        var totalFanout = 0
        var maxFanout = 0
        for (node in table.nodes) {
            val neighbours = table.geometricNeighborsOf(node) ?: IntArray(0)
            totalFanout += neighbours.size
            maxFanout = maxOf(maxFanout, neighbours.size)
            for (partner in neighbours) undirected.add(setOf(node, partner))
        }
        assertEquals(32, undirected.size)
        assertEquals(3, maxFanout)
        assertEquals(1.73, totalFanout.toDouble() / table.letterKeyCount, 0.01)
    }
}
