package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TT-TYPO-NEXT Phase B (docs/TT-TYPO-NEXT.md): the same-length bonus inside the fuzzy tier. When
 * the engine's [FuzzyEditPolicy] enables it, a fuzzy candidate exactly as long as the typed
 * prefix ranks BEFORE its own continuations within its edit class — that is what puts the
 * correction itself ("сәләм", frequency 36) above its longer derivative ("сәләмәтлек", 65) for
 * the typo "сцләм". Frequency order is preserved otherwise, and the bonus never crosses edit
 * classes or touches the exact level.
 *
 * The detection is allocation-free: a candidate whose remainder past the variant is empty IS the
 * variant itself, and a substitution variant has the typed prefix's code-point length.
 */
class TdictPrefixIndexSameLengthBonusTest {
    private val table = E3bTestFixtures.tatarNeighborTable()

    /** Class #1 with the bonus on: isolates the bonus from the class enablement. */
    private val bonusPolicy = FuzzyEditPolicy(intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS), true)

    /**
     * The Phase-B calibrated arm (classes #1+#2 + bonus) — FuzzyEditPolicy.TATAR as calibrated in
     * Phase B. Phase C redefined TATAR to {1, 4}, so the geometric-neighbour bonus cases run
     * against this explicit policy.
     */
    private val phaseBPolicy = FuzzyEditPolicy(
        intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_GEOMETRIC),
        true,
    )

    private fun index(
        entries: List<Pair<String, Long>>,
        policy: FuzzyEditPolicy?,
    ): TdictPrefixIndex {
        val index = EngineTestFixtures.index(entries, fuzzyEditPolicy = policy)
        index.updateKeyNeighbors(table)
        return index
    }

    private fun lookup(index: TdictPrefixIndex, prefix: String): List<String> =
        index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))

    @Test
    fun theSameLengthCandidateRanksBeforeItsOwnContinuationsWhenTheBonusIsOn() {
        // Typed "кум"; the class #1 variant "күл" (у→ү) matches both "күл" (the variant itself,
        // same length as the typed prefix) and its continuation "күләк". Without the bonus the
        // frequency decides ("күләк" first); with it, "күл" leads.
        val index = index(listOf("күл" to 100L, "күләк" to 9_999L), bonusPolicy)
        assertEquals(listOf("күл", "күләк"), lookup(index, "кул"))
    }

    @Test
    fun withoutTheBonusTheFrequencyOrderIsExactlyThePrePhaseBOne() {
        // The DEFAULT policy (no bonus): the same two candidates rank by frequency — the frozen
        // pre-Phase-B order of TdictPrefixIndexFuzzyTest.
        val index = index(listOf("күл" to 100L, "күләк" to 9_999L), null)
        assertEquals(listOf("күләк", "күл"), lookup(index, "кул"))
    }

    @Test
    fun frequencyOrderIsPreservedAmongSameLengthCandidates() {
        // Typed "аит"; two class #2 variants are themselves dictionary words of the typed length:
        // "сит" (а→с, 50) and "кит" (а→к, 5). The bonus makes both outrank continuations, and
        // between themselves the frequency order stands.
        val index = index(
            listOf("кит" to 5L, "китап" to 9_999L, "сит" to 50L),
            phaseBPolicy,
        )
        assertEquals(listOf("сит", "кит", "китап"), lookup(index, "аит"))
    }

    @Test
    fun theBonusNeverCrossesEditClasses() {
        // Typed "кум": "күмеш" is a class #1 CONTINUATION (variant "күм" + "еш"), "көм" a class #2
        // same-length word (variant "көм" itself). The class key dominates the bonus: the class #1
        // continuation still outranks the class #2 same-length candidate.
        val index = index(listOf("күмеш" to 5L, "көм" to 10L), phaseBPolicy)
        assertEquals(listOf("күмеш", "көм"), lookup(index, "кум"))
    }

    @Test
    fun theBonusDoesNotTouchTheExactLevel() {
        // Typed "бал": the exact continuation "бала" stays first; the same-length class #1
        // candidate "бәл" (а→ә, the variant itself) fills the next cell.
        val index = index(
            listOf("бала" to 10L, "бәл" to 9_999L),
            FuzzyEditPolicy.TATAR,
        )
        assertEquals(listOf("бала", "бәл"), lookup(index, "бал"))
    }
}
