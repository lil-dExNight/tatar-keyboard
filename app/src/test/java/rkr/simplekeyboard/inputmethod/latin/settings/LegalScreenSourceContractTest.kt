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
 * The "Legal information" screen holds the two public documents of the app (privacy policy and
 * license, opened in the browser) and is the single legal row of the root screen. The corpus
 * attribution ships as `NOTICE.txt` next to the bundled dictionaries — no data-sources screen
 * and no data-sources strings exist any more, and this suite pins both halves of that.
 * The screen is an `Activity`, so it is pinned by source (as in
 * `PersonalQuarantineScreenSourceContractTest`).
 */
class LegalScreenSourceContractTest {

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
        bodyOf(host, "private fun buildLegalScreen()", "\n    private fun ")
    }

    @Test
    fun the_screen_is_the_single_legal_row_of_the_root_screen() {
        val root = bodyOf(host, "private fun buildRootScreen()", "private fun buildLegalScreen()")
        assertTrue("root screen must offer a row that opens the legal screen",
            root.contains("navigateTo(Screen.LEGAL)"))
        assertTrue("the row needs its own title string",
            root.contains("R.string.settings_screen_legal"))
        assertTrue("the screen must be dispatched in showScreen",
            host.contains("Screen.LEGAL -> buildLegalScreen()"))
        assertFalse("the documents open from the legal screen, not from the root one",
            root.contains("R.string.privacy_policy"))
    }

    @Test
    fun the_screen_carries_the_two_documents() {
        assertTrue(screenBody.contains("R.string.privacy_policy"))
        assertTrue(screenBody.contains("R.string.privacy_policy_url"))
        assertTrue(screenBody.contains("R.string.license"))
        assertTrue(screenBody.contains("R.string.license_url"))
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
            assertTrue("settings_screen_legal missing from $folder",
                File(sourceRoot(), "res/$folder/strings.xml").readText()
                    .contains("name=\"settings_screen_legal\""))
        }
    }
}
