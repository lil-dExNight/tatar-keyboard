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

package rkr.simplekeyboard.inputmethod.latin

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.common.Constants

/**
 * S8 of `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md`: the host app owns the InputConnection and can
 * be malicious or buggy — the IME must not crash, OOM, or corrupt state. This suite drives the
 * REAL [RichInputConnection] against hostile host behavior through the same Android-free seams
 * [RichInputConnectionRobustnessTest] uses (`RichInputConnection(null)` plus the package-private
 * surface), extended with a fake [InputConnection] injected into the private `mIC` field for the
 * throwing-binder shapes. The suggestion-side half of the start/finish churn shape lives in
 * `suggestions/HostileHostSuggestionChurnTest.kt`; the structural pins (every editor call wrapped,
 * every reload write bounded) live in [RichInputConnectionRobustnessContractTest] and
 * `inputlogic/BatchEditPairingContractTest.kt`.
 *
 * Per-shape verdicts (2026-09-29):
 *
 *  * Oversized `getTextBeforeCursor`/`getSurroundingText` answers (the asked-for count is only a
 *    hint; the F3 window cap covered LOCAL appends only) — **HOLED, fixed**: a reload payload past
 *    the 1024-char window was stored verbatim, re-inflating the cache to binder-cap size and making
 *    every later append a full-length copy. `onBeforeCursorCacheReloaded` now keeps the window
 *    TAIL, `applyTextAroundCursor` keeps the after-cursor HEAD; the selection string is kept
 *    verbatim on purpose (it must stay consistent with the host-reported selection span; it is
 *    replaced wholesale by every reload, so it cannot grow without bound across reloads).
 *  * Editor calls throwing `RuntimeException` across the binder (`DeadObjectException` from a died
 *    host surfaces as a RuntimeException at the proxy; a hostile host can rethrow its own) —
 *    **HOLED, fixed**: nothing was caught anywhere (the F5 doctrine), so a throwing
 *    commit/delete/batch-close rode the UI thread up and killed the IME process. Every editor call
 *    in `RichInputConnection` now sits in a `catch (RuntimeException)` that degrades silently —
 *    the F6 dead-editor idiom (the F6 guards return silently too) — and the cache keeps following
 *    the INTENDED edit, which the next cursor-move reload re-syncs to the editor's ground truth
 *    (F8/F10). `InputLogic` needed no change: it reaches the editor only through this wrapper
 *    (O6-pinned), so the wrapper absorbing the failure covers every one of its paths.
 *  * Null / malformed SurroundingText — **CLEAN, extended**: null text and out-of-range/inverted
 *    selections already fell back to the empty cache (F4); this suite adds the absent null-text
 *    and `Int.MAX_VALUE` pins. Host-reported negative selection indexes were **HOLED, fixed**:
 *    `updateSelection` only normalized inversion (F7), so `(-5, -2)` survived as a "known" cursor
 *    position and `(5, -1)` normalized to `(-1, 5)` — `hasSelection()` TRUE for a span no host
 *    ever reported, which `deleteSelectedText` would have edited from. Any negative component now
 *    fails closed to the documented `INVALID_CURSOR_POSITION` state (EditorInfo's own "-1 means
 *    unknown" semantics).
 *  * Rapid start/finish-input churn — **CLEAN**: the Android-free seams are exercised under a
 *    seeded 20 000-step hostile op storm with per-step invariants; the suggestion-side interleave
 *    (an in-flight lookup applying after finishInput) was already guarded by the controller's
 *    session stamp and is re-proven under churn in the sibling suite.
 */
class HostileHostRobustnessTest {

    private val window = Constants.EDITOR_CONTENTS_CACHE_SIZE

    // --- Oversized host payloads (the asked-for window is a hint, not a bound) -------------------

    @Test
    fun anOversizedBeforeCursorReloadKeepsOnlyTheWindowTail() {
        val connection = RichInputConnection(null)
        // A hostile answer to getTextBeforeCursor(1024, 0), far past the requested window.
        val payload = "а".repeat(5000) + "б".repeat(window)

        connection.onBeforeCursorCacheReloaded(payload)

        assertEquals(window, connection.cachedTextBeforeCursor.length)
        assertEquals("the cursor end is the live end", "б".repeat(window),
            connection.cachedTextBeforeCursor.toString())
        assertFalse("a truncated reload cannot reach the text start",
            connection.cacheReachedTextStart())
    }

