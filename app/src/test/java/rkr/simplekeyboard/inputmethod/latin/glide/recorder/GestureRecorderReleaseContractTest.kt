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

package rkr.simplekeyboard.inputmethod.latin.glide.recorder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug gesture recorder's perimeter, enforced from source on both unit-test variants:
 *
 * - the recorder never enters the release APK — no main, release or androidTest source
 *   references it, and the main manifest declares none of its components;
 * - the recorder never reads field text, never touches the network, never logs;
 * - its storage is confined to the base (credential-protected) `noBackupFilesDir`;
 * - its debug components are non-exported and request no permissions.
 */
class GestureRecorderReleaseContractTest {

    @Test
    fun theReleaseSourceSetNeverReferencesTheRecorder() {
        for (file in nonDebugSources()) {
            val source = file.readText()
            assertFalse("${file.path} references the recorder package",
                source.contains("glide.recorder"))
            assertFalse("${file.path} references a recorder type",
                source.contains("GestureRecord"))
        }
    }

    @Test
    fun theMainManifestDeclaresNoRecorderComponent() {
        val manifest = mainManifest().readText()
        assertFalse(manifest.contains("GestureRecorder"))
        assertFalse(manifest.contains("gesture_recorder"))
    }

    @Test
    fun theRecorderLivesInTheDebugSourceSet() {
        val sources = recorderSources()
        assertTrue("the recorder sources exist under app/src/debug", sources.isNotEmpty())
        for (file in sources) {
            assertTrue("${file.path} is a debug-source-set file",
                file.path.replace(File.separatorChar, '/').contains("/src/debug/"))
        }
    }

    @Test
    fun theRecorderNeverReadsFieldText() {
        val forbidden = listOf(
            "getCurrentInputConnection", "InputConnection", "EditorInfo", "ExtractedText",
            "getTextBeforeCursor", "getTextAfterCursor", "getSelectedText",
            "ClipboardManager", "ClipData",
        )
        for (file in recorderSources() + debugManifest()) {
            val source = file.readText()
            for (token in forbidden) {
                assertFalse("${file.name} touches field text ($token)", source.contains(token))
            }
        }
    }

    @Test
    fun theRecorderHasNoNetworkAndNoLogging() {
        val forbidden = listOf(
            "INTERNET", "HttpURLConnection", "Socket(", "android.util.Log",
            "println(", "System.out", "System.err",
        )
        for (file in recorderSources() + debugManifest()) {
            val source = file.readText()
            for (token in forbidden) {
                assertFalse("${file.name} carries $token", source.contains(token))
            }
        }
    }

    @Test
    fun theRecorderWritesOnlyUnderNoBackupFilesDir() {
        val sources = recorderSources()
        val shell = sources.single { it.name == "GestureRecorderController.kt" }.readText()
        assertTrue("the storage root is noBackupFilesDir", shell.contains("noBackupFilesDir"))
        val forbidden = listOf(
            ".filesDir", "cacheDir", "getExternalFilesDir", "externalStorage",
            "Environment.get", "MediaStore",
        )
        for (file in sources) {
            val source = file.readText()
            for (token in forbidden) {
                assertFalse("${file.name} writes outside noBackupFilesDir ($token)",
                    source.contains(token))
            }
        }
    }

    @Test
    fun theDebugManifestKeepsTheComponentsNonExported() {
        val manifest = debugManifest().readText()
        assertFalse("the recorder requests no permission", manifest.contains("uses-permission"))
        assertEquals(
            "every recorder component is non-exported",
            2,
            Regex("""android:exported="false"""").findAll(manifest).count(),
        )
        assertFalse(manifest.contains("android:exported=\"true\""))
    }

    /** Main, release (if present) and androidTest sources: everything the release sees. */
    private fun nonDebugSources(): List<File> {
        val roots = listOfNotNull(
            firstDirectory("src/main/java", "app/src/main/java"),
            firstDirectory("src/release", "app/src/release"),
            firstDirectory("src/androidTest", "app/src/androidTest"),
        )
        assertTrue("main sources not found", roots.isNotEmpty())
        return roots.flatMap { root ->
            root.walkTopDown().filter {
                it.isFile && (it.extension == "kt" || it.extension == "java")
            }
        }
    }

    private fun recorderSources(): List<File> {
        val dir = firstDirectory(
            "src/debug/java/rkr/simplekeyboard/inputmethod/latin/glide/recorder",
            "app/src/debug/java/rkr/simplekeyboard/inputmethod/latin/glide/recorder",
        ) ?: error("the recorder sources were not found; the debug source set is required")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun mainManifest(): File = firstFile(
        "src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml",
    ) ?: error("main manifest not found")

    private fun debugManifest(): File = firstFile(
        "src/debug/AndroidManifest.xml", "app/src/debug/AndroidManifest.xml",
    ) ?: error("the debug manifest was not found; the debug source set is required")

    private fun firstDirectory(vararg paths: String): File? =
        paths.map(::File).firstOrNull(File::isDirectory)

    private fun firstFile(vararg paths: String): File? =
        paths.map(::File).firstOrNull(File::isFile)
}
