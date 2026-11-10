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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Recent-emoji store: the three-factor gate, the "write once per hide, only when changed"
 * rule, the "erased never resurrects" rule and the fail-closed reads — all on the plain JVM through
 * the injected file, gate and medium seams.
 */
class RecentEmojiStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** An in-memory medium that counts reads and writes and can be told to fail a read or write. */
    private class FakeFileOps : RecentEmojiFileOps {
        var content: String? = null
        var readCount = 0
        var writeCount = 0
        var tempRemovalCount = 0
        var throwOnRead = false
        var throwOnWrite = false

        /** A write that leaves the previous medium in place, like a failed atomic replace. */
        var failWrites = false

        /** A post-rename directory-sync failure: the new content is installed, yet not durable. */
        var writeReturnsFalseAfterInstalling = false

        /** A temp leftover an interrupted write may have stranded; removal can be refused too. */
        var tempRemains = false
        var failTempRemoval = false

        override fun read(file: File): String? {
            readCount++
            if (throwOnRead) throw RuntimeException("simulated unreadable medium")
            return content
        }

        override fun writeAtomic(file: File, content: String): Boolean {
            writeCount++
            if (throwOnWrite) throw RuntimeException("simulated process death during the write")
            if (writeReturnsFalseAfterInstalling) {
                this.content = content
                return false
            }
            if (failWrites) return false
            this.content = content
            return true
        }

        override fun isAbsentOrEmpty(file: File): Boolean = content.isNullOrEmpty()

        override fun removeTemp(file: File): Boolean {
            tempRemovalCount++
            if (!failTempRemoval) tempRemains = false
            return !tempRemains
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

    // --- Only durable writes clear the dirty flag and count a save ----------------------------

    @Test
    fun aRefusedWriteStaysDirtyAndRetriesOnTheNextHide() {
        val ops = FakeFileOps().apply {
            content = "A"
            failWrites = true
        }
        val store = storeWith(ops, FakeGate(open))
        store.recordUse("B", setOf("A", "B"))
        store.flushOnHide()

        assertTrue(store.isDirty())
        assertEquals(0, store.saveCount)
        // The previous medium is untouched by the failed write.
        assertEquals("A", ops.content)

        ops.failWrites = false
        store.flushOnHide()
        assertFalse(store.isDirty())
        assertEquals(1, store.saveCount)
        assertEquals(2, ops.writeCount)
        assertEquals("B${RecentEmojiList.SEPARATOR}A", ops.content)
    }

    @Test
    fun aWriteInterruptedByProcessDeathStaysDirtyAndCountsNoSave() {
        val ops = FakeFileOps().apply { throwOnWrite = true }
        val store = storeWith(ops, FakeGate(open))
        store.recordUse("A", setOf("A"))

        store.flushOnHide() // must not throw
        assertTrue(store.isDirty())
        assertEquals(0, store.saveCount)

        ops.throwOnWrite = false
        store.flushOnHide()
        assertFalse(store.isDirty())
        assertEquals(1, store.saveCount)
        assertEquals("A", ops.content)
    }

    @Test
    fun aPostRenameDurabilityFailureStaysDirtyAlthoughTheNewContentIsInstalled() {
        val ops = FakeFileOps().apply { writeReturnsFalseAfterInstalling = true }
        val store = storeWith(ops, FakeGate(open))
        store.recordUse("A", setOf("A"))
        store.flushOnHide()

        // The rename installed the new content, but the directory sync failed: no save counts
        // and the change stays pending even though the medium already shows it.
        assertEquals("A", ops.content)
        assertTrue(store.isDirty())
        assertEquals(0, store.saveCount)

        ops.writeReturnsFalseAfterInstalling = false
        store.flushOnHide()
        assertFalse(store.isDirty())
        assertEquals(1, store.saveCount)
        assertEquals(2, ops.writeCount)
        assertEquals("A", ops.content)
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

        assertTrue(store.clear())
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
        assertTrue(store.clear()) // must not throw; nothing is left
        assertTrue(store.currentRecents(setOf("A")).isEmpty())
    }

    @Test
    fun aFailedWriteAnswersThatTheMediumWasNotCleared() {
        val ops = FakeFileOps().apply {
            content = "A"
            failWrites = true
        }
        val store = storeWith(ops, FakeGate(open))

        assertFalse(store.clear())
        assertEquals("A", ops.content)
        // The in-memory list is still cleared, so no hide writes the old entries back.
        assertFalse(store.isDirty())
        assertTrue(store.currentRecents(setOf("A")).isEmpty())
    }

    @Test
    fun clearWhileLockedTouchesNothing() {
        val ops = FakeFileOps().apply { content = "A" }
        val store = storeWith(ops, FakeGate(open.copy(userUnlocked = false)))
        // Locked device: the path is never touched, so no write happens and nothing is confirmed.
        assertFalse(store.clear())
        assertEquals(0, ops.writeCount)
        assertEquals(0, ops.tempRemovalCount)
        assertFalse(store.isDirty())
    }

    // --- Erase also sweeps the temp an interrupted write stranded ------------------------------

    @Test
    fun clearRemovesTheTempLeftoverOfAnInterruptedWrite() {
        val ops = FakeFileOps().apply {
            content = "A"
            tempRemains = true
        }
        val store = storeWith(ops, FakeGate(open))
        assertTrue(store.clear())
        assertEquals("", ops.content)
        assertFalse(ops.tempRemains)
        assertEquals(1, ops.tempRemovalCount)
    }

    @Test
    fun clearFailsWhileTheTempLeftoverRemains() {
        val ops = FakeFileOps().apply {
            content = "A"
            tempRemains = true
            failTempRemoval = true
        }
        val store = storeWith(ops, FakeGate(open))
        // The medium itself is empty now, but the stranded temp still counts as not erased.
        assertFalse(store.clear())
        assertEquals("", ops.content)
        assertTrue(ops.tempRemains)
    }

    @Test
    fun clearSweepsTheTempEvenWhenTheMediumWriteFails() {
        val ops = FakeFileOps().apply {
            content = "A"
            failWrites = true
            tempRemains = true
        }
        val store = storeWith(ops, FakeGate(open))
        assertFalse(store.clear())
        assertEquals(1, ops.tempRemovalCount)
        assertFalse(ops.tempRemains)
        assertEquals("A", ops.content)
    }

    @Test
    fun clearFailsWhileTheErasedStateIsNotProvenDurable() {
        val ops = FakeFileOps().apply {
            content = "A"
            writeReturnsFalseAfterInstalling = true
        }
        val store = storeWith(ops, FakeGate(open))
        // The file reads empty, but the erase's rename was never directory-synced: report failure
        // rather than promise an erase a crash could undo.
        assertFalse(store.clear())
        assertEquals("", ops.content)

        ops.writeReturnsFalseAfterInstalling = false
        assertTrue(store.clear())
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

    // --- Sequences outside the drawable set ----------------------------------------------------

    @Test
    fun aSequenceAbsentFromTheSetIsNeitherRecordedNorWritten() {
        val ops = FakeFileOps().apply { content = "A" }
        val store = storeWith(ops, FakeGate(open))
        store.recordUse("T", setOf("A"))
        assertFalse(store.isDirty())
        store.flushOnHide()
        assertEquals(0, ops.writeCount)
        assertEquals("A", ops.content)
    }

    // --- Removing one entry --------------------------------------------------------------------

    @Test
    fun removeRecentDropsTheEntryAndPersistsOnTheNextHide() {
        val ops = FakeFileOps()
        val store = storeWith(ops, FakeGate(open))
        val available = setOf("A", "B", "C")
        store.recordUse("A", available)
        store.recordUse("B", available)
        store.recordUse("C", available)
        store.flushOnHide()

        store.removeRecent("B", available)
        assertEquals(listOf("C", "A"), store.currentRecents(available))
        assertTrue(store.isDirty())

        store.flushOnHide()
        assertEquals("C${RecentEmojiList.SEPARATOR}A", ops.content)
        assertFalse(store.isDirty())
    }

    @Test
    fun removeRecentOfAMissingEntryIsANoOpThatDirtiesNothing() {
        val ops = FakeFileOps()
        val store = storeWith(ops, FakeGate(open))
        store.recordUse("A", setOf("A"))
        store.flushOnHide()
        val writesBefore = ops.writeCount

        store.removeRecent("Z", setOf("A"))
        assertFalse(store.isDirty())
        store.flushOnHide()
        assertEquals(writesBefore, ops.writeCount)
        assertEquals(listOf("A"), store.currentRecents(setOf("A")))
    }

    @Test
    fun removingTheLastEntryEmptiesTheListAndTheMedium() {
        val ops = FakeFileOps()
        val store = storeWith(ops, FakeGate(open))
        store.recordUse("A", setOf("A"))
        store.removeRecent("A", setOf("A"))
        store.flushOnHide()
        assertTrue(store.currentRecents(setOf("A")).isEmpty())
        assertTrue(ops.content.isNullOrEmpty())
    }

    @Test
    fun removeRecentWhileLockedKeepsTheRemovalPendingAndNeverTouchesThePath() {
        val ops = FakeFileOps()
        val gate = FakeGate(open)
        val store = storeWith(ops, gate)
        store.recordUse("A", setOf("A"))
        store.flushOnHide()

        gate.state = open.copy(userUnlocked = false)
        store.removeRecent("A", setOf("A"))
        // The list in memory is updated, but a locked path is never written: the removal stays
        // pending, like a failed write, and the next unlocked hide lands it.
        assertTrue(store.isDirty())
        store.flushOnHide()
        assertEquals("A", ops.content)

        gate.state = open
        store.flushOnHide()
        assertTrue(ops.content.isNullOrEmpty())
    }

    @Test
    fun anEntryRecordedBeforeTheSetWasKnownIsHiddenOnceTheSetLacksIt() {
        val ops = FakeFileOps()
        val store = storeWith(ops, FakeGate(open))
        store.recordUse("T", emptySet())
        assertEquals(listOf("T"), store.currentRecents(emptySet()))
        assertTrue(store.currentRecents(setOf("A")).isEmpty())
    }

    // --- Erase-all on the real medium: the stranded temp decides the answer ---------------------

    @Test
    fun eraseAllOnTheRealMediumRemovesTheTempLeftover() {
        val file = File(temporaryFolder.root, "recent_emoji_v1")
        AtomicRecentEmojiFileOps.writeAtomic(file, "A")
        // A write the process died in the middle of: the temp holds the uninstalled replacement.
        val temp = File(temporaryFolder.root, "recent_emoji_v1.tmp")
        temp.writeText("B")
        val store = RecentEmojiStore(
            RecentEmojiFileProvider { file }, AtomicRecentEmojiFileOps, FakeGate(open))

        assertTrue(store.clear())
        assertTrue(AtomicRecentEmojiFileOps.isAbsentOrEmpty(file))
        assertFalse(temp.exists())
    }

    @Test
    fun eraseAllOnTheRealMediumFailsWhileTheTempRemains() {
        val file = File(temporaryFolder.root, "recent_emoji_v1")
        AtomicRecentEmojiFileOps.writeAtomic(file, "A")
        // A non-empty directory in the temp's place can neither be written nor deleted.
        val temp = temporaryFolder.newFolder("recent_emoji_v1.tmp")
        File(temp, "stranded").writeText("B")
        val store = RecentEmojiStore(
            RecentEmojiFileProvider { file }, AtomicRecentEmojiFileOps, FakeGate(open))

        assertFalse(store.clear())
        // The failed erase never cost the previous medium.
        assertEquals("A", AtomicRecentEmojiFileOps.read(file))
        assertTrue(temp.exists())
    }
}
