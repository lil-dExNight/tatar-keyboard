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

import kotlin.math.abs

/**
 * Pure, Android-free geometry and gesture state for the emoji panel, modelled on
 * [rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionStripState]. It owns nothing but
 * primitives and the immutable [EmojiSetSnapshot], so every rule in it is exercised on the plain
 * JVM without a device.
 *
 * Layout, top to bottom (the Telegram-client arrangement the operator asked for):
 *
 *  1. a fixed-height row of category tabs across the full width, the active one under a rounded
 *     pill;
 *  2. a fixed-height band holding the search pill;
 *  3. the scrolling content: every category one after another, each introduced by a header row,
 *     so a scroll runs continuously through the whole set and it is visible where one section
 *     ends and the next begins. There is no per-category scroll any more — the active tab is a
 *     consequence of where the content is scrolled to, not a separate mode.
 *
 * Two functional keys float above the content in the bottom corners rather than sitting in a bar:
 * "back to letters" on the left (horizontally where `?123` sits on the letter keyboard, so the
 * muscle memory survives) and delete on the right, exactly as the reference screenshot has them.
 *
 * Cell width is exactly `panelWidth / columns` (8 columns portrait, 12 landscape) and the cell is
 * square, its height clamped to [MIN_CELL_DP]..[MAX_CELL_DP]. Cell indices are a compact
 * `0 until totalEntryCount` addressing of the WHOLE set (not of one category) and never shift when
 * the grid scrolls: scrolling only changes which rows are visible and where they are drawn.
 */
internal class EmojiPanelState {
    private var snapshot: EmojiSetSnapshot = EmojiSetSnapshot.EMPTY

    private var panelWidth = 0
    private var panelHeight = 0
    private var bottomInsetPx = 0
    private var columns = PORTRAIT_COLUMNS
    private var minCellPx = 0
    private var maxCellPx = 0

    private var tabBarPx = 0
    private var searchBarPx = 0
    private var headerPx = 0
    private var floatingPx = 0
    private var floatingInsetPx = 0
    private var backWidthPx = 0

    /**
     * Content layout, rebuilt only when the snapshot, the column count or the cell metrics change —
     * never while drawing or scrolling. `sectionTop[s]` is the y of section s's header inside the
     * content, `sectionTop[sectionCount]` is the total content height; `sectionStart[s]` is the
     * global compact index of that section's first entry.
     */
    private var sectionTop = EMPTY_INTS
    private var sectionStart = EMPTY_INTS

    private var scrollY = 0

    /** Minimum horizontal travel that turns a sideways drag into a jump between sections. */
    private var swipeMinPx = 0

    // Gesture bookkeeping.
    private var downTarget = NO_TARGET
    private var pressedTarget = NO_TARGET
    private var activePointerId = INVALID_POINTER_ID
    private var downInGrid = false
    private var scrolling = false
    private var swiping = false
    private var downX = 0f
    private var downY = 0f
    private var lastMoveY = 0f

    /** Set by [onUp] and drained by the view: -1 previous section, +1 next, 0 nothing. */
    private var pendingSwipe = 0

    // The long-press popup of skin-tone variants; NO_TARGET in [popupCell] means "closed".
    private var popupCell = NO_TARGET
    private var popupVariants = 0
    private var popupVariant = NO_TARGET

    // --- Configuration ------------------------------------------------------------------------

    fun setSnapshot(snapshot: EmojiSetSnapshot) {
        this.snapshot = snapshot
        scrollY = 0
        rebuildLayout()
    }

    fun setColumns(columns: Int) {
        if (columns > 0 && columns != this.columns) {
            this.columns = columns
            rebuildLayout()
        }
    }

