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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display snapshot composition: the Recent tab is first and exists only when the list is
 * non-empty, which is a consequence of the empty list rather than a separate code branch.
 */
class EmojiDisplaySnapshotsTest {

    private val base = EmojiSet.parse("#smileys\nA\nB\n#food\nC")

    @Test
    fun availableSequencesIsTheUnionOfEveryCategory() {
        assertEquals(setOf("A", "B", "C"), EmojiDisplaySnapshots.availableSequences(base))
    }

    @Test
    fun emptyRecentsReturnTheBaseSnapshotUnchanged() {
        // No Recent tab exists for an empty list: the same instance comes back.
        assertSame(base, EmojiDisplaySnapshots.withRecents(base, emptyList()))
    }

    @Test
    fun nonEmptyRecentsBecomeTheFirstCategory() {
        val withRecents = EmojiDisplaySnapshots.withRecents(base, listOf("B", "A"))

        // Exactly one extra category, added at the front.
        assertEquals(base.categoryCount + 1, withRecents.categoryCount)
        assertEquals(EmojiDisplaySnapshots.RECENT_CATEGORY_NAME, withRecents.categoryName(0))
        assertEquals(listOf("B", "A"), withRecents.entriesOf(0))

        // The original categories follow in order, unchanged.
        assertEquals(base.categoryName(0), withRecents.categoryName(1))
        assertEquals(base.entriesOf(0), withRecents.entriesOf(1))
        assertEquals(base.categoryName(1), withRecents.categoryName(2))
        assertEquals(base.entriesOf(1), withRecents.entriesOf(2))
    }

    @Test
    fun recentTabPreservesMruOrder() {
        val withRecents = EmojiDisplaySnapshots.withRecents(base, listOf("C", "A", "B"))
        assertEquals("C", withRecents.entryAt(0, 0))
        assertEquals("A", withRecents.entryAt(0, 1))
        assertEquals("B", withRecents.entryAt(0, 2))
        assertTrue(withRecents.categoryName(0) == EmojiDisplaySnapshots.RECENT_CATEGORY_NAME)
    }
}
