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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-09-25 SAFE wave: `readKeyboardColor` used to evaluate
 * `readKeyboardDefaultColor(context)` — a walk over the theme table and the colour resource
 * arrays — on EVERY read, including the common case where the preference holds a value and the
 * default is thrown away. The reader now asks `contains` first: preference set -> the stored
 * value, the default never computed; preference absent -> the theme default.
 *
 * `SharedPreferences` and `Context` do not exist on a plain JVM (no Robolectric here, by
 * design), so the branch structure is pinned at the source level — the same way
 * [AppRestrictionsSourceContractTest] pins the restriction loader.
 */
class KeyboardColorPreferenceTest {

    private fun settingsSource(): String {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/java from ${File(".").absolutePath}")
        val file = File(root, "rkr/simplekeyboard/inputmethod/latin/settings/Settings.java")
        assertTrue("Settings.java missing", file.isFile)
        return file.readText()
    }

    private fun readerBody(text: String) =
        text.substringAfter("public static int readKeyboardColor(")
            .substringBefore("public static void removeKeyboardColor")

    @Test
    fun aStoredColourIsReturnedWithoutComputingTheDefault() {
        val body = readerBody(settingsSource())
        assertTrue(
            "the reader must consult contains() before touching the stored value",
            body.contains("prefs.contains(PREF_KEYBOARD_COLOR)"),
        )
        assertTrue(
            "a present preference yields the stored int",
            body.contains("prefs.getInt(PREF_KEYBOARD_COLOR, 0)"),
        )
        val contains = body.indexOf("prefs.contains(PREF_KEYBOARD_COLOR)")
        val get = body.indexOf("prefs.getInt(PREF_KEYBOARD_COLOR, 0)")
        val default = body.indexOf("return readKeyboardDefaultColor(context);")
        assertTrue("contains() must gate the getInt()", contains in 0 until get)
        assertTrue("the stored-value branch must precede the default walk", get in 0 until default)
    }

    @Test
    fun anAbsentColourStillFallsBackToTheThemeDefault() {
        val body = readerBody(settingsSource())
        assertTrue(
            "the absent case must keep returning the theme default",
            body.contains("return readKeyboardDefaultColor(context);"),
        )
    }
}