    /**
     * Supplies every fixed band and floating-key measurement in px. The view measures them from dp
     * and from its own label widths; this class only arranges what it is given.
     */
    fun setCellMetrics(
        minCellPx: Int,
        maxCellPx: Int,
        tabBarPx: Int,
        searchBarPx: Int,
        headerPx: Int,
        floatingPx: Int,
        floatingInsetPx: Int,
        backWidthPx: Int,
    ) {
        this.minCellPx = minCellPx
        this.maxCellPx = maxCellPx
        this.tabBarPx = tabBarPx.coerceAtLeast(0)
        this.searchBarPx = searchBarPx.coerceAtLeast(0)
        this.headerPx = headerPx.coerceAtLeast(0)
        this.floatingPx = floatingPx.coerceAtLeast(0)
        this.floatingInsetPx = floatingInsetPx.coerceAtLeast(0)
        this.backWidthPx = backWidthPx.coerceAtLeast(0)
        rebuildLayout()
    }

    /**
     * Sets how far a sideways drag must travel before it counts as a jump between sections. The
     * view measures it from the platform touch slop; keeping it a parameter is what lets the rule
     * be exercised on the JVM.
     */
    fun setSwipeMinDistance(px: Int) {
        swipeMinPx = px.coerceAtLeast(0)
    }

    /**
     * How far the panel reaches under the navigation bar, in px. The view measures it from its own
     * position on screen; this class only arranges what it is given, exactly as it does with every
     * other metric. Returns true when the value actually changed, so the caller redraws only then.
     */
    fun setBottomInset(px: Int): Boolean {
        val value = px.coerceAtLeast(0)
        if (value == bottomInsetPx) {
            return false
        }
        bottomInsetPx = value
        clampScroll()
        return true
    }

    fun setViewport(width: Int, height: Int) {
        val newWidth = width.coerceAtLeast(0)
        val widthChanged = newWidth != panelWidth
        panelWidth = newWidth
        panelHeight = height.coerceAtLeast(0)
        if (widthChanged) {
            rebuildLayout()
        } else {
            clampScroll()
        }
    }

    /** Scrolls the content so section [category]'s header sits at the top of the viewport. */
    fun setActiveCategory(category: Int): Boolean {
        if (category !in 0 until sectionCount()) return false
        cancelGesture()
        return setScrollY(sectionTop[category])
    }

    // --- Bands --------------------------------------------------------------------------------

    /**
     * How much the two fixed bands are squeezed so the scrolling content keeps a floor.
     *
     * The tab row and the search band are constants in dp, but the panel's height is the
     * keyboard's — and the keyboard is a user setting. At "Keyboard height 50 %" the bands ate
     * almost everything left after the navigation-bar reservation and under one row of emoji
     * remained visible, with the floating "АБВ" key sitting on the section header
     * (docs/DEVICE-RESEARCH-GEOMETRY.md, Р-2).
     *
     * So the bands yield first: they shrink only as far as the content floor demands, never below
     * [MIN_BAND_SCALE] of their asked size, and never at all while everything fits. At full height
     * the factor is exactly 1 and every measurement is what it always was.
     */
    private fun bandScale(): Float {
        val bands = tabBarPx + searchBarPx
        if (bands <= 0) {
            return 1f
        }
        val usable = usableHeight()
        val floor = headerPx + CONTENT_FLOOR_ROWS * minCellPx
        if (bands + floor <= usable) {
            return 1f
        }
        val available = (usable - floor).coerceAtLeast(0)
        return (available.toFloat() / bands).coerceIn(MIN_BAND_SCALE, 1f)
    }

    private fun scaled(px: Int): Int = (px * bandScale()).toInt()

    fun tabBarHeight(): Int = scaled(tabBarPx)

    fun searchBarTop(): Int = scaled(tabBarPx)

    fun searchBarHeight(): Int = scaled(searchBarPx)

    fun headerHeight(): Int = headerPx

    /** Top of the scrolling content area: below the tab row and the search band. */
    fun gridTop(): Int = tabBarHeight() + searchBarHeight()

