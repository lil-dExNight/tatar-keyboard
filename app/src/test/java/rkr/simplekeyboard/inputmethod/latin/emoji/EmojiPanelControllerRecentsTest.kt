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
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controller ↔ recent-store wiring: a tap records through the background executor (never on
 * the caller thread), the write happens on hide, and a non-empty list shows as the first category.
 */
class EmojiPanelControllerRecentsTest {

    private class FakeSurface : EmojiSurface {
        val shown = ArrayList<EmojiSetSnapshot>()
        override fun showPanel(snapshot: EmojiSetSnapshot) {
            shown.add(snapshot)
        }
    }

    private class ManualExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        val queued: Int get() = tasks.size
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }
        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
        override fun shutdown() {
            stopped = true
        }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            val remaining = tasks.toMutableList()
            tasks.clear()
            return remaining
        }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class FakeFileOps : RecentEmojiFileOps {
        var content: String? = null
        var writeCount = 0
        var failWrites = false
        override fun read(file: File): String? = content
        override fun writeAtomic(file: File, content: String) {
            writeCount++
            if (!failWrites) this.content = content
        }
        override fun isAbsentOrEmpty(file: File): Boolean = content.isNullOrEmpty()
    }

    private val grin = "\uD83D\uDE00"
    private val beam = "\uD83D\uDE01"
    private val base = EmojiSet.parse("#smileys\n$grin\n$beam\n\uD83D\uDE02")
    private val openGate = RecentEmojiGate {
        RecentEmojiGateState(
            shouldShowSuggestions = true,
            userUnlocked = true,
            noPersonalizedLearning = false,
            keyguardLocked = false,
            incognito = false,
        )
    }

    private fun controllerWith(
        surface: EmojiSurface,
        executor: ManualExecutor?,
        ops: FakeFileOps,
    ): EmojiPanelController {
        val store = RecentEmojiStore(RecentEmojiFileProvider { File("recent_emoji_controller_test") }, ops, openGate)
        return EmojiPanelController(
            surface,
            EmojiUiPoster { it.run() },
            { executor },
            { EmojiSnapshotSource { base } },
            { store },
        )
    }

    @Test
    fun aTapRecordsOnTheBackgroundExecutorAndTheWriteHappensOnHide() {
        val surface = FakeSurface()
        val executor = ManualExecutor()
        val ops = FakeFileOps()
        val controller = controllerWith(surface, executor, ops)

        controller.onEmojiKeyPressed()
        executor.runAll()
        assertEquals(1, surface.shown.size)

        controller.onEmojiInserted(grin)
        // The record is queued, not run on the calling (UI) thread: nothing written yet.
        assertEquals(1, executor.queued)
        assertEquals(0, ops.writeCount)
        executor.runAll()
        assertEquals(0, ops.writeCount) // recording alone does not persist

        controller.onPanelHidden()
        executor.runAll()
        // Persisted exactly once on hide, and it carries the inserted emoji.
        assertEquals(1, ops.writeCount)
        assertTrue(ops.content!!.contains(grin))
    }

    @Test
    fun clearingAnswersOnTheExecutorWithTheStoresResult() {
        val surface = FakeSurface()
        val executor = ManualExecutor()
        val ops = FakeFileOps().apply { content = grin }
        val controller = controllerWith(surface, executor, ops)
        val answers = ArrayList<Boolean>()

        controller.requestClearRecents { answers.add(it) }
        // Queued like every store operation: no answer on the calling thread.
        assertTrue(answers.isEmpty())
        executor.runAll()

        assertEquals(listOf(true), answers)
        assertEquals("", ops.content)
    }

    @Test
    fun aClearThatLeavesTheMediumInPlaceAnswersFalse() {
        val executor = ManualExecutor()
        val ops = FakeFileOps().apply {
            content = grin
            failWrites = true
        }
        val controller = controllerWith(FakeSurface(), executor, ops)
        val answers = ArrayList<Boolean>()

        controller.requestClearRecents { answers.add(it) }
        executor.runAll()

        assertEquals(listOf(false), answers)
    }

    @Test
    fun aClearThatCannotBeQueuedAnswersFalseAtOnce() {
        val ops = FakeFileOps().apply { content = grin }
        val controller = controllerWith(FakeSurface(), null, ops)
        val answers = ArrayList<Boolean>()

        controller.requestClearRecents { answers.add(it) }

        assertEquals(listOf(false), answers)
        assertEquals(grin, ops.content)
    }

    @Test
    fun aClearDroppedByShutdownAnswersFalseOnce() {
        val executor = ManualExecutor()
        val ops = FakeFileOps().apply { content = grin }
        val controller = controllerWith(FakeSurface(), executor, ops)
        val answers = ArrayList<Boolean>()

        controller.requestClearRecents { answers.add(it) }
        controller.onDestroy()
        executor.runAll()

        assertEquals(listOf(false), answers)
        assertEquals(grin, ops.content)
    }

    @Test
    fun aClearWhoseRefreshThrowsStillAnswers() {
        val executor = ManualExecutor()
        val ops = FakeFileOps().apply { content = grin }
        val store = RecentEmojiStore(RecentEmojiFileProvider { File("recent_emoji_controller_test") }, ops, openGate)
        var posterGone = false
        val controller = EmojiPanelController(
            FakeSurface(),
            EmojiUiPoster { if (posterGone) throw IllegalStateException("poster gone") else it.run() },
            { executor },
            { EmojiSnapshotSource { base } },
            { store },
        )
        controller.onEmojiKeyPressed()
        executor.runAll()
        posterGone = true
        val answers = ArrayList<Boolean>()

        controller.requestClearRecents { answers.add(it) }
        executor.runAll()

        assertEquals(listOf(true), answers)
    }

    @Test
    fun aNonEmptyRecentsListShowsAsTheFirstCategory() {
        val surface = FakeSurface()
        val executor = ManualExecutor()
        val ops = FakeFileOps()
        val controller = controllerWith(surface, executor, ops)

        controller.onEmojiKeyPressed()
        executor.runAll()

        controller.onEmojiInserted(beam)
        executor.runAll()

        // Re-open the (already READY) panel: the recents now precede the base categories.
        controller.onEmojiKeyPressed()
        executor.runAll()

        val last = surface.shown.last()
        assertEquals(EmojiDisplaySnapshots.RECENT_CATEGORY_NAME, last.categoryName(0))
        assertEquals(listOf(beam), last.entriesOf(0))
        assertEquals(base.categoryCount + 1, last.categoryCount)
    }

    // --- Removing one entry --------------------------------------------------------------------

    private class RecordingSurface : EmojiSurface {
        val shown = ArrayList<EmojiSetSnapshot>()
        val refreshed = ArrayList<EmojiSetSnapshot>()
        var spokenNamesBound = 0
        var boundLanguage: String? = null

        override fun showPanel(snapshot: EmojiSetSnapshot) {
            shown.add(snapshot)
        }

        override fun refreshAfterRecentRemoved(snapshot: EmojiSetSnapshot) {
            refreshed.add(snapshot)
        }

        override fun bindSpokenNames(names: EmojiSearchIndex, language: String?) {
            spokenNamesBound++
            boundLanguage = language
        }
    }

    @Test
    fun onRecentRemovedDropsTheEntryAndRefreshesTheShownPanel() {
        val surface = RecordingSurface()
        val executor = ManualExecutor()
        val ops = FakeFileOps()
        val controller = controllerWith(surface, executor, ops)

        controller.onEmojiKeyPressed()
        executor.runAll()
        controller.onEmojiInserted(grin)
        controller.onEmojiInserted(beam)
        executor.runAll()

        controller.onRecentRemoved(grin)
        executor.runAll()

        // The shown panel is refreshed through the same snapshot path, minus the removed entry.
        assertEquals(1, surface.refreshed.size)
        val refreshed = surface.refreshed.last()
        assertEquals(EmojiDisplaySnapshots.RECENT_CATEGORY_NAME, refreshed.categoryName(0))
        assertEquals(listOf(beam), refreshed.entriesOf(0))
        // The removal is persisted by the next hide's flush, like every other change.
        controller.onPanelHidden()
        executor.runAll()
        assertFalse(ops.content!!.contains(grin))
        assertTrue(ops.content!!.contains(beam))
    }

    @Test
    fun onRecentRemovedWithoutAStoreOrPanelIsANoOp() {
        // No recents store: nothing to remove, no refresh.
        val surface = RecordingSurface()
        val controller = EmojiPanelController(
            surface,
            EmojiUiPoster { it.run() },
            { ManualExecutor() },
            { EmojiSnapshotSource { base } },
        )
        controller.onRecentRemoved(grin)
        assertEquals(0, surface.refreshed.size)
    }

    @Test
    fun removingTheLastRecentRefreshesToTheBaseSnapshotWithoutARecentsCategory() {
        val surface = RecordingSurface()
        val executor = ManualExecutor()
        val controller = controllerWith(surface, executor, FakeFileOps())

        controller.onEmojiKeyPressed()
        executor.runAll()
        controller.onEmojiInserted(grin)
        executor.runAll()

        controller.onRecentRemoved(grin)
        executor.runAll()

        val refreshed = surface.refreshed.last()
        // An empty recents list shows the base snapshot unchanged: no Recent category.
        assertEquals(base.categoryCount, refreshed.categoryCount)
        assertEquals("smileys", refreshed.categoryName(0))
    }

    // --- The press needs no arming callback ------------------------------------------------------

    @Test
    fun thePressApiCarriesNoArmingCallbackAndEveryPressIsAnsweredAlike() {
        // The press feedback is the ordinary key-down path's, identical for every press, so the
        // press API carries no feedback callback: the panel show adds none of its own.
        val pressShapes = EmojiPanelController::class.java.declaredMethods
            .filter { it.name == "onEmojiKeyPressed" }
        assertEquals(1, pressShapes.size)
        assertEquals(0, pressShapes.single().parameterCount)

        val surface = FakeSurface()
        val executor = ManualExecutor()
        val controller = controllerWith(surface, executor, FakeFileOps())

        // First press (NOT_PREPARED): the deferred show is armed and the one preparation queued.
        assertTrue(controller.onEmojiKeyPressed())
        assertEquals(1, executor.queued)
        // A press while PREPARING re-arms the same show; no second preparation is queued.
        assertTrue(controller.onEmojiKeyPressed())
        assertEquals(1, executor.queued)
        assertEquals(0, surface.shown.size)

        executor.runAll()
        assertEquals(1, surface.shown.size)

        // READY from here on: the press shows the panel again with no new preparation.
        assertTrue(controller.onEmojiKeyPressed())
        executor.runAll()
        assertEquals(2, surface.shown.size)
    }

    // --- Spoken cell names ----------------------------------------------------------------------

    @Test
    fun publishingThePanelBindsTheSpokenNamesOnceTheIndexLoads() {
        val surface = RecordingSurface()
        val executor = ManualExecutor()
        val controller = EmojiPanelController(
            surface,
            EmojiUiPoster { it.run() },
            { executor },
            { EmojiSnapshotSource { base } },
            { null },
            { EmojiSearchIndexSource { index } },
        )

        controller.setSpokenNameLanguage("tt")
        controller.onEmojiKeyPressed()
        // The panel publish queues two tasks: the recents read and the spoken-name index load.
        executor.runAll()

        assertEquals(1, surface.spokenNamesBound)
        assertEquals("tt", surface.boundLanguage)
    }

    @Test
    fun setSpokenNameLanguageRebindsTheLoadedIndexUnderTheNewLanguage() {
        val surface = RecordingSurface()
        val controller = EmojiPanelController(
            surface,
            EmojiUiPoster { it.run() },
            { ManualExecutor() },
            { EmojiSnapshotSource { base } },
            { null },
            { EmojiSearchIndexSource { index } },
        )

        controller.setSpokenNameLanguage("ru")
        // Not loaded yet: nothing is bound, the sequence fallback speaks.
        assertEquals(0, surface.spokenNamesBound)

        controller.onEmojiKeyPressed()
        assertEquals(0, surface.spokenNamesBound)
        controller.setSpokenNameLanguage("tt")
        assertEquals(0, surface.spokenNamesBound)
    }

    private val index = EmojiSearchIndex.parse(
        "$grin\tулыбка\tулыбка smile\tелмаю\n$beam\tулыбка лучами\tулыбка smile beam\n",
    )
}
