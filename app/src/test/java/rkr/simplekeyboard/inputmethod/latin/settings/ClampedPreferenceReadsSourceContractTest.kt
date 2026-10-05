/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry and volume readers of `Settings.java` clamp the stored value to the range the
 * settings screens can produce and treat a non-finite float as absent: a value from outside the
 * range (a hand-edited file could carry one) must never build an unusable keyboard, e.g. a NaN or
 * zero height that draws nothing. `SharedPreferences` does not exist on a plain JVM, so the wiring
 * is pinned at the source level, like the other contract tests in this package.
 */
class ClampedPreferenceReadsSourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private val settings by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/settings/Settings.java")
            .readText()
    }

    private fun readerBody(from: String, to: String): String =
        settings.substringAfter(from).substringBefore(to)

    @Test
    fun theFloatClampFallsBackOnNonFiniteValues() {
        val body = readerBody(
            "private static float clampStoredFloat(", "private static int clampStoredInt(")
        assertTrue("a NaN must read as the default", body.contains("Float.isNaN(value)"))
        assertTrue("an infinity must read as the default", body.contains("Float.isInfinite(value)"))
        assertTrue(body.contains("Math.min(max, Math.max(min, value))"))
    }

    @Test
    fun keyboardHeightReadsClampedToTheFormerSeekBarRange() {
        val body = readerBody(
            "public static float readKeyboardHeight(", "public static float readEmojiPanelHeight(")
        assertTrue(body.contains("readFloatTolerant(prefs, PREF_KEYBOARD_HEIGHT, defaultValue)"))
        assertTrue(body.contains("clampStoredFloat("))
        assertTrue(body.contains("MIN_KEYBOARD_HEIGHT_SCALE"))
        assertTrue(body.contains("MAX_KEYBOARD_HEIGHT_SCALE"))
        assertTrue(settings.contains("MIN_KEYBOARD_HEIGHT_SCALE = 0.5f"))
        assertTrue(settings.contains("MAX_KEYBOARD_HEIGHT_SCALE = 1.5f"))
    }

    @Test
    fun emojiPanelHeightReadsClampedToThePresetRange() {
        val body = readerBody(
            "public static float readEmojiPanelHeight(", "public static int readBottomOffsetPortrait(")
        assertTrue(body.contains("readFloatTolerant(prefs, PREF_EMOJI_PANEL_HEIGHT, defaultValue)"))
        assertTrue(body.contains("clampStoredFloat("))
        assertTrue(body.contains("EmojiPanelHeightPresets.SAME_SCALE"))
        assertTrue(body.contains("EmojiPanelHeightPresets.MAX_SCALE"))
    }

    @Test
    fun keypressSoundVolumeReadsClampedToThePercentRange() {
        val body = readerBody(
            "public static float readKeypressSoundVolume(", "private static final float DEFAULT_KEYPRESS_SOUND_VOLUME")
        assertTrue(body.contains("clampStoredFloat(volume, 0.0f, 1.0f"))
    }

    @Test
    fun bottomOffsetReadsClampedToTheConfiguredSliderRange() {
        val body = readerBody(
            "public static int readBottomOffsetPortrait(", "public static final int DEFAULT_BOTTOM_OFFSET")
        assertTrue(body.contains("clampStoredInt("))
        // The clamp bounds mirror the slider's configured range; the two must not drift apart.
        val config = File(sourceRoot(), "res/values/config-common.xml").readText()
        assertEquals(configInt(config, "config_min_bottom_offset_portrait"),
            sourceInt(settings, "MIN_BOTTOM_OFFSET"))
        assertEquals(configInt(config, "config_max_bottom_offset_portrait"),
            sourceInt(settings, "MAX_BOTTOM_OFFSET"))
    }

    private fun configInt(config: String, name: String): Int =
        Regex("<integer name=\"$name\">(-?\\d+)</integer>").find(config)
            ?.groupValues?.get(1)?.toInt() ?: error("$name missing from config-common.xml")

    private fun sourceInt(source: String, name: String): Int =
        Regex("$name = (-?\\d+)").find(source)
            ?.groupValues?.get(1)?.toInt() ?: error("$name missing from Settings.java")
}
