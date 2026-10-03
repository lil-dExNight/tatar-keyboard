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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The text-shortcut store's placement and wiring contracts: its own schema and file (the learned-
 * words store is never touched), the shared credential-protected no-backup directory, and the
 * production wiring of the strip's read side and the separator hook.
 */
class TextShortcutSourceContractTest {

    @Test
    fun theStoreSharesThePersonalNoBackupDirectoryAndNeverGoesDeviceProtected() {
        val owner = ownerSource()
        assertTrue("the shortcut file lives in the personal/ no-backup directory",
            owner.contains("noBackupFilesDir"))
        assertTrue(owner.contains("AndroidPersonalDictionaryStorage.PERSONAL_DIRECTORY_NAME"))
        assertFalse("never a device-protected context",
            owner.contains("createDeviceProtectedStorageContext"))
        assertTrue("serialized on the personal stores' shared worker",
            owner.contains("PersonalDictionaries.sharedStoreExecutor()"))
        assertTrue("the credential-protected path requires the unlock gate",
            owner.contains("isUserUnlocked"))
    }

    @Test
    fun theShortcutStoreIsItsOwnSchemaAndNeverPollutesTheLearnedWordsStore() {
        val pkg = personalPackageDir()
        val format = File(pkg, "TcutFormat.kt").readText()
        assertTrue(format.contains("""TATCUT"""))
        assertTrue(format.contains("""shortcuts-s"""))
        for (name in listOf("TpersFormat.kt")) {
            assertFalse("$name must not know about shortcuts",
                File(pkg, "$name").readText().contains("hortcut"))
        }
        val storePkg = packageDir()
        for (name in listOf("PersonalDictionaryStore.kt", "PersonalEntries.kt")) {
            assertFalse("$name must not know about shortcuts",
                File(storePkg, name).readText().contains("hortcut"))
        }
    }

    @Test
    fun theStripReadSideAndTheSeparatorHookAreWiredInProduction() {
        val latinIme = mainSource("latin/LatinIME.java")
        assertTrue(latinIme.contains("setShortcutSource(TextShortcutStores.sourceFor(this))"))
        assertTrue(latinIme.contains("TextShortcutStores.setErasureListener"))

        // The user's own pair is tried before the statistical correction.
        val autocorrectHooks = mainSource("latin/LatinImeAutocorrect.java")
        val expansionAt = autocorrectHooks.indexOf("maybeExpandShortcutBeforeSeparator")
        val correctionAt = autocorrectHooks.indexOf(
            "mSuggestionsController.maybeAutocorrectBeforeSeparator")
        assertTrue("the expansion hook exists", expansionAt >= 0)
        assertTrue("the expansion is tried before the correction",
            correctionAt > expansionAt)
    }

    @Test
    fun theStoreAndItsFormatNeverLog() {
        val pkg = packageDir()
        for (name in listOf("TextShortcutStore.kt", "TextShortcutStores.kt", "TextShortcutFilter.kt")) {
            val text = File(pkg, name).readText()
            for (marker in listOf("android.util.Log", "println(", "System.out", "System.err")) {
                assertFalse("$name carries '$marker'", text.contains(marker))
            }
        }
        for (name in listOf("TcutFormat.kt", "TcutValidator.kt", "TextShortcuts.kt",
                "TextShortcutSource.kt")) {
            val text = File(personalPackageDir(), name).readText()
            for (marker in listOf("android.util.Log", "println(", "System.out", "System.err")) {
                assertFalse("$name carries '$marker'", text.contains(marker))
            }
        }
    }

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private fun mainSource(relativePath: String): String =
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/$relativePath").readText()

    private fun ownerSource(): String =
        File(packageDir(), "TextShortcutStores.kt").readText()

    private fun packageDir(): File = File(sourceRoot(),
        "java/rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore")

    private fun personalPackageDir(): File = File(sourceRoot(),
        "java/rkr/simplekeyboard/inputmethod/latin/dictionary/personal")
}