    /**
     * The bottom of the area the panel may actually use: its own height minus the strip the
     * navigation bar covers.
     *
     * The panel's box is the keyboard's box — same height, same position, and both of them reach
     * the bottom of the IME window. Whether that bottom is also the bottom of the SCREEN is a
     * platform decision: through Android 14 the framework laid the input view out above the
     * navigation bar, and from Android 15 it does not, so the window's last [bottomInsetPx] pixels
     * sit under the bar. The letter keyboard survives that unaided because its rows never fill its
     * box; the panel's floating keys are pinned to the box's bottom edge and landed inside the bar,
     * where the system takes the touches and the user could not get back to the letters
     * (defect Д-1, `docs/DEVICE-UAT-1.9.12.md`).
     *
     * Every measurement that means "the bottom of the panel" goes through here, so the grid stops
     * scrolling at the bar instead of under it and the floating keys, their touch targets and their
     * accessibility bounds all move together. With [bottomInsetPx] at 0 — every platform that
     * insets the input view itself — the arithmetic is exactly what it was.
     */
    private fun usableHeight(): Int = (panelHeight - bottomInsetPx).coerceAtLeast(0)

    fun gridHeight(): Int = (usableHeight() - gridTop()).coerceAtLeast(0)

    /** Kept as the name the accessibility and fling paths use; the viewport is the grid itself. */
    fun gridViewportHeight(): Int = gridHeight()

    // --- Grid geometry ------------------------------------------------------------------------

    fun columnCount(): Int = columns

    fun categoryCount(): Int = snapshot.categoryCount

    fun sectionCount(): Int = snapshot.categoryCount

    fun categoryName(category: Int): String =
        if (category in 0 until snapshot.categoryCount) snapshot.categoryName(category) else ""

    /** Cell width in px: exactly `panelWidth / columns`, never clamped. */
    fun cellWidth(): Int = if (columns > 0) panelWidth / columns else 0

    /**
     * Cell height in px: the square cell, clamped to the dp range. The previous design squeezed it
     * so that a whole number of rows filled the panel exactly; with one continuous scroll through
     * every section there is no row to align to any more, and the squeeze was what made the glyphs
     * look small.
     */
    fun cellHeight(): Int {
        val width = cellWidth()
        if (maxCellPx <= 0) return width
        return width.coerceIn(minCellPx, maxCellPx)
    }

    fun columnLeft(column: Int): Int = if (columns > 0) panelWidth * column / columns else 0

    fun columnRight(column: Int): Int = if (columns > 0) panelWidth * (column + 1) / columns else 0

    /** Total number of entries across every section; the compact cell index space. */
    fun entryCount(): Int {
        val sections = sectionCount()
        return if (sections == 0) 0 else sectionStart[sections]
    }

    /** The global compact index of section [section]'s first entry. */
    fun sectionStartIndex(section: Int): Int =
        if (section in 0..sectionCount()) sectionStart[section] else 0

    fun sectionEntryCount(section: Int): Int =
        if (section in 0 until sectionCount()) snapshot.entryCount(section) else 0

    fun sectionRowCount(section: Int): Int {
        if (columns <= 0) return 0
        val count = sectionEntryCount(section)
        return (count + columns - 1) / columns
    }

    /** Content y of section [section]'s header top; `sectionTop(sectionCount())` is the total. */
    fun sectionTop(section: Int): Int =
        if (section in 0..sectionCount()) sectionTop[section] else 0

    /** Content y of section [section]'s first cell row. */
    fun sectionGridTop(section: Int): Int = sectionTop(section) + headerPx

    fun contentHeight(): Int {
        val sections = sectionCount()
        return if (sections == 0) 0 else sectionTop[sections]
    }

    fun maxScrollY(): Int = (contentHeight() - gridHeight()).coerceAtLeast(0)

    fun scrollY(): Int = scrollY

    fun setScrollY(value: Int): Boolean {
        val clamped = value.coerceIn(0, maxScrollY())
        if (clamped == scrollY) return false
        scrollY = clamped
        return true
    }

    fun scrollBy(deltaY: Int): Boolean = setScrollY(scrollY + deltaY)

