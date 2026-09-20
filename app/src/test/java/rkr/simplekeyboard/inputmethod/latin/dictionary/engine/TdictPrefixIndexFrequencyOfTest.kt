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
import java.lang.management.ManagementFactory

/**
 * P3 (docs/TT-SUGGESTIONS.md): [TdictPrefixIndex.frequencyOf] — exact whole-word frequency lookup,
 * reusing the block-search machinery, with the lookup-path allocation discipline pinned by the
 * same ThreadMXBean pattern the fuzzy pass uses.
 */
class TdictPrefixIndexFrequencyOfTest {

    private val entries = listOf(
        "бала" to 20L,
        "балалар" to 4L,
        "балда" to 9L,
        "китап" to 1_000L,
        "татар" to 60_000L,
        "татарлар" to 12_085L,
        "татарча" to 9_093L,
        "өй" to 4_000L,
    )

    private fun bytes(word: String) = word.toByteArray(Charsets.UTF_8)

    @Test
    fun presentWordsReturnTheirExactFrequency() {
        val index = EngineTestFixtures.index(entries)
        for ((word, frequency) in entries) {
            assertEquals(word, frequency, index.frequencyOf(bytes(word), bytes(word).size))
        }
        // The string form agrees with the byte form.
        assertEquals(60_000L, index.frequencyOf("татар"))
    }

    @Test
    fun absentWordsReturnTheZeroSentinel() {
        val index = EngineTestFixtures.index(entries)
        // Prefixes of words are not words; neither are continuations past a word, other blocks'
        // neighbors, or the empty query.
        for (absent in listOf("бал", "балдак", "татарларга", "мин", "я")) {
            assertEquals(absent, 0L, index.frequencyOf(bytes(absent), bytes(absent).size))
            assertEquals(absent, 0L, index.frequencyOf(absent))
        }
        assertEquals(0L, index.frequencyOf(bytes(""), 0))
    }

    @Test
    fun oversizedQueriesReturnTheZeroSentinelWithoutTouchingTheIndex() {
        val index = EngineTestFixtures.index(entries)
        val oversized = ByteArray(TdictPrefixIndex.MAX_PREFIX_BYTES + 1) { 'а'.code.toByte() }
        assertEquals(0L, index.frequencyOf(oversized, oversized.size))
    }

    // The lookup-path discipline: a whole-word frequency read must not allocate, whether the word
    // is present (one block decode, then cache hits) or absent. Same measurement pattern as
    // TdictPrefixIndexFuzzyTest.perLookupAllocationDoesNotDependOnTheNumberOfVariants.
    @Test
    fun perLookupAllocationIsZeroForPresentAndAbsentWords() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        val index = EngineTestFixtures.index(entries)
        val present = bytes("татар")
        val absent = bytes("татарларга")

        fun perCallBytes(query: ByteArray, iterations: Int): Long {
            val before = threadBean.getThreadAllocatedBytes(threadId)
            for (i in 0 until iterations) index.frequencyOf(query, query.size)
            val after = threadBean.getThreadAllocatedBytes(threadId)
            return (after - before) / iterations
        }

        // Warm the block cache and the call sites before measuring.
        perCallBytes(present, 50_000)
        perCallBytes(absent, 50_000)

        val presentBytes = perCallBytes(present, 200_000)
        val absentBytes = perCallBytes(absent, 200_000)
        assertTrue("present=$presentBytes bytes/call", presentBytes <= 8L)
        assertTrue("absent=$absentBytes bytes/call", absentBytes <= 8L)
    }
}
