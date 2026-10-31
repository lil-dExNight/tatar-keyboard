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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            calls.add("readBefore"); return "abc"
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
        override fun getCurrentInputConnection() = bound
        override fun mayReadEditorText() = mayRead
        override fun resetSubtypeCycleOrder() {}
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
        worker.execute { release.await(5, TimeUnit.SECONDS) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // Without a service the worker's posts to the UI thread fail; that is not under test.
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        try {
            connection.reloadTextCache() // queued behind the blocker, gate passed for "normal"

            // A password field starts before the worker runs and binds its connection.
            host.bound = password
            host.mayRead = false
            connection.onStartInput()
            release.countDown()
            worker.submit {}.get(5, TimeUnit.SECONDS)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertTrue("the password field is never read, was ${password.calls}",
            password.calls.none { it.startsWith("read") })
        assertTrue("the captured field is the one read", normal.calls.any { it.startsWith("read") })
    }

    // --- helpers ---------------------------------------------------------------------------------

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