    /** The section owning content y, clamped into range; `0` when there is no content. */
    fun sectionAtContentY(contentY: Int): Int {
        val sections = sectionCount()
        if (sections == 0) return 0
        var section = 0
        while (section < sections - 1 && contentY >= sectionTop[section + 1]) section++
        return section
    }

    /** The tab drawn as active: the section the top of the viewport is inside. */
    fun activeCategory(): Int = sectionAtContentY(scrollY)

    fun firstVisibleSection(): Int = sectionAtContentY(scrollY)

    fun lastVisibleSection(): Int {
        val sections = sectionCount()
        if (sections == 0) return -1
        val bottom = scrollY + gridHeight() - 1
        return sectionAtContentY(bottom.coerceAtLeast(scrollY))
    }

    /** First row of [section] at least partly visible, or -1 when the section shows no row. */
    fun firstVisibleRowOf(section: Int): Int {
        val height = cellHeight()
        val rows = sectionRowCount(section)
        if (height <= 0 || rows == 0) return -1
        val top = sectionGridTop(section)
        val relative = scrollY - top
        if (relative + gridHeight() <= 0) return -1
        val row = if (relative <= 0) 0 else relative / height
        return if (row >= rows) -1 else row
    }

    /** Last row of [section] at least partly visible, or -1 when the section shows no row. */
    fun lastVisibleRowOf(section: Int): Int {
        val height = cellHeight()
        val rows = sectionRowCount(section)
        if (height <= 0 || rows == 0) return -1
        val top = sectionGridTop(section)
        val relativeBottom = scrollY + gridHeight() - top
        if (relativeBottom <= 0) return -1
        val row = (relativeBottom - 1) / height
        return if (row < 0) -1 else row.coerceAtMost(rows - 1)
    }

    /** Number of cells at least partially inside the grid viewport at the current scroll. */
    fun visibleCellCount(): Int {
        val sections = sectionCount()
        if (sections == 0 || columns <= 0) return 0
        var total = 0
        var section = firstVisibleSection()
        val last = lastVisibleSection()
        while (section in 0..last) {
            val first = firstVisibleRowOf(section)
            val lastRow = lastVisibleRowOf(section)
            if (first >= 0 && lastRow >= first) {
                val count = sectionEntryCount(section)
                val start = first * columns
                val end = ((lastRow + 1) * columns).coerceAtMost(count)
                if (end > start) total += end - start
            }
            section++
        }
        return total
    }

    /** The sequence at compact global cell [index]; scroll never shifts this mapping. */
    fun entryAt(index: Int): String {
        if (index < 0 || index >= entryCount()) return ""
        val section = sectionOfIndex(index)
        return snapshot.entryAt(section, index - sectionStart[section])
    }

    /** The section a global compact cell index belongs to. */
    fun sectionOfIndex(index: Int): Int {
        val sections = sectionCount()
        if (sections == 0) return 0
        var section = 0
        while (section < sections - 1 && index >= sectionStart[section + 1]) section++
        return section
    }

    // --- Tabs and floating keys ----------------------------------------------------------------

    /** Number of category tabs shown; a category with 0 surviving entries is absent by construction. */
    fun tabCount(): Int = snapshot.categoryCount

    /**
     * Tabs share the width inside the same side inset the search pill uses, so the first and the
     * last glyph keep their air instead of touching the panel edge.
     */
    fun tabLeft(tab: Int): Int {
        val tabs = tabCount()
        if (tabs <= 0) return 0
        val span = tabSpan()
        return floatingInsetPx + span * tab / tabs
    }

    fun tabRight(tab: Int): Int {
        val tabs = tabCount()
        if (tabs <= 0) return 0
        val span = tabSpan()
        return floatingInsetPx + span * (tab + 1) / tabs
    }

    private fun tabSpan(): Int = (panelWidth - 2 * floatingInsetPx).coerceAtLeast(0)

    fun searchLeft(): Int = floatingInsetPx

    fun searchRight(): Int = (panelWidth - floatingInsetPx).coerceAtLeast(floatingInsetPx)

