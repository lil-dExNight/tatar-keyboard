package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyPrefixVariantsChatSubstitutionTest {
    private data class PositionedVariant(val position: Int, val text: String)

    private val incrementingTable = ChatSubstitutionTable { codePoint ->
        when (codePoint) {
            97 -> 98
            98 -> 99
            99 -> 100
            100 -> 101
            101 -> 102
            102 -> 103
            103 -> 104
            else -> codePoint
        }
    }

    private fun variantsOf(
        prefix: String,
        table: ChatSubstitutionTable = incrementingTable,
        maxVariants: Int = 100,
        codePointScratch: IntArray = IntArray(64),
        variantScratch: ByteArray = ByteArray(256),
        eligiblePositionScratch: IntArray = IntArray(64),
        originalCodePointScratch: IntArray = IntArray(64),
    ): Pair<Int, List<PositionedVariant>> {
        return variantsOf(
            prefix.toByteArray(Charsets.UTF_8),
            table,
            maxVariants,
            codePointScratch,
            variantScratch,
            eligiblePositionScratch,
            originalCodePointScratch,
        )
    }

    private fun variantsOf(
        prefixUtf8: ByteArray,
        table: ChatSubstitutionTable = incrementingTable,
        maxVariants: Int = 100,
        codePointScratch: IntArray = IntArray(64),
        variantScratch: ByteArray = ByteArray(256),
        eligiblePositionScratch: IntArray = IntArray(64),
        originalCodePointScratch: IntArray = IntArray(64),
    ): Pair<Int, List<PositionedVariant>> {
        val collected = ArrayList<PositionedVariant>()
        val emitted = FuzzyPrefixVariants.generateChatSubstitutionVariants(
            prefixUtf8,
            prefixUtf8.size,
            table,
            codePointScratch,
            variantScratch,
            eligiblePositionScratch,
            originalCodePointScratch,
            maxVariants,
        ) { position, variantUtf8, length ->
            collected.add(
                PositionedVariant(position, String(variantUtf8, 0, length, Charsets.UTF_8)),
            )
        }
        return emitted to collected
    }

    @Test
    fun twoEligiblePositionsEmitOnlyTheirCombinedSubstitution() {
        val (emitted, variants) = variantsOf("ab")

        assertEquals(1, emitted)
        assertEquals(listOf(PositionedVariant(0, "bc")), variants)
    }

    @Test
    fun sixEligiblePositionsEmitEveryMultiSubstitutionSubsetWithoutIdentityOrDuplicates() {
        val (emitted, variants) = variantsOf("abcdef")
        val expected = ArrayList<PositionedVariant>()
        for (mask in 1 until 64) {
            if (Integer.bitCount(mask) < 2) continue
            val text = StringBuilder(6)
            for (position in 0 until 6) {
                val original = 97 + position
                val codePoint = if (mask and (1 shl position) != 0) original + 1 else original
                text.appendCodePoint(codePoint)
            }
            expected.add(PositionedVariant(Integer.numberOfTrailingZeros(mask), text.toString()))
        }

        val texts = variants.map { it.text }
        assertEquals(57, emitted)
        assertEquals(expected, variants)
        assertEquals(texts.distinct(), texts)
        assertFalse(texts.contains("abcdef"))
    }

    @Test
    fun moreThanSixEligiblePositionsEmitOnlyTheAllSubstitutedVariant() {
        val (emitted, variants) = variantsOf("abcdefg")

        assertEquals(1, emitted)
        assertEquals(listOf(PositionedVariant(0, "bcdefgh")), variants)
    }

    @Test
    fun zeroOrOneEligiblePositionEmitsNothing() {
        val zero = variantsOf("123")
        val one = variantsOf("a12")

        assertEquals(0, zero.first)
        assertTrue(zero.second.isEmpty())
        assertEquals(0, one.first)
        assertTrue(one.second.isEmpty())
    }

    @Test
    fun mixedWidthCodePointsProduceValidUtf8AndCodePointPositions() {
        val table = ChatSubstitutionTable { codePoint ->
            when (codePoint) {
                97 -> 0x00e9
                98 -> 0x20ac
                99 -> 0x1f600
                else -> codePoint
            }
        }
        val input = textOf(0x1f600, 97, 98, 99)

        val (emitted, variants) = variantsOf(input, table)

        assertEquals(4, emitted)
        assertEquals(
            listOf(
                PositionedVariant(1, textOf(0x1f600, 0x00e9, 0x20ac, 99)),
                PositionedVariant(1, textOf(0x1f600, 0x00e9, 98, 0x1f600)),
                PositionedVariant(2, textOf(0x1f600, 97, 0x20ac, 0x1f600)),
                PositionedVariant(1, textOf(0x1f600, 0x00e9, 0x20ac, 0x1f600)),
            ),
            variants,
        )
        assertTrue(variants.none { it.text.contains('\ufffd') })
    }

    @Test
    fun malformedUtf8FailsWithoutEmitting() {
        val malformedInputs = listOf(
            bytesOf(0xc3, 0x28),
            bytesOf(0xe2, 0x82),
            bytesOf(0xe0, 0x80, 0x80),
            bytesOf(0xed, 0xa0, 0x80),
            bytesOf(0xf4, 0x90, 0x80, 0x80),
        )

        for (input in malformedInputs) {
            val (emitted, variants) = variantsOf(input)
            assertEquals(-1, emitted)
            assertTrue(variants.isEmpty())
        }
    }

    @Test
    fun workingCodePointsAreRestoredAfterSuccessAndBudgetFailure() {
        for (maxVariants in intArrayOf(4, 3)) {
            val codePointScratch = IntArray(6) { -1 }
            val eligiblePositionScratch = IntArray(6) { -2 }
            val originalCodePointScratch = IntArray(6) { -3 }

            val (emitted, variants) = variantsOf(
                "abc",
                maxVariants = maxVariants,
                codePointScratch = codePointScratch,
                eligiblePositionScratch = eligiblePositionScratch,
                originalCodePointScratch = originalCodePointScratch,
            )

            assertEquals(if (maxVariants == 4) 4 else -1, emitted)
            assertEquals(if (maxVariants == 4) 4 else 0, variants.size)
            assertArrayEquals(intArrayOf(97, 98, 99, -1, -1, -1), codePointScratch)
            assertArrayEquals(intArrayOf(0, 1, 2, -2, -2, -2), eligiblePositionScratch)
            assertArrayEquals(intArrayOf(97, 98, 99, -3, -3, -3), originalCodePointScratch)
        }
    }

    @Test
    fun outputOverflowFailsAndRestoresWorkingCodePoints() {
        val codePointScratch = IntArray(2)
        val originalCodePointScratch = IntArray(2)

        val (emitted, variants) = variantsOf(
            "ab",
            codePointScratch = codePointScratch,
            variantScratch = ByteArray(1),
            originalCodePointScratch = originalCodePointScratch,
        )

        assertEquals(-1, emitted)
        assertTrue(variants.isEmpty())
        assertArrayEquals(intArrayOf(97, 98), codePointScratch)
        assertArrayEquals(intArrayOf(97, 98), originalCodePointScratch)
    }

    @Test
    fun outputOverflowAfterEarlierVariantsStillFailsAndRestoresScratch() {
        val oneByteTable = ChatSubstitutionTable { codePoint ->
            when (codePoint) {
                97 -> 120
                98 -> 121
                99 -> 122
                100 -> 0x1f600
                else -> codePoint
            }
        }
        val codePointScratch = IntArray(4)
        val originalCodePointScratch = IntArray(4)
        val (emitted, _) = variantsOf(
            "abcd",
            table = oneByteTable,
            codePointScratch = codePointScratch,
            variantScratch = ByteArray(5),
            originalCodePointScratch = originalCodePointScratch,
        )

        assertEquals(-1, emitted)
        assertArrayEquals(intArrayOf(97, 98, 99, 100), codePointScratch)
        assertArrayEquals(intArrayOf(97, 98, 99, 100), originalCodePointScratch)
    }

    @Test
    fun shortScratchFails() {
        assertEquals(-1, variantsOf("abc", codePointScratch = IntArray(2)).first)
        assertEquals(-1, variantsOf("abc", eligiblePositionScratch = IntArray(2)).first)
        assertEquals(-1, variantsOf("abc", originalCodePointScratch = IntArray(2)).first)
    }

    @Test
    fun exceedingTheVariantBudgetFailsWithoutPartialOutput() {
        val (emitted, variants) = variantsOf("abc", maxVariants = 3)

        assertEquals(-1, emitted)
        assertTrue(variants.isEmpty())
    }

    private fun textOf(vararg codePoints: Int): String {
        return String(codePoints, 0, codePoints.size)
    }

    private fun bytesOf(vararg values: Int): ByteArray {
        return ByteArray(values.size) { values[it].toByte() }
    }
}
