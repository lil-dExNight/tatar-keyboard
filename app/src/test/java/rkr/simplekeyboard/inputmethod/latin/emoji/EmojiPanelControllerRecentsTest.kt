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
        override fun read(file: File): String? = content
        override fun writeAtomic(file: File, content: String) {
            writeCount++
            this.content = content
        }
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
        surface: FakeSurface,
        executor: ManualExecutor,
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
}
