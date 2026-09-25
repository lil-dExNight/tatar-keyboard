package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary

/**
 * Unit tests for [CompositeGlideInventory]: the append order, the synthetic frequency formula,
 * the duplicate/casing-override rule, the fail-closed skip through the real index build, and
 * the memory budget of a full personal dictionary (docs/GLIDE-PERSONAL.md).
 */
class CompositeGlideInventoryTest {

    private val geometry = GlideTestFixtures.tatarGeometry()

    private fun collect(inventory: GlideWordInventory): List<Pair<String, Long>> {
        val visited = mutableListOf<Pair<String, Long>>()
        inventory.forEachWord { word, frequency -> visited.add(word to frequency) }
        return visited
    }

    @Test
    fun personalEntriesAppendAfterTheBaseOnesInThePersonalOrder() {
        val base = ListGlideInventory(listOf("бала" to 60L, "сәләм" to 100L))
        // Normalized-ascending snapshot order is адам < бөркет < гөмбә; the personal order is
        // usage-descending, so the walk must emit бөркет, адам, гөмбә after the base entries.
        val personal = GlideTestFixtures.personalDictionary("адам" to 5, "бөркет" to 9, "гөмбә" to 1)
        val composite = CompositeGlideInventory(base, personal) { false }

        assertEquals(5, composite.entryCount)
        assertEquals(
            listOf("бала", "сәләм", "бөркет", "адам", "гөмбә"),
            collect(composite).map { it.first },
        )
        // wordAt serves the saved spelling for the personal tail.
        assertEquals("бала", composite.wordAt(0))
        assertEquals("бөркет", composite.wordAt(2))
        assertEquals("гөмбә", composite.wordAt(4))
    }

    @Test
    fun theSyntheticFrequencyScalesWithUsageAndIsCappedAtTheBaseMaximum() {
        val base = ListGlideInventory(listOf("бала" to 1_000L))
        val personal = GlideTestFixtures.personalDictionary("адам" to 10, "бөркет" to 5, "гөмбә" to 1)
        val composite = CompositeGlideInventory(base, personal) { false }

        // baseMax = 1000, maxUsage = 10: frequency = max(1, 1000 x usage / 10).
        assertEquals(
            listOf("бала" to 1_000L, "адам" to 1_000L, "бөркет" to 500L, "гөмбә" to 100L),
            collect(composite),
        )
    }

    @Test
    fun theSyntheticFrequencyFloorIsOne() {
        val base = ListGlideInventory(listOf("бала" to 10L))
        // maxUsage = 100, usage 1: 10 x 1 / 100 truncates to 0 — the floor keeps the
        // strictly-positive contract.
        val personal = GlideTestFixtures.personalDictionary("адам" to 1, "бөркет" to 100)
        val composite = CompositeGlideInventory(base, personal) { false }

        assertEquals(
            listOf("бала" to 10L, "бөркет" to 10L, "адам" to 1L),
            collect(composite),
        )
    }

    @Test
    fun theBaseMaximumIsUnchangedSoDictionaryRankingKeepsItsNormalizer() {
        val base = ListGlideInventory(listOf("бала" to 60L, "сәләм" to 100L))
        val personal = GlideTestFixtures.personalDictionary("адам" to 9)
        val composite = CompositeGlideInventory(base, personal) { false }

        // The most-used personal word ties the base maximum exactly, never exceeds it.
        val baseIndex = GlideWordIndex.build(base, geometry)
        val compositeIndex = GlideWordIndex.build(composite, geometry)
        assertEquals(baseIndex.maxFrequency, compositeIndex.maxFrequency)
        assertEquals(100L, compositeIndex.maxFrequency)
    }