    fun floatingTop(): Int = (usableHeight() - floatingInsetPx - floatingPx).coerceAtLeast(0)

    fun floatingBottom(): Int = (usableHeight() - floatingInsetPx).coerceAtLeast(0)

    fun backLeft(): Int = floatingInsetPx

    fun backRight(): Int = floatingInsetPx + backWidthPx

    fun deleteLeft(): Int = (panelWidth - floatingInsetPx - floatingPx).coerceAtLeast(0)

    fun deleteRight(): Int = (panelWidth - floatingInsetPx).coerceAtLeast(0)

    // --- Skin-tone popup ------------------------------------------------------------------------

    /**
     * Opens the variant popup over cell [cell] with [variants] entries. The popup is a row of
     * cell-sized slots centred on its anchor, pushed inside the panel horizontally and drawn above
     * the anchor row — or below it when the anchor is too close to the top.
     */
    fun openPopup(cell: Int, variants: Int): Boolean {
        if (cell < 0 || cell >= entryCount() || variants <= 0) return false
        popupCell = cell
        popupVariants = variants
        popupVariant = NO_TARGET
        return true
    }

    fun closePopup(): Boolean {
        if (popupCell == NO_TARGET) return false
        popupCell = NO_TARGET
        popupVariants = 0
        popupVariant = NO_TARGET
        return true
    }

    fun isPopupOpen(): Boolean = popupCell != NO_TARGET

    fun popupCell(): Int = popupCell

    fun popupVariantCount(): Int = popupVariants

    /** The variant the finger is currently over, or [NO_TARGET]. */
    fun popupVariant(): Int = popupVariant

    fun popupWidth(): Int = popupVariants * cellWidth()

    fun popupHeight(): Int = cellHeight()

    fun popupLeft(): Int {
        if (!isPopupOpen()) return 0
        val columns = this.columns.coerceAtLeast(1)
        val section = sectionOfIndex(popupCell)
        val column = (popupCell - sectionStart[section]) % columns
        val centre = (columnLeft(column) + columnRight(column)) / 2
        val width = popupWidth()
        return (centre - width / 2).coerceIn(0, (panelWidth - width).coerceAtLeast(0))
    }

    fun popupRight(): Int = popupLeft() + popupWidth()

    /** Top of the popup in view coordinates; above the anchor row, or below it when it would clip. */
    fun popupTop(): Int {
        if (!isPopupOpen()) return 0
        val columns = this.columns.coerceAtLeast(1)
        val section = sectionOfIndex(popupCell)
        val row = (popupCell - sectionStart[section]) / columns
        val height = cellHeight()
        val anchorTop = gridTop() + sectionGridTop(section) + row * height - scrollY
        val above = anchorTop - height
        return if (above >= gridTop()) above else anchorTop + height
    }

    fun popupBottom(): Int = popupTop() + popupHeight()

    fun popupVariantLeft(variant: Int): Int = popupLeft() + variant * cellWidth()

    fun popupVariantRight(variant: Int): Int = popupLeft() + (variant + 1) * cellWidth()

    /** The slot a target paints in, kept for the pressed highlight of a tab. */
    fun slotOfTarget(target: Int): Int = if (isTab(target)) tabIndexOf(target) else NO_TARGET

    /**
     * Virtual-node count the accessibility delegate exposes: every visible cell, every tab, the
     * search pill and the two functional keys — or, while the skin-tone popup is up, exactly its
     * variants, because the popup owns the whole surface and a touch outside it dismisses rather
     * than reaching the grid. Kept here so it is verifiable without a device.
     */
    fun virtualNodeCount(): Int =
        if (isPopupOpen()) popupVariants else visibleCellCount() + tabCount() + 3

    // --- Hit testing --------------------------------------------------------------------------

    fun isInGrid(x: Float, y: Float): Boolean {
        val top = gridTop()
        return x >= 0f && x < panelWidth && y >= top && y < top + gridHeight()
    }

