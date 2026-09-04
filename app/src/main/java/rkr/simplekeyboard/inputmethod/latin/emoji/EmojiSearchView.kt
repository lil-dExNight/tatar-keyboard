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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.abs
import rkr.simplekeyboard.inputmethod.R

/**
 * The emoji-search surface: two bands that take the place of the suggestion strip while the search
 * is open, with the ordinary letter keyboard visible underneath them.
 *
 * Why this shape. In the Telegram client the search field summons the system keyboard; here the
 * keyboard IS this application, so the emoji grid cannot stay on screen while the user types — the
 * grid and a letter layout do not fit together. The same trade is what Gboard makes: the grid gives
 * way to the letters and the results come back as a scrolling strip. The search pill from the panel
 * survives the switch, now holding the typed query, so the transition reads as the pill being
 * focused rather than as a different screen.
 *
 * The query itself lives in the pure [EmojiSearchQuery] and never reaches the editor; matching is
 * the pure [EmojiSearchIndex]. This view owns only the Android surface: paints built once, no
 * allocations in [onDraw] or [onTouchEvent], and only the visible results drawn. Picking a result
 * goes through the listener to `LatinIME.onTextInput(String)`, exactly like a panel cell.
 */
class EmojiSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs, R.attr.mainKeyboardViewStyle) {

    /** Callbacks for the close key and for picking a result; all on the UI thread. */
    interface Listener {
        /** The "✕" key or a backspace on an empty query: leave the search, back to the panel. */
        fun onEmojiSearchClosed()

        /** A result was tapped: insert [sequence] through the ordinary text-input path. */
        fun onEmojiSearchPick(sequence: String)
    }

    private companion object {
        private const val QUERY_ROW_DP = 46f
        private const val RESULT_ROW_DP = 54f
        private const val RESULT_CELL_DP = 54f

        private const val PILL_INSET_X_DP = 8f
        private const val PILL_INSET_Y_DP = 5f
        private const val ICON_INSET_DP = 18f
        private const val TEXT_INSET_DP = 40f
        private const val CLOSE_INSET_DP = 26f
        private const val CLOSE_RADIUS_DP = 13f
        private const val CLOSE_CROSS_DP = 5f
        private const val MESSAGE_INSET_DP = 16f

        private const val SEARCH_ICON_RADIUS_DP = 6f
        private const val SEARCH_ICON_STROKE_DP = 1.6f
        private const val SEARCH_ICON_HANDLE_DP = 5f
        private const val CLOSE_STROKE_DP = 1.8f
        private const val CARET_STROKE_DP = 1.5f
        /** Air between the caret's right edge and the first letter of the hint. */
        private const val HINT_GAP_DP = 3f
        // Р-3: размеры текста клавиатурных поверхностей считаются в dp, а НЕ в sp.
        // Каждый из этих текстов живёт в полосе фиксированной dp-высоты (полоса подсказок
        // 40dp, вкладки 44dp, строка поиска 50dp, заголовок секции 30dp), а системный
        // масштаб шрифта растит только текст. При font_scale 2.0 полоса подсказок
        // вырождалась в «Мини… · Минем · Мини…» — две ячейки из трёх неразличимы ровно для
        // тех, кому крупный шрифт и нужен (docs/DEVICE-RESEARCH-GEOMETRY.md, Р-3).
        // Клавиши раскладки всегда считались в dp; здесь то же правило.

        private const val QUERY_TEXT_SIZE_DP = 16f
        private const val MESSAGE_TEXT_SIZE_DP = 14f
        private const val RESULT_TEXT_SCALE = 0.62f

        private const val PILL_ALPHA = 0xE0
        private const val HINT_ALPHA = 0xA0
        private const val PRESSED_ALPHA = 90

        private const val NO_TARGET = -1
        private const val CLOSE_TARGET = -2

        // Accessibility virtual-view ids. Result ids are the result position (0 until 60), so the
        // pill and the close key sit far above any of them.
        private const val QUERY_ID = 1_000_000
        private const val CLOSE_ID = 1_000_001
    }

    private var listener: Listener? = null
    private var index: EmojiSearchIndex = EmojiSearchIndex.EMPTY
    private var queryText: String = ""
    private var resultCount = 0
    private var scrollX = 0

