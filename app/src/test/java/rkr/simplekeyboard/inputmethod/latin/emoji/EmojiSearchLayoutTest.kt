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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emoji search layout rules: the caret sits at the end of the typed query, the result band does
 * not exist while the query is empty, a query shows two result rows, a field holding only spaces
 * is not a query, and the caret does not stand on the hint.
 *
 * The pure half is checked directly; the half inside `onDraw` / `onMeasure` is checked by grepping
 * the source, like [EmojiPanelSourceContractTest].
 */
class EmojiSearchLayoutTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private val view by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/emoji/EmojiSearchView.kt")
            .readText()
    }

    private fun bodyOf(start: String, end: String) =
        view.substringAfter(start).substringBefore(end)

    /** Every argument list of [call] in [source], collapsed to one line each; nesting survives. */
    private fun argumentsOf(source: String, call: String): List<String> {
        val calls = ArrayList<String>()
        var from = source.indexOf(call)
        while (from >= 0) {
            var depth = 0
            var index = from + call.length - 1
            while (index < source.length) {
                when (source[index]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                index++
            }
            calls.add(
                source.substring(from + call.length, index)
                    .replace(Regex("\\s+"), " ")
                    .trim(),
            )
            from = source.indexOf(call, index)
        }
        return calls
    }

    // --- The pure rules -----------------------------------------------------------------------

    /**
     * The caret is exactly at the right edge of the drawn text; any extra offset reads as a
     * trailing space.
     */
    @Test
    fun theCaretXIsTheRightEdgeOfTheTextAndNothingElse() {
        assertEquals(65f, EmojiSearchLayout.caretX(40f, 25f), 0f)
        // An empty query: the caret sits where the text would start, against the hint.
        assertEquals(40f, EmojiSearchLayout.caretX(40f, 0f), 0f)
        // Whatever the text width, the caret moves with it one-to-one and gains nothing.
        var width = 0f
        while (width < 400f) {
            assertEquals(40f + width, EmojiSearchLayout.caretX(40f, width), 0f)
            width += 7.5f
        }
    }

    @Test
    fun theResultBandExistsOnlyWhileThereIsAQuery() {
        assertFalse(EmojiSearchLayout.showsResultBand(""))
        assertTrue(EmojiSearchLayout.showsResultBand("к"))
        assertTrue(EmojiSearchLayout.showsResultBand("дьяво"))
    }

    @Test
    fun anEmptyQueryMeasuresTheQueryRowAloneAndATypedOneAddsTheBand() {
        assertEquals(46, EmojiSearchLayout.contentHeight(46, 54, ""))
        // The band is two rows of the row height: twice the results stay visible.
        assertEquals(46 + 2 * 54, EmojiSearchLayout.contentHeight(46, 54, "к"))
        // The band is the whole of the difference: nothing else changes with the query.
        assertEquals(
            2 * 54,
            EmojiSearchLayout.contentHeight(46, 54, "кот") - EmojiSearchLayout.contentHeight(46, 54, ""),
        )
    }

    // --- The two-row band's position <-> cell mapping -----------------------------------------

    @Test
    fun resultsFillTheBandColumnByColumn() {
        // A column holds one cell per row; the best matches sit in the first columns.
        assertEquals(0, EmojiSearchLayout.resultColumn(0))
        assertEquals(0, EmojiSearchLayout.resultRow(0))
        assertEquals(0, EmojiSearchLayout.resultColumn(1))
        assertEquals(1, EmojiSearchLayout.resultRow(1))
        assertEquals(1, EmojiSearchLayout.resultColumn(2))
        assertEquals(0, EmojiSearchLayout.resultRow(2))
        // Round trip across the whole result cap.
        for (position in 0 until 60) {
            assertEquals(
                position,
                EmojiSearchLayout.resultPosition(
                    EmojiSearchLayout.resultColumn(position),
                    EmojiSearchLayout.resultRow(position),
                ),
            )
        }
    }

    @Test
    fun theLastColumnMayHoldOneCell() {
        assertEquals(1, EmojiSearchLayout.resultColumnCount(1))
        assertEquals(1, EmojiSearchLayout.resultColumnCount(2))
        assertEquals(2, EmojiSearchLayout.resultColumnCount(3))
        assertEquals(30, EmojiSearchLayout.resultColumnCount(60))
        assertEquals(0, EmojiSearchLayout.resultColumnCount(0))
    }

    @Test
    fun theBandScrollsColumnsAndNeverPastTheLastOne() {
        // 14 results = 7 columns of 54 px in a 300 px view: the band scrolls until the last
        // column's right edge meets the view's right edge, and no further.
        assertEquals(0, EmojiSearchLayout.maxScrollX(14, 54, 7 * 54))
        assertEquals(7 * 54 - 300, EmojiSearchLayout.maxScrollX(14, 54, 300))
        assertEquals(0, EmojiSearchLayout.maxScrollX(2, 54, 300))
        assertEquals(0, EmojiSearchLayout.maxScrollX(0, 54, 300))
    }

    @Test
    fun theFirstVisibleColumnFollowsTheScrollOffset() {
        assertEquals(0, EmojiSearchLayout.firstVisibleColumn(0, 54))
        assertEquals(0, EmojiSearchLayout.firstVisibleColumn(53, 54))
        assertEquals(1, EmojiSearchLayout.firstVisibleColumn(54, 54))
        // A corrupted (negative) offset reads as the first column, never as a crash.
        assertEquals(0, EmojiSearchLayout.firstVisibleColumn(-10, 54))
    }

    @Test
    fun theLastResultIsFullyVisibleAtTheScrollLimit() {
        val cell = 54
        val width = 300
        val scroll = EmojiSearchLayout.maxScrollX(60, cell, width)
        val position = 59
        val left = EmojiSearchLayout.resultColumn(position) * cell - scroll
        val row = EmojiSearchLayout.resultRow(position)
        assertEquals(246, left)
        assertEquals(width, left + cell)
        assertEquals(1, row)
        assertEquals(46 + cell, 46 + row * cell)
        assertEquals(46 + 2 * cell, 46 + row * cell + cell)
    }

    @Test
    fun scrollingHasNoLimitWhenResultsOrCellsAreEmpty() {
        assertEquals(0, EmojiSearchLayout.maxScrollX(0, 54, 300))
        assertEquals(0, EmojiSearchLayout.maxScrollX(60, 0, 300))
        assertEquals(0, EmojiSearchLayout.maxScrollX(2, 54, 300))
    }

    // --- The caret sits at the end of the text ------------------------------------------------

    /**
     * Nothing but the text width may enter the caret's x. In particular `closeCrossPx` (the
     * half-size of the "✕" key at the other end of the pill) must not, or a 5dp gap appears.
     */
    @Test
    fun theCaretIsDrawnAtTheEndOfTheQueryAndNothingIsAddedToIt() {
        val queryRow = bodyOf("private fun drawQueryRow", "private fun drawCaret")
        val caretCalls = argumentsOf(queryRow, "drawCaret(")
        assertTrue("expected two drawCaret calls, found ${caretCalls.size}", caretCalls.size == 2)
        for (call in caretCalls) {
            assertFalse(
                "the caret x still carries the close-key constant: $call",
                call.contains("closeCrossPx"),
            )
            assertFalse(
                "the caret x carries a padding constant: $call",
                call.contains("InsetPx") && !call.contains("textLeft"),
            )
        }
        // The typed-text branch positions the caret through the pure helper, so the rule is
        // testable without a device.
        assertTrue(
            "the typed-text caret must go through EmojiSearchLayout.caretX",
            caretCalls.any { it.contains("EmojiSearchLayout.caretX") },
        )
    }

    /** The close-key constant is used only by the close key itself. */
    @Test
    fun theCloseCrossConstantIsUsedOnlyByTheCloseKey() {
        val queryRow = bodyOf("private fun drawQueryRow", "private fun drawCaret")
        val closeSection = queryRow.substringAfter("val closeX = w - pillInsetXPx - closeInsetPx")
        val outsideCloseKey = queryRow.substringBefore("val closeX = w - pillInsetXPx - closeInsetPx")
        assertFalse(
            "closeCrossPx is used before the close key is drawn",
            outsideCloseKey.contains("closeCrossPx"),
        )
        assertTrue(closeSection.contains("closeCrossPx"))
    }

    // --- No result band while the query is empty ----------------------------------------------

    /**
     * An empty query measures `queryRowPx` only; adding `resultRowPx` would reserve 54dp for an
     * empty band.
     */
    @Test
    fun theMeasuredHeightDropsTheResultBandWhileTheQueryIsEmpty() {
        val measure = bodyOf("override fun onMeasure", "override fun onDraw")
        assertFalse(
            "onMeasure still adds the result band unconditionally",
            measure.contains("queryRowPx + resultRowPx"),
        )
        assertTrue(
            "onMeasure must take its height from EmojiSearchLayout.contentHeight",
            measure.contains("EmojiSearchLayout.contentHeight"),
        )
    }

    /** The placeholder text is gone: an empty query draws no message at all. */
    @Test
    fun anEmptyQueryDrawsNoPlaceholderMessage() {
        val results = bodyOf("private fun drawResults", "private fun maxScrollX()")
        assertFalse(
            "the \"type a query\" placeholder is still drawn",
            results.contains("typeMoreText"),
        )
        assertTrue(
            "the \"nothing found\" message stays for a query that found nothing",
            results.contains("noResultsText"),
        )
    }

    /** A query that gains or loses its first character changes the measured height, so re-layout. */
    @Test
    fun changingTheQueryRequestsLayoutBecauseTheHeightCanChange() {
        val setQuery = bodyOf("fun setQuery(query: String)", "override fun onMeasure(")
        assertTrue("setQuery must requestLayout when the band appears or goes", setQuery.contains("requestLayout()"))
    }

    /** No node is announced for a band that is not there. */
    @Test
    fun theAccessibilityTreeNeverAnnouncesTheAbsentResultBand() {
        val visible = bodyOf("override fun getVisibleVirtualViews", "override fun onPopulateNodeForVirtualView")
        assertTrue(
            "result nodes must be guarded by the result count",
            visible.contains("if (resultCount == 0"),
        )
        assertTrue(visible.contains("resultCellPx <= 0 || resultRowPx <= 0 || width <= 0"))
    }

    // --- A query of spaces is not a query -----------------------------------------------------

    /**
     * The search trims before matching, so a run of spaces can never have a result; every other
     * part of the view must agree and treat such a field as empty (no "nothing found" band).
     */
    @Test
    fun aQueryOfNothingButSpacesIsNotAQuery() {
        assertFalse(EmojiSearchLayout.hasQuery(""))
        assertFalse(EmojiSearchLayout.hasQuery(" "))
        assertFalse(EmojiSearchLayout.hasQuery("      "))
        // Tabs and newlines never reach the query, but blankness must not depend on that.
        assertFalse(EmojiSearchLayout.hasQuery("\u00a0 \u2009"))
        assertTrue(EmojiSearchLayout.hasQuery("к"))
        // A space around real text is still real text: only the all-blank field is empty.
        assertTrue(EmojiSearchLayout.hasQuery(" к "))
        assertTrue(EmojiSearchLayout.hasQuery("кот "))
    }

    @Test
    fun theResultBandDoesNotExistForAQueryOfSpaces() {
        assertFalse(EmojiSearchLayout.showsResultBand(" "))
        assertFalse(EmojiSearchLayout.showsResultBand("   "))
        assertTrue(EmojiSearchLayout.showsResultBand(" кот"))
        // The measured height is the plain query row, exactly as for the untouched field.
        assertEquals(
            EmojiSearchLayout.contentHeight(46, 54, ""),
            EmojiSearchLayout.contentHeight(46, 54, "   "),
        )
    }

    /**
     * One answer to "is there a query", in [EmojiSearchLayout]. A second copy inside `onDraw` or
     * inside the accessibility tree is how the band, the height and the spoken node drift apart.
     */
    @Test
    fun theViewNeverAsksWhetherTheQueryIsEmptyOnItsOwn() {
        val ownChecks = Regex("queryText\\.(isEmpty|isNotEmpty|isBlank|isNotBlank)\\(\\)")
            .findAll(view)
            .map { it.value }
            .toList()
        assertTrue(
            "the view still decides emptiness itself: $ownChecks",
            ownChecks.isEmpty(),
        )
    }

    /** The hint and the spoken node follow the same rule as the band. */
    @Test
    fun theHintAndTheSpokenNodeGoThroughTheSameRuleAsTheBand() {
        val queryRow = bodyOf("private fun drawQueryRow", "private fun drawCaret")
        assertTrue(
            "the hint branch must ask EmojiSearchLayout.hasQuery",
            queryRow.contains("EmojiSearchLayout.hasQuery(queryText)"),
        )
        val node = bodyOf("override fun onPopulateNodeForVirtualView", "override fun onPerformActionForVirtualView")
        assertTrue(
            "the query node must ask EmojiSearchLayout.hasQuery",
            node.contains("EmojiSearchLayout.hasQuery(queryText)"),
        )
    }

    // --- The caret must not stand on the hint -------------------------------------------------

    /**
     * With an empty field the caret is at `textLeft`, so the hint starts past the caret's right
     * edge instead of at `textLeft`, where the caret would cover its first letter.
     */
    @Test
    fun theHintStartsPastTheRightEdgeOfTheCaret() {
        val textLeft = 40f
        val stroke = 1.5f
        val gap = 3f
        val hintLeft = EmojiSearchLayout.hintLeft(EmojiSearchLayout.caretX(textLeft, 0f), stroke, gap)
        assertTrue("the hint must not start on the caret", hintLeft > textLeft + stroke / 2f)
        assertEquals(textLeft + stroke / 2f + gap, hintLeft, 0f)
        // A wider caret pushes the hint further, so the clearance never depends on density.
        assertEquals(
            gap,
            EmojiSearchLayout.hintLeft(textLeft, 4f, gap) - (textLeft + 2f),
            0f,
        )
    }

    /** The drawn hint takes its x from that rule and not from the text origin. */
    @Test
    fun theHintIsDrawnFromTheOffsetAndNotFromTheTextOrigin() {
        val queryRow = bodyOf("private fun drawQueryRow", "private fun drawCaret")
        val hintCalls = argumentsOf(queryRow, "drawText(")
            .filter { it.startsWith("hintText,") }
        assertEquals("expected exactly one hint drawText", 1, hintCalls.size)
        assertFalse(
            "the hint is still drawn from the caret's own x: ${hintCalls[0]}",
            hintCalls[0].contains("hintText, textLeft,"),
        )
        assertTrue(
            "the hint x must come from EmojiSearchLayout.hintLeft",
            queryRow.contains("EmojiSearchLayout.hintLeft("),
        )
    }

    // --- The two-row band in the view (source contracts) ---------------------------------------

    /** Draw, hit test and the spoken nodes must all agree on the same column-major mapping. */
    @Test
    fun theViewDrawsHitsAndSpeaksTheTwoRowsThroughOneMapping() {
        val draw = bodyOf("private fun drawResults", "private fun maxScrollX()")
        assertTrue(draw.contains("EmojiSearchLayout.firstVisibleColumn(scrollX, resultCellPx)"))
        assertTrue(draw.contains("EmojiSearchLayout.resultColumnCount(resultCount)"))
        assertTrue(draw.contains("EmojiSearchLayout.resultPosition(column, row)"))
        // Each row's cell is centered on its own row, not on the band.
        assertTrue(draw.contains("rowTop + resultRowPx / 2f + centerOffset"))
        val hit = bodyOf("private fun targetAt(", "override fun onTouchEvent")
        assertTrue(hit.contains("EmojiSearchLayout.resultPosition(column, row)"))
        assertTrue(hit.contains("EmojiSearchLayout.RESULT_ROWS"))
        val node = bodyOf("override fun onPopulateNodeForVirtualView", "override fun onPerformActionForVirtualView")
        assertTrue(node.contains("index.resultNameAt(virtualViewId, spokenNameLanguage)"))
        assertTrue(node.contains("EmojiSearchLayout.resultColumn(virtualViewId)"))
        assertTrue(node.contains("EmojiSearchLayout.resultRow(virtualViewId)"))
        assertTrue(node.contains("* resultCellPx - scrollX"))
        assertTrue(node.contains("rowTop + resultRowPx"))
    }

    /** The scroll limit is the band's columns, not its cells: two rows share one scroll offset. */
    @Test
    fun theScrollLimitCountsColumnsNotCells() {
        val limit = bodyOf("private fun maxScrollX()", "private fun targetAt(")
        assertTrue(
            "maxScrollX must come from EmojiSearchLayout.maxScrollX",
            limit.contains("EmojiSearchLayout.maxScrollX(resultCount, resultCellPx, width)"),
        )
        assertFalse(
            "the scroll limit still multiplies cells by the result count",
            limit.contains("resultCount * resultCellPx"),
        )
    }

    @Test
    fun accessibilityActionsScrollBothWaysOnlyWhenContentOverflows() {
        val host = bodyOf("override fun onPopulateNodeForHost", "override fun onPopulateNodeForVirtualView")
        assertTrue(host.contains("if (canScrollResults())"))
        assertTrue(host.contains("node.isScrollable = true"))
        assertTrue(host.contains("AccessibilityNodeInfo.ACTION_SCROLL_FORWARD"))
        assertTrue(host.contains("AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD"))

        val guard = bodyOf("private fun canScrollResults", "private fun scrollOneViewport")
        assertTrue(guard.contains("width > 0 && resultCellPx > 0 && maxScrollX() > 0"))
        val scroll = bodyOf("private fun scrollOneViewport", "private fun targetAt")
        assertTrue(scroll.contains("if (!canScrollResults()) return false"))
        assertTrue(scroll.contains("if (forward) width else -width"))
        assertTrue(scroll.contains(".coerceIn(0, maxScroll)"))
        assertTrue(scroll.contains("if (next == scrollX) return false"))

        val perform = view.substringAfter("override fun performAccessibilityAction(action: Int")
        assertTrue(perform.contains("AccessibilityNodeInfo.ACTION_SCROLL_FORWARD"))
        assertTrue(perform.contains("AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD"))
        assertTrue(perform.contains("scrollOneViewport(forward = true)"))
        assertTrue(perform.contains("scrollOneViewport(forward = false)"))
    }

    @Test
    fun everyScrollChangeInvalidatesTheVirtualNodeTree() {
        val touch = bodyOf("override fun onTouchEvent", "override fun onVisibilityChanged")
        val touchChange = touch.substringAfter("if (next != scrollX)").substringBefore("return true")
        assertTrue(touchChange.contains("scrollX = next"))
        assertTrue(touchChange.contains("invalidateAccessibilityRootIfExploring()"))

        val accessibilityScroll = bodyOf("private fun scrollOneViewport", "private fun targetAt")
        assertTrue(accessibilityScroll.contains("scrollX = next"))
        assertTrue(accessibilityScroll.contains("invalidateAccessibilityRootIfExploring()"))

        val resetBodies = listOf(
            bodyOf("fun setQuery(query: String)", "fun release()"),
            bodyOf("fun release()", "override fun onMeasure"),
        )
        for (body in resetBodies) {
            assertTrue(body.contains("scrollX = 0"))
            assertTrue(body.contains("invalidateAccessibilityRootIfExploring()"))
        }
        val gate = bodyOf("private fun invalidateAccessibilityRootIfExploring", "private inner class")
        assertTrue(gate.contains("accessibilityManager.isTouchExplorationEnabled"))
        assertTrue(gate.contains("accessibilityHelper.invalidateRoot()"))
    }

    /** The no-results message centers on the whole band, not on the first row of two. */
    @Test
    fun theNoResultsMessageCentersOnTheWholeBand() {
        val draw = bodyOf("private fun drawResults", "private fun maxScrollX()")
        assertTrue(
            "the message baseline must use the band height",
            draw.contains("top + bandPx / 2f"),
        )
    }
}
