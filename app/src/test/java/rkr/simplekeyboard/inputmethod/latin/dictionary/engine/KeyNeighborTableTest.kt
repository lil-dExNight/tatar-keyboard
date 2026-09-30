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
        val keys = listOf(E3aTestFixtures.rawKey('а', 'ә'))
        val table = KeyNeighborTable.build("tt_RU", false, keys)
        assertTrue(table.isEmpty)
        assertEquals(0, table.letterKeyCount)
        assertEquals(0, table.nodes.size)
        assertNull(table.longPressPartnersOf('а'.code))
    }

    @Test
    fun longPressPairsAreSymmetrizedAndDeduplicated() {
        val table = E3aTestFixtures.tatarNeighborTable()
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
        val table = E3aTestFixtures.tatarNeighborTable()
        for (letter in listOf('ә', 'ө', 'ү', 'җ', 'ң', 'һ')) {
            assertTrue("missing node $letter", table.nodes.contains(letter.code))
        }
    }

    @Test
    fun codesAreFoldedToNfcLowercaseAndNonLetterKeysDropped() {
        val keys = listOf(
            // Upper-case base + upper-case partner: folded to "а" / "ә".
            E3aTestFixtures.rawKey('А'.code, 'Ә'.code),
            // Space is a valid code point but not a letter -> dropped.
            E3aTestFixtures.rawKey(' '.code),
            // A negative special key code (e.g. CODE_DELETE) is not a code point -> dropped.
            E3aTestFixtures.rawKey(-5),
        )
        val table = KeyNeighborTable.build("tt_RU", true, keys)
        assertEquals(1, table.letterKeyCount)
        assertArrayEquals(intArrayOf('ә'.code), table.longPressPartnersOf('а'.code))
        assertFalse(table.isEmpty)
        assertNull(table.longPressPartnersOf(' '.code))
    }

    @Test
    fun theTatarLayoutHasThirtySevenLetterKeysAndThirtyNineNodes() {
        // 37 = 6 + 11 + 11 + 9 letter keys; the node set also carries the two more-key-only
        // letters ё and ъ, which are not keys of their own.
        val table = E3bTestFixtures.tatarNeighborTable()
        assertEquals(37, table.letterKeyCount)
        assertEquals(39, table.nodes.size)
    }

    @Test
    fun tableCarriesItsSubtypeId() {
        assertEquals("tt_RU", E3aTestFixtures.tatarNeighborTable().subtypeId)
    }
}
