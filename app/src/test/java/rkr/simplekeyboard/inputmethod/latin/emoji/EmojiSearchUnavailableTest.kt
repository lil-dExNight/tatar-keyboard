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
import org.junit.Test

/**
 * A gesture that does nothing must say so.
 *
 * The search cell is a button drawn inside the emoji panel's tab row. It is drawn whether or not
 * the search index can be read, and the index is read at most once per process —
 * [EmojiSearchIndex.EMPTY] means "loaded and unusable", and that verdict is never retried. So every
 * path that ends without a search open ends with a cell that stays on the screen and does nothing
 * FOR THE REST OF THE PROCESS, with no message of any kind. This pins the three of them.
 */
class EmojiSearchUnavailableTest {

    private class RecordingSurface : EmojiSurface {
        var searchesOpened = 0
        var toldUnavailable = 0

        override fun showPanel(snapshot: EmojiSetSnapshot) {}

        override fun showEmojiSearch(index: EmojiSearchIndex) {
            searchesOpened++
        }

        override fun onEmojiSearchUnavailable() {
            toldUnavailable++
        }
    }

    private class DirectExecutor : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private fun controller(
        surface: EmojiSurface,
        searchSource: () -> EmojiSearchIndexSource?,
    ) = EmojiPanelController(
        surface,
        EmojiUiPoster { it.run() },
        { DirectExecutor() },
        { EmojiSnapshotSource { EmojiSet.parse("#smileys\n😀") } },
        { null },
        searchSource,
    )

    /** The index file is there but nothing usable comes out of it. */
    @Test
    fun anIndexThatLoadsEmptyIsAnsweredRatherThanIgnored() {
        val surface = RecordingSurface()
        val panel = controller(surface) { EmojiSearchIndexSource { EmojiSearchIndex.EMPTY } }
        panel.onSearchRequested()
        assertEquals("no search was opened", 0, surface.searchesOpened)
        assertEquals("the pill was tapped and nothing was said", 1, surface.toldUnavailable)
    }

    /** Reading it throws — a truncated asset, no memory, anything. */
    @Test
    fun anIndexThatCannotBeReadIsAnsweredRatherThanIgnored() {
        val surface = RecordingSurface()
        val panel = controller(surface) {
            EmojiSearchIndexSource { throw IllegalStateException("unreadable") }
        }
        panel.onSearchRequested()
        assertEquals(0, surface.searchesOpened)
        assertEquals("the pill was tapped and nothing was said", 1, surface.toldUnavailable)
    }

    /** There is no source at all to read from. */
    @Test
    fun noIndexSourceIsAnsweredRatherThanIgnored() {
        val surface = RecordingSurface()
        val panel = controller(surface) { null }
        panel.onSearchRequested()
        assertEquals(0, surface.searchesOpened)
        assertEquals("the pill was tapped and nothing was said", 1, surface.toldUnavailable)
    }

    /**
     * The verdict is cached, so the SECOND tap takes the early-return path at the top of
     * `onSearchRequested` — a different branch, and the one a real finger actually reaches, since
     * nobody taps a dead button only once.
     */
    @Test
    fun everySubsequentTapIsAnsweredToo() {
        val surface = RecordingSurface()
        val panel = controller(surface) { EmojiSearchIndexSource { EmojiSearchIndex.EMPTY } }
        panel.onSearchRequested()
        panel.onSearchRequested()
        panel.onSearchRequested()
        assertEquals(0, surface.searchesOpened)
        assertEquals("a dead button was tapped three times", 3, surface.toldUnavailable)
    }

    /** The working case must not start answering "unavailable". */
    @Test
    fun aUsableIndexStillOpensTheSearchAndSaysNothing() {
        val surface = RecordingSurface()
        val index = EmojiSearchIndex.parse("\uD83D\uDE00\tgrinning face\tsmile grin\n")
        val panel = controller(surface) { EmojiSearchIndexSource { index } }
        panel.onSearchRequested()
        assertEquals(1, surface.searchesOpened)
        assertEquals(0, surface.toldUnavailable)
    }
}
