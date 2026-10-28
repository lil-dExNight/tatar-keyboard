package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipped fuzzy policies as executable pins:
 *
 *  - [FuzzyEditPolicy.DEFAULT]: class #1 (long-press partner) only, no same-length bonus. Every
 *    engine opened without an explicit policy runs it, the Russian engine included.
 *  - [FuzzyEditPolicy.TATAR]: class #1 on the always-on domain, the DL-1 tier (substitution,
 *    deletion, insertion and transposition under one rank tag, gated on an empty exact pass at
 *    >= 4 code points) on the tier domain, and the same-length bonus. Autocorrect stays class #1.
 */
class TdictPrefixIndexShippedFuzzyClassesTest {
    private val table = E3bTestFixtures.tatarNeighborTable()

    private fun index(
        entries: List<Pair<String, Long>>,
        policy: FuzzyEditPolicy? = null,
    ): TdictPrefixIndex {
        val index = EngineTestFixtures.index(entries, fuzzyEditPolicy = policy)
        index.updateKeyNeighbors(table)
        return index
    }

    private fun lookup(index: TdictPrefixIndex, prefix: String): List<String> =
        index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))

    /**
     * Source contract, part 1: the DEFAULT policy (every engine without an explicit one, the
     * Russian engine included) is class #1 only, with no same-length bonus.
     */
    @Test
    fun theDefaultPolicyIsExactlyThePrePhaseBShippedConfiguration() {
        assertEquals(
            listOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS),
            FuzzyEditPolicy.DEFAULT.editClasses.toList(),
        )
        assertFalse(FuzzyEditPolicy.DEFAULT.sameLengthBonus)
    }

    /**
     * Source contract, part 2: the TATAR policy carries class #1 (always-on domain) and
     * EDIT_CLASS_SUBSTITUTION, which engages the DL-1 tier on the gated domain, with the
     * same-length bonus.
     */
    @Test
    fun theTatarPolicyRunsClassesOneAndFourWithTheSameLengthBonus() {
        assertEquals(
            listOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
            FuzzyEditPolicy.TATAR.editClasses.toList(),
        )
        assertTrue(FuzzyEditPolicy.TATAR.sameLengthBonus)
    }

    /**
     * Under the default policy the tier never engages — even where its activation gate
     * (empty exact pass, >= 4 code points) would be met. "аита" corrects to "китап" only through
     * a substitution variant (а→к at position 0, which has no long-press partner).
     */
    @Test
    fun anEngineOpenedWithoutAPolicyKeepsTheTierOffTheLivePath() {
        val index = index(listOf("китап" to 10L))
        assertEquals(emptyList<String>(), lookup(index, "аита"))
    }

    /** The TATAR policy recovers the gated substitution case the default policy provably misses. */
    @Test
    fun theTatarPolicyRecoversTheGatedTierCase() {
        val index = index(listOf("китап" to 10L), FuzzyEditPolicy.TATAR)
        assertEquals(listOf("китап"), lookup(index, "аита"))
    }

    /**
     * The tier activation gate: the same lookup under the TATAR policy does NOT engage the tier
     * when the exact pass found anything — so "китап" (an exact continuation of "кита") is the
     * only candidate and no substitution noise appears beside it.
     */
    @Test
    fun theTatarPolicyNeverEngagesTheTierWhenTheExactPassFoundAnything() {
        val index = index(
            // Code-point sorted, as the tdict fixture requires: битап < китап.
            listOf("битап" to 9_999L, "китап" to 10L),
            FuzzyEditPolicy.TATAR,
        )
        assertEquals(listOf("китап"), lookup(index, "кита"))
    }

    /**
     * On the tier domain the former class-#1 candidate no longer enjoys class priority: "кумеш"
     * reaches "күмеш" (у→ү, the long-press partner) and "көмеш" (у→ө) through the same
     * substitution family, so both carry one class tag and frequency decides — "көмеш" first.
     */
    @Test
    fun theTierRanksTheFormerClass1CandidateByFrequency() {
        // Code-point sorted: ү (U+04AF) precedes ө (U+04E9) at the second position.
        val index = index(listOf("күмеш" to 5L, "көмеш" to 9_999L), FuzzyEditPolicy.TATAR)
        assertEquals(listOf("көмеш", "күмеш"), lookup(index, "кумеш"))
    }

    /** The tier's transposition family swaps adjacent letters: "икта" recovers "китап". */
    @Test
    fun theTatarPolicyRecoversATranspositionThroughTheTier() {
        val index = index(listOf("китап" to 10L), FuzzyEditPolicy.TATAR)
        assertEquals(listOf("китап"), lookup(index, "икта"))
    }

    /** The tier's insertion family re-inserts a missing letter: "киап" recovers "китап". */
    @Test
    fun theTatarPolicyRecoversADeletionTypoThroughTheTier() {
        val index = index(listOf("китап" to 10L), FuzzyEditPolicy.TATAR)
        assertEquals(listOf("китап"), lookup(index, "киап"))
    }

    /** The tier's deletion family removes an extra letter: "киттап" recovers "китап". */
    @Test
    fun theTatarPolicyRecoversAnInsertionTypoThroughTheTier() {
        val index = index(listOf("китап" to 10L), FuzzyEditPolicy.TATAR)
        assertEquals(listOf("китап"), lookup(index, "киттап"))
    }

    /**
     * Source contract, part 3 — the production wiring itself. LatinIME is an Android service and
     * cannot run under the JVM harness, so the wiring is pinned on its source text with the same
     * dual-path lookup the resource contracts use: the Tatar engine must receive
     * [FuzzyEditPolicy.TATAR] and every other engine null (= [FuzzyEditPolicy.DEFAULT]).
     */
    @Test
    fun latinImeWiresTheTatarPolicyToTheTatarEngineOnly() {
        val candidates = listOf(
            File("src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java"),
            File("app/src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java"),
        )
        val source = candidates.firstOrNull { it.isFile }?.readText()
            ?: error("cannot locate LatinIME.java from ${File(".").absolutePath}")
        assertTrue(
            "the Tatar engine ships FuzzyEditPolicy.TATAR, others ship null (= DEFAULT)",
            source.contains("tatarEngine ? FuzzyEditPolicy.TATAR : null"),
        )
    }
}
