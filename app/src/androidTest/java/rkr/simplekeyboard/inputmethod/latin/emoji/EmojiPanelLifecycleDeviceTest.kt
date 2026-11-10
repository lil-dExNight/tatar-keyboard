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

import android.os.Handler
import android.os.Looper
import android.test.InstrumentationTestCase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Deferred panel publication and recents on a real main looper, executor, and Android file. */
class EmojiPanelLifecycleDeviceTest : InstrumentationTestCase() {

    private class Surface : EmojiSurface {
        val shows = AtomicInteger()
        @Volatile var last: EmojiSetSnapshot? = null
        @Volatile var shown = CountDownLatch(1)

        override fun showPanel(snapshot: EmojiSetSnapshot) {
            last = snapshot
            shows.incrementAndGet()
            shown.countDown()
        }
    }

    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var executor: ExecutorService
    private lateinit var handler: Handler
    private var controller: EmojiPanelController? = null

    override fun setUp() {
        super.setUp()
        directory = File(
            instrumentation.targetContext.noBackupFilesDir,
            "emoji-panel-device-test-${System.nanoTime()}",
        )
        assertTrue(directory.mkdirs())
        file = File(directory, "recent_emoji_v1")
        assertTrue(AtomicRecentEmojiFileOps.writeAtomic(file, "😀"))
        executor = Executors.newSingleThreadExecutor()
        handler = Handler(Looper.getMainLooper())
    }

    override fun tearDown() {
        instrumentation.runOnMainSync { controller?.onDestroy() }
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        directory.deleteRecursively()
        super.tearDown()
    }

    fun testOldEditorCannotPublishAndNewEditorReadsAndWritesRecents() {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val fileOps = object : RecentEmojiFileOps by AtomicRecentEmojiFileOps {
            override fun read(source: File): String? {
                readStarted.countDown()
                assertTrue("recents read was not released", releaseRead.await(10, TimeUnit.SECONDS))
                return AtomicRecentEmojiFileOps.read(source)
            }
        }
        val store = RecentEmojiStore(
            RecentEmojiFileProvider { file },
            fileOps,
            RecentEmojiGate {
                RecentEmojiGateState(
                    shouldShowSuggestions = true,
                    userUnlocked = true,
                    noPersonalizedLearning = false,
                    keyguardLocked = false,
                    incognito = false,
                )
            },
        )
        val surface = Surface()
        val base = EmojiSet.parse("#smileys\n😀\n😁")
        instrumentation.runOnMainSync {
            controller = EmojiPanelController(
                surface,
                EmojiUiPoster { runnable -> handler.post(runnable) },
                { executor },
                { EmojiSnapshotSource { base } },
                { store },
            )
            assertTrue(controller!!.onEmojiKeyPressed())
        }

        assertTrue("the deferred recents read never started", readStarted.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { controller!!.onEditorSessionChanged() }
        releaseRead.countDown()
        awaitExecutor()
        awaitMain()
        assertEquals("the old editor's recents callback published a panel", 0, surface.shows.get())

        instrumentation.runOnMainSync { assertTrue(controller!!.onEmojiKeyPressed()) }
        assertTrue("the current editor never received the prepared panel",
            surface.shown.await(10, TimeUnit.SECONDS))
        val shown = surface.last!!
        assertEquals(EmojiDisplaySnapshots.RECENT_CATEGORY_NAME, shown.categoryName(0))
        assertEquals(listOf("😀"), shown.entriesOf(0))

        instrumentation.runOnMainSync { controller!!.onEmojiInserted("😁") }
        awaitExecutor()
        instrumentation.runOnMainSync { controller!!.onPanelHidden() }
        awaitExecutor()
        assertEquals("😁${RecentEmojiList.SEPARATOR}😀", file.readText())
        assertEquals(1, store.saveCount)
        assertFalse(File(directory, file.name + ".tmp").exists())

        instrumentation.runOnMainSync { controller!!.onPanelHidden() }
        awaitExecutor()
        assertEquals("an unchanged second hide wrote again", 1, store.saveCount)
    }

    private fun awaitExecutor() {
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        assertTrue("the panel executor did not drain", done.await(10, TimeUnit.SECONDS))
    }

    private fun awaitMain() {
        val done = CountDownLatch(1)
        handler.post { done.countDown() }
        assertTrue("the main looper did not drain", done.await(10, TimeUnit.SECONDS))
    }
}
