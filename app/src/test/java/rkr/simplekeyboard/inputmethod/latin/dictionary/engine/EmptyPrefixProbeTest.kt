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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The deferred empty-prefix probe, at the two levels that carry it:
 * [CompositePrefixComputer.shortestEmptyProperPrefixLength] does the binary search itself, and
 * [LatestOnlyPrefixEngine.requestEmptyPrefixProbe] sequences it on the serialized worker behind
 * every lookup already issued — the separator's next-word one in particular.
 */
class EmptyPrefixProbeTest {

    // --- The computer's binary search -----------------------------------------------------------

    /**
     * A fake primary whose exact pass continues exactly the prefixes in [nonEmptyPrefixes];
     * [knownWords] drives the frequency source the known-word shortcut reads.
     */
    private class FakePrimary(
        private val nonEmptyPrefixes: Set<String>,
        private val knownWords: Set<String> = emptySet(),
    ) : ClassifiedPrefixComputer, WordFrequencySource {
        val lookedUp = mutableListOf<String>()

        override var lastExactCount = 0
            private set

        override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> {
            val prefix = normalizedPrefixUtf8.decodeUtf8()
            lookedUp += prefix
            lastExactCount = if (prefix in nonEmptyPrefixes) 1 else 0
            return if (prefix in nonEmptyPrefixes) listOf(prefix + "л") else emptyList()
        }

        override fun frequencyOf(word: String): Long = if (word in knownWords) 5L else 0L
    }

    private class RangePrimary(
        private val nonEmptyPrefixes: Set<String>,
    ) : ClassifiedPrefixComputer, WordFrequencySource, ExactPrefixRangeChecker {
        val rangeChecks = mutableListOf<String>()

        override var lastExactCount = 0
            private set

        override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> =
            error("the exact-only probe must not run a full lookup")

        override fun frequencyOf(word: String): Long = 0L

        override fun hasExactContinuation(normalizedPrefixUtf8: ImmutableUtf8Prefix): Boolean {
            val prefix = normalizedPrefixUtf8.decodeUtf8()
            rangeChecks += prefix
            return prefix in nonEmptyPrefixes
        }
    }

    private fun prober(primary: ClassifiedPrefixComputer) =
        CompositePrefixComputer(primary, PersonalCandidateSource.EMPTY)

    @Test
    fun theBinarySearchFindsTheShortestEmptyProperPrefix() {
        // "әбитләре": one to three letters still continue into dictionary words, four do not.
        val primary = FakePrimary(setOf("ә", "әб", "әби"))
        val found = prober(primary).shortestEmptyProperPrefixLength("әбитләре")

        assertEquals(4, found)
        // The search halved its way there instead of walking every prefix.
        assertEquals(listOf("әбит", "әб", "әби"), primary.lookedUp)
    }

    @Test
    fun theMappedDictionaryPathUsesOnlyExactRangeChecks() {
        val primary = RangePrimary(setOf("ә", "әб", "әби"))

        val found = prober(primary).shortestEmptyProperPrefixLength("әбитләре")

        assertEquals(4, found)
        assertEquals(listOf("әбит", "әб", "әби"), primary.rangeChecks)
    }

    @Test
    fun aWordWhoseProperPrefixesAllContinueFindsNothing() {
        val word = "әбитләре"
        val primary = FakePrimary((1 until word.length).map { word.substring(0, it) }.toSet())

        assertEquals(
            EmptyPrefixProber.NO_EMPTY_PREFIX,
            prober(primary).shortestEmptyProperPrefixLength(word),
        )
    }

    @Test
    fun aDictionaryWordIsNotProbedAtAll() {
        val primary = FakePrimary(emptySet(), knownWords = setOf("сүз"))

        assertEquals(
            EmptyPrefixProber.NO_EMPTY_PREFIX,
            prober(primary).shortestEmptyProperPrefixLength("сүз"),
        )
        // The cold membership read answered it: no probe lookup ever ran.
        assertTrue(primary.lookedUp.isEmpty())
    }

