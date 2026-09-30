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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recent-emoji store: the three-factor gate, the "write once per hide, only when changed"
 * rule, the "erased never resurrects" rule and the fail-closed reads — all on the plain JVM through
 * the injected file, gate and medium seams.
 */
class RecentEmojiStoreTest {

    /** An in-memory medium that counts reads and writes and can be told to fail a read. */
    private class FakeFileOps : RecentEmojiFileOps {
        var content: String? = null
        var readCount = 0
        var writeCount = 0
        var throwOnRead = false

        override fun read(file: File): String? {
            readCount++
            if (throwOnRead) throw RuntimeException("simulated unreadable medium")
            return content
        }

        override fun writeAtomic(file: File, content: String) {
            writeCount++
            this.content = content
        }
    }

    private class FakeGate(var state: RecentEmojiGateState) : RecentEmojiGate {
        override fun current(): RecentEmojiGateState = state
    }

    private val open = RecentEmojiGateState(
        shouldShowSuggestions = true,
        userUnlocked = true,
        noPersonalizedLearning = false,
        keyguardLocked = false,
        incognito = false,
    )

    private val dummyFile = File("recent_emoji_store_test")

    private fun storeWith(ops: FakeFileOps, gate: FakeGate): RecentEmojiStore =
        RecentEmojiStore(RecentEmojiFileProvider { dummyFile }, ops, gate)

    // --- The gate factors: each one alone blocks recording; all open records -------------------

    @Test
    fun allGatesOpenAllowsRecording() {
        val ops = FakeFileOps()
        val store = storeWith(ops, FakeGate(open))
        store.recordUse("A", setOf("A"))
        assertEquals(listOf("A"), store.currentRecents(setOf("A")))
        assertTrue(store.isDirty())
    }

    @Test
    fun shouldShowSuggestionsFalseBlocksRecording() {
        val ops = FakeFileOps()
        val store = storeWith(ops, FakeGate(open.copy(shouldShowSuggestions = false)))
        store.recordUse("A", setOf("A"))
        assertTrue(store.currentRecents(setOf("A")).isEmpty())
        assertFalse(store.isDirty())
    }

    @Test
    fun lockedDeviceBlocksRecordingAndNeverTouchesThePath() {
        val ops = FakeFileOps().apply { content = "A" }
        val store = storeWith(ops, FakeGate(open.copy(userUnlocked = false)))
        store.recordUse("A", setOf("A"))
        assertTrue(store.currentRecents(setOf("A")).isEmpty())
        assertFalse(store.isDirty())
        // Before the first unlock the credential-protected path does not exist: never read it.
        assertEquals(0, ops.readCount)
    }

    @Test
    fun noPersonalizedLearningBlocksRecording() {
        val ops = FakeFileOps()
        val store = storeWith(ops, FakeGate(open.copy(noPersonalizedLearning = true)))
        store.recordUse("A", setOf("A"))
        assertTrue(store.currentRecents(setOf("A")).isEmpty())
        assertFalse(store.isDirty())
    }

    @Test
    fun aLockedKeyguardHidesTheListAndBlocksRecording() {
        val ops = FakeFileOps().apply { content = "A" }
        val gate = FakeGate(open)
        val store = storeWith(ops, gate)
        assertEquals(listOf("A"), store.currentRecents(setOf("A", "B")))
        gate.state = open.copy(keyguardLocked = true)
        store.recordUse("B", setOf("A", "B"))
        assertFalse(store.isDirty())
        assertTrue(store.currentRecents(setOf("A", "B")).isEmpty())
        // Unlocking shows the same list again; nothing was dropped or added.
        gate.state = open
        assertEquals(listOf("A"), store.currentRecents(setOf("A", "B")))
    }

    @Test
    fun aLockedKeyguardShowsNothingEvenBeforeTheFirstRead() {
        val ops = FakeFileOps().apply { content = "A" }
        val store = storeWith(ops, FakeGate(open.copy(keyguardLocked = true)))
        assertTrue(store.currentRecents(setOf("A")).isEmpty())
        assertEquals(0, ops.readCount)
    }

    @Test
    fun incognitoBlocksRecordingButKeepsTheList() {
        val ops = FakeFileOps().apply { content = "A" }
        val store = storeWith(ops, FakeGate(open.copy(incognito = true)))
        store.recordUse("B", setOf("A", "B"))
        assertFalse(store.isDirty())
        assertEquals(listOf("A"), store.currentRecents(setOf("A", "B")))
        store.flushOnHide()
        assertEquals(0, ops.writeCount)
    }

