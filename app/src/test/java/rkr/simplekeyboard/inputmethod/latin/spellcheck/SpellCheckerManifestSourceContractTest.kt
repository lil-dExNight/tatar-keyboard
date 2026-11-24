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

package rkr.simplekeyboard.inputmethod.latin.spellcheck

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The spell checker is the second exported entry point of the app and the only component the
 * platform binds without the user opening it, so its manifest entry, its discovery metadata and
 * its isolation from the keyboard machinery are pinned by source.
 */
class SpellCheckerManifestSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private val manifest by lazy { File(sourceRoot(), "AndroidManifest.xml").readText() }

    private val serviceFile by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/spellcheck/TatarSpellCheckerService.kt")
    }

    private val scsFile by lazy { File(sourceRoot(), "res/xml/spell_checker.xml") }

    private fun startTag(tag: String, marker: String): String {
        val markerAt = manifest.indexOf(marker)
        assertTrue("$tag with $marker is missing from the manifest", markerAt >= 0)
        val open = manifest.lastIndexOf("<$tag", markerAt)
        assertTrue("no <$tag> around $marker", open >= 0)
        val end = manifest.indexOf('>', open)
        assertTrue("unterminated <$tag> around $marker", end > markerAt)
        return manifest.substring(open, end + 1)
    }

    private fun element(tag: String, marker: String): String {
        val start = startTag(tag, marker)
        if (start.endsWith("/>")) return start
        val open = manifest.lastIndexOf("<$tag", manifest.indexOf(marker))
        val close = manifest.indexOf("</$tag>", open)
        assertTrue("$tag with $marker is not closed", close > open)
        return manifest.substring(open, close)
    }

    private fun subtypeElements(): List<Element> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(scsFile).documentElement
        val nodes = root.getElementsByTagName("subtype")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun the_service_is_exported_only_behind_the_text_service_permission() {
        val service = element("service", "latin.spellcheck.TatarSpellCheckerService")
        assertTrue(service.contains(
            """android:permission="android.permission.BIND_TEXT_SERVICE""""))
        assertTrue(service.contains("""android:exported="true""""))
        assertTrue(service.contains(
            """<action android:name="android.service.textservice.SpellCheckerService" />"""))
        assertTrue(service.contains("""android:name="android.view.textservice.scs""""))
        assertTrue(service.contains("""android:resource="@xml/spell_checker""""))
        assertFalse("the spell checker must not request INTERNET",
            manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun every_subtype_carries_both_locale_forms() {
        assertEquals(listOf("tt_RU" to "tt-RU", "ru_RU" to "ru-RU", "en_US" to "en-US"),
            subtypeElements().map {
                it.getAttribute("android:subtypeLocale") to it.getAttribute("android:languageTag")
            })
    }

    @Test
    fun the_metadata_names_an_exported_settings_activity() {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(scsFile).documentElement
        val settingsActivity = root.getAttribute("android:settingsActivity")
        assertEquals("rkr.simplekeyboard.inputmethod.latin.settings.SpellCheckerSettingsActivity",
            settingsActivity)
        val activity = element("activity", "latin.settings.SpellCheckerSettingsActivity")
        assertTrue(activity.contains("""android:exported="true""""))
    }

    @Test
    fun the_session_accepts_every_word_and_feeds_no_typo_mark() {
        val service = serviceFile.readText()
        assertTrue(service.contains("SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY"))
        assertFalse(service.contains("RESULT_ATTR_LOOKS_LIKE_TYPO"))
        assertFalse(service.contains("RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS"))
    }

    @Test
    fun the_service_starts_none_of_the_keyboard_machinery() {
        val wordsFile = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/spellcheck/SpellCheckerWords.kt")
        for (file in listOf(serviceFile, wordsFile)) {
            val source = file.readText()
            for (banned in listOf("LatinIME", "RichInputMethodManager", "KeyboardLayoutSet",
                    "SharedPreferences", "SharedPreferencesCompat", "java.io", "Log.")) {
                assertFalse("${file.name} must not reach $banned", source.contains(banned))
            }
        }
    }
}
