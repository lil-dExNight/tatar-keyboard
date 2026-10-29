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
 * A functional code with no branch in `handleFunctionalEvent` is a no-op: the event is consumed,
 * the text is untouched and the IME keeps running. Every code the switch knows keeps its own
 * branch.
 *
 * Asserted by source, as in [PhantomSpaceSourceContractTest]: `InputLogic` needs a live
 * `LatinIME`.
 */
class UnknownFunctionalKeyContractTest {

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

    /** The default branch of the functional-key switch, comments stripped, whitespace folded. */
    private fun defaultBranch(): String {
        val method = body("private void handleFunctionalEvent(")
        val start = method.indexOf("default:")
        assertTrue("the functional switch has a default branch", start >= 0)
        return method.substring(start)
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    @Test
    fun anUnknownFunctionalCodeNeverThrows() {
        assertFalse(defaultBranch().contains("throw"))
    }

    @Test
    fun anUnknownFunctionalCodeLeavesTheTextAlone() {
        assertEquals("default: break; } }", defaultBranch())
    }

    @Test
    fun everyKnownFunctionalCodeKeepsItsBranch() {
        val method = body("private void handleFunctionalEvent(")
        val known = listOf(
            "CODE_DELETE", "CODE_SHIFT", "CODE_CAPSLOCK", "CODE_SYMBOL_SHIFT",
            "CODE_SWITCH_ALPHA_SYMBOL", "CODE_SETTINGS", "CODE_PASTE", "CODE_SELECT_ALL",
            "CODE_CUT", "CODE_COPY", "CODE_PASTE_CONTEXT_MENU", "CODE_CURSOR_LEFT",
            "CODE_CURSOR_RIGHT", "CODE_ACTION_NEXT", "CODE_ACTION_PREVIOUS",
            "CODE_LANGUAGE_SWITCH", "CODE_EMOJI", "CODE_SHIFT_ENTER",
        )
        for (code in known) {
            assertTrue("$code keeps its branch", method.contains("case Constants.$code:"))
        }
    }
}
