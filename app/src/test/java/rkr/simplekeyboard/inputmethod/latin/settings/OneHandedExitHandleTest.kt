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

package rkr.simplekeyboard.inputmethod.latin.settings

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-handed exit handle: the strip the dock frees answers a tap (return to full width) and
 * a long-press (the side picker), and nothing else — a tap there can never mean a key, because
 * key hitboxes are clamped to the grid's span and the handle is exactly its complement.
 *
 * The geometry is pure and tested directly; the registration on the built keyboard is pinned at
 * the source level, like the rest of the dock wiring in [OneHandedModeTest].
 */
class OneHandedExitHandleTest {

    private val left = OneHandedMode.SIDE_LEFT
    private val right = OneHandedMode.SIDE_RIGHT
    private val off = OneHandedMode.SIDE_OFF

    // --- The handle is exactly the freed strip --------------------------------------------------

    @Test
    fun theHandleSpansTheWholeFreedStrip() {
        // The 720 px reference width: the grid takes 612 px and the strip the other 108.
        assertArrayEquals(intArrayOf(612, 720), OneHandedMode.exitHandleSpanPx(720, left))
        assertArrayEquals(intArrayOf(0, 108), OneHandedMode.exitHandleSpanPx(720, right))
    }

    @Test
    fun theTwoHandsMirrorEachOther() {
        val leftSpan = OneHandedMode.exitHandleSpanPx(1000, left)
        val rightSpan = OneHandedMode.exitHandleSpanPx(1000, right)
        assertEquals(1000 - leftSpan[0], rightSpan[1])
        assertEquals(1000 - leftSpan[1], rightSpan[0])
    }

    @Test
    fun noStripNoHandle() {
        assertArrayEquals(intArrayOf(0, 0), OneHandedMode.exitHandleSpanPx(720, off))
        assertArrayEquals(intArrayOf(0, 0), OneHandedMode.exitHandleSpanPx(720, 7))
        // The dock degenerates on a screen too narrow for the fifth-row floor: no strip either.
        assertArrayEquals(intArrayOf(0, 0), OneHandedMode.exitHandleSpanPx(300, left))
        assertArrayEquals(intArrayOf(0, 0), OneHandedMode.exitHandleSpanPx(300, right))
    }

    // --- The no-overlap invariant ----------------------------------------------------------------

    /**
     * For every width and side the handle is exactly what the key hitboxes cannot cover: the
     * builder clamps hitboxes to [dockPaddings[0], width - dockPaddings[1]) (pinned in
     * [OneHandedModeTest]), and pointwise the handle must hold exactly outside that span.
     */
    @Test
    fun theHandleIsExactlyTheHitboxSpansComplement() {
        for (side in listOf(off, left, right, 7)) {
            var width = 1
            while (width <= 2000) {
                val span = OneHandedMode.exitHandleSpanPx(width, side)
                val hitboxMin = OneHandedMode.dockPaddingsPx(width, side)[0]
                val hitboxMax = width - OneHandedMode.dockPaddingsPx(width, side)[1]
                var x = 0
                while (x < width) {
                    val inHandle = x >= span[0] && x < span[1]
                    val inHitboxSpan = x >= hitboxMin && x < hitboxMax
                    assertTrue(
                        "width $width side $side: x=$x is in both the handle and the hitbox span",
                        !(inHandle && inHitboxSpan),
                    )
                    // Full coverage while docked: no dead spot left that answers nothing.
                    if (span[1] > span[0]) {
                        assertTrue(
                            "width $width side $side: x=$x is in neither the handle nor the grid",
                            inHandle || inHitboxSpan,
                        )
                    }
                    x++
                }
                width += 37
            }
        }
    }

    // --- contains() ------------------------------------------------------------------------------

    @Test
    fun containsFollowsTheHalfOpenSpanAndTheOccupiedHeight() {
        // Left dock at 720 px: the strip is [612, 720).
        assertFalse(OneHandedMode.exitHandleContains(612, 720, 611, 100, 500))
        assertTrue(OneHandedMode.exitHandleContains(612, 720, 612, 100, 500))
        assertTrue(OneHandedMode.exitHandleContains(612, 720, 719, 100, 500))
        assertFalse(OneHandedMode.exitHandleContains(612, 720, 720, 100, 500))
        // The full occupied height answers.
        assertTrue(OneHandedMode.exitHandleContains(612, 720, 650, 0, 500))
        assertTrue(OneHandedMode.exitHandleContains(612, 720, 650, 499, 500))
        assertFalse(OneHandedMode.exitHandleContains(612, 720, 650, 500, 500))
        assertFalse(OneHandedMode.exitHandleContains(612, 720, 650, -1, 500))
    }

    @Test
    fun anEmptySpanContainsNothing() {
        assertFalse(OneHandedMode.exitHandleContains(0, 0, 0, 100, 500))
        assertFalse(OneHandedMode.exitHandleContains(0, 0, 650, 100, 500))
        assertFalse(OneHandedMode.exitHandleContains(20, 10, 15, 100, 500))
        assertFalse(OneHandedMode.exitHandleContains(0, 100, 50, 0, 0))
        assertFalse(OneHandedMode.exitHandleContains(0, 100, 50, 0, -1))
    }

    // --- The registration (source contracts) -----------------------------------------------------

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun source(path: String) = File(sourceRoot(), path).readText()

    @Test
    fun theBuilderRegistersTheHandleSpanOnTheParams() {
        val builder = source("java/rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardBuilder.java")
        val attrs = builder.substringAfter("private void parseKeyboardAttributes(")
            .substringBefore("private void parseKeyboardContent(")
        assertTrue(attrs.contains("OneHandedMode.exitHandleSpanPx(width, oneHandedSide)"))
        assertTrue(attrs.contains("params.mDockExitHandleMinX = exitHandleSpan[0];"))
        assertTrue(attrs.contains("params.mDockExitHandleMaxX = exitHandleSpan[1];"))
        val params = source("java/rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardParams.java")
        assertTrue(params.contains("public int mDockExitHandleMinX"))
        assertTrue(params.contains("public int mDockExitHandleMaxX"))
    }