    private val backgroundPaint = Paint()
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val queryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            QUERY_TEXT_SIZE_DP,
            resources.displayMetrics,
        )
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            MESSAGE_TEXT_SIZE_DP,
            resources.displayMetrics,
        )
    }
    private val resultPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val pillRect = RectF()
    private val queryFontMetrics = Paint.FontMetrics()
    private val messageFontMetrics = Paint.FontMetrics()
    private val resultFontMetrics = Paint.FontMetrics()

    private val queryRowPx = dp(QUERY_ROW_DP)
    private val resultRowPx = dp(RESULT_ROW_DP)
    private val resultCellPx = dp(RESULT_CELL_DP)
    private val pillInsetXPx = dp(PILL_INSET_X_DP).toFloat()
    private val pillInsetYPx = dp(PILL_INSET_Y_DP).toFloat()
    private val iconInsetPx = dp(ICON_INSET_DP).toFloat()
    private val textInsetPx = dp(TEXT_INSET_DP).toFloat()
    private val closeInsetPx = dp(CLOSE_INSET_DP)
    private val closeRadiusPx = dp(CLOSE_RADIUS_DP)
    private val closeCrossPx = dp(CLOSE_CROSS_DP).toFloat()
    private val messageInsetPx = dp(MESSAGE_INSET_DP).toFloat()
    private val searchIconRadiusPx = dp(SEARCH_ICON_RADIUS_DP).toFloat()
    private val searchIconHandlePx = dp(SEARCH_ICON_HANDLE_DP).toFloat()
    private val hintGapPx = dp(HINT_GAP_DP).toFloat()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val hintText: String = context.getString(R.string.emoji_search_hint)
    private val noResultsText: String = context.getString(R.string.emoji_search_no_results)
    private val closeDescription: String = context.getString(R.string.emoji_search_close)

    // Gesture bookkeeping; the results strip scrolls horizontally.
    private var downTarget = NO_TARGET
    private var pressedTarget = NO_TARGET
    private var activePointerId = -1
    private var scrolling = false
    private var downX = 0f
    private var lastMoveX = 0f

    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val accessibilityHelper = EmojiSearchAccessibilityHelper()

    init {
        val themeColors = context.theme.obtainStyledAttributes(R.styleable.EmojiSearchView)
        val keyColor = themeColors.getColor(R.styleable.EmojiSearchView_keyNormalBackgroundColor, Color.LTGRAY)
        val functionalColor = themeColors.getColor(R.styleable.EmojiSearchView_emojiPanelFunctionalKeyColor, keyColor)
        backgroundPaint.color = themeColors.getColor(R.styleable.EmojiSearchView_emojiPanelBackgroundColor, keyColor)
        queryPaint.color = themeColors.getColor(R.styleable.EmojiSearchView_functionalTextColor, Color.DKGRAY)
        pressedPaint.color = themeColors.getColor(R.styleable.EmojiSearchView_keyPressedBackgroundColor, Color.GRAY)
        resultPaint.color = themeColors.getColor(R.styleable.EmojiSearchView_keyTextColor, Color.BLACK)
        themeColors.recycle()
        pillPaint.color = withAlpha(functionalColor, PILL_ALPHA)
        messagePaint.color = withAlpha(queryPaint.color, HINT_ALPHA)
        iconPaint.color = withAlpha(queryPaint.color, HINT_ALPHA)
        iconPaint.strokeWidth = dp(SEARCH_ICON_STROKE_DP).toFloat()
        closePaint.color = queryPaint.color
        closePaint.strokeWidth = dp(CLOSE_STROKE_DP).toFloat()
        caretPaint.color = queryPaint.color
        caretPaint.strokeWidth = dp(CARET_STROKE_DP).toFloat()
        pressedPaint.alpha = PRESSED_ALPHA
        resultPaint.textSize = resultCellPx * RESULT_TEXT_SCALE
        resultPaint.getFontMetrics(resultFontMetrics)
        queryPaint.getFontMetrics(queryFontMetrics)
        messagePaint.getFontMetrics(messageFontMetrics)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Binds the index the search runs against and resets the query display. */
    fun setIndex(index: EmojiSearchIndex) {
        this.index = index
        setQuery("")
    }

    /** Re-runs the search for [query] and redraws. The query text is never sent anywhere else. */
    fun setQuery(query: String) {
        val hadResultBand = EmojiSearchLayout.showsResultBand(queryText)
        queryText = query
        resultCount = index.search(query)
        scrollX = 0
        cancelGesture()
        // The first character typed brings the result band into being and the last one deleted
        // takes it away again, and either changes the measured height.
        if (EmojiSearchLayout.showsResultBand(queryText) != hadResultBand) {
            requestLayout()
        }
        invalidate()
        invalidateAccessibilityRootIfExploring()
    }

    /** Drops the bound index, the listener and any transient state before detach or replacement. */
    fun release() {
        cancelGesture()
        listener = null
        index = EmojiSearchIndex.EMPTY
        queryText = ""
        resultCount = 0
        scrollX = 0
        visibility = GONE
        invalidateAccessibilityRootIfExploring()
    }

    /**
     * The query row always; the result band only while there is a query for it to report on. An
     * empty query therefore measures 46dp instead of 100dp and the freed height goes back to what
     * sits below in the stack, rather than holding an empty band open.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = EmojiSearchLayout.contentHeight(queryRowPx, resultRowPx, queryText)
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        canvas.drawRect(0f, 0f, w, height.toFloat(), backgroundPaint)
        drawQueryRow(canvas, w)
        drawResults(canvas, w)
    }

    private fun drawQueryRow(canvas: Canvas, w: Float) {
        val top = pillInsetYPx
        val bottom = queryRowPx - pillInsetYPx
        if (bottom <= top) return
        val radius = (bottom - top) / 2f
        pillRect.set(pillInsetXPx, top, w - pillInsetXPx, bottom)
        canvas.drawRoundRect(pillRect, radius, radius, pillPaint)

        val centerY = (top + bottom) / 2f
        val iconX = pillInsetXPx + iconInsetPx
        canvas.drawCircle(iconX, centerY - searchIconRadiusPx / 4f, searchIconRadiusPx, iconPaint)
        val diagonal = searchIconRadiusPx * 0.7071f
        canvas.drawLine(
            iconX + diagonal,
            centerY - searchIconRadiusPx / 4f + diagonal,
            iconX + diagonal + searchIconHandlePx * 0.7071f,
            centerY - searchIconRadiusPx / 4f + diagonal + searchIconHandlePx * 0.7071f,
            iconPaint,
        )

        val textLeft = pillInsetXPx + textInsetPx
        val baseline = centerY - (queryFontMetrics.ascent + queryFontMetrics.descent) / 2f
        if (!EmojiSearchLayout.hasQuery(queryText)) {
            // Spaces alone are not a query: the field reads as untouched, hint and all. The caret
            // keeps the text's own origin and the hint steps aside for it, the way an empty focused
            // EditText reads on the platform; drawing both from textLeft put the caret on the "П".
            canvas.drawText(
                hintText,
                EmojiSearchLayout.hintLeft(
                    EmojiSearchLayout.caretX(textLeft, 0f),
                    caretPaint.strokeWidth,
                    hintGapPx,
                ),
                baseline,
                messagePaint,
            )
            drawCaret(canvas, textLeft, centerY)
        } else {
            canvas.drawText(queryText, textLeft, baseline, queryPaint)
            drawCaret(
                canvas,
                EmojiSearchLayout.caretX(textLeft, queryPaint.measureText(queryText)),
                centerY,
            )
        }

        // The close key sits inside the right end of the pill.
        val closeX = w - pillInsetXPx - closeInsetPx
        if (pressedTarget == CLOSE_TARGET) {
            canvas.drawCircle(closeX, centerY, closeRadiusPx.toFloat(), pressedPaint)
        }
        canvas.drawLine(
            closeX - closeCrossPx, centerY - closeCrossPx,
            closeX + closeCrossPx, centerY + closeCrossPx, closePaint,
        )
        canvas.drawLine(
            closeX - closeCrossPx, centerY + closeCrossPx,
            closeX + closeCrossPx, centerY - closeCrossPx, closePaint,
        )
    }

    private fun drawCaret(canvas: Canvas, x: Float, centerY: Float) {
        val half = queryPaint.textSize * 0.45f
        canvas.drawLine(x, centerY - half, x, centerY + half, caretPaint)
    }

    private fun drawResults(canvas: Canvas, w: Float) {
        val top = queryRowPx.toFloat()
        val centerOffset = -(resultFontMetrics.ascent + resultFontMetrics.descent) / 2f
        val baseline = top + resultRowPx / 2f + centerOffset
        if (resultCount == 0) {
            // An empty query has no band to draw in: it is not measured, so there is nothing here.
            if (!EmojiSearchLayout.showsResultBand(queryText)) return
            val messageBaseline =
                top + resultRowPx / 2f - (messageFontMetrics.ascent + messageFontMetrics.descent) / 2f
            canvas.drawText(noResultsText, messageInsetPx, messageBaseline, messagePaint)
            return
        }
        val first = (scrollX / resultCellPx).coerceAtLeast(0)
        var position = first
        while (position < resultCount) {
            val left = position * resultCellPx - scrollX
            if (left > w) break
            if (pressedTarget == position) {
                val inset = resultCellPx * 0.08f
                pillRect.set(
                    left + inset,
                    top + inset,
                    left + resultCellPx - inset,
                    top + resultRowPx - inset,
                )
                canvas.drawRoundRect(pillRect, inset * 2f, inset * 2f, pressedPaint)
            }
            canvas.drawText(index.resultAt(position), left + resultCellPx / 2f, baseline, resultPaint)
            position++
        }
    }

    /** Widest scroll offset that still shows content; the strip never scrolls past its last cell. */
    private fun maxScrollX(): Int = (resultCount * resultCellPx - width).coerceAtLeast(0)

    private fun targetAt(x: Float, y: Float): Int {
        if (x < 0f || x >= width || y < 0f || y >= height) return NO_TARGET
        if (y < queryRowPx) {
            val closeX = width - pillInsetXPx - closeInsetPx
            return if (abs(x - closeX) <= closeRadiusPx) CLOSE_TARGET else NO_TARGET
        }
        if (resultCount == 0 || resultCellPx <= 0) return NO_TARGET
        val position = ((x + scrollX) / resultCellPx).toInt()
        return if (position in 0 until resultCount) position else NO_TARGET
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pointerIndex = event.actionIndex
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                cancelGesture()
                downTarget = targetAt(x, y)
                pressedTarget = downTarget
                activePointerId = event.getPointerId(pointerIndex)
                downX = x
                lastMoveX = x
                scrolling = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return true
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                if (!scrolling && y >= queryRowPx && abs(x - downX) > touchSlop) {
                    scrolling = true
                    pressedTarget = NO_TARGET
                }
                if (scrolling) {
                    val delta = (lastMoveX - x).toInt()
                    lastMoveX = x
                    val next = (scrollX + delta).coerceIn(0, maxScrollX())
                    if (next != scrollX) {
                        scrollX = next
                        invalidate()
                    }
                    return true
                }
                val here = targetAt(x, y)
                val next = if (here == downTarget) downTarget else NO_TARGET
                if (next != pressedTarget) {
                    pressedTarget = next
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.actionIndex
                val target = if (scrolling) {
                    NO_TARGET
                } else if (targetAt(event.getX(pointerIndex), event.getY(pointerIndex)) == downTarget) {
                    downTarget
                } else {
                    NO_TARGET
                }
                cancelGesture()
                invalidate()
                dispatchTarget(target)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelGesture()
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            cancelGesture()
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
            target == CLOSE_TARGET -> listener?.onEmojiSearchClosed()
            target >= 0 -> listener?.onEmojiSearchPick(index.resultAt(target))
        }
    }

    private fun cancelGesture() {
        downTarget = NO_TARGET
        pressedTarget = NO_TARGET
        activePointerId = -1
        scrolling = false
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    ).toInt()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun invalidateAccessibilityRootIfExploring() {
        if (accessibilityManager.isTouchExplorationEnabled) {
            accessibilityHelper.invalidateRoot()
        }
    }

    /**
     * Virtual nodes for the query pill (so a screen reader can read back what has been typed), the
     * close key and every visible result. A result speaks its Russian CLDR name rather than the
     * bare sequence, because here the name is already at hand.
     */
    private inner class EmojiSearchAccessibilityHelper :
        ExploreByTouchHelper(this@EmojiSearchView) {
        private val tempBounds = Rect()

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val target = targetAt(x, y)
            return when {
                target == CLOSE_TARGET -> CLOSE_ID
                target >= 0 -> target
                y < queryRowPx -> QUERY_ID
                else -> INVALID_ID
            }
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds.add(QUERY_ID)
            virtualViewIds.add(CLOSE_ID)
            if (resultCount == 0 || resultCellPx <= 0) return
            var position = (scrollX / resultCellPx).coerceAtLeast(0)
            while (position < resultCount && position * resultCellPx - scrollX <= width) {
                virtualViewIds.add(position)
                position++
            }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            node.className = android.widget.Button::class.java.name
            when {
                virtualViewId == QUERY_ID -> {
                    node.className = android.widget.EditText::class.java.name
                    node.contentDescription =
                        if (EmojiSearchLayout.hasQuery(queryText)) queryText else hintText
                    tempBounds.set(0, 0, width, queryRowPx)
                }
                virtualViewId == CLOSE_ID -> {
                    node.contentDescription = closeDescription
                    val closeX = (width - pillInsetXPx - closeInsetPx).toInt()
                    tempBounds.set(
                        closeX - closeRadiusPx,
                        0,
                        closeX + closeRadiusPx,
                        queryRowPx,
                    )
                }
                virtualViewId in 0 until resultCount -> {
                    node.contentDescription = index.resultNameAt(virtualViewId)
                    val left = virtualViewId * resultCellPx - scrollX
                    tempBounds.set(left, queryRowPx, left + resultCellPx, queryRowPx + resultRowPx)
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
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            when {
                virtualViewId == CLOSE_ID -> listener?.onEmojiSearchClosed()
                virtualViewId in 0 until resultCount ->
                    listener?.onEmojiSearchPick(index.resultAt(virtualViewId))
                else -> return false
            }
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }
}
