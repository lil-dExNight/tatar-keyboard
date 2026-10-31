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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardSwitcher
import rkr.simplekeyboard.inputmethod.latin.settings.Settings

/**
 * Typing in a password field right after a normal field, with auto-capitalization off: the
 * letters must land in the password field. The host enables the debug IME and makes it default
 * beforehand. A password field adds to the setup screen's try-it field; keys are found on the
 * live keyboard view, so no screen geometry is assumed.
 */
class PasswordFieldSwitchDeviceTest : InstrumentationTestCase() {

    private var savedAutoCap = true
    private var startedActivity: Activity? = null

    override fun setUp() {
        val prefs = PreferenceManagerCompat.getDeviceSharedPreferences(instrumentation.targetContext)
        savedAutoCap = prefs.getBoolean(Settings.PREF_AUTO_CAP, true)
        prefs.edit().putBoolean(Settings.PREF_AUTO_CAP, false).commit()
    }

    override fun tearDown() {
        startedActivity?.finish()
        PreferenceManagerCompat.getDeviceSharedPreferences(instrumentation.targetContext)
            .edit().putBoolean(Settings.PREF_AUTO_CAP, savedAutoCap).commit()
    }

    fun testLettersTypedAfterSwitchingToAPasswordFieldLandThere() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent()
                .setClassName(context, "rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        startedActivity = activity
        val normal = activity.findViewById<EditText>(R.id.setup_test_field)
        assertNotNull("the try-it field must exist", normal)
        lateinit var password: EditText
        instrumentation.runOnMainSync {
            password = EditText(activity)
            password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            val parent = normal.parent as ViewGroup
            parent.addView(password, parent.indexOfChild(normal) + 1, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        instrumentation.waitForIdleSync()

        // Focus the normal field with one tap and wait for the keyboard.
        val location = IntArray(2)
        normal.getLocationOnScreen(location)
        tap(location[0] + normal.width / 2f, location[1] + normal.height / 2f)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        assertTrue("the IME never accepted text", waitFor(10_000L) { imm.isAcceptingText })
        // The restarted process may bind the IME after the tap; ask again until it shows.
        var shown = false
        for (attempt in 0 until 10) {
            instrumentation.runOnMainSync { imm.showSoftInput(normal, 0) }
            if (waitFor(2000L) { keyboardIsShown() }) {
                shown = true
                break
            }
        }
        assertTrue("the keyboard view never came up", shown)
        Thread.sleep(2000)

        // A probe letter proves the keyboard types into the normal field.
        val normalLetter = tapFirstLetterKey()
        assertTrue("the normal field must receive the probe letter, was '${normal.text}'",
            waitFor(3000L) { normal.text.toString() == normalLetter })

        // Move focus to the password field; the keyboard stays up and restarts input there.
        instrumentation.runOnMainSync {
            password.requestFocus()
            imm.showSoftInput(password, 0)
        }
        assertTrue("the password field must be the served field with a password keyboard up",
            waitFor(5000L) {
                imm.isActive(password) && keyboardIsShown() &&
                    KeyboardSwitcher.getInstance().mainKeyboardView.keyboard.mId.passwordInput()
            })
        Thread.sleep(1000)

        val passwordLetter = tapFirstLetterKey()
        val landed = waitFor(3000L) { password.text.toString() == passwordLetter }
        Log.i(TAG, "password field length after one tap: ${password.text.length}; normal field: '${normal.text}'")
        assertTrue("the letter typed in the password field must land there " +
            "(password field length ${password.text.length})", landed)
        assertEquals("the normal field must not receive the password letter",
            normalLetter, normal.text.toString())

        // A second letter lands too, so the first was not a one-off refresh.
        val secondLetter = tapFirstLetterKey()
        assertTrue("the second letter must land in the password field",
            waitFor(3000L) { password.text.toString() == passwordLetter + secondLetter })
    }

    /** Must run on the main thread. */
    private fun keyboardIsShown(): Boolean {
        val view = KeyboardSwitcher.getInstance().mainKeyboardView
        return view != null && view.isShown && view.keyboard != null
    }

    /** Taps the first letter key of the live keyboard and returns the letter it types. */
    private fun tapFirstLetterKey(): String {
        var x = 0f
        var y = 0f
        var letter = ""
        instrumentation.runOnMainSync {
            val view = KeyboardSwitcher.getInstance().mainKeyboardView
            val keyboard = view?.keyboard
            if (view != null && keyboard != null) {
                val key = keyboard.sortedKeys.firstOrNull { Character.isLetter(it.code) }
                if (key != null) {
                    val origin = IntArray(2)
                    view.getLocationOnScreen(origin)
                    x = origin[0] + view.paddingLeft + key.x + key.width / 2f
                    y = origin[1] + view.paddingTop + key.y + key.height / 2f
                    letter = String(Character.toChars(Character.toLowerCase(key.code)))
                }
            }
        }
        assertTrue("no letter key on the live keyboard", letter.isNotEmpty())
        tap(x, y)
        return letter
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var met = false
            instrumentation.runOnMainSync { met = condition() }
            if (met) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun tap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        Thread.sleep(60)
        inject(MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis())
        Thread.sleep(300)
    }

    private fun inject(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val TAG = "PasswordFieldSwitch"
    }
}
