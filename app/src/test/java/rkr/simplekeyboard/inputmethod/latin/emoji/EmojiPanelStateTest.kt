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

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiPanelStateTest {

    private val minCellPx = 36
    private val maxCellPx = 56
    private val tabBarPx = 44
    private val searchBarPx = 50
    private val headerPx = 30
    private val floatingPx = 44
    private val floatingInsetPx = 8
    private val backWidthPx = 60

    /** The top of the scrolling content under the tab row and the search band. */
    private val gridTopPx = tabBarPx + searchBarPx

    private fun EmojiPanelState.applyMetrics() = setCellMetrics(
        minCellPx,
        maxCellPx,
        tabBarPx,
        searchBarPx,
        headerPx,
        floatingPx,
        floatingInsetPx,
        backWidthPx,
    )

    /** Builds a single-category snapshot with [count] distinct entries via the real parser. */
    private fun snapshotOf(count: Int, categoryName: String = "cat"): EmojiSetSnapshot {
        val text = buildString {
            append('#').append(categoryName).append('\n')
            for (i in 0 until count) append('e').append(i).append('\n')
        }
        return EmojiSet.parse(text)
    }

    private fun multiCategorySnapshot(vararg counts: Int): EmojiSetSnapshot {
        val text = buildString {
            counts.forEachIndexed { category, count ->
                append("#cat").append(category).append('\n')
                for (i in 0 until count) append('c').append(category).append('_').append(i).append('\n')
            }
        }
        return EmojiSet.parse(text)
    }

    private val swipeMinPx = 32

    private fun configuredState(
        snapshot: EmojiSetSnapshot,
        width: Int = 8 * 40,
        height: Int = 400,
    ): EmojiPanelState {
        val state = EmojiPanelState()
        state.setColumns(8)
        state.applyMetrics()
        state.setSwipeMinDistance(swipeMinPx)
        state.setViewport(width, height)
        state.setSnapshot(snapshot)
        return state
    }

    // --- Geometry ------------------------------------------------------------------------------

    @Test
    fun portraitCellIsNeverNarrowerThanALetterKeyFrom240To1280Dp() {
        val state = EmojiPanelState()
        state.setColumns(EmojiPanelState.PORTRAIT_COLUMNS)
        for (widthDp in 240..1280) {
            state.setViewport(widthDp, 400)
            val cellWidth = state.cellWidth()
            // A first-row Tatar letter key is 100/11 %p wide (11 keys), i.e. widthDp / 11.
            val letterKeyWidth = widthDp / 11
            assertTrue(
                "cell $cellWidth < letter $letterKeyWidth at $widthDp dp",
                cellWidth >= letterKeyWidth,
            )
            // 100/8 = 12.5%p vs 100/11 = 9.091%p.
            assertTrue(
                "cell fraction ${cellWidth.toDouble() / widthDp} below 9.091%p at $widthDp dp",
                cellWidth.toDouble() / widthDp >= 9.091 / 100.0,
            )
        }
    }

    /**
     * The cell is square again. The previous panel shrank it so that a whole number of rows filled
     * the viewport exactly; with one continuous scroll through every section there is no row to
     * align to, and that squeeze was what made the glyphs look small against the reference.
     */
    @Test
    fun cellIsSquareAndOnlyClampedToTheDpRange() {
        val state = EmojiPanelState()
        state.setColumns(8)
        state.applyMetrics()

        // Narrow: width/8 = 30 < 36 -> clamped up.
        state.setViewport(8 * 30, 400)
        assertEquals(30, state.cellWidth())
        assertEquals(36, state.cellHeight())

        // In range: square, untouched.
        state.setViewport(8 * 45, 400)
        assertEquals(45, state.cellWidth())
        assertEquals(45, state.cellHeight())

        // Wide: width/8 = 80 > 56 -> clamped down to the maximum, and no further.
        state.setViewport(8 * 80, 400)
        assertEquals(80, state.cellWidth())
        assertEquals(56, state.cellHeight())
    }

    @Test
    fun columnsAreEightPortraitAndTwelveLandscape() {
        val state = EmojiPanelState()
        state.setColumns(EmojiPanelState.PORTRAIT_COLUMNS)
        state.setViewport(360, 400)
        assertEquals(8, state.columnCount())
        assertEquals(45, state.cellWidth())

        state.setColumns(EmojiPanelState.LANDSCAPE_COLUMNS)
        state.setViewport(1200, 400)
        assertEquals(12, state.columnCount())
        assertEquals(100, state.cellWidth())
    }

    @Test
    fun columnBoundariesAreContiguousAndCoverTheFullWidth() {
        val state = EmojiPanelState()
        state.setColumns(8)
        for (width in 240..400) {
            state.setViewport(width, 300)
            assertEquals(0, state.columnLeft(0))
            assertEquals(width, state.columnRight(7))
            for (c in 0 until 7) {
                assertEquals(state.columnRight(c), state.columnLeft(c + 1))
            }
        }
    }

    /** The content is below both fixed bands, and the bands themselves tile the top of the panel. */
    @Test
    fun theTwoFixedBandsSitAboveTheScrollingContent() {
        val state = configuredState(snapshotOf(50))
        assertEquals(tabBarPx, state.tabBarHeight())
        assertEquals(tabBarPx, state.searchBarTop())
        assertEquals(searchBarPx, state.searchBarHeight())
        assertEquals(gridTopPx, state.gridTop())
        assertEquals(400 - gridTopPx, state.gridHeight())
    }

    // --- Нижний инсет под панелью навигации (дефект Д-1) ----------------------------------------

    /**
     * Everything that means "the bottom of the panel" moves up together by the navigation-bar
     * overlap: the floating keys, their touch targets (the same two numbers back the hit test) and
     * the scrolling viewport. Before the fix the keys were pinned to the panel's own bottom edge,
     * which from Android 15 lies UNDER the bar — the system took the touches and the user could not
     * leave the panel (docs/DEVICE-UAT-1.9.12.md, Д-1).
     */
    @Test
    fun bottomInsetLiftsFloatingKeysAndShrinksTheViewport() {
        val state = configuredState(snapshotOf(50))
        val navBarPx = 96

        assertEquals(400 - floatingInsetPx, state.floatingBottom())
        assertEquals(400 - floatingInsetPx - floatingPx, state.floatingTop())
        assertEquals(400 - gridTopPx, state.gridHeight())

        assertTrue("первая установка инсета обязана считаться изменением",
            state.setBottomInset(navBarPx))

        assertEquals(400 - navBarPx - floatingInsetPx, state.floatingBottom())
        assertEquals(400 - navBarPx - floatingInsetPx - floatingPx, state.floatingTop())
        assertEquals(400 - navBarPx - gridTopPx, state.gridHeight())
    }

    /** The floating keys must end strictly above the strip the navigation bar covers. */
    @Test
    fun floatingKeysNeverReachIntoTheInsetStrip() {
        val state = configuredState(snapshotOf(50))
        val navBarPx = 96
        state.setBottomInset(navBarPx)
        val insetTop = 400 - navBarPx
        assertTrue(
            "низ плавающих клавиш ${state.floatingBottom()} не должен заходить за $insetTop",
            state.floatingBottom() <= insetTop,
        )
        assertTrue(state.floatingTop() < state.floatingBottom())
    }

    /** A platform that insets the input view itself reports no overlap, and nothing moves. */
    @Test
    fun zeroBottomInsetLeavesTheGeometryExactlyAsItWas() {
        val state = configuredState(snapshotOf(50))
        val floatingBottom = state.floatingBottom()
        val floatingTop = state.floatingTop()
        val gridHeight = state.gridHeight()

        assertFalse("нулевой инсет — не изменение", state.setBottomInset(0))
        assertEquals(floatingBottom, state.floatingBottom())
        assertEquals(floatingTop, state.floatingTop())
        assertEquals(gridHeight, state.gridHeight())
    }

    /** Repeating the same overlap is not a change, so a layout pass never forces a needless redraw. */
    @Test
    fun repeatedBottomInsetIsNotReportedAsAChange() {
        val state = configuredState(snapshotOf(50))
        assertTrue(state.setBottomInset(96))
        assertFalse(state.setBottomInset(96))
        assertTrue(state.setBottomInset(0))
    }

    /** A negative overlap is meaningless and is clamped, never applied as a growth. */
    @Test
    fun negativeBottomInsetIsClampedToZero() {
        val state = configuredState(snapshotOf(50))
        val floatingBottom = state.floatingBottom()
        assertFalse(state.setBottomInset(-50))
        assertEquals(floatingBottom, state.floatingBottom())
    }

    // --- Сжатие фиксированных полос при нехватке высоты (Р-2) ------------------------------------

    /** While everything fits, the bands are exactly what the view asked for — nothing is scaled. */
    @Test
    fun bandsKeepTheirAskedSizeWhileThereIsRoom() {
        val state = configuredState(snapshotOf(50))
        assertEquals(tabBarPx, state.tabBarHeight())
        assertEquals(searchBarPx, state.searchBarHeight())
        assertEquals(gridTopPx, state.gridTop())
    }

    /**
     * When the panel is too short, the bands yield instead of the content: the grid keeps at least
     * a header plus one minimum row, which is what "less than one row of emoji" cost the user at
     * Keyboard height 50 % (docs/DEVICE-RESEARCH-GEOMETRY.md, Р-2).
     */
    @Test
    fun bandsShrinkSoTheGridKeepsItsFloor() {
        val floor = headerPx + 2 * minCellPx
        val short = 180
        val state = configuredState(snapshotOf(50), height = short)

        assertTrue("полосы обязаны сжаться", state.tabBarHeight() < tabBarPx)
        assertTrue(state.searchBarHeight() < searchBarPx)
        assertEquals(state.tabBarHeight() + state.searchBarHeight(), state.gridTop())
        assertTrue(
            "сетке должно остаться не меньше пола ($floor): ${state.gridHeight()}",
            state.gridHeight() >= floor,
        )
    }

    /** The squeeze has a floor of its own: bands never shrink away to nothing. */
    @Test
    fun bandsNeverShrinkBelowTheirFloor() {
        val state = configuredState(snapshotOf(50), height = 80)
        assertTrue(state.tabBarHeight() >= (tabBarPx * 0.6f).toInt())
        assertTrue(state.searchBarHeight() >= (searchBarPx * 0.6f).toInt())
        assertTrue("полосы обязаны остаться видимыми", state.tabBarHeight() > 0)
    }

    /**
     * The navigation-bar reservation counts against the same budget: taking 96 px away can be what
     * pushes a panel that used to fit into the squeeze.
     */
    @Test
    fun bottomInsetCanTriggerTheSqueeze() {
        val state = configuredState(snapshotOf(50), height = 200)
        assertEquals("без инсета всё ещё помещается", tabBarPx, state.tabBarHeight())

        state.setBottomInset(96)
        assertTrue("после резервирования навбара полосы обязаны сжаться",
            state.tabBarHeight() < tabBarPx)
    }

    // --- Sections ------------------------------------------------------------------------------

    /**
     * The core of the new layout: one continuous content made of section blocks, each a header
     * followed by its whole rows, with the global cell indices packed across section boundaries.
     */
    @Test
    fun sectionsTileTheContentAndIndicesAreGlobalAndCompact() {
        val counts = intArrayOf(50, 20, 5, 9)
        val state = configuredState(multiCategorySnapshot(*counts))
        val cell = state.cellHeight()
        val columns = state.columnCount()

        assertEquals(counts.size, state.sectionCount())
        var expectedTop = 0
        var expectedStart = 0
        for (section in counts.indices) {
            assertEquals("top of section $section", expectedTop, state.sectionTop(section))
            assertEquals("start of section $section", expectedStart, state.sectionStartIndex(section))
            assertEquals(expectedTop + headerPx, state.sectionGridTop(section))
            val rows = (counts[section] + columns - 1) / columns
            assertEquals(rows, state.sectionRowCount(section))
            expectedTop += headerPx + rows * cell
            expectedStart += counts[section]
        }
        assertEquals(counts.sum(), state.entryCount())
        // The content ends one section block past the last one, plus the trailing air that lets the
        // final row be scrolled clear of the floating keys.
        assertEquals(expectedTop + floatingPx + 2 * floatingInsetPx, state.contentHeight())

        // Global index -> entry crosses section boundaries without a gap.
        assertEquals("c0_0", state.entryAt(0))
        assertEquals("c0_49", state.entryAt(49))
        assertEquals("c1_0", state.entryAt(50))
        assertEquals("c2_0", state.entryAt(70))
        assertEquals("c3_8", state.entryAt(83))
        assertEquals(0, state.sectionOfIndex(49))
        assertEquals(1, state.sectionOfIndex(50))
        assertEquals(3, state.sectionOfIndex(83))
    }

    /** The active tab is a consequence of the scroll position, not of a separate mode. */
    @Test
    fun theActiveTabFollowsTheScrollAndATabTapScrollsToItsSection() {
        val state = configuredState(multiCategorySnapshot(50, 20, 40, 40))
        assertEquals(0, state.activeCategory())

        assertTrue(state.setActiveCategory(2))
        assertEquals(state.sectionTop(2), state.scrollY())
        assertEquals(2, state.activeCategory())

        // Scrolling back by hand moves the active tab back with no explicit category change.
        state.setScrollY(state.sectionTop(1))
        assertEquals(1, state.activeCategory())
        state.setScrollY(0)
        assertEquals(0, state.activeCategory())
    }

    @Test
    fun scrollIsClampedToTheContentHeight() {
        val state = configuredState(snapshotOf(200))
        state.setScrollY(-500)
        assertEquals(0, state.scrollY())
        state.setScrollY(Int.MAX_VALUE / 2)
        assertEquals(state.maxScrollY(), state.scrollY())
        assertTrue(state.maxScrollY() > 0)
    }

    // --- Virtual node count = visible cells + tabs + search + 2 functional keys -----------------

    private fun expectedVisibleCells(state: EmojiPanelState): Int {
        val cell = state.cellHeight()
        val columns = state.columnCount()
        if (cell <= 0 || columns <= 0) return 0
        val top = state.scrollY()
        val bottom = top + state.gridHeight()
        var total = 0
        for (section in 0 until state.sectionCount()) {
            val count = state.sectionEntryCount(section)
            val gridTop = state.sectionGridTop(section)
            for (index in 0 until count) {
                val row = index / columns
                val cellTop = gridTop + row * cell
                if (cellTop < bottom && cellTop + cell > top) total++
            }
        }
        return total
    }

    @Test
    fun virtualNodeCountIsVisibleCellsPlusTabsPlusSearchPlusTwoFunctionalKeys() {
        val state = configuredState(multiCategorySnapshot(50, 20, 5), height = 300)
        assertEquals(3, state.tabCount())

        val visibleAtTop = expectedVisibleCells(state)
        assertEquals(visibleAtTop, state.visibleCellCount())
        assertEquals(visibleAtTop + 3 + 3, state.virtualNodeCount())

        state.setScrollY(state.maxScrollY())
        val visibleAtBottom = expectedVisibleCells(state)
        assertEquals(visibleAtBottom, state.visibleCellCount())
        assertEquals(visibleAtBottom + 3 + 3, state.virtualNodeCount())
    }

    @Test
    fun onlyVisibleRowsAreCounted() {
        val state = configuredState(snapshotOf(200), height = 300)
        assertEquals(25, state.sectionRowCount(0))
        assertTrue(state.visibleCellCount() < 200)
        assertTrue(state.visibleCellCount() <= 8 * 8)
    }

    /** The visible-cell walk must agree with the drawn range at every scroll offset. */
    @Test
    fun theVisibleCellWalkMatchesTheDrawnRangeAtEveryScrollOffset() {
        val state = configuredState(multiCategorySnapshot(50, 20, 5, 33), height = 300)
        var scroll = 0
        while (scroll <= state.maxScrollY()) {
            state.setScrollY(scroll)
            assertEquals("at scroll $scroll", expectedVisibleCells(state), state.visibleCellCount())
            scroll += 7
        }
    }

    // --- Glyph filtering: compact, scroll-stable indices ---------------------------------------

    @Test
    fun glyphFilteringYieldsCompactIndicesThatScrollNeverShifts() {
        val text = buildString {
            append("#cat\n")
            for (i in 0 until 40) append('e').append(i).append('\n')
        }
        // A fake probe rejecting half the set: keep only even-numbered entries.
        val probe = GlyphProbe { sequence -> sequence.removePrefix("e").toInt() % 2 == 0 }
        val snapshot = EmojiSet.build(text, probe)
        assertEquals(20, snapshot.totalEntryCount())

        val state = configuredState(snapshot, height = 200)
        assertEquals(20, state.entryCount())
        // Kept entries are packed with no gaps: e0, e2, e4, ...
        assertEquals("e0", state.entryAt(0))
        assertEquals("e2", state.entryAt(1))
        assertEquals("e20", state.entryAt(10))
        assertEquals("e38", state.entryAt(19))

        // Scrolling changes which rows show, never the index -> entry mapping.
        val atIndexTen = state.entryAt(10)
        state.setScrollY(state.maxScrollY())
        assertTrue(state.scrollY() > 0)
        assertEquals(atIndexTen, state.entryAt(10))
    }

    // --- Hit testing ---------------------------------------------------------------------------

    @Test
    fun hitTestingResolvesCellsTabsSearchAndTheTwoFloatingKeys() {
        val counts = intArrayOf(50, 20, 5)
        val width = 8 * 40
        val height = 400
        val state = configuredState(multiCategorySnapshot(*counts), width, height)

        // The first cell sits under the first section's header, not at the top of the content.
        assertEquals(EmojiPanelState.NO_TARGET, state.targetAt(1f, gridTopPx + 1f))
        assertEquals(0, state.targetAt(1f, gridTopPx + headerPx + 1f))

        // The tab row is the top band; tab 1 is the second category.
        val tabCenter = (state.tabLeft(1) + state.tabRight(1)) / 2f
        val tabTarget = state.targetAt(tabCenter, 1f)
        assertTrue(EmojiPanelState.isTab(tabTarget))
        assertEquals(1, EmojiPanelState.tabIndexOf(tabTarget))

        // The search pill is the band under it.
        assertTrue(EmojiPanelState.isSearch(state.targetAt(width / 2f, tabBarPx + 1f)))

        // The two floating keys win over the content they are drawn on top of.
        val floatingY = (height - floatingInsetPx - 1).toFloat()
        assertTrue(EmojiPanelState.isBack(state.targetAt((floatingInsetPx + 1).toFloat(), floatingY)))
        assertTrue(
            EmojiPanelState.isDelete(state.targetAt((width - floatingInsetPx - 1).toFloat(), floatingY)),
        )

        // Out of bounds is no target.
        assertEquals(EmojiPanelState.NO_TARGET, state.targetAt(-1f, 1f))
        assertEquals(EmojiPanelState.NO_TARGET, state.targetAt(1f, (height + 1).toFloat()))
    }

    /** Tabs tile the row inside the side inset with no gap and no overlap. */
    @Test
    fun tabsTileTheRowWithoutOverlap() {
        val width = 591
        val state = configuredState(multiCategorySnapshot(50, 20, 5, 10, 10, 10, 10, 10), width, 400)
        val tabs = state.tabCount()
        assertEquals(8, tabs)
        assertEquals(floatingInsetPx, state.tabLeft(0))
        assertEquals(width - floatingInsetPx, state.tabRight(tabs - 1))
        for (tab in 1 until tabs) {
            assertEquals(state.tabRight(tab - 1), state.tabLeft(tab))
            assertTrue("tab $tab is empty", state.tabRight(tab) > state.tabLeft(tab))
        }
        // Every x in the tab row still resolves to some tab, the side insets included.
        for (x in 0 until width) {
            assertTrue(EmojiPanelState.isTab(state.targetAt(x.toFloat(), 1f)))
        }
    }

    /** A section header is drawn inside the scroll but is not a target of its own. */
    @Test
    fun sectionHeadersAreNeverATarget() {
        val state = configuredState(multiCategorySnapshot(160, 160, 160))
        for (section in 0 until state.sectionCount()) {
            state.setScrollY(state.sectionTop(section))
            assertEquals("section $section is not reachable", state.sectionTop(section), state.scrollY())
            var y = gridTopPx
            while (y < gridTopPx + headerPx) {
                assertEquals(
                    "header of section $section at y=$y",
                    EmojiPanelState.NO_TARGET,
                    state.targetAt(1f, y.toFloat()),
                )
                y++
            }
        }
    }

    /** Cells beyond a section's last entry are dead space, not the next section's first cell. */
    @Test
    fun theTailOfAPartialRowIsNotATarget() {
        // 9 entries: row 0 is full, row 1 holds exactly one cell and seven empty ones.
        val state = configuredState(multiCategorySnapshot(9, 16))
        val cell = state.cellHeight()
        val y = (gridTopPx + headerPx + cell + 1).toFloat()
        assertEquals(8, state.targetAt(1f, y))
        for (column in 1 until 8) {
            val x = (state.columnLeft(column) + 1).toFloat()
            assertEquals("column $column", EmojiPanelState.NO_TARGET, state.targetAt(x, y))
        }
    }

    // --- Gestures ------------------------------------------------------------------------------

    @Test
    fun tappingACellReturnsThatCellOnUp() {
        val state = configuredState(snapshotOf(50))
        val y = (gridTopPx + headerPx + 5).toFloat()
        assertEquals(0, state.onDown(1, 5f, y))
        assertEquals(0, state.pressedTarget())
        assertEquals(0, state.onUp(1, 5f, y))
    }

    @Test
    fun verticalDragScrollsTheContentAndSuppressesTheTap() {
        val state = configuredState(snapshotOf(200))
        state.onDown(1, 50f, 250f)
        // Move up past the touch slop.
        assertTrue(state.onMove(1, 50f, 200f, 8))
        assertTrue(state.isScrolling())
        assertTrue(state.scrollY() > 0)
        // A gesture that became a scroll never activates a cell.
        assertEquals(EmojiPanelState.NO_TARGET, state.onUp(1, 50f, 200f))
    }

    /** A drag started on the tab row or the search band scrolls nothing; only the content scrolls. */
    @Test
    fun aDragOutsideTheContentNeverScrolls() {
        val state = configuredState(snapshotOf(200))
        state.onDown(1, 50f, 5f)
        // The move may still clear the pressed highlight; what it must never do is scroll.
        state.onMove(1, 50f, 60f, 8)
        assertEquals(0, state.scrollY())
        assertFalse(state.isScrolling())
    }

    @Test
    fun aPointerUpForAnotherPointerDoesNotEndTheGesture() {
        val state = configuredState(snapshotOf(50))
        val y = (gridTopPx + headerPx + 5).toFloat()
        state.onDown(7, 5f, y)
        assertFalse(state.onPointerUp(19))
        assertEquals(7, state.activePointerId())
        assertEquals(0, state.onUp(7, 5f, y))
    }

    @Test
    fun theFloatingKeysAndTheSearchPillHaveDistinctTargets() {
        val targets = intArrayOf(
            EmojiPanelState.NO_TARGET,
            EmojiPanelState.BACK_TARGET,
            EmojiPanelState.DELETE_TARGET,
            EmojiPanelState.SEARCH_TARGET,
        )
        for (i in targets.indices) {
            for (j in i + 1 until targets.size) {
                assertNotEquals(targets[i], targets[j])
            }
            assertFalse(EmojiPanelState.isCell(targets[i]))
            assertFalse(EmojiPanelState.isTab(targets[i]))
        }
        assertTrue(EmojiPanelState.isSearch(EmojiPanelState.SEARCH_TARGET))
        assertFalse(EmojiPanelState.isSearch(EmojiPanelState.BACK_TARGET))
    }

    // --- Sideways swipe between sections -------------------------------------------------------

    /**
     * The axis is decided once, by whichever direction clears the slop first. Deciding once is what
     * stops a slightly slanted scroll from turning into a section jump halfway down the drag.
     */
    @Test
    fun aSidewaysDragJumpsASectionAndAVerticalOneScrolls() {
        val state = configuredState(multiCategorySnapshot(80, 80, 80))
        val startY = (gridTopPx + headerPx + 20).toFloat()

        // Horizontal first: the axis locks sideways and the content does not scroll.
        state.onDown(1, 200f, startY)
        state.onMove(1, 240f, startY + 4f, 8)
        assertTrue(state.isSwiping())
        assertFalse(state.isScrolling())
        assertEquals(0, state.scrollY())
        state.onMove(1, 300f, startY + 6f, 8)
        assertEquals(0, state.scrollY())
        assertEquals(EmojiPanelState.NO_TARGET, state.onUp(1, 300f, startY + 6f))
        assertEquals(-1, state.consumeSwipe())
        // Drained exactly once.
        assertEquals(0, state.consumeSwipe())

        // Vertical first: the axis locks down and the content scrolls as before.
        state.onDown(1, 200f, startY + 100f)
        state.onMove(1, 204f, startY + 40f, 8)
        assertTrue(state.isScrolling())
        assertFalse(state.isSwiping())
        assertTrue(state.scrollY() > 0)
        state.onUp(1, 204f, startY + 40f)
        assertEquals(0, state.consumeSwipe())
    }

    @Test
    fun aSwipeLeftAsksForTheNextSectionAndRightForThePrevious() {
        val state = configuredState(multiCategorySnapshot(80, 80, 80))
        val y = (gridTopPx + headerPx + 20).toFloat()

        state.onDown(1, 300f, y)
        state.onMove(1, 260f, y, 8)
        state.onUp(1, 300f - swipeMinPx, y)
        assertEquals("left swipe -> next section", 1, state.consumeSwipe())

        state.onDown(1, 100f, y)
        state.onMove(1, 140f, y, 8)
        state.onUp(1, 100f + swipeMinPx, y)
        assertEquals("right swipe -> previous section", -1, state.consumeSwipe())
    }

    /** A sideways drag that never travels far enough is not a swipe: it must change nothing. */
    @Test
    fun aShortSidewaysDragIsNotASwipe() {
        val state = configuredState(multiCategorySnapshot(80, 80, 80))
        val y = (gridTopPx + headerPx + 20).toFloat()
        state.onDown(1, 300f, y)
        state.onMove(1, 315f, y, 8)
        state.onUp(1, 315f, y)
        assertEquals(0, state.consumeSwipe())
        assertEquals(0, state.scrollY())
    }

    /** A drag that became a swipe must never also activate the cell it started on. */
    @Test
    fun aSwipeNeverActivatesTheCellItStartedOn() {
        val state = configuredState(multiCategorySnapshot(80, 80, 80))
        val y = (gridTopPx + headerPx + 20).toFloat()
        assertTrue(EmojiPanelState.isCell(state.onDown(1, 300f, y)))
        state.onMove(1, 200f, y, 8)
        assertEquals(EmojiPanelState.NO_TARGET, state.pressedTarget())
        assertEquals(EmojiPanelState.NO_TARGET, state.onUp(1, 200f, y))
    }

    // --- Skin-tone popup -----------------------------------------------------------------------

    @Test
    fun thePopupOpensOverItsAnchorAndStaysInsideThePanel() {
        val width = 8 * 40
        val state = configuredState(multiCategorySnapshot(64), width)
        val variants = 6

        // A cell in the middle: the popup is centred on it and sits above its row.
        assertTrue(state.openPopup(11, variants))
        assertTrue(state.isPopupOpen())
        assertEquals(11, state.popupCell())
        assertEquals(variants, state.popupVariantCount())
        assertEquals(variants * state.cellWidth(), state.popupWidth())
        assertEquals(state.cellHeight(), state.popupHeight())
        assertTrue("popup runs off the left", state.popupLeft() >= 0)
        assertTrue("popup runs off the right", state.popupRight() <= width)

        // The first row has nothing above it, so the popup drops below the anchor instead.
        state.closePopup()
        state.openPopup(0, variants)
        val anchorTop = gridTopPx + headerPx
        assertEquals(anchorTop + state.cellHeight(), state.popupTop())

        // A row further down has room above and the popup goes there.
        state.closePopup()
        state.openPopup(8 * 3, variants)
        assertEquals(anchorTop + 2 * state.cellHeight(), state.popupTop())
    }

    @Test
    fun theOpenPopupOwnsTheWholeSurface() {
        val state = configuredState(multiCategorySnapshot(64))
        state.openPopup(11, 6)

        // A touch inside the card resolves to a variant.
        val y = (state.popupTop() + 1).toFloat()
        for (variant in 0 until 6) {
            val x = (state.popupVariantLeft(variant) + 1).toFloat()
            val target = state.targetAt(x, y)
            assertTrue("variant $variant", EmojiPanelState.isPopupVariant(target))
            assertEquals(variant, EmojiPanelState.popupVariantIndexOf(target))
        }
        // Everything else is inert while it is up: no cells, no tabs, no search, no floating keys.
        assertEquals(EmojiPanelState.NO_TARGET, state.targetAt(1f, 1f))
        assertEquals(EmojiPanelState.NO_TARGET, state.targetAt(1f, (tabBarPx + 1).toFloat()))
        assertEquals(
            EmojiPanelState.NO_TARGET,
            state.targetAt(1f, (400 - floatingInsetPx - 1).toFloat()),
        )
    }

    @Test
    fun aReleaseOnAVariantPicksItAndAReleaseElsewhereDismisses() {
        val state = configuredState(multiCategorySnapshot(64))
        state.openPopup(11, 6)
        val y = (state.popupTop() + 1).toFloat()
        val x = (state.popupVariantLeft(2) + 1).toFloat()

        state.onDown(1, x, y)
        val picked = state.onUp(1, x, y)
        assertTrue(EmojiPanelState.isPopupVariant(picked))
        assertEquals(2, EmojiPanelState.popupVariantIndexOf(picked))

        state.onDown(1, x, y)
        assertEquals(EmojiPanelState.POPUP_DISMISS_TARGET, state.onUp(1, 1f, 1f))
    }

    /** Sliding along the open popup moves the highlight without picking anything. */
    @Test
    fun theHighlightFollowsTheFingerAlongThePopup() {
        val state = configuredState(multiCategorySnapshot(64))
        state.openPopup(11, 6)
        val y = (state.popupTop() + 1).toFloat()
        state.onDown(1, (state.popupVariantLeft(0) + 1).toFloat(), y)
        assertEquals(0, state.popupVariant())
        assertTrue(state.onMove(1, (state.popupVariantLeft(3) + 1).toFloat(), y, 8))
        assertEquals(3, state.popupVariant())
        // Off the card: nothing highlighted, and the content still has not scrolled.
        assertTrue(state.onMove(1, 1f, 1f, 8))
        assertEquals(EmojiPanelState.NO_TARGET, state.popupVariant())
        assertEquals(0, state.scrollY())
    }

    /**
     * While the popup is up it owns the surface for a screen reader too: the node tree is exactly
     * its variants. Leaving the grid exposed would let TalkBack activate a cell a finger cannot.
     */
    @Test
    fun theOpenPopupIsTheWholeVirtualNodeTree() {
        val state = configuredState(multiCategorySnapshot(64, 32))
        val closedCount = state.virtualNodeCount()
        assertEquals(state.visibleCellCount() + state.tabCount() + 3, closedCount)

        state.openPopup(11, EmojiSkinTones.VARIANT_COUNT)
        assertEquals(EmojiSkinTones.VARIANT_COUNT, state.virtualNodeCount())

        state.closePopup()
        assertEquals(closedCount, state.virtualNodeCount())
    }

    /** Popup variants, tabs and the fixed targets live in blocks that can never collide. */
    @Test
    fun popupVariantTargetsNeverCollideWithTabsOrTheFixedTargets() {
        for (variant in 0 until EmojiSkinTones.VARIANT_COUNT) {
            val target = EmojiPanelState.POPUP_TARGET_BASE - variant
            assertTrue(EmojiPanelState.isPopupVariant(target))
            assertEquals(variant, EmojiPanelState.popupVariantIndexOf(target))
            assertFalse("variant $variant reads as a tab", EmojiPanelState.isTab(target))
            assertFalse(EmojiPanelState.isCell(target))
            assertFalse(EmojiPanelState.isBack(target))
            assertFalse(EmojiPanelState.isDelete(target))
            assertFalse(EmojiPanelState.isSearch(target))
        }
        for (tab in 0 until 32) {
            val target = EmojiPanelState.TAB_TARGET_BASE - tab
            assertTrue(EmojiPanelState.isTab(target))
            assertFalse("tab $tab reads as a popup variant", EmojiPanelState.isPopupVariant(target))
        }
        assertFalse(EmojiPanelState.isPopupVariant(EmojiPanelState.POPUP_DISMISS_TARGET))
        assertFalse(EmojiPanelState.isTab(EmojiPanelState.POPUP_DISMISS_TARGET))
    }

    @Test
    fun hotGestureAndHitTestingAllocateZeroBytesAfterWarmup() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true
        val state = configuredState(multiCategorySnapshot(200, 100, 50))

        // The continuous ACTION_MOVE scroll path is the one the contract calls allocation-free.
        state.onDown(1, 50f, 350f)
        repeat(200_000) { state.onMove(1, 50f, (200 + (it and 127)).toFloat(), 8) }
        // Hit testing and the visible-cell walk are exercised on the same hot surface.
        repeat(100_000) { state.targetAt(50f, 200f) }
        repeat(100_000) { state.visibleCellCount() }

        val threadId = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(200_000) { state.onMove(1, 50f, (200 + (it and 127)).toFloat(), 8) }
        repeat(100_000) { state.targetAt(50f, 200f) }
        repeat(100_000) { state.visibleCellCount() }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before

        assertEquals(0L, allocated)
    }

    // --- Delete auto-repeat --------------------------------------------------------------------

    @Test
    fun deleteRepeatFiresOnceOnBeginAndNeverDoublesForOneGesture() {
        val repeat = DeleteRepeatState()
        assertTrue(repeat.begin())
        assertEquals(1, repeat.fireCount)
        assertTrue(repeat.isArmed())
        // A second begin inside the same hold does not fire again.
        assertFalse(repeat.begin())
        assertEquals(1, repeat.fireCount)
    }

    @Test
    fun deleteRepeatTicksWhileArmedAndStopsAfterCancel() {
        val repeat = DeleteRepeatState()
        repeat.begin()
        assertTrue(repeat.tick())
        assertTrue(repeat.tick())
        assertEquals(3, repeat.fireCount)

        assertTrue(repeat.cancel())
        assertFalse(repeat.isArmed())
        // Every stop condition maps to cancel(); after it no tick can fire.
        assertFalse(repeat.tick())
        assertEquals(3, repeat.fireCount)
        // Idempotent.
        assertFalse(repeat.cancel())
    }

    @Test
    fun quickTapDeletesExactlyOnce() {
        val repeat = DeleteRepeatState()
        assertTrue(repeat.begin()) // ACTION_DOWN fires one delete
        repeat.cancel() // ACTION_UP before the repeat timeout
        assertFalse(repeat.tick()) // a stray scheduled tick must not fire
        assertEquals(1, repeat.fireCount)
    }
}
