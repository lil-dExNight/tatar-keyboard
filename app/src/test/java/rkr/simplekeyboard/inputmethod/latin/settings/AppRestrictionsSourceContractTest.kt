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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The managed-restrictions loader reads values an administrator supplies, so it must survive a
 * value of the wrong type. `Bundle.getString` returns null both when the key is absent and when it
 * holds something that is not a string, so the color branch must not call `startsWith` on the
 * result unchecked, or an NPE aborts the whole restriction load.
 *
 * The loader takes a `Bundle` and a `SharedPreferences.Editor`, neither of which exists on a plain
 * JVM, so the guard is pinned at the source level — the same way the other settings contracts in
 * this package are. The policy surface is pinned here too: which keys the loader writes values
 * for and which app_restrictions.xml declares.
 */
class AppRestrictionsSourceContractTest {

    private fun settingsSource(): String {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/java from ${File(".").absolutePath}")
        val file = File(root, "rkr/simplekeyboard/inputmethod/latin/settings/Settings.java")
        assertTrue("Settings.java is missing", file.isFile)
        return file.readText()
    }

    private fun restrictionsXml(): String {
        val candidates = listOf(
            File("src/main/res/xml/app_restrictions.xml"),
            File("app/src/main/res/xml/app_restrictions.xml"))
        val file = candidates.firstOrNull(File::isFile)
            ?: error("cannot locate app/src/main/res/xml/app_restrictions.xml " +
                "from ${File(".").absolutePath}")
        return file.readText()
    }

    /** A color restriction of the wrong type must not be dereferenced. */
    @Test
    fun colourRestrictionIsNullCheckedBeforeUse() {
        val text = settingsSource()
        assertTrue(
            "a restricted color must be null-checked before startsWith",
            Regex("""color\s*!=\s*null\s*&&\s*color\.startsWith""").containsMatchIn(text),
        )
        assertTrue(
            "no unconditional color.startsWith may remain",
            !Regex("""\n\s*if\s*\(\s*color\.startsWith""").containsMatchIn(text),
        )
    }

    /**
     * A color that does not parse is dropped rather than thrown: the restriction load must finish
     * even when one value is nonsense, leaving that preference at its default.
     */
    @Test
    fun unparsableColourFallsBackToRemovingThePreference() {
        val text = settingsSource()
        val branch = text.substringAfter("case PREF_KEYBOARD_COLOR:").substringBefore("break;\n" +
            "                    case ")
        assertTrue(
            "the color branch must remove the key, not throw",
            branch.contains("prefsEditor.remove(key)"),
        )
        assertTrue(
            "swallowing NumberFormatException must be explained by a comment",
            Regex("""catch\s*\(NumberFormatException[^)]*\)\s*\{[^}]*//""").containsMatchIn(text),
        )
    }

    /**
     * No restriction branch writes the suggestions pref; a delivered-but-unhandled key can still
     * disable the settings row (details in the assertion messages).
     */
    @Test
    fun noRestrictionKeyMapsToTheSuggestionsMasterSwitch() {
        assertTrue(
            "the literal checked against the XML must be the constant's actual value",
            settingsSource().contains(
                "PREF_TATAR_SUGGESTIONS = \"pref_tatar_suggestions\""),
        )
        assertFalse(
            "app_restrictions.xml must not declare the suggestions pref: a delivered-but-" +
                "unhandled key is still recorded in ACTIVE_RESTRICTIONS and disables the " +
                "settings row while the setup switch keeps writing",
            restrictionsXml().contains("android:key=\"pref_tatar_suggestions\""),
        )
        assertFalse(
            "loadRestrictions must not handle PREF_TATAR_SUGGESTIONS: a handled key would let " +
                "a policy value override the setup switch's write, so that write would need " +
                "the settings row's gate",
            settingsSource().contains("case PREF_TATAR_SUGGESTIONS:"),
        )
    }
}
