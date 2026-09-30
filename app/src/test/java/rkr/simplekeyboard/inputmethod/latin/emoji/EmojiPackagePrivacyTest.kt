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

package rkr.simplekeyboard.inputmethod.latin.emoji

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Privacy test for the whole emoji package, mirror image of `DictionaryStoragePrivacyTest`.
 *
 * It greps the frozen package sources for logging, network and analytics; it asserts the recents
 * medium is credential-protected (the mirror of the dictionary's device-protected assertion — the
 * dictionary asserts it CONTAINS `createDeviceProtectedStorageContext()`, the emoji package asserts
 * it does NOT); and it confirms, by source contract, that nothing in the package writes to any
 * store except the single recent-emoji file.
 */
class EmojiPackagePrivacyTest {

    private fun packageDir(): File {
        val relative = "src/main/java/rkr/simplekeyboard/inputmethod/latin/emoji"
        return listOf(File(relative), File("app/$relative")).firstOrNull(File::isDirectory)
            ?: error("cannot locate emoji package sources")
    }

    private val files by lazy {
        packageDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val source by lazy { files.joinToString("\n") { it.readText() } }

    @Test
    fun emojiPackageHasNoLoggingNetworkOrAnalytics() {
        listOf(
            "android.util.Log",
            "println(",
            "System.out",
            "java.net.",
            "android.permission.INTERNET",
            "FirebaseAnalytics",
        ).forEach { forbidden ->
            assertFalse("emoji package contains $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun recentsMediumIsCredentialProtectedNotDeviceProtected() {
        // The mirror of the dictionary asset assertion: the recents live in the base
        // (credential-protected) noBackupFilesDir, so the package never asks for a device-protected
        // context. The `personal` package tests make the same assertion.
        assertFalse(source.contains("createDeviceProtectedStorageContext()"))
        assertFalse(source.contains("createCredentialProtectedStorageContext()"))
        // The medium is the base context's noBackupFilesDir.
        assertTrue(source.contains("noBackupFilesDir"))
    }

    @Test
    fun theOnlyStoreTheEmojiPackageWritesToIsTheRecentsFile() {
        // The single file-writing path is the recents medium in RecentEmojiStore.kt; nothing else
        // in the package writes a store. No SharedPreferences, no openFileOutput anywhere.
        for (forbidden in listOf(
            "SharedPreferences",
            "getSharedPreferences",
            "openFileOutput",
            ".edit()",
            "FileWriter",
        )) {
            assertFalse("emoji package writes via $forbidden", source.contains(forbidden))
        }
        val writers = files.filter { it.readText().contains("FileOutputStream") }.map { it.name }
        assertTrue(
            "only RecentEmojiStore.kt writes a file; found $writers",
            writers.toSet() == setOf("RecentEmojiStore.kt"),
        )
    }

    /**
     * The emoji-search query never reaches the editor. The keyboard types "into itself" while the
     * search is open, so no part of the package may commit text, start a composing region or read
     * the field, and the query must not be persisted anywhere either.
     */
    @Test
    fun theSearchQueryNeverReachesTheEditorAndIsNeverStored() {
        // Built from fragments so this test's own list is not a hit when the package is grepped.
        val editorApis = listOf(
            "commit" + "Text",
            "set" + "ComposingText",
            "finish" + "ComposingText",
            "getText" + "BeforeCursor",
            "getText" + "AfterCursor",
            "get" + "SelectedText",
            "delete" + "SurroundingText",
            "Input" + "Connection",
        )
        for (forbidden in editorApis) {
            assertFalse("emoji package touches the editor via $forbidden", source.contains(forbidden))
        }
        val query = File(packageDir(), "EmojiSearchQuery.kt").readText()
        // The query lives in memory for the length of one search and nowhere else.
        for (forbidden in listOf("File(", "Uri", "Intent", "Bundle", "SharedPreferences")) {
            assertFalse("the query escapes via $forbidden", query.contains(forbidden))
        }
    }

    /** The search index is read from the packed asset and never written back or cached on disk. */
    @Test
    fun theSearchIndexIsReadOnlyAndLoadedOffTheStartupPath() {
        val controller = File(packageDir(), "EmojiPanelController.kt").readText()
        assertTrue(controller.contains("emoji/emoji_search_v1.txt"))
        // Loaded on the background executor, from onSearchRequested — never eagerly.
        val onSearch = controller.substringAfter("fun onSearchRequested()")
            .substringBefore("private fun onSearchIndexLoaded(")
        assertTrue(onSearch.contains("backgroundExecutor.execute"))
        val index = File(packageDir(), "EmojiSearchIndex.kt").readText()
        for (forbidden in listOf("FileOutputStream", "openFileOutput", "SharedPreferences")) {
            assertFalse("the index is persisted via $forbidden", index.contains(forbidden))
        }
    }
}
