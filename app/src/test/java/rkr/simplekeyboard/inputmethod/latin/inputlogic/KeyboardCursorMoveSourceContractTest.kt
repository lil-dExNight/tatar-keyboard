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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cursor move made by the keyboard itself (space slide, delete swipe and the releases that end
 * them) drops the double-space and auto-space state, like an external move does. These moves go
 * through `setSelection`, so `InputLogic.onUpdateSelection` sees them as expected and never
 * resets anything.
 *
 * Asserted by source for the reason given in [CommitPathConnectionContractTest].
 */
class KeyboardCursorMoveSourceContractTest {

    private fun read(path: String): String {
        val roots = listOf(File("src/main"), File("app/src/main"))
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
        return File(root, "java/rkr/simplekeyboard/inputmethod/latin/$path").readText()
    }

    private val inputLogic by lazy { read("inputlogic/InputLogic.java") }
    private val latinIme by lazy { read("LatinIME.java") }

    /** The body of the method whose declaration contains [signature], by brace matching. */
    private fun body(source: String, signature: String): String {
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

    @Test
    fun aKeyboardCursorMoveResetsTheSpaceState() {
        val reset = body(inputLogic, "public void onKeyboardCursorMove(")
        assertTrue(reset.contains("mJustDoubleSpaced = false;"))
        assertTrue(reset.contains("mLastSpaceDownTime = 0;"))
        assertTrue(reset.contains("mAutoSpaceCursor = NO_AUTO_SPACE;"))
    }

    @Test
    fun theCursorMoveFunnelResetsBeforeAndWithoutTheSuggestionController() {
        val funnel = body(latinIme, "private void onSuggestionsAffectingCursorMove(")
        val reset = funnel.indexOf("mInputLogic.onKeyboardCursorMove();")
        assertTrue("the reset must not depend on the suggestion controller",
            reset in 0 until funnel.indexOf("if (mSuggestionsController"))
    }

    @Test
    fun everyKeyboardCursorGestureGoesThroughTheFunnel() {
        for (name in listOf(
            "public void onMoveCursorPointer(",
            "public void onMoveDeletePointer(",
            "public void onUpWithDeletePointerActive(",
            "public void onUpWithSpacePointerActive(",
        )) {
            assertTrue("$name must call the cursor-move funnel",
                body(latinIme, name).contains("onSuggestionsAffectingCursorMove();"))
        }
    }
}
