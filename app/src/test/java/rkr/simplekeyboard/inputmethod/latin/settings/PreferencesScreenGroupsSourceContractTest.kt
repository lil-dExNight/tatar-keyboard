/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Preferences screen is grouped into "Suggestions", "Typing", "Key press" and a read-only
 * "Gestures" card. The number-row, fourth-cell and emoji-key switches live here too; the
 * delete-swipe summary must name the word-delete flick. The screen is an `Activity`, so the
 * wiring is pinned by source like the other contract tests in this package.
 */
class PreferencesScreenGroupsSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private val host by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt").readText()
    }

    private val rows by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsRows.kt").readText()
    }

    private val keyPress by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsKeyPressScreen.kt").readText()
    }

    private fun screenBody(): String =
        host.substringAfter("private fun buildPreferencesScreen()")
            .substringBefore("\n    private fun ")

    private fun appearanceBody(): String =
        host.substringAfter("private fun buildAppearanceScreen()")
            .substringBefore("\n    private fun ")

    private fun stringsXml(folder: String): String =
        File(sourceRoot(), "res/$folder/strings.xml").readText()

    // --- The grouped cards ---------------------------------------------------------------------

    @Test
    fun the_screen_builds_suggestions_typing_key_press_and_gestures_cards_in_order() {
        val body = screenBody()
        val suggestionsHeader = body.indexOf("addSectionHeader(getString(R.string.settings_section_suggestions))")
        val typingHeader = body.indexOf("addSectionHeader(getString(R.string.settings_section_typing))")
        val keyPressSection = body.indexOf("buildKeyPressSection()")
        val gesturesHeader = body.indexOf("addSectionHeader(getString(R.string.gestures_card_title))")
        assertTrue("the Suggestions card header is present", suggestionsHeader >= 0)
        assertTrue("the Typing card header follows it", typingHeader > suggestionsHeader)
        assertTrue("the Key press section follows Typing", keyPressSection > typingHeader)
        assertTrue("the Gestures card header follows Key press", gesturesHeader > keyPressSection)
    }

    @Test
    fun suggestions_holds_the_strip_rows_and_typing_holds_the_behavior_rows() {
        // Rows are created into their list before the list's card is appended, so the Typing
        // slice runs from the Suggestions card's append to the Gestures header.
        val body = screenBody()
        val suggestionsCard = body.indexOf("addCard(suggestionRows")
        val gesturesHeader = body.indexOf("addSectionHeader(getString(R.string.gestures_card_title))")
        assertTrue("the Suggestions card is appended", suggestionsCard >= 0)
        assertTrue("the Gestures card follows", gesturesHeader > suggestionsCard)
        val suggestionsSlice = body.substring(0, suggestionsCard)
        val typingSlice = body.substring(suggestionsCard, gesturesHeader)

        for (key in listOf("Settings.PREF_TATAR_SUGGESTIONS", "Settings.PREF_PERSONAL_DICTIONARY",
                "Settings.PREF_TATAR_AUTOCORRECT", "Settings.PREF_EMOJI_SUGGESTIONS",
                "Settings.PREF_INCOGNITO_MODE", "Settings.PREF_STRIP_FOURTH_CELL")) {
            assertTrue("the Suggestions card holds $key", suggestionsSlice.contains(key))
        }
        for (key in listOf("Settings.PREF_SHOW_NUMBER_ROW", "Settings.PREF_SHOW_EMOJI_KEY",
                "Settings.PREF_GLIDE_TYPING", "Settings.PREF_AUTO_CAP",
                "Settings.PREF_SHOW_SPECIAL_CHARS", "Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY",
                "Settings.PREF_ENABLE_IME_SWITCH", "Settings.PREF_SPACE_SWIPE",
                "Settings.PREF_DELETE_SWIPE")) {
            assertTrue("the Typing card holds $key", typingSlice.contains(key))
        }
    }


    @Test
    fun the_gestures_card_is_read_only_and_lists_every_gesture() {
        val body = screenBody()
        val card = body.substringAfter("addSectionHeader(getString(R.string.gestures_card_title))")
            .substringBefore("setRowEnabled(imeRow,")
        for (name in listOf("gesture_space_trackpad", "gesture_delete_slide",
                "gesture_word_delete_flick", "gesture_glide")) {
            assertTrue("the card lists $name", card.contains("R.string.$name"))
            assertTrue("with its one-line summary", card.contains("R.string.${name}_summary"))
        }
        assertFalse("a gesture row is never a switch", card.contains("switchRow"))
        val gestureRowDoc = rows.substringAfter("internal fun SettingsHostActivity.gestureRow(")
            .substringBefore("internal fun")
        assertTrue("the builder kills the tap target",
            gestureRowDoc.contains("row.isClickable = false"))
        assertTrue("and strips the chevron",
            gestureRowDoc.contains("R.id.row_chevron)"))
    }

    @Test
    fun appearance_switches_and_key_press_stay_in_preferences() {
        val appearance = appearanceBody()
        for (key in listOf("PREF_STRIP_FOURTH_CELL", "PREF_SHOW_NUMBER_ROW",
                "PREF_SHOW_EMOJI_KEY")) {
            assertFalse("Appearance must not retain $key", appearance.contains(key))
        }
        assertTrue("the fourth-cell row keeps its suggestions-dependent state",
            screenBody().contains("fourthCellRow = fourthCellSwitch"))
        assertTrue(screenBody().contains("Settings.readTatarSuggestionsEnabled(prefs)"))

        for (key in listOf("PREF_VIBRATE_ON", "PREF_SOUND_ON", "PREF_KEYPRESS_SOUND_VOLUME",
                "PREF_POPUP_ON", "PREF_KEY_LONGPRESS_TIMEOUT")) {
            assertTrue("the Key press section keeps $key", keyPress.contains(key))
        }
        assertTrue(keyPress.contains("internal fun SettingsHostActivity.buildKeyPressSection()"))
        assertFalse(host.contains("Screen.KEY_PRESS"))
    }

    // --- The flick is named in the summary -----------------------------------------------------

    @Test
    fun the_delete_swipe_summary_names_the_word_delete_flick() {
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val strings = stringsXml(folder)
            val summary = strings.substringAfter("<string name=\"delete_swipe_summary\">")
                .substringBefore("</string>")
            assertTrue("$folder: the summary must be more than the slide half",
                summary.length > 10)
        }
        // The English base names the flick explicitly; translations re-state the same contract.
        val english = stringsXml("values")
        val summary = english.substringAfter("<string name=\"delete_swipe_summary\">")
            .substringBefore("</string>")
        assertTrue("the English summary says a fast left flick deletes the word",
            summary.contains("flick") && summary.contains("word"))
    }

    // --- Locale parity -------------------------------------------------------------------------

    @Test
    fun every_new_string_exists_in_all_three_locales() {
        val keys = listOf(
            "settings_section_suggestions", "settings_section_typing",
            "gestures_card_title",
            "gesture_space_trackpad", "gesture_space_trackpad_summary",
            "gesture_delete_slide", "gesture_delete_slide_summary",
            "gesture_word_delete_flick", "gesture_word_delete_flick_summary",
            "gesture_glide", "gesture_glide_summary",
            "settings_screen_key_press", "settings_screen_information",
            "settings_screen_bottom_offset", "prefs_bottom_offset_portrait_settings",
            "prefs_bottom_offset_landscape_settings",
        )
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val strings = stringsXml(folder)
            for (key in keys) {
                assertTrue("$folder/strings.xml is missing $key",
                    strings.contains("<string name=\"$key\">"))
            }
        }
    }
}
