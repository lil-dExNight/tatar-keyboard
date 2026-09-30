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

package rkr.simplekeyboard.inputmethod.latin.emoji

/**
 * The parts of [EmojiSearchView] geometry and state that can go wrong without a crash, kept
 * Android-free so they run on the plain JVM (the same split as the panel and [EmojiPanelState]):
 * caret position, hint position, whether there is a query, and the measured height.
 */
internal object EmojiSearchLayout {

    /**
     * X of the caret: the right edge of the drawn query and nothing else. The caret follows the
     * text, so no inset, padding or key half-size is added: [textLeft] is where the query is drawn
     * from and [textWidth] is its measured width. An extra offset reads as a trailing space.
     */
    fun caretX(textLeft: Float, textWidth: Float): Float = textLeft + textWidth

    /**
     * Where the hint is drawn from while there is no query: past the right edge of the caret, which
     * stands at [caretX] and is [caretStrokeWidth] wide, plus [gapPx]. Drawing the hint from the
     * caret's own x would put the caret on the hint's first letter.
     */
    fun hintLeft(caretX: Float, caretStrokeWidth: Float, gapPx: Float): Float =
        caretX + caretStrokeWidth / 2f + gapPx

    /**
     * Whether the field holds a query. The whole view uses this one answer: for the result band,
     * the measured height, the hint and the screen-reader text. A run of spaces is not a query,
     * because [EmojiSearchIndex.search] trims before matching and spaces alone never match.
     */
    fun hasQuery(queryText: String): Boolean = queryText.isNotBlank()

    /**
     * Whether the result band under the query row is shown: exactly when [hasQuery]. An empty query
     * shows no placeholder band; the space goes to the keyboard instead.
     */
    fun showsResultBand(queryText: String): Boolean = hasQuery(queryText)

    /**
     * Height the view asks for: the query row always, the result band only while there is a query.
     */
    fun contentHeight(queryRowPx: Int, resultBandPx: Int, queryText: String): Int =
        if (showsResultBand(queryText)) queryRowPx + resultBandPx else queryRowPx
}
