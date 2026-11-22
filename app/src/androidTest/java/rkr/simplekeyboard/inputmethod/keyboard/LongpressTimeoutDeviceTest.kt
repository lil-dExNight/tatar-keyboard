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

import android.content.Context
import android.content.SharedPreferences
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.test.InstrumentationTestCase
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
import rkr.simplekeyboard.inputmethod.keyboard.internal.DrawingProxy
import rkr.simplekeyboard.inputmethod.keyboard.internal.TimerProxy
import rkr.simplekeyboard.inputmethod.latin.InputAttributes
import rkr.simplekeyboard.inputmethod.latin.Subtype
import rkr.simplekeyboard.inputmethod.latin.common.Constants
import rkr.simplekeyboard.inputmethod.latin.settings.Settings
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils

/** Stored preference bounds through the real reader and the real PointerTracker timer path. */
class LongpressTimeoutDeviceTest : InstrumentationTestCase() {

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private var hadSavedTimeout = false
    private var savedTimeout = 0
    private val timerDelays = ArrayList<Int>()
    private var trackpadStarts = 0

    private val timerProxy = object : TimerProxy {
        override fun startTypingStateTimer(typedKey: Key?) {}
        override fun isTypingState(): Boolean = false
        override fun startKeyRepeatTimerOf(tracker: PointerTracker?, repeatCount: Int, delay: Int) {}
        override fun startLongPressTimerOf(tracker: PointerTracker?, delay: Int) {
            timerDelays.add(delay)
        }
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

    private val listener = object : KeyboardActionListener.Adapter() {
        override fun onSpaceTrackpadStart(): Boolean {
            trackpadStarts++
            return true
        }
    }

    override fun setUp() {
        super.setUp()
        context = instrumentation.targetContext
        preferences = PreferenceManagerCompat.getDeviceSharedPreferences(context)
        hadSavedTimeout = preferences.contains(Settings.PREF_KEY_LONGPRESS_TIMEOUT)
        if (hadSavedTimeout) {
            savedTimeout = preferences.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 0)
        }
        Settings.init(context)
        PointerTracker.init(
            context.theme.obtainStyledAttributes(R.styleable.MainKeyboardView),
            timerProxy,
            drawingProxy,
        )
        PointerTracker.setKeyboardActionListener(listener)
        PointerTracker.setGlideAvailable(false)
    }

    override fun tearDown() {
        PointerTracker.cancelAllPointerTrackers()
        PointerTracker.setKeyboardActionListener(KeyboardActionListener.Adapter.EMPTY_LISTENER)
        PointerTracker.setGlideAvailable(false)
        val editor = preferences.edit()
        if (hadSavedTimeout) editor.putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, savedTimeout)
        else editor.remove(Settings.PREF_KEY_LONGPRESS_TIMEOUT)
        editor.commit()
        Settings.getInstance().onDestroy()
        super.tearDown()
    }

    fun testNegativeStoredTimeoutClampsToSliderMinimumForOrdinaryLongPress() {
        val minimum = resources.getInteger(R.integer.config_min_longpress_timeout)
        storeAndLoad(Int.MIN_VALUE)

        assertEquals(minimum,
            Settings.readKeyLongpressTimeout(preferences, resources))
        assertEquals(minimum, armTimerForKey('а'))

        // -1 used to be the in-band "missing" sentinel; a stored -1 is still a negative value.
        storeAndLoad(-1)
        assertEquals(minimum, Settings.readKeyLongpressTimeout(preferences, resources))
        assertEquals(minimum, armTimerForKey('а'))
    }

    fun testHugeStoredTimeoutClampsToSliderMaximumForOrdinaryLongPress() {
        val maximum = resources.getInteger(R.integer.config_max_longpress_timeout)
        storeAndLoad(Int.MAX_VALUE)

        assertEquals(maximum,
            Settings.readKeyLongpressTimeout(preferences, resources))
        assertEquals(maximum, armTimerForKey('а'))
    }

    fun testValidStoredTimeoutPassesThrough() {
        val minimum = resources.getInteger(R.integer.config_min_longpress_timeout)
        val maximum = resources.getInteger(R.integer.config_max_longpress_timeout)
        val valid = minimum + (maximum - minimum) / 2
        storeAndLoad(valid)

        assertEquals(valid, Settings.readKeyLongpressTimeout(preferences, resources))
        assertEquals(valid, armTimerForKey('а'))
    }

    fun testSpaceTrackpadUsesClampedTimeoutAndStarts() {
        val maximum = resources.getInteger(R.integer.config_max_longpress_timeout)
        storeAndLoad(Int.MAX_VALUE)

        assertEquals(maximum * SPACE_TIMEOUT_MULTIPLIER, armTimerForKey(Constants.CODE_SPACE))
        PointerTracker.getPointerTracker(0).onLongPressed()
        assertEquals("the space long press did not enter the trackpad", 1, trackpadStarts)
    }

    private fun storeAndLoad(value: Int) {
        assertTrue(preferences.edit().putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, value).commit())
        Settings.getInstance().loadSettings(InputAttributes(EditorInfo()))
        timerDelays.clear()
        trackpadStarts = 0
        PointerTracker.cancelAllPointerTrackers()
    }

    private fun armTimerForKey(code: Char): Int = armTimerForKey(code.code)

    private fun armTimerForKey(code: Int): Int {
        val keyboard = buildKeyboard()
        val key = keyboard.sortedKeys.first { it.code == code }
        val detector = KeyDetector(
            resources.getDimension(R.dimen.config_key_hysteresis_distance),
            resources.getDimension(R.dimen.config_key_hysteresis_distance_for_sliding_modifier),
            resources.getDimension(R.dimen.config_sliding_modifier_slop),
        )
        detector.setKeyboard(keyboard, 0f, 0f)
        PointerTracker.setKeyDetector(detector)
        val tracker = PointerTracker.getPointerTracker(0)
        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_DOWN,
            key.x + key.width / 2f, key.y + key.height / 2f, 0,
        )
        tracker.processMotionEvent(event, detector)
        event.recycle()
        assertEquals("one key down armed a different number of timers", 1, timerDelays.size)
        return timerDelays.single()
    }

    private fun buildKeyboard(): Keyboard {
        val themed = ContextThemeWrapper(context, R.style.KeyboardTheme_Tatar)
        return KeyboardLayoutSet.Builder(themed, null)
            .setKeyboardTheme(KeyboardTheme.THEME_ID_TATAR)
            .setKeyboardGeometry(
                themed.resources.displayMetrics.widthPixels,
                ResourceUtils.getDefaultKeyboardHeight(themed.resources),
                0,
            )
            .setSubtype(Subtype("tt_RU", "tatar", "tatar", false, themed.resources))
            .setLanguageSwitchKeyEnabled(true)
            .setShowSpecialChars(true)
            .setShowNumberRow(false)
            .setShowEmojiKey(false)
            .build()
            .getKeyboard(KeyboardId.ELEMENT_ALPHABET)
    }

    private val resources
        get() = context.resources

    private companion object {
        const val SPACE_TIMEOUT_MULTIPLIER = 3
    }
}
