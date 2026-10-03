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

package rkr.simplekeyboard.inputmethod.latin.emoji

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract (the vibrator cannot run in a JVM test, same reason as
 * GlideTouchIntegrationContractTest): the emoji skin-tone popup's long-press haptic goes through
 * the app's key-press vibration toggle, exactly like a key press — one boolean check in
 * AudioAndHapticFeedbackManager, no new setting.
 */
class EmojiLongPressHapticSourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun java(path: String) = File(sourceRoot(), "java/$path").readText()

    private val panel by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelView.kt")
    }
    private val manager by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/AudioAndHapticFeedbackManager.java")
    }
    private val settingsValues by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/settings/SettingsValues.java")
    }

    @Test
    fun theLongPressHapticRoutesThroughTheFeedbackManager() {
        val runnable = panel.substringAfter("internal val longPressRunnable = Runnable {")
            .substringBefore("private val deleteRepeatRunnable")
        assertTrue(
            "the popup's haptic goes through the manager",
            runnable.contains(
                "AudioAndHapticFeedbackManager.getInstance().performLongPressHapticFeedback(this)",
            ),
        )
        assertFalse(
            "no direct View.performHapticFeedback is left in the panel",
            panel.contains("performHapticFeedback("),
        )
    }

    @Test
    fun theManagerGatesTheLongPressOnTheSameToggleAsAKeyPress() {
        val keyPress = manager.substringAfter("public void performHapticFeedback(")
            .substringBefore("public void performLongPressHapticFeedback(")
        assertTrue("a key press checks the toggle", keyPress.contains("if (!mSettingsValues.mVibrateOn"))
        val longPress = manager.substringAfter("public void performLongPressHapticFeedback(")
            .substringBefore("public void performTickFeedback(")
        val gate = longPress.indexOf("if (!mSettingsValues.mVibrateOn")
        assertTrue("the long press checks the same toggle", gate >= 0)
        assertTrue(
            "the LONG_PRESS feel survives on every API level",
            longPress.contains("performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);"),
        )
        assertTrue("the gate precedes the haptic",
            gate < longPress.indexOf("performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);"))
        assertTrue("the toggle is the key-press vibration setting",
            settingsValues.contains("mVibrateOn = Settings.readVibrationEnabled(prefs, res);"))
    }
}