    @Test
    fun anOversizedSurroundingTextKeepsBothWindowEdges() {
        val connection = RichInputConnection(null)
        val before = "а".repeat(3000)
        val selection = "в".repeat(2000)
        val after = "б".repeat(3000)

        assertTrue(connection.applyTextAroundCursor(before + selection + after, 3000, 5000))

        assertEquals("а".repeat(window), connection.cachedTextBeforeCursor.toString())
        assertEquals("б".repeat(window), connection.cachedTextAfterCursor.toString())
        assertEquals("the selection keeps its host-reported span, verbatim",
            selection, connection.selectedText.toString())
        assertFalse(connection.cacheReachedTextStart())
    }

    @Test
    fun aOneMegabytePayloadLeavesTheWindowBoundedAndConsistent() {
        val connection = RichInputConnection(null)
        val half = 1 shl 19
        val payload = "а".repeat(half) + "б".repeat(half) // 1 Mi chars around a collapsed cursor

        assertTrue(connection.applyTextAroundCursor(payload, half, half))

        assertEquals(window, connection.cachedTextBeforeCursor.length)
        assertEquals(window, connection.cachedTextAfterCursor.length)
        // The kept window is exactly the payload around the cursor position.
        assertEquals("а".repeat(window), connection.cachedTextBeforeCursor.toString())
        assertEquals("б".repeat(window), connection.cachedTextAfterCursor.toString())
        // And stepping still works off the truncated window.
        assertEquals(-1, connection.getUnicodeSteps(-1, false))
        assertEquals(1, connection.getUnicodeSteps(1, false))
    }

    @Test
    fun theWindowInvariantSurvivesAppendsAfterAnOversizedReload() {
        val connection = RichInputConnection(null)
        connection.onBeforeCursorCacheReloaded("х".repeat(100 * window))

        connection.appendToTextBeforeCursor("ю")

        assertEquals("the F3 append bound re-converges the window at the next keystroke",
            window, connection.cachedTextBeforeCursor.length)

        // A truncation boundary leaves a window whose tail is a mid-word cluster: х‍б is ONE
        // grapheme (the ZWJ joins them), so one step back covers all three UTF-16 chars.
        connection.appendToTextBeforeCursor("‍б")
        assertEquals(-3, connection.getUnicodeSteps(-1, false))
    }

    // --- Null / malformed host reports ------------------------------------------------------------

    @Test
    fun aNullSurroundingTextYieldsEmptyCaches() {
        val connection = RichInputConnection(null)
        connection.applyTextAroundCursor("hello", 2, 2)

        assertFalse(connection.applyTextAroundCursor(null, 0, 0))

        assertEquals("", connection.cachedTextBeforeCursor.toString())
        assertEquals("", connection.selectedText.toString())
        assertEquals("", connection.cachedTextAfterCursor.toString())
    }

