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

package rkr.simplekeyboard.inputmethod.latin.lab

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fifth-row experiment contract: arm A is the shipped order (and the default), arms B and C
 * carry the same six letters in their pinned orders, and the variant keyboard files are wired to
 * their rows. The orders are read from the layout XML, so a hand edit of an arm fails here.
 */
class FifthRowArmTest {

    @Test
    fun defaultArmIsTheShippedOrder() {
        assertEquals(FifthRowArm.ARM_A, FifthRowArm.DEFAULT)
        assertEquals(FifthRowArm.ARM_A, FifthRowArm.normalize(-1))
        assertEquals(FifthRowArm.ARM_A, FifthRowArm.normalize(3))
        assertEquals(FifthRowArm.ARM_B, FifthRowArm.normalize(1))
        assertEquals(FifthRowArm.ARM_C, FifthRowArm.normalize(2))
        val settings = read("java/rkr/simplekeyboard/inputmethod/latin/settings/Settings.java")
        assertTrue("the stored pref resolves through the shipped default",
            settings.contains("prefs.getInt(PREF_FIFTH_ROW_ARM, FifthRowArm.DEFAULT)"))
    }

    @Test
    fun armARowKeepsTheShippedOrder() {
        assertEquals(SHIPPED_ORDER, extraRowLetters("rowkeys_tatar_extra.xml"))
    }

    @Test
    fun armBRowIsTheFrequencyOrder() {
        assertEquals(
            listOf(0x04D9, 0x04AF, 0x04A3, 0x04E9, 0x0497, 0x04BB),
            extraRowLetters("rowkeys_tatar_extra_b.xml"),
        )
    }

    @Test
    fun armCRowIsTheIncumbentDesktopScanOrder() {
        assertEquals(
            listOf(0x04BB, 0x04E9, 0x04D9, 0x04AF, 0x04A3, 0x0497),
            extraRowLetters("rowkeys_tatar_extra_c.xml"),
        )
    }

    @Test
    fun allArmsCarryTheSameSixLetters() {
        val sets = listOf("rowkeys_tatar_extra.xml", "rowkeys_tatar_extra_b.xml",
            "rowkeys_tatar_extra_c.xml").map { extraRowLetters(it).toSet() }
        assertTrue(sets.all { it == sets[0] })
        assertEquals(6, sets[0].size)
    }

    @Test
    fun variantKeyboardsWireTheirOwnRows() {
        assertTrue(readXml("kbd_tatar.xml").contains("@xml/rows_tatar\""))
        assertTrue(readXml("kbd_tatar_b.xml").contains("@xml/rows_tatar_b\""))
        assertTrue(readXml("kbd_tatar_c.xml").contains("@xml/rows_tatar_c\""))
        assertTrue(readXml("rows_tatar.xml").contains("@xml/rowkeys_tatar_extra\""))
        assertTrue(readXml("rows_tatar_b.xml").contains("@xml/rowkeys_tatar_extra_b\""))
        assertTrue(readXml("rows_tatar_c.xml").contains("@xml/rowkeys_tatar_extra_c\""))
    }

    @Test
    fun armSelectionIsSeamedIntoTheLayoutSetBuilder() {
        val layoutSet = read("java/rkr/simplekeyboard/inputmethod/keyboard/KeyboardLayoutSet.java")
        assertTrue("the alphabet element resolves through the arm",
            layoutSet.contains("FifthRowArm.alphabetKeyboardXmlId("))
    }

    @Test
    fun nonTatarLayoutSetsAndTheShippedArmKeepTheirKeyboard() {
        assertEquals(42, FifthRowArm.alphabetKeyboardXmlId(
            "keyboard_layout_set_qwerty", 42, FifthRowArm.ARM_B))
        assertEquals(42, FifthRowArm.alphabetKeyboardXmlId(
            "keyboard_layout_set_russian", 42, FifthRowArm.ARM_C))
        assertEquals(42, FifthRowArm.alphabetKeyboardXmlId(
            "keyboard_layout_set_tatar", 42, FifthRowArm.ARM_A))
    }

    /** The `latin:keySpec` code points of the file's Key elements, in document order. */
    private fun extraRowLetters(fileName: String): List<Int> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(xmlFile(fileName))
        val keys = document.getElementsByTagName("Key")
        return (0 until keys.length).map { index ->
            val keySpec = keys.item(index).attributes.getNamedItem("latin:keySpec").nodeValue
            assertEquals("one code point per keySpec in $fileName", 1, keySpec.codePointCount(0, keySpec.length))
            keySpec.codePointAt(0)
        }
    }

    private fun readXml(fileName: String): String = xmlFile(fileName).readText()

    private fun xmlFile(fileName: String): File {
        for (candidate in listOf("src/main/res/xml/$fileName", "app/src/main/res/xml/$fileName")) {
            val file = File(candidate)
            if (file.isFile) return file
        }
        error("resource not found: $fileName")
    }

    private fun read(path: String): String {
        for (candidate in listOf("src/main/$path", "app/src/main/$path")) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        error("source not found: $path")
    }

    private companion object {
        // The shipped fifth row: schwa, barred o, straight u, zhe, en, shha.
        val SHIPPED_ORDER = listOf(0x04D9, 0x04E9, 0x04AF, 0x0497, 0x04A3, 0x04BB)
    }
}
