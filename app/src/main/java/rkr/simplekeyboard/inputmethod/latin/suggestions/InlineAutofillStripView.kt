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
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup

/**
 * The strip surface while an inline-autofill session owns it: up to three equal cells, each
 * holding one content view the system inflates. A plain [ViewGroup] — the API-30 autofill types
 * never appear here, so this class loads on any API level; it is inflated only from the gated
 * binder path. The word strip's rendering is untouched: while this host is visible,
 * [SuggestionStripView] is GONE, and the host's children draw themselves.
 */
class InlineAutofillStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    /**
     * Id of the live session, [NO_SESSION] when idle. An inflate callback that arrives after the
     * session it belongs to ended (the field switched meanwhile) is refused by [hostCell].
     */
    private var sessionId = NO_SESSION

    private val stripHeightPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        SuggestionStripState.STRIP_HEIGHT_DP.toFloat(),
        resources.displayMetrics,
    ).toInt()

    /** Starts a fresh session: stale content goes, and the returned id owns the coming inflates. */
    fun beginSession(): Int {
        removeAllViews()
        sessionId++
        return sessionId
    }

    /** Ends the session and drops every hosted view; the word strip becomes the surface again. */
    fun endSession() {
        sessionId = NO_SESSION
        removeAllViews()
    }

    /**
     * Adds [view] as the next cell of the session [forSession]. Refused when the session has
     * moved on or the cells are full — a refused view is simply never attached.
     */
    fun hostCell(forSession: Int, view: View): Boolean {
        if (forSession == NO_SESSION || forSession != sessionId
            || childCount >= InlineStripSpecs.CELL_COUNT
        ) {
            return false
        }
        addView(view)
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val measuredHeight = resolveSize(stripHeightPx, heightMeasureSpec)
        var cell = 0
        while (cell < childCount) {
            val child = getChildAt(cell)
            if (child.visibility != GONE) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(cellWidth(cell, measuredWidth), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
                )
            }
            cell++
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        var cell = 0
        while (cell < childCount) {
            val child = getChildAt(cell)
            if (child.visibility != GONE) {
                val cellLeft = width * cell / InlineStripSpecs.CELL_COUNT
                child.layout(cellLeft, 0, cellLeft + cellWidth(cell, width), height)
            }
            cell++
        }
    }

    private fun cellWidth(cell: Int, widthPx: Int): Int =
        widthPx * (cell + 1) / InlineStripSpecs.CELL_COUNT - widthPx * cell / InlineStripSpecs.CELL_COUNT

    private companion object {
        const val NO_SESSION = 0
    }
}
