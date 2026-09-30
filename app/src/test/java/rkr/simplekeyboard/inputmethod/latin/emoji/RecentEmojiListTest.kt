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

package rkr.simplekeyboard.inputmethod.latin.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure MRU model and its fail-closed codec, exercised entirely on the plain JVM.
 */
class RecentEmojiListTest {

    private val separator = RecentEmojiList.SEPARATOR

    /** A distinct, whitespace-free token of [length] `char`. */
    private fun token(id: Int, length: Int): String {
        val prefix = "e$id-"
        return prefix + "x".repeat((length - prefix.length).coerceAtLeast(1))
    }

    // --- MRU semantics -------------------------------------------------------------------------

    @Test
    fun mostRecentInsertIsAlwaysFirst() {
        val list = RecentEmojiList.EMPTY.used("A").used("B").used("C")
        assertEquals(listOf("C", "B", "A"), list.entries)
    }

    @Test
    fun reinsertingMovesToFrontWithoutDuplicating() {
        val list = RecentEmojiList.of("A", "B", "C")
        assertEquals(listOf("A", "B", "C"), list.entries)

        val moved = list.used("C")
        assertEquals(listOf("C", "A", "B"), moved.entries)
        // No duplicate: still exactly three entries.
        assertEquals(3, moved.entries.size)
        assertEquals(3, moved.entries.toSet().size)
    }

    @Test
    fun tapInsideRecentTabMovesTheTappedEntryToFront() {
        // A tap inside the Recent tab is just a use of an entry already in the list.
        val list = RecentEmojiList.of("A", "B", "C", "D")
        val moved = list.used("C")
        assertEquals(listOf("C", "A", "B", "D"), moved.entries)
    }

    @Test
    fun twentyFifthInsertEvictsTheOldestEntry() {
        var list = RecentEmojiList.EMPTY
        // Insert e0..e23 so the list is full (24) with e23 at the front, e0 at the back.
        for (i in 0 until RecentEmojiList.MAX_ENTRIES) {
            list = list.used("e$i")
        }
        assertEquals(RecentEmojiList.MAX_ENTRIES, list.entries.size)
        assertEquals("e0", list.entries.last())

        val evicted = list.used("new")
        assertEquals(RecentEmojiList.MAX_ENTRIES, evicted.entries.size)
        assertEquals("new", evicted.entries.first())
        // The oldest entry (e0) fell off; the 25th did not grow the list.
        assertFalse(evicted.entries.contains("e0"))
    }

    @Test
    fun usingAnEmptySequenceIsANoOp() {
        val list = RecentEmojiList.of("A", "B")
        assertSame(list, list.used(""))
    }

    // --- The independent 512-char budget -------------------------------------------------------

    @Test
    fun totalLengthNeverExceedsMaxChars() {
        // Nine 64-char tokens sum to 576 > 512, so the tail is dropped by the char budget before
        // the 24-entry cap can bite. The result stays within MAX_CHARS.
        val tokens = Array(9) { token(it, 64) }
        val list = RecentEmojiList.of(*tokens)
        val total = list.entries.sumOf { it.length }
        assertTrue("total $total exceeds ${RecentEmojiList.MAX_CHARS}", total <= RecentEmojiList.MAX_CHARS)
        // The front entry is kept; the last token that would break the budget is dropped.
        assertEquals(tokens[0], list.entries.first())
        assertFalse(list.entries.contains(tokens[8]))
    }

    @Test
    fun deserializeRejectsOverBudgetContentAsEmpty() {
        val raw = (0 until 9).joinToString(separator.toString()) { token(it, 64) }
        assertTrue(RecentEmojiList.deserialize(raw).isEmpty)
    }

    // --- Fail-closed codec ---------------------------------------------------------------------

    @Test
    fun roundTripsAValidMedium() {
        val list = RecentEmojiList.of("A", "B", "C")
        val restored = RecentEmojiList.deserialize(list.serialize())
        assertEquals(list, restored)
        assertEquals(listOf("A", "B", "C"), restored.entries)
    }

    @Test
    fun nullOrEmptyMediumYieldsEmpty() {
        assertTrue(RecentEmojiList.deserialize(null).isEmpty)
        assertTrue(RecentEmojiList.deserialize("").isEmpty)
    }

    @Test
    fun corruptedMediumYieldsEmptyWithoutThrowing() {
        // A duplicate token.
        assertTrue(RecentEmojiList.deserialize("A${separator}A").isEmpty)
        // A token carrying whitespace (never valid inside an emoji sequence).
        assertTrue(RecentEmojiList.deserialize("A${separator}B C").isEmpty)
        // A single token longer than the per-sequence bound.
        assertTrue(RecentEmojiList.deserialize("x".repeat(200)).isEmpty)
        // More tokens than the entry cap.
        val tooMany = (0..RecentEmojiList.MAX_ENTRIES).joinToString(separator.toString()) { "e$it" }
        assertTrue(RecentEmojiList.deserialize(tooMany).isEmpty)
    }

    @Test
    fun partiallyBrokenMediumYieldsEmptyRatherThanKeepingTheGoodTokens() {
        // One bad token anywhere collapses the whole read: fail-closed, never partial.
        val raw = "A${separator}B${separator}C D${separator}E"
        assertTrue(RecentEmojiList.deserialize(raw).isEmpty)
    }

    // --- filteredTo: entries absent from the snapshot are dropped ------------------------------

    @Test
    fun filteredToDropsEntriesAbsentFromTheSnapshotPreservingOrder() {
        val list = RecentEmojiList.of("A", "B", "C")
        val filtered = list.filteredTo(setOf("A", "C"))
        assertEquals(listOf("A", "C"), filtered.entries)
    }

    @Test
    fun filteredToReturnsSameInstanceWhenAllPresentAndEmptyWhenNonePresent() {
        val list = RecentEmojiList.of("A", "B")
        assertSame(list, list.filteredTo(setOf("A", "B", "Z")))
        assertTrue(list.filteredTo(setOf("Z")).isEmpty)
    }

    @Test
    fun equalityIsByEntriesInOrder() {
        assertEquals(RecentEmojiList.of("A", "B"), RecentEmojiList.of("A", "B"))
        assertNotEquals(RecentEmojiList.of("A", "B"), RecentEmojiList.of("B", "A"))
    }
}
