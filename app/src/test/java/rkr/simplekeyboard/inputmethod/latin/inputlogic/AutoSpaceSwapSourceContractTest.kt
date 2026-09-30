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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A punctuation mark typed right after an accepted suggestion's auto-space replaces that space
 * ("сүз " + "," gives "сүз, "). Only the two paths that append the auto-space and the swap itself
 * arm it, every other edit path disarms it, and the separator handler checks the cursor position
 * before it sends the mark.
 *
 * Asserted by source for the reason given in [CommitPathConnectionContractTest]; the character
 * set itself is tested in `TatarWordUtilsTest`.
 */
class AutoSpaceSwapSourceContractTest {

    private val source by lazy {
        val roots = listOf(File("src/main"), File("app/src/main"))
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
        File(root, "java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java").readText()
    }

    /** The body of the method whose declaration contains [signature], by brace matching. */
    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is missing", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("unbalanced braces after $signature")
    }

    private val arm = "mAutoSpaceCursor = appendsAutoSpace\n" +
        "                ? mConnection.getExpectedSelectionStart() : NO_AUTO_SPACE;"

    @Test
    fun onlyTheAutoSpacePathsArmTheSwap() {
        assertEquals(2, Regex(Regex.escape(arm)).findAll(source).count())
        assertTrue(body("private boolean replaceTrailingWord(").contains(arm))
        assertTrue(body("public boolean commitPredictedWord(").contains(arm))
        // Armed only after the edit reached the editor.
        for (name in listOf("private boolean replaceTrailingWord(", "public boolean commitPredictedWord(")) {
            val method = body(name)
            assertTrue("$name arms after the connection check",
                method.indexOf("if (!connected) {") < method.indexOf(arm))
        }
    }

    @Test
    fun everyOtherEditPathDisarmsIt() {
        val resets = listOf(
            "public void startInput(",
            "public InputTransaction onTextInput(",
            "public void onUpdateSelection(",
            "private void handleConsumedEvent(",
            "private void handleNonSeparatorEvent(",
            "private void handleBackspaceEvent(",
            "public boolean revertTatarAutocorrection(",
            "public int commitGlideWord(",
            "public boolean replaceGlideLiftedWord(",
            "public boolean deleteGlideLiftedWord(",
        )
        for (name in resets) {
            assertTrue("$name must reset the auto-space position",
                body(name).contains("mAutoSpaceCursor = NO_AUTO_SPACE;"))
        }
    }

    @Test
    fun theSeparatorChecksThePositionBeforeSendingTheMark() {
        val separator = body("private void handleSeparatorEvent(")
        val position = separator.indexOf(
            "mAutoSpaceCursor == mConnection.getExpectedSelectionStart()")
        val consumed = separator.indexOf("mAutoSpaceCursor = NO_AUTO_SPACE;")
        val swap = separator.indexOf("TatarWordUtils.swapsWithAutoSpace(event.mCodePoint)")
        val send = separator.indexOf("sendKeyCodePoint(event.mCodePoint);")
        assertTrue("the position check comes first", position in 0 until consumed)
        assertTrue("the swap is one-shot", consumed < swap)
        assertTrue("the swap precedes the plain send", swap in 0 until send)
        assertTrue("the space before the cursor is re-checked",
            separator.contains("mConnection.getCodePointBeforeCursor() == Constants.CODE_SPACE"))
        assertTrue("no swap over a selection", separator.contains("!mConnection.hasSelection()"))
        // The moved space stays replaceable, so a run of marks stays together ("сүз?! ").
        val rearm = separator.indexOf("mAutoSpaceCursor = mConnection.getExpectedSelectionStart();")
        assertTrue("the swap re-arms after its batch",
            rearm > separator.indexOf("mConnection.endBatchEdit();") && rearm < send)
    }

    @Test
    fun theOnlyOtherArmingIsTheSwapItself() {
        val arms = Regex("mAutoSpaceCursor = mConnection\\.getExpectedSelectionStart\\(\\);")
        assertEquals(1, arms.findAll(source).count())
        assertTrue(arms.containsMatchIn(body("private void handleSeparatorEvent(")))
    }
}
