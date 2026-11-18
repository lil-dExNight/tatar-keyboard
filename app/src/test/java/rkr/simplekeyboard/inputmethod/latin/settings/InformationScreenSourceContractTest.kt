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
 * The Information screen combines backup actions with the two public documents (privacy policy
 * and license). It has one root row. The corpus attribution ships as `NOTICE.txt` next to the
 * bundled dictionaries — no data-sources screen and no data-sources strings exist any more.
 * The screen is an `Activity`, so it is pinned by source.
 */
class InformationScreenSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private val host by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt").readText()
    }

    private fun bodyOf(source: String, from: String, to: String) =
        source.substringAfter(from).substringBefore(to)

    private val screenBody by lazy {
        bodyOf(host, "private fun buildInformationScreen()", "\n    private fun ")
    }

    @Test
    fun the_screen_is_the_single_information_row_of_the_root_screen() {
        val root = bodyOf(host, "private fun buildRootScreen()",
            "private fun buildInformationScreen()")
        assertTrue("root screen must offer a row that opens Information",
            root.contains("navigateTo(Screen.INFORMATION)"))
        assertTrue("the row needs its own title string",
            root.contains("R.string.settings_screen_information"))
        assertTrue("the screen must be dispatched in showScreen",
            host.contains("Screen.INFORMATION -> buildInformationScreen()"))
        assertFalse("the documents open from Information, not from the root one",
            root.contains("R.string.privacy_policy"))
        assertFalse(root.contains("Screen.LEGAL") || root.contains("Screen.BACKUP"))
        assertFalse(host.contains("Screen.LEGAL") || host.contains("Screen.BACKUP"))
    }

    @Test
    fun the_screen_carries_backup_and_the_two_documents() {
        for (name in listOf("settings_screen_backup", "backup_contents_note",
                "backup_export_action", "backup_encrypted_export_action", "backup_import_action",
                "settings_screen_legal", "privacy_policy", "privacy_policy_url",
                "license", "license_url")) {
            assertTrue("Information is missing $name", screenBody.contains("R.string.$name"))
        }
    }

    @Test
    fun the_data_sources_screen_and_its_strings_stay_removed() {
        assertFalse(host.contains("buildDataSourcesScreen"))
        assertFalse(host.contains("Screen.DATA_SOURCES"))
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val text = File(sourceRoot(), "res/$folder/strings.xml").readText()
            assertFalse("$folder still carries a data_sources string",
                text.contains("name=\"data_sources_"))
            assertFalse("$folder still carries the data-sources title",
                text.contains("name=\"settings_screen_data_sources\""))
        }
        assertFalse(File(sourceRoot(), "res/values/strings-appname.xml").readText()
            .contains("name=\"data_sources_"))
    }

    /** The screen title exists in all three of the locales this project ships itself. */
    @Test
    fun the_title_speaks_all_three_languages() {
        for (folder in listOf("values", "values-ru", "values-tt")) {
            assertTrue("settings_screen_information missing from $folder",
                File(sourceRoot(), "res/$folder/strings.xml").readText()
                    .contains("name=\"settings_screen_information\""))
        }
    }
}
