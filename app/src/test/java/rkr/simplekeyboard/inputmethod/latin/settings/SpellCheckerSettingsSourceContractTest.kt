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
 * The spell-checker settings: the screen the system list opens, the Tatar switch, the link to the
 * saved words and the guide that leads to the system switch. The screens are `Activity` views and
 * the selector is framework state, so both are pinned by source.
 */
class SpellCheckerSettingsSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private fun source(path: String): String = File(sourceRoot(), path).readText()

    private val host by lazy {
        source("java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
    }

    private val forwarder by lazy {
        source("java/rkr/simplekeyboard/inputmethod/latin/settings/SpellCheckerSettingsActivity.kt")
    }

    private val selection by lazy {
        source("java/rkr/simplekeyboard/inputmethod/latin/spellcheck/SpellCheckerSelection.kt")
    }

    private fun body(start: String): String =
        host.substringAfter(start).substringBefore("\n    private fun ")

    @Test
    fun the_system_entry_forwards_to_the_spell_checker_screen() {
        assertTrue(forwarder.contains("SettingsHostActivity.EXTRA_SCREEN"))
        assertTrue(forwarder.contains("Screen.SPELL_CHECKER.name"))
        assertFalse("the forwarder must read no extras", forwarder.contains("getStringExtra"))
        assertTrue(host.contains("Screen.SPELL_CHECKER -> buildSpellCheckerScreen()"))
        assertTrue(host.contains("screenFromExtra(intent)"))
    }

    @Test
    fun the_screen_holds_the_tatar_switch_and_the_saved_words_link() {
        val screen = body("private fun buildSpellCheckerScreen()")
        assertTrue(screen.contains(
            "switchRow(Settings.PREF_TATAR_SPELL_CHECKER, true,"))
        assertTrue(screen.contains("navigateTo(Screen.PERSONAL_DICTIONARY)"))
        assertTrue(screen.contains("SpellCheckerSelection.isSelected(this)"))
        assertTrue(source("java/rkr/simplekeyboard/inputmethod/latin/settings/Settings.java")
            .contains("PREF_TATAR_SPELL_CHECKER = \"pref_tatar_spell_checker\""))
    }

    @Test
    fun the_guide_opens_the_system_screen_and_falls_back() {
        assertTrue(host.contains("R.string.settings_section_spell_checker"))
        assertTrue(host.contains("R.string.spell_checker_guide_body"))
        assertTrue(host.contains(
            """SPELL_CHECKER_SETTINGS_CLASS =
                "com.android.settings.Settings\${'$'}SpellCheckersSettingsActivity"""))
        val opener = body("private fun openSystemSpellCheckerSettings()")
        assertTrue(opener.contains(
            "ComponentName(SYSTEM_SETTINGS_PACKAGE, SPELL_CHECKER_SETTINGS_CLASS)"))
        assertTrue(opener.contains("android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS"))
        assertTrue(opener.contains("R.string.spell_checker_settings_unavailable"))
        assertTrue("both entry points are tried in order",
            opener.indexOf("direct") < opener.indexOf("fallback"))
    }

    @Test
    fun the_selection_read_falls_back_instead_of_throwing() {
        assertTrue(selection.contains("""SELECTED_SPELL_CHECKER = "selected_spell_checker""""))
        assertTrue(selection.contains("""SPELL_CHECKER_ENABLED = "spell_checker_enabled""""))
        assertTrue(selection.contains(
            "ComponentName(context, TatarSpellCheckerService::class.java)"))
        assertTrue("both secure-setting reads are guarded", selection.contains("catch (e: Exception)"))
    }

    @Test
    fun the_strings_speak_all_three_languages() {
        val names = listOf("spell_checker_service_name", "spell_checker_subtype_tatar",
            "spell_checker_subtype_russian", "spell_checker_subtype_english",
            "spell_checker_settings_title", "settings_section_spell_checker",
            "spell_checker_tatar_switch", "spell_checker_tatar_switch_summary",
            "spell_checker_languages_note", "spell_checker_section_system",
            "spell_checker_status_selected", "spell_checker_status_not_selected",
            "spell_checker_open_system_settings", "spell_checker_settings_unavailable",
            "spell_checker_guide_body")
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val text = source("res/$folder/strings.xml")
            for (name in names) {
                assertTrue("$folder is missing $name", text.contains("name=\"$name\""))
            }
        }
    }
}
