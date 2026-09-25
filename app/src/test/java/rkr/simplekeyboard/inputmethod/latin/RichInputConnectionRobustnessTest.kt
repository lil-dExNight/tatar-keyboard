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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.common.Constants

/**
 * The behavioral half of the 2026-09-25 input-robustness fix wave
 * (docs/SECURITY-AUDIT-2026-09-25-FIXES.md): the pieces of [RichInputConnection] that run
 * without a live editor, driven exactly like [CacheTextStartProvenanceTest] drives them —
 * `RichInputConnection(null)` plus the package-private seams. The structural half (batch
 * pairing, dead-editor guards, the reload's threading) lives in
 * [RichInputConnectionRobustnessContractTest], because those paths need a live `LatinIME`.
 */
class RichInputConnectionRobustnessTest {

    private val window = Constants.EDITOR_CONTENTS_CACHE_SIZE

    // --- F3: the before-cursor cache is bounded ------------------------------------------------

    @Test
    fun appendingBeyondTheWindowKeepsTheTailAndDropsTheProvenance() {
        val connection = RichInputConnection(null)
        connection.onBeforeCursorCacheReloaded("мин ")

        // 4 + 1024 chars: one char over the window — the head must go.
        connection.appendToTextBeforeCursor("ю".repeat(window))

        assertEquals(window, connection.cachedTextBeforeCursor.length)
        assertEquals("ю".repeat(window), connection.cachedTextBeforeCursor.toString())
        assertFalse(
            "cutting the window's head destroys the text-start edge, so the provenance clears",
            connection.cacheReachedTextStart(),
        )
    }

    @Test
    fun appendingUpToTheWindowKeepsTheProvenance() {
        val connection = RichInputConnection(null)
        connection.onBeforeCursorCacheReloaded("мин ")

        // Exactly the window size: nothing was cut, the start edge is intact.
        connection.appendToTextBeforeCursor("ю".repeat(window - 4))

        assertEquals("мин " + "ю".repeat(window - 4), connection.cachedTextBeforeCursor.toString())
        assertTrue(connection.cacheReachedTextStart())
    }

    // --- F4: a lying SurroundingText falls back to the empty cache ------------------------------

    @Test
    fun aValidSurroundingTextSplitsAroundTheSelection() {
        val connection = RichInputConnection(null)

        assertTrue(connection.applyTextAroundCursor("hello world", 3, 5))

        assertEquals("hel", connection.cachedTextBeforeCursor.toString())
        assertEquals("lo", connection.selectedText.toString())
        assertEquals(" world", connection.cachedTextAfterCursor.toString())
    }

    @Test
    fun anInvertedOrOutOfRangeSurroundingSelectionYieldsEmptyCaches() {
        val connection = RichInputConnection(null)
        connection.applyTextAroundCursor("hello world", 3, 5)

        // start > end: the pre-fix code crashed here in String.subSequence.
        assertFalse(connection.applyTextAroundCursor("hello world", 5, 3))
        assertEquals("", connection.cachedTextBeforeCursor.toString())
        assertEquals("", connection.selectedText.toString())
        assertEquals("", connection.cachedTextAfterCursor.toString())

        assertFalse("negative start", connection.applyTextAroundCursor("hello world", -1, 3))
        assertFalse("end past the text", connection.applyTextAroundCursor("hello world", 3, 100))
        assertEquals("", connection.cachedTextBeforeCursor.toString())

        // A cursor at the very end is valid and stays valid.
        assertTrue(connection.applyTextAroundCursor("hello world", 11, 11))
        assertEquals("hello world", connection.cachedTextBeforeCursor.toString())
        assertEquals("", connection.cachedTextAfterCursor.toString())
    }

    // --- F7: an inverted selection report is normalized -----------------------------------------

    @Test
    fun anInvertedSelectionReportIsNormalized() {
        val connection = RichInputConnection(null)

        connection.updateSelection(10, 5)

        assertEquals(5, connection.expectedSelectionStart)
        assertEquals(10, connection.expectedSelectionEnd)
        assertTrue(connection.hasSelection())

        connection.updateSelection(7, 7)
        assertEquals(7, connection.expectedSelectionStart)
        assertEquals(7, connection.expectedSelectionEnd)
        assertFalse(connection.hasSelection())
    }

    // --- F1: the paste threshold -----------------------------------------------------------------

    @Test
    fun thePasteThresholdAdmitsOnlySmallNonEmptyClips() {
        // The boundary values name MAX_DIRECT_PASTE_CHARS = 64 * 1024 (the constant is private;
        // the contract test pins the declaration).
        assertFalse(RichInputConnection.shouldCommitPasteDirectly(null))
        assertFalse(RichInputConnection.shouldCommitPasteDirectly(""))
        assertTrue(RichInputConnection.shouldCommitPasteDirectly("сәлам"))
        assertTrue(RichInputConnection.shouldCommitPasteDirectly("х".repeat(64 * 1024 - 1)))
        assertFalse(
            "at the threshold the editor pastes itself — no parcel copy crosses the binder",
            RichInputConnection.shouldCommitPasteDirectly("х".repeat(64 * 1024)),
        )
        assertFalse(RichInputConnection.shouldCommitPasteDirectly("х".repeat(70 * 1024)))
    }

    // --- F11: getUnicodeSteps at ZWJ tails --------------------------------------------------------

    @Test
    fun aZwjClusterEndingAtTheCursorIsOneStep() {
        val connection = RichInputConnection(null)
        connection.onBeforeCursorCacheReloaded("а‍б")

        assertEquals(-3, connection.getUnicodeSteps(-1, false))
    }

    /**
     * The F11 boundary fix: the cached window is a TAIL slice of the editor text, so a ZWJ can
     * sit at index 0 (the slice started mid-cluster). The old `i > 1` boundary skipped the
     * ZWJ check at index 1 and left the dangling joiner behind the cursor; `i >= 1` swallows it.
     */
    @Test
    fun aZwjTailAtTheWindowStartIsSwallowedWhole() {
        val connection = RichInputConnection(null)
        connection.onBeforeCursorCacheReloaded("‍б")

        assertEquals(-2, connection.getUnicodeSteps(-1, false))
    }

    @Test
    fun aZwjPlusEmojiTailAtTheWindowStartIsSwallowedWhole() {
        val connection = RichInputConnection(null)
        // ZWJ + one surrogate pair: three UTF-16 chars, one cluster tail.
        connection.onBeforeCursorCacheReloaded("‍👩")

        assertEquals(-3, connection.getUnicodeSteps(-1, false))
    }

    @Test
    fun ordinaryAndSurrogateStepsAreUnchanged() {
        val connection = RichInputConnection(null)
        connection.onBeforeCursorCacheReloaded("аб")
        assertEquals(-1, connection.getUnicodeSteps(-1, false))

        connection.onBeforeCursorCacheReloaded("😀")
        assertEquals("a surrogate pair is one step", -2, connection.getUnicodeSteps(-1, false))
    }

    @Test
    fun anEmptyCacheReturnsTheRequestUnchanged() {
        val connection = RichInputConnection(null)

        assertEquals(-1, connection.getUnicodeSteps(-1, false))
        assertEquals(1, connection.getUnicodeSteps(1, false))
    }

    @Test
    fun aForwardStepOverAClusterCoversTheWholeCluster() {
        val connection = RichInputConnection(null)
        connection.applyTextAroundCursor("б‍в", 0, 0)

        assertEquals(3, connection.getUnicodeSteps(1, false))

        connection.applyTextAroundCursor("аб", 0, 0)
        assertEquals(1, connection.getUnicodeSteps(1, false))
    }
}
