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

package rkr.simplekeyboard.inputmethod.keyboard.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge clamping of the key-preview balloon. The placer clamps the balloon's left edge into the
 * key grid's side margins (the keyboard's side paddings), and the droplet's neck mirrors the
 * shift so it stays over the parent key. The math is pure and pinned directly; the wiring (who
 * clamps, who mirrors, when the neck path is rebuilt, and the more-keys panel's touch origin) is
 * pinned from source, since the views need a device.
 */
class KeyPreviewClampTest {

    // A 720 px wide keyboard with the stock side padding and a 90 px balloon: the tightest
    // real case is a 12-key row, whose edge keys center 30 px from the grid edge.
    private val clampLeft = 6
    private val clampRight = 714
    private val balloonWidth = 90

    private fun clamp(naturalX: Int, width: Int = balloonWidth,
            left: Int = clampLeft, right: Int = clampRight) =
        KeyPreviewChoreographer.clampPreviewX(naturalX, width, left, right)

    @Test
    fun aLeftEdgeKeyClampsTheBalloonToTheKeyGridEdge() {
        // Key center 30 px from the grid edge: the key-centered left edge would be -15.
        assertEquals(clampLeft, clamp(-15))
        // The neck moves against the shift and stays over the key.
        assertEquals(-21, KeyPreviewChoreographer.neckShift(-15, clampLeft))
    }

    @Test
    fun aRightEdgeKeyClampsTheBalloonToTheKeyGridEdge() {
        // Key center 30 px from the right grid edge: the key-centered left edge would be 645.
        assertEquals(clampRight - balloonWidth, clamp(645))
        assertEquals(21, KeyPreviewChoreographer.neckShift(645, clampRight - balloonWidth))
    }

    @Test
    fun aMiddleKeyKeepsTheKeyCenteredPlacement() {
        assertEquals(300, clamp(300))
        assertEquals(0, KeyPreviewChoreographer.neckShift(300, 300))
    }

    @Test
    fun exactBoundariesAreNotClamped() {
        assertEquals(clampLeft, clamp(clampLeft))
        assertEquals(clampRight - balloonWidth, clamp(clampRight - balloonWidth))
        assertEquals(0, KeyPreviewChoreographer.neckShift(clampLeft, clampLeft))
    }

    @Test
    fun onePixelOutsideTheBoundaryClamps() {
        assertEquals(clampLeft, clamp(clampLeft - 1))
        assertEquals(clampRight - balloonWidth, clamp(clampRight - balloonWidth + 1))
    }

    @Test
    fun aBalloonWiderThanTheBandIsCenteredInTheBand() {
        // Defensive: the balloon cannot fit, so it hangs over both edges evenly.
        assertEquals(-25, clamp(0, width = 150, left = 0, right = 100))
    }

    @Test
    fun aBalloonAsWideAsTheBandFillsTheBand() {
        assertEquals(10, clamp(30, width = 100, left = 10, right = 110))
    }

    @Test
    fun aDegenerateBandStillLandsInside() {
        assertEquals(5, clamp(0, width = 90, left = 50, right = 50))
    }

    // ----- Wiring, pinned from source -----

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun java(path: String) = File(sourceRoot(), "java/$path").readText()

    @Test
    fun thePlacerClampsAndMirrorsTheNeckOnlyOnTheDroplet() {
        val choreographer = java("rkr/simplekeyboard/inputmethod/keyboard/internal/KeyPreviewChoreographer.java")
        assertTrue(
            "the placement clamps the key-centered position into the band",
            choreographer.contains(
                "final int previewX = clampPreviewX(naturalX, previewWidth,\n" +
                    "                mParams.getBalloonClampLeft(), mParams.getBalloonClampRight());"),
        )
        assertTrue(
            "the neck mirror is droplet-only: the 9-patch backgrounds keep their baked neck",
            choreographer.contains("background instanceof KeyPreviewBalloonDrawable") &&
                choreographer.contains(".setNeckOffset(neckShift(naturalX, previewX))"),
        )
    }

    @Test
    fun theClampBandIsTheKeyGridSideEdges() {
        val view = java("rkr/simplekeyboard/inputmethod/keyboard/MainKeyboardView.java")
        val band = view.substringAfter("setBalloonClampBand(").substringBefore(");")
        assertTrue("the band starts at the keyboard's left padding", band.contains("keyboard.mLeftPadding"))
        assertTrue(
            "the band ends at the keyboard's right padding",
            band.contains("keyboard.mOccupiedWidth") && band.contains("keyboard.mRightPadding"),
        )
    }

    @Test
    fun theNeckPathIsRebuiltOnlyWhenTheOffsetChanges() {
        val drawable = java("rkr/simplekeyboard/inputmethod/keyboard/internal/KeyPreviewBalloonDrawable.java")
        val setter = drawable.substringAfter("public void setNeckOffset(").substringBefore("private void buildPath(")
        assertTrue("an unchanged offset is a no-op", setter.contains("if (mNeckOffsetPx == offsetPx)"))
        assertTrue(
            "the rebuild reuses the cached bounds and allocates nothing",
            setter.contains("buildPath(mPathWidth, mPathHeight)"),
        )
        val buildPath = drawable.substringAfter("private void buildPath(").substringBefore("public void draw(")
        assertTrue("the neck anchor follows the offset", buildPath.contains("width / 2f + mNeckOffsetPx"))
        assertTrue(
            "the path is rebuilt on the cached bounds, so onBoundsChange records them",
            drawable.substringAfter("protected void onBoundsChange(").contains("mPathWidth = bounds.width();"),
        )
    }

    @Test
    fun theMoreKeysPanelTouchOriginFollowsTheClampedPosition() {
        val panel = java("rkr/simplekeyboard/inputmethod/keyboard/MoreKeysKeyboardView.java")
        val show = panel.substringAfter("public void showMoreKeysPanel(").substringBefore("protected int getDefaultCoordX(")
        assertTrue(
            "the panel still clamps inside the parent view",
            show.contains("final int clampedX = Math.max(0, Math.min(maxX, x));"),
        )
        assertTrue(
            "slide-selection maps to the keys as drawn when the panel is pinned at the edge",
            show.contains("mOriginX = clampedX + container.getPaddingLeft();"),
        )
    }
}
