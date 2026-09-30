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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O6 of `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md` — the InputConnection binder audit
 * (findings: `docs/IC-BINDER-AUDIT-2026-09-29.md`). Every `InputConnection` call is a binder
 * transaction into the host app, and the platform docs name binder stalls a top UI-thread stall
 * source, so the audit's verified properties are pinned here in the project's source-contract
 * idiom (the same reason `CommitPathConnectionContractTest` is written this way: `InputLogic`
 * and `RichInputConnection` need a live `LatinIME` and do not run in a plain JVM test).
 *
 * What is pinned:
 *
 * 1. Per keystroke exactly ONE editor mutation crosses the binder (`commitText`, or
 *    `deleteSurroundingText` for backspace); every per-keystroke READ is served by
 *    `RichInputConnection`'s local cache, and `InputLogic` never holds a raw connection.
 * 2. The whole editor-call inventory of `RichInputConnection` is count-anchored, so a new call
 *    site is a conscious act (fail-closed), and every editor READ lives inside the coalesced
 *    background reload — with the payload window bounded by `EDITOR_CONTENTS_CACHE_SIZE`.
 * 3. Key events stay deliberately unbatched (the platform ignores batch edits for them);
 *    `replaceText`'s two-call pre-34 fallback only ever runs inside `performRecapitalization`'s
 *    batch. The try/finally pairing of every batch is `BatchEditPairingContractTest`'s job and
 *    is not duplicated here.
 * 4. No View — neither the `keyboard/` package nor the latin-side canvas views — can reach the
 *    connection at all, which makes an IC call from a draw/layout/measure path unwritable.
 * 5. The cache refresh discipline: the reload is requested at exactly the known boundaries
 *    (field start via the EditorInfo PARCEL — no round trip on S+ —, cursor moves, the
 *    space-slide release), and both `EditorInfo` and the connection itself come from the
 *    framework's cached fields, never from a fresh binder query.
 */
class InputConnectionBinderContractTest {

    private val connection by lazy { readMain("$LATIN/RichInputConnection.java") }
    private val inputLogic by lazy { readMain("$LATIN/inputlogic/InputLogic.java") }
    private val latinIme by lazy { readMain("$LATIN/LatinIME.java") }

    // --- Q1: per-keystroke cost --------------------------------------------------------------

    @Test
    fun inputLogicReachesTheEditorOnlyThroughTheCacheAwareWrapper() {
        assertFalse(
            "InputLogic must not import the raw connection — every editor call is " +
                "RichInputConnection's, where the cache and the dead-editor guards live",
            inputLogic.contains("import android.view.inputmethod.InputConnection"),
        )
        assertFalse("InputLogic must never hold or call a raw InputConnection",
            inputLogic.contains("mIC."))
    }

    @Test
    fun aLetterKeystrokeCostsExactlyOneEditorMutation() {
        // The tail of every non-separator letter: one code point, one commitText. The digit
        // branch is the documented backward-compat exception (two key events, see below).
        val anchor = "private void sendKeyCodePoint"
        val at = inputLogic.indexOf(anchor)
        assertTrue("the sendKeyCodePoint method is missing", at >= 0)
        // The last method of the class — the rest of the file is its body plus the class brace.
        val body = inputLogic.substring(at)
        assertEquals(1, occurrences(body, "mConnection.commitText("))
        assertTrue("digits keep the sendDownUpKeyEvent compatibility path",
            body.contains("sendDownUpKeyEvent(codePoint - '0' + KeyEvent.KEYCODE_0)"))
        assertFalse("no editor READ may ride the per-keystroke commit",
            body.contains("getTextBeforeCursor(") || body.contains("getSurroundingText("))
    }

    @Test
    fun editorInfoAndConnectionComeFromTheFrameworkCache() {
        // No override anywhere in the service: InputMethodService answers both from fields it
        // already holds, so the per-keystroke EditorInfo reads (the action id, the password
        // gate) never cross the binder.
        assertFalse(latinIme.contains("getCurrentInputConnection"))
        assertFalse("the EditorInfo must stay the framework's cached field, not a re-query",
            latinIme.contains("EditorInfo getCurrentInputEditorInfo"))
    }

    // --- Q4: the read inventory — every editor read is the bounded background reload ----------

