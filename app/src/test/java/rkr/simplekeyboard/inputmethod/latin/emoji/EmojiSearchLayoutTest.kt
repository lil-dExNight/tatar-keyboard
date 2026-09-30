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
 * The rules the operator found broken on a real phone. In 1.6.0: the caret must sit at the end of
 * the typed query, and the result band must not exist while the query is empty. In 1.6.1: a field
 * holding nothing but spaces is not a query, and the caret must not stand on the hint.
 *
 * The pure half is checked directly; the half that lives inside `onDraw` / `onMeasure` is checked
 * the way the rest of this package checks drawing code — by grepping the frozen source, in the
 * style of [EmojiPanelSourceContractTest].
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
     * The caret is exactly at the right edge of the drawn text. 1.6.0 added `closeCrossPx` (5dp)
     * here, so at the default density the caret stood 5px past the text and the operator read the
     * gap as a trailing space.
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
        assertEquals(100, EmojiSearchLayout.contentHeight(46, 54, "к"))
        // The band is the whole of the difference: nothing else changes with the query.
        assertEquals(
            54,
            EmojiSearchLayout.contentHeight(46, 54, "кот") - EmojiSearchLayout.contentHeight(46, 54, ""),
        )
    }

    // --- Defect 1: the caret sits at the end of the text ---------------------------------------

    /**
     * 1.6.0 drew the caret at `textLeft + measureText(queryText) + closeCrossPx`, where
     * `closeCrossPx` is the half-size of the "✕" key at the other end of the pill. The operator
     * read the resulting 5dp gap as a trailing space. Nothing but the text width may enter the
     * caret's x.
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

    // --- Defect 2: no result band while the query is empty -------------------------------------

    /**
     * 1.6.0 always measured `queryRowPx + resultRowPx`, so an empty query reserved 54dp for a band
     * whose only content was the words "type a query". The band must not be measured at all then.
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
    }

    // --- Defect 3: a query of spaces is not a query --------------------------------------------

    /**
     * The operator typed a space into the empty field in 1.6.1 and got a band saying "nothing
     * found". The search itself trims before matching, so a run of spaces can never have a result;
     * every other part of the view has to agree with that and treat such a field as empty.
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

    // --- Defect 4: the caret must not stand on the hint ----------------------------------------

    /**
     * With an empty field 1.6.1 drew the hint from `textLeft` and the caret at `textLeft` too, so
     * the caret stood on the first letter of "Поиск эмодзи"
     * (`operator-shots/1.6.0-empty-query-plaque.jpg`). The hint starts past the caret's right edge.
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
}
