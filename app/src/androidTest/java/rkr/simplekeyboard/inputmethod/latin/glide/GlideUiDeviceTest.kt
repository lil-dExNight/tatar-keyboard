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

package rkr.simplekeyboard.inputmethod.latin.glide

import android.app.Instrumentation
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.MotionEvent
import android.os.SystemClock
import android.widget.EditText
import rkr.simplekeyboard.inputmethod.R

/**
 * The 2026-09-24 field fix on the live UI (docs/ROADMAP-P7.md): the user's exact flow through
 * the REAL keyboard on screen — the debug IME is enabled and made default by the host before
 * the run — press с, REST ~0.8 s, drag through ә→л→ә→м, lift.
 *
 * The P7-5 lift-commit contract: the lift itself commits the decode's top cell («сәлләм» — the
 * documented degenerate-path collision, see ROADMAP-P7 P7-3), the strip keeps the remaining
 * candidates as tappable alternatives, and tapping the cell that holds сәләм (the first
 * alternative — the left cell) replaces the committed word in place. P7-7 (2026-09-25): the
 * commits carry NO auto-space, and a second glide right after the first prepends exactly ONE
 * chain space (the д hereby path below: д→о→н→ь→я).
 *
 * The events go through `Instrumentation.sendPointerSync` — real system-level injection into
 * the live IME window, with real wall-clock holds. The strip-visible screenshot is taken by the
 * host during the logged pause. Never packaged into a release APK.
 */
class GlideUiDeviceTest : InstrumentationTestCase() {

