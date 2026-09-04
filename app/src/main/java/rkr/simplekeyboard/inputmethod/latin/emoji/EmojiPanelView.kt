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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.abs
import rkr.simplekeyboard.inputmethod.R

/**
 * The emoji panel: a real, allocation-free Canvas surface that replaces the
 * [rkr.simplekeyboard.inputmethod.keyboard.MainKeyboardView] while shown; the two never draw at
 * once. The keyboard ships no emoji font — every cell is drawn with the system font, and entries
 * the running device cannot render were already dropped by the glyph probe when the snapshot was
 * built, so no "tofu" box ever reaches a cell.
 *
 * The arrangement is the one the operator asked for, copied from the Telegram client: a row of
 * category tabs across the top with the active one under a round pill, a search pill under it, and
 * then one continuous scroll through every section, each introduced by its own header. The two
 * functional keys — "АБВ" (back to the letters) and delete — float over the content in the bottom
 * corners instead of sitting in a bar of their own. No space, no Enter.
 *
 * All geometry, hit testing and scrolling live in the pure [EmojiPanelState]; the auto-repeat of
 * delete lives in the pure [DeleteRepeatState]. This view owns only the Android surface: paints
 * built once, no allocations in [onDraw] or [onTouchEvent], and only the visible rows drawn.
 *
 * Insertion goes solely through the listener, which routes to `LatinIME.onTextInput(String)`;
 * delete routes through `LatinIME.onCodeInput(CODE_DELETE)`. This view never commits or deletes
 * text itself and never reads, logs or transmits the field text.
 */
class EmojiPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs, R.attr.mainKeyboardViewStyle) {

    /** Callbacks for the functional keys, the search pill and for picking an emoji; all on the UI thread. */
    interface Listener {
        /** The "АБВ" key: hide the panel and return to the letter keyboard. */
        fun onEmojiPanelBackToKeyboard()

        /** The delete key: one backspace through the ordinary code-input path. */
        fun onEmojiPanelDelete()

        /** A grid cell was tapped: insert [sequence] through the ordinary text-input path. */
        fun onEmojiPanelPick(sequence: String)

        /** The search pill was tapped: enter the emoji-search mode. */
        fun onEmojiPanelSearch()
    }

    private companion object {
        private const val LABEL_TEXT_SIZE_SP = 16f
        private const val HEADER_TEXT_SIZE_SP = 14f
        private const val SEARCH_TEXT_SIZE_SP = 15f

        // Glyph size as a fraction of the cell. The old panel squeezed the cell to fit whole rows
        // and drew at 0.62 of the squeezed height, which is what made the emoji look small next to
        // the reference; the cell is square again and the glyph fills more of it.
        private const val EMOJI_TEXT_SCALE = 0.66f
        private const val TAB_TEXT_SCALE = 0.46f

        private const val PRESSED_ALPHA = 90
        private const val ACTIVE_TAB_ALPHA = 0x40
        private const val HEADER_ALPHA = 0xE6
        private const val SEARCH_PILL_ALPHA = 0x80
        private const val SEARCH_HINT_ALPHA = 0xB0

        private const val MIN_CELL_DP = EmojiPanelState.MIN_CELL_DP.toFloat()
        private const val MAX_CELL_DP = EmojiPanelState.MAX_CELL_DP.toFloat()

        // The three fixed bands and the floating keys, in dp.
        private const val TAB_BAR_DP = 44f
        private const val SEARCH_BAR_DP = 50f
        private const val SECTION_HEADER_DP = 30f
        private const val FLOATING_KEY_DP = 44f
        private const val FLOATING_INSET_DP = 8f

        // A halo of the sheet colour around each floating key, so the key never blurs into the
        // emoji it is drawn over. The reference gets that separation for free from a dark button on
        // a dark sheet; a light theme needs it drawn.
        private const val FLOATING_HALO_DP = 3f

        // A sideways drag becomes a jump between sections once it travels this far. Four times the
        // platform slop: far enough that a slanted scroll never trips it, short enough to flick.
        private const val SWIPE_MIN_SLOPS = 4

        // How long the section jump animates, in ms. Long enough to read as movement rather than a
        // teleport, short enough not to feel like waiting.
        private const val SECTION_JUMP_MS = 220

        // The skin-tone popup: a rounded card of variant cells over the anchor.
        private const val POPUP_RADIUS_DP = 10f
        private const val POPUP_HALO_DP = 3f
        private const val POPUP_SELECTED_ALPHA = 0x55

        // Insets of the pills inside their bands.
        private const val SEARCH_PILL_INSET_DP = 5f
        private const val TAB_PILL_INSET_DP = 4f
        private const val HEADER_TEXT_INSET_DP = 12f
        private const val SEARCH_ICON_INSET_DP = 18f
        private const val SEARCH_TEXT_INSET_DP = 40f

        // The magnifier is drawn from primitives rather than shipped as a font or a bitmap.
        private const val SEARCH_ICON_RADIUS_DP = 6f
        private const val SEARCH_ICON_STROKE_DP = 1.6f
        private const val SEARCH_ICON_HANDLE_DP = 5f

        // The recents tab carries a clock, as in the reference, rather than whichever emoji happens
        // to be most recent. It is drawn from primitives too, so still no icon font ships.
        private const val CLOCK_ICON_RADIUS_DP = 9f
        private const val CLOCK_ICON_STROKE_DP = 1.8f

        private const val BACK_LABEL = "АБВ"

        // U+232B ERASE TO THE LEFT: a system glyph, so the panel ships no font of its own.
        private const val DELETE_LABEL = "⌫"

        // Padding around the "АБВ" label that sets how wide the floating back key is.
        private const val BACK_PADDING_DP = 16f

        // VelocityTracker reports velocity in px per this many milliseconds.
        private const val VELOCITY_UNITS = 1000

        // Accessibility virtual-view id space. Cell ids are the compact global cell index (0 until
        // entryCount, at most ~1389), so the tab and functional-key ids sit far above any cell id
        // and can never collide with one.
        private const val TAB_ID_BASE = 1_000_000
        private const val BACK_ID = 2_000_000
        private const val DELETE_ID = 2_000_001
        private const val SEARCH_ID = 2_000_002

        // Skin-tone popup variants get their own id block above the functional keys.
        private const val POPUP_ID_BASE = 3_000_000
    }

