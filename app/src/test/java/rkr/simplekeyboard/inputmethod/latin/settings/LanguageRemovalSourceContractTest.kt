/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for language removal: a tap still opens layouts, a guarded long-click opens a
 * one-item Remove popup, and that popup removes only the selected locale. Android views and
 * dialogs are not available to these plain JVM tests, so the relevant function bodies are pinned.
 */
class LanguageRemovalSourceContractTest {

    private val source by lazy {
        val relative = "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/" +
            "SettingsLanguagesScreens.kt"
        val root = listOf(File("."), File("app"), File("..")).firstOrNull {
            File(it, relative).isFile
        } ?: error("cannot locate $relative from ${File(".").absolutePath}")
        File(root, relative).readText()
    }

    private fun section(start: String, end: String): String {
        val startIndex = source.indexOf(start)
        check(startIndex >= 0) { "missing source section: $start" }
        val bodyStart = startIndex + start.length
        val endIndex = source.indexOf(end, bodyStart)
        check(endIndex >= 0) { "missing source section boundary: $end" }
        return source.substring(bodyStart, endIndex)
    }

    private val screen by lazy {
        section(
            "internal fun SettingsHostActivity.buildLanguagesScreen() {",
            "internal fun SettingsHostActivity.showRemoveLanguageDialog(",
        )
    }

    private val removalDialog by lazy {
        section(
            "internal fun SettingsHostActivity.showRemoveLanguageDialog(localeString: String) {",
            "internal fun SettingsHostActivity.showLocalePickerDialog(",
        )
    }

    private val addPicker by lazy {
        section(
            "internal fun SettingsHostActivity.showLocalePickerDialog(",
            "internal fun SettingsHostActivity.buildLanguageDetailScreen(",
        )
    }

    private fun String.compact() = replace(Regex("\\s+"), " ")

    @Test
    fun tapOpensDetailsAndRemovalIsOnlyOnGuardedLongClick() {
        assertFalse(
            "the separate multi-choice removal action is gone",
            source.contains("actionRow(R.string.remove_language)"),
        )
        val tap = screen.substringAfter("val row = linkRow(displayName, layoutNames) {")
            .substringBefore("}")
        assertTrue(tap.contains("detailLocale = localeString"))
        assertTrue(tap.contains("navigateTo(Screen.LANGUAGE_DETAIL)"))
        assertFalse("an ordinary tap never removes a subtype", tap.contains("removeSubtype"))

        assertTrue(
            "long-click is installed only while another language remains",
            screen.compact().contains(
                "if (usedValues.size > 1) { row.setOnLongClickListener { " +
                    "showRemoveLanguageDialog(localeString) true } }",
            ),
        )
        assertEquals(1, Regex("setOnLongClickListener").findAll(screen).count())
    }

    @Test
    fun removePopupNamesTheLocaleAndHasOneProtectedRemoveItem() {
        val dialog = removalDialog.compact()
        assertTrue(dialog.contains("currentDialog?.dismiss() currentDialog = AlertDialog.Builder(this)"))
        assertTrue(
            "the popup title is the selected language's localized display name",
            dialog.contains(
                ".setTitle(LocaleResourceUtils.getLocaleDisplayNameInSystemLocale(localeString))",
            ),
        )
        assertTrue(
            "the popup has exactly one item, localized Remove",
            dialog.contains(".setItems(arrayOf(getString(R.string.remove))) { _, _ ->"),
        )
        assertFalse(dialog.contains(".setMultiChoiceItems("))
        assertTrue(dialog.contains("DialogUtils.filterObscuredTouches(dialog) dialog.show()"))
    }

    @Test
    fun removeDeletesOnlyTheSelectedLocaleSubtypesAndRebuildsLanguages() {
        val subtypeSources = Regex("richImm\\.getEnabledSubtypesForLocale\\(([^)]*)\\)")
            .findAll(removalDialog).map { it.groupValues[1] }.toList()
        assertEquals("only the locale captured by its row may be enumerated",
            listOf("localeString"), subtypeSources)
        assertEquals(1, Regex("richImm\\.removeSubtype\\(").findAll(removalDialog).count())

        val selection = removalDialog
            .substringAfter(".setItems(arrayOf(getString(R.string.remove))) { _, _ ->")
            .substringBefore(".create()")
        assertTrue(selection.contains(
            "for (subtype in richImm.getEnabledSubtypesForLocale(localeString)) {"))
        assertTrue(selection.contains("richImm.removeSubtype(subtype)"))
        assertTrue(selection.contains("showScreen(Screen.LANGUAGES)"))
        assertTrue("the screen is rebuilt after removal",
            selection.indexOf("richImm.removeSubtype(subtype)") <
                selection.indexOf("showScreen(Screen.LANGUAGES)"))
    }

    @Test
    fun addingStaysMultiChoiceWithoutTheOldRemovalOptions() {
        assertTrue(screen.compact().contains(
            "showLocalePickerDialog(unusedValues, R.string.add_language, R.string.add) " +
                "{ checkedValues ->"))
        assertTrue(screen.contains("richImm.addSubtype("))
        assertTrue(screen.contains("SubtypeLocaleUtils.getDefaultSubtype(localeString, resources)"))
        assertTrue(addPicker.contains(".setMultiChoiceItems(names, checkedItems)"))
        assertTrue(addPicker.contains(
            "onAccept(localeValues.filterIndexed { index, _ -> checkedItems[index] })"))
        assertTrue(addPicker.contains("showScreen(Screen.LANGUAGES)"))
        assertFalse(source.contains("allowAllChecked"))
        assertFalse(addPicker.contains("hasUncheckedItem"))
    }
}