    @Test
    fun theProbeLeavesThePublishedLookupVerdictsAlone() {
        val primary = FakePrimary(setOf("ә", "әб", "әби"))
        val computer = prober(primary)
        // A real lookup publishes its verdicts; "кү" finds no exact continuation here.
        computer.lookup(ImmutableUtf8Prefix.copyOf("кү".toByteArray(Charsets.UTF_8)))
        assertEquals("кү", computer.lastExactMissPrefix)
        assertEquals(0, computer.lastExactCount)

        computer.shortestEmptyProperPrefixLength("әбитләре")

        assertEquals("кү", computer.lastExactMissPrefix)
        assertEquals(0, computer.lastExactCount)
        assertNull(computer.lastAutocorrectAdvice)
    }

    // --- The engine's sequencing ----------------------------------------------------------------

    /** Records the order the worker touches the computer, per entry point. */
    private class RecordingComputer : ClassifiedPrefixComputer, NextWordComputer, EmptyPrefixProber {
        val events = mutableListOf<String>()

        override var lastExactCount = 0
            private set

        override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> {
            events += "lookup:" + normalizedPrefixUtf8.decodeUtf8()
            return emptyList()
        }

        override fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String> {
            events += "predict:" + normalizedContextWordUtf8.decodeUtf8()
            return emptyList()
        }

        override fun shortestEmptyProperPrefixLength(word: String): Int {
            events += "probe:$word"
            return 2
        }
    }

    private fun engine(
        executor: ManualEngineExecutor,
        computer: PrefixComputer,
    ) = LatestOnlyPrefixEngine(
        EngineTestFixtures.identity,
        computer,
        executor,
        ResultHandoff { },
    )

    @Test
    fun theProbeRunsBehindTheIssuedNextWordLookup() {
        val executor = ManualEngineExecutor()
        val computer = RecordingComputer()
        val engine = engine(executor, computer)
        val answer = AtomicInteger(EmptyPrefixProber.NO_EMPTY_PREFIX - 1)

        engine.requestNextWord(1, "tt", "сүз".toByteArray(Charsets.UTF_8))
        assertTrue(engine.requestEmptyPrefixProbe("сүзләр") { answer.set(it) })
        executor.runAll()

        assertEquals(listOf("predict:сүз", "probe:сүзләр"), computer.events)
        assertEquals(2, answer.get())
    }

    @Test
    fun theProbeRunsBehindEveryAlreadyIssuedPrefixLookup() {
        val executor = ManualEngineExecutor()
        val computer = RecordingComputer()
        val engine = engine(executor, computer)

        engine.request(1, "tt", "с".toByteArray(Charsets.UTF_8))
        engine.request(1, "tt", "сү".toByteArray(Charsets.UTF_8))
        assertTrue(engine.requestEmptyPrefixProbe("сүз") { })
        executor.runAll()

        // The burst coalesces as always (the middle prefix is dropped), and the probe runs last.
        assertEquals(listOf("lookup:с", "lookup:сү", "probe:сүз"), computer.events)
    }

    @Test
    fun aComputerWithoutTheProberRejectsTheProbe() {
        val executor = ManualEngineExecutor()
        val engine = engine(executor, PrefixComputer { emptyList() })

        assertFalse(engine.requestEmptyPrefixProbe("сүз") { })
    }

    @Test
    fun aDestroyedEngineRejectsTheProbe() {
        val executor = ManualEngineExecutor()
        val engine = engine(executor, RecordingComputer())
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))

        assertFalse(engine.requestEmptyPrefixProbe("сүз") { })
    }

    @Test
    fun aRejectingExecutorRejectsTheProbe() {
        val executor = ManualEngineExecutor(reject = true)
        val engine = engine(executor, RecordingComputer())

        assertFalse(engine.requestEmptyPrefixProbe("сүз") { })
    }
}
