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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for tapjacking protection: every interactive
 * element of the settings and setup screens carries `android:filterTouchesWhenObscured="true"`,
 * so a touch arriving while another app's window obscures the screen is dropped instead of
 * toggling a switch the user never saw. Whether a touch is filtered is a framework decision
 * made on the view that RECEIVES it — the flag does not inherit from parent to child — so the
 * contract names the actual tap targets, not just the screen roots.
 *
 * The IME's own layouts must stay clean: the keyboard window never sets the flag (its key
 * previews and popups are child views of the same window, with no overlay scenario), so this
 * test also pins their absence.
 */
class SettingsTapjackingSourceContractTest {

    private fun layout(name: String): String {
        val candidates = listOf(
            File("src/main/res/layout/$name.xml"), File("app/src/main/res/layout/$name.xml"))
        val file = candidates.firstOrNull(File::isFile)
            ?: error("cannot locate $name.xml from ${File(".").absolutePath}")
        return file.readText()
    }

    private val flag = "android:filterTouchesWhenObscured=\"true\""

    private fun assertFlagged(layoutName: String, vararg viewIds: String) {
        val text = layout(layoutName)
        for (id in viewIds) {
            val tag = text.substringAfter("android:id=\"@+id/$id\"")
            assertTrue(
                "$layoutName.xml: @$id must filter touches when obscured",
                tag.substringBefore(">").contains(flag)
                    || tag.substringBefore("/>").contains(flag),
            )
        }
    }

    @Test
    fun settingsScreenRootAndBackButtonAreFlagged() {
        assertFlagged("settings_screen", "settings_root", "settings_back")
    }

    @Test
    fun settingsRowsAreFlaggedOnTheirClickTargets() {
        // The row root IS the click target (the Switch inside row_switch is decorative:
        // clickable=false), so the flag belongs on the root of every row layout.
        assertTrue(layout("row_link").contains(flag))
        assertTrue(layout("row_switch").contains(flag))
        assertTrue(layout("row_value").contains(flag))
        assertFlagged("row_text_input", "row_text_input")
    }

    @Test
    fun seekBarDialogIsFlagged() {
        assertFlagged("seek_bar_dialog", "seek_bar_dialog_bar")
        assertTrue("the inflated root too", layout("seek_bar_dialog").contains(flag))
    }

    @Test
    fun setupScreenIsFlaggedOnEveryActionTarget() {
        assertFlagged("setup_activity",
            "setup_root", "setup_step1_button", "setup_step2_button",
            "setup_done_button", "setup_test_field")
    }

    @Test
    fun imeWindowLayoutsStayUnflagged() {
        for (name in listOf(
            "input_view", "suggestion_strip", "more_keys_keyboard", "emoji_panel", "emoji_search")) {
            assertFalse(
                "$name.xml belongs to the IME window and must not filter touches",
                layout(name).contains("filterTouchesWhenObscured"),
            )
        }
    }
}
