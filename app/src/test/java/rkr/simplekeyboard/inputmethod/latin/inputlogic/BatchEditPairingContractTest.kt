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
 * Every beginBatchEdit/endBatchEdit pair in `InputLogic` and `RichInputConnection` is a
 * try/finally, so a RuntimeException from a dying editor mid-edit cannot skip the endBatchEdit and
 * leave the batch nest level stuck.
 *
 * An uncaught editor RuntimeException would also reach the UI thread and kill the IME process, so
 * every editor call in `RichInputConnection` sits in a `catch (final RuntimeException e)` that
 * returns silently; the catch keeps the finally's batch close reachable and never replaces it.
 * `InputLogic` has no catch at all: it reaches the editor only through that wrapper.
 *
 * Asserted by source because both classes need a live `LatinIME` to run; the regexes are kept
 * honest by the batch-count anchors at the bottom.
 */
class BatchEditPairingContractTest {

    private fun read(name: String): String {
        val roots = listOf(File("src/main"), File("app/src/main"))
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
        return File(root, "java/rkr/simplekeyboard/inputmethod/latin/$name").readText()
    }

    private val files by lazy {
        linkedMapOf(
            "InputLogic" to read("inputlogic/InputLogic.java"),
            "RichInputConnection" to read("RichInputConnection.java"),
        )
    }

    /** A batch call written either as `mConnection.beginBatchEdit();` or bare (own class). */
    private fun batchCall(name: String) =
        Regex("(?:mConnection\\.|(?<![\\w.]))$name\\(\\);")

    /** The batch opens a try before anything that can throw (the isConnected read cannot). */
    private val batchOpensTry = Regex(
        "beginBatchEdit\\(\\);" +
            "(?:\\s|//[^\\n]*)*" +
            "(?:final boolean connected = mConnection\\.isConnected\\(\\);(?:\\s|//[^\\n]*)*)?" +
            "try \\{"
    )

    private val batchClosesInFinally =
        Regex("\\} finally \\{\\s*(?:mConnection\\.)?endBatchEdit\\(\\);\\s*\\}")

    @Test
    fun everyBatchOpensATryBeforeItsFirstEdit() {
        for ((name, src) in files) {
            val begins = batchCall("beginBatchEdit").findAll(src).count()
            val openingTries = batchOpensTry.findAll(src).count()
            assertEquals(
                "$name: every beginBatchEdit() is followed by a try (comments and the " +
                    "connection read in between are fine)",
                begins, openingTries,
            )
        }
    }

    @Test
    fun everyBatchClosesInsideAFinally() {
        for ((name, src) in files) {
            val ends = batchCall("endBatchEdit").findAll(src).count()
            val finallyCloses = batchClosesInFinally.findAll(src).count()
            assertEquals(
                "$name: every endBatchEdit() sits inside a finally block",
                ends, finallyCloses,
            )
        }
    }

    @Test
    fun catchesLiveOnlyAtTheEditorBoundaryAndOnlyForRuntimeException() {
        val inputLogic = files.getValue("InputLogic")
        assertFalse(
            "InputLogic: S8's catches live in the wrapper alone — InputLogic reaches the " +
                "editor only through RichInputConnection (O6), so a catch here would be dead " +
                "code pretending to be a guard",
            Regex("\\bcatch\\s*\\(").containsMatchIn(inputLogic),
        )
        val connection = files.getValue("RichInputConnection")
        // Comment-stripped so a javadoc {@code catch (...)} mention cannot masquerade as a site.
        val code = connection
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")
        val allCatches = Regex("\\bcatch\\s*\\(").findAll(code).count()
        val runtimeCatches =
            Regex("catch \\(final RuntimeException e\\)").findAll(code).count()
        assertEquals(
            "RichInputConnection: one catch per editor-call site — begin/endBatchEdit, the " +
                "reload body, commitText, replaceText, deleteTextBeforeCursor, " +
                "deleteSelectedText, performEditorAction, pasteClipboard, sendKeyEvent, " +
                "setSelection; a new editor call without one trips the O6 inventory first",
            11, runtimeCatches,
        )
        assertEquals(
            "every catch is RuntimeException-only — never Throwable, Error, or checked-only",
            runtimeCatches, allCatches,
        )
    }

    /** The scan is meaningful only while it sees the batches it claims to pair. */
    @Test
    fun theScanAnchorsToTheKnownBatchCounts() {
        assertEquals(10, batchCall("beginBatchEdit").findAll(files.getValue("InputLogic")).count())
        assertEquals(10, batchCall("endBatchEdit").findAll(files.getValue("InputLogic")).count())
        assertEquals(
            1,
            batchCall("beginBatchEdit").findAll(files.getValue("RichInputConnection")).count(),
        )
        assertEquals(
            1,
            batchCall("endBatchEdit").findAll(files.getValue("RichInputConnection")).count(),
        )
    }

    /** Next to the pairing: the negative-range guard ahead of the recapitalization. */
    @Test
    fun performRecapitalizationRefusesANegativeSelectionLength() {
        val body = files.getValue("InputLogic")
            .substringAfter("private void performRecapitalization()")
            .substringBefore("public int getCurrentAutoCapsState(")
        val compute = body.indexOf("final int numCharsSelected = selectionEnd - selectionStart;")
        val guard = body.indexOf("if (numCharsSelected < 0) {")
        val rotate = body.indexOf("mRecapitalizeStatus.rotate();")
        assertTrue("the guard follows the length computation", compute in 0 until guard)
        assertTrue("and precedes the edit", guard in 0 until rotate)
    }
}
