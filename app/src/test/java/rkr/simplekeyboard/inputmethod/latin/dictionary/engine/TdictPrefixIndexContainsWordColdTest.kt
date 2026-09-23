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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 of Phase 2 (docs/ROADMAP-P2.md): the thread-safe, cache-free whole-word membership read the
 * personal-bigram context gate consults from the store's worker — never from the lookup worker.
 * Pinned here: exact agreement with the dictionary contents, the input guards, and a concurrency
 * smoke run proving the cold read races no scratch the hot lookup path owns.
 */
class TdictPrefixIndexContainsWordColdTest {

    // 200 words across several front-coded blocks, so the cold read walks real block structure.
    private val words = (0 until 200)
        .map { index ->
            "бал" + Char(0x0430 + index % 32) +
                Char(0x0430 + index / 32 % 32) + Char(0x0430 + index / 64 % 32)
        }
        .distinct()
        .sorted()
    private val index = EngineTestFixtures.index(words.map { it to 1L })

    @Test
    fun everyDictionaryWordIsFoundWhereverItSorts() {
        for (word in words) {
            assertTrue("[$word] must be found", index.containsWordCold(word))
        }
    }

    @Test
    fun wordsOutsideTheDictionaryAreNotFound() {
        assertFalse(index.containsWordCold("ааа")) // sorts before everything
        assertFalse(index.containsWordCold("яяяяяя")) // sorts after everything
        assertFalse(index.containsWordCold("балабвж")) // between entries
        assertFalse(index.containsWordCold("бала")) // a proper prefix of real words is not a word
        assertFalse(index.containsWordCold(words[0] + "а")) // a word plus one letter is not a word
    }

    @Test
    fun theInputGuardsHold() {
        assertFalse(index.containsWordCold(""))
        assertFalse(index.containsWordCold("а".repeat(200))) // past MAX_WORD_BYTES
    }

    @Test
    fun theColdReadAgreesWithFrequencyOfOnTheSameWords() {
        // frequencyOf is the worker-confined exact lookup; the cold read must agree with it on
        // every word, or the context gate and the engine would disagree about the dictionary.
        for (word in words.take(25)) {
            assertEquals(
                "[$word]",
                index.frequencyOf(word) > 0,
                index.containsWordCold(word),
            )
        }
    }

    @Test
    fun theColdReadRacesTheLookupPathWithoutEitherBreaking() {
        // The contract this method exists for: a foreign thread may read while the lookup worker
        // scans, because the cold path touches nothing but the read-only buffer and its locals.
        val failures = ConcurrentLinkedQueue<String>()
        val wordList = words
        val rounds = 40
        val startGate = CountDownLatch(1)
        val readers = (1..3).map { readerIndex ->
            thread(name = "cold-reader-$readerIndex") {
                startGate.await()
                repeat(rounds) { round ->
                    val word = wordList[(round * 5 + readerIndex) % wordList.size]
                    if (!index.containsWordCold(word)) failures += "missed [$word]"
                    if (index.containsWordCold(word + "я")) failures += "invented [${word}я]"
                }
            }
        }
        startGate.countDown()
        // Hammer the real lookup path from this thread while the readers run.
        repeat(rounds) { round ->
            index.lookup(
                ImmutableUtf8Prefix.copyOf(
                    wordList[(round * 3) % wordList.size].take(2).toByteArray(Charsets.UTF_8),
                ),
            )
        }
        readers.forEach { it.join() }
        assertTrue(failures.toString(), failures.isEmpty())
    }
}
