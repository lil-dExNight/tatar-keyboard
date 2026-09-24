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

import android.test.InstrumentationTestCase
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.keyboard.internal.DrawingProxy
import rkr.simplekeyboard.inputmethod.keyboard.internal.TimerProxy
import rkr.simplekeyboard.inputmethod.latin.InputAttributes
import rkr.simplekeyboard.inputmethod.latin.Subtype
import rkr.simplekeyboard.inputmethod.latin.common.Constants
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath
import rkr.simplekeyboard.inputmethod.latin.settings.Settings
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils

/**
 * The 2026-09-24 field fix (docs/ROADMAP-P7.md), proven on the device through the REAL
 * PointerTracker with REAL MotionEvents and real wall-clock holds: the user's exact flow —
 * press a letter, REST, then swipe and lift — must deliver a glide, and a fired long-press must
 * close the gesture instead. The strip-level content of the delivered path is proven by
 * GlideDeviceInstrumentationTest (same device, same geometry, the decode itself).
 *
 * Same JUnit3/legacy-runner shape as the other device harnesses; never packaged into a release.
 */
class GlidePointerDeviceTest : InstrumentationTestCase() {

    private var savedSuggestions = false
    private var savedGlide = false

    private val recordedGlides = ArrayList<GlidePath>()
    private var longPressTimerArms = 0
    private var longPressCancels = 0

    private val recorder = object : KeyboardActionListener.Adapter() {
        override fun onGlideInput(path: GlidePath) {
            val copy = GlidePath(path.size)
            path.copyInto(copy)
            recordedGlides.add(copy)
        }
    }

    private val timerProxy = object : TimerProxy {
        override fun startTypingStateTimer(typedKey: Key?) {}
        override fun isTypingState(): Boolean = false
        override fun startKeyRepeatTimerOf(tracker: PointerTracker?, repeatCount: Int, delay: Int) {}
        override fun startLongPressTimerOf(tracker: PointerTracker?, delay: Int) {
            longPressTimerArms++
        }
        override fun cancelLongPressTimersOf(tracker: PointerTracker?) {
            longPressCancels++
        }
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
    }

    override fun setUp() {
        val context = instrumentation.targetContext
        val prefs = rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
            .getDeviceSharedPreferences(context)
        savedSuggestions = prefs.getBoolean("pref_tatar_suggestions", false)
        savedGlide = prefs.getBoolean("pref_glide_typing", true)
        prefs.edit()
            .putBoolean("pref_tatar_suggestions", true)
            .putBoolean("pref_glide_typing", true)
            .commit()
        Settings.init(context)
        Settings.getInstance().loadSettings(InputAttributes(EditorInfo(), false))
        PointerTracker.init(
            context.theme.obtainStyledAttributes(R.styleable.MainKeyboardView),
            timerProxy, drawingProxy,
        )
        PointerTracker.setKeyboardActionListener(recorder)
        recordedGlides.clear()
        longPressTimerArms = 0
        longPressCancels = 0
    }

    override fun tearDown() {
        PointerTracker.setKeyboardActionListener(KeyboardActionListener.EMPTY_LISTENER)
        PointerTracker.cancelAllPointerTrackers()
        val prefs = rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
            .getDeviceSharedPreferences(instrumentation.targetContext)
        prefs.edit()
            .putBoolean("pref_tatar_suggestions", savedSuggestions)
            .putBoolean("pref_glide_typing", savedGlide)
            .commit()
    }

    /** The user's exact flow: press the letter, REST 700 ms, then glide through the rest. */
    fun testHoldThenSwipeDeliversAGlide() {
        driveGesture(holdMs = 700L, fast = true)
        assertEquals("exactly one glide must be delivered", 1, recordedGlides.size)
        val path = recordedGlides[0]
        assertTrue("the path must carry the whole gesture", path.size > 10)
    }

    /** The pre-fix flow that always worked: an immediate swipe. */
    fun testImmediateSwipeStillDeliversAGlide() {
        driveGesture(holdMs = 0L, fast = true)
        assertEquals(1, recordedGlides.size)
    }

