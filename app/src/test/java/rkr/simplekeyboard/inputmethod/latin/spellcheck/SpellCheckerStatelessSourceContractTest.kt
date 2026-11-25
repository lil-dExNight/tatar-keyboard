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

package rkr.simplekeyboard.inputmethod.latin.spellcheck

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spell checker can read data, but it has no output, learning or keyboard-surface path. */
class SpellCheckerStatelessSourceContractTest {

    private fun spellcheckDirectory(): File = listOf(
        File("src/main/java/rkr/simplekeyboard/inputmethod/latin/spellcheck"),
        File("app/src/main/java/rkr/simplekeyboard/inputmethod/latin/spellcheck"),
    ).firstOrNull(File::isDirectory) ?: error("cannot locate spell-checker sources")

    @Test
    fun productionSourcesHaveNoLoggingOrOutputWrites() {
        val banned = listOf(
            "Log.", "android.util.Log", "println(", "printStackTrace", "System.out", "System.err",
            "android.content.SharedPreferences", ".edit(", ".apply()", ".commit()",
            "FileOutputStream", "FileWriter", "RandomAccessFile", "Files.write",
            ".writeText(", ".writeBytes(", ".appendText(", ".appendBytes(",
            ".ensurePublished(", ".cleanupReleasedVersions(",
        )
        for (file in sourceFiles()) {
            val source = file.readText()
            for (token in banned) {
                assertFalse("${file.name} must not contain $token", source.contains(token))
            }
            assertFalse(
                "${file.name} must not put input into an exception",
                Regex("(?:Exception|Error)\\([^\\n]*\\$\\{").containsMatchIn(source),
            )
        }
    }

    @Test
    fun onlyTheMissingDictionaryPublicationPreparesStorage() {
        val token = ".prepare {"
        for (file in sourceFiles()) {
            val source = file.readText()
            if (file.name == "SpellCheckerAndroid.kt") {
                val publish = source.indexOf("override fun publish(")
                assertTrue("publish must exist", publish >= 0)
                assertEquals(
                    "the storage preparation must sit inside publish",
                    1,
                    source.split(token).size - 1,
                )
                assertTrue("publish must prepare storage", source.indexOf(token) > publish)
            } else {
                assertFalse("${file.name} must not prepare storage", source.contains(token))
            }
        }
    }

    @Test
    fun productionSourcesCannotMutatePersonalDataOrStartKeyboardMachinery() {
        val banned = listOf(
            "PersonalDictionaryStore", "PersonalDictionaries.", ".storeFor(",
            ".addManually(", ".noteCompletion(", ".forget(", ".replaceAll(", ".clearAll(",
            ".prime(", "PersonalLearning", "LearningKeyguard", "EraseAll",
            "LatinIME", "RichInputMethodManager", "RichInputConnection", "InputLogic",
            "EditorSurface", "InputView", "MainKeyboardView", "KeyboardSwitcher",
            "SuggestionStripView", "SuggestionsController",
        )
        for (file in sourceFiles()) {
            val source = file.readText()
            for (token in banned) {
                assertFalse("${file.name} must not reach $token", source.contains(token))
            }
        }
    }

    @Test
    fun personalReaderIsGatedBeforeItsDirectoryProvider() {
        val source = File(spellcheckDirectory(), "SpellCheckerDictionaries.kt").readText()
        val gate = source.indexOf("if (!isUnlocked) return PersonalDictionary.EMPTY")
        val directory = source.indexOf("File(directory(), TpersFormat.personalFileName(subtypeId))")
        assertTrue(gate >= 0)
        assertTrue(directory > gate)
    }

    private fun sourceFiles(): List<File> =
        spellcheckDirectory().listFiles { file -> file.extension == "kt" }?.sortedBy(File::getName)
            ?: emptyList()
}
