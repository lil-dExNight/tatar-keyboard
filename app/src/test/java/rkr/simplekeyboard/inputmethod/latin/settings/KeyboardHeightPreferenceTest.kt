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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U6 of Phase 5 (docs/ROADMAP-P5.md): the "Keyboard height" row is three named presets
 * (Compact / Default / Tall) stored as the very same `pref_keyboard_height` float the
 * inherited seek bar wrote.
 *
 * The preset mapping is pure and tested directly; everything Android-flavoured (the pipeline
 * that turns the float into a taller keyboard and the live-apply seam) is pinned at the
 * source level, in the style of EmojiKeySurfaceContractTest — this project's JVM suite runs
 * without Robolectric on purpose.
 */
class KeyboardHeightPreferenceTest {

    // --- The presets themselves (pure JVM) ----------------------------------------------------

    @Test
    fun threePresetsInAscendingOrder() {
        assertEquals(listOf(0.85f, 1.0f, 1.15f), KeyboardHeightPresets.SCALES)
    }

    @Test
    fun defaultPresetIsTodaysBehavior() {
        // The factory state must be exactly the scale the pipeline already used when the
        // preference is absent (SettingsValues.DEFAULT_SIZE_SCALE = 1.0f, multiplied onto
        // config_default_keyboard_height). Moving this is a behavior change, not a tweak.
        assertEquals(1, KeyboardHeightPresets.DEFAULT_INDEX)
        assertEquals(
            SettingsValues.DEFAULT_SIZE_SCALE,
            KeyboardHeightPresets.SCALES[KeyboardHeightPresets.DEFAULT_INDEX],
        )
        assertEquals(1.0f, KeyboardHeightPresets.DEFAULT_SCALE)
    }

    @Test
    fun presetsStayInsideTheFormerSeekBarRange() {
        // The seek bar allowed 50–150 %; the presets deliberately sit well inside it, so no
        // choice can produce a geometry the shipped layout code has never rendered.
        for (scale in KeyboardHeightPresets.SCALES) {
            assertTrue("$scale below the old 50% floor", scale >= 0.5f)
            assertTrue("$scale above the old 150% ceiling", scale <= 1.5f)
        }
    }

    @Test
    fun indexForScaleMatchesEveryPresetExactly() {
        assertEquals(0, KeyboardHeightPresets.indexForScale(KeyboardHeightPresets.COMPACT_SCALE))
        assertEquals(1, KeyboardHeightPresets.indexForScale(KeyboardHeightPresets.DEFAULT_SCALE))
        assertEquals(2, KeyboardHeightPresets.indexForScale(KeyboardHeightPresets.TALL_SCALE))
    }

    @Test
    fun indexForScaleAbsorbsSeekBarEraArithmetics() {
        // The seek bar wrote value/100f (e.g. 115/100f); float noise around a preset still
        // resolves to it rather than to the percent fallback.
        assertEquals(2, KeyboardHeightPresets.indexForScale(1.15f + 0.0005f))
        assertEquals(0, KeyboardHeightPresets.indexForScale(0.85f - 0.0005f))
    }

