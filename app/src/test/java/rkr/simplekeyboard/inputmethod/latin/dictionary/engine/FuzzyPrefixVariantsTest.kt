package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyPrefixVariantsTest {
    private val table = E3aTestFixtures.tatarNeighborTable()
    private val codePointScratch = IntArray(64)
    private val variantScratch = ByteArray(256)

    private data class PositionedVariant(val position: Int, val text: String)

    private fun variantsOf(prefix: String, maxVariants: Int = 100): Pair<Int, List<String>> {
        val collected = ArrayList<String>()
        val bytes = prefix.toByteArray(Charsets.UTF_8)
        val emitted = FuzzyPrefixVariants.generateLongPressVariants(
            bytes, bytes.size, table, codePointScratch, variantScratch, maxVariants,
        ) { variantUtf8, length ->
            collected.add(String(variantUtf8, 0, length, Charsets.UTF_8))
        }
        return emitted to collected
    }

    @Test
    fun replacesEveryPositionThatHasALongPressPartner() {
        // "ана": а→ә at position 0 and 2, н→ң at position 1.
        val (emitted, variants) = variantsOf("ана")
        assertEquals(3, emitted)
        assertEquals(listOf("әна", "аңа", "анә"), variants)
    }

    @Test
    fun eachTatarFifthRowLetterReEncodesToItsBaseOrBases() {
        // The six letters the product exists for: ә ө ү җ ң һ. Each must re-encode to valid UTF-8.
        assertEquals(listOf("ат", "эт"), variantsOf("әт").second)
        assertEquals(listOf("от"), variantsOf("өт").second)
        assertEquals(listOf("ут"), variantsOf("үт").second)
        assertEquals(listOf("жт"), variantsOf("җт").second)
        assertEquals(listOf("нт"), variantsOf("ңт").second)
        assertEquals(listOf("гт", "хт"), variantsOf("һт").second)
    }

    @Test
    fun schwaExpandsToBothDeclaredBasesInCodePointOrder() {
        // "ә" is declared on both "а" (0x430) and "э" (0x44D); symmetrization gives both back.
        assertEquals(listOf("аби", "эби"), variantsOf("әби").second)
    }

    @Test
    fun aPrefixWithoutAnyPartnerProducesNoVariant() {
        val (emitted, variants) = variantsOf("сит")
        assertEquals(0, emitted)
        assertTrue(variants.isEmpty())
    }

    @Test
    fun exceedingTheVariantBudgetFailsClosedWithMinusOne() {
        // "аоуана" would emit six variants (а×3, о, у, н); a budget of three drops the whole set.
        val (emitted, _) = variantsOf("аоуана", maxVariants = 3)
        assertEquals(-1, emitted)
    }

    @Test
    fun countCodePointsByLeadBytesHandlesCyrillicLatinAndMixed() {
        val cyrillic = "кит".toByteArray(Charsets.UTF_8)
        assertEquals(6, cyrillic.size)
        assertEquals(3, countCodePointsByLeadBytes(cyrillic, cyrillic.size))

        val latin = "abc".toByteArray(Charsets.UTF_8)
        assertEquals(3, countCodePointsByLeadBytes(latin, latin.size))

        // Mixed script + digit: "а" is two bytes, "b" and "1" one byte each: three code points.
        val mixed = "аb1".toByteArray(Charsets.UTF_8)
        assertEquals(4, mixed.size)
        assertEquals(3, countCodePointsByLeadBytes(mixed, mixed.size))

        // A two-letter Cyrillic prefix is four bytes but only two code points.
        val twoCyrillic = "мә".toByteArray(Charsets.UTF_8)
        assertEquals(4, twoCyrillic.size)
        assertEquals(2, countCodePointsByLeadBytes(twoCyrillic, twoCyrillic.size))
    }

    private fun deletionsOf(
        prefix: String,
        maxVariants: Int = 100,
    ): Pair<Int, List<PositionedVariant>> {
        val collected = ArrayList<PositionedVariant>()
        val bytes = prefix.toByteArray(Charsets.UTF_8)
        val emitted = FuzzyPrefixVariants.generateDeletionVariants(
            bytes, bytes.size, codePointScratch, variantScratch, maxVariants,
        ) { position, variantUtf8, length ->
            collected.add(PositionedVariant(position, String(variantUtf8, 0, length, Charsets.UTF_8)))
        }
        return emitted to collected
    }

    private fun insertionsOf(
        prefix: String,
        alphabet: IntArray,
        maxVariants: Int = 100,
    ): Pair<Int, List<PositionedVariant>> {
        val collected = ArrayList<PositionedVariant>()
        val bytes = prefix.toByteArray(Charsets.UTF_8)
        val emitted = FuzzyPrefixVariants.generateInsertionVariants(
            bytes, bytes.size, alphabet, codePointScratch, variantScratch, maxVariants,
        ) { position, variantUtf8, length ->
            collected.add(PositionedVariant(position, String(variantUtf8, 0, length, Charsets.UTF_8)))
        }
        return emitted to collected
    }

    private fun transpositionsOf(
        prefix: String,
        maxVariants: Int = 100,
    ): Pair<Int, List<PositionedVariant>> {
        val collected = ArrayList<PositionedVariant>()
        val bytes = prefix.toByteArray(Charsets.UTF_8)
        val emitted = FuzzyPrefixVariants.generateTranspositionVariants(
            bytes, bytes.size, codePointScratch, variantScratch, maxVariants,
        ) { position, variantUtf8, length ->
            collected.add(PositionedVariant(position, String(variantUtf8, 0, length, Charsets.UTF_8)))
        }
        return emitted to collected
    }

    @Test
    fun deletionRemovesEveryPositionInAscendingOrder() {
        val (emitted, variants) = deletionsOf("ана")
        assertEquals(3, emitted)
        assertEquals(
            listOf(
                PositionedVariant(0, "на"),
                PositionedVariant(1, "аа"),
                PositionedVariant(2, "ан"),
            ),
            variants,
        )
    }

    @Test
    fun deletionRestoresTheScratchBetweenPositions() {
        // A restore failure would corrupt every variant after the first: each of these differs
        // from "бала" by exactly one removed letter.
        val (emitted, variants) = deletionsOf("бала")
        assertEquals(4, emitted)
        assertEquals(listOf("ала", "бла", "баа", "бал"), variants.map { it.text })
    }

    @Test
    fun deletionFailsClosedOverTheBudget() {
        // "ана" emits three variants; a budget of two drops the whole set.
        assertEquals(-1, deletionsOf("ана", maxVariants = 2).first)
    }

    @Test
    fun insertionCoversEveryGapAndEveryAlphabetLetter() {
        // Position ascending, then the alphabet array's order; the end position appends.
        val alphabet = intArrayOf('ә'.code, 'у'.code)
        val (emitted, variants) = insertionsOf("бал", alphabet)
        assertEquals(8, emitted)
        assertEquals(
            listOf(
                PositionedVariant(0, "әбал"),
                PositionedVariant(0, "убал"),
                PositionedVariant(1, "бәал"),
                PositionedVariant(1, "буал"),
                PositionedVariant(2, "баәл"),
                PositionedVariant(2, "баул"),
                PositionedVariant(3, "балә"),
                PositionedVariant(3, "балу"),
            ),
            variants,
        )
    }

    @Test
    fun insertionRestoresTheScratchBetweenPositions() {
        // Every variant of "бал" keeps the other two letters in place and in order.
        val (_, variants) = insertionsOf("бал", intArrayOf('ә'.code))
        assertEquals(listOf("әбал", "бәал", "баәл", "балә"), variants.map { it.text })
    }

    @Test
    fun insertionFailsClosedOverTheBudget() {
        val alphabet = intArrayOf('ә'.code, 'у'.code)
        assertEquals(-1, insertionsOf("бал", alphabet, maxVariants = 3).first)
    }

    @Test
    fun transpositionSwapsEveryAdjacentPairInAscendingOrder() {
        val (emitted, variants) = transpositionsOf("бала")
        assertEquals(3, emitted)
        assertEquals(
            listOf(
                PositionedVariant(0, "абла"),
                PositionedVariant(1, "блаа"),
                PositionedVariant(2, "баал"),
            ),
            variants,
        )
    }

    @Test
    fun transpositionSkipsASwapOfEqualLetters() {
        // Pivot 1 of "баа" would reproduce the typed prefix; it is not a variant.
        val (emitted, variants) = transpositionsOf("баа")
        assertEquals(1, emitted)
        assertEquals(listOf(PositionedVariant(0, "аба")), variants)
    }

    @Test
    fun transpositionFailsClosedOverTheBudget() {
        assertEquals(-1, transpositionsOf("бала", maxVariants = 2).first)
    }

    @Test
    fun theTierVariantsAreValidUtf8OfTheExpectedLength() {
        // A byte-level fragment would decode with a replacement character; the families' arity is
        // n - 1, n + 1 and n code points respectively.
        for (prefix in listOf("сәләм", "күңел")) {
            val codePointCount = prefix.codePointCount(0, prefix.length)
            val families = listOf(
                codePointCount - 1 to deletionsOf(prefix).second,
                codePointCount + 1 to insertionsOf(prefix, intArrayOf('ә'.code, 'у'.code)).second,
                codePointCount to transpositionsOf(prefix).second,
            )
            for ((expectedLength, variants) in families) {
                assertTrue(variants.isNotEmpty())
                for (variant in variants) {
                    assertFalse("replacement character in $variant", variant.text.contains('\uFFFD'))
                    assertEquals(expectedLength, variant.text.codePointCount(0, variant.text.length))
                }
            }
        }
    }
}
