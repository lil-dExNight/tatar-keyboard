/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-25 audit, privacy: the lifecycle of the editor surrounding-text cache
 * (`RichInputConnection.mTextBeforeCursor` and friends), pinned in source because `LatinIME`
 * cannot be instantiated without Android.
 *
 * Two rules:
 *
 * 1. The cache dies with the input session on EVERY boundary — `onFinishInputView` always cleared
 *    it; `onFinishInputInternal` and `onWindowHidden` used to leave it in memory until the next
 *    field (a lock screen or a home gesture over an unchanged field), and now clear it too.
 *
 * 2. Password fields are never re-read into the cache at all: every `reloadTextCache` call site
 *    in `LatinIME` is gated by the password check. Auto-caps is unaffected by construction —
 *    `getCursorCapsMode` reads only the local cache (no IPC), and the user's own typing keeps
 *    that cache current inside a password field.
 */
class EditorTextCachePrivacySourceContractTest {

    @Test
    fun everySessionBoundaryClearsTheEditorTextCache() {
        val ime = read(LATIN_IME)
        // The signatures are anchored at the class-member indent (4 spaces): the UI handler
        // inner class declares an identically-named 8-space-indented onFinishInputView.
        for (signature in listOf(
            "\n    public void onFinishInputView(final boolean finishingInput)",
            "\n    void onFinishInputInternal()",
            "\n    public void onWindowHidden()",
        )) {
            assertTrue(
                "${signature.trim()} must clear the editor text cache",
                javaBody(ime, signature).contains("mInputLogic.clearCaches()"),
            )
        }
    }

    @Test
    fun passwordFieldsAreNeverReReadFromTheEditor() {
        val ime = read(LATIN_IME)
        // The field-start reload: password fields take the clear-instead-of-reload branch, and
        // the check runs on the fresh EditorInfo (the settings still describe the previous field
        // at that point).
        val startView = javaBody(ime, "void onStartInputViewInternal(final EditorInfo editorInfo")
        assertTrue(startView.contains("if (isPasswordField(editorInfo))"))
        assertTrue(startView.contains("mInputLogic.clearCaches();"))
        assertTrue(startView.contains("mInputLogic.mConnection.reloadTextCache(editorInfo, restarting)"))
        assertTrue(
            "the password check must precede the reload",
            startView.indexOf("isPasswordField(editorInfo)") <
                startView.indexOf("reloadTextCache(editorInfo, restarting)"),
        )

        // The two later reload paths (external cursor move, space-slide release) are gated too.
        val updateSelection = javaBody(ime, "public void onUpdateSelection(final int oldSelStart")
        assertGuardedReload(updateSelection, "onUpdateSelection")
        val spaceSlide = javaBody(ime, "public void onUpWithSpacePointerActive()")
        assertGuardedReload(spaceSlide, "onUpWithSpacePointerActive")
    }

    @Test
    fun autoCapsReadsOnlyTheLocalCacheNeverTheEditor() {
        val connection = read(RICH_INPUT_CONNECTION)
        val capsMode = javaBody(connection, "public int getCursorCapsMode(final int inputType")
        assertTrue(
            "caps mode derives from the local before-cursor cache",
            capsMode.contains("CapsModeUtils.getCapsMode(mTextBeforeCursor"),
        )
        assertFalse(
            "caps mode must not call into the editor (that would re-read a password field)",
            capsMode.contains("getSurroundingText(") || capsMode.contains("mIC.getTextBeforeCursor("),
        )
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun assertGuardedReload(body: String, name: String) {
        val guard = body.indexOf("if (!isCurrentFieldPasswordField())")
        val reload = body.indexOf("mInputLogic.reloadTextCache();")
        assertTrue("$name must gate its cache reload on the password check", guard >= 0)
        assertTrue("$name must still reload for ordinary fields", reload >= 0)
        assertTrue("$name: the guard must precede the reload", guard < reload)
    }

    /** The body of the Java method whose signature starts with [signatureStart]. */
    private fun javaBody(source: String, signatureStart: String): String {
        val start = source.indexOf(signatureStart)
        assertTrue("method not found: $signatureStart", start >= 0)
        val openBrace = source.indexOf('{', start)
        var depth = 0
        var index = openBrace
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
            index++
        }
        error("unbalanced braces after $signatureStart")
    }

    private fun read(path: String): String {
        val candidates = listOf(path, "app/$path")
        for (candidate in candidates) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        error("source not found: $path")
    }

    private companion object {
        const val LATIN_IME = "src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java"
        const val RICH_INPUT_CONNECTION =
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java"
    }
}
