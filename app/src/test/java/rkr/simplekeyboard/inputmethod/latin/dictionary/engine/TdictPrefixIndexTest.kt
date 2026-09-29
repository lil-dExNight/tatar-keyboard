package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TdictPrefixIndexTest {
    @Test
    fun binaryRangeExcludesExactWordAndRanksTopThree() {
        val index = EngineTestFixtures.index(
            listOf(
                "бал" to 50,
                "бала" to 10,
                "балалар" to 4,
                "балан" to 9,
                "балчык" to 9,
                "бар" to 100,
            ),
        )

        assertEquals(listOf("бала", "балан", "балчык"), lookup(index, utf8("бал")))
        assertEquals(listOf("балан", "балалар"), lookup(index, utf8("бала")))
        assertFalse(lookup(index, utf8("бал")).contains("бал"))
    }

    @Test
    fun frequencyIsUnsignedAndLexicalTieUsesUnsignedUtf8Order() {
        val index = EngineTestFixtures.index(
            listOf(
                "а" to 1,
                "аа" to 7,
                "аб" to 7,
                "ав" to 0xffff_ffffL,
                "аә" to 7,
            ),
        )

        assertEquals(listOf("ав", "аа", "аб"), lookup(index, utf8("а")))
    }

    @Test
    fun lexicalTieBreakUsesUnicodeCodePointOrderAcrossBmpAndSupplementaryWords() {
        val index = EngineTestFixtures.index(
            listOf(
                "x" to 100,
                "x\uE000" to 7,
                "x\uD800\uDC00" to 7,
                "x\uD83D\uDE00" to 7,
            ),
        )

        assertEquals(
            listOf("x\uE000", "x\uD800\uDC00", "x\uD83D\uDE00"),
            lookup(index, utf8("x")),
        )
        assertFalse(lookup(index, utf8("x")).contains("x"))
    }

    @Test
    fun handlesTatarUtf8BoundariesNoMatchAndEmptyInput() {
        val index = EngineTestFixtures.index(
            listOf(
                "а" to 1,
                "җ" to 4,
                "ң" to 3,
                "ү" to 1,
                "ә" to 6,
                "әб" to 5,
                "ө" to 2,
            ),
        )

        assertEquals(listOf("әб"), lookup(index, utf8("ә")))
        assertTrue(lookup(index, utf8("я")).isEmpty())
        assertTrue(lookup(index, ByteArray(0)).isEmpty())
        assertTrue(lookup(index, ByteArray(129) { 1 }).isEmpty())
        assertTrue(lookup(index, byteArrayOf(0xd0.toByte())).isEmpty())
        assertTrue(lookup(index, byteArrayOf(0xc0.toByte(), 0x80.toByte())).isEmpty())
        assertTrue(
            lookup(index, byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())).isEmpty(),
        )
    }

    @Test
    fun malformedLayoutsFailClosed() {
        val raw = DictionaryTestFixtures.raw(listOf("аб" to 2, "әби" to 1))
        assertNull(open(raw.copyOf(20)))

        val badMagic = raw.copyOf().also { it[0] = 'X'.code.toByte() }
        assertNull(open(badMagic))

        val badSchema = raw.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(8, 3)
        }
        assertNull(open(badSchema))

        val zeroFrequency = raw.copyOf().also {
            // The last byte of the fixture is the last entry's single-byte frequency varint.
            it[it.size - 1] = 0
        }
        assertNull(open(zeroFrequency))

        val badOffset = raw.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(72 + 4, 0)
        }
        assertNull(open(badOffset))
    }

    @Test
    fun sourcePositionAndByteOrderCannotChangeFrozenReader() {
        val raw = DictionaryTestFixtures.raw(listOf("аб" to 2, "әби" to 1))
        val source = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val index = requireNotNull(open(raw, source))
        source.position(source.limit())
        source.order(ByteOrder.BIG_ENDIAN)

        assertEquals(listOf("аб"), lookup(index, utf8("а")))
        assertTrue(lookup(index, utf8("әби")).isEmpty())
    }

    private fun open(raw: ByteArray, source: ByteBuffer = ByteBuffer.wrap(raw)) =
        TdictPrefixIndex.open(
            source,
            EngineTestFixtures.identity,
            2,
            raw.size.toLong(),
        )

    private fun utf8(value: String) = value.toByteArray(Charsets.UTF_8)

    private fun lookup(index: TdictPrefixIndex, bytes: ByteArray): List<String> =
        index.lookup(ImmutableUtf8Prefix.copyOf(bytes))
}
