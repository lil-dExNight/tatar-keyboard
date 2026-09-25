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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-locale policy of 2026-09-25, pinned: the app's own screens (Setup,
 * Settings) default to TATAR — system tt → tt, system ru → ru, anything else
 * (English included) → tt. The mapping half is pure and tested directly; the
 * Android half (createConfigurationContext) is pinned at source level like the
 * other contract tests, because a JVM has no real Resources.
 */
class AppLocaleTest {

    @Test
    fun russianAndTatarSystemsKeepTheirOwnResolution() {
        assertNull("ru resolves to values-ru on its own", AppLocale.forcedLocaleFor("ru"))
        assertNull("tt resolves to values-tt on its own", AppLocale.forcedLocaleFor("tt"))
    }

    @Test
    fun everyOtherSystemLanguageIsForcedToTatar() {
        // English included: the resource fallback (values/) being English does NOT make
        // English the app default — the operator's call is a Tatar default.
        for (language in listOf("en", "de", "tr", "zh", "ar", "")) {
            assertEquals("system '$language' must land on Tatar",
                "tt", AppLocale.forcedLocaleFor(language)?.language)
        }
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
