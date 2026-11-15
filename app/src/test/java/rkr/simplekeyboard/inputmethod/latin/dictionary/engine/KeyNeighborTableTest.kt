package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyNeighborTableTest {
    @Test
    fun onlyTheAlphabetElementIsAValidSource() {
        // Same keys, non-alphabet element: the whole table is empty and holds no pair or node.
        val keys = listOf(LongPressTestFixtures.rawKey('а', 'ә'))
        val table = KeyNeighborTable.build("tt_RU", false, keys)
        assertTrue(table.isEmpty)
        assertEquals(0, table.letterKeyCount)
        assertEquals(0, table.nodes.size)
        assertNull(table.longPressPartnersOf('а'.code))
    }

    @Test
    fun longPressPairsAreSymmetrizedAndDeduplicated() {
        val table = LongPressTestFixtures.tatarNeighborTable()
        // Forward direction taken straight from the layout.
        assertArrayEquals(intArrayOf('ә'.code), table.longPressPartnersOf('а'.code))
        // Reverse direction added by symmetrization; two declarations of "ә" collapse to one node
        // whose partners are both of its bases, in code-point order.
        assertArrayEquals(intArrayOf('а'.code, 'э'.code), table.longPressPartnersOf('ә'.code))
        // "һ" is declared on both "г" and "х"; symmetrization gives it both bases.
        assertArrayEquals(intArrayOf('г'.code, 'х'.code), table.longPressPartnersOf('һ'.code))
        assertArrayEquals(intArrayOf('һ'.code), table.longPressPartnersOf('г'.code))
        // A plain letter has no partners at all.
        assertNull(table.longPressPartnersOf('к'.code))
    }

    @Test
    fun everyFifthRowLetterBecomesANode() {
        val table = LongPressTestFixtures.tatarNeighborTable()
        for (letter in listOf('ә', 'ө', 'ү', 'җ', 'ң', 'һ')) {
            assertTrue("missing node $letter", table.nodes.contains(letter.code))
        }
    }

    @Test
    fun codesAreFoldedToNfcLowercaseAndNonLetterKeysDropped() {
        val keys = listOf(
            // Upper-case base + upper-case partner: folded to "а" / "ә".
            LongPressTestFixtures.rawKey('А'.code, 'Ә'.code),
            // Space is a valid code point but not a letter -> dropped.
            LongPressTestFixtures.rawKey(' '.code),
            // A negative special key code (e.g. CODE_DELETE) is not a code point -> dropped.
            LongPressTestFixtures.rawKey(-5),
        )
        val table = KeyNeighborTable.build("tt_RU", true, keys)
        assertEquals(1, table.letterKeyCount)
        assertArrayEquals(intArrayOf('ә'.code), table.longPressPartnersOf('а'.code))
        assertFalse(table.isEmpty)
        assertNull(table.longPressPartnersOf(' '.code))
    }

    @Test
    fun theTatarLayoutHasThirtyNineLetterKeysAndThirtyNineNodes() {
        // 39 = 8 + 11 + 11 + 9 letter keys: the extra row carries ё and ъ as direct keys, so
        // every node of the alphabet is a key with its own rectangle.
        val table = TatarLayoutTestFixtures.tatarNeighborTable()
        assertEquals(39, table.letterKeyCount)
        assertEquals(39, table.nodes.size)
        for (letter in listOf('ә', 'ө', 'ү', 'җ', 'ң', 'һ', 'ё', 'ъ')) {
            assertTrue("missing node $letter", table.nodes.contains(letter.code))
        }
    }

    @Test
    fun tableCarriesItsSubtypeId() {
        assertEquals("tt_RU", LongPressTestFixtures.tatarNeighborTable().subtypeId)
    }

    @Test
    fun geometricNeighborsFollowTheLayoutGeometry() {
        val table = TatarLayoutTestFixtures.tatarNeighborTable()
        // Cross-row pairs of the layout-derived relation, recomputed from the shipped rows:
        // scripts/typo_pack.py's read_layout_geometric_map derives the same map.
        assertTrue(table.areGeometricNeighbors('к'.code, 'а'.code))
        assertTrue(table.areGeometricNeighbors('у'.code, 'ө'.code))
        assertTrue(table.areGeometricNeighbors('н'.code, 'җ'.code))
        // Same-row adjacent keys, the physically closest pairs.
        assertTrue(table.areGeometricNeighbors('к'.code, 'е'.code))
        assertTrue(table.areGeometricNeighbors('ә'.code, 'ө'.code))
        // The rule is symmetric.
        assertTrue(table.areGeometricNeighbors('а'.code, 'к'.code))
        // ё and ъ are direct keys now, so they carry rectangles and join pairs of their own.
        assertTrue(table.areGeometricNeighbors('ё'.code, 'з'.code))
        assertTrue(table.areGeometricNeighbors('ъ'.code, 'х'.code))
        assertTrue(table.areGeometricNeighbors('ё'.code, 'ъ'.code))
        // Distant keys are not neighbors: one key over on the same row, the near-miss across
        // rows (ү↔у, the nearest false pair of the whole layout), and keys two rows apart.
        assertFalse(table.areGeometricNeighbors('к'.code, 'н'.code))
        assertFalse(table.areGeometricNeighbors('ү'.code, 'у'.code))
        assertFalse(table.areGeometricNeighbors('ә'.code, 'а'.code))
        // A direct key is not a neighbor of its long-press base row partner by distance.
        assertFalse(table.areGeometricNeighbors('ё'.code, 'е'.code))
        assertFalse(table.areGeometricNeighbors('е'.code, 'ё'.code))
        // A key is never its own neighbor.
        assertFalse(table.areGeometricNeighbors('к'.code, 'к'.code))
        // The full relation over the node set: 34 cross-row pairs plus the 35 same-row adjacent
        // pairs the radius admits (7 on the eight-key extra row, 10 + 10 on the home rows, 8 on
        // the third row). Every node has a rectangle, so no letter is excluded.
        var pairs = 0
        for (first in table.nodes) {
            for (second in table.nodes) {
                if (first < second && table.areGeometricNeighbors(first, second)) pairs++
            }
        }
        assertEquals(69, pairs)
    }

    @Test
    fun keysWithoutGeometryJoinNoGeometricPair() {
        // The legacy fixture form (no rectangle) keeps the long-press pairs and the node set but
        // carries no geometric adjacency, so the neighbor bonus can never fire on it.
        val table = LongPressTestFixtures.tatarNeighborTable()
        assertFalse(table.areGeometricNeighbors('а'.code, 'ә'.code))
        assertFalse(table.areGeometricNeighbors('к'.code, 'е'.code))
    }

    @Test
    fun aNonAlphabetElementCarriesNoGeometricPair() {
        val table = KeyNeighborTable.build(
            "tt_RU", false, listOf(LongPressTestFixtures.rawKey('а', 'ә')),
        )
        assertFalse(table.areGeometricNeighbors('а'.code, 'ә'.code))
    }
}
