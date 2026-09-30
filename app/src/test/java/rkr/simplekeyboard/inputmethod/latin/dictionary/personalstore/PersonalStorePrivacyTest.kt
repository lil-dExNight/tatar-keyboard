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
 * Privacy gate of the personal write package, the inverse of `DictionaryStoragePrivacyTest`. The
 * bundled dictionary must use a device-protected context (it is read in direct boot); the user's
 * words must not, so this test asserts no device-protected context anywhere in the package. It
 * also enforces: (a) exception messages are constant and never include the word or the file path;
 * (b) no type that carries a word is a `data class`.
 */
class PersonalStorePrivacyTest {
    private val files by lazy {
        packageDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
    private val source by lazy { files.joinToString("\n") { it.readText() } }

    @Test
    fun packageHasNoLoggingNetworkAnalyticsOrDeviceProtectedContext() {
        listOf(
            "android.util.Log",
            "println(",
            "System.out",
            "java.net.",
            "android.permission.INTERNET",
            "FirebaseAnalytics",
            "typedText",
            "createDeviceProtectedStorageContext",
        ).forEach { forbidden ->
            assertFalse("personalstore contains $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun theMediumIsCredentialProtectedNoBackupFilesDir() {
        // The mirror of the dictionary asset's device-protected assertion: personal words live in
        // the base (credential-protected) noBackupFilesDir, so the package never asks for a
        // device-protected context.
        assertTrue(source.contains("noBackupFilesDir"))
        assertFalse(source.contains("createCredentialProtectedStorageContext"))
    }

    @Test
    fun noTypeCarryingAWordIsADataClass() {
        // A synthesised data-class toString would print the words at the first interpolation.
        // Matched as a DECLARATION so the KDoc that explains this rule does not trip it.
        val dataClassDeclaration =
            Regex("""(?m)^\s*(?:internal |public |private |open |abstract |sealed )*data class\b""")
        assertFalse("a data class declaration is present", dataClassDeclaration.containsMatchIn(source))
    }

    @Test
    fun everyExceptionMessageIsAConstantWithNoInterpolatedUserText() {
        for (file in files) {
            file.readLines().forEachIndexed { number, line ->
                if (line.contains("IOException(") || line.contains("Exception(") || line.contains("error(")) {
                    assertFalse(
                        "${file.name}:${number + 1} interpolates into an exception message: $line",
                        line.contains("\$"),
                    )
                }
            }
        }
    }

    @Test
    fun noMethodNameCarriesTypedTextTerms() {
        val forbiddenTerms = listOf("typed", "query", "prefix", "candidate", "inputtext")
        val functionNames = Regex("""\bfun\s+([A-Za-z0-9_]+)""").findAll(source)
            .map { it.groupValues[1].lowercase() }
            .toList()
        assertTrue(functionNames.isNotEmpty())
        for (name in functionNames) {
            for (term in forbiddenTerms) {
                assertFalse("method name '$name' carries typed-text term '$term'", name.contains(term))
            }
        }
    }

    private fun packageDir(): File {
        val relative = "java/rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore"
        return listOf(File("src/main/$relative"), File("app/src/main/$relative"))
            .firstOrNull(File::isDirectory)
            ?: error("cannot locate personalstore sources from ${File(".").absolutePath}")
    }
}
