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

package rkr.simplekeyboard.inputmethod.keyboard

import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.keyboard.internal.DrawingProxy
import rkr.simplekeyboard.inputmethod.keyboard.internal.TimerProxy
import rkr.simplekeyboard.inputmethod.latin.InputAttributes
import rkr.simplekeyboard.inputmethod.latin.Subtype
import rkr.simplekeyboard.inputmethod.latin.settings.Settings
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils
import kotlin.math.hypot

/**
 * The up-to-down touch-noise filter, driven through the real PointerTracker with real
 * MotionEvents. A close down after a previous up may be noise; a distant pointer or an older
 * previous up must not disable it, and no absolute event time may turn the filter off.
 *
 * Uses the JUnit3 legacy runner like the other device tests; not part of the release APK.
 */
class TouchNoiseDeviceTest : InstrumentationTestCase() {

    private lateinit var keyDetector: KeyDetector
    private lateinit var firstPoint: Pair<Float, Float>
    private lateinit var secondPoint: Pair<Float, Float>
    private var noiseWindowMs = 0L
    private var trackerId = 0
    private val tracker: PointerTracker
        get() = PointerTracker.getPointerTracker(trackerId)
    private val committedCodes = ArrayList<Int>()
    private val pressedCodes = ArrayList<Int>()