    private fun inRect(x: Float, y: Float, left: Int, top: Int, right: Int, bottom: Int): Boolean =
        x >= left && x < right && y >= top && y < bottom

    /**
     * The target under ([x], [y]): a compact global cell index (`>= 0`), [BACK_TARGET],
     * [DELETE_TARGET], [SEARCH_TARGET], a tab (see [isTab]/[tabIndexOf]), or [NO_TARGET]. The two
     * floating keys are tested first because they are drawn above the content.
     */
    fun targetAt(x: Float, y: Float): Int {
        if (x < 0f || x >= panelWidth || y < 0f || y >= panelHeight) return NO_TARGET
        if (isPopupOpen()) {
            // While the popup is up it owns the whole surface: it is drawn over everything, and a
            // touch outside it dismisses rather than reaching the grid underneath.
            if (y >= popupTop() && y < popupBottom() && x >= popupLeft() && x < popupRight()) {
                val cellWidth = cellWidth()
                if (cellWidth <= 0) return NO_TARGET
                val variant = ((x - popupLeft()) / cellWidth).toInt()
                if (variant in 0 until popupVariants) return POPUP_TARGET_BASE - variant
            }
            return NO_TARGET
        }
        if (floatingPx > 0) {
            val top = floatingTop()
            val bottom = floatingBottom()
            if (inRect(x, y, backLeft(), top, backRight(), bottom)) return BACK_TARGET
            if (inRect(x, y, deleteLeft(), top, deleteRight(), bottom)) return DELETE_TARGET
        }
        if (tabBarHeight() > 0 && y < tabBarHeight()) {
            val tabs = tabCount()
            if (tabs <= 0) return NO_TARGET
            // The side insets belong to the outermost tabs rather than being dead space.
            var tab = 0
            while (tab < tabs - 1 && x >= tabRight(tab)) tab++
            return TAB_TARGET_BASE - tab
        }
        if (searchBarHeight() > 0 && y < gridTop()) {
            return if (x >= searchLeft() && x < searchRight()) SEARCH_TARGET else NO_TARGET
        }
        val height = cellHeight()
        if (height <= 0 || columns <= 0) return NO_TARGET
        val gridTop = gridTop()
        if (y < gridTop || y >= gridTop + gridHeight()) return NO_TARGET
        val contentY = (y.toInt() - gridTop) + scrollY
        if (contentY < 0 || contentY >= contentHeight()) return NO_TARGET
        val section = sectionAtContentY(contentY)
        val rowTop = sectionGridTop(section)
        if (contentY < rowTop) return NO_TARGET // the header itself is not a target
        val row = (contentY - rowTop) / height
        if (row >= sectionRowCount(section)) return NO_TARGET
        var column = 0
        while (column < columns - 1 && x >= columnRight(column)) column++
        val local = row * columns + column
        if (local >= sectionEntryCount(section)) return NO_TARGET
        return sectionStart[section] + local
    }

    // --- Gesture state machine ----------------------------------------------------------------

    fun onDown(pointerId: Int, x: Float, y: Float): Int {
        cancelGesture()
        val target = targetAt(x, y)
        downTarget = target
        pressedTarget = target
        activePointerId = pointerId
        downInGrid = isInGrid(x, y) && !isPopupOpen()
        downX = x
        downY = y
        lastMoveY = y
        scrolling = false
        swiping = false
        if (isPopupOpen()) popupVariant = if (isPopupVariant(target)) popupVariantIndexOf(target) else NO_TARGET
        return target
    }