    private var savedSuggestions = false
    private var savedGlide = false

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
    }

    override fun tearDown() {
        val prefs = rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
            .getDeviceSharedPreferences(instrumentation.targetContext)
        prefs.edit()
            .putBoolean("pref_tatar_suggestions", savedSuggestions)
            .putBoolean("pref_glide_typing", savedGlide)
            .commit()
    }

    fun testHoldThenSwipeLiftCommitsAndAlternativeReplaces() {
        driveUiGesture(800L)
    }

    private fun driveUiGesture(holdMs: Long) {
        val instrumentation = getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            android.content.Intent()
                .setClassName(context, "rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val field = activity.findViewById<EditText>(R.id.setup_test_field)
        assertNotNull("the try-it field must exist", field)
        // Focus the field with ONE tap (a second tap could land on the keyboard the first tap
        // opened), then wait passively for the IME window (adjustResize moves the field).
        val fieldLocation = IntArray(2)
        field.getLocationOnScreen(fieldLocation)
        tap(instrumentation, fieldLocation[0] + field.width / 2f, fieldLocation[1] + field.height / 2f)
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        var immUp = false
        for (attempt in 0 until 20) {
            Thread.sleep(500)
            if (imm.isAcceptingText) {
                immUp = true
                break
            }
        }
        assertTrue("the IME never accepted text (keyboard never came up)", immUp)
        Thread.sleep(3000)
        // Close the loop on the process/window race: a probe letter must land in the field before
        // the gesture is driven (a stale keyboard window from a previous run would eat it).
        var probes = 0
        while (probes < 10) {
            val fieldNow = IntArray(2)
            field.getLocationOnScreen(fieldLocation)
            tap(instrumentation, fieldLocation[0] + field.width / 2f, fieldLocation[1] + field.height / 2f)
            Thread.sleep(700)
            tap(instrumentation, 234f, 1396f) // с
            Thread.sleep(700)
            if (field.text.toString().endsWith("с")) break
            field.text.clear()
            probes++
            Thread.sleep(1000)
        }
        assertTrue("the keyboard never processed a probe tap", field.text.toString().endsWith("с"))
        field.text.clear()
        Thread.sleep(500)
        // The engine publishes ~7.5 s after the first field focus on a cold install (the
        // dictionary was already prepared here, but the start is still slow on this class of
        // device). Wait it out; the gesture must see a live engine.
        Thread.sleep(9000)

        // The gesture: down on с, rest 800 ms, then ә → л → ә → м, lift. Screen coordinates of
        // the live 720x1640 layout (calibrated from the device screencap, 2026-09-24).
        val path = listOf(
            234f to 1396f, // с
            60f to 1110f, // ә
            491f to 1301f, // л
            60f to 1110f, // ә
            297f to 1396f, // м
        )
        drivePath(instrumentation, path, holdMs)
        // The decode lands on the strip; the marker log gives the host the screencap window.
        Thread.sleep(1500)
        Log.i(TAG, "STRIP-VISIBLE")
        Thread.sleep(3000)

        val lifted = field.text.toString()
        Log.i(TAG, "field right after the lift: '$lifted'")
        assertTrue(
            "the lift itself commits the top candidate — P7-7: with NO auto-space, was '$lifted'",
            lifted == "сәлләм",
        )

        // Tap the LEFT strip cell: the alternatives hold candidates 2..4, and сәләм is the
        // first of them (the committed top cell сәлләм is no longer in the strip).
        tap(instrumentation, 120f, 980f)
        Thread.sleep(1500)
        val after = field.text.toString()
        Log.i(TAG, "field after the alternative tap: '$after'")
        assertTrue(
            "the alternative tap must replace the committed word in place (no space drift), was '$after'",
            after == "сәләм",
        )

        // P7-7 chaining: a second glide right after prepends exactly ONE space.
        val second = listOf(
            540f to 1301f, // д
            414f to 1301f, // о
            360f to 1206f, // н
            487f to 1396f, // ь
            98f to 1396f, // я
        )
        drivePath(instrumentation, second, holdMs = 0L)
        Thread.sleep(1500)
        val chained = field.text.toString()
        Log.i(TAG, "field after the chained glide: '$chained'")
        // The synthetic straight-segment path decodes to донья/дөнья depending on the ranking —
        // the CONTRACT under test is the spacing: the first word, exactly one chain space, one
        // decoded word, nothing trailing.
        assertTrue(
            "the chained glide prepends exactly one space and no trailing one, was '$chained'",
            chained.matches(Regex("сәләм \\S+")),
        )

        // And the undo of the chain step returns to exactly the first word's state.
        pressDelete(instrumentation)
        Thread.sleep(800)
        val undone = field.text.toString()
        Log.i(TAG, "field after the chain undo: '$undone'")
        assertTrue("undo must delete the word WITH the chain space, was '$undone'",
            undone == "сәләм")
    }

    /**
     * One gesture: down on the first point, an optional rest, the drag through the rest, lift.
     * Screen coordinates of the live 720x1640 layout (calibrated from the device screencap).
     */
    private fun drivePath(
        instrumentation: Instrumentation,
        path: List<Pair<Float, Float>>,
        holdMs: Long,
    ) {
        val downTime = SystemClock.uptimeMillis()
        inject(instrumentation, MotionEvent.ACTION_DOWN, path[0], downTime)
        if (holdMs > 0) Thread.sleep(holdMs)
        for (segment in 0 until path.size - 1) {
            val from = path[segment]
            val to = path[segment + 1]
            for (step in 1..8) {
                val x = from.first + (to.first - from.first) * step / 8f
                val y = from.second + (to.second - from.second) * step / 8f
                Thread.sleep(20)
                inject(instrumentation, MotionEvent.ACTION_MOVE, x to y, SystemClock.uptimeMillis())
            }
        }
        inject(instrumentation, MotionEvent.ACTION_UP, path.last(), SystemClock.uptimeMillis())
    }

    /** One tap on the keyboard's own delete key (bottom-right of the letters block). */
    private fun pressDelete(instrumentation: Instrumentation) {
        tap(instrumentation, 663f, 1396f)
    }

    private fun tap(instrumentation: Instrumentation, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        inject(instrumentation, MotionEvent.ACTION_DOWN, x to y, downTime)
        Thread.sleep(60)
        inject(instrumentation, MotionEvent.ACTION_UP, x to y, SystemClock.uptimeMillis())
    }

    private fun inject(instrumentation: Instrumentation, action: Int, point: Pair<Float, Float>, eventTime: Long) {
        val event = MotionEvent.obtain(eventTime, eventTime, action, point.first, point.second, 0)
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    companion object {
        private const val TAG = "GlideUi"
    }
}
