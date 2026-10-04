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

package rkr.simplekeyboard.inputmethod.latin.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Developer screen is the operator-facing half of the fifth-row instrument: it holds the arm
 * picker, the lab-log switch and the clear action, and clears the keyboard cache when the arm
 * changes. The whole instrument is debug-only — the root row is gated on FLAG_DEBUGGABLE, and
 * the arm application and the log itself are gated on the same flag where they apply, so a pref
 * restored from a backup cannot arm them on a release build. The screen is an `Activity`, so it
 * is pinned by source (as in `LegalScreenSourceContractTest`).
 */
class DeveloperScreenSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private val host by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt").readText()
    }
    private val latinIme by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()
    }
    private val labLog by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/lab/LabSessionLog.kt").readText()
    }
    private val layoutSet by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/keyboard/KeyboardLayoutSet.java").readText()
    }

    private fun bodyOf(source: String, from: String, to: String) =
        source.substringAfter(from).substringBefore(to)

    private val screenBody by lazy {
        bodyOf(host, "private fun buildDeveloperScreen()", "\n    private val fifthRowArmLabelRes")
    }

    @Test
    fun the_screen_is_reachable_from_the_root_screen_on_debuggable_builds_only() {
        val root = bodyOf(host, "private fun buildRootScreen()", "private fun buildLegalScreen()")
        assertTrue("root screen must offer a row that opens the developer screen",
            root.contains("navigateTo(Screen.DEVELOPER)"))
        assertTrue("the row needs its own title string",
            root.contains("R.string.settings_screen_developer"))
        assertTrue("the row must be gated on the debuggable flag",
            root.contains("ApplicationInfo.FLAG_DEBUGGABLE"))
        assertTrue("the screen must be dispatched in showScreen",
            host.contains("Screen.DEVELOPER -> buildDeveloperScreen()"))
    }

    @Test
    fun the_arm_and_the_log_are_gated_where_they_apply() {
        assertTrue("the layout set applies the arm only on debuggable builds",
            layoutSet.contains("ApplicationInfo.FLAG_DEBUGGABLE"))
        assertTrue("the layout set still resolves the alphabet element through the arm",
            layoutSet.contains("FifthRowArm.alphabetKeyboardXmlId("))
        assertTrue("the log arms only on debuggable builds",
            labLog.contains("ApplicationInfo.FLAG_DEBUGGABLE"))
    }

    @Test
    fun the_screen_holds_the_arm_picker_the_log_switch_and_the_clear_action() {
        assertTrue(screenBody.contains("fifthRowArmRow()"))
        assertTrue(screenBody.contains("Settings.PREF_LAB_SESSION_LOG"))
        assertTrue(screenBody.contains("R.string.clear_lab_log"))
        assertTrue("the picker writes the arm pref",
            host.contains("prefs.edit().putInt(Settings.PREF_FIFTH_ROW_ARM, which).apply()"))
        assertTrue("the clear action reaches the log",
            host.contains("LabSessionLog.clear(this)"))
    }

    @Test
    fun an_arm_change_clears_the_keyboard_cache() {
        val listener = bodyOf(host, "private val prefChangeListener", "\n    override fun attachBaseContext")
        assertTrue("the arm pref must be one of the layout-affecting keys",
            listener.contains("Settings.PREF_FIFTH_ROW_ARM == key"))
        assertTrue(listener.contains("KeyboardLayoutSet.onKeyboardThemeChanged()"))
    }

    @Test
    fun the_ime_feeds_only_codes_and_window_events() {
        assertTrue("the IME hands the singleton to its prefs once",
            latinIme.contains("LabSessionLog.init(this, mDevicePrefs)"))
        assertTrue("key down goes through the singleton",
            latinIme.contains("LabSessionLog.onKeyDown(primaryCode,"))
        assertTrue(latinIme.contains("LabSessionLog.onKeyboardShown()"))
        assertTrue(latinIme.contains("LabSessionLog.onKeyboardHidden()"))
        assertTrue("the singleton stays off unless the setting says on",
            labLog.contains("if (!enabled || passwordField || !isUnlocked()) return"))
    }

    /** Every new visible string exists in all three of the locales this project ships itself. */
    @Test
    fun the_new_strings_speak_all_three_languages() {
        val keys = listOf(
            "settings_screen_developer", "developer_intro", "fifth_row_arm",
            "lab_session_log", "lab_session_log_summary", "lab_session_log_pull_note",
            "clear_lab_log", "clear_lab_log_confirm")
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val text = File(sourceRoot(), "res/$folder/strings.xml").readText()
            for (key in keys) {
                assertTrue("$key missing from $folder", text.contains("name=\"$key\""))
            }
        }
    }
}