    @Test
    fun aDuplicateIsNotIndexedButOverridesTheDictionaryCasing() {
        val base = ListGlideInventory(listOf("гүзәл" to 100L, "сәләм" to 50L))
        val personal = GlideTestFixtures.personalDictionary("Гүзәл" to 9)
        val composite = CompositeGlideInventory(base, personal) { it == "гүзәл" }

        // One candidate, not two: the entry count is the base's alone.
        assertEquals(2, composite.entryCount)
        assertEquals(listOf("гүзәл" to 100L, "сәләм" to 50L), collect(composite))
        // …and the dictionary entry carries the user's saved casing (E4b's one-cell rule).
        assertEquals("Гүзәл", composite.wordAt(0))
        assertEquals("сәләм", composite.wordAt(1))
    }

    @Test
    fun aDuplicateSavedExactlyAsTheDictionaryFormOverridesNothing() {
        val base = ListGlideInventory(listOf("гүзәл" to 100L))
        val personal = GlideTestFixtures.personalDictionary("гүзәл" to 9)
        val composite = CompositeGlideInventory(base, personal) { it == "гүзәл" }

        assertEquals(1, composite.entryCount)
        assertEquals("гүзәл", composite.wordAt(0))
    }

    @Test
    fun aPersonalEntryServesItsSavedCasingAtWordAtButItsNormalizedFormAtTheWalk() {
        val base = ListGlideInventory(listOf("бала" to 60L))
        val personal = GlideTestFixtures.personalDictionary("Айсулу" to 4)
        val composite = CompositeGlideInventory(base, personal) { false }

        assertEquals(listOf("бала" to 60L, "айсулу" to 60L), collect(composite))
        assertEquals("Айсулу", composite.wordAt(1))
    }

    @Test
    fun anUnmappablePersonalWordIsSkippedFailClosedByTheRealIndex() {
        val base = ListGlideInventory(listOf("сәләм" to 100L))
        // 'x' has no key on the Tatar layout: the word is served by the inventory but the
        // index build must skip it exactly like an unmappable dictionary word.
        val personal = GlideTestFixtures.personalDictionary("дәресx" to 5)
        val composite = CompositeGlideInventory(base, personal) { false }

        val index = GlideWordIndex.build(composite, geometry)
        assertEquals(2, composite.entryCount)
        assertEquals(1, index.wordCount)
        assertEquals(1, index.skippedWordCount)
    }

    @Test
    fun anEmptyPersonalDictionaryAddsNothing() {
        val base = ListGlideInventory(listOf("бала" to 60L, "сәләм" to 100L))
        val composite = CompositeGlideInventory(base, PersonalDictionary.EMPTY) { false }

        assertEquals(base.entryCount, composite.entryCount)
        assertEquals(collect(base), collect(composite))
        assertEquals(base.wordAt(1), composite.wordAt(1))
    }

    @Test
    fun aFullPersonalDictionaryStaysWithinTheMemoryBudget() {
        // The format cap: 2 000 entries of the maximum 24 code points, every letter mappable —
        // the worst case the decoder can ever index.
        val alphabet = "абвгдежзийклмнопрстуфхцчшщэюяәөүҗңһ"
        val entries = (0 until 2_000).map { i ->
            var suffix = ""
            var value = i
            repeat(4) {
                suffix += alphabet[value % alphabet.length]
                value /= alphabet.length
            }
            ("а".repeat(20) + suffix) to (i % 97 + 1)
        }.toTypedArray()
        val personal = GlideTestFixtures.personalDictionary(*entries)
        assertEquals(2_000, personal.size)

        val base = ListGlideInventory(listOf("бала" to 100L))
        val baseIndex = GlideWordIndex.build(base, geometry)
        val compositeIndex = GlideWordIndex.build(
            CompositeGlideInventory(base, personal) { false }, geometry,
        )
        assertEquals(2_001, compositeIndex.wordCount)
        val delta = compositeIndex.retainedByteEstimate - baseIndex.retainedByteEstimate
        // The mission's memory gate: +256 KiB worst case (a ~44 B flat-array cost per entry).
        assertTrue("the personal tail added $delta bytes", delta in 1..(256 * 1024L))
    }
}