    /** Returns true when the visible state (scroll offset or pressed highlight) changed. */
    fun onMove(pointerId: Int, x: Float, y: Float, touchSlop: Int): Boolean {
        if (pointerId != activePointerId || activePointerId == INVALID_POINTER_ID) return false
        var changed = false
        if (isPopupOpen()) {
            // The finger slides along the open popup and the highlighted variant follows it.
            val here = targetAt(x, y)
            val variant = if (isPopupVariant(here)) popupVariantIndexOf(here) else NO_TARGET
            if (variant != popupVariant) {
                popupVariant = variant
                changed = true
            }
            return changed
        }
        // The axis is decided once, by whichever direction clears the slop first: a vertical drag
        // scrolls the content, a horizontal one jumps between sections. Deciding once is what keeps
        // a slightly slanted scroll from turning into a section jump halfway through.
        if (!scrolling && !swiping && downInGrid) {
            val dx = abs(x - downX)
            val dy = abs(y - downY)
            if (dy > touchSlop && dy >= dx) {
                scrolling = true
            } else if (dx > touchSlop && dx > dy) {
                swiping = true
            }
            if ((scrolling || swiping) && pressedTarget != NO_TARGET) {
                pressedTarget = NO_TARGET
                changed = true
            }
        }
        if (swiping) {
            lastMoveY = y
            return changed
        }
        if (scrolling) {
            val delta = (lastMoveY - y).toInt()
            lastMoveY = y
            if (scrollBy(delta)) changed = true
            return changed
        }
        lastMoveY = y
        val here = targetAt(x, y)
        val next = if (here == downTarget) downTarget else NO_TARGET
        if (next != pressedTarget) {
            pressedTarget = next
            changed = true
        }
        return changed
    }

    /** Returns the activated target, or [NO_TARGET] when the gesture became a scroll or slid off. */
    fun onUp(pointerId: Int, x: Float, y: Float): Int {
        if (pointerId != activePointerId) {
            cancelGesture()
            return NO_TARGET
        }
        if (isPopupOpen()) {
            // A release on a variant picks it; a release anywhere else dismisses the popup. The
            // caller closes the popup either way.
            val here = targetAt(x, y)
            cancelGesture()
            return if (isPopupVariant(here)) here else POPUP_DISMISS_TARGET
        }
        val result = when {
            swiping -> {
                val travel = x - downX
                if (abs(travel) >= swipeMinPx && swipeMinPx > 0) {
                    pendingSwipe = if (travel < 0f) 1 else -1
                }
                NO_TARGET
            }
            scrolling -> NO_TARGET
            targetAt(x, y) == downTarget -> downTarget
            else -> NO_TARGET
        }
        cancelGesture()
        return result
    }

    /** Drains the swipe left by the last [onUp]: -1 previous section, +1 next, 0 nothing. */
    fun consumeSwipe(): Int {
        val swipe = pendingSwipe
        pendingSwipe = 0
        return swipe
    }

    /** Ends the gesture only when Android reports the active pointer went up. */
    fun onPointerUp(pointerId: Int): Boolean =
        pointerId == activePointerId && cancelGesture()

    fun cancelGesture(): Boolean {
        val changed = downTarget != NO_TARGET ||
            pressedTarget != NO_TARGET ||
            activePointerId != INVALID_POINTER_ID ||
            scrolling ||
            swiping
        downTarget = NO_TARGET
        pressedTarget = NO_TARGET
        activePointerId = INVALID_POINTER_ID
        downInGrid = false
        scrolling = false
        swiping = false
        return changed
    }

    fun pressedTarget(): Int = pressedTarget

    fun downTarget(): Int = downTarget

    fun activePointerId(): Int = activePointerId

    fun isScrolling(): Boolean = scrolling

    fun isSwiping(): Boolean = swiping

    /**
     * Recomputes the two section-offset arrays. Called only from the four setters that can change
     * the content shape, never from drawing, hit testing or scrolling: this is the single place in
     * the class that allocates.
     */
    private fun rebuildLayout() {
        val sections = snapshot.categoryCount
        if (sections == 0) {
            sectionTop = EMPTY_INTS
            sectionStart = EMPTY_INTS
            scrollY = 0
            return
        }
        if (sectionTop.size != sections + 1) {
            sectionTop = IntArray(sections + 1)
            sectionStart = IntArray(sections + 1)
        }
        val height = cellHeight()
        var y = 0
        var index = 0
        for (section in 0 until sections) {
            sectionTop[section] = y
            sectionStart[section] = index
            val count = snapshot.entryCount(section)
            val rows = if (columns > 0) (count + columns - 1) / columns else 0
            y += headerPx + rows * height
            index += count
        }
        // Trailing air so the last row can be scrolled clear of the floating keys.
        sectionTop[sections] = y + floatingPx + 2 * floatingInsetPx
        sectionStart[sections] = index
        clampScroll()
    }

