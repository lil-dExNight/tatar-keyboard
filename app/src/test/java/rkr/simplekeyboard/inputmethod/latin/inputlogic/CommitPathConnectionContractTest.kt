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

package rkr.simplekeyboard.inputmethod.latin.inputlogic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission `tt-personal-dict`, finding A4 of `docs/SILENT-AUDIT.md`: the three insertion paths of the
 * frozen text contract may not report an edit they did not make.
 *
 * `RichInputConnection` updates its own text cache BEFORE it checks whether a connection exists, and
 * both commit paths used to return `true` unconditionally. With the editor gone between the band
 * being painted and the tap, that produced two wrongs at once: `SuggestionsController.onTap` unbound
 * the candidates and cleared the band for text that reached no editor, and the cache was left
 * holding characters the editor does not have, with `mExpectedSelStart` moved along with them —
 * after which the trailing word, the letter-after-cursor test and the suffix an undo matches are all
 * read off fiction.
 *
 * Asserted by source: `InputLogic` needs a live `InputMethodService`, a `KeyboardSwitcher` and a
 * `RichInputMethodManager` singleton, so it does not run in a plain JVM test — the same reason the
 * `Autocorrect` and `SuggestionStrip` contracts in this suite are written this way. The predicates
 * are proved fail-capable at the bottom against the exact code they replaced.
 */
class CommitPathConnectionContractTest {

    private val source by lazy {
        val roots = listOf(File("src/main"), File("app/src/main"))
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
        File(root, "java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java").readText()
    }

    private fun bodyOf(from: String, to: String) = source.substringAfter(from).substringBefore(to)

    /** Paths one and two — an accepted suggestion and an autocorrection — share this one method. */
    private val replaceTrailingWord by lazy {
        bodyOf("private boolean replaceTrailingWord(", "* Undoes the autocorrection")
    }

    /** Path three — a predicted next word. Deletes nothing, but commits through the same cache. */
    private val commitPredictedWord by lazy {
        bodyOf("public boolean commitPredictedWord(", "/** Allocation-free suffix test")
    }

    /**
     * The undo of path two (D3) — missed by 25c1ae28 and covered here since the audit of
     * 2026-09-24: it deletes and commits through the same cache, so it answers to the same
     * contract.
     */
    private val revertTatarAutocorrection by lazy {
        bodyOf("public boolean revertTatarAutocorrection(", "/**\n     * Commits a predicted next word")
    }

    @Test
    fun allThreeInsertionPathsRefuseWhenThereIsNoEditorToCommitInto() {
        for ((name, body) in paths()) {
            assertTrue("$name does not ask whether there is an editor at all",
                body.contains("final boolean connected = mConnection.isConnected();"))
            assertTrue("$name must report the truth rather than a blanket success",
                body.contains("if (!connected) {\n            return false;\n        }"))
        }
    }

    @Test
    fun theCheckHappensAfterTheConnectionIsRefreshedAndBeforeAnythingIsWritten() {
        for ((name, body) in paths()) {
            val begin = body.indexOf("mConnection.beginBatchEdit();")
            val check = body.indexOf("final boolean connected = mConnection.isConnected();")
            val guarded = body.indexOf("if (connected) {")
            val commit = body.indexOf("mConnection.commitText(")
            assertTrue("$name: beginBatchEdit is what refreshes mIC, so the check follows it",
                begin in 0 until check)
            assertTrue("$name: and the commit sits INSIDE the guarded block",
                check < guarded && guarded < commit)
        }
        // Deleting is a cache mutation too, and on the two paths that delete it comes first.
        for ((name, body) in pathsWithDelete()) {
            assertTrue("$name: the delete must be guarded as well",
                body.indexOf("if (connected) {") < body.indexOf("mConnection.deleteTextBeforeCursor("))
        }
    }

    /**
     * The batch is opened and closed exactly once on BOTH paths through each method — the shape the
     * frozen text contract pins in `AutocorrectSourceContractTest`, and the reason the check is not
     * written as an early return with a second `endBatchEdit()`.
     */
    @Test
    fun theBatchIsStillOpenedAndClosedExactlyOnce() {
        for ((name, body) in paths()) {
            assertEquals("$name opens one batch", 1,
                Regex("mConnection\\.beginBatchEdit\\(").findAll(body).count())
            assertEquals("$name closes it once, on either path", 1,
                Regex("mConnection\\.endBatchEdit\\(").findAll(body).count())
        }
    }

    /** The shape that shipped: same two methods, no check. Every predicate above must reject it. */
    @Test
    fun thePredicatesRejectTheShapeTheyReplaced() {
        val shipped = """
            mConnection.beginBatchEdit();
            mConnection.deleteTextBeforeCursor(expectedPrefix.length());
            mConnection.commitText(textToCommit, 1);
            mConnection.endBatchEdit();
            return true;
        """.trimIndent()
        assertFalse("the shipped body carried no connection check",
            shipped.contains("final boolean connected = mConnection.isConnected();"))
        assertTrue("and it always claimed success", shipped.contains("return true;"))
    }

    private fun paths() = listOf(
        "replaceTrailingWord" to replaceTrailingWord,
        "commitPredictedWord" to commitPredictedWord,
        "revertTatarAutocorrection" to revertTatarAutocorrection,
    )

    /** The paths whose edit deletes first — both must guard the delete as well as the commit. */
    private fun pathsWithDelete() = listOf(
        "replaceTrailingWord" to replaceTrailingWord,
        "revertTatarAutocorrection" to revertTatarAutocorrection,
    )
}
