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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wiring behind [CursorMoveBandTest], pinned in source shape because the classes that carry it
 * (`LatinIME`, `RichInputConnection`) cannot be instantiated without Android.
 *
 * The rule in one sentence: **every path that blanks the band for a cursor move must also ask for
 * it back once that move has settled** — except the emoji panel and the emoji search, which route
 * through the same invalidation precisely to get a band that stays empty while they are up. Without
 * the re-derivation, a space slide, a delete swipe or a tap into the text leaves the strip blank
 * until the next keystroke.
 */
class CursorMoveBandSourceContractTest {

    @Test
    fun theControllerOffersTheOtherHalfOfACursorMove() {
        val controller = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt",
        )
        assertTrue(
            "SuggestionsController must expose onCursorMoveSettled()",
            controller.contains("fun onCursorMoveSettled()"),
        )
        // onSelectionChanged stays lookup-free: the emoji panel depends on it (see
        // SuggestionsControllerEmojiPanelBandStateTest), and on the external-move path the text
        // cache has not been refetched yet when it runs.
        val invalidation = kotlinBody(controller, "fun onSelectionChanged()")
        assertEquals(
            "onSelectionChanged must not look anything up",
            0,
            invalidation.occurrencesOf("requestCurrentPrefix("),
        )
    }

    @Test
    fun everyCursorMovePathAsksForTheBandBack() {
        val ime = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
        )
        // The keyboard's own cursor gestures — space slide, delete swipe, the release that ends
        // either — all funnel through this one method, and it now does both halves.
        val funnel = javaBody(ime, "private void onSuggestionsAffectingCursorMove()")
        assertEquals(1, funnel.occurrencesOf("mSuggestionsController.onSelectionChanged()"))
        assertEquals(1, funnel.occurrencesOf("mHandler.postRefreshSuggestionBand()"))

        // Exactly three places in LatinIME invalidate the band for a moved cursor, and each one is
        // accounted for: the gesture funnel above; onUpdateSelection, whose EXTERNAL move refetches
        // the text cache and gets the band back where that refetch lands (asserted in
        // anExternalMoveAsksForTheBandBackOnlyOnceItsTextCacheIsCurrent); and the emoji panel, which
        // must NOT get the band back — an empty band is its whole point.
        assertEquals(
            "onSelectionChanged has exactly three callers in LatinIME",
            3,
            ime.occurrencesOf("mSuggestionsController.onSelectionChanged()"),
        )
        val externalMove = javaBody(ime, "public void onUpdateSelection(final int oldSelStart")
        assertEquals(
            "an external move must invalidate the band",
            1,
            externalMove.occurrencesOf("mSuggestionsController.onSelectionChanged()"),
        )
        assertEquals(
            "an external move must refetch the text cache, which is what asks for the band back",
            1,
            externalMove.occurrencesOf("mInputLogic.reloadTextCache()"),
        )
        val refresh = javaBody(ime, "private void refreshSuggestionBandAfterCursorMove()")
        assertTrue(
            "the emoji panel must be excluded from the re-derivation",
            refresh.contains("mKeyboardSwitcher.isEmojiPanelShown()"),
        )
        assertTrue(
            "the emoji search must be excluded from the re-derivation",
            refresh.contains("mEmojiSearchQuery != null"),
        )
        assertEquals(1, refresh.occurrencesOf("mSuggestionsController.onCursorMoveSettled()"))

        // The message coalesces: one re-derivation per looper turn however many paths ask for it.
        val poster = javaBody(ime, "public void postRefreshSuggestionBand()")
        assertTrue(
            "the refresh message must coalesce",
            poster.contains("removeMessages(MSG_REFRESH_SUGGESTION_BAND)"),
        )
    }

    @Test
    fun anExternalMoveAsksForTheBandBackOnlyOnceItsTextCacheIsCurrent() {
        val connection = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java",
        )
        // An external cursor move refetches the text around the cursor on a background thread, so
        // the band may only be re-derived where that fetch lands — once per SDK branch of the
        // reload.
        val reload = javaBody(connection, "public void reloadTextCache()")
        assertEquals(
            "the band refresh must be posted from both branches of the cache reload",
            2,
            reload.occurrencesOf("mLatinIME.mHandler.postRefreshSuggestionBand()"),
        )
        // And it must be posted only when the cache is current on BOTH sides of the cursor. The
        // band is decided by the text after the cursor as much as by the text before it — a letter
        // right after the cursor means no candidates at all — so posting where the shift state is
        // posted (which needs the text BEFORE the cursor and nothing else) would re-derive the band
        // from a half-updated cache. The order is checked by index: in the pre-S branch the refresh
        // must come after the assignment of mTextAfterCursor, and in the S branch after
        // setTextAroundCursor, which sets both sides at once.
        val legacyStart = reload.indexOf("final CharSequence textBeforeCursor")
        assertTrue("the reload must still have its two SDK branches", legacyStart > 0)
        val modern = reload.substring(0, legacyStart)
        val legacy = reload.substring(legacyStart)
        assertTrue(
            "the S branch must re-derive after the whole surrounding text is set",
            modern.indexOf("setTextAroundCursor(textAroundCursor)") <
                modern.indexOf("postRefreshSuggestionBand()"),
        )
        assertTrue(
            "the pre-S branch must re-derive after the text AFTER the cursor is set",
            legacy.indexOf("mTextAfterCursor = textAfterCursor.toString()") <
                legacy.indexOf("postRefreshSuggestionBand()"),
        )
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun javaBody(source: String, signature: String): String = balancedBody(source, signature)

    private fun kotlinBody(source: String, signature: String): String = balancedBody(source, signature)

    /** The brace-balanced body of the declaration that starts with [signature]. */
    private fun balancedBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("declaration not found: $signature", start >= 0)
        var index = source.indexOf('{', start)
        assertTrue("body not found: $signature", index >= 0)
        val open = index
        var depth = 0
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        throw AssertionError("unbalanced braces after $signature")
    }

    private fun read(vararg paths: String): String =
        paths.map(::File).firstOrNull(File::isFile)?.readText()
            ?: error("file not found: ${paths.first()}")
}
