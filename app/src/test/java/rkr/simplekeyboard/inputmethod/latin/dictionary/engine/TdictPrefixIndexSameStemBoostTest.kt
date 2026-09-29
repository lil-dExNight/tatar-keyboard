/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil

/**
 * P3 same-stem boost (docs/TT-SUGGESTIONS.md): when the typed prefix is itself a complete
 * dictionary word of at least four code points, exact candidates whose remainder is a known Tatar
 * suffix rank before unrelated continuations; frequency order is preserved within each group; the
 * typed word itself stays excluded. This consciously amends the frozen D1 ranking for that one
 * case — everything else is pinned byte-identical below (no table, a prefix that is not a complete
 * word, or a complete word below the four-code-point threshold).
 *
 * The synthetic tests pin the merge rule itself; the real-asset tests pin the mission's acceptance
 * (prefix татар ranks татарлар/татарча above татарстан*) against the shipped dictionaries.
 */
class TdictPrefixIndexSameStemBoostTest {

    private fun lookup(index: TdictPrefixIndex, prefix: String): List<String> =
        index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))

    // Sorted fixture (mind the byte order: балайт < балак < балалар < балалы < балам — й < к < л < м).
    // "бала" is the complete word at four code points; "бал" the same below the threshold.
    // Stem-track remainders: лар (балалар), лы (балалы), м (балам); unrelated: балайт, балак.
    private val fixture = listOf(
        "бал" to 60L,
        "бала" to 50L,
        "балайт" to 100L,
        "балак" to 90L,
        "балалар" to 10L,
        "балалы" to 12L,
        "балам" to 4L,
    )

    @Test
    fun withoutATableTheFrozenOrderIsByteIdentical() {
        val index = EngineTestFixtures.index(fixture)
        // The exact D1 expectation: frequency desc, code-point asc on ties, typed word excluded.
        assertEquals(listOf("балайт", "балак", "балалы"), lookup(index, "бала"))
    }

    @Test
    fun withATableSameStemContinuationsRankBeforeUnrelatedOnes() {
        val index = EngineTestFixtures.index(fixture, TatarSuffixRules)
        // Stem track (балалы 12, балалар 10, балам 4 — frequency desc) fills all three cells; the
        // unrelated балайт (100) and балак (90) fall off the band despite outranking them all.
        assertEquals(listOf("балалы", "балалар", "балам"), lookup(index, "бала"))
    }

    @Test
    fun theBoostKeepsFrequencyOrderInsideTheStemGroup() {
        // балалар is code-point-before балалы; the stem group must keep frequency order, so
        // балалы (12) leads балалар (10).
        val index = EngineTestFixtures.index(fixture, TatarSuffixRules)
        val result = lookup(index, "бала")
        assertTrue(result.indexOf("балалы") < result.indexOf("балалар"))
    }

    @Test
    fun theTypedWordItselfStaysExcluded() {
        val index = EngineTestFixtures.index(fixture, TatarSuffixRules)
        assertTrue(lookup(index, "бала").none { it == "бала" })
    }

    @Test
    fun aPrefixThatIsNotACompleteWordKeepsTheFrozenOrder() {
        // "балай" is long enough but not a dictionary word: with or without the table the result
        // must be identical — the length gate alone never engages the boost.
        val withTable = EngineTestFixtures.index(fixture, TatarSuffixRules)
        val withoutTable = EngineTestFixtures.index(fixture)
        for (prefix in listOf("б", "ба", "балай", "баламк")) {
            assertEquals("prefix=$prefix", lookup(withoutTable, prefix), lookup(withTable, prefix))
        }
    }

    @Test
    fun aCompleteWordBelowFourCodePointsIsNotBoosted() {
        // "бал" IS a complete dictionary word, but at three code points it is a common mid-typing
        // state: the threshold keeps the frozen order byte-identical.
        val withTable = EngineTestFixtures.index(fixture, TatarSuffixRules)
        val withoutTable = EngineTestFixtures.index(fixture)
        assertEquals(listOf("балайт", "балак", "бала"), lookup(withoutTable, "бал"))
        assertEquals(lookup(withoutTable, "бал"), lookup(withTable, "бал"))
    }

    @Test
    fun aPrefixWithNoContinuationStaysEmpty() {
        val index = EngineTestFixtures.index(fixture, TatarSuffixRules)
        assertTrue(lookup(index, "балам").isEmpty())
    }

    // --- Real shipped assets --------------------------------------------------------------------

    @Test
    fun tatarPrefixBoostsInflectionsAboveToponyms() {
        val index = requireNotNull(tatarWithRules)
        // Mission acceptance (docs/TT-SUGGESTIONS-PLAN.md P3 "Done when"): татарлар/татарча-type
        // continuations above татарстан*. татар is five code points — above the threshold.
        assertEquals(listOf("татарлар", "татарча", "татарлары"), lookup(index, "татар"))
    }

    @Test
    fun shortCompleteTatarWordsKeepTheFrozenOrder() {
        val withRules = requireNotNull(tatarWithRules)
        val withoutRules = requireNotNull(tatarWithoutRules)
        // су (2 cp) and өй (2 cp) are complete words below the threshold: no boost, although both
        // have table-suffix continuations — measured identical to the frozen pass.
        assertEquals(listOf("сум", "сугыш", "сумга"), lookup(withoutRules, "су"))
        assertEquals(lookup(withoutRules, "су"), lookup(withRules, "су"))
        assertEquals(listOf("өйрәнү", "өйдә", "өйрәнергә"), lookup(withoutRules, "өй"))
        assertEquals(lookup(withoutRules, "өй"), lookup(withRules, "өй"))
        // кит (3 cp): same, one code point below the gate.
        assertEquals(lookup(withoutRules, "кит"), lookup(withRules, "кит"))
        assertEquals(listOf("китте", "киткән", "китап"), lookup(withoutRules, "кит"))
    }

    @Test
    fun tatarPrefixThatIsNotACompleteWordKeepsTheRecordedOrder() {
        val withRules = requireNotNull(tatarWithRules)
        val withoutRules = requireNotNull(tatarWithoutRules)
        // "тата" is not a dictionary word — the boost must be inert, byte for byte.
        assertEquals(
            listOf("татар", "татарстан", "татарстанда"),
            lookup(withoutRules, "тата"),
        )
        assertEquals(lookup(withoutRules, "тата"), lookup(withRules, "тата"))
    }

    @Test
    fun theRussianEngineNeverAppliesTatarRules() {
        val russian = requireNotNull(russianIndex)
        // The production shape: the Russian engine is constructed WITHOUT a table, so its frozen
        // order stands. майор is a five-code-point complete word whose continuation майору carries
        // a Tatar table remainder (у) — the with-table column reorders it, which is the
        // counter-proof that the injection is the only thing that changes the ranking.
        assertEquals(listOf("майора", "майором", "майору"), lookup(russian, "майор"))
        assertEquals(
            listOf("майора", "майору", "майором"),
            lookup(requireNotNull(russianWithRules), "майор"),
        )
    }

    // --- Budgets on the boost path --------------------------------------------------------------

    // The boost pass adds no allocations of its own: measure the same prefix, with the same
    // results, on two fixture indexes that differ only in the table. (The fixture is crafted so
    // the boosted and frozen rankings coincide, isolating the pass's own cost.)
    @Test
    fun theEngagedBoostAllocatesNothingPerLookup() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        val allocFixture = listOf(
            "бала" to 50L,
            "балайт" to 8L,
            "балалы" to 12L,
            "балам" to 9L,
        )
        val withTable = EngineTestFixtures.index(allocFixture, TatarSuffixRules)
        val withoutTable = EngineTestFixtures.index(allocFixture)
        val prefix = ImmutableUtf8Prefix.copyOf("бала".toByteArray(Charsets.UTF_8))
        // The crafted equality the measurement relies on: identical result lists, so identical
        // materialization cost on both sides — and the boost really is engaged (four code points).
        assertEquals(listOf("балалы", "балам", "балайт"), lookup(withoutTable, "бала"))
        assertEquals(lookup(withoutTable, "бала"), lookup(withTable, "бала"))

        fun perLookupBytes(index: TdictPrefixIndex, iterations: Int): Long {
            val before = threadBean.getThreadAllocatedBytes(threadId)
            for (i in 0 until iterations) index.lookup(prefix)
            val after = threadBean.getThreadAllocatedBytes(threadId)
            return (after - before) / iterations
        }

        perLookupBytes(withTable, 50_000)
        perLookupBytes(withoutTable, 50_000)
        val withBytes = perLookupBytes(withTable, 200_000)
        val withoutBytes = perLookupBytes(withoutTable, 200_000)
        // A handful of bytes of slack absorbs measurement noise (same slack as the fuzzy test).
        assertTrue(
            "withTable=$withBytes withoutTable=$withoutBytes bytes/lookup",
            abs(withBytes - withoutBytes) <= 8L,
        )
    }

    // The p95 lookup budget with the boost engaged, over prefixes that ARE complete words of at
    // least four code points (the only case the dual-track pass runs) — measured on the real
    // shipped Tatar asset. The frequencyOf assertion per prefix keeps the sample honest: a word
    // that drops out of the dictionary fails the test loudly instead of silently measuring the
    // disabled path.
    @Test
    fun boostedLookupP95OverCompleteWordPrefixesIsAtMostFiveMilliseconds() {
        val index = requireNotNull(tatarWithRules)
        index.updateKeyNeighbors(null)
        val words = listOf("татар", "китап", "урман", "бала", "мәктәп", "татарлар")
        for (word in words) {
            assertTrue("$word must be a complete word", index.frequencyOf(word) > 0L)
        }
        val prefixes = words.map { ImmutableUtf8Prefix.copyOf(it.toByteArray(Charsets.UTF_8)) }
        repeat(500) { index.lookup(prefixes[it % prefixes.size]) }

        val timings = LongArray(2_000)
        var consumed = 0L
        for (sample in timings.indices) {
            val prefix = prefixes[sample % prefixes.size]
            val started = System.nanoTime()
            val results = index.lookup(prefix)
            timings[sample] = System.nanoTime() - started
            for (result in results) consumed = consumed * 31 + result.length
        }
        timings.sort()
        val medianMillis = timings[timings.size / 2] / 1_000_000.0
        val p95Millis = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
        println(
            "P3 boosted compute median=${"%.3f".format(java.util.Locale.ROOT, medianMillis)} ms " +
                "p95=${"%.3f".format(java.util.Locale.ROOT, p95Millis)} ms consumed=$consumed",
        )
        assertTrue("boosted p95=${p95Millis}ms", timings[ceil(timings.size * 0.95).toInt() - 1] <= 5_000_000L)
        assertTrue(consumed != Long.MIN_VALUE)
    }

    companion object {
        private var tatarWithRules: TdictPrefixIndex? = null
        private var tatarWithoutRules: TdictPrefixIndex? = null
        private var russianIndex: TdictPrefixIndex? = null
        private var russianWithRules: TdictPrefixIndex? = null

        private fun load(
            assetPath: String,
            spec: DictionaryArtifactSpec,
            suffixTable: InflectedSuffixTable?,
        ): TdictPrefixIndex {
            val asset = listOf(File("src/main/$assetPath"), File("app/src/main/$assetPath"))
                .firstOrNull(File::isFile) ?: error("cannot locate $assetPath")
            val rawFile = File.createTempFile("p3-boost-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                val identity = DictionaryIdentity(
                    spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
                )
                return requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(rawFile.readBytes()), identity,
                        validated.entryCount, validated.rawSize, suffixTable,
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        @JvmStatic
        @BeforeClass
        fun loadCommittedDictionaries() {
            tatarWithRules = load(
                "assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                DictionaryArtifactSpec.TATAR_TOP100K_V1, TatarSuffixRules,
            )
            tatarWithoutRules = load(
                "assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                DictionaryArtifactSpec.TATAR_TOP100K_V1, null,
            )
            russianIndex = load(
                "assets/dictionaries/russian_top100k_v1.tdict.zlib",
                DictionaryArtifactSpec.RUSSIAN_TOP100K_V1, null,
            )
            russianWithRules = load(
                "assets/dictionaries/russian_top100k_v1.tdict.zlib",
                DictionaryArtifactSpec.RUSSIAN_TOP100K_V1, TatarSuffixRules,
            )
        }
    }
}