    /** A finger that just rests: the long-press fires, and afterwards no glide can arm. */
    fun testFiredLongPressClosesTheGesture() {
        driveGesture(holdMs = 700L, fast = false, fireLongPress = true, word = "али")
        assertTrue("the long-press timer must have armed at down", longPressTimerArms > 0)
        assertTrue("the long-press cancels its own timers", longPressCancels > 0)
        assertEquals("a fired long-press never delivers a glide", 0, recordedGlides.size)
    }

    /**
     * The drive: down on the с key of the live Tatar keyboard, hold, then drag through
     * ә → л → ә → м and lift. Real MotionEvents, real wall-clock holds.
     */
    private fun driveGesture(holdMs: Long, fast: Boolean, fireLongPress: Boolean = false, word: String = "сәләм") {
        val keyboard = buildKeyboard()
        val keyDetector = KeyDetector(
            dimen(R.dimen.config_key_hysteresis_distance),
            dimen(R.dimen.config_key_hysteresis_distance_for_sliding_modifier),
            dimen(R.dimen.config_sliding_modifier_slop),
        )
        keyDetector.setKeyboard(keyboard, 0f, 0f)
        PointerTracker.setKeyDetector(keyDetector)
        val tracker = PointerTracker.getPointerTracker(0)
        val centers = centersOf(keyboard, word)

        val downTime = SystemClock.uptimeMillis()
        tracker.processMotionEvent(event(MotionEvent.ACTION_DOWN, centers[0], downTime), keyDetector)
        if (holdMs > 0) {
            Thread.sleep(holdMs)
        }
        if (fireLongPress) {
            tracker.onLongPressed()
        }
        // The drag: ~10 intermediate points per segment, ~16 ms apart (a real swipe's cadence)
        // for the fast flow, ~150 ms apart (a drift) for the slow one.
        val stepDelay = if (fast) 16L else 150L
        for (segment in 0 until centers.size - 1) {
            val from = centers[segment]
            val to = centers[segment + 1]
            for (step in 1..10) {
                val x = from.first + (to.first - from.first) * step / 10f
                val y = from.second + (to.second - from.second) * step / 10f
                Thread.sleep(stepDelay)
                tracker.processMotionEvent(
                    event(MotionEvent.ACTION_MOVE, x, y, SystemClock.uptimeMillis()), keyDetector,
                )
            }
        }
        Thread.sleep(20)
        val last = centers.last()
        tracker.processMotionEvent(
            event(MotionEvent.ACTION_UP, last.first, last.second, SystemClock.uptimeMillis()),
            keyDetector,
        )
    }

    private fun buildKeyboard(): Keyboard {
        val context = instrumentation.targetContext
        val themed = ContextThemeWrapper(context, R.style.KeyboardTheme_Tatar)
        val resources = themed.resources
        return KeyboardLayoutSet.Builder(themed, null)
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

    private fun centersOf(keyboard: Keyboard, word: String): List<Pair<Float, Float>> {
        val centers = ArrayList<Pair<Float, Float>>(word.length)
        for (char in word) {
            var found: Key? = null
            for (key in keyboard.sortedKeys) {
                if (Character.toLowerCase(key.code) == Character.toLowerCase(char.code)) {
                    found = key
                    break
                }
            }
            checkNotNull(found) { "no key for $char" }
            centers.add(
                (found.x + found.width / 2f) to (found.y + found.height / 2f),
            )
        }
        return centers
    }

    private fun event(action: Int, x: Float, y: Float, eventTime: Long): MotionEvent =
        MotionEvent.obtain(eventTime, eventTime, action, x, y, 0)

    private fun event(action: Int, point: Pair<Float, Float>, eventTime: Long): MotionEvent =
        event(action, point.first, point.second, eventTime)

    private fun dimen(id: Int): Float = instrumentation.targetContext.resources.getDimension(id)
}
