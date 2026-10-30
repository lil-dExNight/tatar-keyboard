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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The globe keeps its tap action and exposes keyboard actions from its long-press panel. */
class GlobeMenuLayoutTest {
    private companion object {
        const val LATIN = "http://schemas.android.com/apk/res-auto"
        val entries = listOf(
            "change_keyboard" to "key_input_method_picker",
            "globe_menu_emoji" to "key_emoji",
            "one_handed_mode_left" to "key_one_handed_left",
            "one_handed_mode_right" to "key_one_handed_right",
            "one_handed_mode_full" to "key_one_handed_full",
        )
    }

    @Test
    fun phoneAndTabletMenusHaveTheSameOrderedActions() {
        for (dir in listOf("xml", "xml-sw600dp")) {
            val style = languageSwitchStyle(dir)
            assertEquals("!icon/language_switch_key|!code/key_language_switch",
                style.getAttributeNS(LATIN, "keySpec"))
            assertTrue(style.getAttributeNS(LATIN, "keyActionFlags").contains("enableLongPress"))
            val specs = style.getAttributeNS(LATIN, "moreKeys").split(",")
            assertEquals("!fixedColumnOrder!3", specs[0])
            assertEquals("!hasLabels!", specs[1])
            assertEquals(entries.map { (label, code) -> "!string/$label|!code/$code" },
                specs.drop(2))
        }
    }

    @Test
    fun everyVisibleMenuLabelExistsInEveryShippedLocale() {
        for (valuesDir in listOf("values", "values-ru", "values-tt")) {
            val strings = File(File(resRoot(), valuesDir), "strings.xml").readText()
            for ((label, _) in entries) {
                assertTrue("$valuesDir lacks $label",
                    strings.contains("<string name=\"$label\">"))
            }
        }
    }

    @Test
    fun globeLongPressReachesTheKeyboardOwnedPanel() {
        val tracker = source("keyboard/PointerTracker.java")
        val longPress = tracker.substringAfter("public void onLongPressed()")
            .substringBefore("private void cancelKeyTracking()")
        val pickerBranch = longPress.substringAfter("if (code == Constants.CODE_SPACE)")
            .substringBefore("setReleasedKeyGraphics")
        assertFalse(pickerBranch.contains("CODE_LANGUAGE_SWITCH"))
        assertTrue(longPress.contains("sDrawingProxy.showMoreKeysKeyboard(key, this)"))
    }

    @Test
    fun menuActionsUseTheExistingPickerAndLayoutPaths() {
        val ime = source("latin/LatinIME.java")
        val input = ime.substringAfter("public void onCodeInput(final int codePoint")
            .substringBefore("public void onEvent(final Event event)")
        assertTrue(input.contains("case Constants.CODE_INPUT_METHOD_PICKER:"))
        assertTrue(input.contains("showInputMethodPicker();"))
        assertTrue(input.contains("setOneHandedSide(OneHandedMode.SIDE_LEFT);"))
        assertTrue(input.contains("setOneHandedSide(OneHandedMode.SIDE_RIGHT);"))
        assertTrue(input.contains("setOneHandedSide(OneHandedMode.SIDE_OFF);"))
        assertFalse(input.contains("case Constants.CODE_LANGUAGE_SWITCH:"))

        val picker = ime.substringAfter("private boolean showInputMethodPicker()")
            .substringBefore("public Locale getCurrentLayoutLocale()")
        assertTrue(picker.contains("mRichImm.showSubtypePicker(this,"))
        val sideChange = ime.substringAfter("private void setOneHandedSide(final int side)")
            .substringBefore("public void onDockExitHandleLongPress()")
        assertTrue(sideChange.contains("manager.getApplicationRestrictions()"))
        assertTrue(sideChange.contains("restrictions.containsKey(Settings.PREF_ONE_HANDED_SIDE)"))
        val inputLogic = source("latin/inputlogic/InputLogic.java")
        assertTrue(inputLogic.contains("case Constants.CODE_LANGUAGE_SWITCH:"))
        assertTrue(inputLogic.contains("handleLanguageSwitchKey();"))
    }

    private fun languageSwitchStyle(dir: String): Element {
        val file = File(File(resRoot(), dir), "key_styles_common.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val styles = doc.getElementsByTagName("key-style")
        for (index in 0 until styles.length) {
            val style = styles.item(index) as Element
            if (style.getAttributeNS(LATIN, "styleName") == "languageSwitchKeyStyle") {
                return style
            }
        }
        error("languageSwitchKeyStyle missing from ${file.path}")
    }

    private fun resRoot(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/res")
    }

    private fun source(relativePath: String): String {
        val relative = "rkr/simplekeyboard/inputmethod/$relativePath"
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate $relative")
    }
}
