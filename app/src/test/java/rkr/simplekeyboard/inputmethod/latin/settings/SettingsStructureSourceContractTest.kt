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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source contracts for settings navigation and the final Appearance structure. */
class SettingsStructureSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private fun source(path: String): String = File(sourceRoot(), path).readText()

    private val host by lazy {
        source("java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
    }

    private val keyPress by lazy {
        source("java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsKeyPressScreen.kt")
    }

    private fun body(start: String): String =
        host.substringAfter(start).substringBefore("\n    private fun ")

    @Test
    fun activatedKeyboardHidesOnlyTheRootBackChevron() {
        val activation = body("private fun isKeyboardActivated()")
        assertTrue(activation.contains("enabledInputMethodList"))
        assertTrue(activation.contains("SetupState.isImeEnabled"))
        assertTrue(activation.contains("SetupState.isImeCurrent"))
        assertTrue(activation.contains("SetupState.isSetupComplete"))
        assertTrue(activation.contains("Settings.Secure.DEFAULT_INPUT_METHOD"))

        val showScreen = host.substringAfter("internal fun showScreen(screen: Screen)")
            .substringBefore("private fun playScreenTransition()")
        assertTrue(showScreen.contains(
            "screen == Screen.ROOT && isKeyboardActivated()"))
        assertTrue(showScreen.contains("View.GONE"))
        assertTrue(showScreen.contains("View.VISIBLE"))
    }

    @Test
    fun appearanceHasNoThemePickerOrMovedSwitches() {
        assertFalse(host.contains("themeRow"))
        assertFalse(host.contains("showThemeDialog"))
        assertFalse(host.contains("themeName()"))
        val appearance = body("private fun buildAppearanceScreen()")
        assertFalse(appearance.contains("settings_screen_theme"))
        for (key in listOf("PREF_SHOW_NUMBER_ROW", "PREF_STRIP_FOURTH_CELL",
                "PREF_SHOW_EMOJI_KEY")) {
            assertFalse(appearance.contains(key))
        }
    }

    @Test
    fun appearanceRowsFollowTheRequestedOrder() {
        val appearance = body("private fun buildAppearanceScreen()")
        val rows = listOf("keyboardHeightRow()", "oneHandedModeRow()",
            "emojiPanelHeightRow()", "bottomOffsetRow()")
        val positions = rows.map(appearance::indexOf)
        assertTrue("every Appearance row exists: $positions", positions.all { it >= 0 })
        assertEquals("Appearance rows are out of order", positions.sorted(), positions)
    }

    @Test
    fun oneShiftRowOpensTwoIndependentOffsetSliders() {
        val shift = body("private fun bottomOffsetRow()")
        assertEquals(2, Regex("SeekBarDialogHelper\\.Entry\\(").findAll(shift).count())
        val portrait = shift.indexOf("Settings.PREF_BOTTOM_OFFSET_PORTRAIT")
        val landscape = shift.indexOf("Settings.PREF_BOTTOM_OFFSET_LANDSCAPE")
        assertTrue(portrait >= 0 && landscape > portrait)
        assertTrue(shift.contains("bottomOffsetProxy()"))
        assertTrue(shift.contains("bottomOffsetLandscapeProxy()"))
        assertTrue(shift.contains("config_min_bottom_offset_portrait"))
        assertTrue(shift.contains("config_max_bottom_offset_portrait"))
        assertTrue(shift.contains("config_min_bottom_offset_landscape"))
        assertTrue(shift.contains("config_max_bottom_offset_landscape"))
        assertEquals(2, shift.split("config_bottom_offset_step").size - 1)
        assertTrue(shift.contains("SeekBarDialogHelper.show(this,"))

        for (proxyName in listOf("bottomOffsetProxy", "bottomOffsetLandscapeProxy")) {
            val proxy = keyPress.substringAfter("SettingsHostActivity.$proxyName()")
                .substringBefore("internal fun")
            assertTrue("$proxyName writes the key supplied by its Entry",
                proxy.contains("prefs.edit().putInt(key, value).apply()"))
            assertTrue("$proxyName resets the key supplied by its Entry",
                proxy.contains("prefs.edit().remove(key).apply()"))
        }
    }
}
