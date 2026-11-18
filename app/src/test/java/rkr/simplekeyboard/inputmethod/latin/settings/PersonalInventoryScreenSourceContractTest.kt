/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package rkr.simplekeyboard.inputmethod.latin.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalInventoryCategory

/** Navigation, confirmation, accessibility and localization of the counts-only inventory screen. */
class PersonalInventoryScreenSourceContractTest {
    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private val host by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt").readText()
    }
    private val screen by lazy {
        host.substringAfter("private fun buildPersonalInventoryScreen() {")
            .substringBefore("private fun personalInventoryTitleRes(")
    }
    private val dialog by lazy {
        host.substringAfter("private fun showPersonalInventoryDeleteDialog(")
            .substringBefore("    /**\n     * One saved-content row")
    }

    @Test
    fun everyRequiredCategoryHasARowAndACount() {
        assertEquals(setOf(
            PersonalInventoryCategory.WORDS,
            PersonalInventoryCategory.PAIRS,
            PersonalInventoryCategory.EMOJI,
            PersonalInventoryCategory.SENTENCE_STARTS,
            PersonalInventoryCategory.REFUSED_CORRECTIONS,
            PersonalInventoryCategory.TEXT_SHORTCUTS,
            PersonalInventoryCategory.PENDING_COUNTERS,
            PersonalInventoryCategory.QUARANTINE,
        ), PersonalInventoryCategory.values().toSet())
        assertTrue(host.contains("PERSONAL_INVENTORY(R.string.personal_inventory_screen)"))
        assertTrue(host.contains("Screen.PERSONAL_INVENTORY -> buildPersonalInventoryScreen()"))
        assertTrue(host.contains("navigateTo(Screen.PERSONAL_INVENTORY)"))
        assertTrue(screen.contains("for (category in PersonalInventoryCategory.values())"))
        assertTrue(screen.contains("snapshot.countOf(category)"))
        assertTrue(screen.contains("resources.getQuantityString("))
        for (category in PersonalInventoryCategory.values()) {
            assertTrue("title mapping for $category",
                host.contains("$category -> R.string.personal_inventory") ||
                    host.contains("$category ->\n") ||
                    host.contains("$category,"))
        }
        assertTrue("the existing full erase remains available",
            screen.contains("showErasePersonalDictionaryDialog(subtypeIds)"))
    }

    @Test
    fun deletionIsConfirmedAndRepaintsFromTheFreshSnapshot() {
        assertTrue(dialog.contains(".setMessage(R.string.personal_inventory_delete_confirm)"))
        assertTrue(dialog.contains(".setPositiveButton(R.string.personal_inventory_delete)"))
        assertTrue(dialog.contains("controller.clear(category, personalSubtypeIds())"))
        assertTrue(dialog.contains("personalInventorySnapshot = snapshot"))
        assertTrue(dialog.contains("showScreen(Screen.PERSONAL_INVENTORY)"))
        assertTrue(dialog.contains("DialogUtils.filterObscuredTouches(dialog)"))
        assertFalse("an empty category is not a delete target",
            screen.substringAfter("if (deletable) {").substringBefore("} else {")
                .contains("isClickable = false"))
    }

    @Test
    fun lockedAndIncompleteStatesDoNotPretendToBeEmpty() {
        assertTrue(screen.contains("if (!snapshot.unlocked) {"))
        assertTrue(screen.contains("R.string.personal_inventory_locked"))
        assertTrue(screen.contains("if (!snapshot.complete) {"))
        assertTrue(screen.contains("R.string.personal_inventory_read_failed"))
        assertTrue(screen.contains("if (snapshot.isEmpty) {"))
        assertTrue(screen.contains("R.string.personal_inventory_empty"))
    }

    @Test
    fun everyInventoryStringIsLocalized() {
        val strings = listOf(
            "personal_inventory_screen",
            "personal_inventory_summary",
            "personal_inventory_intro",
            "personal_inventory_loading",
            "personal_inventory_locked",
            "personal_inventory_read_failed",
            "personal_inventory_empty",
            "personal_inventory_words",
            "personal_inventory_words_description",
            "personal_inventory_pairs",
            "personal_inventory_pairs_description",
            "personal_inventory_emoji",
            "personal_inventory_emoji_description",
            "personal_inventory_sentence_starts",
            "personal_inventory_sentence_starts_description",
            "personal_inventory_refused_corrections",
            "personal_inventory_refused_corrections_description",
            "personal_inventory_text_shortcuts",
            "personal_inventory_text_shortcuts_description",
            "personal_inventory_pending",
            "personal_inventory_pending_description",
            "personal_inventory_quarantine",
            "personal_inventory_quarantine_description",
            "personal_inventory_delete",
            "personal_inventory_delete_confirm_title",
            "personal_inventory_delete_confirm",
            "personal_inventory_delete_failed",
        )
        for (qualifier in listOf("values", "values-ru", "values-tt")) {
            val xml = File(sourceRoot(), "res/$qualifier/strings.xml").readText()
            for (name in strings) {
                assertTrue("$qualifier/$name", xml.contains("<string name=\"$name\">"))
            }
            for (name in listOf("personal_inventory_entries", "personal_inventory_files")) {
                assertTrue("$qualifier/$name", xml.contains("<plurals name=\"$name\">"))
            }
        }
    }
}
