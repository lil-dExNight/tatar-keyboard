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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
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
import rkr.simplekeyboard.inputmethod.R

/** One allocation-free hot-path Canvas view containing exactly three suggestion cells. */
class SuggestionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs, R.attr.mainKeyboardViewStyle) {
    fun interface OnSuggestionClickListener {
        fun onSuggestionClick(cellId: Int, suggestion: String)
    }

    /**
     * A long press on a filled cell (E4d). The word shown there is passed as it is displayed;
     * whether it belongs to the personal dictionary — and therefore whether anything happens at all
     * — is decided on the other side, never here.
     */
    fun interface OnSuggestionLongPressListener {
        fun onSuggestionLongPress(cellId: Int, suggestion: String)
    }

    private val state = SuggestionStripState()
    /**
     * Optional spoken label per cell, set right after [setSuggestions] by the owner of the band
     * for cells whose text does not read well aloud (an emoji). A null entry means "speak the
     * cell's text". Reset by every [setSuggestions]/[clearSuggestions], so a label can never
     * outlive the content it described.
     */
    private val spokenLabels = arrayOfNulls<String>(SuggestionStripState.CELL_COUNT)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            TEXT_SIZE_DP,
            resources.displayMetrics,
        )
    }
    private val decorationPaint = Paint()
    private val fontMetrics = Paint.FontMetrics()
    private val displaySuggestions = arrayOfNulls<String>(SuggestionStripState.CELL_COUNT)
    private val stripHeightPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        SuggestionStripState.STRIP_HEIGHT_DP.toFloat(),
        resources.displayMetrics,
    ).toInt()
    private val horizontalTextPaddingPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        HORIZONTAL_TEXT_PADDING_DP,
        resources.displayMetrics,
    )
    private val accessibilityHelper = SuggestionAccessibilityHelper()
    private val accessibilityManager = context
        .getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private var textBaseline = 0f
    private var pressedColor = DEFAULT_PRESSED_COLOR
    private var separatorColor = DEFAULT_SEPARATOR_COLOR
    private var listener: OnSuggestionClickListener? = null
    private var longPressListener: OnSuggestionLongPressListener? = null
    private var touchSequenceAccepted = false
    /** True once the timer has fired for this touch sequence: the tap on release is cancelled. */
    private var longPressFired = false
    /** Allocated once, so neither onTouchEvent nor onDraw ever creates an object. */
    private val longPressRunnable = Runnable { fireLongPress() }

    init {
        val stripAttributes = context.obtainStyledAttributes(
            attrs,
            R.styleable.SuggestionStripView,
            R.attr.mainKeyboardViewStyle,
            R.style.KeyboardView,
        )
        textPaint.color = stripAttributes.getColor(
            R.styleable.SuggestionStripView_keyTextColor,
            Color.BLACK,
        )
        pressedColor = stripAttributes.getColor(
            R.styleable.SuggestionStripView_keyPressedBackgroundColor,
            DEFAULT_PRESSED_COLOR,
        )
        separatorColor = withAlpha(
            stripAttributes.getColor(
                R.styleable.SuggestionStripView_functionalTextColor,
                textPaint.color,
            ),
            SEPARATOR_ALPHA,
        )
        stripAttributes.recycle()

        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    fun setOnSuggestionClickListener(listener: OnSuggestionClickListener?) {
        this.listener = listener
    }

    fun setOnSuggestionLongPressListener(listener: OnSuggestionLongPressListener?) {
        this.longPressListener = listener
    }

    /**
     * The timer body: the cell is read here, off the touch path, and the tap of this sequence is
     * cancelled — a long press NEVER commits text.
     */
    private fun fireLongPress() {
        val cell = state.pressedCell()
        if (cell == SuggestionStripState.NO_CELL) return
        val suggestion = state.suggestionAt(cell) ?: return
        longPressFired = true
        longPressListener?.onSuggestionLongPress(cell, suggestion)
    }

    private fun scheduleLongPress() {
        removeCallbacks(longPressRunnable)
        postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelLongPress(resetFired: Boolean = true) {
        removeCallbacks(longPressRunnable)
        if (resetFired) longPressFired = false
    }

    fun setSuggestions(first: String?, second: String?, third: String?) {
        clearSpokenLabels()
        val hadSuggestions = state.hasAnySuggestion()
        if (!state.setSuggestions(first, second, third)) return
        rebuildDisplaySuggestions()
        accessibilityHelper.invalidateRoot()
        invalidate()
        // Announce ONLY the empty band -> words transition, and only while touch exploration is
        // actually on. The triple changes on every keystroke; announcing each one would bury the
        // key echo TalkBack users type by, exactly like the shift-mode announcements deliberately
        // stay silent on the frequent auto-caps transitions (KeyboardAccessibilityDelegate). The
        // words themselves stay reachable at any time through the virtual cell nodes.
        if (hadSuggestions || !accessibilityManager.isTouchExplorationEnabled) return
        val available = listOfNotNull(
            state.suggestionAt(0),
            state.suggestionAt(1),
            state.suggestionAt(2),
        )
        if (available.isNotEmpty()) {
            announceForAccessibility(
                context.getString(
                    R.string.spoken_suggestions_available,
                    available.joinToString(", "),
                ),
            )
        }
    }

    fun clearSuggestions() {
        clearSpokenLabels()
        if (!state.clear()) return
        clearDisplaySuggestions()
        accessibilityHelper.invalidateRoot()
        invalidate()
    }

    /**
     * Sets the spoken labels of the three cells; see the field comment. Called by the owner of the
     * band immediately after [setSuggestions]; never creates content of its own.
     */
    fun setSpokenLabels(first: String?, second: String?, third: String?) {
        if (spokenLabels[0] == first && spokenLabels[1] == second && spokenLabels[2] == third) {
            return
        }
        spokenLabels[0] = first
        spokenLabels[1] = second
        spokenLabels[2] = third
        accessibilityHelper.invalidateRoot()
    }

    private fun clearSpokenLabels() {
        spokenLabels[0] = null
        spokenLabels[1] = null
        spokenLabels[2] = null
    }

    /** Drops every reference and transient state that must not survive view replacement. */
    fun release() {
        clearSpokenLabels()
        val changed = state.clear()
        touchSequenceAccepted = false
        listener = null
        visibility = GONE
        clearDisplaySuggestions()
        accessibilityHelper.invalidateRoot()
        if (changed) invalidate()
    }

    fun getSuggestion(cellId: Int): String? = state.suggestionAt(cellId)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val measuredHeight = resolveSize(stripHeightPx, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        textPaint.getFontMetrics(fontMetrics)
        textBaseline = height / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f
        rebuildDisplaySuggestions()
        accessibilityHelper.invalidateRoot()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pressedCell = state.pressedCell()
        if (pressedCell != SuggestionStripState.NO_CELL) {
            decorationPaint.color = pressedColor
            canvas.drawRect(
                state.cellLeft(pressedCell, width).toFloat(),
                0f,
                state.cellRight(pressedCell, width).toFloat(),
                height.toFloat(),
                decorationPaint,
            )
        }

        decorationPaint.color = separatorColor
        var separator = 1
        while (separator < SuggestionStripState.CELL_COUNT) {
            val x = state.cellLeft(separator, width).toFloat()
            canvas.drawLine(x, 0f, x, height.toFloat(), decorationPaint)
            separator++
        }

        var cell = 0
        while (cell < SuggestionStripState.CELL_COUNT) {
            val suggestion = displaySuggestions[cell]
            if (suggestion != null) {
                val center = (state.cellLeft(cell, width) + state.cellRight(cell, width)) / 2f
                canvas.drawText(suggestion, center, textBaseline, textPaint)
            }
            cell++
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                state.cancelGesture()
                touchSequenceAccepted = false
                val pointerIndex = event.actionIndex
                val handled = state.onDown(
                    event.getPointerId(pointerIndex),
                    event.getX(pointerIndex),
                    event.getY(pointerIndex),
                    width,
                    height,
                )
                touchSequenceAccepted = handled
                longPressFired = false
                if (handled) {
                    invalidate()
                    scheduleLongPress()
                }
                return handled
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchSequenceAccepted) return false
                val oldPressed = state.pressedCell()
                val pointerIndex = event.findPointerIndex(state.activePointerId())
                if (pointerIndex < 0) {
                    state.cancelGesture()
                } else {
                    state.onMove(
                        event.getPointerId(pointerIndex),
                        event.getX(pointerIndex),
                        event.getY(pointerIndex),
                        width,
                        height,
                    )
                }
                if (oldPressed != state.pressedCell()) {
                    invalidate()
                    // The finger left the cell it started on: that is no longer a long press.
                    cancelLongPress()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return touchSequenceAccepted
            MotionEvent.ACTION_POINTER_UP -> {
                if (!touchSequenceAccepted) return false
                val oldPressed = state.pressedCell()
                state.onPointerUp(event.getPointerId(event.actionIndex))
                if (oldPressed != state.pressedCell()) invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!touchSequenceAccepted) return false
                val pointerIndex = event.actionIndex
                val cell = state.onUp(
                    event.getPointerId(pointerIndex),
                    event.getX(pointerIndex),
                    event.getY(pointerIndex),
                    width,
                    height,
                )
                state.cancelGesture()
                touchSequenceAccepted = false
                invalidate()
                val wasLongPress = longPressFired
                cancelLongPress()
                if (!wasLongPress && cell != SuggestionStripState.NO_CELL) activateCell(cell)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val handled = touchSequenceAccepted || state.hasActiveGesture()
                val changed = state.cancelGesture()
                touchSequenceAccepted = false
                cancelLongPress()
                if (changed) invalidate()
                return handled
            }
        }
        return false
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun activateCell(cellId: Int): Boolean {
        val suggestion = state.suggestionAt(cellId) ?: return false
        listener?.onSuggestionClick(cellId, suggestion)
        return true
    }

    private fun rebuildDisplaySuggestions() {
        if (width <= 0) {
            clearDisplaySuggestions()
            return
        }
        var cell = 0
        while (cell < SuggestionStripState.CELL_COUNT) {
            val suggestion = state.suggestionAt(cell)
            displaySuggestions[cell] = if (suggestion == null) {
                null
            } else {
                val availableWidth = (
                    state.cellRight(cell, width) - state.cellLeft(cell, width)
                ).toFloat() - horizontalTextPaddingPx * 2f
                TextUtils.ellipsize(
                    suggestion,
                    textPaint,
                    availableWidth.coerceAtLeast(0f),
                    TextUtils.TruncateAt.END,
                ).toString()
            }
            cell++
        }
    }

    private fun clearDisplaySuggestions() {
        var cell = 0
        while (cell < SuggestionStripState.CELL_COUNT) {
            displaySuggestions[cell] = null
            cell++
        }
    }

    private inner class SuggestionAccessibilityHelper : ExploreByTouchHelper(this@SuggestionStripView) {
        private val tempBounds = Rect()

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val cell = state.cellAt(x, y, width, height)
            return if (state.isCellPopulated(cell)) cell else INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            var cell = 0
            while (cell < SuggestionStripState.CELL_COUNT) {
                if (state.isCellPopulated(cell)) virtualViewIds.add(cell)
                cell++
            }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            val suggestion = state.suggestionAt(virtualViewId)
            if (suggestion == null) {
                node.contentDescription = ""
                tempBounds.set(0, 0, 1, 1)
                node.setBoundsInParent(tempBounds)
                return
            }
            node.className = android.widget.Button::class.java.name
            // An emoji cell speaks its name ("самолёт"), a word cell its own text.
            node.contentDescription = spokenLabels[virtualViewId] ?: suggestion
            tempBounds.set(
                state.cellLeft(virtualViewId, width),
                0,
                state.cellRight(virtualViewId, width),
                height,
            )
            node.setBoundsInParent(tempBounds)
            val actionable = isVirtualCellActionable(virtualViewId)
            if (actionable) {
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                // Exposed on EVERY filled cell, not only on personal words. An action present only
                // on personal ones would make the contents of a private list observable to any
                // enabled accessibility service and to automated tree dumps — one could see which
                // of the three words came from it. For a dictionary word the action is a no-op,
                // which is what the contract requires of a long press there anyway.
                node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
                node.isLongClickable = true
            }
            node.isClickable = actionable
            node.isEnabled = actionable
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            if (!isVirtualCellActionable(virtualViewId)) return false
            if (action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK) {
                val suggestion = state.suggestionAt(virtualViewId) ?: return false
                longPressListener?.onSuggestionLongPress(virtualViewId, suggestion)
                return true
            }
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            if (!activateCell(virtualViewId)) return false
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }

        private fun isVirtualCellActionable(virtualViewId: Int): Boolean =
            ViewCompat.isAttachedToWindow(this@SuggestionStripView)
                && isShown
                && isEnabled
                && state.isCellPopulated(virtualViewId)
    }

    companion object {
        const val VIRTUAL_ID_LEFT = 0
        const val VIRTUAL_ID_CENTER = 1
        const val VIRTUAL_ID_RIGHT = 2
        // Р-3: размеры текста клавиатурных поверхностей считаются в dp, а НЕ в sp.
        // Каждый из этих текстов живёт в полосе фиксированной dp-высоты (полоса подсказок
        // 40dp, вкладки 44dp, строка поиска 50dp, заголовок секции 30dp), а системный
        // масштаб шрифта растит только текст. При font_scale 2.0 полоса подсказок
        // вырождалась в «Мини… · Минем · Мини…» — две ячейки из трёх неразличимы ровно для
        // тех, кому крупный шрифт и нужен (docs/DEVICE-RESEARCH-GEOMETRY.md, Р-3).
        // Клавиши раскладки всегда считались в dp; здесь то же правило.
        private const val TEXT_SIZE_DP = 17f
        private const val HORIZONTAL_TEXT_PADDING_DP = 8f
        private const val SEPARATOR_ALPHA = 0x30
        private const val DEFAULT_PRESSED_COLOR = 0x22000000
        private const val DEFAULT_SEPARATOR_COLOR = 0x30000000

        private fun withAlpha(color: Int, alpha: Int): Int =
            color and 0x00ffffff or (alpha shl 24)
    }
}
