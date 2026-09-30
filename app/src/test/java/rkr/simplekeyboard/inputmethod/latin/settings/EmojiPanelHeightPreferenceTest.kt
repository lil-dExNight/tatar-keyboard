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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Emoji panel height" Appearance row is three
 * named presets (Same as keyboard / Larger / Max) stored as the single `pref_emoji_panel_height`
 * float — a scale of the keyboard box. Mirrors [KeyboardHeightPreferenceTest]: the preset mapping
 * and the scale/cap rule are pure and tested directly; everything Android-specific (the
 * restriction pipeline, the settings row, the consumption at the panel's show path) is pinned at
 * the source level, because this project's JVM suite runs without Robolectric on purpose.
 */
class EmojiPanelHeightPreferenceTest {

    // --- The presets themselves (pure JVM) ----------------------------------------------------

    @Test
    fun threePresetsInAscendingOrder() {
        assertEquals(listOf(1.0f, 1.2f, 100f), EmojiPanelHeightPresets.SCALES)
    }

    @Test
    fun defaultPresetIsTodaysBehavior() {
        // The factory state must be exactly the same-box invariant the panel always had. Moving
        // this is a behavior change, not a tweak.
        assertEquals(0, EmojiPanelHeightPresets.DEFAULT_INDEX)
        assertEquals(
            EmojiPanelHeightPresets.SAME_SCALE,
            EmojiPanelHeightPresets.SCALES[EmojiPanelHeightPresets.DEFAULT_INDEX],
        )
        assertEquals(1.0f, EmojiPanelHeightPresets.SAME_SCALE)
    }

    @Test
    fun indexForScaleMatchesEveryPresetExactly() {
        assertEquals(0, EmojiPanelHeightPresets.indexForScale(EmojiPanelHeightPresets.SAME_SCALE))
        assertEquals(1, EmojiPanelHeightPresets.indexForScale(EmojiPanelHeightPresets.LARGER_SCALE))
        assertEquals(2, EmojiPanelHeightPresets.indexForScale(EmojiPanelHeightPresets.MAX_SCALE))
    }

    @Test
    fun indexForScaleAbsorbsRestrictionArithmetics() {
        // A managed restriction writes percent/100f; float noise around a preset still resolves to
        // it rather than to the percent fallback.
        assertEquals(1, EmojiPanelHeightPresets.indexForScale(1.2f + 0.0005f))
        assertEquals(0, EmojiPanelHeightPresets.indexForScale(1.0f - 0.0005f))
    }

    @Test
    fun indexForScaleLeavesPercentValuesUnowned() {
        // A stored value between presets belongs to no preset: it keeps applying verbatim and the
        // row shows it as a percent instead of relabeling it.
        assertEquals(-1, EmojiPanelHeightPresets.indexForScale(1.30f))
        assertEquals(-1, EmojiPanelHeightPresets.indexForScale(0.90f))
        assertEquals(-1, EmojiPanelHeightPresets.indexForScale(50f))
    }

    // --- applyTo: scale after the strip bonus, cap at the screen ceiling ------------------------

    @Test
    fun sameKeepsTheBoxExactlyAndIsNeverCapped() {
        // The same-box invariant: not even the ceiling is consulted under the default — the panel
        // (keyboard + strip bonus) may legitimately sit above 46%p of the screen and always did.
        assertEquals(686, EmojiPanelHeightPresets.applyTo(686, 600, EmojiPanelHeightPresets.SAME_SCALE))
        assertEquals(686, EmojiPanelHeightPresets.applyTo(686, 1048, 1.0f))
    }

    @Test
    fun largerScalesTheBoxByOneFifth() {
        assertEquals(823, EmojiPanelHeightPresets.applyTo(686, 1048, EmojiPanelHeightPresets.LARGER_SCALE))
    }

    @Test
    fun largerIsCappedAtTheCeiling() {
        // 1.2 × 900 = 1080 > 1048 (46%p of a 2280px screen): the cap, not the scale, wins.
        assertEquals(1048, EmojiPanelHeightPresets.applyTo(900, 1048, EmojiPanelHeightPresets.LARGER_SCALE))
    }

    @Test
    fun maxIsTheCeilingItselfWhateverTheKeyboardBoxWas() {
        assertEquals(1048, EmojiPanelHeightPresets.applyTo(411, 1048, EmojiPanelHeightPresets.MAX_SCALE))
        assertEquals(1048, EmojiPanelHeightPresets.applyTo(686, 1048, EmojiPanelHeightPresets.MAX_SCALE))
    }

    @Test
    fun anUnknownCeilingDisablesTheCap() {
        // A degenerate (non-positive) cap must never shrink the panel to nothing: scale only.
        assertEquals(1200, EmojiPanelHeightPresets.applyTo(1000, 0, 1.2f))
    }

    // --- The wiring (source contracts) ----------------------------------------------------------

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun java(path: String) = File(sourceRoot(), "java/$path").readText()

    private fun res(path: String) = File(sourceRoot(), "res/$path").readText()

    private val settings by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/settings/Settings.java")
    }
    private val settingsValues by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/settings/SettingsValues.java")
    }
    private val host by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
    }

    @Test
    fun storageKeyAndReaderMirrorTheKeyboardHeightPref() {
        assertTrue(settings.contains("PREF_EMOJI_PANEL_HEIGHT = \"pref_emoji_panel_height\""))
        assertTrue(settings.contains("public static float readEmojiPanelHeight(final SharedPreferences prefs,"))
        assertTrue(settings.contains("prefs.getFloat(PREF_EMOJI_PANEL_HEIGHT, defaultValue)"))
    }

    @Test
    fun settingsValuesReadsTheScaleWithThePinnedDefault() {
        assertTrue(settingsValues.contains("public final float mEmojiPanelHeightScale"))
        assertTrue(settingsValues.contains(
            "mEmojiPanelHeightScale = Settings.readEmojiPanelHeight(prefs, EmojiPanelHeightPresets.SAME_SCALE)"))
    }

    @Test
    fun theShowPathAppliesTheScaleAfterTheStripBonusAndCapsAtTheScreenCeiling() {
        // InputView owns the strip bonus; the scale/cap applies to the final value, in the view
        // that measures the panel.
        val inputView = java("rkr/simplekeyboard/inputmethod/latin/InputView.java")
        val body = inputView.substringAfter("public EmojiPanelView showEmojiPanel(")
            .substringBefore("public void hideEmojiPanel(")
        assertTrue(body.contains("panelHeightPx += stripHeight"))
        assertTrue(body.contains("EmojiPanelHeightPresets.applyTo("))
        // The switcher hands over the pref and the 46%p ceiling; the cap comes from the very
        // fraction that caps the keyboard itself.
        val switcher = java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java")
        assertTrue(switcher.contains("settingsValues.mEmojiPanelHeightScale"))
        assertTrue(switcher.contains("ResourceUtils.getMaxKeyboardHeight("))
        val resourceUtils = java("rkr/simplekeyboard/inputmethod/latin/utils/ResourceUtils.java")
        val cap = resourceUtils.substringAfter("public static int getMaxKeyboardHeight(")
        assertTrue(cap.contains("R.fraction.config_max_keyboard_height"))
    }

    @Test
    fun appearanceRowIsThePresetChoice() {
        assertTrue(host.contains("emojiPanelHeightRow()"))
        // One-tap picker (setItems), not a radio dialog — the house contract of
        // EmojiRecentAndFlingSourceContractTest forbids an unnamed OK button anywhere in this file.
        assertTrue(host.contains("showEmojiPanelHeightDialog"))
        assertTrue(host.contains("prefs.edit().putFloat(Settings.PREF_EMOJI_PANEL_HEIGHT,"))
        assertTrue(host.contains("EmojiPanelHeightPresets.SCALES[which]"))
        // The restricted-row behavior is the one every other row has.
        assertTrue(host.contains("isRestricted(Settings.PREF_EMOJI_PANEL_HEIGHT)"))
    }

    @Test
    fun theFourLabelsExistInAllThreeLocales() {
        for (key in listOf("emoji_panel_height", "emoji_panel_height_same",
                "emoji_panel_height_larger", "emoji_panel_height_max")) {
            for (dir in listOf("values", "values-ru", "values-tt")) {
                assertTrue("$dir misses $key",
                    res("$dir/strings.xml").contains("name=\"$key\""))
            }
        }
    }

    @Test
    fun theManagedRestrictionGovernsTheSameKey() {
        // An administrator's integer percent loads into the same float pref through the keyboard
        // height's branch (100 = same, 120 = larger, 10000 = max; other percents apply verbatim).
        assertTrue(res("xml/app_restrictions.xml").contains(
            "android:key=\"pref_emoji_panel_height\""))
        assertTrue(settings.contains("case PREF_EMOJI_PANEL_HEIGHT:"))
    }
}
