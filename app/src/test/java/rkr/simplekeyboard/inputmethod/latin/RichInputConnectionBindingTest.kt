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

package rkr.simplekeyboard.inputmethod.latin

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.SurroundingText
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which editor each call reaches. A field switch to a password field reloads nothing, so the
 * connection must be bound at field start and re-read by every call outside a batch edit; a
 * typed letter otherwise goes to the previous field's connection. Text reloads, the coalesced
 * follow-up included, ask the host's password gate and read only the connection they captured.
 */
class RichInputConnectionBindingTest {

    /** Records every call that reaches it; reads answer short fixed text. */
    private class RecordingEditor(val name: String) : InputConnection {
        val calls = mutableListOf<String>()
        var readStarted: CountDownLatch? = null
        var readRelease: CountDownLatch? = null

        private fun blockRead() {
            readStarted?.countDown()
            try {
                readRelease?.await()
            } catch (_: InterruptedException) {
                // shutdownNow releases a blocked read; its late result must still be dropped.
            }
        }

        override fun beginBatchEdit(): Boolean { calls.add("begin"); return true }
        override fun endBatchEdit(): Boolean { calls.add("end"); return true }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            calls.add("commit:$text"); return true
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            calls.add("delete:$beforeLength,$afterLength"); return true
        }
        override fun setSelection(start: Int, end: Int): Boolean {
            calls.add("select:$start,$end"); return true
        }
        override fun performEditorAction(actionCode: Int): Boolean {
            calls.add("action:$actionCode"); return true
        }
        override fun performContextMenuAction(id: Int): Boolean {
            calls.add("menu:$id"); return true
        }
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            calls.add("readBefore")
            blockRead()
            return "abc"
        }
        override fun getSurroundingText(
            beforeLength: Int,
            afterLength: Int,
            flags: Int,
        ): SurroundingText? {
            calls.add("readSurrounding")
            blockRead()
            return null
        }
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            calls.add("readAfter"); return ""
        }
        override fun getSelectedText(flags: Int): CharSequence? {
            calls.add("readSelection"); return ""
        }
        override fun clearMetaKeyStates(states: Int) = true
        override fun closeConnection() {}
        override fun commitCompletion(text: CompletionInfo?) = true
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?) = true
        override fun commitCorrection(correctionInfo: CorrectionInfo?) = true
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = true
        override fun finishComposingText() = true
        override fun getCursorCapsMode(reqModes: Int) = 0
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
        override fun getHandler(): Handler? = null
        override fun performPrivateCommand(action: String?, data: Bundle?) = true
        override fun reportFullscreenMode(enabled: Boolean) = true
        override fun requestCursorUpdates(cursorUpdateMode: Int) = true
        override fun sendKeyEvent(event: KeyEvent?) = true
        override fun setComposingRegion(start: Int, end: Int) = true
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = true

        override fun toString() = name
    }

    /** The service side: which field is bound now and whether its text may be read. */
    private class FakeHost(var bound: InputConnection?, var mayRead: Boolean) :
        RichInputConnection.EditorHost {
        val uiPosts = mutableListOf<Runnable>()
        var surroundingTextReloads = false
        @Volatile var rejectPosts = false

        override fun getCurrentInputConnection() = bound
        override fun mayReadEditorText() = mayRead
        override fun resetSubtypeCycleOrder() {}
        override fun useSurroundingText() = surroundingTextReloads
        override fun postReloadResult(result: Runnable) {
            uiPosts += result
            if (rejectPosts) throw AssertionError("editor reload posted after shutdown")
        }
    }

    private val normal = RecordingEditor("normal")
    private val password = RecordingEditor("password")

    // --- The connection follows the bound field --------------------------------------------------

    @Test
    fun theFieldStartBindsAPasswordFieldWithoutReadingIt() {
        val host = FakeHost(password, mayRead = false)
        val connection = RichInputConnection(null, host)

        connection.onStartInput()

        assertTrue(connection.isConnected)
        assertTrue("binding reads nothing", password.calls.isEmpty())
    }

    @Test
    fun aLetterTypedAfterASwitchToAPasswordFieldLandsInThePasswordField() {
        val host = FakeHost(normal, mayRead = true)
        val connection = RichInputConnection(null, host)
        // Typing in the normal field binds its connection (any batch edit does).
        connection.beginBatchEdit()
        connection.commitText("а", 1)
        connection.endBatchEdit()

        // The password field starts: the cache is cleared and nothing is reloaded; with
        // auto-caps off no caps read happens either. Then a letter is typed, outside a batch.
        host.bound = password
        host.mayRead = false
        connection.commitText("б", 1)

        assertEquals(listOf("commit:б"), password.calls)
        assertEquals("the previous field gets nothing more",
            listOf("begin", "commit:а", "end"), normal.calls)
    }

    @Test
    fun everyUnbatchedEditorCallReachesTheFieldBoundNow() {
        val host = FakeHost(normal, mayRead = true)
        val connection = RichInputConnection(null, host)
        connection.onStartInput()
        connection.updateSelection(5, 5)
        host.bound = password

        connection.deleteTextBeforeCursor(1)
        connection.setSelection(2, 3)
        connection.performEditorAction(6)
        connection.performContextMenuAction(16908319)

        assertEquals(listOf("delete:1,0", "select:2,3", "action:6", "menu:16908319"), password.calls)
        assertTrue(normal.calls.isEmpty())
    }

    @Test
    fun aBatchOpensAndClosesOnOneEditor() {
        val host = FakeHost(normal, mayRead = true)
        val connection = RichInputConnection(null, host)

        connection.beginBatchEdit()
        host.bound = password // cannot happen on the UI thread mid-batch; the batch must hold
        connection.commitText("а", 1)
        connection.endBatchEdit()

        assertEquals(listOf("begin", "commit:а", "end"), normal.calls)
        assertTrue(password.calls.isEmpty())
    }

    @Test
    fun anUnmatchedEndDoesNotPoisonTheNextBatch() {
        val host = FakeHost(normal, mayRead = true)
        val connection = RichInputConnection(null, host)
        connection.onStartInput()

        connection.endBatchEdit()
        host.bound = password
        connection.beginBatchEdit()
        connection.commitText("а", 1)
        connection.endBatchEdit()

        assertTrue("an unmatched end never reaches the editor", normal.calls.isEmpty())
        assertEquals(listOf("begin", "commit:а", "end"), password.calls)
        assertEquals(0, nestLevelOf(connection))
        connection.shutdown()
    }

    @Test
    fun nestedBatchesCloseOnlyTheOutermostEditorBatch() {
        val host = FakeHost(normal, mayRead = true)
        val connection = RichInputConnection(null, host)

        connection.beginBatchEdit()
        connection.beginBatchEdit()
        host.bound = password
        connection.commitText("а", 1)
        connection.endBatchEdit()
        connection.commitText("б", 1)

        assertEquals("the inner close keeps both the batch and its editor",
            listOf("begin", "commit:а", "commit:б"), normal.calls)
        assertTrue(password.calls.isEmpty())

        connection.endBatchEdit()
        connection.commitText("в", 1)

        assertEquals(listOf("begin", "commit:а", "commit:б", "end"), normal.calls)
        assertEquals("the next unbatched call refreshes the editor", listOf("commit:в"), password.calls)
        assertEquals(0, nestLevelOf(connection))
        connection.shutdown()
    }

    @Test
    fun aFieldWithoutAConnectionGetsNothing() {
        val host = FakeHost(normal, mayRead = true)
        val connection = RichInputConnection(null, host)
        connection.onStartInput()
        host.bound = null

        connection.commitText("а", 1)

        assertFalse(connection.isConnected)
        assertTrue(normal.calls.isEmpty())
    }

    // --- Reloads ask the password gate -----------------------------------------------------------

    @Test
    fun aReloadInAPasswordFieldNeverStarts() {
        val connection = RichInputConnection(null, FakeHost(password, mayRead = false))

        connection.reloadTextCache()

        assertFalse("no reload is in flight", flag(connection, "mReloadInFlight"))
        assertTrue(password.calls.isEmpty())
    }

    @Test
    fun theFieldStartFillAsksThePasswordGateToo() {
        val connection = RichInputConnection(null, FakeHost(password, mayRead = false))

        // The gate answers before the EditorInfo is touched, so none is needed here.
        connection.reloadTextCache(null, false)

        assertEquals(-1, connection.expectedSelectionStart)
        assertEquals("", connection.cachedTextBeforeCursor.toString())
        assertTrue(password.calls.isEmpty())
    }

    @Test
    fun theCoalescedFollowUpAsksThePasswordGateToo() {
        val host = FakeHost(normal, mayRead = true)
        val connection = RichInputConnection(null, host)
        setFlag(connection, "mReloadInFlight", true) // a reload is on the worker
        connection.reloadTextCache()
        assertTrue("the request folds into the follow-up",
            flag(connection, "mReloadRequestedWhileInFlight"))

        // The bound field is now a password field when the in-flight reload completes.
        host.bound = password
        host.mayRead = false
        connection.finishReloadTextCache()

        assertFalse("the follow-up must not start a reload", flag(connection, "mReloadInFlight"))
        assertFalse(flag(connection, "mReloadRequestedWhileInFlight"))
        assertTrue(password.calls.isEmpty())
    }

    @Test
    fun theWorkerReadsTheConnectionCapturedWithTheGate() {
        val host = FakeHost(normal, mayRead = true)
        val connection = RichInputConnection(null, host)
        val worker = executorOf(connection)
        val release = CountDownLatch(1)
        worker.execute {
            try {
                release.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // The finally block releases this test blocker before closing the worker.
            }
        }
        try {
            connection.reloadTextCache() // queued behind the blocker, gate passed for "normal"

            // A password field starts before the worker runs and binds its connection.
            host.bound = password
            host.mayRead = false
            connection.onStartInput()
            release.countDown()
            worker.submit {}.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            connection.shutdown()
        }

        assertTrue("the password field is never read, was ${password.calls}",
            password.calls.none { it.startsWith("read") })
        assertTrue("the captured field is the one read", normal.calls.any { it.startsWith("read") })
        assertEquals("the captured result posts through the host, not a null service",
            1, host.uiPosts.size)
    }

    // --- Shutdown discards reload work -----------------------------------------------------------

    @Test
    fun shutdownDiscardsQueuedAndCoalescedReloads() {
        val host = FakeHost(normal, mayRead = true)
        val connection = RichInputConnection(null, host)
        val worker = executorOf(connection)
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        worker.execute {
            blockerStarted.countDown()
            try {
                releaseBlocker.await()
            } catch (_: InterruptedException) {
                // shutdownNow releases the blocker; the queued reload must not run after it.
            }
        }
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

        try {
            connection.reloadTextCache()
            assertTrue(flag(connection, "mReloadInFlight"))
            connection.reloadTextCache()
            assertTrue(flag(connection, "mReloadRequestedWhileInFlight"))

            connection.shutdown()
            connection.shutdown()

            assertTrue(worker.isShutdown)
            assertFalse(flag(connection, "mReloadInFlight"))
            assertFalse(flag(connection, "mReloadRequestedWhileInFlight"))
            connection.finishReloadTextCache()
            connection.reloadTextCache() // a late request cannot revive the closed executor
            releaseBlocker.countDown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue("the discarded reload never reads the editor", normal.calls.isEmpty())
        } finally {
            releaseBlocker.countDown()
            connection.shutdown()
            worker.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun shutdownRejectsALateBeforeAfterUiPost() {
        assertLateReadCannotPostAfterShutdown(useSurroundingText = false)
    }

    @Test
    fun shutdownRejectsALateSurroundingTextUiPost() {
        assertLateReadCannotPostAfterShutdown(useSurroundingText = true)
    }

    @Test
    fun aPostedBeforeAfterApplyCannotPublishAfterShutdown() {
        assertPostedApplyCannotPublishAfterShutdown(useSurroundingText = false)
    }

    @Test
    fun aPostedSurroundingTextApplyCannotPublishAfterShutdown() {
        assertPostedApplyCannotPublishAfterShutdown(useSurroundingText = true)
    }

    @Test
    fun repeatedConstructionAndShutdownLeavesNoPriorWorkerAlive() {
        val workers = mutableListOf<ExecutorService>()
        repeat(4) { index ->
            val connection = RichInputConnection(null)
            val worker = executorOf(connection)
            workers += worker
            try {
                worker.submit {}.get(5, TimeUnit.SECONDS) // start this lifecycle's thread
            } finally {
                connection.shutdown()
            }
            connection.shutdown()
            assertTrue("worker $index terminates", worker.awaitTermination(5, TimeUnit.SECONDS))
        }

        assertTrue(workers.all { it.isTerminated })
    }

    private fun assertLateReadCannotPostAfterShutdown(useSurroundingText: Boolean) {
        val branch = if (useSurroundingText) "SurroundingText" else "before/after"
        val editor = RecordingEditor(branch)
        val host = FakeHost(editor, mayRead = true)
        host.surroundingTextReloads = useSurroundingText
        val connection = RichInputConnection(null, host)
        val worker = executorOf(connection)
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        editor.readStarted = readStarted
        editor.readRelease = releaseRead
        connection.onBeforeCursorCacheReloaded("sentinel")
        connection.updateSelection(3, 3)
        val generation = cacheGenerationOf(connection)
        val workerFailure = AtomicReference<Throwable?>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> workerFailure.set(throwable) }

        try {
            connection.reloadTextCache()
            assertTrue("$branch read starts", readStarted.await(5, TimeUnit.SECONDS))
            connection.reloadTextCache()
            assertTrue(flag(connection, "mReloadRequestedWhileInFlight"))
            host.rejectPosts = true

            connection.shutdown()
            releaseRead.countDown()

            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue("$branch late read attempted a UI post: ${host.uiPosts.size}",
                host.uiPosts.isEmpty())
            assertNull("$branch post failure was not swallowed", workerFailure.get())
            assertTrue(connection.reloadCaptureIsStale(generation, 3, 3))
            assertEquals("$branch late result is not published", "sentinel",
                connection.cachedTextBeforeCursor.toString())
            assertFalse(flag(connection, "mReloadInFlight"))
            assertFalse(flag(connection, "mReloadRequestedWhileInFlight"))
            connection.finishReloadTextCache()
            assertTrue("$branch completion cannot post a follow-up", host.uiPosts.isEmpty())
        } finally {
            releaseRead.countDown()
            connection.shutdown()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private fun assertPostedApplyCannotPublishAfterShutdown(useSurroundingText: Boolean) {
        val branch = if (useSurroundingText) "SurroundingText" else "before/after"
        val editor = RecordingEditor(branch)
        val host = FakeHost(editor, mayRead = true)
        host.surroundingTextReloads = useSurroundingText
        val connection = RichInputConnection(null, host)
        val worker = executorOf(connection)
        connection.onBeforeCursorCacheReloaded("sentinel")
        connection.updateSelection(3, 3)

        try {
            connection.reloadTextCache()
            worker.submit {}.get(5, TimeUnit.SECONDS)

            val expectedReads = if (useSurroundingText) {
                listOf("readSurrounding")
            } else {
                listOf("readBefore", "readAfter")
            }
            assertEquals("$branch worker completed its read", expectedReads, editor.calls)
            assertEquals("$branch apply was enqueued before shutdown", 1, host.uiPosts.size)
            val apply = host.uiPosts.single()
            connection.reloadTextCache()
            assertTrue(flag(connection, "mReloadRequestedWhileInFlight"))

            connection.shutdown()
            host.rejectPosts = true
            apply.run()

            assertEquals("$branch queued apply did not publish", "sentinel",
                connection.cachedTextBeforeCursor.toString())
            assertFalse(flag(connection, "mReloadInFlight"))
            assertFalse(flag(connection, "mReloadRequestedWhileInFlight"))
            assertEquals("$branch completion neither posts nor reads a follow-up",
                expectedReads, editor.calls)
            assertEquals("$branch completion adds no UI post", 1, host.uiPosts.size)
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            connection.shutdown()
            worker.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun nestLevelOf(connection: RichInputConnection): Int {
        val field = RichInputConnection::class.java.getDeclaredField("mNestLevel")
        field.isAccessible = true
        return field.getInt(connection)
    }

    private fun cacheGenerationOf(connection: RichInputConnection): Int {
        val field = RichInputConnection::class.java.getDeclaredField("mCacheGeneration")
        field.isAccessible = true
        return field.getInt(connection)
    }

    private fun flag(connection: RichInputConnection, name: String): Boolean {
        val field = RichInputConnection::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getBoolean(connection)
    }

    private fun setFlag(connection: RichInputConnection, name: String, value: Boolean) {
        val field = RichInputConnection::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.setBoolean(connection, value)
    }

    private fun executorOf(connection: RichInputConnection): ExecutorService {
        val field = RichInputConnection::class.java.getDeclaredField("mBackgroundThread")
        field.isAccessible = true
        return field.get(connection) as ExecutorService
    }
}
