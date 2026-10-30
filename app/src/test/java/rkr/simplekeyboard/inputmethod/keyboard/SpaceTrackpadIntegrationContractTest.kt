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

package rkr.simplekeyboard.inputmethod.keyboard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android-facing wiring that cannot be instantiated by the plain JVM state-machine tests. */
class SpaceTrackpadIntegrationContractTest {
    private val tracker = source("keyboard/PointerTracker.java")
    private val ime = source("latin/LatinIME.java")

    @Test
    fun quickSpaceSlideKeepsItsOriginalPath() {
        val move = tracker.substringAfter("private void onMoveEventInternal(")
            .substringBefore("private void onUpEvent(")
        val trackpad = move.indexOf("if (mSpaceTrackpadState.isActive())")
        val quickSlide = move.indexOf("oldKey.getCode() == Constants.CODE_SPACE")
        assertTrue(trackpad in 0 until quickSlide)
        assertTrue(move.substring(quickSlide).contains("sListener.onMoveCursorPointer(steps);"))
    }

    @Test
    fun secondPointerIsConsumedWithoutPressingItsKey() {
        val down = tracker.substringAfter("private void onSpaceTrackpadSecondaryDownEventInternal(")
            .substringBefore("private void onSpaceTrackpadSecondaryUp(")
        assertTrue(down.contains("mIsTrackingForActionDisabled = true;"))
        assertTrue(down.contains("mIsSpaceTrackpadSecondary = true;"))
        assertFalse(down.contains("callListenerOnPress"))
        assertFalse(down.contains("onCodeInput"))

        val up = tracker.substringAfter("private void onUpEvent(final int x")
            .substringBefore("public void onPhantomUpEvent")
        assertTrue(up.contains("trackpadOwner.onSpaceTrackpadSecondaryUp(mPointerId);"))
        assertTrue(up.contains("sPointerTrackerQueue.remove(this);"))
    }

    @Test
    fun upAndCancelFinishWithoutCommittingSpace() {
        val up = tracker.substringAfter("private void onUpEventInternal(")
            .substringBefore("public void cancelTrackingForAction()")
        val finish = up.indexOf("finishSpaceTrackpad(mIsTrackingForActionDisabled);")
        val send = up.indexOf("detectAndSendKey(currentKey")
        assertTrue(finish in 0 until send)
        assertTrue(up.substring(0, finish).contains("if (mSpaceTrackpadState.isActive())"))

        val cancel = tracker.substringAfter("private void onCancelEventInternal()")
            .substringBefore("private boolean isMajorEnoughMoveToBeOnNewKey")
        assertTrue(cancel.contains("finishSpaceTrackpad(true /* cancelled */);"))
    }

    @Test
    fun editorEntryRequiresKnownNonPasswordCursor() {
        val gate = ime.substringAfter("private boolean canUseSpaceTrackpad()")
            .substringBefore("public boolean onSpaceTrackpadStart()")
        assertTrue(gate.contains("mInputLogic.mConnection.hasCursorPosition()"))
        assertTrue(gate.contains("isCurrentFieldPasswordField()"))
        assertTrue(gate.contains("mSettings.getCurrent().mSpaceSwipeEnabled"))
    }

    @Test
    fun selectionUsesAbsoluteEdgesAndNeverDpadFallback() {
        val handlers = ime.substringAfter("private boolean canUseSpaceTrackpad()")
            .substringBefore("public void onMoveDeletePointer")
        assertFalse(handlers.contains("sendDownUpKeyEvent"))
        assertFalse(handlers.contains("KEYCODE_DPAD"))
        assertTrue(handlers.contains("connection.setSelection(Math.min(anchor, newActiveEdge),"))
        assertTrue(handlers.contains("Math.max(anchor, newActiveEdge));"))
        assertTrue(handlers.contains("connection.setSelection(mSpaceTrackpadInitialStart, mSpaceTrackpadInitialEnd);"))
        assertTrue(handlers.contains("getUnicodeSteps(steps, rightSidePointer)"))
    }

    private fun source(relativePath: String): String {
        val relative = "rkr/simplekeyboard/inputmethod/$relativePath"
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate $relative")
    }
}
