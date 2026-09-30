package rkr.simplekeyboard.inputmethod.latin.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream

class EmojiGlyphVerdictsTest {
    private class CountingProbe(private val missing: Set<String>, private val failing: Set<String> = emptySet()) :
        GlyphProbe {
        var calls = 0

        override fun hasGlyph(sequence: String): Boolean {
            calls++
            if (sequence in failing) throw IllegalStateException("probe failure")
            return sequence !in missing
        }
    }

    private val table = listOf(
        "tt\tсәләм\t👋",
        "tt\tчәчәк\t🌸",
        "tt\tмәче\t🐈",
        "ru\tпривет\t👋",
        "ru\tкот\t🐈",
        "ru\tновое\t🫨",
    ).joinToString("\n")

    private fun input(): InputStream = table.byteInputStream(Charsets.UTF_8)

    @Test
    fun theSecondParseReusesTheVerdictsWithoutProbing() {
        val verdicts = EmojiGlyphVerdicts()
        val probe = CountingProbe(missing = setOf("🫨"), failing = setOf("🌸"))
        var factoryCalls = 0

        val first = verdicts.parse(input()) { factoryCalls++; probe }
        val probesAfterFirst = probe.calls
        val second = verdicts.parse(input()) { factoryCalls++; probe }

        assertEquals(4, probesAfterFirst)
        assertEquals(probesAfterFirst, probe.calls)
        assertEquals(1, factoryCalls)
        for (loaded in listOf(first, second)) {
            assertEquals(4, loaded.entryCount)
            assertEquals("👋", loaded.lookup("tt", "сәләм"))
            assertNull(loaded.lookup("tt", "чәчәк"))
            assertNull(loaded.lookup("ru", "новое"))
            assertEquals("🐈", loaded.lookup("ru", "кот"))
        }
        assertEquals(first.distinctEmoji(), second.distinctEmoji())
    }

    @Test
    fun theVerdictsMatchAFreshProbeOnTheShippedTable() {
        val asset = listOf(File("src/main/assets/$SUGGEST_ASSET"), File("app/src/main/assets/$SUGGEST_ASSET"))
            .first(File::isFile)
        // Rejects every third distinct sequence: a stand-in for a font without some emoji.
        val distinct = asset.inputStream().use { EmojiSuggestIndex.parse(it) }.distinctEmoji().sorted()
        val missing = distinct.filterIndexed { index, _ -> index % 3 == 0 }.toSet()
        val verdicts = EmojiGlyphVerdicts()
        val probe = CountingProbe(missing)

        val first = asset.inputStream().use { stream -> verdicts.parse(stream) { probe } }
        val second = asset.inputStream().use { stream -> verdicts.parse(stream) { probe } }

        println("EMOJI_GLYPH_PROBES|first=${distinct.size}|reload=${probe.calls - distinct.size}")
        assertEquals(distinct.size, probe.calls)
        assertEquals(first.entryCount, second.entryCount)
        assertEquals(first.distinctEmoji(), second.distinctEmoji())
        assertTrue(first.distinctEmoji().none { it in missing })
    }

    @Test
    fun anEmptyOrUnreadableFirstLoadStoresNothing() {
        val verdicts = EmojiGlyphVerdicts()
        val probe = CountingProbe(missing = emptySet())
        val unreadable = object : InputStream() {
            override fun read(): Int = throw IOException("unreadable")
        }

        assertTrue(verdicts.parse(unreadable) { probe }.isEmpty)
        assertTrue(verdicts.parse("".byteInputStream()) { probe }.isEmpty)
        val allMissing = CountingProbe(missing = setOf("👋", "🌸", "🐈", "🫨"))
        assertTrue(verdicts.parse(input()) { allMissing }.isEmpty)

        val loaded = verdicts.parse(input()) { probe }
        assertEquals(4, probe.calls)
        assertEquals(6, loaded.entryCount)
    }

    private companion object {
        const val SUGGEST_ASSET = "emoji/emoji_suggest_v1.txt"
    }
}