    @Test
    fun extremeSurroundingSelectionIndexesAreRejected() {
        val connection = RichInputConnection(null)

        assertFalse(connection.applyTextAroundCursor("hi", 0, Int.MAX_VALUE))
        assertFalse(connection.applyTextAroundCursor("hi", Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals("", connection.cachedTextBeforeCursor.toString())
        assertEquals("", connection.cachedTextAfterCursor.toString())
    }

    @Test
    fun aNegativeSelectionReportFailsClosedToNoCursorPosition() {
        val connection = RichInputConnection(null)
        connection.updateSelection(4, 4) // a sane position first

        connection.updateSelection(-5, -2) // garbage from the host

        assertFalse("a negative report carries no usable position", connection.hasCursorPosition())
        assertFalse(connection.hasSelection())
        assertEquals(-1, connection.expectedSelectionStart)
        assertEquals(-1, connection.expectedSelectionEnd)

        // The next honest report tracks again.
        connection.updateSelection(3, 3)
        assertTrue(connection.hasCursorPosition())
        assertEquals(3, connection.expectedSelectionStart)
    }

    @Test
    fun aMixedNegativeSelectionReportLeavesNoPhantomSelection() {
        val connection = RichInputConnection(null)

        // (5, -1): the F7 inversion swap alone would have stored (-1, 5) — hasSelection() TRUE for
        // a span no host ever reported, and deleteSelectedText would have computed a 6-char
        // selection length from it.
        connection.updateSelection(5, -1)

        assertFalse(connection.hasCursorPosition())
        assertFalse("a garbage report must not invent a selection", connection.hasSelection())
        assertEquals(-1, connection.expectedSelectionStart)
        assertEquals(-1, connection.expectedSelectionEnd)
    }

    // --- Editor calls throwing across the binder ----------------------------------------------------

    /**
     * A minimal hostile host. Every call is recordable, and the delete/batch-close calls can be
     * armed to throw — the local stand-in for a binder proxy whose remote side died
     * (`DeadObjectException` reaches the caller as a RuntimeException) or rethrew its own failure.
     * The unused methods answer inert defaults; none of them may be reached by the driven paths.
     */
    private class FakeInputConnection : InputConnection {
        val deleteCalls = mutableListOf<Pair<Int, Int>>()
        var deleteFailuresBeforeSuccess = 0
        var endBatchEditCalls = 0
        var endBatchFailures = 0

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (deleteFailuresBeforeSuccess > 0) {
                deleteFailuresBeforeSuccess--
                throw RuntimeException("the host died mid-call")
            }
            deleteCalls.add(beforeLength to afterLength)
            return true
        }

        override fun endBatchEdit(): Boolean {
            endBatchEditCalls++
            if (endBatchFailures > 0) {
                endBatchFailures--
                throw RuntimeException("the host died mid-call")
            }
            return true
        }

        override fun beginBatchEdit() = true
        override fun clearMetaKeyStates(states: Int) = true
        override fun closeConnection() {}
        override fun commitCompletion(text: CompletionInfo?) = true
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?) = true
        override fun commitCorrection(correctionInfo: CorrectionInfo?) = true
        override fun commitText(text: CharSequence?, newCursorPosition: Int) = true
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = true
        override fun finishComposingText() = true
        override fun getCursorCapsMode(reqModes: Int) = 0
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
        override fun getHandler(): Handler? = null
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = null
        override fun performContextMenuAction(id: Int) = true
        override fun performEditorAction(actionCode: Int) = true
        override fun performPrivateCommand(action: String?, data: Bundle?) = true
        override fun reportFullscreenMode(enabled: Boolean) = true
        override fun requestCursorUpdates(cursorUpdateMode: Int) = true
        override fun sendKeyEvent(event: KeyEvent?) = true
        override fun setComposingRegion(start: Int, end: Int) = true
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = true
        override fun setSelection(start: Int, end: Int) = true
    }

    @Test
    fun aThrowingDeleteSurroundingTextDoesNotEscapeAndTheNextDeleteLands() {
        val editor = FakeInputConnection()
        editor.deleteFailuresBeforeSuccess = 1
        val connection = RichInputConnection(null)
        injectEditor(connection, editor)
        connection.onBeforeCursorCacheReloaded("сүз")
        connection.updateSelection(3, 3)

        // The hostile editor throws across the binder: nothing may escape to the caller.
        connection.deleteTextBeforeCursor(1)
        // The cache follows the INTENDED edit either way (the same cache-first order commitText
        // uses); the next cursor-move reload re-syncs it to the editor's ground truth (F8/F10).
        assertEquals("сү", connection.cachedTextBeforeCursor.toString())

        // The very next keystroke against the recovered editor lands exactly what is expected.
        connection.deleteTextBeforeCursor(1)

        assertEquals("the recovered editor receives the retry", listOf(1 to 0), editor.deleteCalls)
        assertEquals("с", connection.cachedTextBeforeCursor.toString())
    }

    @Test
    fun aThrowingEndBatchEditLeavesTheNestLevelBalanced() {
        val editor = FakeInputConnection()
        editor.endBatchFailures = 1
        val connection = RichInputConnection(null)
        injectEditor(connection, editor)
        setNestLevel(connection, 1) // as if a beginBatchEdit had succeeded

        connection.endBatchEdit() // must not escape

        assertEquals("the nest level is ours: it balances even when the editor's close throws",
            0, nestLevelOf(connection))
        assertEquals(1, editor.endBatchEditCalls)

        // A later balanced close against the recovered editor still reaches it.
        setNestLevel(connection, 1)
        connection.endBatchEdit()
        assertEquals(2, editor.endBatchEditCalls)
    }

