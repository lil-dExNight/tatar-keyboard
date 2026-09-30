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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionaryTestFixtures.Entry
import java.io.File

/**
 * Privacy of the read-only personal package: no logging or network, no user text in exception
 * messages, no user-text-carrying `data class`, and no write API. The device-protected storage
 * assertion for this package is the tree-wide one in the emoji suite.
 */
class PersonalDictionaryReadPathPrivacyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun packageSourcesCarryNoLoggingNetworkAnalyticsOrTypedTextCarryingDataClass() {
        val source = personalSources()
        listOf(
            "android.util.Log",
            "println(",
            "System.out",
            "java.net.",
            "FirebaseAnalytics",
            "createDeviceProtectedStorageContext",
            "createCredentialProtectedStorageContext",
        ).forEach { forbidden -> assertFalse("found $forbidden", source.contains(forbidden)) }

        // No type that carries the user's words may be a `data class` (its auto toString would leak
        // them). Matched as a DECLARATION, so the KDoc that explains this rule does not trip it.
        val dataClassDeclaration = Regex("""(?m)^\s*(?:internal |public |private |open |abstract |sealed )*data class\b""")
        assertFalse("a data class declaration is present", dataClassDeclaration.containsMatchIn(source))
    }

    @Test
    fun theReadOnlyPhaseContainsNoWriteApi() {
        val source = personalSources()
        listOf(
            "FileOutputStream",
            "OutputStream",
            ".writeBytes",
            "RandomAccessFile",
            "createNewFile",
            "atomicRename",
            "atomicReplace",
            "syncDirectory",
            "syncFile",
            "Files.write",
            "FileWriter",
            "DurableFileOps",
        ).forEach { forbidden -> assertFalse("write API present: $forbidden", source.contains(forbidden)) }
        // It does read.
        assertTrue(source.contains("readBytes()"))
    }

    @Test
    fun noValidationMessageContainsTheOffendingWordOrTheFilePath() {
        val control = "аБвгд" // mixed case: a distinctive control word that fails validation.
        val bytes = PersonalDictionaryTestFixtures.build(listOf(Entry(control)))
        val file = temporaryFolder.newFile("control-${System.nanoTime()}.tpers")
            .also { it.writeBytes(bytes) }
        try {
            TpersValidator().validate(file, PersonalSubtypes.TATAR_RU)
            org.junit.Assert.fail("expected the control word to be rejected")
        } catch (expected: PersonalDictionaryValidationException) {
            val message = expected.message.orEmpty()
            assertTrue(message.isNotEmpty())
            assertFalse("message leaked the word", message.contains(control))
            assertFalse("message leaked the file path", message.contains(file.name))
            assertFalse("message leaked the file path", message.contains(file.path))
        }
    }

    private fun personalSources(): String {
        val relative = "java/rkr/simplekeyboard/inputmethod/latin/dictionary/personal"
        val root = listOf(File("src/main/$relative"), File("app/src/main/$relative"))
            .firstOrNull(File::isDirectory)
            ?: error("cannot locate personal sources from ${File(".").absolutePath}")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
    }
}
