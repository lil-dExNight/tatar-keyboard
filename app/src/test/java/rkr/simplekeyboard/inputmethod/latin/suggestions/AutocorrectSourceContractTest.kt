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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two autocorrect invariants that live in code shape rather than in behavior, because the
 * classes that carry them (`InputLogic`, `LatinIME`) cannot be instantiated without Android: the
 * autocorrection inserts through the SAME single commit as an accepted suggestion, and neither path
 * ever creates composing text.
 */
class AutocorrectSourceContractTest {

    @Test
    fun autocorrectInsertsThroughTheSameSingleCommitAsAnAcceptedSuggestion() {
        val source = inputLogicSource()

        // Both insertion paths delegate to one shared method and do no editing of their own, so
        // they stay the same commit.
        for (entryPoint in listOf("commitChosenSuggestion", "commitTatarAutocorrection")) {
            val body = methodBody(source, "public boolean $entryPoint(")
            assertEquals(
                "$entryPoint must delegate to replaceTrailingWord exactly once",
                1,
                body.occurrencesOf("replaceTrailingWord("),
            )
            for (edit in EDIT_CALLS) {
                assertEquals(
                    "$entryPoint must not edit the editor itself ($edit)",
                    0,
                    body.occurrencesOf(edit),
                )
            }
        }

        // The shared method performs exactly one delete and exactly one commit, inside one batch
        // edit — the single insertion the contract describes.
        val shared = methodBody(source, "private boolean replaceTrailingWord(")
        for (call in EDIT_CALLS) {
            assertEquals(
                "replaceTrailingWord must call $call exactly once",
                1,
                shared.occurrencesOf(call),
            )
        }

        // The undo is the same shape: one delete, one commit, one batch edit.
        val revert = methodBody(source, "public boolean revertTatarAutocorrection(")
        for (call in EDIT_CALLS) {
            assertEquals(
                "revertTatarAutocorrection must call $call exactly once",
                1,
                revert.occurrencesOf(call),
            )
        }
        // The undo is pinned to the exact text it put there: an offset can coincide again after
        // unrelated edits, this suffix cannot.
        assertTrue(
            "the undo must verify what actually stands before the cursor",
            revert.contains("endsWith(mConnection.getCachedTextBeforeCursor()"),
        )

        // The auto-space belongs to the accepted suggestion alone: the separator the user pressed is
        // what separates a corrected word, and a space here would produce "сүз  ,".
        assertTrue(
            "the autocorrection path must ask for no auto-space",
            methodBody(source, "public boolean commitTatarAutocorrection(")
                .contains("false /* withAutoSpace */"),
        )
        assertTrue(
            "the accepted-suggestion path must keep its auto-space",
            methodBody(source, "public boolean commitChosenSuggestion(")
                .contains("true /* withAutoSpace */"),
        )
    }

    @Test
    fun autocorrectNeverIntroducesComposingText() {
        // No source in the app names a composing API at all, so a composing region cannot appear on
        // any path without this test failing. This pins the rule "no composing text — commit at
        // once, delete by code points", which autocorrect as the second insertion path keeps.
        val offenders = mainSources().filter { file ->
            val text = file.readText()
            COMPOSING_APIS.any(text::contains)
        }
        assertTrue(
            "composing text must never be created: ${offenders.map(File::getName)}",
            offenders.isEmpty(),
        )

        // The Tatar insertion and undo reach the editor through RichInputConnection, which offers no
        // composing method to call in the first place.
        val connection = firstFile(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java",
        )
        for (api in COMPOSING_APIS) {
            assertTrue(
                "RichInputConnection must expose no $api",
                !connection.readText().contains(api),
            )
        }
    }

    @Test
    fun theAutocorrectSeamsAreTheOnlyWayTheControllerEditsTextForD3() {
        // The controller owns the decision and none of the mechanics: everything it does to the
        // text goes through the two editor seams, so a second edit path cannot appear here unseen.
        val controller = firstFile(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt",
        ).readText()
        assertEquals(1, controller.occurrencesOf("editor.replaceTypedWord("))
        assertEquals(1, controller.occurrencesOf("editor.revertTypedWord("))
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    /** The body of the method whose declaration starts with [signature], braces balanced. */
    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("method not found: $signature", start >= 0)
        var index = source.indexOf('{', start)
        assertTrue("method body not found: $signature", index >= 0)
        var depth = 0
        val open = index
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        throw AssertionError("unbalanced braces after $signature")
    }

    private fun inputLogicSource(): String = firstFile(
        "src/main/java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java",
        "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java",
    ).readText()

    private fun mainSources(): List<File> {
        val root = listOf("src/main/java", "app/src/main/java")
            .map(::File).firstOrNull(File::isDirectory) ?: error("main sources not found")
        return root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()
    }

    private fun firstFile(vararg paths: String): File =
        paths.map(::File).firstOrNull(File::isFile) ?: error("file not found: ${paths.first()}")

    companion object {
        private val EDIT_CALLS = listOf(
            "mConnection.beginBatchEdit(",
            "mConnection.deleteTextBeforeCursor(",
            "mConnection.commitText(",
            "mConnection.endBatchEdit(",
        )

        private val COMPOSING_APIS = listOf(
            "setComposingText",
            "setComposingRegion",
            "finishComposingText",
            "getComposingText",
        )
    }
}
