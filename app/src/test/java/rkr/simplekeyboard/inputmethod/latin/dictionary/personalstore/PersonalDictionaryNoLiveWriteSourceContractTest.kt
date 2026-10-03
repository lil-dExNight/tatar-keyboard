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
 * The typing path never writes a personal word:
 *
 *  - `SuggestionsController`, the class that sees every keystroke, does not reference the store
 *    package at all;
 *  - `LatinIME` may reach the process-wide owner for the read source and the erasure listener,
 *    but calls no mutation on it;
 *  - outside the store package, a mutation may be called only from the settings screen, where the
 *    user asks for it explicitly, and from the backup coordinator, where a restore the user picked
 *    is applied.
 */
class PersonalDictionaryNoLiveWriteSourceContractTest {
    private val latinIme by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()
    }
    private val suggestionsController by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt").readText()
    }

    /** Every mutation the store exposes. Learning would have to call one of these. */
    private val mutations = listOf(
        "addManually(", "forget(", "clearAll(", "noteAcceptedSuggestion(", "flush(", "writeWhole(",
        "replaceAll(",
    )

    @Test
    fun theSuggestionsControllerNeverReferencesThePersonalStore() {
        for (marker in listOf(
            "PersonalDictionaryStore",
            "AndroidPersonalDictionaryStorage",
            "PersonalDictionaries",
            "personalstore",
        )) {
            assertFalse(
                "SuggestionsController — the class that sees every keystroke — references $marker",
                suggestionsController.contains(marker),
            )
        }
    }

    @Test
    fun theImeReadsAndUnbindsButNeverWrites() {
        assertTrue("the IME wires the read source",
            latinIme.contains("PersonalDictionaries.sourceFor("))
        for (mutation in mutations) {
            assertFalse(
                "the typing path must not write: LatinIME calls $mutation — learning is E4c",
                latinIme.contains(mutation),
            )
        }
        // Fail-capable: the same predicate against a source that does call one.
        assertTrue((latinIme + "store.addManually(word)").contains("addManually("))
    }

    @Test
    fun outsideItsPackageOnlyTheSettingsScreenMutatesThePersonalDictionary() {
        val javaRoot = File(sourceRoot(), "java")
        val separator = File.separator
        val packagePath = "dictionary${separator}personalstore$separator"
        // The screen carries the user's explicit mutations; the backup coordinator carries the
        // restore the user picked in the file picker. Neither learns from typing.
        val allowedOutside = listOf(
            "settings${separator}PersonalDictionaryScreen",
            "settings${separator}backup${separator}",
        )

        // Only files that know about the personal dictionary at all: `clearAll(`/`flush(` are
        // ordinary verbs and also belong to unrelated stores (the recent-emoji one, for instance).
        val mutatingFiles = javaRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { file ->
                val text = file.readText()
                val knowsThePersonalStore = text.contains("PersonalDictionaryStore") ||
                    text.contains("PersonalDictionaries")
                knowsThePersonalStore && mutations.any { text.contains(it) }
            }
            .map { it.path }
            .toList()

        assertTrue("something mutates the store, so the API exists", mutatingFiles.isNotEmpty())
        for (path in mutatingFiles) {
            assertTrue(
                "a mutation lives outside the store package and outside the allowed writers: $path",
                path.contains(packagePath) || allowedOutside.any { path.contains(it) },
            )
        }
    }

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
}