    private val recorder = object : KeyboardActionListener.Adapter() {
        override fun onPressKey(primaryCode: Int, repeatCount: Int, isSinglePointer: Boolean) {
            pressedCodes.add(primaryCode)
        }

        override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
            committedCodes.add(primaryCode)
        }
    }

    private val timerProxy = object : TimerProxy {
        override fun startTypingStateTimer(typedKey: Key?) {}
        override fun isTypingState(): Boolean = false
        override fun startKeyRepeatTimerOf(tracker: PointerTracker?, repeatCount: Int, delay: Int) {}
        override fun startLongPressTimerOf(tracker: PointerTracker?, delay: Int) {}
        override fun cancelLongPressTimersOf(tracker: PointerTracker?) {}
        override fun cancelLongPressShiftKeyTimer() {}
        override fun cancelKeyTimersOf(tracker: PointerTracker?) {}
        override fun startDoubleTapShiftKeyTimer() {}
        override fun cancelDoubleTapShiftKeyTimer() {}
        override fun isInDoubleTapShiftKeyTimeout(): Boolean = false
    }

    private val drawingProxy = object : DrawingProxy {
        override fun onKeyPressed(key: Key?, withPreview: Boolean) {}
        override fun onKeyReleased(key: Key?, withAnimation: Boolean) {}
        override fun showMoreKeysKeyboard(key: Key?, tracker: PointerTracker?): MoreKeysPanel? = null
        override fun startWhileTypingAnimation(fadeInOrOut: Int) {}
        override fun onGlideTrailPoint(x: Float, y: Float, eventTime: Long) {}
        override fun onGlideTrailEnd() {}
    }

    override fun setUp() {
        val context = instrumentation.targetContext
        Settings.init(context)
        Settings.getInstance().loadSettings(InputAttributes(EditorInfo()))
        val themed = ContextThemeWrapper(context, R.style.KeyboardTheme_Tatar)
        val attributes = themed.theme.obtainStyledAttributes(R.styleable.MainKeyboardView)
        try {
            PointerTracker.init(attributes, timerProxy, drawingProxy)
        } finally {
            attributes.recycle()
        }
        PointerTracker.setKeyboardActionListener(recorder)
        PointerTracker.setGlideAvailable(false)
        PointerTracker.cancelAllPointerTrackers()

        val keyboard = buildKeyboard(themed)
        keyDetector = KeyDetector(
            context.resources.getDimension(R.dimen.config_key_hysteresis_distance),
            context.resources.getDimension(
                R.dimen.config_key_hysteresis_distance_for_sliding_modifier),
            context.resources.getDimension(R.dimen.config_sliding_modifier_slop),
        )
        keyDetector.setKeyboard(keyboard, 0f, 0f)
        PointerTracker.setKeyDetector(keyDetector)
        firstPoint = centerOf(keyboard, FIRST_KEY)
        secondPoint = centerOf(keyboard, SECOND_KEY)
        noiseWindowMs = context.resources.getInteger(
            R.integer.config_touch_noise_threshold_time).toLong()
        val noiseDistance = context.resources.getDimension(
            R.dimen.config_touch_noise_threshold_distance)
        val keyDistance = hypot(
            firstPoint.first - secondPoint.first,
            firstPoint.second - secondPoint.second,
        ).toDouble()
        assertTrue("the test keys must be farther apart than the noise threshold",
            keyDistance > noiseDistance)

        trackerId = TEST_POINTER_ID
        committedCodes.clear()
        pressedCodes.clear()
    }

    override fun tearDown() {
        PointerTracker.setKeyboardActionListener(KeyboardActionListener.Adapter.EMPTY_LISTENER)
        PointerTracker.cancelAllPointerTrackers()
        PointerTracker.setGlideAvailable(false)
    }

    fun testCloseDownAtThePreviousUpPointIsRejected() {
        val base = SystemClock.uptimeMillis() % DAY_MS + MINUTE_MS
        tap(firstPoint, base, base + TAP_MS)
        assertEquals(listOf(FIRST_KEY.code), committedCodes)
        committedCodes.clear()

        val secondDown = base + TAP_MS + noiseWindowMs / 2
        tap(firstPoint, secondDown, secondDown + TAP_MS)
        assertTrue("a close repeat within the noise window must be rejected",
            committedCodes.isEmpty())
    }

    fun testDownFarBeyondTheNoiseWindowIsAccepted() {
        val base = SystemClock.uptimeMillis() % DAY_MS + MINUTE_MS
        tap(firstPoint, base, base + TAP_MS)
        committedCodes.clear()

        val secondDown = base + TAP_MS + noiseWindowMs * 2
        tap(firstPoint, secondDown, secondDown + TAP_MS)
        assertEquals(listOf(FIRST_KEY.code), committedCodes)
    }

    fun testDistantDownInsideTheNoiseWindowIsAccepted() {
        val base = SystemClock.uptimeMillis() % DAY_MS + MINUTE_MS
        tap(firstPoint, base, base + TAP_MS)
        committedCodes.clear()

        val secondDown = base + TAP_MS + noiseWindowMs / 2
        tap(secondPoint, secondDown, secondDown + TAP_MS)
        assertEquals(listOf(SECOND_KEY.code), committedCodes)
    }

    fun testHighAbsoluteUptimeDoesNotDisableTheFilter() {
        val base = DAY_MS * 2
        tap(firstPoint, base, base + TAP_MS)
        committedCodes.clear()

        val secondDown = base + TAP_MS + noiseWindowMs / 2
        tap(firstPoint, secondDown, secondDown + TAP_MS)
        assertTrue("the filter must still work long after boot", committedCodes.isEmpty())
    }

    fun testFirstTouchBeforeAnyUpIsAcceptedNearBoot() {
        tap(firstPoint, FIRST_TOUCH_MS, FIRST_TOUCH_MS + TAP_MS)
        assertEquals(listOf(FIRST_KEY.code), committedCodes)
    }

    fun testPreviousUpIsScopedAcrossRealPointerDownAndUp() {
        val base = SystemClock.uptimeMillis() % DAY_MS + MINUTE_MS
        val reusedTracker = tracker
        val freshTracker = PointerTracker.getPointerTracker(trackerId + 1)
        tap(reusedTracker, firstPoint, base, base + TAP_MS)
        committedCodes.clear()
        pressedCodes.clear()

        // The fresh pointer must not inherit the reused pointer's recent up.
        val streamDown = base + TAP_MS + noiseWindowMs / 2
        dispatch(freshTracker, MotionEvent.ACTION_DOWN, firstPoint, streamDown, streamDown)
        assertEquals(listOf(FIRST_KEY.code), pressedCodes)

        // Reusing the first pointer ID at the same point must apply its own previous up.
        val reusedPointerDown = streamDown + 2
        val pointers = arrayOf(
            TouchPointer(freshTracker.mPointerId, firstPoint),
            TouchPointer(reusedTracker.mPointerId, firstPoint),
        )
        dispatchPointers(reusedTracker, MotionEvent.ACTION_POINTER_DOWN, 1,
            reusedPointerDown, streamDown, *pointers)
        assertEquals("the reused pointer's close down must still be filtered",
            listOf(FIRST_KEY.code), pressedCodes)

        val freshPointerUp = reusedPointerDown + 5
        dispatchPointers(freshTracker, MotionEvent.ACTION_POINTER_UP, 0,
            freshPointerUp, streamDown, *pointers)
        dispatch(reusedTracker, MotionEvent.ACTION_UP, firstPoint,
            freshPointerUp + 5, streamDown)
        assertEquals(listOf(FIRST_KEY.code), pressedCodes)
        assertEquals(listOf(FIRST_KEY.code), committedCodes)
    }

    fun testCancelClearsTheNoiseWindow() {
        val base = SystemClock.uptimeMillis() % DAY_MS + MINUTE_MS
        tap(firstPoint, base, base + TAP_MS)
        committedCodes.clear()

        // Distant, so this accepted down reaches a cancel while the old up is still fresh.
        val cancelledDown = base + TAP_MS + noiseWindowMs / 2
        dispatch(MotionEvent.ACTION_DOWN, secondPoint, cancelledDown, cancelledDown)
        dispatch(MotionEvent.ACTION_CANCEL, secondPoint, cancelledDown + TAP_MS, cancelledDown)

        val closeDown = cancelledDown + TAP_MS + 1
        assertTrue("the post-cancel down must be inside the original up's noise window",
            closeDown - (base + TAP_MS) < noiseWindowMs)
        tap(secondPoint, closeDown, closeDown + TAP_MS)
        assertEquals("a cancel must clear the previous-up window",
            listOf(SECOND_KEY.code), committedCodes)
    }

    fun testFullStreamCancelClearsPeerTracker() {
        val base = SystemClock.uptimeMillis() % DAY_MS + MINUTE_MS
        val peerTracker = tracker
        val receivingTracker = PointerTracker.getPointerTracker(trackerId + 1)
        tap(peerTracker, firstPoint, base, base + TAP_MS)

        // Both pointers are active; the peer's distant down passes its spatial check.
        val streamDown = base + TAP_MS + noiseWindowMs / 4
        dispatch(receivingTracker, MotionEvent.ACTION_DOWN, secondPoint, streamDown, streamDown)
        val peerDown = streamDown + 2
        val pointers = arrayOf(
            TouchPointer(receivingTracker.mPointerId, secondPoint),
            TouchPointer(peerTracker.mPointerId, secondPoint),
        )
        dispatchPointers(peerTracker, MotionEvent.ACTION_POINTER_DOWN, 1,
            peerDown, streamDown, *pointers)
        dispatchPointers(receivingTracker, MotionEvent.ACTION_CANCEL, 0,
            peerDown + 5, streamDown, *pointers)
        committedCodes.clear()

        // Reuse the peer ID while its original up is still inside the noise window.
        val reusedDown = base + TAP_MS + noiseWindowMs - 5
        assertTrue("the peer's reused down must be inside its original up's noise window",
            reusedDown - (base + TAP_MS) < noiseWindowMs)
        tap(peerTracker, firstPoint, reusedDown, reusedDown + 1)
        assertEquals("canceling one pointer must clear its peers' previous-up state",
            listOf(FIRST_KEY.code), committedCodes)
    }

    fun testKeyboardCloseAndReopenClearsActiveAndIdleTrackers() {
        val base = SystemClock.uptimeMillis() % DAY_MS + MINUTE_MS
        val activeTracker = tracker
        val idleTracker = PointerTracker.getPointerTracker(trackerId + 1)
        tap(activeTracker, firstPoint, base, base + TAP_MS)
        val idleDown = base + TAP_MS + 1
        tap(idleTracker, secondPoint, idleDown, idleDown + TAP_MS)

        // Keep one tracker active at close while the other is idle with a recent up.
        val activeDown = idleDown + TAP_MS + noiseWindowMs / 4
        dispatch(activeTracker, MotionEvent.ACTION_DOWN, secondPoint, activeDown, activeDown)
        PointerTracker.cancelAllPointerTrackers()
        committedCodes.clear()

        // Reopen with the same IDs. Each down remains inside its original up's noise window.
        val activeReuseDown = base + TAP_MS + noiseWindowMs - 5
        val idleReuseDown = idleDown + TAP_MS + noiseWindowMs - 5
        assertTrue("the active tracker's reused down must be inside its original up window",
            activeReuseDown - (base + TAP_MS) < noiseWindowMs)
        assertTrue("the idle tracker's reused down must be inside its original up window",
            idleReuseDown - (idleDown + TAP_MS) < noiseWindowMs)
        tap(activeTracker, firstPoint, activeReuseDown, activeReuseDown + 1)
        tap(idleTracker, secondPoint, idleReuseDown, idleReuseDown + 1)
        assertEquals(listOf(FIRST_KEY.code, SECOND_KEY.code), committedCodes)
    }

    private fun tap(point: Pair<Float, Float>, downTime: Long, upTime: Long) {
        tap(tracker, point, downTime, upTime)
    }

    private fun tap(tracker: PointerTracker, point: Pair<Float, Float>, downTime: Long,
            upTime: Long) {
        dispatch(tracker, MotionEvent.ACTION_DOWN, point, downTime, downTime)
        dispatch(tracker, MotionEvent.ACTION_UP, point, upTime, downTime)
    }

    private fun dispatch(action: Int, point: Pair<Float, Float>, eventTime: Long,
            downTime: Long) {
        dispatch(tracker, action, point, eventTime, downTime)
    }

    private fun dispatch(tracker: PointerTracker, action: Int, point: Pair<Float, Float>,
            eventTime: Long, downTime: Long) {
        dispatchPointers(tracker, action, 0, eventTime, downTime,
            TouchPointer(tracker.mPointerId, point))
    }

    private fun dispatchPointers(tracker: PointerTracker, action: Int, actionIndex: Int,
            eventTime: Long, downTime: Long, vararg pointers: TouchPointer) {
        val properties = Array(pointers.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = pointers[index].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(pointers.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = pointers[index].point.first
                y = pointers[index].point.second
                pressure = 1f
                size = 1f
            }
        }
        val encodedAction = if (action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            action
        }
        val event = MotionEvent.obtain(
            downTime, eventTime, encodedAction, pointers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            tracker.processMotionEvent(event, keyDetector)
        } finally {
            event.recycle()
        }
    }

    private data class TouchPointer(val id: Int, val point: Pair<Float, Float>)

    private fun buildKeyboard(context: ContextThemeWrapper): Keyboard {
        val resources = context.resources
        return KeyboardLayoutSet.Builder(context, null)
            .setKeyboardTheme(KeyboardTheme.THEME_ID_TATAR)
            .setKeyboardGeometry(
                resources.displayMetrics.widthPixels,
                ResourceUtils.getDefaultKeyboardHeight(resources),
                0,
            )
            .setSubtype(Subtype("tt_RU", "tatar", "tatar", false, resources))
            .setLanguageSwitchKeyEnabled(true)
            .setShowSpecialChars(true)
            .setShowNumberRow(false)
            .setShowEmojiKey(false)
            .build()
            .getKeyboard(KeyboardId.ELEMENT_ALPHABET)
    }

    private fun centerOf(keyboard: Keyboard, character: Char): Pair<Float, Float> {
        val key = keyboard.sortedKeys.firstOrNull {
            Character.toLowerCase(it.code) == Character.toLowerCase(character.code)
        } ?: error("no key for $character")
        return (key.x + key.width / 2f) to (key.y + key.height / 2f)
    }

    companion object {
        private const val FIRST_KEY = 'а'
        private const val SECOND_KEY = 'м'
        private const val FIRST_TOUCH_MS = 5L
        private const val TAP_MS = 10L
        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val TEST_POINTER_ID = 2
    }
}
