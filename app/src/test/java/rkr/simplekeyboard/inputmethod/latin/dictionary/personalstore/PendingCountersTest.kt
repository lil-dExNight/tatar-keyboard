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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pending counters: a threshold, a cap, an expiry rule, and no user text anywhere. */
class PendingCountersTest {

    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun theThresholdIsThreeCleanCompletions() {
        assertEquals(3, PendingCounters.LEARN_THRESHOLD)
        val key = PendingCounters.keyOf(salt, "гүзәлия")
        var counters = PendingCounters.EMPTY
        counters = counters.note(key)
        assertEquals(1, counters.countOf(key))
        counters = counters.note(key)
        assertEquals("after two completions the word is still not learned",
            2, counters.countOf(key))
        assertTrue(counters.countOf(key) < PendingCounters.LEARN_THRESHOLD)
        counters = counters.note(key)
        assertTrue("the third completion reaches the threshold",
            counters.countOf(key) >= PendingCounters.LEARN_THRESHOLD)
    }

    @Test
    fun countingIsOnTheNormalizedFormSoTwoCasingsShareOneCounter() {
        val lower = PendingCounters.keyOf(salt, "гүзәл")
        val alsoLower = PendingCounters.keyOf(salt, "гүзәл")
        assertEquals(lower, alsoLower)
        // A different word must not collide with it.
        assertNotEquals(lower, PendingCounters.keyOf(salt, "гүзәллек"))
        // A different salt gives a different key for the same word — that is the point of the salt.
        assertNotEquals(lower, PendingCounters.keyOf(ByteArray(16) { 7 }, "гүзәл"))
    }

    @Test
    fun theCapEvictsTheOldestEntryRatherThanGrowing() {
        var counters = PendingCounters.EMPTY
        val first = PendingCounters.keyOf(salt, "слово000")
        counters = counters.note(first)
        for (index in 1 until PendingCounters.MAX_PENDING) {
            counters = counters.note(PendingCounters.keyOf(salt, "слово%03d".format(index)))
        }
        assertEquals(PendingCounters.MAX_PENDING, counters.size)
        counters = counters.note(PendingCounters.keyOf(salt, "переполнение"))
        assertEquals("the cap holds", PendingCounters.MAX_PENDING, counters.size)
        assertEquals("and the oldest entry is the one that left", 0, counters.countOf(first))
    }

    @Test
    fun anEntryThatFallsBehindTheLifetimeIsDroppedAtTheNextFlush() {
        val stale = PendingCounters.keyOf(salt, "однажды")
        var counters = PendingCounters.EMPTY.note(stale)
        assertEquals(1, counters.countOf(stale))
        repeat(PendingCounters.LIFETIME_SERIALS.toInt() + 1) { index ->
            counters = counters.note(PendingCounters.keyOf(salt, "прочее%04d".format(index)))
        }
        val pruned = counters.prunedForFlush()
        assertEquals("a word typed once and never again does not live in the file forever",
            0, pruned.countOf(stale))
    }

    @Test
    fun pruningIsAnIdentityWhenNothingIsStale() {
        val counters = PendingCounters.EMPTY.note(PendingCounters.keyOf(salt, "гүзәлия"))
        assertSame(counters, counters.prunedForFlush())
    }

    @Test
    fun theSerializedFileContainsNoByteOfTheWord() {
        val word = "гүзәлия"
        val counters = PendingCounters.EMPTY.note(PendingCounters.keyOf(salt, word))
        val bytes = counters.serialize()
        val encoded = word.toByteArray(StandardCharsets.UTF_8)
        assertEquals("the UTF-8 of the word must not appear in the file", -1, indexOf(bytes, encoded))
        // Nor its lowercase ASCII-ish transliteration guess, nor the salt itself.
        assertEquals(-1, indexOf(bytes, salt))
    }

    @Test
    fun aRoundTripPreservesCountsAndOrder() {
        var counters = PendingCounters.EMPTY
        listOf("беренче", "икенче", "өченче").forEach { word ->
            counters = counters.note(PendingCounters.keyOf(salt, word))
        }
        counters = counters.note(PendingCounters.keyOf(salt, "икенче"))
        val restored = PendingCounters.parse(counters.serialize())
        assertEquals(counters.size, restored.size)
        assertEquals(2, restored.countOf(PendingCounters.keyOf(salt, "икенче")))
        assertEquals(1, restored.countOf(PendingCounters.keyOf(salt, "беренче")))
    }

    @Test
    fun anythingUnreadableFailsClosedToEmpty() {
        assertEquals(0, PendingCounters.parse(ByteArray(0)).size)
        assertEquals(0, PendingCounters.parse(ByteArray(64)).size)
        val good = PendingCounters.EMPTY.note(PendingCounters.keyOf(salt, "гүзәлия")).serialize()
        val truncated = good.copyOf(good.size - 1)
        assertEquals(0, PendingCounters.parse(truncated).size)
        val corrupted = good.copyOf()
        corrupted[0] = 'X'.code.toByte()
        assertEquals(0, PendingCounters.parse(corrupted).size)
    }

    @Test
    fun anAbsurdNextSerialFailsClosedToEmpty() {
        // The header is MAGIC (8 bytes) + version (2) + count (2) + nextSerial (8, little-endian).
        val good = PendingCounters.EMPTY.note(PendingCounters.keyOf(salt, "гүзәлия")).serialize()
        fun withSerial(serial: Long): ByteArray {
            val bytes = good.copyOf()
            for (index in 0 until 8) bytes[12 + index] = (serial shr (8 * index)).toByte()
            return bytes
        }
        // A huge serial would wrap negative at the next note() and make every flush prune
        // everything: the file is rejected instead.
        assertEquals(0, PendingCounters.parse(withSerial(Long.MAX_VALUE)).size)
        assertEquals(0, PendingCounters.parse(withSerial(PendingCounters.MAX_NEXT_SERIAL + 1)).size)
        assertEquals(0, PendingCounters.parse(withSerial(0)).size)
        // The boundary itself still parses, and a note from it stays far from Long overflow.
        val atBound = PendingCounters.parse(withSerial(PendingCounters.MAX_NEXT_SERIAL))
        assertEquals(1, atBound.size)
        assertEquals(PendingCounters.MAX_NEXT_SERIAL, atBound.nextSerial)
        assertEquals(PendingCounters.MAX_NEXT_SERIAL + 1,
            atBound.note(PendingCounters.keyOf(salt, "дөнья")).nextSerial)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }
}