    private val state = EmojiPanelState()
    private val deleteRepeat = DeleteRepeatState()

    /** The single, reusable fling scroller for the whole View lifetime. */
    private val scroller = OverScroller(context)

    /** Obtained at most once per gesture on ACTION_DOWN, recycled on ACTION_UP/ACTION_CANCEL. */
    private var velocityTracker: VelocityTracker? = null

    private val backgroundPaint = Paint()
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activeTabPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val searchPillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val functionalKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val floatingHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val popupSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val searchIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val clockIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** The one reusable rect every rounded pill is drawn through. */
    private val keyRect = RectF()
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            LABEL_TEXT_SIZE_SP,
            resources.displayMetrics,
        )
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            HEADER_TEXT_SIZE_SP,
            resources.displayMetrics,
        )
        isFakeBoldText = true
    }
    private val searchTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            SEARCH_TEXT_SIZE_SP,
            resources.displayMetrics,
        )
    }
    private val emojiFontMetrics = Paint.FontMetrics()
    private val tabFontMetrics = Paint.FontMetrics()
    private val labelFontMetrics = Paint.FontMetrics()
    private val headerFontMetrics = Paint.FontMetrics()
    private val searchFontMetrics = Paint.FontMetrics()

    private val minCellPx = dp(MIN_CELL_DP)
    private val maxCellPx = dp(MAX_CELL_DP)
    private val tabBarPx = dp(TAB_BAR_DP)
    private val searchBarPx = dp(SEARCH_BAR_DP)
    private val sectionHeaderPx = dp(SECTION_HEADER_DP)
    // Scratch for the navigation-bar overlap measured in onLayout: reused, so nothing is
    // allocated on a layout pass.
    private val visibleFrameScratch = Rect()
    private val locationScratch = IntArray(2)

    private val floatingKeyPx = dp(FLOATING_KEY_DP)
    private val floatingInsetPx = dp(FLOATING_INSET_DP)
    private val searchPillInsetPx = dp(SEARCH_PILL_INSET_DP).toFloat()
    private val tabPillInsetPx = dp(TAB_PILL_INSET_DP).toFloat()
    private val headerTextInsetPx = dp(HEADER_TEXT_INSET_DP).toFloat()
    private val searchIconInsetPx = dp(SEARCH_ICON_INSET_DP).toFloat()
    private val searchTextInsetPx = dp(SEARCH_TEXT_INSET_DP).toFloat()
    private val searchIconRadiusPx = dp(SEARCH_ICON_RADIUS_DP).toFloat()
    private val searchIconHandlePx = dp(SEARCH_ICON_HANDLE_DP).toFloat()
    private val clockIconRadiusPx = dp(CLOCK_ICON_RADIUS_DP).toFloat()

    /** Set when category 0 is the recents, so the tab row draws a clock instead of an emoji. */
    private var hasRecentTab = false

    /**
     * Width of the floating "АБВ" key: its own label plus padding, measured once here rather than
     * assumed, so a longer localized label can never overrun the key.
     */
    private val backWidthPx = (labelPaint.measureText(BACK_LABEL) + 2 * dp(BACK_PADDING_DP)).toInt()

    private val popupRadiusPx = dp(POPUP_RADIUS_DP).toFloat()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()

    /** Bound once the controller has read the packed asset; empty until then. */
    private var skinTones: EmojiSkinTones = EmojiSkinTones.EMPTY

    /** The neutral sequence the open popup belongs to, so a pick composes from the right base. */
    private var popupBase: String = ""

    /**
     * True between the long press that opened the popup and the release that ends the same gesture.
     * The release of the opening press must not dismiss what it has just put on screen: the finger
     * is still on the anchor, which is outside the popup card. Keeping the popup up lets the two
     * usual gestures both work — slide onto a variant and let go, or let go and then tap one.
     */
    private var popupOpenedThisGesture = false

    /**
     * The popup's variant strings, composed once when it opens and reused by every frame after.
     * Composing them in [onDraw] would allocate six strings a frame, which the panel's contract
     * forbids.
     */
    private val popupVariants = Array(EmojiSkinTones.VARIANT_COUNT) { "" }
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val repeatStartTimeoutMs =
        resources.getInteger(R.integer.config_key_repeat_start_timeout).toLong()
    private val repeatIntervalMs =
        resources.getInteger(R.integer.config_key_repeat_interval).toLong()

    private var panelHeightPx = 0
    private var tabLabels: Array<String> = emptyArray()

    // The category name of each tab, kept alongside [tabLabels] so the header row and the
    // accessibility delegate can read a localized title without recomputing any geometry.
    private var tabNames: Array<String> = emptyArray()

    // The localized visible title of each section, resolved once per snapshot so onDraw never
    // touches resources.
    private var sectionTitles: Array<String> = emptyArray()

    private val searchHint: String = context.getString(R.string.emoji_search_hint)

    // True while a fling is animating, so a single "scroll finished" accessibility refresh fires on
    // the frame the scroller settles rather than on every frame.
    private var flingActive = false
    private var listener: Listener? = null

    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val accessibilityHelper = EmojiPanelAccessibilityHelper()

    /**
     * Opens the skin-tone popup when a press on a tone-capable cell outlives the platform long-press
     * timeout. The pending tap is dropped, so the same press can never both insert the neutral
     * emoji and open the popup.
     */
    private val longPressRunnable = Runnable {
        val cell = state.downTarget()
        if (!EmojiPanelState.isCell(cell) || state.isScrolling() || state.isSwiping()) {
            return@Runnable
        }
        val sequence = state.entryAt(cell)
        if (!skinTones.hasTones(sequence)) {
            return@Runnable
        }
        popupBase = sequence
        var variant = 0
        while (variant < popupVariants.size) {
            popupVariants[variant] = skinTones.variantAt(sequence, variant)
            variant++
        }
        if (state.openPopup(cell, EmojiSkinTones.VARIANT_COUNT)) {
            popupOpenedThisGesture = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            invalidateAccessibilityRootIfExploring()
        }
    }

    private val deleteRepeatRunnable = object : Runnable {
        override fun run() {
            if (deleteRepeat.tick()) {
                listener?.onEmojiPanelDelete()
                postDelayed(this, repeatIntervalMs)
            }
        }
    }

    init {
        val themeColors = context.theme.obtainStyledAttributes(R.styleable.EmojiPanelView)
        // The sheet is the keyboard background, not the key colour: filling it with the key colour
        // made the whole panel read as one unpainted rectangle instead of as the keyboard surface.
        val keyColor = themeColors.getColor(R.styleable.EmojiPanelView_keyNormalBackgroundColor, Color.LTGRAY)
        val functionalColor = themeColors.getColor(R.styleable.EmojiPanelView_emojiPanelFunctionalKeyColor, keyColor)
        backgroundPaint.color = themeColors.getColor(R.styleable.EmojiPanelView_emojiPanelBackgroundColor, keyColor)
        labelPaint.color = themeColors.getColor(R.styleable.EmojiPanelView_functionalTextColor, Color.DKGRAY)
        tabPaint.color = themeColors.getColor(R.styleable.EmojiPanelView_functionalTextColor, Color.DKGRAY)
        pressedPaint.color = themeColors.getColor(R.styleable.EmojiPanelView_keyPressedBackgroundColor, Color.GRAY)
        emojiPaint.color = themeColors.getColor(R.styleable.EmojiPanelView_keyTextColor, Color.BLACK)
        functionalKeyPaint.color = functionalColor
        themeColors.recycle()
        activeTabPaint.color = withAlpha(functionalColor, ACTIVE_TAB_ALPHA + 0x7F)
        searchPillPaint.color = withAlpha(functionalColor, SEARCH_PILL_ALPHA + 0x60)
        headerPaint.color = withAlpha(labelPaint.color, HEADER_ALPHA)
        searchTextPaint.color = withAlpha(labelPaint.color, SEARCH_HINT_ALPHA)
        searchIconPaint.color = withAlpha(labelPaint.color, SEARCH_HINT_ALPHA)
        searchIconPaint.strokeWidth = dp(SEARCH_ICON_STROKE_DP).toFloat()
        clockIconPaint.color = labelPaint.color
        clockIconPaint.strokeWidth = dp(CLOCK_ICON_STROKE_DP).toFloat()
        pressedPaint.alpha = PRESSED_ALPHA
        floatingHaloPaint.color = backgroundPaint.color
        floatingHaloPaint.strokeWidth = dp(FLOATING_HALO_DP).toFloat()
        popupPaint.color = functionalColor
        popupHaloPaint.color = backgroundPaint.color
        popupHaloPaint.strokeWidth = dp(POPUP_HALO_DP).toFloat()
        popupSelectedPaint.color = withAlpha(labelPaint.color, POPUP_SELECTED_ALPHA)
        state.setSwipeMinDistance(touchSlop * SWIPE_MIN_SLOPS)
        applyMetrics()
        state.setColumns(currentColumns())
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    private fun applyMetrics() {
        state.setCellMetrics(
            minCellPx,
            maxCellPx,
            tabBarPx,
            searchBarPx,
            sectionHeaderPx,
            floatingKeyPx,
            floatingInsetPx,
            backWidthPx,
        )
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Binds the skin-tone table; until it arrives a long press simply does nothing. */
    fun setSkinTones(tones: EmojiSkinTones) {
        skinTones = tones
    }

    /** Binds the published snapshot and precomputes the tab glyphs and the section titles. */
    fun setSnapshot(snapshot: EmojiSetSnapshot) {
        state.setColumns(currentColumns())
        state.setSnapshot(snapshot)
        val count = snapshot.categoryCount
        tabLabels = Array(count) { snapshot.entryAt(it, 0) }
        tabNames = Array(count) { snapshot.categoryName(it) }
        sectionTitles = Array(count) { categoryTitle(snapshot.categoryName(it)).toString() }
        hasRecentTab = count > 0 &&
            snapshot.categoryName(0) == EmojiDisplaySnapshots.RECENT_CATEGORY_NAME
        invalidate()
        // A rebind rebuilds the whole content, so the virtual-node tree changed; refresh it, but
        // only while a screen reader is actually exploring.
        invalidateAccessibilityRootIfExploring()
    }

    /** Matches the panel to the current keyboard height so insets stay identical to the keyboard. */
    fun setPanelHeightPx(heightPx: Int) {
        if (heightPx > 0 && heightPx != panelHeightPx) {
            panelHeightPx = heightPx
            requestLayout()
        }
    }

    /** Drops transient state, the delete repeat and the listener before detach or replacement. */
    fun release() {
        cancelDeleteRepeat()
        cancelSkinTonePopupTimer()
        popupOpenedThisGesture = false
        state.closePopup()
        scroller.forceFinished(true)
        flingActive = false
        recycleVelocityTracker()
        state.cancelGesture()
        listener = null
        visibility = GONE
        invalidateAccessibilityRootIfExploring()
    }

    /**
     * Frees the bound snapshot and per-layout caches under memory pressure
     * ([rkr.simplekeyboard.inputmethod.latin.LatinIME] `MSG_DEALLOCATE_MEMORY`, 10 s) or when input
     * finishes. The panel allocates no offscreen buffer, so there is nothing else to free; the
     * reusable paints and the single [scroller] stay. The controller keeps the one prepared snapshot
     * for the whole process (it is never re-prepared), so the next show simply re-binds it through
     * [setSnapshot]. A no-op while the panel is visible, so it never blanks a shown grid.
     */
    fun releaseSnapshotCaches() {
        if (visibility == VISIBLE) {
            return
        }
        cancelDeleteRepeat()
        cancelSkinTonePopupTimer()
        state.closePopup()
        scroller.forceFinished(true)
        flingActive = false
        state.cancelGesture()
        state.setSnapshot(EmojiSetSnapshot.EMPTY)
        tabLabels = emptyArray()
        tabNames = emptyArray()
        sectionTitles = emptyArray()
        hasRecentTab = false
        invalidateAccessibilityRootIfExploring()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val fallback = MeasureSpec.getSize(heightMeasureSpec)
        val desiredHeight = if (panelHeightPx > 0) panelHeightPx else fallback
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    /**
     * Re-measures how far the panel reaches under the navigation bar. Called from [onLayout],
     * because that is where both terms are final: the panel's own position on screen and the
     * frame the system bars leave free.
     *
     * [getWindowVisibleDisplayFrame] is the seam that makes this work without asking which Android
     * version is running. It reports the same bottom — the top edge of the navigation bar — on
     * every platform; what differs is how far the IME window itself reaches past it. Subtracting
     * one from the other gives the real overlap: zero through Android 14, where the framework lays
     * the input view out above the bar, and the bar's height from Android 15, where it does not.
     */
    private fun updateBottomInset() {
        getWindowVisibleDisplayFrame(visibleFrameScratch)
        getLocationOnScreen(locationScratch)
        val overlap = locationScratch[1] + height - visibleFrameScratch.bottom
        if (state.setBottomInset(overlap.coerceAtLeast(0))) {
            invalidateAccessibilityRootIfExploring()
            invalidate()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateBottomInset()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        state.setColumns(currentColumns())
        applyMetrics()
        state.setViewport(width, height)
        updateBottomInset()
        emojiPaint.textSize = state.cellHeight() * EMOJI_TEXT_SCALE
        emojiPaint.getFontMetrics(emojiFontMetrics)
        tabPaint.textSize = tabBarPx * TAB_TEXT_SCALE
        tabPaint.getFontMetrics(tabFontMetrics)
        labelPaint.getFontMetrics(labelFontMetrics)
        headerPaint.getFontMetrics(headerFontMetrics)
        searchTextPaint.getFontMetrics(searchFontMetrics)
        invalidateAccessibilityRootIfExploring()
    }

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            // Physics live in EmojiFling: clamp the scroller's position into range, then keep
            // animating until the scroller settles. onDraw still paints only the visible rows.
            flingActive = true
            state.setScrollY(EmojiFling.clampScroll(scroller.currY, state.maxScrollY()))
            postInvalidateOnAnimation()
            return
        }
        if (flingActive) {
            // The fling just settled: the visible-cell set is final, so refresh the virtual-node
            // tree exactly once, and only while a screen reader is exploring.
            flingActive = false
            invalidateAccessibilityRootIfExploring()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        val pressed = state.pressedTarget()
        drawTabRow(canvas, pressed)
        drawSearchBar(canvas, pressed)
        drawContent(canvas, w, pressed)
        drawFloatingKeys(canvas, pressed)
        drawSkinTonePopup(canvas)
    }

    /** The skin-tone popup, drawn over everything: a rounded card of the neutral cell plus five tones. */
    private fun drawSkinTonePopup(canvas: Canvas) {
        if (!state.isPopupOpen()) return
        val left = state.popupLeft().toFloat()
        val top = state.popupTop().toFloat()
        val right = state.popupRight().toFloat()
        val bottom = state.popupBottom().toFloat()
        if (right <= left || bottom <= top) return
        keyRect.set(left, top, right, bottom)
        canvas.drawRoundRect(keyRect, popupRadiusPx, popupRadiusPx, popupHaloPaint)
        canvas.drawRoundRect(keyRect, popupRadiusPx, popupRadiusPx, popupPaint)

        val emojiCenterOffset = -(emojiFontMetrics.ascent + emojiFontMetrics.descent) / 2f
        val baseline = (top + bottom) / 2f + emojiCenterOffset
        val selected = state.popupVariant()
        var variant = 0
        val variants = state.popupVariantCount()
        while (variant < variants) {
            val variantLeft = state.popupVariantLeft(variant).toFloat()
            val variantRight = state.popupVariantRight(variant).toFloat()
            if (variant == selected) {
                val inset = (variantRight - variantLeft) * 0.06f
                keyRect.set(variantLeft + inset, top + inset, variantRight - inset, bottom - inset)
                canvas.drawRoundRect(keyRect, popupRadiusPx, popupRadiusPx, popupSelectedPaint)
            }
            canvas.drawText(
                popupVariants[variant],
                (variantLeft + variantRight) / 2f,
                baseline,
                emojiPaint,
            )
            variant++
        }
    }

    /** The top row of category tabs; the active one sits under a round pill, as in the reference. */
    private fun drawTabRow(canvas: Canvas, pressed: Int) {
        val tabs = state.tabCount()
        if (tabs <= 0 || tabBarPx <= 0) return
        val active = state.activeCategory()
        val baseline = tabBarPx / 2f - (tabFontMetrics.ascent + tabFontMetrics.descent) / 2f
        var tab = 0
        while (tab < tabs) {
            val left = state.tabLeft(tab).toFloat()
            val right = state.tabRight(tab).toFloat()
            val pill = minOf(right - left, tabBarPx.toFloat()) - 2 * tabPillInsetPx
            if (pill > 0f) {
                val centerX = (left + right) / 2f
                val centerY = tabBarPx / 2f
                keyRect.set(
                    centerX - pill / 2f,
                    centerY - pill / 2f,
                    centerX + pill / 2f,
                    centerY + pill / 2f,
                )
                if (tab == active) {
                    canvas.drawRoundRect(keyRect, pill / 2f, pill / 2f, activeTabPaint)
                }
                if (EmojiPanelState.isTab(pressed) && EmojiPanelState.tabIndexOf(pressed) == tab) {
                    canvas.drawRoundRect(keyRect, pill / 2f, pill / 2f, pressedPaint)
                }
            }
            if (tab == 0 && hasRecentTab) {
                drawClockIcon(canvas, (left + right) / 2f, tabBarPx / 2f)
            } else {
                canvas.drawText(tabLabels[tab], (left + right) / 2f, baseline, tabPaint)
            }
            tab++
        }
    }

    /** The recents tab's clock: a ring and two hands, so the tab reads as "recent", not as an emoji. */
    private fun drawClockIcon(canvas: Canvas, centerX: Float, centerY: Float) {
        canvas.drawCircle(centerX, centerY, clockIconRadiusPx, clockIconPaint)
        canvas.drawLine(centerX, centerY, centerX, centerY - clockIconRadiusPx * 0.55f, clockIconPaint)
        canvas.drawLine(centerX, centerY, centerX + clockIconRadiusPx * 0.45f, centerY, clockIconPaint)
    }

    /** The search pill: a wide rounded band with a drawn magnifier and the localized hint. */
    private fun drawSearchBar(canvas: Canvas, pressed: Int) {
        if (searchBarPx <= 0) return
        val top = state.searchBarTop().toFloat() + searchPillInsetPx
        val bottom = (state.searchBarTop() + searchBarPx).toFloat() - searchPillInsetPx
        val left = state.searchLeft().toFloat()
        val right = state.searchRight().toFloat()
        if (right <= left || bottom <= top) return
        val radius = (bottom - top) / 2f
        keyRect.set(left, top, right, bottom)
        canvas.drawRoundRect(keyRect, radius, radius, searchPillPaint)
        if (EmojiPanelState.isSearch(pressed)) {
            canvas.drawRoundRect(keyRect, radius, radius, pressedPaint)
        }
        val centerY = (top + bottom) / 2f
        val iconX = left + searchIconInsetPx
        canvas.drawCircle(iconX, centerY - searchIconRadiusPx / 4f, searchIconRadiusPx, searchIconPaint)
        val diagonal = searchIconRadiusPx * 0.7071f
        canvas.drawLine(
            iconX + diagonal,
            centerY - searchIconRadiusPx / 4f + diagonal,
            iconX + diagonal + searchIconHandlePx * 0.7071f,
            centerY - searchIconRadiusPx / 4f + diagonal + searchIconHandlePx * 0.7071f,
            searchIconPaint,
        )
        val baseline = centerY - (searchFontMetrics.ascent + searchFontMetrics.descent) / 2f
        canvas.drawText(searchHint, left + searchTextInsetPx, baseline, searchTextPaint)
    }

    /** The scrolling content: only the sections and rows inside the viewport are drawn. */
    private fun drawContent(canvas: Canvas, w: Float, pressed: Int) {
        val sections = state.sectionCount()
        if (sections <= 0) return
        val columns = state.columnCount()
        val cellHeight = state.cellHeight()
        if (columns <= 0 || cellHeight <= 0) return
        val scrollY = state.scrollY()
        val gridTop = state.gridTop().toFloat()
        val gridBottom = gridTop + state.gridHeight()
        val emojiCenterOffset = -(emojiFontMetrics.ascent + emojiFontMetrics.descent) / 2f
        val headerBaselineOffset =
            sectionHeaderPx / 2f - (headerFontMetrics.ascent + headerFontMetrics.descent) / 2f

        canvas.save()
        canvas.clipRect(0f, gridTop, w, gridBottom)
        val firstSection = state.firstVisibleSection()
        val lastSection = state.lastVisibleSection()
        var section = firstSection
        while (section in firstSection..lastSection) {
            val headerTop = gridTop + (state.sectionTop(section) - scrollY)
            if (headerTop < gridBottom && headerTop + sectionHeaderPx > gridTop) {
                canvas.drawText(
                    sectionTitles[section],
                    headerTextInsetPx,
                    headerTop + headerBaselineOffset,
                    headerPaint,
                )
            }
            val firstRow = state.firstVisibleRowOf(section)
            val lastRow = state.lastVisibleRowOf(section)
            if (firstRow >= 0 && lastRow >= firstRow) {
                val sectionGridTop = gridTop + (state.sectionGridTop(section) - scrollY)
                val start = state.sectionStartIndex(section)
                val count = state.sectionEntryCount(section)
                var row = firstRow
                while (row <= lastRow) {
                    var column = 0
                    while (column < columns) {
                        val local = row * columns + column
                        if (local >= count) break
                        val left = state.columnLeft(column).toFloat()
                        val right = state.columnRight(column).toFloat()
                        val top = sectionGridTop + row * cellHeight
                        val index = start + local
                        if (EmojiPanelState.isCell(pressed) && pressed == index) {
                            val inset = (right - left) * 0.08f
                            keyRect.set(left + inset, top + inset, right - inset, top + cellHeight - inset)
                            canvas.drawRoundRect(keyRect, inset * 2f, inset * 2f, pressedPaint)
                        }
                        canvas.drawText(
                            state.entryAt(index),
                            (left + right) / 2f,
                            top + cellHeight / 2f + emojiCenterOffset,
                            emojiPaint,
                        )
                        column++
                    }
                    row++
                }
            }
            section++
        }
        canvas.restore()
    }

    /** "АБВ" and delete, floating over the content in the bottom corners as in the reference. */
    private fun drawFloatingKeys(canvas: Canvas, pressed: Int) {
        if (floatingKeyPx <= 0) return
        val top = state.floatingTop().toFloat()
        val bottom = state.floatingBottom().toFloat()
        val radius = (bottom - top) / 2f
        val labelBaseline = (top + bottom) / 2f - (labelFontMetrics.ascent + labelFontMetrics.descent) / 2f

        keyRect.set(state.backLeft().toFloat(), top, state.backRight().toFloat(), bottom)
        canvas.drawRoundRect(keyRect, radius, radius, floatingHaloPaint)
        canvas.drawRoundRect(keyRect, radius, radius, functionalKeyPaint)
        if (EmojiPanelState.isBack(pressed)) {
            canvas.drawRoundRect(keyRect, radius, radius, pressedPaint)
        }
        canvas.drawText(BACK_LABEL, keyRect.centerX(), labelBaseline, labelPaint)

        keyRect.set(state.deleteLeft().toFloat(), top, state.deleteRight().toFloat(), bottom)
        canvas.drawRoundRect(keyRect, radius, radius, floatingHaloPaint)
        canvas.drawRoundRect(keyRect, radius, radius, functionalKeyPaint)
        if (EmojiPanelState.isDelete(pressed)) {
            canvas.drawRoundRect(keyRect, radius, radius, pressedPaint)
        }
        canvas.drawText(DELETE_LABEL, keyRect.centerX(), labelBaseline, labelPaint)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) {
                    scroller.forceFinished(true)
                }
                obtainVelocityTracker()
                velocityTracker?.addMovement(event)
                val pointerIndex = event.actionIndex
                val target = state.onDown(
                    event.getPointerId(pointerIndex),
                    event.getX(pointerIndex),
                    event.getY(pointerIndex),
                )
                if (EmojiPanelState.isDelete(target) && deleteRepeat.begin()) {
                    listener?.onEmojiPanelDelete()
                    postDelayed(deleteRepeatRunnable, repeatStartTimeoutMs)
                }
                popupOpenedThisGesture = false
                maybeArmLongPress(target)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val pointerIndex = event.findPointerIndex(state.activePointerId())
                if (pointerIndex < 0) return true
                val changed = state.onMove(
                    event.getPointerId(pointerIndex),
                    event.getX(pointerIndex),
                    event.getY(pointerIndex),
                    touchSlop,
                )
                if (!EmojiPanelState.isDelete(state.pressedTarget())) {
                    cancelDeleteRepeat()
                }
                if (!EmojiPanelState.isCell(state.pressedTarget())) {
                    cancelSkinTonePopupTimer()
                }
                if (changed) invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return true
            MotionEvent.ACTION_POINTER_UP -> {
                if (state.onPointerUp(event.getPointerId(event.actionIndex))) {
                    cancelDeleteRepeat()
                    cancelSkinTonePopupTimer()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelDeleteRepeat()
                cancelSkinTonePopupTimer()
                velocityTracker?.addMovement(event)
                val pointerIndex = event.actionIndex
                val wasScrolling = state.isScrolling()
                maybeFling(wasScrolling)
                recycleVelocityTracker()
                val target = state.onUp(
                    event.getPointerId(pointerIndex),
                    event.getX(pointerIndex),
                    event.getY(pointerIndex),
                )
                invalidate()
                // A drag-scroll that did not turn into a fling has finished here; refresh the a11y
                // tree once (the fling path refreshes from computeScroll when the scroller settles).
                if (wasScrolling && scroller.isFinished) {
                    invalidateAccessibilityRootIfExploring()
                }
                dispatchTarget(target)
                maybeJumpSection(state.consumeSwipe())
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelDeleteRepeat()
                cancelSkinTonePopupTimer()
                state.closePopup()
                scroller.forceFinished(true)
                recycleVelocityTracker()
                state.cancelGesture()
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            cancelDeleteRepeat()
            cancelSkinTonePopupTimer()
            popupOpenedThisGesture = false
            state.closePopup()
            state.cancelGesture()
        }
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun dispatchTarget(target: Int) {
        when {
            EmojiPanelState.isPopupVariant(target) -> {
                val variant = EmojiPanelState.popupVariantIndexOf(target)
                val sequence = popupVariants.getOrElse(variant) { popupBase }
                state.closePopup()
                popupOpenedThisGesture = false
                invalidate()
                invalidateAccessibilityRootIfExploring()
                if (sequence.isNotEmpty()) listener?.onEmojiPanelPick(sequence)
            }
            target == EmojiPanelState.POPUP_DISMISS_TARGET -> {
                if (!popupOpenedThisGesture) {
                    state.closePopup()
                    invalidateAccessibilityRootIfExploring()
                }
                popupOpenedThisGesture = false
                invalidate()
            }
            EmojiPanelState.isCell(target) -> listener?.onEmojiPanelPick(state.entryAt(target))
            EmojiPanelState.isBack(target) -> listener?.onEmojiPanelBackToKeyboard()
            EmojiPanelState.isSearch(target) -> listener?.onEmojiPanelSearch()
            EmojiPanelState.isTab(target) -> if (state.setActiveCategory(EmojiPanelState.tabIndexOf(target))) {
                invalidate()
                invalidateAccessibilityRootIfExploring()
            }
            // Delete already fired on ACTION_DOWN through the auto-repeat; nothing to do on up.
        }
    }

    private fun cancelDeleteRepeat() {
        if (deleteRepeat.cancel()) {
            removeCallbacks(deleteRepeatRunnable)
        }
    }

    /** Arms the skin-tone long press, but only over a cell whose emoji actually has tones. */
    private fun maybeArmLongPress(target: Int) {
        cancelSkinTonePopupTimer()
        if (skinTones.isEmpty || !EmojiPanelState.isCell(target) || state.isPopupOpen()) {
            return
        }
        if (!skinTones.hasTones(state.entryAt(target))) {
            return
        }
        postDelayed(longPressRunnable, longPressTimeoutMs)
    }

    private fun cancelSkinTonePopupTimer() {
        removeCallbacks(longPressRunnable)
    }

    /**
     * Animates a sideways flick into a jump to the neighbouring section. The same single [scroller]
     * that carries a fling carries this, so there is still no second animator and no allocation.
     */
    private fun maybeJumpSection(direction: Int) {
        if (direction == 0) return
        val sections = state.sectionCount()
        if (sections <= 0) return
        val target = (state.activeCategory() + direction).coerceIn(0, sections - 1)
        val from = state.scrollY()
        val to = state.sectionTop(target).coerceIn(0, state.maxScrollY())
        if (to == from) return
        scroller.forceFinished(true)
        scroller.startScroll(0, from, 0, to - from, SECTION_JUMP_MS)
        postInvalidateOnAnimation()
    }

    /** Obtains the per-gesture [VelocityTracker] at most once; DOWN calls this, UP/CANCEL recycle. */
    private fun obtainVelocityTracker() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
    }

    /** Releases the per-gesture [VelocityTracker]; called on ACTION_UP, ACTION_CANCEL and release. */
    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /**
     * On release of a gesture that was scrolling, starts a fling when the release speed clears the
     * platform minimum. The fling/tap decision and the scroll clamp are the pure [EmojiFling]
     * physics; the single reusable [scroller] carries the motion and [computeScroll] advances it.
     */
    private fun maybeFling(wasScrolling: Boolean) {
        val tracker = velocityTracker ?: return
        if (!wasScrolling) {
            return
        }
        tracker.computeCurrentVelocity(VELOCITY_UNITS, maxFlingVelocity.toFloat())
        val velocityY = tracker.getYVelocity(state.activePointerId())
        if (EmojiFling.shouldFling(true, velocityY, minFlingVelocity, state.maxScrollY())) {
            scroller.forceFinished(true)
            scroller.fling(0, state.scrollY(), 0, -velocityY.toInt(), 0, 0, 0, state.maxScrollY())
            postInvalidateOnAnimation()
        }
    }

    private fun currentColumns(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            EmojiPanelState.LANDSCAPE_COLUMNS
        } else {
            EmojiPanelState.PORTRAIT_COLUMNS
        }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    ).toInt()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceAtMost(0xFF), Color.red(color), Color.green(color), Color.blue(color))

    // --- Accessibility ------------------------------------------------------------------------

    /**
     * Refreshes the ExploreByTouchHelper virtual-node tree, but only while touch exploration is on
     * — the single gate behind every panel invalidateRoot, exactly as the suggestion strip keeps
     * its rare announcements behind the same check. The panel calls this on a category change and
     * once a scroll settles; it never fires during an in-progress scroll or when no screen reader
     * is exploring.
     */
    private fun invalidateAccessibilityRootIfExploring() {
        if (accessibilityManager.isTouchExplorationEnabled) {
            accessibilityHelper.invalidateRoot()
        }
    }

    /**
     * Runs a virtual node's activation through the SAME listener path a finger tap uses, so a click
     * from a screen reader never adds a second insertion or deletion route: a cell goes through
     * [Listener.onEmojiPanelPick] (-> `LatinIME.onTextInput`), delete through
     * [Listener.onEmojiPanelDelete] (-> `LatinIME.onCodeInput`), back through
     * [Listener.onEmojiPanelBackToKeyboard], search through [Listener.onEmojiPanelSearch], and a
     * tab scrolls to its section. Returns true when it did something.
     */
    private fun activateForAccessibility(target: Int): Boolean = when {
        EmojiPanelState.isPopupVariant(target) -> {
            val variant = EmojiPanelState.popupVariantIndexOf(target)
            val sequence = popupVariants.getOrElse(variant) { popupBase }
            state.closePopup()
            invalidate()
            invalidateAccessibilityRootIfExploring()
            if (sequence.isNotEmpty()) listener?.onEmojiPanelPick(sequence)
            true
        }
        EmojiPanelState.isCell(target) -> {
            listener?.onEmojiPanelPick(state.entryAt(target))
            true
        }
        EmojiPanelState.isBack(target) -> {
            listener?.onEmojiPanelBackToKeyboard()
            true
        }
        EmojiPanelState.isDelete(target) -> {
            listener?.onEmojiPanelDelete()
            true
        }
        EmojiPanelState.isSearch(target) -> {
            listener?.onEmojiPanelSearch()
            true
        }
        EmojiPanelState.isTab(target) -> {
            if (state.setActiveCategory(EmojiPanelState.tabIndexOf(target))) {
                invalidate()
                invalidateAccessibilityRootIfExploring()
            }
            true
        }
        else -> false
    }

    /** Scrolls one grid viewport for a root ACTION_SCROLL_FORWARD/BACKWARD; true when it moved. */
    private fun scrollOneViewport(forward: Boolean): Boolean {
        val viewport = state.gridViewportHeight()
        if (viewport <= 0 || state.maxScrollY() <= 0) {
            return false
        }
        val moved = state.scrollBy(if (forward) viewport else -viewport)
        if (moved) {
            invalidate()
            invalidateAccessibilityRootIfExploring()
        }
        return moved
    }

    /**
     * Localized title of a category, used both as the visible section header and as the spoken tab
     * name; the raw slug is the fail-safe fallback.
     */
    private fun categoryTitle(categoryName: String): CharSequence {
        val resId = when (categoryName) {
            EmojiDisplaySnapshots.RECENT_CATEGORY_NAME -> R.string.spoken_emoji_category_recent
            "smileys-emotion" -> R.string.spoken_emoji_category_smileys
            "people-body" -> R.string.spoken_emoji_category_people
            "animals-nature" -> R.string.spoken_emoji_category_animals
            "food-drink" -> R.string.spoken_emoji_category_food
            "travel-places" -> R.string.spoken_emoji_category_travel
            "activities" -> R.string.spoken_emoji_category_activities
            "objects" -> R.string.spoken_emoji_category_objects
            "symbols" -> R.string.spoken_emoji_category_symbols
            "flags" -> R.string.spoken_emoji_category_flags
            else -> return categoryName
        }
        return context.getString(resId)
    }

    /**
     * The panel's [ExploreByTouchHelper], modelled on `SuggestionStripView`'s delegate. Its virtual
     * views are ONLY the visible cells, the category tabs, the search pill and the two functional
     * keys — the exact set [EmojiPanelState.virtualNodeCount] counts — enumerated from the same
     * hit-tests and geometry the touch path uses, with no second geometry of its own. A cell's
     * contentDescription is the emoji sequence itself: the panel deliberately ships no emoji-name
     * database for speech, so how a screen reader voices the sequence is left to the system. Tabs,
     * the search pill and the functional keys get localized descriptions. A node click runs the
     * same action as a finger tap through [activateForAccessibility], and the root node exposes
     * ACTION_SCROLL_FORWARD/BACKWARD.
     */
    private inner class EmojiPanelAccessibilityHelper :
        ExploreByTouchHelper(this@EmojiPanelView) {
        private val tempBounds = Rect()

        override fun getVirtualViewAt(x: Float, y: Float): Int =
            targetToVirtualId(state.targetAt(x, y))

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            if (state.isPopupOpen()) {
                // The popup owns the whole surface while it is up — a touch outside it dismisses
                // rather than reaching the grid — so the node tree is exactly its variants. Leaving
                // the grid exposed here would let a screen reader activate a cell the finger
                // cannot.
                var variant = 0
                val variants = state.popupVariantCount()
                while (variant < variants) {
                    virtualViewIds.add(POPUP_ID_BASE + variant)
                    variant++
                }
                return
            }
            // Visible cells: the same sections and rows the content draws, so the node set matches
            // what is on screen and never exposes a scrolled-off cell.
            val columns = state.columnCount()
            if (columns > 0) {
                val lastSection = state.lastVisibleSection()
                var section = state.firstVisibleSection()
                while (section in 0..lastSection) {
                    val firstRow = state.firstVisibleRowOf(section)
                    val lastRow = state.lastVisibleRowOf(section)
                    if (firstRow >= 0 && lastRow >= firstRow) {
                        val start = state.sectionStartIndex(section)
                        val count = state.sectionEntryCount(section)
                        var local = firstRow * columns
                        val end = ((lastRow + 1) * columns).coerceAtMost(count)
                        while (local < end) {
                            virtualViewIds.add(start + local)
                            local++
                        }
                    }
                    section++
                }
            }
            // Category tabs, then the search pill and the two functional keys.
            var tab = 0
            val tabs = state.tabCount()
            while (tab < tabs) {
                virtualViewIds.add(TAB_ID_BASE + tab)
                tab++
            }
            virtualViewIds.add(SEARCH_ID)
            virtualViewIds.add(BACK_ID)
            virtualViewIds.add(DELETE_ID)
        }

        override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
            node.className = View::class.java.name
            if (state.maxScrollY() > 0) {
                node.isScrollable = true
                node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
                node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)
            }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            node.className = android.widget.Button::class.java.name
            when {
                virtualViewId >= POPUP_ID_BASE -> {
                    val variant = virtualViewId - POPUP_ID_BASE
                    // The variant speaks its own sequence, exactly as a grid cell does: the panel
                    // ships no emoji-name database for speech.
                    node.contentDescription = popupVariants.getOrElse(variant) { popupBase }
                    tempBounds.set(
                        state.popupVariantLeft(variant),
                        state.popupTop(),
                        state.popupVariantRight(variant),
                        state.popupBottom(),
                    )
                }
                virtualViewId == BACK_ID -> {
                    node.contentDescription =
                        context.getString(R.string.spoken_description_to_alpha)
                    tempBounds.set(
                        state.backLeft(),
                        state.floatingTop(),
                        state.backRight(),
                        state.floatingBottom(),
                    )
                }
                virtualViewId == DELETE_ID -> {
                    node.contentDescription = context.getString(R.string.spoken_description_delete)
                    tempBounds.set(
                        state.deleteLeft(),
                        state.floatingTop(),
                        state.deleteRight(),
                        state.floatingBottom(),
                    )
                }
                virtualViewId == SEARCH_ID -> {
                    node.contentDescription = searchHint
                    tempBounds.set(
                        state.searchLeft(),
                        state.searchBarTop(),
                        state.searchRight(),
                        state.searchBarTop() + state.searchBarHeight(),
                    )
                }
                virtualViewId >= TAB_ID_BASE -> {
                    val tab = virtualViewId - TAB_ID_BASE
                    node.contentDescription = categoryTitle(tabNames.getOrElse(tab) { "" })
                    tempBounds.set(state.tabLeft(tab), 0, state.tabRight(tab), state.tabBarHeight())
                }
                virtualViewId in 0 until state.entryCount() -> {
                    // The cell's spoken description is the sequence itself; no name database ships.
                    node.contentDescription = state.entryAt(virtualViewId)
                    boundsOfCell(virtualViewId, tempBounds)
                }
                else -> {
                    node.contentDescription = ""
                    tempBounds.set(0, 0, 1, 1)
                    node.setBoundsInParent(tempBounds)
                    return
                }
            }
            node.setBoundsInParent(tempBounds)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.isClickable = true
            node.isEnabled = true
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) {
                return false
            }
            if (!activateForAccessibility(virtualIdToTarget(virtualViewId))) {
                return false
            }
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }

        private fun targetToVirtualId(target: Int): Int = when {
            EmojiPanelState.isPopupVariant(target) ->
                POPUP_ID_BASE + EmojiPanelState.popupVariantIndexOf(target)
            EmojiPanelState.isCell(target) -> target
            EmojiPanelState.isBack(target) -> BACK_ID
            EmojiPanelState.isDelete(target) -> DELETE_ID
            EmojiPanelState.isSearch(target) -> SEARCH_ID
            EmojiPanelState.isTab(target) -> TAB_ID_BASE + EmojiPanelState.tabIndexOf(target)
            else -> INVALID_ID
        }

        private fun virtualIdToTarget(virtualViewId: Int): Int = when {
            virtualViewId >= POPUP_ID_BASE ->
                EmojiPanelState.POPUP_TARGET_BASE - (virtualViewId - POPUP_ID_BASE)
            virtualViewId == BACK_ID -> EmojiPanelState.BACK_TARGET
            virtualViewId == DELETE_ID -> EmojiPanelState.DELETE_TARGET
            virtualViewId == SEARCH_ID -> EmojiPanelState.SEARCH_TARGET
            virtualViewId >= TAB_ID_BASE ->
                EmojiPanelState.TAB_TARGET_BASE - (virtualViewId - TAB_ID_BASE)
            virtualViewId in 0 until state.entryCount() -> virtualViewId
            else -> EmojiPanelState.NO_TARGET
        }

        private fun boundsOfCell(index: Int, out: Rect) {
            val columns = state.columnCount().coerceAtLeast(1)
            val section = state.sectionOfIndex(index)
            val local = index - state.sectionStartIndex(section)
            val column = local % columns
            val row = local / columns
            val cellHeight = state.cellHeight()
            // Same origin the content is drawn from, so a TalkBack node stays over its cell.
            val top = state.gridTop() + state.sectionGridTop(section) + row * cellHeight - state.scrollY()
            out.set(
                state.columnLeft(column),
                top,
                state.columnRight(column),
                top + cellHeight,
            )
        }
    }

    /**
     * Root scroll actions from a screen reader. ExploreByTouchHelper routes host-node actions back
     * through this view, so handling ACTION_SCROLL_FORWARD/BACKWARD here scrolls the content by one
     * viewport through the same [EmojiPanelState] the touch path uses.
     */
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD ->
                if (scrollOneViewport(forward = true)) return true
            AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD ->
                if (scrollOneViewport(forward = false)) return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
