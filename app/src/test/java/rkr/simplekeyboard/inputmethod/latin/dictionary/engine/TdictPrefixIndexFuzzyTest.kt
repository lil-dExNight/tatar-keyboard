package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.abs

class TdictPrefixIndexFuzzyTest {
    private val table = E3aTestFixtures.tatarNeighborTable()

    private fun index(entries: List<Pair<String, Long>>, withTable: Boolean = true): TdictPrefixIndex {
        val index = EngineTestFixtures.index(entries)
        if (withTable) index.updateKeyNeighbors(table)
        return index
    }

    private fun lookup(index: TdictPrefixIndex, prefix: String): List<String> =
        index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))

    // Rule 1: a fuzzy candidate never outranks an exact one, whatever its frequency.
    @Test
    fun fuzzyCandidateNeverOutranksAnExactCandidateEvenWithHigherFrequency() {
        val index = index(
            listOf(
                "китап" to 10L,       // exact block of the typed prefix "кита"
                "китәм" to 5L,        // fuzzy block of the variant "китә" (а→ә)
                "китәп" to 9_999L,    // fuzzy, far higher frequency than any exact
            ),
        )
        // "китәп" has the highest frequency in the whole dictionary, yet it stays BELOW the single
        // exact candidate "китап"; the exact level is exhausted first.
        assertEquals(listOf("китап", "китәп", "китәм"), lookup(index, "кита"))
    }

    // Rule 2: within a level the frozen order holds — frequency descending, then code-point lexical
    // ascending on ties.
    @Test
    fun withinTheFuzzyLevelOrderIsFrequencyDescendingThenCodePointAscending() {
        val index = index(
            listOf(
                "күл" to 50L,     // fuzzy (variant "күл" of typed "кул", у→ү), ties with "күлә"
                "күлә" to 50L,    // fuzzy, same frequency -> code-point/length tie-break
                "күләк" to 100L,  // fuzzy, highest frequency -> first
            ),
        )
        // No exact candidate ("кул" begins no word), so all three fuzzy cells rank among themselves:
        // "күләк" by frequency, then the 50-tie broken by code point ("күл" before "күлә").
        assertEquals(listOf("күләк", "күл", "күлә"), lookup(index, "кул"))
    }

    // Rule 3: the form equal to the typed word is excluded on both levels.
    @Test
    fun theTypedWordFormIsExcludedFromBothLevels() {
        val index = index(
            listOf(
                "бала" to 20L,      // == typed word: excluded on the exact level
                "балалар" to 5L,    // exact continuation
                "балә" to 9L,       // fuzzy (variant "балә", а→ә at the last position)
                "бәла" to 8L,       // fuzzy (variant "бәла", а→ә at the second position)
            ),
        )
        val result = lookup(index, "бала")
        assertFalse("typed word must never be offered", result.contains("бала"))
        assertEquals("балалар", result[0])
        // Exact first, fuzzy fills the rest; the excluded word never reappears via the fuzzy level.
        assertEquals(listOf("балалар", "балә", "бәла"), result)
    }

    // Rule 4 (characterization): with no fuzzy candidates the order is exactly D1's.
    @Test
    fun withNoNeighborTableTheResultIsByteForByteTheD1Result() {
        val entries = listOf(
            "бал" to 50L,
            "бала" to 10L,
            "балалар" to 4L,
            "балан" to 9L,
            "балчык" to 9L,
            "бар" to 100L,
        )
        val withoutTable = index(entries, withTable = false)
        // Identical to the frozen D1 expectation in TdictPrefixIndexTest.
        assertEquals(listOf("бала", "балан", "балчык"), lookup(withoutTable, "бал"))
        assertEquals(listOf("балан", "балалар"), lookup(withoutTable, "бала"))
    }

    @Test
    fun theFuzzyLevelIsSkippedWhenTheExactPassAlreadyFillsAllThreeCells() {
        val index = index(
            listOf(
                "бал" to 50L,
                "бала" to 10L,
                "балалар" to 4L,
                "балан" to 9L,
                "балчык" to 9L,
                "бар" to 100L,
                "бәлеш" to 9_999L,   // a fuzzy candidate that must NOT appear (exact fills 3 cells)
            ),
        )
        val result = lookup(index, "бал")
        assertEquals(listOf("бала", "балан", "балчык"), result)
        assertFalse(result.contains("бәлеш"))
    }

    @Test
    fun theFuzzyLevelIsGatedOnAThreeCodePointPrefix() {
        val entries = listOf(
            "аул" to 5L,      // exact block of "ау" and the typed word for "аул"
            "аүл" to 50L,     // fuzzy block of variant "аүл" (у→ү)
            "әул" to 100L,    // fuzzy block of variant "әул" (а→ә)
        )
        // Two code points (four bytes): the fuzzy pass must not run.
        assertEquals(listOf("аул"), lookup(index(entries), "ау"))
        // Three code points: the fuzzy pass runs and fills the cells left empty by the exact pass
        // (which excluded the typed word "аул").
        assertEquals(listOf("әул", "аүл"), lookup(index(entries), "аул"))
    }

    @Test
    fun theSettingATableDoesNotChangeAnyExactResultAcrossPrefixes() {
        val entries = listOf(
            "бал" to 50L,
            "бала" to 10L,
            "балалар" to 4L,
            "балан" to 9L,
            "балчык" to 9L,
            "бар" to 100L,
        )
        val withTable = index(entries, withTable = true)
        val withoutTable = index(entries, withTable = false)
        for (prefix in listOf("б", "ба", "бал", "бала", "балан", "бар", "я")) {
            assertEquals(
                "prefix=$prefix",
                lookup(withoutTable, prefix),
                lookup(withTable, prefix),
            )
        }
    }

    // Allocation: per-lookup allocation does not depend on the number of variants generated.
    @Test
    fun perLookupAllocationDoesNotDependOnTheNumberOfVariants() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        // Neither prefix matches any word, so both return the same (empty) result: the only
        // difference between them is how many fuzzy variants are generated and scanned.
        val index = index(listOf("мин" to 5L, "син" to 5L))
        val fewVariants = ImmutableUtf8Prefix.copyOf("сит".toByteArray(Charsets.UTF_8)) // 0 variants
        val manyVariants = ImmutableUtf8Prefix.copyOf("аоуана".toByteArray(Charsets.UTF_8)) // 6 variants
        assertTrue(lookup(index, "сит").isEmpty())
        assertTrue(lookup(index, "аоуана").isEmpty())

        repeat(50_000) {
            index.lookup(fewVariants)
            index.lookup(manyVariants)
        }

        val iterations = 200_000
        fun perLookupBytes(prefix: ImmutableUtf8Prefix): Long {
            val before = threadBean.getThreadAllocatedBytes(threadId)
            for (i in 0 until iterations) index.lookup(prefix)
            val after = threadBean.getThreadAllocatedBytes(threadId)
            return (after - before) / iterations
        }

        val few = perLookupBytes(fewVariants)
        val many = perLookupBytes(manyVariants)
        // Independent of variant count: the many-variant lookup allocates no more per call than the
        // no-variant one. A handful of bytes of slack absorbs measurement noise.
        assertTrue("few=$few many=$many bytes/lookup", abs(many - few) <= 8L)
        assertTrue("many=$many bytes/lookup", many <= 8L)
    }

    // TT-TYPO-NEXT Phase C (G3-C): the same allocation contract with edit class #4 engaged — the
    // calibrated Tatar policy {1, 4}, so the heavy arm runs the probe-first full substitution
    // (152 probes, 0 survivors, 0 scanned variants). Both prefixes return the SAME empty result
    // (the result-list materialization of a non-empty lookup is pre-existing and out of scope);
    // the probe path and the bonus branch are primitive arithmetic — they allocate nothing.
    @Test
    fun perLookupAllocationWithTheTatarPolicyDoesNotDependOnTheNumberOfProbes() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        val geometricTable = E3bTestFixtures.tatarNeighborTable()
        val index = EngineTestFixtures.index(
            listOf("мин" to 5L, "син" to 5L),
            fuzzyEditPolicy = FuzzyEditPolicy.TATAR,
        )
        index.updateKeyNeighbors(geometricTable)
        // "abc": no letter is in the table at all -> 0 variants, 0 probes. "мсмә": 4 code points
        // with an empty exact pass -> the class #4 gate fires; with the Phase-C2 range narrowing
        // only positions 0 ("*смә", whole dictionary) and 1 ("м*мә", the "мин" range) are probed —
        // 2 x 38 = 76 probes, no survivor.
        val fewVariants = ImmutableUtf8Prefix.copyOf("abc".toByteArray(Charsets.UTF_8))
        val manyProbes = ImmutableUtf8Prefix.copyOf("мсмә".toByteArray(Charsets.UTF_8))
        assertTrue(index.lookup(fewVariants).isEmpty())
        assertTrue(index.lookup(manyProbes).isEmpty())
        assertEquals(76, index.lastFuzzyProbeCount)

        repeat(50_000) {
            index.lookup(fewVariants)
            index.lookup(manyProbes)
        }

        val iterations = 200_000
        fun perLookupBytes(prefix: ImmutableUtf8Prefix): Long {
            val before = threadBean.getThreadAllocatedBytes(threadId)
            for (i in 0 until iterations) index.lookup(prefix)
            val after = threadBean.getThreadAllocatedBytes(threadId)
            return (after - before) / iterations
        }

        val few = perLookupBytes(fewVariants)
        val many = perLookupBytes(manyProbes)
        assertTrue("few=$few many=$many bytes/lookup", abs(many - few) <= 8L)
        assertTrue("many=$many bytes/lookup", many <= 8L)
    }
}
