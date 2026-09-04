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

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The managed-restrictions loader reads values an administrator supplies, so it must survive a
 * value of the wrong type. `Bundle.getString` returns null both when the key is absent and when it
 * holds something that is not a string; the colour branch called `startsWith` on the result
 * straight away and took the whole restriction load down with an NPE.
 *
 * The loader takes a `Bundle` and a `SharedPreferences.Editor`, neither of which exists on a plain
 * JVM, so the guard is pinned at the source level — the same way the other settings contracts in
 * this package are.
 */
class AppRestrictionsSourceContractTest {

    private fun settingsSource(): String {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/java from ${File(".").absolutePath}")
        val file = File(root, "rkr/simplekeyboard/inputmethod/latin/settings/Settings.java")
        assertTrue("нет Settings.java", file.isFile)
        return file.readText()
    }

    /** A colour restriction of the wrong type must not be dereferenced. */
    @Test
    fun colourRestrictionIsNullCheckedBeforeUse() {
        val text = settingsSource()
        assertTrue(
            "цвет из ограничений обязан проверяться на null до startsWith",
            Regex("""color\s*!=\s*null\s*&&\s*color\.startsWith""").containsMatchIn(text),
        )
        assertTrue(
            "не должно остаться безусловного color.startsWith",
            !Regex("""\n\s*if\s*\(\s*color\.startsWith""").containsMatchIn(text),
        )
    }

    /**
     * A colour that does not parse is dropped rather than thrown: the restriction load must finish
     * even when one value is nonsense, leaving that preference at its default.
     */
    @Test
    fun unparsableColourFallsBackToRemovingThePreference() {
        val text = settingsSource()
        val branch = text.substringAfter("case PREF_KEYBOARD_COLOR:").substringBefore("break;\n" +
            "                    case ")
        assertTrue(
            "ветка цвета обязана снимать ключ, а не бросать исключение",
            branch.contains("prefsEditor.remove(key)"),
        )
        assertTrue(
            "проглатывание NumberFormatException обязано быть объяснено комментарием",
            Regex("""catch\s*\(NumberFormatException[^)]*\)\s*\{[^}]*//""").containsMatchIn(text),
        )
    }
}
