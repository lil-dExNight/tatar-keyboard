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

/**
 * Stage A of `docs/ROADMAP-P8-PLAN.md` — the Apple-UX batch-1 values, pinned so a later theme
 * edit cannot silently drift them back:
 *
 * - **W1** key shadow alpha 0.30 in light mode (`#4D000000`), matching the KeyboardKit master
 *   asset; the dark value was already 0.70 and stays.
 * - **W2** functional key light fill `#ABB1BA` (KeyboardKit master; the old `#B3B7C0` was the
 *   6.9.4 pin).
 * - **W3** the spacebar language label no longer fades: final alpha 255.
 * - **W6** `performHapticFeedback` must not carry `FLAG_IGNORE_GLOBAL_SETTING` — the
 *   system-wide haptics switch wins on API < 29 exactly as the Vibrator honours it on API 29+.
 *
 * Resources and framework classes do not exist on a plain JVM (no Robolectric here, by design),
 * so these are source-level pins, the discipline used across the settings contract tests.
 */
class AppleUxBatchOneContractTest {

    private fun projectFile(relative: String): File {
        val candidates = listOf(File("."), File("app"), File(".."))
        val root = candidates.firstOrNull { File(it, relative).isFile }
            ?: error("cannot locate $relative from ${File(".").absolutePath}")
        return File(root, relative)
    }

    private fun colorValue(text: String, name: String): String {
        val open = "<color name=\"$name\">"
        val start = text.indexOf(open)
        assertTrue("colour $name missing", start >= 0)
        return text.substring(start + open.length, text.indexOf("</color>", start))
    }

    @Test
    fun w1TheLightKeyShadowIsThirtyPercentBlack() {
        val colors = projectFile("src/main/res/values/colors.xml").readText()
        assertEquals("W1: light key shadow", "#4D000000", colorValue(colors, "ios_key_shadow"))
    }

    @Test
    fun w1TheDarkKeyShadowIsUnchanged() {
        val night = projectFile("src/main/res/values-night/colors.xml").readText()
        assertEquals("dark shadow stays 0.70", "#B3000000", colorValue(night, "ios_key_shadow"))
    }

    @Test
    fun w2TheLightFunctionalKeyMatchesTheReference() {
        val colors = projectFile("src/main/res/values/colors.xml").readText()
        assertEquals("W2: functional key", "#ABB1BA", colorValue(colors, "ios_key_functional"))
    }

    @Test
    fun w3TheSpacebarLanguageLabelStaysOpaque() {
        val config = projectFile("src/main/res/values/config-common.xml").readText()
        val open = "<integer name=\"config_language_on_spacebar_final_alpha\">"
        val start = config.indexOf(open)
        assertTrue("alpha integer missing", start >= 0)
        val value = config.substring(start + open.length, config.indexOf("</integer>", start))
        assertEquals("W3: final alpha", "255", value)
    }

    @Test
    fun w6HapticsDoNotOverrideTheSystemSetting() {
        val source = projectFile(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/AudioAndHapticFeedbackManager.java",
        ).readText()
        assertFalse(
            "W6: the global-setting override must be gone",
            source.contains("FLAG_IGNORE_GLOBAL_SETTING"),
        )
        assertTrue(
            "the KEYBOARD_TAP call stays, single-argument",
            source.contains("performHapticFeedback(\n                        HapticFeedbackConstants.KEYBOARD_TAP)"),
        )
    }

    @Test
    fun w6TheKeyboardOwnToggleStillGatesVibration() {
        val source = projectFile(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/AudioAndHapticFeedbackManager.java",
        ).readText()
        val body = source.substringAfter("public void performHapticFeedback(")
            .substringBefore("public void performTickFeedback(")
        assertTrue(
            "the app's own vibrate switch is still checked first",
            body.contains("!mSettingsValues.mVibrateOn || mVibrator == null"),
        )
    }
}