    @Test
    fun accessibilityHandleHasAStableIdWithoutRenumberingKeys() {
        val delegate = source("java/rkr/simplekeyboard/inputmethod/accessibility/KeyboardAccessibilityDelegate.kt")
        assertTrue(delegate.contains("const val DOCK_EXIT_HANDLE_ID = Int.MAX_VALUE"))
        val visible = delegate.substringAfter("override fun getVisibleVirtualViews(")
            .substringBefore("override fun onPopulateNodeForVirtualView(")
        assertTrue(visible.contains("virtualViewIds.add(i)"))
        assertTrue(visible.contains("if (dockExitHandleKeyboard() != null)"))
        assertTrue(visible.contains("virtualViewIds.add(DOCK_EXIT_HANDLE_ID)"))
        assertTrue(visible.indexOf("virtualViewIds.add(i)") < visible.indexOf("virtualViewIds.add(DOCK_EXIT_HANDLE_ID)"))

        val hit = delegate.substringAfter("override fun getVirtualViewAt(")
            .substringBefore("override fun getVisibleVirtualViews(")
        assertTrue(hit.contains("if (dockExitHandleContains(x, y)) return DOCK_EXIT_HANDLE_ID"))
        assertTrue(hit.contains("val index = sortedKeys().indexOf(key)"))
        assertTrue(hit.contains("return if (index >= 0) index else INVALID_ID"))
    }

    @Test
    fun accessibilityHandleBoundsAndActionsMatchTheDockTouchPath() {
        val delegate = source("java/rkr/simplekeyboard/inputmethod/accessibility/KeyboardAccessibilityDelegate.kt")
        val populate = delegate.substringAfter("override fun onPopulateNodeForVirtualView(")
            .substringBefore("private fun performDockExitHandleAction(")
        val handle = populate.substringAfter("if (virtualViewId == DOCK_EXIT_HANDLE_ID)")
            .substringBefore("val keys = sortedKeys()")
        assertTrue(handle.contains("R.string.spoken_description_dock_exit_handle"))
        assertTrue(handle.contains("keyboard.mDockExitHandleMinX + keyboardView.paddingLeft"))
        assertTrue(handle.contains("keyboard.mDockExitHandleMaxX + keyboardView.paddingLeft"))
        assertTrue(handle.contains("keyboardView.paddingTop"))
        assertTrue(handle.contains("keyboardView.paddingTop + keyboard.mOccupiedHeight"))
        assertTrue(handle.contains("AccessibilityNodeInfo.ACTION_CLICK"))
        assertTrue(handle.contains("AccessibilityNodeInfo.ACTION_LONG_CLICK"))
        assertTrue(handle.contains("node.isClickable = true"))
        assertTrue(handle.contains("node.isLongClickable = true"))

        val perform = delegate.substringAfter("private fun performDockExitHandleAction(")
            .substringBefore("override fun onPerformActionForVirtualView(")
        assertTrue(perform.contains("MotionEvent.ACTION_DOWN"))
        assertTrue(perform.contains("MotionEvent.ACTION_UP"))
        assertTrue(perform.contains("MotionEvent.ACTION_CANCEL"))
        assertTrue(perform.contains("keyboardView.processMotionEvent(down)"))
        assertTrue(perform.contains("keyboardView.processMotionEvent(end)"))
        assertTrue(perform.contains("PointerTracker.getPointerTracker(0).onLongPressed()"))

        val tracker = source("java/rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java")
        assertTrue(tracker.contains("sListener.onDockExitHandleTap();"))
        assertTrue(tracker.contains("sListener.onDockExitHandleLongPress();"))
    }

    @Test
    fun accessibilityHandleRejectsEmptyAndDegenerateStates() {
        val delegate = source("java/rkr/simplekeyboard/inputmethod/accessibility/KeyboardAccessibilityDelegate.kt")
        val geometry = delegate.substringAfter("private fun dockExitHandleKeyboard()")
            .substringBefore("private fun dockExitHandleContains(")
        assertTrue(geometry.contains("it.mDockExitHandleMinX < it.mDockExitHandleMaxX"))
        assertTrue(geometry.contains("it.mOccupiedHeight > 0"))
        val perform = delegate.substringAfter("private fun performDockExitHandleAction(")
            .substringBefore("override fun onPerformActionForVirtualView(")
        assertTrue(perform.contains("dockExitHandleKeyboard() ?: return false"))
        val populate = delegate.substringAfter("if (virtualViewId == DOCK_EXIT_HANDLE_ID)")
            .substringBefore("val keys = sortedKeys()")
        assertTrue(populate.contains("if (keyboard == null)"))
        assertTrue(populate.contains("tempBounds.set(0, 0, 1, 1)"))
    }

    @Test
    fun accessibilityHandleDescriptionIsLocalized() {
        for (dir in listOf("values", "values-ru", "values-tt")) {
            val strings = source("res/$dir/strings-a11y.xml")
            assertTrue("$dir missing dock exit description",
                strings.contains("\"spoken_description_dock_exit_handle\""))
        }
    }

    /** The geometry must stay Android-free so this suite keeps running it on the plain JVM. */
    @Test
    fun theGeometryStaysAndroidFree() {
        val mode = source("java/rkr/simplekeyboard/inputmethod/latin/settings/OneHandedMode.kt")
        assertFalse(mode.contains("import android"))
    }
}
