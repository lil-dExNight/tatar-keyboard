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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Popup movement ticks only when a newly highlighted cell is non-null. */
class MoreKeysHapticSourceContractTest {
    private val panel = source("keyboard/MoreKeysKeyboardView.java")
    private val feedback = source("latin/AudioAndHapticFeedbackManager.java")

    @Test
    fun onlyMoveOntoANewCellRequestsATick() {
        val move = panel.substringAfter("public void onMoveEvent(final int x")
            .substringBefore("public void onUpEvent(final int x")
        assertTrue(move.contains("final Key oldKey = mCurrentKey;"))
        assertTrue(move.contains("mCurrentKey = detectKey(x, y);"))
        assertTrue(move.contains("if (mCurrentKey != null && mCurrentKey != oldKey)"))
        assertEquals(1, Regex("performTickFeedback\\(\\)").findAll(move).count())

        val down = panel.substringAfter("public void onDownEvent(final int x")
            .substringBefore("public void onMoveEvent(final int x")
        val up = panel.substringAfter("public void onUpEvent(final int x")
            .substringBefore("protected void onKeyInput(final Key key)")
        assertFalse(down.contains("performTickFeedback"))
        assertFalse(up.contains("performTickFeedback"))
    }

    @Test
    fun theExistingSettingAndThrottleGateTheTick() {
        val tick = feedback.substringAfter("public void performTickFeedback()")
            .substringBefore("public void onSettingsChanged")
        assertTrue(tick.contains("!mSettingsValues.mVibrateOn"))
        assertTrue(tick.contains("System.currentTimeMillis() - mLastTickTime < TICK_FREQUENCY"))
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
