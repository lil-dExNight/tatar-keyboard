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

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionStripDockSourceContractTest {
    @Test
    fun keyboardDockRectangleReachesBothStripSurfaces() {
        val keyboard = java("rkr/simplekeyboard/inputmethod/keyboard/Keyboard.java")
        assertTrue(keyboard.contains("mDockContentMinX = params.mHitboxMinX"))
        assertTrue(keyboard.contains("mDockContentMaxX = params.mHitboxMaxX"))

        val switcher = java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java")
        assertTrue(switcher.contains("mCurrentInputView.setDockContent(newKeyboard)"))

        val inputView = java("rkr/simplekeyboard/inputmethod/latin/InputView.java")
        assertTrue(inputView.contains("mSuggestionStripView.setDockContentBounds"))
        assertTrue(inputView.contains("mInlineAutofillStripView.setDockContentBounds"))
        assertTrue(inputView.contains("strip.setDockContentBounds(mStripContentLeftPx"))
        val widthBody = inputView.substringAfter("public int getStripWidthPx()")
            .substringBefore("private int dockContentWidth")
        assertTrue(widthBody.contains("dockContentWidth(mSuggestionStripView.getWidth())"))
        assertTrue(widthBody.contains("dockContentWidth(mInlineAutofillStripView.getWidth())"))
    }

    @Test
    fun wordStripDrawingTouchEllipsizingAndAccessibilityShareStateGeometry() {
        val view = java(
            "rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionStripView.kt"
        )
        val draw = view.substringAfter("override fun onDraw")
            .substringBefore("@Suppress(\"ClickableViewAccessibility\")")
        assertTrue(draw.contains("state.cellBoundary(separator, width)"))
        assertTrue(draw.contains("state.cellLeft(cell, width)"))
        assertTrue(draw.contains("state.cellRight(cell, width)"))

        val touch = view.substringAfter("override fun onTouchEvent")
            .substringBefore("override fun dispatchHoverEvent")
        assertTrue(touch.contains("state.onDown("))
        assertTrue(touch.contains("state.onMove("))
        assertTrue(touch.contains("state.onUp("))

        val ellipsize = view.substringAfter("private fun rebuildDisplaySuggestions")
            .substringBefore("private fun clearDisplaySuggestions")
        assertTrue(ellipsize.contains("state.cellRight(cell, width)"))
        assertTrue(ellipsize.contains("state.cellLeft(cell, width)"))

        val accessibility = view.substringAfter("private inner class SuggestionAccessibilityHelper")
        assertTrue(accessibility.contains("state.cellAt(x, y, width, height)"))
        assertTrue(accessibility.contains("state.cellLeft(virtualViewId, width)"))
        assertTrue(accessibility.contains("state.cellRight(virtualViewId, width)"))
    }

    @Test
    fun autofillUsesDockBoundsWithoutMirroringPlatformOrder() {
        val host = java(
            "rkr/simplekeyboard/inputmethod/latin/suggestions/InlineAutofillStripView.kt"
        )
        assertTrue(host.contains("fun setDockContentBounds(leftPx: Int, rightPx: Int)"))
        assertTrue(host.contains("InlineStripSpecs.cellLeftPx(cell, contentLeft, contentRight)"))
        assertTrue(host.contains("InlineStripSpecs.cellRightPx(cell, contentLeft, contentRight)"))
        assertFalse(host.contains("SIDE_RIGHT"))
        assertFalse(host.contains("mirrored"))
    }

    @Test
    fun singlePurposePublicationsOptOutOfRankedMirroring() {
        val controller = java(
            "rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt"
        )
        assertTrue(controller.contains("showBand(listOf(offer), ranked = false)"))
        assertTrue(controller.contains("showBand(listOf(emoji), ranked = false)"))
        assertTrue(
            controller.contains(
                "showBand(listOf(revertCellDecorator.decorate(replacement.typedForm)), ranked = false)"
            )
        )
        assertTrue(controller.contains("KeyboardSwitcher.getInstance().keyboard?.mId?.mOneHandedSide"))
    }

    private fun java(path: String): String = File(sourceRoot(), "java/$path").readText()

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }
}
