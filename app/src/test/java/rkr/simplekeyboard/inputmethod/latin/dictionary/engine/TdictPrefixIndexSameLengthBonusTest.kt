package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The same-length bonus inside the fuzzy tier. When the engine's [FuzzyEditPolicy] enables it, a
 * fuzzy candidate exactly as long as the typed prefix ranks BEFORE its own continuations within
 * its edit class — that is what puts the correction itself ("сәләм", frequency 36) above its
 * longer derivative ("сәләмәтлек", 65) for the typo "сцләм". Frequency order is preserved
 * otherwise, and the bonus never crosses edit classes or touches the exact level.
 *
 * The detection is allocation-free: a candidate whose remainder past the variant is empty IS the
 * variant itself, and a substitution variant has the typed prefix's code-point length.
 */
class TdictPrefixIndexSameLengthBonusTest {
    private val table = E3bTestFixtures.tatarNeighborTable()

    /** Class #1 with the bonus on: isolates the bonus from the class enablement. */
    private val bonusPolicy = FuzzyEditPolicy(intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS), true)

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
        // Typed "аита" (4 code points, no exact continuation); two class #4 variants are themselves
        // dictionary words of the typed length: "сита" (а→с, 50) and "кита" (а→к, 5). The bonus
        // makes both outrank the continuation "китап", and between themselves the frequency order
        // stands.
        val index = index(
            listOf("кита" to 5L, "китап" to 9_999L, "сита" to 50L),
            FuzzyEditPolicy.TATAR,
        )
        assertEquals(listOf("сита", "кита", "китап"), lookup(index, "аита"))
    }

    @Test
    fun theBonusNeverCrossesEditClasses() {
        // Typed "кума" (4 code points, no exact continuation): "күмак" is a class #1 CONTINUATION
        // (variant "күма" + "к"), "кома" a class #4 same-length word (variant "кома" itself). The
        // class key dominates the bonus: the class #1 continuation still outranks the class #4
        // same-length candidate.
        val index = index(listOf("кома" to 10L, "күмак" to 5L), FuzzyEditPolicy.TATAR)
        assertEquals(listOf("күмак", "кома"), lookup(index, "кума"))
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
