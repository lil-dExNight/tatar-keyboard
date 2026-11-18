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
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the password-field and worker boundaries that a behavioral JVM test cannot observe. */
class BackupPasswordSourceContractTest {

    @Test
    fun passwordFieldsDisableLearningSuggestionsAndAutofill() {
        val host = source("java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
        val field = host.substringAfter("private fun backupPasswordField(")
            .substringBefore("/** Copies the editable")
        assertTrue(field.contains("TYPE_TEXT_VARIATION_PASSWORD"))
        assertTrue(field.contains("applyPrivateInputFlags(field)"))
        val flags = host.substringAfter("private fun applyPrivateInputFlags(")
            .substringBefore("private fun textInputRow(")
        assertTrue(flags.contains("IME_FLAG_NO_PERSONALIZED_LEARNING"))
        assertTrue(flags.contains("TYPE_TEXT_FLAG_NO_SUGGESTIONS"))
        assertTrue(flags.contains("IMPORTANT_FOR_AUTOFILL_NO"))
    }

    @Test
    fun passwordDialogsAreSecuredAndCleared() {
        val host = source("java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
        val export = host.substringAfter("private fun showEncryptedBackupExportPasswordDialog(")
            .substringBefore("private fun showBackupImportDialog(")
        assertTrue(export.contains("DialogUtils.securePersonalContent(dialog)"))
        assertTrue(export.contains("DialogUtils.filterObscuredTouches(dialog)"))
        assertTrue(export.contains("passwordField.text.clear()"))
        assertTrue(export.contains("password.contentEquals(confirmation)"))
        assertTrue(export.contains("password.fill('\\u0000')"))
        val import = host.substringAfter("private fun showBackupImportPasswordDialog(")
            .substringBefore("private fun backupPasswordField(")
        assertTrue(import.contains("DialogUtils.securePersonalContent(dialog)"))
        assertTrue(import.contains("backupCoordinator().importFrom(uri, password)"))
        assertFalse("a password must never be converted to String",
            export.contains(".text.toString()") || import.contains(".text.toString()"))
    }

    @Test
    fun cryptographyStaysOffTheUiThreadAndPasswordsAreWiped() {
        val coordinator = source(
            "java/rkr/simplekeyboard/inputmethod/latin/settings/backup/BackupCoordinator.kt")
        val export = coordinator.substringAfter("fun exportTo(")
            .substringBefore("fun importFrom(")
        assertTrue(export.contains("submitBackupTask(executor, password)"))
        assertTrue(export.contains("BackupTransfer.export(target, password)"))
        assertTrue(export.contains("password?.fill('\\u0000')"))
        assertTrue(export.contains("if (!accepted) uiPoster { onFinished(false) }"))
        val import = coordinator.substringAfter("fun importFrom(")
            .substringBefore("private fun readBounded(")
        assertTrue(import.contains("submitBackupTask(executor, password)"))
        assertTrue(import.contains("BackupTransfer.import(target, bytes, password)"))
        assertTrue(import.contains("password?.fill('\\u0000')"))
        assertTrue(import.contains(
            "if (!accepted) uiPoster { onFinished(BackupImportResult.WRITE_FAILED) }"))
    }

    @Test
    fun aRejectedBackupTaskWipesThePasswordWithoutRunning() {
        val password = "secret".toCharArray()
        var ran = false

        val accepted = submitBackupTask(
            Executor { throw RejectedExecutionException("shut down") },
            password,
        ) { ran = true }

        assertFalse(accepted)
        assertFalse("a rejected task never runs", ran)
        assertTrue("rejection must still wipe the password", password.all { it == '\u0000' })
    }

    @Test
    fun anAcceptedBackupTaskOwnsPasswordCleanup() {
        val password = "secret".toCharArray()
        var sawPassword = false

        val accepted = submitBackupTask(Executor { it.run() }, password) {
            sawPassword = password.any { it != '\u0000' }
            password.fill('\u0000')
        }

        assertTrue(accepted)
        assertTrue("the task receives the password", sawPassword)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun everyLocaleHasThePasswordDialogs() {
        val names = listOf(
            "backup_encrypted_export_action",
            "backup_export_password_title",
            "backup_export_password_action",
            "backup_import_password_title",
            "backup_import_password_action",
            "backup_password_hint",
            "backup_password_confirm_hint",
            "backup_password_empty",
            "backup_password_mismatch",
        )
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val strings = source("res/$folder/strings.xml")
            for (name in names) {
                assertTrue("$folder is missing $name", strings.contains("name=\"$name\""))
            }
        }
    }

    private fun source(relative: String): String =
        listOf(File("src/main", relative), File("app/src/main", relative))
            .firstOrNull(File::isFile)?.readText() ?: error("cannot locate $relative")
}
