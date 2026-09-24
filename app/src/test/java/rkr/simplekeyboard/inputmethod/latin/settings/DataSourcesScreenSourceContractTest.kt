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

/**
 * Mission `tt-corpus-os`, decision 2: the attribution the data sources ask for is IN the product,
 * and stays there.
 *
 * The operator decided on 2026-08-24 to use word frequencies derived from OpenSubtitles even though
 * that collection publishes no licence grant. The one thing the collection does ask of anyone who
 * uses it is a link back to `opensubtitles.org`. A link that lives only in a mission report is not
 * in the product, so it lives on a settings screen — and a settings screen is an `Activity`, which
 * needs a device. So it is pinned by source, in the style this class is already pinned by elsewhere
 * (`PersonalQuarantineScreenSourceContractTest`).
 *
 * Three things are being held still, and each one is a thing a later edit could plausibly undo:
 *
 * 1. The screen exists and is reachable — a screen nobody can open attributes nothing.
 * 2. Every source named on it carries its link, `opensubtitles.org` in particular.
 * 3. The screen says where the data actually is. Until 1.9.0 the words sat in acceptance queues and
 *    no packed asset contained one of them, and the test held the two collections OUT of the
 *    shipped section. On 2026-08-24 mission `tt-dict-accept` accepted 27 134 Russian and 226 Tatar
 *    forms by machine rule and packed them, so the assertion is turned around rather than deleted:
 *    all three collections must now stand under the shipped header, and there must be no header
 *    left claiming anything is merely prepared. `docs/DICT-ACCEPT.md` records the rule and counts.
 */
class DataSourcesScreenSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private val host by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt").readText()
    }
    private val appNames by lazy { File(sourceRoot(), "res/values/strings-appname.xml").readText() }
    private val dictionaryNotice by lazy {
        File(sourceRoot(), "assets/dictionaries/NOTICE.txt").readText()
    }

    private fun bodyOf(source: String, from: String, to: String) =
        source.substringAfter(from).substringBefore(to)

    /**
     * Only the body of `buildDataSourcesScreen`. The bound used to be the next KDoc block, which
     * ran on through several later screens; that was harmless while every assertion was a
     * `contains`, and stops being harmless the moment one of them is an `assertFalse`. The next
     * `private fun` is the real end of the function.
     */
    private val screenBody by lazy {
        bodyOf(host, "private fun buildDataSourcesScreen()", "\n    private fun ")
    }

    @Test
    fun the_screen_is_reachable_from_the_root_screen() {
        val root = bodyOf(host, "private fun buildRootScreen()", "private fun buildDataSourcesScreen")
        assertTrue("root screen must offer a row that opens the data sources screen",
            root.contains("navigateTo(Screen.DATA_SOURCES)"))
        assertTrue("the row needs its own title string",
            root.contains("R.string.settings_screen_data_sources"))
        assertTrue("the screen must be dispatched in showScreen",
            host.contains("Screen.DATA_SOURCES -> buildDataSourcesScreen()"))
    }

    @Test
    fun every_named_source_carries_its_link() {
        for (name in listOf("leipzig", "tatoeba", "opensubtitles")) {
            assertTrue("the $name row must open its own URL",
                screenBody.contains("R.string.data_sources_${name}_url"))
            assertTrue("the $name row must have a title",
                screenBody.contains("R.string.data_sources_${name}_title"))
        }
    }

    /**
     * The link OpenSubtitles asks for, spelled out. This is the whole of what that collection
     * requests in return for its data, and the reason the row exists at all; a rename of the string
     * key would still leave the URL, and dropping the URL fails here rather than quietly.
     */
    @Test
    fun the_opensubtitles_link_is_present_in_the_product() {
        // The scheme follows the resource: the 2026-09-24 wave moved the link to https, and the
        // pin holds the product to exactly what it ships.
        assertTrue("opensubtitles.org must be a real URL resource",
            appNames.contains("https://www.opensubtitles.org/"))
        assertTrue("NOTICE.txt next to the dictionaries must carry the same link",
            dictionaryNotice.contains("http://www.opensubtitles.org/"))
        assertTrue("NOTICE.txt must say plainly that there is no licence grant, not soften it",
            dictionaryNotice.contains("NO license grant"))
    }

    /**
     * Since 1.9.0 all three collections are inside the packed dictionaries, so the screen has to
     * say that and nothing weaker. The check stays two-sided, with both sides reversed: every
     * source must sit under the shipped header, and the "prepared, not in the app yet" header must
     * be gone entirely — a header that survives with nothing under it is the same false claim in
     * a quieter form.
     */
    @Test
    fun every_packed_source_is_shown_as_shipped() {
        val shipped = screenBody.substringAfter("R.string.data_sources_in_app")
        for (name in listOf("leipzig", "tatoeba", "opensubtitles")) {
            assertTrue("$name is packed since 1.9.0 and must sit under the shipped header",
                shipped.contains("data_sources_${name}_title"))
        }
        assertFalse("nothing is merely prepared any more; the header must be gone from the screen",
            screenBody.contains("data_sources_prepared"))
        for (folder in listOf("values", "values-ru", "values-tt")) {
            assertFalse("the unused header string must be gone from $folder too",
                File(sourceRoot(), "res/$folder/strings.xml").readText()
                    .contains("name=\"data_sources_prepared\""))
        }
        assertTrue("the dictionary NOTICE must agree with the screen",
            dictionaryNotice.contains("PACKED since 1.9.0"))
        assertFalse("and must no longer say the words are only queued",
            dictionaryNotice.contains("NOT yet packed"))
    }

    /** Every new visible string exists in all three of the locales this project ships itself. */
    @Test
    fun the_new_strings_speak_all_three_languages() {
        val keys = listOf(
            "settings_screen_data_sources", "data_sources_intro", "data_sources_in_app",
            "data_sources_leipzig_summary",
            "data_sources_tatoeba_summary", "data_sources_opensubtitles_summary")
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val text = File(sourceRoot(), "res/$folder/strings.xml").readText()
            for (key in keys) {
                assertTrue("$key missing from $folder", text.contains("name=\"$key\""))
            }
        }
        assertEquals("no source name or URL may be sent for translation", 6,
            Regex("name=\"data_sources_[a-z]+_(title|url)\" translatable=\"false\"")
                .findAll(appNames).count())
    }
}
