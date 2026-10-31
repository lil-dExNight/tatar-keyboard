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

package rkr.simplekeyboard.inputmethod.latin.settings.backup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupOutcomeSourceContractTest {

    @Test
    fun anIncompleteLocalSnapshotDoesNotTouchThePickedDocument() {
        val coordinator = source(
            "java/rkr/simplekeyboard/inputmethod/latin/settings/backup/BackupCoordinator.kt")
        val export = coordinator.substringAfter("fun exportTo(").substringBefore("fun importFrom(")
        val incomplete = export.substringAfter("val bytes = BackupTransfer.export(target)")
            .substringBefore("} catch (_: Exception)")
        assertTrue(incomplete.contains("if (bytes == null)"))
        assertTrue(incomplete.substringBefore("documentTouched = true").contains("false"))
        assertTrue(export.contains("if (!written && documentTouched)"))
    }

    /** Each kind is opened through its owning store, and the answer is awaited, not assumed. */
    @Test
    fun everyKindIsOpenedThroughItsOwningStore() {
        val coordinator = source(
            "java/rkr/simplekeyboard/inputmethod/latin/settings/backup/BackupCoordinator.kt")
        val open = coordinator.substringAfter("override fun openPersonalStore(")
            .substringBefore("override fun replacePersonalFile(")
        for (call in listOf(
            "PersonalDictionaries.prime(appContext, subtypeId, outcome)",
            "PersonalBigramDictionaries.prime(appContext, subtypeId, outcome)",
            "PersonalEmojiDictionaries.prime(appContext, subtypeId, outcome)",
            "RefusedCorrectionStores.prime(appContext, subtypeId, outcome)",
            "TextShortcutStores.prime(appContext, outcome)",
        )) {
            assertTrue(call, open.contains(call))
        }
        assertTrue(open.contains("return awaitStore { outcome ->"))
        val await = coordinator.substringAfter("private fun awaitStore(")
            .substringBefore("override fun replacePreferences(")
        assertTrue(await.contains("done.await()"))
        assertTrue(await.contains("succeeded.get()"))
    }

    @Test
    fun privacyChangesHaveALocalizedImportOutcome() {
        val host = source("java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
        assertTrue(host.contains("BackupImportResult.IMPORTED_PRIVACY_CHANGED ->"))
        assertTrue(host.contains("R.string.backup_import_privacy_changed"))
        for (folder in listOf("values", "values-ru", "values-tt")) {
            assertTrue("$folder is missing the privacy-change warning",
                source("res/$folder/strings.xml").contains(
                    "name=\"backup_import_privacy_changed\""))
        }
    }

    @Test
    fun onlyValidationFailureSaysNothingChanged() {
        val host = source("java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
        val outcomes = host.substringAfter("val messageRes = when (result)").substringBefore("Toast.makeText")
        val invalid = outcomes.substringAfter("BackupImportResult.INVALID_FILE ->")
            .substringBefore("BackupImportResult.WRITE_FAILED")
        val write = outcomes.substringAfter("BackupImportResult.WRITE_FAILED ->")
        assertTrue(invalid.contains("backup_import_failed_invalid"))
        assertFalse(write.contains("backup_import_failed_invalid"))
        assertTrue(write.contains("backup_import_failed_write"))
    }

    @Test
    fun clearingTheJournalAlsoDeletesTheTemporaryCopy() {
        // A write cut short leaves the temporary copy, which holds the same personal states.
        val coordinator = source(
            "java/rkr/simplekeyboard/inputmethod/latin/settings/backup/BackupCoordinator.kt")
        val write = coordinator.substringAfter("override fun writeImportJournal(")
            .substringBefore("override fun clearImportJournal(")
        assertTrue(write.contains("val temporary = importJournalTemporaryFile()"))
        val clear = coordinator.substringAfter("override fun clearImportJournal(")
            .substringBefore("private fun importJournalFile()")
        assertTrue(clear.contains("listOf(importJournalTemporaryFile(), importJournalFile())"))
        assertTrue(clear.contains("if (!AndroidDurableFileOps.delete(file) && file.exists()) return false"))
    }

    /** The file holds every personal store the import replaces, so both texts name each of them. */
    @Test
    fun theBackupTextsNameEverythingTheFileHolds() {
        val english = source("res/values/strings.xml")
        for (name in listOf("backup_contents_note", "backup_import_confirm_message")) {
            val text = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .find(english)?.groupValues?.get(1)?.lowercase() ?: error("$name missing")
            for (kind in listOf("settings", "saved words", "word pairs", "emoji", "text shortcuts", "corrections")) {
                assertTrue("$name must name $kind", text.contains(kind))
            }
        }
    }

    private fun source(relative: String): String {
        return listOf(File("src/main", relative), File("app/src/main", relative))
            .firstOrNull(File::isFile)?.readText() ?: error("cannot locate $relative")
    }
}
