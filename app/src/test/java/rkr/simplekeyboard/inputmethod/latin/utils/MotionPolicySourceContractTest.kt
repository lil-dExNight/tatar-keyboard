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

package rkr.simplekeyboard.inputmethod.latin.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract (like GlideTrailContractTest: the views need a real window, so the wiring is
 * grepped, not exercised) for the WCAG 2.3.3 gate of the hand-rolled animations: when the system
 * animator scale is 0, the glide trail's post-lift fade clears instantly and the emoji panel's
 * fling and section-jump animations get a zero duration. What is pinned here: the scale is read
 * once per gesture and never per frame, the gate consults the cached [MotionPolicy], and the
 * direct feedback — the live trail under the finger and the drag itself — is never gated.
 */
class MotionPolicySourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun java(path: String) = File(sourceRoot(), "java/$path").readText()

    private val policy by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/utils/MotionPolicy.kt")
    }
    private val keyboardView by lazy {
        java("rkr/simplekeyboard/inputmethod/keyboard/MainKeyboardView.java")
    }
    private val panel by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelView.kt")
    }
    private val panelGestures by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelGestures.kt")
    }

    private fun countOccurrences(haystack: String, needle: String): Int =
        if (needle.isEmpty()) 0 else haystack.split(needle).size - 1

    private fun onTouchBody() =
        panel.substringAfter("override fun onTouchEvent").substringBefore("override fun onVisibilityChanged")

    // --- The helper reads the system animator scale once and decides from it ---------------------

    @Test
    fun thePolicyReadsTheAnimatorDurationScaleWithAnEnabledDefault() {
        assertTrue(policy.contains("Settings.Global.ANIMATOR_DURATION_SCALE"))
        assertTrue("a missing setting defaults to animations on", policy.contains("1f,"))
        assertTrue(
            "the decision is the scale alone",
            policy.contains("val animationsEnabled: Boolean = durationScale > 0f"),
        )
        assertTrue("Java callers reach the factory", policy.contains("@JvmStatic"))
    }

    // --- The glide trail fade --------------------------------------------------------------------

    @Test
    fun theTrailFadeReadsTheScaleOncePerGesture() {
        assertEquals(
            "one read site for the whole keyboard view",
            1, countOccurrences(keyboardView, "MotionPolicy.of("),
        )
        val point = keyboardView.substringAfter("public void onGlideTrailPoint(")
            .substringBefore("public void onGlideTrailEnd()")
        assertTrue("the read sits in the feed, guarded to run once per gesture",
            point.contains("if (mGlideMotionPolicy == null) {"))
        assertTrue(point.contains("mGlideMotionPolicy = MotionPolicy.of(getContext());"))
        // The live trail under the finger is feedback, not decoration: the feed and its
        // invalidate stay outside the gate.
        assertTrue(point.contains("mGlideTrail.addPoint(x, y, (float) eventTime);"))
        assertFalse("the feed itself is never gated", point.contains("getAnimationsEnabled"))
    }

    @Test
    fun aZeroScaleClearsTheTrailAtTheLiftInsteadOfFading() {
        val end = keyboardView.substringAfter("public void onGlideTrailEnd()")
            .substringBefore("protected void onAttachedToWindow()")
        val gate = end.indexOf("!motionPolicy.getAnimationsEnabled()")
        val clear = end.indexOf("mGlideTrail.clear();")
        val fade = end.indexOf("mGlideTrail.startFadeOut((float) SystemClock.uptimeMillis());")
        assertTrue("the lift consults the cached policy", gate >= 0)
        assertTrue("a zero scale clears the ring instantly", clear > gate)
        assertTrue("otherwise the fade runs as before", fade > clear)
        assertTrue("the policy is dropped at the lift", end.contains("mGlideMotionPolicy = null;"))
        val detach = keyboardView.substringAfter("protected void onDetachedFromWindow()")
            .substringBefore("mDrawingPreviewPlacerView.removeAllViews();")
        assertTrue("a closing keyboard drops it too", detach.contains("mGlideMotionPolicy = null;"))
    }

    @Test
    fun theDrawLoopNeverReadsTheScale() {
        val draw = keyboardView.substringAfter("private void drawGlideTrail(final Canvas canvas)")
            .substringBefore("private static final long FADE_FRAME_MS")
        assertFalse("no resolver read in the draw pass", draw.contains("MotionPolicy"))
        assertFalse("the draw pass stays as it was", draw.contains("getAnimationsEnabled"))
    }

    // --- The emoji panel's fling and section jump -------------------------------------------------

    @Test
    fun thePanelReadsTheScaleOncePerGestureAtActionDown() {
        assertEquals("one read site for the whole panel", 1, countOccurrences(panel, "MotionPolicy.of("))
        val down = onTouchBody().substringAfter("MotionEvent.ACTION_DOWN ->")
            .substringBefore("MotionEvent.ACTION_MOVE ->")
        assertTrue(down.contains("motionPolicy = MotionPolicy.of(context)"))
        val up = onTouchBody().substringAfter("MotionEvent.ACTION_UP ->")
            .substringBefore("MotionEvent.ACTION_CANCEL ->")
        assertTrue("ACTION_UP drops the gesture's policy", up.contains("motionPolicy = null"))
        val cancel = onTouchBody().substringAfter("MotionEvent.ACTION_CANCEL ->")
        assertTrue("ACTION_CANCEL drops it too", cancel.contains("motionPolicy = null"))
        // The drag itself (the scrolling under the finger) is not gated anywhere.
        assertFalse(onTouchBody().contains("animationsEnabled"))
    }

    @Test
    fun aZeroScaleSnapsTheFlingToItsRestPosition() {
        val fling = panel.substringAfter("private fun maybeFling(")
            .substringBefore("private fun currentColumns(")
        assertTrue(fling.contains("scroller.fling("))
        val gate = fling.indexOf("motionPolicy?.animationsEnabled == false")
        assertTrue("the fling gate consults the gesture's policy", gate >= 0)
        assertTrue("the physics computes the rest position first",
            fling.indexOf("scroller.fling(") < gate)
        assertTrue("the snap lands on the fling's rest position",
            fling.contains("state.setScrollY(EmojiFling.clampScroll(scroller.finalY, state.maxScrollY()))"))
        assertTrue("and finishes the scroller so no frame is scheduled",
            fling.substring(gate).contains("scroller.forceFinished(true)"))
    }

    @Test
    fun aZeroScaleGivesTheSectionJumpAZeroDuration() {
        assertTrue(
            "the jump duration answers the gesture's policy",
            panelGestures.contains(
                "val durationMs = if (motionPolicy?.animationsEnabled == false) 0 else EmojiPanelView.SECTION_JUMP_MS",
            ),
        )
        assertTrue(panelGestures.contains("scroller.startScroll(0, from, 0, to - from, durationMs)"))
    }
}
