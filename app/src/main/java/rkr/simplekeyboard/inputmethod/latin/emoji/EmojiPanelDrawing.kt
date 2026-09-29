/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
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

import android.graphics.Canvas

/**
 * The painters of [EmojiPanelView] (T2 part 3, docs/ROADMAP-P6.md): the tab row (category tabs
 * plus the search cell at its right end), the floating "АБВ"/delete keys, the skin-tone popup and
 * the recents clock — every one a
 * pure read of [EmojiPanelState] geometry onto the canvas, moved verbatim out of the view.
 * (The search pill painter was retired on 2026-09-28 — docs/EMOJI-PANEL-SPACE-2026-09-28.md,
 * item A — when the pill's 50dp band collapsed into the tab row's search cell.)
 *
 * They are `internal` extension functions on the view, so `onDraw` kept its exact call text — the
 * source contracts slice `EmojiPanelView.kt` between `override fun onDraw` and the touch handler
 * and pin what that region paints. The one painter that must stay in the view is `drawContent`:
 * the "only the visible rows are drawn" tokens the contracts pin live in its loop.
 *
 * The paints, metrics and pixel sizes these functions read went `private` → `internal` on the
 * view — the mechanical minimum, since an extension in another file cannot see a private member
 * (parts 1–2 record the same move).
 */

/** The skin-tone popup, drawn over everything: a rounded card of the neutral cell plus five tones. */
internal fun EmojiPanelView.drawSkinTonePopup(canvas: Canvas) {
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

/** The top row of category tabs plus the search cell at its right end; the active tab sits under a round pill, as in the reference. */
internal fun EmojiPanelView.drawTabRow(canvas: Canvas, pressed: Int) {
    if (tabBarPx <= 0) return
    val tabs = state.tabCount()
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
    // The search cell: the magnifier that used to sit in its own 50dp band under this row. It is
    // drawn even with zero tabs (the row is then its alone), exactly as the hit-test resolves it.
    val searchLeft = state.searchCellLeft().toFloat()
    val searchRight = state.searchCellRight().toFloat()
    if (searchRight > searchLeft) {
        if (EmojiPanelState.isSearch(pressed)) {
            val pill = minOf(searchRight - searchLeft, tabBarPx.toFloat()) - 2 * tabPillInsetPx
            if (pill > 0f) {
                val centerX = (searchLeft + searchRight) / 2f
                val centerY = tabBarPx / 2f
                keyRect.set(
                    centerX - pill / 2f,
                    centerY - pill / 2f,
                    centerX + pill / 2f,
                    centerY + pill / 2f,
                )
                canvas.drawRoundRect(keyRect, pill / 2f, pill / 2f, pressedPaint)
            }
        }
        drawSearchIcon(canvas, (searchLeft + searchRight) / 2f, tabBarPx / 2f)
    }
}

/** The recents tab's clock: a ring and two hands, so the tab reads as "recent", not as an emoji. */
internal fun EmojiPanelView.drawClockIcon(canvas: Canvas, centerX: Float, centerY: Float) {
    canvas.drawCircle(centerX, centerY, clockIconRadiusPx, clockIconPaint)
    canvas.drawLine(centerX, centerY, centerX, centerY - clockIconRadiusPx * 0.55f, clockIconPaint)
    canvas.drawLine(centerX, centerY, centerX + clockIconRadiusPx * 0.45f, centerY, clockIconPaint)
}

/** The search cell's magnifier, drawn from primitives rather than shipped as a font or a bitmap. */
internal fun EmojiPanelView.drawSearchIcon(canvas: Canvas, centerX: Float, centerY: Float) {
    canvas.drawCircle(centerX, centerY - searchIconRadiusPx / 4f, searchIconRadiusPx, searchIconPaint)
    val diagonal = searchIconRadiusPx * 0.7071f
    canvas.drawLine(
        centerX + diagonal,
        centerY - searchIconRadiusPx / 4f + diagonal,
        centerX + diagonal + searchIconHandlePx * 0.7071f,
        centerY - searchIconRadiusPx / 4f + diagonal + searchIconHandlePx * 0.7071f,
        searchIconPaint,
    )
}

/** "АБВ" and delete, floating over the content in the bottom corners as in the reference. */
internal fun EmojiPanelView.drawFloatingKeys(canvas: Canvas, pressed: Int) {
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
    canvas.drawText(EmojiPanelView.BACK_LABEL, keyRect.centerX(), labelBaseline, labelPaint)

    keyRect.set(state.deleteLeft().toFloat(), top, state.deleteRight().toFloat(), bottom)
    canvas.drawRoundRect(keyRect, radius, radius, floatingHaloPaint)
    canvas.drawRoundRect(keyRect, radius, radius, functionalKeyPaint)
    if (EmojiPanelState.isDelete(pressed)) {
        canvas.drawRoundRect(keyRect, radius, radius, pressedPaint)
    }
    canvas.drawText(EmojiPanelView.DELETE_LABEL, keyRect.centerX(), labelBaseline, labelPaint)
}
