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
 * Every preference read in `Settings.java` and in the settings screens (`SettingsValues.java`,
 * `SettingsRows.kt`, `SettingsKeyPressScreen.kt`, `SettingsHostActivity.kt`) goes through the
 * tolerant readers (a wrong-typed stored value — say from a hand-edited backup file — is dropped
 * and the default wins, instead of crash-looping the service at startup), and the theme read in
 * `KeyboardTheme.java` catches the same `ClassCastException`. Source-level contract: there is no
 * Robolectric in this project, so the wiring is pinned at the source level like the other
 * contract tests here.
 */
class TolerantPreferenceReadsSourceContractTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/java from ${File(".").absolutePath}")
        val file = File(root, relative)
        assertTrue("$relative missing", file.isFile)
        return file.readText()
    }

    @Test
    fun settingsReadsNeverCallTheRawGetters() {
        val text = sourceOf("rkr/simplekeyboard/inputmethod/latin/settings/Settings.java")
        // The raw getters appear exactly once each: inside their own tolerant helper.
        for (raw in listOf("getBoolean", "getInt", "getFloat", "getString", "getStringSet")) {
            val count = Regex(Regex.escape("prefs.$raw(")).findAll(text).count()
            assertTrue("prefs.$raw( must appear exactly once, inside its tolerant helper ($count)",
                count == 1)
        }
        for (helper in listOf("readBooleanTolerant", "readIntTolerant", "readFloatTolerant",
                "readStringTolerant", "readStringSetTolerant")) {
            assertTrue("$helper missing from Settings.java", text.contains("$helper(final SharedPreferences prefs"))
            // every tolerant reader drops the bad key on a type mismatch
            val body = text.substringAfter("$helper(final SharedPreferences prefs")
                .substringBefore("static ")
            assertTrue("$helper must drop the wrong-typed key", body.contains("catch (ClassCastException"))
            assertTrue("$helper must remove the bad key", body.contains("prefs.edit().remove(key)"))
        }
    }

    @Test
    fun theSettingsScreensNeverCallTheRawGetters() {
        for (file in listOf("SettingsValues.java", "SettingsRows.kt",
                "SettingsKeyPressScreen.kt", "SettingsHostActivity.kt")) {
            val text = sourceOf("rkr/simplekeyboard/inputmethod/latin/settings/$file")
            for (raw in listOf("getBoolean", "getInt", "getFloat", "getString", "getStringSet")) {
                val count = Regex(Regex.escape("prefs.$raw(")).findAll(text).count()
                assertTrue("$file: prefs.$raw( must not appear, reads go through the tolerant readers" +
                    " ($count)", count == 0)
            }
        }
    }

    @Test
    fun theThemeReadCatchesTheTypeMismatch() {
        val text = sourceOf("rkr/simplekeyboard/inputmethod/keyboard/KeyboardTheme.java")
        assertTrue(text.contains("catch (final ClassCastException e)"))
    }
}