    @Test
    fun indexForScaleLeavesSeekBarEraValuesUnowned() {
        // A stored value between presets (the seek bar's 130 %) belongs to no preset: it keeps
        // applying verbatim and the row shows it as a percent instead of relabeling it.
        assertEquals(-1, KeyboardHeightPresets.indexForScale(1.30f))
        assertEquals(-1, KeyboardHeightPresets.indexForScale(0.90f))
        assertEquals(-1, KeyboardHeightPresets.indexForScale(1.5f))
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
    fun storageKeyIsUnchangedFromTheSeekBarEra() {
        // Same key, same float: values written by the old slider (and by the integer
        // restriction, which divides an admin's percent by 100) keep meaning the same thing.
        assertTrue(settings.contains("PREF_KEYBOARD_HEIGHT = \"pref_keyboard_height\""))
        assertTrue(settings.contains("public static float readKeyboardHeight(final SharedPreferences prefs,"))
        assertTrue(settings.contains("prefs.getFloat(PREF_KEYBOARD_HEIGHT, defaultValue)"))
    }

    @Test
    fun settingsValuesReadsTheScaleWithThePinnedDefault() {
        assertTrue(settingsValues.contains("DEFAULT_SIZE_SCALE = 1.0f"))
        assertTrue(settingsValues.contains(
            "mKeyboardHeightScale = Settings.readKeyboardHeight(prefs, DEFAULT_SIZE_SCALE)"))
    }

    @Test
    fun heightPipelineMultipliesTheScaleOntoTheDefaultHeight() {
        // Layout selection honours the preference at exactly this multiplication: the base
        // height comes from the resources, the preference scales it, and KeyboardBuilder then
        // derives every row height, key hit box and the bonus height from the result, so the
        // KeyDetector grid and the popup previews follow on their own.
        val resourceUtils = java("rkr/simplekeyboard/inputmethod/latin/utils/ResourceUtils.java")
        val body = resourceUtils.substringAfter("public static int getKeyboardHeight(")
            .substringBefore("public static int getDefaultKeyboardHeight(")
        assertTrue(body.contains("settingsValues.mKeyboardHeightScale"))
        assertTrue(body.contains("defaultKeyboardHeight * scale"))

        val switcher = java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java")
        assertTrue(switcher.contains("ResourceUtils.getKeyboardHeight(res, settingsValues)"))
        assertTrue(switcher.contains(
            "builder.setKeyboardGeometry(keyboardWidth, keyboardHeight, keyboardBottomOffset)"))
    }

    @Test
    fun liveApplySeamRebuildsSettingsValuesAndCachesByHeight() {
        // 1. Any change to the pref rebuilds SettingsValues immediately (the Settings listener).
        val listenerBody = settings.substringAfter(
            "public void onSharedPreferenceChanged(final SharedPreferences prefs, final String key)")
            .substringBefore("@Override public void onReceive")
        assertTrue(listenerBody.contains("loadSettings(mSettingsValues.mInputAttributes)"))
        // 2. The next keyboard load re-reads the scale and lands it in KeyboardId.mHeight.
        val layoutSet = java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardLayoutSet.java")
        assertTrue(layoutSet.contains("public Builder setKeyboardGeometry(final int keyboardWidth,"
            + " final int keyboardHeight,"))
        // 3. mHeight participates in equals()/hashCode(): a changed height can never hit a
        // stale sKeyboardCache entry, which is why no cache clear is needed for this pref
        // (unlike mShowEmojiKey, which KeyboardId deliberately ignores).
        val keyboardId = java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardId.java")
        val hashBody = keyboardId.substringAfter("private static int computeHashCode(")
            .substringBefore("private boolean equalsId(")
        val equalsBody = keyboardId.substringAfter("private boolean equalsId(final KeyboardId other)")
            .substringBefore("private static boolean isAlphabetKeyboard(")
        assertTrue(hashBody.contains("id.mHeight"))
        assertTrue(equalsBody.contains("other.mHeight == mHeight"))
    }

    @Test
    fun appearanceRowIsThePresetChoiceAndTheSeekBarIsGone() {
        assertTrue(host.contains("keyboardHeightRow()"))
        // One-tap picker (setItems), not a radio dialog: the house contract of
        // EmojiRecentAndFlingSourceContractTest forbids an unnamed OK button anywhere in
        // this file, and the choice is reversible, so it applies on the tap itself.
        assertTrue(host.contains("setItems(labels)"))
        assertTrue(host.contains("prefs.edit().putFloat(Settings.PREF_KEYBOARD_HEIGHT,"))
        assertTrue(host.contains("KeyboardHeightPresets.SCALES[which]"))
        // The restricted-row behavior is the one every other row has.
        assertTrue(host.contains("isRestricted(Settings.PREF_KEYBOARD_HEIGHT)"))
        // The seek-bar wiring it replaces must be gone, resources included.
        assertFalse(host.contains("keyboardHeightProxy"))
        assertFalse(host.contains("config_min_keyboar_height"))
        assertFalse(host.contains("config_max_keyboar_height"))
        assertFalse(host.contains("config_keyboar_height_step"))
        assertFalse(res("values/config-common.xml").contains("keyboar_height"))
    }

    @Test
    fun theThreeLabelsExistInAllThreeLocales() {
        for (key in listOf("keyboard_height_compact", "keyboard_height_default",
                "keyboard_height_tall")) {
            for (dir in listOf("values", "values-ru", "values-tt")) {
                assertTrue("$dir misses $key",
                    res("$dir/strings.xml").contains("name=\"$key\""))
            }
        }
    }

    @Test
    fun theManagedRestrictionStillGovernsTheSameKey() {
        // An administrator's integer percent keeps loading into the same float pref
        // (a forced 130 simply matches no preset and shows as "130%").
        assertTrue(res("xml/app_restrictions.xml").contains(
            "android:key=\"pref_keyboard_height\""))
        assertTrue(settings.contains("case PREF_KEYBOARD_HEIGHT:"))
    }
}
