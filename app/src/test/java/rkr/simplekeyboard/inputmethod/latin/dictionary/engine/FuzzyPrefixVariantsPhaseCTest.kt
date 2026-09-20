package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edit class #4 (TT-TYPO-NEXT Phase C): full single substitution over the layout's typeable
 * alphabet. Direct generator tests — the engine-level behavior (activation gate, probe-first
 * budgets, ranking) is in [TdictPrefixIndexPhaseCTest] and the calibration in
 * [TtTypoPhaseCCalibrationTest].
 */
class FuzzyPrefixVariantsPhaseCTest {
    private val alphabet = E3bTestFixtures.tatarNeighborTable().nodes
    private val codePointScratch = IntArray(64)
    private val variantScratch = ByteArray(256)

    private fun variantsOf(prefix: String, maxVariants: Int = 10_000): Pair<Int, List<String>> {
        val collected = ArrayList<String>()
        val bytes = prefix.toByteArray(Charsets.UTF_8)
        val emitted = FuzzyPrefixVariants.generateFullSubstitutionVariants(
            bytes, bytes.size, alphabet, codePointScratch, variantScratch, maxVariants,
        ) { _, v, len -> collected.add(String(v, 0, len, Charsets.UTF_8)) }
        return emitted to collected
    }

    @Test
    fun everyPositionIsReplacedByEveryOtherAlphabetLetterInOrder() {
        // "кит": 3 positions x (39 - 1) = 114 variants; position-major, then alphabet ascending.
        val (emitted, variants) = variantsOf("кит")
        assertEquals(3 * (alphabet.size - 1), emitted)
        assertEquals(emitted, variants.size)
        val perPosition = variants.chunked(alphabet.size - 1)
        assertEquals(3, perPosition.size)
        // Position 0: every letter but к at position 0, the rest of the prefix untouched.
        for ((index, variant) in perPosition[0].withIndex()) {
            assertEquals("ит", variant.substring(1))
            assertFalse(variant.startsWith("к"))
        }
        // Alphabet order within a position is the sorted node set (code-point ascending).
        val firstLetters = perPosition[0].map { it.substring(0, 1) }
        assertEquals(firstLetters.sorted(), firstLetters)
        // Position 1: every letter but и at position 1.
        for (variant in perPosition[1]) {
            assertEquals("к", variant.substring(0, 1))
            assertEquals("т", variant.substring(2))
            assertFalse(variant.startsWith("ки"))
        }
    }

    @Test
    fun thePrefixItselfIsNeverEmitted() {
        val (_, variants) = variantsOf("сәләм")
        assertFalse(variants.contains("сәләм"))
        assertEquals(variants.distinct(), variants)
    }

    @Test
    fun variantsReEncodeToValidUtf8ForTheWholeAlphabet() {
        // Replacing around the two-byte fifth-row letters must produce valid UTF-8: the alphabet
        // contains code points above U+04FF never (Cyrillic two-byte only), and every variant of a
        // fifth-row letter round-trips.
        val (_, variants) = variantsOf("әни")
        assertTrue(variants.all { it.length == "әни".length })
        assertTrue(variants.contains("сни"))
        assertTrue(variants.contains("ңни"))
    }

    @Test
    fun theProbeBudgetFailsClosed() {
        // 114 variants would be emitted for "кит"; a budget of 10 overflows mid-generation.
        val (emitted, _) = variantsOf("кит", maxVariants = 10)
        assertEquals(-1, emitted)
    }
}
