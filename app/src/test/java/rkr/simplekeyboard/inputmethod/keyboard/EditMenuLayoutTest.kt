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

package rkr.simplekeyboard.inputmethod.keyboard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The text-editing menu on the Enter key's long press, checked on the keyboard XML alone.
 *
 * `navigateMoreKeysStyle` is declared inside a &lt;switch&gt; keyed on `passwordInput` in both
 * `res/xml/key_styles_enter.xml` and `res/xml-sw600dp/key_styles_enter.xml`: in a password field
 * the style carries no moreKeys (no menu at all, since cut and copy are disabled there by
 * convention), and elsewhere it carries the six menu entries in their panel order. The labels are
 * `!string/` references, so this test also checks they exist in every shipped locale.
 */
class EditMenuLayoutTest {

    private companion object {
        private const val LATIN = "http://schemas.android.com/apk/res-auto"
        private const val STYLE = "navigateMoreKeysStyle"

        /** The menu entries in panel order: label reference, then the code name it fires. */
        private val ENTRIES = listOf(
            "edit_menu_cut" to "key_cut",
            "edit_menu_select_all" to "key_select_all",
            "edit_menu_copy" to "key_copy",
            "edit_menu_paste" to "key_paste_context_menu",
            "edit_menu_cursor_left" to "key_left",
            "edit_menu_cursor_right" to "key_right",
        )
    }

    private fun resRoot(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/res from ${File(".").absolutePath}")
    }

    /**
     * The moreKeys attribute of [STYLE] as [rkr.simplekeyboard.inputmethod.keyboard.internal.KeyboardBuilder]
     * resolves it: the first &lt;case&gt; whose `passwordInput` attribute matches, or the
     * &lt;default&gt;.
     */
    private fun moreKeysOf(dir: String, passwordInput: Boolean): String? {
        val file = File(File(resRoot(), dir), "key_styles_enter.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val styles = doc.getElementsByTagName("key-style")
        for (i in 0 until styles.length) {
            val style = styles.item(i) as Element
            if (style.getAttributeNS(LATIN, "styleName") != STYLE) continue
            val case = style.parentNode
            if (case is Element && case.tagName == "case") {
                val attr = case.getAttributeNS(LATIN, "passwordInput")
                if (attr.isNotEmpty() && attr.toBoolean() != passwordInput) continue
            } else if (case is Element && case.tagName == "default") {
                if (passwordInput) continue
            }
            return if (style.hasAttributeNS(LATIN, "moreKeys"))
                style.getAttributeNS(LATIN, "moreKeys") else null
        }
        error("no $STYLE in ${file.path}")
    }

    @Test
    fun passwordFieldsGetNoEditingMenu() {
        for (dir in listOf("xml", "xml-sw600dp")) {
            assertNull("$dir: a password field keeps the Enter long press free of the menu",
                moreKeysOf(dir, passwordInput = true))
        }
    }

    @Test
    fun theMenuCarriesTheSixEntriesInOrder() {
        for (dir in listOf("xml", "xml-sw600dp")) {
            val moreKeys = moreKeysOf(dir, passwordInput = false)
                ?: error("$dir: the Enter long press lost its menu")
            val specs = moreKeys.split(",").filter { it.isNotEmpty() }
            assertTrue("$dir: the menu keys stay in a fixed three-column order",
                specs.first() == "!fixedColumnOrder!3" && specs[1] == "!hasLabels!")
            val entries = specs.drop(2)
            assertEquals("$dir: entry count", ENTRIES.size, entries.size)
            for ((index, expected) in ENTRIES.withIndex()) {
                assertEquals("$dir: entry $index",
                    "!string/${expected.first}|!code/${expected.second}", entries[index])
            }
        }
    }

    @Test
    fun everyMenuLabelExistsInEveryShippedLocale() {
        for (valuesDir in listOf("values", "values-ru", "values-tt")) {
            val stringsFile = File(File(resRoot(), valuesDir), "strings.xml")
            val text = stringsFile.readText()
            for ((label, _) in ENTRIES) {
                assertTrue("$valuesDir/strings.xml lacks $label",
                    text.contains("<string name=\"$label\">"))
            }
        }
    }

    @Test
    fun theKeyboardTextsResolveThroughTheAppLocalePolicy() {
        // The keyboard's !string/ texts (this menu's labels included) resolve against a context
        // wrapped by AppLocale: Tatar unless the system already speaks tt/ru, like the app
        // screens. Both KeyboardTextsSet feeds carry the wrap.
        val builder = File(projectRoot(), "app/src/main/java/rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardBuilder.java").readText()
        val switcher = File(projectRoot(), "app/src/main/java/rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java").readText()
        assertTrue(builder.contains("mTextsSet.setLocale(params.mId.getLocale(), AppLocale.INSTANCE.wrap(mContext))"))
        assertTrue(switcher.contains("AppLocale.INSTANCE.wrap(mThemeContext)"))
    }
}

private fun projectRoot(): File {
    val candidates = listOf(File(""), File(".."))
    return candidates.firstOrNull { File(it, "app/src/main/res").isDirectory }
        ?: error("cannot locate the project root from ${File(".").absolutePath}")
}
