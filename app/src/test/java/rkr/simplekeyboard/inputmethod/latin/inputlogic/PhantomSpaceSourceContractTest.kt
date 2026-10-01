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
 * A letter or digit typed right after a glide commit gets a space before it ("дөнья" + "а" gives
 * "дөнья а"). Only the two glide commit paths arm it, at the new cursor; every path that disarms
 * the auto-space disarms it too, and the non-separator handler checks the cursor first.
 *
 * Asserted by source, as in [AutoSpaceSwapSourceContractTest]: `InputLogic` needs a live
 * `LatinIME`.
 */
class PhantomSpaceSourceContractTest {

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

    private val arm = "mPhantomSpaceCursor = mConnection.getExpectedSelectionStart();"
    private val clear = "mPhantomSpaceCursor = NO_AUTO_SPACE;"

    @Test
    fun onlyTheGlideCommitPathsArmIt() {
        assertEquals(2, Regex(Regex.escape(arm)).findAll(source).count())
        for (name in listOf("public int commitGlideWord(", "public boolean replaceGlideLiftedWord(")) {
            val method = body(name)
            assertTrue("$name arms the phantom space", method.contains(arm))
            assertTrue("$name arms after the connection check",
                method.indexOf("if (!connected) {") < method.indexOf(arm))
            assertFalse("$name does not also clear it", method.contains(clear))
        }
    }

    @Test
    fun everyPathThatDisarmsTheAutoSpaceDisarmsItToo() {
        val paths = listOf(
            "public void startInput(",
            "public InputTransaction onTextInput(",
            "public void onUpdateSelection(",
            "public void onKeyboardCursorMove(",
            "private void handleConsumedEvent(",
            "private void handleNonSeparatorEvent(",
            "private void handleSeparatorEvent(",
            "private void handleBackspaceEvent(",
            "private boolean replaceTrailingWord(",
            "public boolean revertTatarAutocorrection(",
            "public boolean commitPredictedWord(",
            "public boolean deleteGlideLiftedWord(",
        )
        for (name in paths) {
            assertTrue("$name must clear the phantom space", body(name).contains(clear))
        }
        // Outside the two arming paths, each reset of the auto-space is paired with this one.
        val autoSpaceResets = Regex("^\\s+mAutoSpaceCursor = NO_AUTO_SPACE;", RegexOption.MULTILINE)
            .findAll(source).count()
        val paired = Regex("mAutoSpaceCursor = NO_AUTO_SPACE;\\n\\s*" + Regex.escape(clear))
            .findAll(source).count()
        assertEquals("every auto-space reset but the glide commits clears the phantom space",
            autoSpaceResets - 2, paired)
    }

    @Test
    fun aLetterOrDigitAtTheGlideCursorGetsTheSpaceInOneBatch() {
        val handler = body("private void handleNonSeparatorEvent(")
        val position = handler.indexOf(
            "mPhantomSpaceCursor == mConnection.getExpectedSelectionStart()")
        val consumed = handler.indexOf(clear)
        val kind = handler.indexOf("Character.isLetterOrDigit(event.mCodePoint)")
        val begin = handler.indexOf("mConnection.beginBatchEdit();")
        val commit = handler.indexOf(".append(' ')")
        val end = handler.indexOf("mConnection.endBatchEdit();")
        val send = handler.indexOf("sendKeyCodePoint(event.mCodePoint);")
        assertTrue("the position is read before the one-shot clear", position in 0 until consumed)
        assertTrue("only a letter or digit takes the space", consumed < kind)
        assertTrue("no space over a selection", handler.contains("!mConnection.hasSelection()"))
        assertTrue("the space and the code point commit in one batch",
            kind < begin && begin < commit && commit < end)
        assertTrue("the space path returns before the plain send",
            handler.indexOf("return;", end) in end until send)
    }

    @Test
    fun punctuationAndSpaceNeverTakeThePhantomSpace() {
        // Separators go to handleSeparatorEvent, which clears the phantom space without reading
        // it: "дөнья" + "," stays "дөнья,", and a typed space gives one space.
        val separator = body("private void handleSeparatorEvent(")
        assertTrue(separator.contains(clear))
        assertFalse(separator.contains("mPhantomSpaceCursor =="))
    }
}