    @Test
    fun theBlockedStateClosesEveryFactor() {
        val blocked = RecentEmojiGateState.BLOCKED
        assertFalse(blocked.allowsRecording)
        assertFalse(blocked.allowsDisplay)
        assertFalse(blocked.allowsPathAccess)
    }

    @Test
    fun gateIsCheckedBeforeInMemoryMutationSoUnlockingLaterReleasesNothing() {
        val ops = FakeFileOps()
        val gate = FakeGate(open.copy(noPersonalizedLearning = true))
        val store = storeWith(ops, gate)
        // Insert while the gate forbids it: nothing must be held in memory to flush later.
        store.recordUse("A", setOf("A"))
        assertFalse(store.isDirty())
        // Now the field allows learning again — the earlier insert must not resurface.
        gate.state = open
        store.flushOnHide()
        assertEquals(0, ops.writeCount)
        assertTrue(store.currentRecents(setOf("A")).isEmpty())
    }

    // --- Write once per hide, only when changed ------------------------------------------------

    @Test
    fun writeHappensAtMostOncePerHideAndOnlyWhenTheListChanged() {
        val ops = FakeFileOps()
        val store = storeWith(ops, FakeGate(open))
        val available = setOf("A", "B")

        store.recordUse("A", available)
        store.flushOnHide()
        assertEquals(1, ops.writeCount)
        assertEquals(1, store.saveCount)

        // A second hide with no change writes nothing.
        store.flushOnHide()
        assertEquals(1, ops.writeCount)
        assertEquals(1, store.saveCount)

        // Re-using the already-front entry does not change the list, so the next hide is silent.
        store.recordUse("A", available)
        store.flushOnHide()
        assertEquals(1, ops.writeCount)

        // A real change writes exactly once more.
        store.recordUse("B", available)
        store.flushOnHide()
        assertEquals(2, ops.writeCount)
        assertEquals(2, store.saveCount)
    }

    // --- Erased never resurrects ---------------------------------------------------------------

    @Test
    fun clearedRecentsDoNotResurrectOnTheNextHide() {
        val ops = FakeFileOps()
        val store = storeWith(ops, FakeGate(open))
        val available = setOf("A", "B")
        store.recordUse("A", available)
        store.recordUse("B", available)
        assertTrue(store.isDirty())

        store.clear()
        assertEquals("", ops.content)
        assertFalse(store.isDirty())
        val writesAfterClear = ops.writeCount

        // The next hide must not write the erased entries back from memory.
        store.flushOnHide()
        assertEquals(writesAfterClear, ops.writeCount)
        assertTrue(store.currentRecents(available).isEmpty())
    }

    // --- Clear on empty / absent / locked medium is a no-op without exception ------------------

    @Test
    fun clearOnAbsentMediumDoesNotThrow() {
        val ops = FakeFileOps() // content == null: absent medium
        val store = storeWith(ops, FakeGate(open))
        store.clear() // must not throw
        assertTrue(store.currentRecents(setOf("A")).isEmpty())
    }

    @Test
    fun clearWhileLockedTouchesNothing() {
        val ops = FakeFileOps().apply { content = "A" }
        val store = storeWith(ops, FakeGate(open.copy(userUnlocked = false)))
        store.clear()
        // Locked device: the path is never touched, so no write happens.
        assertEquals(0, ops.writeCount)
        assertFalse(store.isDirty())
    }

    // --- Fail-closed reads ---------------------------------------------------------------------

    @Test
    fun unreadableMediumYieldsEmptyWithoutThrowing() {
        val ops = FakeFileOps().apply { throwOnRead = true }
        val store = storeWith(ops, FakeGate(open))
        assertTrue(store.currentRecents(setOf("A")).isEmpty())
    }

    @Test
    fun corruptedMediumYieldsEmptyWithoutThrowing() {
        val ops = FakeFileOps().apply { content = "A${RecentEmojiList.SEPARATOR}A" } // duplicate
        val store = storeWith(ops, FakeGate(open))
        assertTrue(store.currentRecents(setOf("A")).isEmpty())
    }

    @Test
    fun entriesAbsentFromTheSnapshotAreDroppedOnRead() {
        val ops = FakeFileOps().apply { content = "A${RecentEmojiList.SEPARATOR}B" }
        val store = storeWith(ops, FakeGate(open))
        // Only A survives in the current snapshot; B is dropped.
        assertEquals(listOf("A"), store.currentRecents(setOf("A")))
    }
}