    @Test
    fun theOnlyEditorReadsAreTheCoalescedBackgroundReload() {
        val reloadBody = bodyOf(
            connection, "public void reloadTextCache() {", "private void finishReloadTextCache()")
        for (read in listOf(
            "mIC.getSurroundingText(",
            "mIC.getTextBeforeCursor(",
            "mIC.getTextAfterCursor(",
            "mIC.getSelectedText(",
        )) {
            assertEquals("$read exists exactly once in the tree's single IC holder",
                1, occurrences(connection, read))
            assertTrue("$read lives inside the coalesced background reload (F8/F10)",
                reloadBody.contains(read))
        }
        // The one payload-bearing call is bounded in both directions by the cache window; the
        // reload's threading and coalescing itself is RichInputConnectionRobustnessContractTest's.
        assertTrue(
            "the surrounding-text window stays bounded by EDITOR_CONTENTS_CACHE_SIZE",
            reloadBody.contains(
                "mIC.getSurroundingText(Constants.EDITOR_CONTENTS_CACHE_SIZE, " +
                    "Constants.EDITOR_CONTENTS_CACHE_SIZE, 0)"),
        )
        // The AOSP trap: InputConnection#getCursorCapsMode is a binder read. Caps come from the
        // local cache (CapsModeUtils), so this call must never appear.
        assertFalse(connection.contains("mIC.getCursorCapsMode("))
    }

    @Test
    fun theFieldStartCacheFillReadsTheEditorInfoParcelNotTheBinder() {
        val body = bodyOf(
            connection,
            "public void reloadTextCache(final EditorInfo",
            "private boolean mReloadInFlight",
        )
        assertTrue("the S+ branch fills the cache from the already-delivered parcel",
            body.contains(".getInitialSurroundingText(Constants.EDITOR_CONTENTS_CACHE_SIZE"))
        assertFalse("a binder read at field start is a waste — the parcel carries the text",
            body.contains("mIC.getSurroundingText("))
        assertFalse(body.contains("mIC.getTextBeforeCursor("))
    }

    // --- Q1/Q2: the write inventory — a new editor call site must go loud ---------------------

    @Test
    fun theEditorCallInventoryIsExactlyTheKnownSet() {
        val expected = mapOf(
            // commitText itself + the pre-34 replaceText fallback.
            "mIC.commitText(" to 2,
            // The replaceText fallback, deleteTextBeforeCursor, deleteSelectedText.
            "mIC.deleteSurroundingText(" to 3,
            "mIC.replaceText(" to 1,
            "mIC.setSelection(" to 1,
            "mIC.sendKeyEvent(" to 1,
            "mIC.performEditorAction(" to 1,
            // The large-paste fallback — the editor pulls the clipboard itself.
            "mIC.performContextMenuAction(" to 1,
            "mIC.beginBatchEdit(" to 1,
            "mIC.endBatchEdit(" to 1,
        )
        for ((call, count) in expected) {
            assertEquals(
                "$call count drifted in RichInputConnection — a new editor call site is a " +
                    "binder transaction and lands here consciously",
                count, occurrences(connection, call),
            )
        }
    }

    // --- Q2: the deliberate non-batches --------------------------------------------------------

    @Test
    fun keyEventsStayUnbatchedByPlatformDesign() {
        // The AOSP doctrine, kept in the sendDownUpKeyEvent javadoc: batch edits are ignored for
        // key events (they travel a different, asynchronous binder), so wrapping the DOWN/UP pair
        // would be dead code pretending to be a guard. Two fragments because the sentence wraps
        // across javadoc lines.
        assertTrue(inputLogic.contains("asynchronous binder. Also, batch edits"))
        assertTrue(inputLogic.contains("are ignored for key events"))
        val downUp = bodyOf(
            inputLogic,
            "public void sendDownUpKeyEvent(final int keyCode, final int metaState)",
            "private void sendKeyCodePoint(final int codePoint)",
        )
        assertEquals("the pair is DOWN + UP", 2, occurrences(downUp, "mConnection.sendKeyEvent("))
        assertFalse(downUp.contains("beginBatchEdit"))
        val sendKey = bodyOf(
            connection, "public void sendKeyEvent(final KeyEvent keyEvent)", "public void setSelection(")
        assertFalse(sendKey.contains("beginBatchEdit"))
    }

