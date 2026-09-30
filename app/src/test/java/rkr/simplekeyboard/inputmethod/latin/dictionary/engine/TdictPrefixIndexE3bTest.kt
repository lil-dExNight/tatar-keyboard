package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine behavior on the live path of an engine opened WITHOUT an explicit fuzzy policy, i.e.
 * [FuzzyEditPolicy.DEFAULT] (class #1 long-press partner only; the Russian engine's
 * configuration), with the full Tatar alphabet table.
 */
class TdictPrefixIndexE3bTest {
    private val table = E3bTestFixtures.tatarNeighborTable()

    private fun index(entries: List<Pair<String, Long>>, withTable: Boolean = true): TdictPrefixIndex {
        val index = EngineTestFixtures.index(entries)
        if (withTable) index.updateKeyNeighbors(table)
        return index
    }

    private fun lookup(index: TdictPrefixIndex, prefix: String): List<String> =
        index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))

    @Test
    fun theExactCandidateIsKeptFirstAndTheDisabledClassesAddNothing() {
        // "кита" has one exact continuation (китап); the shipped fuzzy pass (class #1 only) finds no
        // long-press variant of "кита" that matches a word, so the result is the exact candidate
        // alone, never shifted.
        val index = index(
            listOf(
                // Code-point sorted, as the tdict fixture requires.
                "кита" to 20L,    // == typed word, excluded
                "китап" to 10L,   // exact continuation of "кита"
                "кутап" to 9L,    // not on the class-#1 path — must not appear
            ),
        )
        assertEquals(listOf("китап"), lookup(index, "кита"))
    }

    @Test
    fun aClass1LongPressTypoIsRecoveredAndNeverDuplicated() {
        // "кум" is "күм" with ү mistyped as its long-press base у. Class #1 (у→ү) recovers "күмеш";
        // de-duplication by dictionary index guarantees it never fills two cells.
        val index = index(listOf("күмеш" to 10L))
        val result = lookup(index, "кум")
        assertEquals(listOf("күмеш"), result)
        assertEquals(result.distinct(), result)
    }

    @Test
    fun theWholeFuzzyLevelIsDroppedWhenTheVariantBudgetIsExceeded() {
        // A synthetic table whose 'к' key carries 65 long-press partners — one past the engine's
        // MAX_FUZZY_VARIANTS budget — makes class #1 alone overflow on a prefix starting with 'к'.
        // Latin + Cyrillic + Greek lowercase letters are all NFC-stable and distinct, so the built
        // partner set keeps all 65.
        val fakePartners = ((0x61..0x7A) + (0x430..0x44F) + (0x3B1..0x3C9))
            .filter { it != 'к'.code }
            .take(65)
            .toIntArray()
        assertEquals(65, fakePartners.size)
        assertTrue("target partner must be present", fakePartners.contains('з'.code))
        val overloaded = KeyNeighborTable.build(
            "tt_RU", true,
            listOf(
                KeyNeighborTable.RawKey('к'.code, fakePartners),
                E3aTestFixtures.rawKey('о'), E3aTestFixtures.rawKey('т'),
            ),
        )
        assertEquals(65, overloaded.longPressPartnersOf('к'.code)!!.size)
        val small = KeyNeighborTable.build(
            "tt_RU", true,
            listOf(
                KeyNeighborTable.RawKey('к'.code, intArrayOf('з'.code)),
                E3aTestFixtures.rawKey('о'), E3aTestFixtures.rawKey('т'),
            ),
        )
        val index = EngineTestFixtures.index(listOf("зот" to 5L))
        // With a small table, class #1 (к→з) recovers "зот".
        index.updateKeyNeighbors(small)
        assertEquals(listOf("зот"), lookup(index, "кот"))
        assertTrue(!index.lastFuzzyOverBudget)
        // With the overloaded table the budget is exceeded, so the WHOLE level is dropped — the
        // partial "зот" found mid-generation is discarded, not kept.
        index.updateKeyNeighbors(overloaded)
        assertEquals(emptyList<String>(), lookup(index, "кот"))
        assertTrue(index.lastFuzzyOverBudget)
    }

    @Test
    fun theResultIsDeterministicAcrossManyRepeats() {
        val index = index(listOf("күмеш" to 10L, "күмешле" to 4L))
        val first = lookup(index, "кум")
        repeat(1000) { assertEquals(first, lookup(index, "кум")) }
    }
}
