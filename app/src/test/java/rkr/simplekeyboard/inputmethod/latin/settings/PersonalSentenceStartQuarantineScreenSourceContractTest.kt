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

/** Settings-side sentence-start recovery contracts; store behavior lives in personal-store tests. */
class PersonalSentenceStartQuarantineScreenSourceContractTest {
    private val host = source("SettingsHostActivity.kt")
    private val controller = source("SentStartQuarantineScreenController.kt")

    @Test
    fun cardShowsCountsAndDamageButNeverWords() {
        assertTrue(host.contains(
            "addSentenceStartQuarantineCards(sentenceController, subtypeIds)"))
        val card = bodyOf(host, "private fun addSentenceStartQuarantineCards(",
            "private fun showDiscardSentenceStartQuarantineDialog(")
        assertTrue(card.contains("val summary = when {"))
        assertTrue(card.contains("report.wordCount == 0"))
        assertTrue(card.contains("report.readToEnd"))
        assertTrue(card.contains("R.plurals.personal_sentence_starts_quarantine_partial"))
        assertTrue(card.contains("R.plurals.personal_sentence_starts_quarantine_whole"))
        assertTrue(card.contains("if (report.wordCount > 0) {"))
        assertTrue(card.contains("controller.quarantines(subtypeIds) { found ->"))
        assertTrue(card.contains("showDiscardSentenceStartQuarantineDialog(controller, subtypeId)"))
        assertFalse("the card must not hold a recovered word",
            card.contains("rawForms") || card.contains("normalizedForms"))
    }

    @Test
    fun everyAnswerComesFromTheStoreOwnerOnTheUiThread() {
        assertTrue(controller.contains("private val uiPoster: (Runnable) -> Unit"))
        for (call in listOf(
            "SentStartOverlays.storeFor(context, subtypeId).inspectQuarantine",
            "SentStartOverlays.storeFor(context, subtypeId)\n            .restoreQuarantine",
            "SentStartOverlays.storeFor(context, subtypeId)\n            .discardQuarantine",
        )) {
            assertTrue(call, controller.contains(call))
        }
        assertEquals("empty inspection, fan-out, restore and discard all answer on the UI thread",
            4, Regex("uiPoster \\{").findAll(controller).count())
        assertFalse(controller.contains("File(") || controller.contains("readBytes("))
    }

    @Test
    fun eraseAndForgetInvalidateTheCachedSentenceCopyReport() {
        val erase = bodyOf(host, "private fun showErasePersonalDictionaryDialog(",
            "private fun afterPersonalMutation(")
        assertTrue(erase.contains("sentenceStartQuarantines = null"))
        val inventory = bodyOf(host, "private fun showPersonalInventoryDeleteDialog(",
            "private fun usageRow(")
        assertTrue(inventory.contains("PersonalInventoryCategory.SENTENCE_STARTS"))
        assertTrue(inventory.contains("sentenceStartQuarantines = null"))
        val forget = bodyOf(host, "private fun showForgetPersonalWordDialog(",
            "private fun showForgetPersonalPairDialog(")
        assertTrue(forget.contains("sentenceStartQuarantines = null"))
    }

    @Test
    fun imeDialogUsesSentenceStartMessage() {
        val latinIme = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()
        val dialog = latinIme.substringAfter("private void showPersonalSentenceStartsUnreadableDialog()")
            .substringBefore("private void attachDialogToInputWindow(")
        assertTrue(dialog.contains(".setMessage(R.string.personal_sentence_starts_unreadable)"))
        assertFalse(dialog.contains(".setMessage(R.string.personal_dictionary_unreadable)"))
    }

    @Test
    fun allRecoveryMessagesAreLocalizedAndNameNoPathOrCause() {
        val keys = listOf(
            "personal_sentence_starts_unreadable",
            "personal_sentence_starts_quarantine_title",
            "personal_sentence_starts_quarantine_none",
            "personal_sentence_starts_quarantine_restore",
            "personal_sentence_starts_quarantine_discard",
            "personal_sentence_starts_quarantine_discard_confirm",
            "personal_sentence_starts_quarantine_restore_failed",
            "personal_sentence_starts_quarantine_discard_failed",
        )
        val forbidden = listOf("file", "path", "error", "tsent", "quarantine", "/")
        for (values in listOf("values", "values-ru", "values-tt")) {
            val strings = File(sourceRoot(), "res/$values/strings.xml").readText()
            for (key in keys) assertTrue("$values is missing $key", strings.contains("\"$key\""))
        }
        val english = File(sourceRoot(), "res/values/strings.xml").readText()
        for (key in keys) {
            val text = english.substringAfter("<string name=\"$key\">").substringBefore("</string>")
            for (word in forbidden) assertFalse("$key names $word", text.lowercase().contains(word))
        }
    }

    private fun source(name: String): String =
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/settings/$name").readText()

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main")

    private fun bodyOf(source: String, from: String, to: String): String =
        source.substringAfter(from).substringBefore(to)
}