    private fun clampScroll() {
        scrollY = scrollY.coerceIn(0, maxScrollY())
    }

    companion object {
        private val EMPTY_INTS = IntArray(0)

        const val PORTRAIT_COLUMNS = 8
        const val LANDSCAPE_COLUMNS = 12
        /**
         * The floor the tab row and the search band may be squeezed to (Р-2). Below this they stop
         * reading as tappable bands, so the panel would rather keep them and show less content.
         */
        private const val MIN_BAND_SCALE = 0.6f

        /**
         * How many minimum-height rows the content is entitled to before the bands start yielding.
         * One row plus a header is what a squeezed panel already showed, so guaranteeing only that
         * would leave Р-2 exactly where it was; two rows is the smallest floor at which the squeeze
         * changes anything a user can see.
         */
        private const val CONTENT_FLOOR_ROWS = 2

        const val MIN_CELL_DP = 36
        const val MAX_CELL_DP = 56

        const val NO_TARGET = -1
        const val BACK_TARGET = -2
        const val DELETE_TARGET = -3
        const val SEARCH_TARGET = -4

        /** Returned by [onUp] when a release dismissed the popup without picking a variant. */
        const val POPUP_DISMISS_TARGET = -5

        // Popup variants occupy a short block well above the tab block, so the two never collide:
        // variant k is POPUP_TARGET_BASE - k, and there are at most VARIANT_COUNT of them.
        const val POPUP_TARGET_BASE = -50
        private const val POPUP_TARGET_SPAN = 16

        // Tabs occupy the block at and below this value: tab k is encoded as TAB_TARGET_BASE - k.
        const val TAB_TARGET_BASE = -100

        const val INVALID_POINTER_ID = -1

        fun isCell(target: Int): Boolean = target >= 0
        fun isBack(target: Int): Boolean = target == BACK_TARGET
        fun isDelete(target: Int): Boolean = target == DELETE_TARGET
        fun isSearch(target: Int): Boolean = target == SEARCH_TARGET
        fun isTab(target: Int): Boolean = target <= TAB_TARGET_BASE
        fun tabIndexOf(target: Int): Int = TAB_TARGET_BASE - target
        fun isPopupVariant(target: Int): Boolean =
            target <= POPUP_TARGET_BASE && target > POPUP_TARGET_BASE - POPUP_TARGET_SPAN
        fun popupVariantIndexOf(target: Int): Int = POPUP_TARGET_BASE - target
    }
}

/**
 * Pure delete auto-repeat state, driven by the panel view. The view fires one delete on
 * [begin], schedules a delayed [tick], and calls [cancel] on every stop condition (ACTION_UP,
 * ACTION_CANCEL, the finger leaving the delete key, the panel being hidden, and input-view
 * recreation). Because it is pure, "one gesture never commits twice" and "every stop condition
 * disarms the repeat" are verifiable on the JVM. The "АБВ" key never touches this class.
 */
internal class DeleteRepeatState {
    private var armed = false

    /** Total number of deletes this instance has fired; used by tests. */
    var fireCount = 0
        private set

    /** Begins a hold: fires once and arms the repeat. A second begin while armed does not fire. */
    fun begin(): Boolean {
        if (armed) return false
        armed = true
        fireCount++
        return true
    }

    /** A scheduled repeat step: fires only while still armed. */
    fun tick(): Boolean {
        if (!armed) return false
        fireCount++
        return true
    }

    /** Stops the repeat; idempotent. Returns true when it was armed. */
    fun cancel(): Boolean {
        val wasArmed = armed
        armed = false
        return wasArmed
    }

    fun isArmed(): Boolean = armed
}
