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

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.util.Size
import android.util.TypedValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import rkr.simplekeyboard.inputmethod.latin.InputView

/**
 * The API-30 half of inline autofill: request construction and response hosting. Every autofill
 * framework type lives behind this object, and callers reach it only under an API-level check, so
 * on older releases none of those classes ever load. The content itself never enters our code:
 * the platform hands us views and we host them.
 */
@TargetApi(Build.VERSION_CODES.R)
object InlineAutofillBinder {

    /**
     * Builds the request the platform asks for when the focused field supports inline autofill:
     * one presentation spec per strip cell, each pinned to the cell's exact size. Returns null
     * when no honest size exists yet (an unmeasured strip on a zero-width display).
     */
    @JvmStatic
    fun createRequest(
        context: Context,
        stripWidthPx: Int,
        displayWidthPx: Int,
    ): InlineSuggestionsRequest? {
        val cells = InlineStripSpecs.cellSizesPx(
            InlineStripSpecs.effectiveStripWidthPx(stripWidthPx, displayWidthPx),
            stripHeightPx(context),
        )
        if (cells.isEmpty()) return null
        val specs = cells.map { cell ->
            InlinePresentationSpec.Builder(
                Size(cell.widthPx, cell.heightPx),
                Size(cell.widthPx, cell.heightPx),
            ).build()
        }
        return InlineSuggestionsRequest.Builder(specs)
            .setMaxSuggestionCount(InlineStripSpecs.CELL_COUNT)
            .build()
    }

    /**
     * True when the response carries no suggestions: the platform's clear signal for the previous
     * field's session. The caller clears the surface on it, before any field gate.
     */
    @JvmStatic
    fun isEmpty(response: InlineSuggestionsResponse): Boolean =
        response.inlineSuggestions.isNullOrEmpty()

    /**
     * Hosts the response: up to one content view per strip cell, each inflated at its cell's
     * promised size and added as it arrives. Returns false without touching the surface when
     * there is nothing to show or the surface is not ours right now (the emoji panel is up);
     * refused content is simply not rendered inline.
     */
    @JvmStatic
    fun hostResponse(
        context: Context,
        inputView: InputView,
        response: InlineSuggestionsResponse,
    ): Boolean {
        val suggestions = response.inlineSuggestions ?: return false
        if (suggestions.isEmpty()) return false
        // The sizes come from the word strip's width (0 before its first layout, in which case the
        // display width stands in): the host itself is inflated by the show below and has no honest
        // width to read yet.
        val cells = InlineStripSpecs.cellSizesPx(
            InlineStripSpecs.effectiveStripWidthPx(
                inputView.stripWidthPx,
                context.resources.displayMetrics.widthPixels,
            ),
            stripHeightPx(context),
        )
        if (cells.isEmpty()) return false
        val host = inputView.showInlineAutofillStrip() ?: return false
        val session = host.beginSession()
        val count = minOf(suggestions.size, InlineStripSpecs.CELL_COUNT)
        var index = 0
        while (index < count) {
            val cell = cells[index]
            suggestions[index].inflate(
                context,
                Size(cell.widthPx, cell.heightPx),
                context.mainExecutor,
            ) { view ->
                if (view != null) host.hostCell(session, view)
            }
            index++
        }
        return true
    }

    private fun stripHeightPx(context: Context): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        SuggestionStripState.STRIP_HEIGHT_DP.toFloat(),
        context.resources.displayMetrics,
    ).toInt()
}
