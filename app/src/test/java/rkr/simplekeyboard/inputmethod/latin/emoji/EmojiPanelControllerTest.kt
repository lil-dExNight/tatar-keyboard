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

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiPanelControllerTest {

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeSurface : EmojiSurface {
        var showCount = 0
        var lastSnapshot: EmojiSetSnapshot? = null

        override fun showPanel(snapshot: EmojiSetSnapshot) {
            showCount++
            lastSnapshot = snapshot
        }
    }

    private class CountingSource(private val snapshot: EmojiSetSnapshot) : EmojiSnapshotSource {
        var buildCount = 0

        override fun build(): EmojiSetSnapshot {
            buildCount++
            return snapshot
        }
    }

    private class RecordingList(private val values: List<String>) : AbstractList<String>() {
        var readCount = 0
        override val size: Int get() = values.size

        override fun get(index: Int): String {
            readCount++
            return values[index]
        }
    }

    /** Queues submitted work so a test can drive the PREPARING window explicitly. */
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

    private val nonEmpty = EmojiSet.parse("#smileys\n\uD83D\uDE00\n\uD83D\uDE01\n\uD83D\uDE02")

    private fun controllerWith(
        surface: FakeSurface,
        source: EmojiSnapshotSource,
        executor: ManualExecutor,
    ): EmojiPanelController = EmojiPanelController(
        surface,
        EmojiUiPoster { it.run() },
        { executor },
        { source },
    )

    // --- Preparation is one-per-process --------------------------------------------------------

    @Test
    fun doublePressStartsExactlyOnePreparationAndShowsOnce() {
        val surface = FakeSurface()
        val source = CountingSource(nonEmpty)
        val executor = ManualExecutor()
        val controller = controllerWith(surface, source, executor)

        controller.onEmojiKeyPressed()
        controller.onEmojiKeyPressed()

        // A second press during PREPARING does not queue a second preparation.
        assertEquals(1, executor.queued)
        executor.runAll()

        assertEquals(1, source.buildCount)
        assertEquals(1, surface.showCount)
        assertEquals(EmojiPanelPreparation.READY, controller.preparationState())
    }

    @Test
    fun readySnapshotShowsImmediatelyWithoutRepreparing() {
        val surface = FakeSurface()
        val source = CountingSource(nonEmpty)
        val executor = ManualExecutor()
        val controller = controllerWith(surface, source, executor)

        controller.onEmojiKeyPressed()
        executor.runAll()
        assertEquals(1, surface.showCount)

        controller.onEmojiKeyPressed()
        // No new preparation task is queued once READY.
        assertEquals(0, executor.queued)
        assertEquals(1, source.buildCount)
        assertEquals(2, surface.showCount)
    }

    @Test
    fun availabilityIsBuiltBeforeTheResultReachesTheUiPoster() {
        val entries = RecordingList(listOf("A", "B"))
        val snapshot = EmojiSetSnapshot(listOf("smileys"), listOf(entries))
        val executor = ManualExecutor()
        val postedToUi = ArrayDeque<Runnable>()
        val controller = EmojiPanelController(
            FakeSurface(),
            EmojiUiPoster { postedToUi.addLast(it) },
            { executor },
            { CountingSource(snapshot) },
        )

        controller.onEmojiKeyPressed()
        executor.runAll()

        val readsAfterWorker = entries.readCount
        assertTrue("the worker must enumerate every emoji", readsAfterWorker > 0)
        assertEquals(1, postedToUi.size)
        postedToUi.removeFirst().run()
        assertEquals("the UI callback must only publish the prepared set",
            readsAfterWorker, entries.readCount)
    }

    // --- The single latest-only deferred show --------------------------------------------------

    @Test
    fun deferredShowIsDroppedOnEditorSessionChange() {
        val surface = FakeSurface()
        val executor = ManualExecutor()
        val controller = controllerWith(surface, CountingSource(nonEmpty), executor)

        controller.onEmojiKeyPressed()
        controller.onEditorSessionChanged()
        executor.runAll()

        assertEquals(0, surface.showCount)
        assertEquals(EmojiPanelPreparation.READY, controller.preparationState())
    }

    @Test
    fun deferredShowIsDroppedOnFinishInputView() {
        val surface = FakeSurface()
        val executor = ManualExecutor()
        val controller = controllerWith(surface, CountingSource(nonEmpty), executor)

        controller.onEmojiKeyPressed()
        controller.onFinishInputView()
        executor.runAll()

        assertEquals(0, surface.showCount)
    }

    @Test
    fun deferredShowIsDroppedOnInputViewRecreation() {
        val surface = FakeSurface()
        val executor = ManualExecutor()
        val controller = controllerWith(surface, CountingSource(nonEmpty), executor)

        controller.onEmojiKeyPressed()
        controller.onInputViewRecreated()
        executor.runAll()

        assertEquals(0, surface.showCount)
    }

    @Test
    fun anotherPressReArmsTheDeferredShowWithoutASecondPreparation() {
        val surface = FakeSurface()
        val source = CountingSource(nonEmpty)
        val executor = ManualExecutor()
        val controller = controllerWith(surface, source, executor)

        controller.onEmojiKeyPressed()
        controller.onEditorSessionChanged() // drops the pending show
        controller.onEmojiKeyPressed() // re-arms it, still one preparation
        executor.runAll()

        assertEquals(1, source.buildCount)
        assertEquals(1, surface.showCount)
    }

    // --- Fail-closed: 0 entries or failed preparation ------------------------------------------

    @Test
    fun emptySnapshotMakesTheKeyANoOpForTheRestOfTheProcess() {
        val surface = FakeSurface()
        val source = CountingSource(EmojiSet.parse("")) // no categories -> empty
        val executor = ManualExecutor()
        val controller = controllerWith(surface, source, executor)

        assertTrue(controller.onEmojiKeyPressed())
        executor.runAll()

        assertEquals(EmojiPanelPreparation.UNAVAILABLE, controller.preparationState())
        assertEquals(0, surface.showCount)

        // Every later press is a no-op with no further preparation.
        assertFalse(controller.onEmojiKeyPressed())
        assertFalse(controller.onEmojiKeyPressed())
        assertEquals(1, source.buildCount)
        assertEquals(0, surface.showCount)
    }

    @Test
    fun aNullSnapshotSourceFailsClosedToUnavailable() {
        val surface = FakeSurface()
        val controller = EmojiPanelController(
            surface,
            EmojiUiPoster { it.run() },
            { ManualExecutor() },
            { null },
        )

        assertFalse(controller.onEmojiKeyPressed())
        assertEquals(EmojiPanelPreparation.UNAVAILABLE, controller.preparationState())
        assertEquals(0, surface.showCount)
    }

    @Test
    fun destroyedControllerNeverShowsOrPrepares() {
        val surface = FakeSurface()
        val source = CountingSource(nonEmpty)
        val executor = ManualExecutor()
        val controller = controllerWith(surface, source, executor)

        controller.onDestroy()
        assertFalse(controller.onEmojiKeyPressed())
        executor.runAll()

        assertEquals(0, source.buildCount)
        assertEquals(0, surface.showCount)
    }

    @Test
    fun aLatePreparationResultAfterDestroyIsIgnored() {
        val surface = FakeSurface()
        val source = CountingSource(nonEmpty)
        val executor = ManualExecutor()
        val postedToUi = ArrayDeque<Runnable>()
        val controller = EmojiPanelController(
            surface,
            EmojiUiPoster { postedToUi.addLast(it) }, // defer the result marshalling
            { executor },
            { source },
        )

        controller.onEmojiKeyPressed() // queues preparation
        executor.runAll() // build runs and posts onPrepared onto the (deferred) UI poster
        controller.onDestroy() // tears down before the posted result is applied
        while (postedToUi.isNotEmpty()) postedToUi.removeFirst().run() // onPrepared must no-op

        assertEquals(0, surface.showCount)
    }
}
