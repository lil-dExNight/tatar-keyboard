package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fuzzy-class contract as an executable policy pin. Before TT-TYPO-NEXT Phase B the shipped
 * set lived in one global constant (`TdictPrefixIndex.SHIPPED_FUZZY_EDIT_CLASSES`, class #1 only —
 * the E3b verdict); Phase B turned it into the per-engine [FuzzyEditPolicy] injected through
 * [TdictPrefixIndex.open] (docs/TT-TYPO-NEXT.md):
 *
 *  - [FuzzyEditPolicy.DEFAULT] — class #1 (long-press partner) only, no same-length bonus — is
 *    bit-identical to the pre-Phase-B shipped behavior. Every engine opened without an explicit
 *    policy runs it (the Russian engine ships exactly this).
 *  - [FuzzyEditPolicy.TATAR] — the SHIPPED Tatar configuration since Phase C2 (2026-09-20):
 *    class #1 (always) + class #4 (probe-first full single substitution, gated on an EMPTY exact
 *    pass at >= 4 code points) + the same-length bonus. (The Phase-B candidate {1,2} failed gate
 *    G1 and was never wired; class #3 was never re-calibrated.)
 *
 * The generators of the unwired classes remain in the tree as infrastructure (exercised directly
 * by [FuzzyPrefixVariantsE3bTest] / [FuzzyPrefixVariantsPhaseCTest]).
 */
class TdictPrefixIndexShippedFuzzyClassesTest {
    private val geometricTable = E3bTestFixtures.tatarNeighborTable()
    private val codePointScratch = IntArray(64)
    private val variantScratch = ByteArray(256)

    private fun index(
        entries: List<Pair<String, Long>>,
        policy: FuzzyEditPolicy? = null,
    ): TdictPrefixIndex {
        val index = EngineTestFixtures.index(entries, fuzzyEditPolicy = policy)
        index.updateKeyNeighbors(geometricTable)
        return index
    }

    private fun lookup(index: TdictPrefixIndex, prefix: String): List<String> =
        index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))

    private fun longPressVariantsOf(prefix: String): List<String> {
        val collected = ArrayList<String>()
        val bytes = prefix.toByteArray(Charsets.UTF_8)
        FuzzyPrefixVariants.generateLongPressVariants(
            bytes, bytes.size, geometricTable, codePointScratch, variantScratch, 100,
        ) { v, len -> collected.add(String(v, 0, len, Charsets.UTF_8)) }
        return collected
    }

    private fun geometricVariantsOf(prefix: String): List<String> {
        val collected = ArrayList<String>()
        val bytes = prefix.toByteArray(Charsets.UTF_8)
        FuzzyPrefixVariants.generateGeometricVariants(
            bytes, bytes.size, geometricTable, codePointScratch, variantScratch, 100,
        ) { v, len -> collected.add(String(v, 0, len, Charsets.UTF_8)) }
        return collected
    }

    private fun transpositionVariantsOf(prefix: String): List<String> {
        val collected = ArrayList<String>()
        val bytes = prefix.toByteArray(Charsets.UTF_8)
        FuzzyPrefixVariants.generateTranspositionVariants(
            bytes, bytes.size, codePointScratch, variantScratch, 100,
        ) { v, len -> collected.add(String(v, 0, len, Charsets.UTF_8)) }
        return collected
    }

    /**
     * Source contract, part 1: the DEFAULT policy — every engine without an explicit one, the
     * Russian engine included — is exactly the pre-Phase-B shipped configuration: class #1 only,
     * no same-length bonus. This is the one place the default live path consults, so classes #2
     * and #3 are provably absent from it.
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
     * Source contract, part 2: the TATAR policy (Phase C) runs class #1 always plus class #4
     * (probe-first full single substitution, itself gated on an empty exact pass at >= 4 code
     * points), with the same-length bonus — and neither class #2 nor class #3.
     */
    @Test
    fun theTatarPolicyRunsClassesOneAndFourWithTheSameLengthBonus() {
        assertEquals(
            listOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION),
            FuzzyEditPolicy.TATAR.editClasses.toList(),
        )
        assertTrue(FuzzyEditPolicy.TATAR.sameLengthBonus)
        assertFalse(FuzzyEditPolicy.TATAR.editClasses.contains(TdictPrefixIndex.EDIT_CLASS_GEOMETRIC))
        assertFalse(FuzzyEditPolicy.TATAR.editClasses.contains(TdictPrefixIndex.EDIT_CLASS_TRANSPOSITION))
    }

    /** An engine opened without a policy gets the default one — the pre-Phase-B behavior. */
    @Test
    fun anEngineOpenedWithoutAPolicyKeepsClass2OffTheLivePath() {
        // Premise: class #2 turns "аит" into "кит" (а→к geometric), which the block of "китап"
        // begins with, while class #1 produces no matching variant.
        assertTrue("premise: class #2 would match", geometricVariantsOf("аит").contains("кит"))
        assertFalse("premise: class #1 would not match", longPressVariantsOf("аит").contains("кит"))
        val index = index(listOf("китап" to 10L))
        assertEquals(emptyList<String>(), lookup(index, "аит"))
    }

    /** Under the default policy a class #3 transposition likewise stays off the live path. */
    @Test
    fun anEngineOpenedWithoutAPolicyKeepsClass3OffTheLivePath() {
        // Premise: class #3 turns "икт" into "кит" (adjacent swap), matching "китап"; class #1
        // produces no matching variant.
        assertTrue("premise: class #3 would match", transpositionVariantsOf("икт").contains("кит"))
        assertFalse("premise: class #1 would not match", longPressVariantsOf("икт").contains("кит"))
        val index = index(listOf("китап" to 10L))
        assertEquals(emptyList<String>(), lookup(index, "икт"))
    }

    /**
     * Under the default policy class #4 never fires either — even where its activation gate
     * (empty exact pass, >= 4 code points) would be met. "аита" corrects to "китап" only through
     * a full-substitution variant (а→к at position 0: no long-press partner and, at 4 code
     * points, no class-#2 policy is wired here either).
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

    /** The TATAR policy keeps class #3 off the live path as well. */
    @Test
    fun theTatarPolicyKeepsClass3OffTheLivePath() {
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
