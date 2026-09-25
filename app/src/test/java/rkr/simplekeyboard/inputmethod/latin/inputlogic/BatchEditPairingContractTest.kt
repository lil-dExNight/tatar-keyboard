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
 * 2026-09-25 audit, F5 (docs/SECURITY-AUDIT-2026-09-25-FIXES.md): every
 * beginBatchEdit/endBatchEdit pair in `InputLogic` and `RichInputConnection` is a try/finally —
 * a RuntimeException from a dying editor mid-edit must not skip the endBatchEdit and stick the
 * batch nest level forever. Nothing is caught: the exception propagates exactly as before.
 *
 * Asserted by source because both classes need a live `LatinIME` to run; the regexes are kept
 * honest by the count anchors at the bottom (nine batches in InputLogic, one in
 * RichInputConnection.deleteSelectedText).
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
    fun nothingIsCaughtAroundTheBatches() {
        for ((name, src) in files) {
            assertFalse(
                "$name: F5 is finally-only — a catch here would swallow the dying editor's " +
                    "exception, which must propagate exactly as before",
                Regex("\\bcatch\\s*\\(").containsMatchIn(src),
            )
        }
    }

    /** The scan is meaningful only while it sees the batches it claims to pair. */
    @Test
    fun theScanAnchorsToTheKnownBatchCounts() {
        assertEquals(9, batchCall("beginBatchEdit").findAll(files.getValue("InputLogic")).count())
        assertEquals(9, batchCall("endBatchEdit").findAll(files.getValue("InputLogic")).count())
        assertEquals(
            1,
            batchCall("beginBatchEdit").findAll(files.getValue("RichInputConnection")).count(),
        )
        assertEquals(
            1,
            batchCall("endBatchEdit").findAll(files.getValue("RichInputConnection")).count(),
        )
    }

    /** The F7 sibling of the pairing: the negative-range guard ahead of the recapitalization. */
    @Test
    fun performRecapitalizationRefusesANegativeSelectionLength() {
        val body = files.getValue("InputLogic")
            .substringAfter("private void performRecapitalization()")
            .substringBefore("/**\n     * Gets the current auto-caps state")
        val compute = body.indexOf("final int numCharsSelected = selectionEnd - selectionStart;")
        val guard = body.indexOf("if (numCharsSelected < 0) {")
        val rotate = body.indexOf("mRecapitalizeStatus.rotate();")
        assertTrue("the guard follows the length computation", compute in 0 until guard)
        assertTrue("and precedes the edit", guard in 0 until rotate)
    }
}
