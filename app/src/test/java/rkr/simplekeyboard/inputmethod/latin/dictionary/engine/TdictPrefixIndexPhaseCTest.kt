package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine behavior of the DL-1 typo tier: the activation gate (empty exact pass AND >= 4 code
 * points), the probe-first cost shape (one probe per variant, scans only for survivors), the
 * shared budgets (exceeding one drops the whole fuzzy level), and the ranking inside the tier
 * (one class tag for every family: the same-length bonus, then frequency). The calibration on
 * the real asset lives in [TtTypoPhaseCCalibrationTest]; the policy pins in
 * [TdictPrefixIndexShippedFuzzyClassesTest].
 */
class TdictPrefixIndexPhaseCTest {
    private val table = E3bTestFixtures.tatarNeighborTable()

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
    fun class4FiresOnlyWhenTheExactPassIsEmptyAndThePrefixIsSettled() {
        val index = index(listOf("китап" to 10L, "китол" to 5L), FuzzyEditPolicy.TATAR)
        // 3 code points, exact empty: the gate's length half cuts class #4 — no recovery.
        assertEquals(emptyList<String>(), lookup(index, "аит"))
        // 4 code points, exact NON-empty ("кито*": китол): the gate's exact half cuts class #4 —
        // "китап" is NOT added even though "кито" is one substitution (о→а) away from its prefix.
        // (The letters of "кито" carry no long-press partner, so class #1 is inert here too.)
        assertEquals(listOf("китол"), lookup(index, "кито"))
        // 4 code points, exact empty: class #4 fires and recovers.
        assertEquals(listOf("китап"), lookup(index, "аита"))
    }

    @Test
    fun probeFirstScansOnlySurvivorsAndCountsThemSeparately() {
        val index = index(listOf("китап" to 10L), FuzzyEditPolicy.TATAR)
        lookup(index, "аита")
        // Position narrowing: "аита" has no word starting with "а" in this dictionary, so every
        // position past the first is skipped for free — only position 0 issues probes:
        // 38 substitutions + 1 deletion + 39 insertions + 1 transposition = 79. Exactly one
        // survivor ("кита"), and the shared variant budget counts survivors only: 1.
        assertEquals(79, index.lastFuzzyProbeCount)
        assertEquals(1, index.lastFuzzyVariantCount)
        assertTrue(index.lastFuzzyVisitedCount > 0)
        assertFalse(index.lastFuzzyOverBudget)
    }

    @Test
    fun theSharedVariantBudgetCountsSurvivorsNotProbes() {
        // A prefix with NO survivors: the same 79 probes as above (only position 0 — "д" starts
        // nothing here), and not one probe survives, so the variant budget stays at 0 — the
        // probes never consume it.
        val index = index(listOf("китап" to 10L), FuzzyEditPolicy.TATAR)
        assertEquals(emptyList<String>(), lookup(index, "дәлә"))
        assertEquals(79, index.lastFuzzyProbeCount)
        assertEquals(0, index.lastFuzzyVariantCount)
    }

    @Test
    fun theSameLengthBonusAppliesWithinClass4() {
        // Typed "китү" (4 cp): the variant "китә" matches "китә" itself (same length) and its
        // continuation "китәп" — the bonus puts the correction itself first despite the frequency.
        val index = index(listOf("китә" to 5L, "китәп" to 9_999L), FuzzyEditPolicy.TATAR)
        assertEquals(listOf("китә", "китәп"), lookup(index, "китү"))
    }

    @Test
    fun frequencyDecidesInsideTheDl1Tier() {
        // Typed "кумеш" (5 cp): the tier's substitution family finds both "күмеш" (у→ү) and
        // "көмеш" (у→ө) under one class tag, so frequency descending decides inside the tier.
        val index = index(listOf("күмеш" to 5L, "көмеш" to 9_999L), FuzzyEditPolicy.TATAR)
        assertEquals(listOf("көмеш", "күмеш"), lookup(index, "кумеш"))
    }

    @Test
    fun theProbeBudgetDropsTheWholeLevelFailClosed() {
        // A synthetic layout whose alphabet overflows MAX_FUZZY_PROBES (8 192): the fixture
        // dictionary is a shared-prefix chain so every position of "кумеш" has a non-empty probe
        // range, and the four families issue ≈ (2N + 1) probes per position — tens of thousands
        // with N ≈ 3 000 — which exceeds the budget. The level is dropped whole — including the
        // class #1 candidate already found. Only the alphabet size matters.
        val letters = ArrayList<KeyNeighborTable.RawKey>()
        var codePoint = 0x41
        while (letters.size < 3_000 && codePoint < 0x2_FFFF) {
            if (Character.isLetter(codePoint)) {
                letters.add(KeyNeighborTable.RawKey(codePoint, IntArray(0)))
            }
            codePoint++
        }
        letters.add(E3aTestFixtures.rawKey('у', 'ү'))
        val huge = KeyNeighborTable.build("tt_RU", true, letters)
        assertTrue(
            "premise: the synthetic alphabet overflows the probe budget",
            5L * (2L * huge.nodes.size + 1) > 8_192,
        )
        // The chain ку/кум/куме/кумет keeps the probe ranges of "кумеш" non-empty at every
        // position while the exact pass returns nothing (no word starts with "кумеш").
        val index = index(
            listOf(
                "ку" to 1L, "кум" to 1L, "куме" to 1L, "кумет" to 1L,
                "күмеш" to 10L,
            ),
            FuzzyEditPolicy.TATAR,
        )
        index.updateKeyNeighbors(huge)
        // "кумеш" would recover "күмеш" through the tier's substitution family (у→ү) — but the
        // probe budget trips and the whole fuzzy level drops, never in part.
        assertEquals(emptyList<String>(), lookup(index, "кумеш"))
        assertTrue(index.lastFuzzyOverBudget)
    }
}
