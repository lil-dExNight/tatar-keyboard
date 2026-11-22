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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import rkr.simplekeyboard.inputmethod.R

/** Real MotionEvent dispatch for the long-press consumption latch of every strip cell kind. */
class SuggestionStripLongPressDeviceTest : InstrumentationTestCase() {

    private var activity: Activity? = null
    private lateinit var view: SuggestionStripView
    private val clicks = ArrayList<String>()
    private val longPresses = ArrayList<String>()

    override fun setUp() {
        super.setUp()
        val context = instrumentation.targetContext
        val started = instrumentation.startActivitySync(
            Intent()
                .setClassName(context, "rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        activity = started
        instrumentation.runOnMainSync {
            view = SuggestionStripView(ContextThemeWrapper(started, R.style.KeyboardTheme_Tatar))
            view.setOnSuggestionClickListener { _, suggestion -> clicks.add(suggestion) }
            view.setOnSuggestionLongPressListener { _, suggestion -> longPresses.add(suggestion) }
            started.setContentView(
                view,
                ViewGroup.LayoutParams(VIEW_WIDTH_PX, VIEW_HEIGHT_PX),
            )
        }
        instrumentation.waitForIdleSync()
        clicks.clear()
        longPresses.clear()
    }

    override fun tearDown() {
        instrumentation.runOnMainSync { view.release() }
        activity?.finish()
        instrumentation.waitForIdleSync()
        super.tearDown()
    }

    fun testWordLongPressLeaveReenterAndReleaseDoesNotCommit() {
        assertLongPressLeaveReenterDoesNotCommit("сәләм", clip = false)
    }

    fun testEmojiLongPressLeaveReenterAndReleaseDoesNotCommit() {
        assertLongPressLeaveReenterDoesNotCommit("❤️", clip = false)
    }

    fun testRecentClipLongPressLeaveReenterAndReleaseDoesNotCommit() {
        assertLongPressLeaveReenterDoesNotCommit("recent clip", clip = true)
    }

    fun testOrdinaryTapsStillCommitAfterAConsumedLongPress() {
        assertLongPressLeaveReenterDoesNotCommit("сәләм", clip = false)
        publish("кит", clip = false)
        tap()
        assertEquals(listOf("кит"), clicks)
    }

    private fun assertLongPressLeaveReenterDoesNotCommit(text: String, clip: Boolean) {
        publish(text, clip)
        val downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, CELL_X, CELL_Y, downTime)
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + LONG_PRESS_MARGIN_MS)
        instrumentation.waitForIdleSync()
        assertEquals("the long-press action must still fire", listOf(text), longPresses)

        dispatch(MotionEvent.ACTION_MOVE, VIEW_WIDTH_PX + OUTSIDE_X, CELL_Y, downTime)
        dispatch(MotionEvent.ACTION_MOVE, CELL_X, CELL_Y, downTime)
        dispatch(MotionEvent.ACTION_UP, CELL_X, CELL_Y, downTime)

        assertTrue("a consumed long press must not commit on release", clicks.isEmpty())
        longPresses.clear()
    }

    private fun publish(text: String, clip: Boolean) {
        instrumentation.runOnMainSync {
            view.setSuggestions(text, null, null, null)
            view.setEmphasis(SuggestionStripState.NO_CELL)
            view.setClipCell(if (clip) 0 else SuggestionStripState.NO_CELL)
        }
        instrumentation.waitForIdleSync()
    }

    private fun tap() {
        val downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, CELL_X, CELL_Y, downTime)
        dispatch(MotionEvent.ACTION_UP, CELL_X, CELL_Y, downTime)
    }

    private fun dispatch(action: Int, x: Float, y: Float, downTime: Long) {
        val eventTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        var handled = false
        instrumentation.runOnMainSync {
            handled = view.dispatchTouchEvent(event)
        }
        event.recycle()
        assertTrue("the active strip gesture must remain dispatched to the view", handled)
    }

    private companion object {
        const val VIEW_WIDTH_PX = 300
        const val VIEW_HEIGHT_PX = 100
        const val CELL_X = 50f
        const val CELL_Y = 50f
        const val OUTSIDE_X = 20f
        const val LONG_PRESS_MARGIN_MS = 250L
    }
}