    // --- Rapid hostile churn ------------------------------------------------------------------------

    /**
     * A seeded storm over every Android-free seam a host report can reach: absurd selection
     * reports (negative, inverted, huge), malformed and oversized SurroundingText payloads,
     * oversized reloads, deletes and unicode steps interleaved. After EVERY step the editor-facing
     * invariants must hold: both cache windows bounded, the text-start provenance consistent with
     * the cache length, and the expected selection either unknown (-1, -1) or a sane ordered pair.
     */
    @Test
    fun aSeededHostileChurnKeepsEveryInvariant() {
        val connection = RichInputConnection(null)
        val random = java.util.Random(20260929L)
        val alphabet = "абвгҗңһүәө ‍👩" // includes a ZWJ and a surrogate pair

        fun hostileText(maxLen: Int): String = buildString(random.nextInt(maxLen + 1)) {
            append(alphabet[random.nextInt(alphabet.length)])
        }

        repeat(20_000) { step ->
            when (random.nextInt(6)) {
                0 -> connection.updateSelection(
                    random.nextInt(3000) - 500, random.nextInt(3000) - 500)
                1 -> {
                    val text = hostileText(2500)
                    connection.applyTextAroundCursor(
                        text,
                        if (text.isEmpty()) 0 else random.nextInt(text.length + 1),
                        if (text.isEmpty()) 0 else random.nextInt(text.length + 1),
                    )
                }
                2 -> connection.appendToTextBeforeCursor(hostileText(window + 8))
                3 -> connection.deleteTextBeforeCursor(random.nextInt(64))
                4 -> connection.onBeforeCursorCacheReloaded(hostileText(3 * window))
                5 -> connection.getUnicodeSteps(random.nextInt(9) - 4, random.nextBoolean())
            }
            assertInvariantsHold(connection, step)
        }
    }

    private fun assertInvariantsHold(connection: RichInputConnection, step: Int) {
        val before = connection.cachedTextBeforeCursor.length
        val after = connection.cachedTextAfterCursor.length
        assertTrue("step $step: the before-cursor cache never exceeds the window",
            before <= window)
        assertTrue("step $step: the after-cursor cache never exceeds the window",
            after <= window)
        if (connection.cacheReachedTextStart()) {
            assertTrue("step $step: the text-start provenance implies a short cache",
                before < window)
        }
        val start = connection.expectedSelectionStart
        val end = connection.expectedSelectionEnd
        assertTrue(
            "step $step: the expected selection is either unknown or a sane ordered pair",
            (start == -1 && end == -1) || (start in 0..end),
        )
    }

    @Test
    fun extremeUnicodeStepRequestsStayBoundedByTheWindow() {
        val connection = RichInputConnection(null)
        connection.applyTextAroundCursor("абвг", 2, 2) // before "аб", after "вг"

        // An absurd request counts at most the cached window — no hang, no overflow.
        assertEquals(-2, connection.getUnicodeSteps(Int.MIN_VALUE, false))
        assertEquals(2, connection.getUnicodeSteps(Int.MAX_VALUE, false))
    }

    // --- Reflection seam ----------------------------------------------------------------------------

    /**
     * The fake-editor injection. `RichInputConnection` refreshes `mIC` from the live `LatinIME`
     * in most methods, but the driven paths (`deleteTextBeforeCursor`, `endBatchEdit`) read the
     * field directly — exactly the field the framework-facing refresh would have written.
     */
    private fun injectEditor(connection: RichInputConnection, editor: InputConnection) {
        val field = RichInputConnection::class.java.getDeclaredField("mIC")
        field.isAccessible = true
        field.set(connection, editor)
    }

    private fun nestLevelOf(connection: RichInputConnection): Int {
        val field = RichInputConnection::class.java.getDeclaredField("mNestLevel")
        field.isAccessible = true
        return field.getInt(connection)
    }

    private fun setNestLevel(connection: RichInputConnection, level: Int) {
        val field = RichInputConnection::class.java.getDeclaredField("mNestLevel")
        field.isAccessible = true
        field.setInt(connection, level)
    }
}
