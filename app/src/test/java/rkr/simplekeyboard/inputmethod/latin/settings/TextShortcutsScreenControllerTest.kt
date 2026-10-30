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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TextShortcuts
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.TextShortcutFilter

class TextShortcutsScreenControllerTest {

    private val saved = TextShortcuts.EMPTY.upsert("әй", "Әйдә, киләбез")

    @Test
    fun aNewPairCanBeWritten() {
        assertEquals(TextShortcutSaveResult.WRITE_STARTED,
            textShortcutSaveDecision(saved, "бб", "Бик булды", replaceExisting = false))
    }

    @Test
    fun aDifferentExpansionNeedsConfirmationBeforeAnyWrite() {
        assertEquals(TextShortcutSaveResult.CONFIRM_REPLACEMENT,
            textShortcutSaveDecision(saved, "әй", "Башка текст", replaceExisting = false))
        assertEquals(TextShortcutSaveResult.WRITE_STARTED,
            textShortcutSaveDecision(saved, "әй", "Башка текст", replaceExisting = true))
    }

    @Test
    fun anUnchangedPairPerformsNoWrite() {
        assertEquals(TextShortcutSaveResult.NO_CHANGE,
            textShortcutSaveDecision(saved, "әй", "Әйдә, киләбез", replaceExisting = false))
        assertEquals(TextShortcutSaveResult.NO_CHANGE,
            textShortcutSaveDecision(saved, "әй", "Әйдә, киләбез", replaceExisting = true))
    }

    @Test
    fun trimAndNfcEquivalentDuplicatesAreDetected() {
        val decomposedAndPadded = "  әй "
        val normalized = TextShortcutFilter.acceptedShortcut(decomposedAndPadded)!!
        assertEquals("әй", normalized)
        assertEquals(TextShortcutSaveResult.CONFIRM_REPLACEMENT,
            textShortcutSaveDecision(saved, normalized, "Башка текст", replaceExisting = false))
        assertEquals(TextShortcutSaveResult.NO_CHANGE,
            textShortcutSaveDecision(saved, normalized, "Әйдә, киләбез", replaceExisting = false))
    }

    @Test
    fun rowTapOpensAPrefilledEditorAndDeletionStaysConfirmed() {
        val host = source("SettingsHostActivity.kt")
        val screen = host.substringAfter("private fun buildTextShortcutsScreen()")
            .substringBefore("private fun showTextShortcutDialog(")
        assertTrue(screen.contains("showTextShortcutDialog(controller, shortcut, expansion)"))
        assertFalse(screen.contains("showDeleteTextShortcutDialog("))

        val editor = host.substringAfter("private fun showTextShortcutDialog(")
            .substringBefore("private fun saveTextShortcut(")
        assertTrue(editor.contains("shortcutField.setText(originalShortcut)"))
        assertTrue(editor.contains("expansionField.setText(originalExpansion)"))
        assertTrue(editor.contains("setNeutralButton(R.string.text_shortcuts_delete)"))
        assertTrue(editor.contains("showDeleteTextShortcutDialog("))
        assertTrue(editor.contains("DialogUtils.securePersonalContent(dialog)"))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/$name"
        return listOf(File(relative), File("app/$relative")).firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate $name")
    }
}
