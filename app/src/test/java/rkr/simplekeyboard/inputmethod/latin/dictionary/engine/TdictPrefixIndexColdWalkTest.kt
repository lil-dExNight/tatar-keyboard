package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P7-1 (docs/GLIDE-PLAN.md): [TdictPrefixIndex.forEachWordCold] — the cold, thread-free
 * enumeration behind the glide decoder's one-time word-index build.
 */
class TdictPrefixIndexColdWalkTest {

    private val entries = listOf(
        // Code-point sorted, long enough to span several front-coded blocks.
        "алма" to 7L, "анла" to 8L, "арба" to 9L, "асма" to 10L, "атла" to 11L,
        "бала" to 20L, "балалар" to 4L, "балда" to 9L, "бит" to 13L,
        "вакыт" to 14L, "гади" to 15L, "дус" to 16L,
        "китап" to 1_000L,
        "татар" to 60_000L, "татарлар" to 12_085L, "татарча" to 9_093L,
        "әби" to 12L, "өй" to 4_000L,
    )

    @Test
    fun coldWalkVisitsEveryEntryInOrderWithItsFrequency() {
        val index = EngineTestFixtures.index(entries)
        val visited = ArrayList<Pair<String, Long>>()
        index.forEachWordCold(ColdWordVisitor { word, frequency -> visited.add(word to frequency) })
        assertEquals(entries, visited)
    }

    @Test
    fun coldWalkAgreesWithTheLookupPathReads() {
        val index = EngineTestFixtures.index(entries)
        // Interleave with real lookups: the cold walk shares no state with them.
        index.lookup(ImmutableUtf8Prefix.copyOf("тат".toByteArray(Charsets.UTF_8)))
        val visited = ArrayList<Pair<String, Long>>()
        index.forEachWordCold(ColdWordVisitor { word, frequency -> visited.add(word to frequency) })
        index.lookup(ImmutableUtf8Prefix.copyOf("бал".toByteArray(Charsets.UTF_8)))
        entries.forEachIndexed { entry, (word, frequency) ->
            assertEquals(word, visited[entry].first)
            assertEquals(frequency, visited[entry].second)
            assertEquals(frequency, index.frequencyOf(word))
        }
    }
}
