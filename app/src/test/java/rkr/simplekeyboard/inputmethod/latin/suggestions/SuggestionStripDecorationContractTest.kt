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

/**
 * W4 of stage A (`docs/ROADMAP-P8-PLAN.md`, from `docs/APPLE-UX-2026-09-25.md`): the strip's
 * decoration is iOS-shaped — separators inset vertically instead of running edge to edge, the
 * pressed cell an inset rounded rect instead of a full-bleed square one, cell text 18dp.
 *
 * `Canvas` does not exist on a plain JVM (no Robolectric here, by design), so the draw shape is
 * pinned at the source level, like the other strip contracts. The allocation-free requirement is
 * pinned too: the rectangle must be a field, never created inside `onDraw`.
 */
class SuggestionStripDecorationContractTest {

    private fun viewSource(): String {
        val relative =
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionStripView.kt"
        val root = listOf(File("."), File("app"), File("..")).firstOrNull {
            File(it, relative).isFile
        } ?: error("cannot locate $relative from ${File(".").absolutePath}")
        return File(root, relative).readText()
    }

    private fun onDrawBody(text: String) =
        text.substringAfter("override fun onDraw(canvas: Canvas)")
            .substringBefore("override fun onMeasure")

    @Test
    fun theSeparatorsAreInsetVertically() {
        val body = onDrawBody(viewSource())
        assertTrue(
            "separator top must come from the inset fraction",
            body.contains("val separatorTop = height * SEPARATOR_INSET_FRACTION"),
        )
        assertTrue(
            "and the line must be drawn between the inset bounds",
            body.contains("canvas.drawLine(x, separatorTop, x, separatorBottom, decorationPaint)"),
        )
        assertFalse(
            "no edge-to-edge separator may remain",
            body.contains("canvas.drawLine(x, 0f, x, height.toFloat()"),
        )
    }

    @Test
    fun thePressedCellIsAnInsetRoundedRect() {
        val body = onDrawBody(viewSource())
        assertTrue("rounded rect draw", body.contains("canvas.drawRoundRect("))
        assertTrue("with the cached radius", body.contains("pressedCellRadiusPx"))
        assertTrue("and the inset", body.contains("pressedCellInsetPx"))
        assertFalse("the square full-bleed rect is gone", body.contains("canvas.drawRect("))
    }

    @Test
    fun theDrawLoopAllocatesNothing() {
        val text = viewSource()
        assertTrue(
            "the rectangle is a field",
            text.contains("private val pressedCellRect = RectF()"),
        )
        assertFalse(
            "and is never constructed inside onDraw",
            onDrawBody(text).contains("RectF("),
        )
    }

    @Test
    fun theCellTextIsEighteenDp() {
        assertTrue(
            "W4: 17dp -> 18dp",
            viewSource().contains("private const val TEXT_SIZE_DP = 18f"),
        )
    }
}
