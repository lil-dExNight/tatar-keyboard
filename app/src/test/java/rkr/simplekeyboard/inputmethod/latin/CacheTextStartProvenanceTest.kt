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
 * "The before-cursor cache starts at the start of the text" is provenance, carried by
 * [RichInputConnection] from the full reload that filled the cache, never re-derived from the
 * cache's length. A length rule (`length < EDITOR_CONTENTS_CACHE_SIZE`) breaks under local
 * mutations: a cursor swipe re-slices
 * the cached window to a handful of chars ([RichInputConnection.setSelection]) and a long
 * backspace run truncates it ([RichInputConnection.deleteTextBeforeCursor]); both leave a short
 * cache whose index 0 is NOT the text start, and the length rule would accept a truncated word
 * there as a whole NEXT_WORD context.
 *
 * Only Android-free paths are driven here: [RichInputConnection.onBeforeCursorCacheReloaded] is
 * the single writer every full reload funnels through, [RichInputConnection.deleteTextBeforeCursor]
 * is the one mutation reachable without a live editor, and the invariant the tests pin — local
 * mutations preserve the provenance — also covers commitText/setSelection, which only move the
 * cursor-side edge of the same window.
 */
class CacheTextStartProvenanceTest {

    private val window = Constants.EDITOR_CONTENTS_CACHE_SIZE

    @Test
    fun aShortFullReloadProvesTheTextStart() {
        val connection = RichInputConnection(null)

        connection.onBeforeCursorCacheReloaded("мин ")

        assertTrue(connection.cacheReachedTextStart())
        assertEquals("мин ", connection.cachedTextBeforeCursor.toString())
    }

    @Test
    fun aFullWindowReloadDoesNotClaimTheTextStart() {
        val connection = RichInputConnection(null)

        // Exactly the window size: there may be more text above, so it is not the text start.
        connection.onBeforeCursorCacheReloaded("х".repeat(window))

        assertFalse(connection.cacheReachedTextStart())
    }

    /**
     * A cache that was filled to the full window (start unknown) loses most of
     * its length to local edits. The length rule would now answer "text start reached" for a cache
     * that starts mid-text; the provenance must not move.
     */
    @Test
    fun backspaceDoesNotInventTheTextStart() {
        val connection = RichInputConnection(null)
        connection.onBeforeCursorCacheReloaded("х".repeat(window))
        connection.updateSelection(window, window)

        connection.deleteTextBeforeCursor(window - 24)

        assertEquals(24, connection.cachedTextBeforeCursor.length)
        assertFalse(
            "a 24-char cache carved out of a full window is NOT known to start at the text start",
            connection.cacheReachedTextStart(),
        )
    }

    /** The other direction: edits must not un-prove a cache that genuinely starts at the text. */
    @Test
    fun backspaceKeepsAProvenTextStart() {
        val connection = RichInputConnection(null)
        // An empty field reloaded after "мин " was typed: the whole text is the cache.
        connection.onBeforeCursorCacheReloaded("мин ")
        connection.updateSelection(4, 4)

        connection.deleteTextBeforeCursor(1)

        assertEquals("мин", connection.cachedTextBeforeCursor.toString())
        assertTrue(
            "deleting at the cursor cannot move the window's start edge",
            connection.cacheReachedTextStart(),
        )
    }
}