    @Test
    fun replaceTextsTwoCallFallbackOnlyEverRunsBatched() {
        // replaceText issues TWO calls on pre-UPSIDE_DOWN_CAKE (delete + commit); its single
        // caller is performRecapitalization, which wraps the whole rotation in one batch (the
        // try/finally shape is BatchEditPairingContractTest's pin).
        assertEquals("replaceText has exactly one caller", 1,
            occurrences(inputLogic, "mConnection.replaceText("))
        val body = bodyOf(
            inputLogic,
            "private void performRecapitalization()",
            "public int getCurrentAutoCapsState(",
        )
        val begin = body.indexOf("mConnection.beginBatchEdit();")
        val replace = body.indexOf("mConnection.replaceText(")
        val end = body.indexOf("mConnection.endBatchEdit();")
        assertTrue("the batch opens before the replace", begin in 0 until replace)
        assertTrue("and closes after it", replace < end)
        // The fallback itself keeps delete + commit adjacent, so the batch covers both.
        val fallback = bodyOf(connection, "public void replaceText(", "public void deleteTextBeforeCursor(")
        val delete = fallback.indexOf("mIC.deleteSurroundingText(0, numCharsSelected);")
        val commit = fallback.indexOf("mIC.commitText(text, 0);")
        assertTrue("the pre-34 fallback is delete-then-commit, adjacently", delete in 0 until commit)
    }

    // --- Q3: the draw path can never reach the binder ------------------------------------------

    @Test
    fun theKeyboardPackageNeverReferencesTheConnection() {
        val roots = listOf(File("src/main"), File("app/src/main"))
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
        val keyboardDir = File(root, "java/rkr/simplekeyboard/inputmethod/keyboard")
        assertTrue("the keyboard package is missing", keyboardDir.isDirectory)
        val offenders = keyboardDir.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                val text = file.readText()
                text.contains("InputConnection") || text.contains("mConnection")
            }
            .map { it.path }
            .toList()
        assertEquals(
            "a View that can reach the binder puts an IC call one step from the draw path: " +
                offenders.joinToString(),
            0, offenders.size,
        )
    }

    @Test
    fun theLatinSideCanvasViewsNeverReferenceTheConnection() {
        for (view in listOf(
            "$LATIN/suggestions/SuggestionStripView.kt",
            "$LATIN/emoji/EmojiPanelView.kt",
            "$LATIN/emoji/EmojiSearchView.kt",
        )) {
            val text = readMain(view)
            assertFalse("$view must not see the InputConnection", text.contains("InputConnection"))
            assertFalse("$view must not see the connection wrapper", text.contains("mConnection"))
            assertFalse("$view must not fetch a connection",
                text.contains("getCurrentInputConnection"))
        }
    }

    // --- Q5: the cache refresh discipline --------------------------------------------------------

    @Test
    fun theCacheIsReloadedAtExactlyTheKnownBoundaries() {
        // Cursor moves (external or self-caused — the reload revalidates the cache against the
        // editor's ground truth) and the space-slide release. The password gating of both is
        // EditorTextCachePrivacySourceContractTest's pin.
        assertEquals(
            "a reload per onUpdateSelection and per space-slide release, nowhere else",
            2, occurrences(latinIme, "mInputLogic.reloadTextCache();"),
        )
        assertEquals(
            "the field-start reload takes the EditorInfo parcel",
            1, occurrences(latinIme, "reloadTextCache(editorInfo, restarting)"),
        )
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun readMain(rel: String): String {
        val roots = listOf(File("src/main"), File("app/src/main"))
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
        val file = File(root, "java/$rel")
        assertTrue("source not found: ${file.path}", file.isFile)
        return file.readText()
    }

    private fun bodyOf(source: String, from: String, to: String): String {
        val body = source.substringAfter(from, "")
        assertTrue("anchor not found: $from", body.isNotEmpty())
        val end = body.indexOf(to)
        assertTrue("boundary not found after $from: $to", end >= 0)
        return body.substring(0, end)
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = haystack.indexOf(needle, index)
            if (index < 0) return count
            count++
            index += needle.length
        }
    }

    private companion object {
        const val LATIN = "rkr/simplekeyboard/inputmethod/latin"
    }
}
