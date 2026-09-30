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
 *  - [FuzzyEditPolicy.TATAR]: class #1 always, class #4 (probe-first full single substitution,
 *    gated on an empty exact pass at >= 4 code points) and the same-length bonus.
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
     * Source contract, part 2: the TATAR policy runs class #1 always plus class #4 (probe-first
     * full single substitution, itself gated on an empty exact pass at >= 4 code points), with the
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
     * Under the default policy class #4 never fires — even where its activation gate
     * (empty exact pass, >= 4 code points) would be met. "аита" corrects to "китап" only through
     * a full-substitution variant (а→к at position 0, which has no long-press partner).
     */
    @Test
    fun anEngineOpenedWithoutAPolicyKeepsClass4OffTheLivePath() {
        val index = index(listOf("китап" to 10L))
        assertEquals(emptyList<String>(), lookup(index, "аита"))
    }

    /** The TATAR policy recovers the gated class-#4 case the default policy provably misses. */
    @Test
    fun theTatarPolicyRecoversTheGatedClass4Case() {
        val index = index(listOf("китап" to 10L), FuzzyEditPolicy.TATAR)
        assertEquals(listOf("китап"), lookup(index, "аита"))
    }

    /**
     * The class-#4 activation gate: the same lookup under the TATAR policy does NOT fire class #4
     * when the exact pass found anything — so "китап" (an exact continuation of "кита") is the
     * only candidate and no substitution noise appears beside it.
     */
    @Test
    fun theTatarPolicyNeverFiresClass4WhenTheExactPassFoundAnything() {
        val index = index(
            // Code-point sorted, as the tdict fixture requires: битап < китап.
            listOf("битап" to 9_999L, "китап" to 10L),
            FuzzyEditPolicy.TATAR,
        )
        assertEquals(listOf("китап"), lookup(index, "кита"))
    }

    /**
     * Positive control: class #1 keeps working under the TATAR policy and still outranks class #4
     * at any frequency. "кумеш" → class #1 (у→ү) → "күмеш"; class #4 (у→ө among all others) adds
     * "көмеш" — with the far higher frequency, yet ranked second by the class key.
     */
    @Test
    fun class1StillOutranksClass4UnderTheTatarPolicy() {
        // Code-point sorted: ү (U+04AF) precedes ө (U+04E9) at the second position.
        val index = index(listOf("күмеш" to 5L, "көмеш" to 9_999L), FuzzyEditPolicy.TATAR)
        assertEquals(listOf("күмеш", "көмеш"), lookup(index, "кумеш"))
    }

    /** The TATAR policy does not recover a transposition: a swap is not a substitution. */
    @Test
    fun theTatarPolicyDoesNotRecoverATransposition() {
        // A 4-code-point transposition "икта" -> "кита" meets the class-#4 activation gate, but a
        // swap is not a substitution, so nothing is recovered.
        val index = index(listOf("китап" to 10L), FuzzyEditPolicy.TATAR)
        assertEquals(emptyList<String>(), lookup(index, "икта"))
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
