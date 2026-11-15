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

package rkr.simplekeyboard.inputmethod.latin.utils

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App screens and keyboard chrome follow the FIRST SUPPORTED language of the whole system locale
 * list, not just its first entry: [de, tt] is a Tatar-speaking user and must get the Tatar UI.
 * The pure mapping is tested directly; Android wrapping is pinned at source level because a JVM
 * has no real Resources.
 */
class AppLocaleTest {

    @Test
    fun aSupportedFirstEntryKeepsTheSystemResolution() {
        for (list in listOf(listOf("tt"), listOf("ru"), listOf("en"),
                listOf("tt", "de"), listOf("ru", "tt"), listOf("en", "ru"))) {
            assertNull("$list resolves to shipped resources on its own",
                AppLocale.forcedLocaleFor(list))
        }
    }

    @Test
    fun aSupportedLanguageLaterInTheListIsForced() {
        assertEquals("tt", AppLocale.forcedLocaleFor(listOf("de", "tt"))?.language)
        assertEquals("tt", AppLocale.forcedLocaleFor(listOf("de", "fr", "tt", "ru"))?.language)
        assertEquals("ru", AppLocale.forcedLocaleFor(listOf("de", "ru", "tt"))?.language)
        assertEquals("en", AppLocale.forcedLocaleFor(listOf("tr", "zh", "en"))?.language)
        // The earliest supported entry wins, not the last and not a fixed preference order.
        assertEquals("ru", AppLocale.forcedLocaleFor(listOf("de", "ru", "tt", "en"))?.language)
        assertEquals("tt", AppLocale.forcedLocaleFor(listOf("de", "tt", "ru", "en"))?.language)
    }

    @Test
    fun anUnsupportedListFallsBackToEnglish() {
        for (list in listOf(listOf("de"), listOf("de", "tr", "zh"), listOf(""), emptyList())) {
            assertEquals("$list must land on English",
                "en", AppLocale.forcedLocaleFor(list)?.language)
        }
    }

    @Test
    fun theFirstSupportedLanguageScansTheWholeListInOrder() {
        assertEquals("tt", AppLocale.firstSupportedLanguage(listOf("de", "tt", "ru")))
        assertEquals("en", AppLocale.firstSupportedLanguage(listOf("en", "tt")))
        assertNull(AppLocale.firstSupportedLanguage(listOf("de", "tr")))
        assertNull(AppLocale.firstSupportedLanguage(emptyList()))
    }

    @Test
    fun theForcedLocaleCarriesOnlyTheLanguage() {
        // createConfigurationContext must not invent a country the user never chose.
        assertEquals(Locale("tt"), AppLocale.forcedLocaleFor(listOf("de", "tt")))
    }

    @Test
    fun theWrapScansTheWholeSystemLocaleList() {
        val source = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/utils/AppLocale.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/utils/AppLocale.kt",
        )
        assertFalse("the wrap must not stop at the first system locale",
            source.contains("locales[0]"))
        assertTrue("the wrap must walk every entry of the system locale list",
            source.contains("0 until locales.size()"))
    }

    @Test
    fun bothAppScreensApplyTheWrapInAttachBaseContext() {
        val setup = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/setup/SetupActivity.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/setup/SetupActivity.kt",
        )
        val settings = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt",
        )
        for ((name, source) in listOf("SetupActivity" to setup, "SettingsHostActivity" to settings)) {
            assertTrue("$name must wrap its base context through AppLocale",
                source.contains("override fun attachBaseContext(newBase: Context)"))
            assertTrue("$name must wrap its base context through AppLocale",
                source.contains("super.attachBaseContext(AppLocale.wrap(newBase))"))
        }
        // The forwarder SettingsActivity renders nothing (no setContentView), so it
        // deliberately has no wrap; the manifest must not grow new visible screens
        // without the policy. Pin the visible set.
        val manifest = read(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
        )
        assertTrue(manifest.contains(".latin.setup.SetupActivity"))
        assertTrue(manifest.contains(".latin.settings.SettingsActivity"))
        assertTrue(manifest.contains(".latin.settings.SettingsHostActivity"))
    }

    private fun read(vararg paths: String): String =
        paths.map(::File).firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate ${paths.first()}")
}
