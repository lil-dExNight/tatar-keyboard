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
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramTestFixtures
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.lang.management.ManagementFactory
import kotlin.math.abs

/**
 * The context boost of the exact pass: candidates among the previous committed word's packed
 * bigram successors rank first, frequency order kept within the track, then the unchanged
 * same-stem and other tracks. The successors arrive as dictionary entry indices
 * ([TatBigrPrefixIndex.collectSuccessorIds]); with no successor armed the lookup is
 * byte-identical to the pre-boost pass, which the guard tests pin.
 */
class TdictPrefixIndexContextBoostTest {

    private fun lookup(index: TdictPrefixIndex, prefix: String): List<String> =
        index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))

    private fun lookupArmed(
        index: TdictPrefixIndex,
        prefix: String,
        successorIds: IntArray,
        successorCount: Int,
    ): List<String> =
        index.lookup(
            ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)),
            successorIds, successorCount,
        )

    private fun idsOf(index: TdictPrefixIndex, vararg words: String): IntArray =
        IntArray(words.size) { slot ->
            val bytes = words[slot].toByteArray(Charsets.UTF_8)
            val id = index.indexOfWord(bytes, bytes.size)
            assertTrue("${words[slot]} must be a dictionary entry", id >= 0)
            id
        }

    // Sorted fixture (byte order: балайт < балак < балалар < балалы < балам — й < к < л < м),
    // shared with the same-stem boost tests. "бала" is the complete word at four code points.
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
    fun aContextSuccessorRanksFirstAndKeepsFrequencyOrderInsideTheTrack() {
        val index = EngineTestFixtures.index(fixture)
        // The frozen order: frequency desc, the typed word excluded.
        assertEquals(listOf("балайт", "балак", "балалы", "балалар"), lookup(index, "бала"))
        // Armed with балак and балалар as the previous word's packed successors: they lead, in
        // their own frequency order (90 > 10), and the rest keeps the frozen order.
        val successors = idsOf(index, "балак", "балалар")
        assertEquals(
            listOf("балак", "балалар", "балайт", "балалы"),
            lookupArmed(index, "бала", successors, 2),
        )
    }

    @Test
    fun theContextTrackLeadsTheSameStemTrack() {
        val index = EngineTestFixtures.index(fixture, TatarSuffixRules)
        // Same-stem alone: балалы/балалар/балам lead, балайт keeps the last cell.
        assertEquals(listOf("балалы", "балалар", "балам", "балайт"), lookup(index, "бала"))
        // Armed with балак (an unrelated continuation): it leads, the stem track follows, and
        // балайт leaves the strip.
        val successors = idsOf(index, "балак")
        assertEquals(
            listOf("балак", "балалы", "балалар", "балам"),
            lookupArmed(index, "бала", successors, 1),
        )
    }

    @Test
    fun aSuccessorThatIsAlsoASameStemContinuationTakesTheContextTrackOnly() {
        val index = EngineTestFixtures.index(fixture, TatarSuffixRules)
        // балалар's remainder (лар) is a table suffix AND it is the armed successor: it leads
        // alone, not twice.
        val successors = idsOf(index, "балалар")
        val result = lookupArmed(index, "бала", successors, 1)
        assertEquals(listOf("балалар", "балалы", "балам", "балайт"), result)
        assertEquals(1, result.count { it == "балалар" })
    }

    @Test
    fun theTypedWordItselfStaysExcludedEvenWhenItIsASuccessor() {
        val index = EngineTestFixtures.index(fixture)
        // The packed table can hold the word itself; the typed word is still never a candidate.
        val successors = idsOf(index, "бала", "балак")
        assertEquals(
            listOf("балак", "балайт", "балалы", "балалар"),
            lookupArmed(index, "бала", successors, 2),
        )
    }

    @Test
    fun successorsOutsideThePrefixRangeChangeNothing() {
        val index = EngineTestFixtures.index(fixture)
        // "бал" is a dictionary entry but does not continue "бала"; nor does a complete word
        // elsewhere ("дан" is not in this fixture, so reuse бал as the off-range id).
        val successors = idsOf(index, "бал")
        assertEquals(lookup(index, "бала"), lookupArmed(index, "бала", successors, 1))
    }

    @Test
    fun anEmptyContextIsByteIdenticalToTheUnarmedLookup() {
        val withRules = EngineTestFixtures.index(fixture, TatarSuffixRules)
        val withoutRules = EngineTestFixtures.index(fixture)
        for (prefix in listOf("б", "бал", "бала", "балам")) {
            for (index in listOf(withRules, withoutRules)) {
                val plain = lookup(index, prefix)
                assertEquals(plain, lookupArmed(index, prefix, IntArray(0), 0))
                assertEquals(plain, lookupArmed(index, prefix, idsOf(index, "балак"), 0))
            }
        }
    }

    @Test
    fun aDroppedContextLeavesNoResidueOnTheNextLookup() {
        val index = EngineTestFixtures.index(fixture)
        val successors = idsOf(index, "балак")
        assertEquals(
            listOf("балак", "балайт", "балалы", "балалар"),
            lookupArmed(index, "бала", successors, 1),
        )
        // The unarmed lookup that follows must not see the previous call's context.
        assertEquals(listOf("балайт", "балак", "балалы", "балалар"), lookup(index, "бала"))
    }

    // --- End-to-end through the packed bigram table ----------------------------------------------

    private fun bigramsOf(
        dictionary: TdictPrefixIndex,
        headsToSuccesses: List<Pair<String, List<String>>>,
        dictionaryWords: List<String>,
    ): TatBigrPrefixIndex {
        // The fixture table is packed against the fixture dictionary's own identity
        // (BigramTestFixtures.DEFAULT_DICTIONARY_SHA), so it opens against [dictionary].
        val raw = BigramTestFixtures.raw(headsToSuccesses, dictionaryWords)
        return requireNotNull(
            TatBigrPrefixIndex.open(
                java.nio.ByteBuffer.wrap(raw), EngineTestFixtures.bigramIdentity, dictionary,
                headsToSuccesses.size.toLong(), raw.size.toLong(),
            ),
        )
    }

    @Test
    fun thePackedIdsOfTheTableDriveTheBoost() {
        // The sim's flip example shape: after дан, typing ка… — казанган leads the strip although
        // it is the rarest continuation.
        // Sorted fixture (byte order: казанга < казанган < казанда < казанның — г < д < н).
        val entries = listOf(
            "дан" to 100L,
            "казан" to 90L,
            "казанган" to 7L,
            "казанда" to 80L,
            "казанның" to 70L,
        )
        val dictionary = EngineTestFixtures.index(entries)
        val bigrams = bigramsOf(
            dictionary, listOf("дан" to listOf("казанган")), entries.map { it.first },
        )
        val ids = IntArray(TatBigrPrefixIndex.MAX_RESULTS)
        val count = bigrams.collectSuccessorIds(
            ImmutableUtf8Prefix.copyOf("дан".toByteArray(Charsets.UTF_8)), ids,
        )
        assertEquals(1, count)
        assertEquals(
            bigrams.predict(ImmutableUtf8Prefix.copyOf("дан".toByteArray(Charsets.UTF_8))),
            (0 until count).map { dictionary.wordAt(ids[it]) },
        )
        assertEquals(
            listOf("казан", "казанда", "казанның", "казанган"),
            lookup(dictionary, "ка"),
        )
        assertEquals(
            listOf("казанган", "казан", "казанда", "казанның"),
            lookupArmed(dictionary, "ка", ids, count),
        )
    }

    @Test
    fun unknownContextHeadsYieldNoIds() {
        val dictionary = EngineTestFixtures.index(listOf("дан" to 1L, "капка" to 1L))
        val bigrams = bigramsOf(
            dictionary, listOf("дан" to listOf("капка")), listOf("дан", "капка"),
        )
        val ids = IntArray(TatBigrPrefixIndex.MAX_RESULTS)
        // Not a head, not a dictionary word, and the empty word: all yield nothing.
        assertEquals(
            0,
            bigrams.collectSuccessorIds(
                ImmutableUtf8Prefix.copyOf("капка".toByteArray(Charsets.UTF_8)), ids,
            ),
        )
        assertEquals(
            0,
            bigrams.collectSuccessorIds(
                ImmutableUtf8Prefix.copyOf("сүз".toByteArray(Charsets.UTF_8)), ids,
            ),
        )
        assertEquals(
            0,
            bigrams.collectSuccessorIds(ImmutableUtf8Prefix.copyOf(ByteArray(0)), ids),
        )
    }

    // --- Budgets on the boost path --------------------------------------------------------------

    // The boost pass adds no allocations of its own: measure the same prefix, with the same
    // results, armed and unarmed. (The fixture is crafted so the armed and frozen rankings
    // coincide, isolating the pass's own cost — the same discipline as the same-stem test.)
    @Test
    fun theEngagedBoostAllocatesNothingPerLookup() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        // Sorted fixture (byte order: казанга < казанда < казанның — г < д < н).
        val allocFixture = listOf(
            "казан" to 90L,
            "казанга" to 60L,
            "казанда" to 80L,
            "казанның" to 70L,
        )
        val index = EngineTestFixtures.index(allocFixture)
        val successors = idsOf(index, "казан", "казанда")
        val prefix = ImmutableUtf8Prefix.copyOf("казан".toByteArray(Charsets.UTF_8))
        // The crafted equality the measurement relies on: identical result lists, so identical
        // materialization cost on both sides — and the boost really is engaged (two armed ids).
        assertEquals(
            listOf("казанда", "казанның", "казанга"),
            lookup(index, "казан"),
        )
        assertEquals(
            lookup(index, "казан"),
            index.lookup(prefix, successors, 2),
        )

        fun perLookupBytes(armed: Boolean, iterations: Int): Long {
            val before = threadBean.getThreadAllocatedBytes(threadId)
            for (i in 0 until iterations) {
                if (armed) index.lookup(prefix, successors, 2) else index.lookup(prefix)
            }
            val after = threadBean.getThreadAllocatedBytes(threadId)
            return (after - before) / iterations
        }

        perLookupBytes(armed = true, iterations = 50_000)
        perLookupBytes(armed = false, iterations = 50_000)
        val armedBytes = perLookupBytes(armed = true, iterations = 200_000)
        val unarmedBytes = perLookupBytes(armed = false, iterations = 200_000)
        // A handful of bytes of slack absorbs measurement noise (same slack as the fuzzy test).
        assertTrue(
            "armed=$armedBytes unarmed=$unarmedBytes bytes/lookup",
            abs(armedBytes - unarmedBytes) <= 8L,
        )
    }
}
