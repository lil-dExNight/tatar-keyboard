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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2b-1 source-contract, in the style of SuggestionStripSourceContractTest / EmojiSourceContractTest:
 * it greps the frozen source rather than exercising Android, guarding the exact surface-switching
 * shape and the eight toggle wiring points E2b-1 promises.
 */
class EmojiKeySurfaceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun java(path: String) = File(sourceRoot(), "java/$path").readText()

    private val keyboardSwitcher by lazy {
        java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java")
    }
    private val inputView by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/InputView.java")
    }

    // --- The four dependent surface-switching points -----------------------------------------

    @Test
    fun setMainKeyboardFrameKeepsMainViewGoneWhilePanelShown() {
        val body = keyboardSwitcher.substringAfter("private void setMainKeyboardFrame(")
            .substringBefore("public enum KeyboardSwitchState")
        assertTrue(body.contains("mEmojiPanelShown"))
        assertTrue(body.contains("View.GONE"))
        // The panel flag is ORed into the suppression so MainKeyboardView cannot become VISIBLE.
        assertTrue(
            body.indexOf("mEmojiPanelShown")
                in 0 until body.indexOf("mKeyboardView.setVisibility"),
        )
    }

    @Test
    fun switchStateNeverHiddenWhilePanelShown() {
        val body = keyboardSwitcher.substringAfter("public KeyboardSwitchState getKeyboardSwitchState()")
            .substringBefore("public void requestUpdatingShiftState")
        // The panel short-circuit returns a non-HIDDEN state before the isShown()-based check.
        assertTrue(body.contains("if (mEmojiPanelShown)"))
        assertTrue(
            body.indexOf("KeyboardSwitchState.OTHER")
                in 0 until body.indexOf("KeyboardSwitchState.HIDDEN"),
        )
    }

    @Test
    fun visibleKeyboardViewReturnsThePanelWhileShown() {
        val body = keyboardSwitcher.substringAfter("public View getVisibleKeyboardView()")
            .substringBefore("public MainKeyboardView getMainKeyboardView()")
        assertTrue(body.contains("mEmojiPanelShown"))
        assertTrue(body.contains("getEmojiPanelView()"))
    }

    @Test
    fun visibleInputBoundsUnionsThePanelWithTheSameUnionCall() {
        // Exactly three union() calls now: the strip, the emoji panel, and the emoji search bands.
        // Every surface that can be on screen joins the touchable region, so a touch on it never
        // falls through to the application behind the keyboard.
        assertEquals(
            3,
            "outBounds\\.union\\(mTemporaryBounds\\)".toRegex().findAll(inputView).count(),
        )
        assertTrue(inputView.contains("mEmojiPanelView"))
        assertTrue(inputView.contains("panel.isShown()"))
        assertTrue(inputView.contains("mEmojiSearchView"))
        assertTrue(inputView.contains("search.isShown()"))
    }

    // --- The emoji key never edits the editor -------------------------------------------------

    @Test
    fun emojiFunctionalBranchOnlyShowsThePanelAndNeverEditsText() {
        val inputLogic = java(
            "rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java",
        )
        val branch = inputLogic.substringAfter("case Constants.CODE_EMOJI:")
            .substringBefore("case Constants.CODE_SHIFT_ENTER:")
        assertTrue(branch.contains("mLatinIME.showEmojiPanel()"))
        for (edit in listOf("commitText", "deleteText", "sendKeyCodePoint", "deleteSurroundingText")) {
            assertFalse("emoji branch must not $edit", branch.contains(edit))
        }
        // Delete inside the panel routes through the ordinary onCodeInput path (E2a cluster delete).
        val onDelete = keyboardSwitcher.substringAfter("public void onEmojiPanelDelete()")
            .substringBefore("public View onCreateInputView()")
        assertTrue(onDelete.contains("mLatinIME.onCodeInput(Constants.CODE_DELETE"))
    }

    @Test
    fun panelSourceHasNoCommitOrDeleteOfItsOwnAndNoLogging() {
        val panel = java("rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelView.kt")
        for (forbidden in listOf("commitText", "deleteSurroundingText", "Log.", "println", "System.out", "java.net.")) {
            assertFalse("EmojiPanelView contains $forbidden", panel.contains(forbidden))
        }
    }

    // --- The toggle: eight wiring points ------------------------------------------------------

    @Test
    fun toggleIsWiredThroughAllEightPoints() {
        // 0. Key + default true.
        val settings = java("rkr/simplekeyboard/inputmethod/latin/settings/Settings.java")
        assertTrue(settings.contains("PREF_SHOW_EMOJI_KEY = \"pref_show_emoji_key\""))
        assertTrue(settings.contains("prefs.getBoolean(PREF_SHOW_EMOJI_KEY, true)"))
        // 1. SettingsValues field + read.
        val settingsValues = java("rkr/simplekeyboard/inputmethod/latin/settings/SettingsValues.java")
        assertTrue(settingsValues.contains("public final boolean mShowEmojiKey"))
        assertTrue(settingsValues.contains("mShowEmojiKey = Settings.readShowEmojiKey(prefs)"))
        // 2. KeyboardLayoutSet Params field + Builder setter.
        val layoutSet = java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardLayoutSet.java")
        assertTrue(layoutSet.contains("boolean mShowEmojiKey"))
        assertTrue(layoutSet.contains("public Builder setShowEmojiKey(final boolean enabled)"))
        // 3. KeyboardId field assigned from params.
        val keyboardId = java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardId.java")
        assertTrue(keyboardId.contains("public final boolean mShowEmojiKey"))
        assertTrue(keyboardId.contains("mShowEmojiKey = params.mShowEmojiKey"))
        // 4. attrs.xml Keyboard_Case attribute.
        val attrs = File(sourceRoot(), "res/values/attrs.xml").readText()
        assertTrue(attrs.contains("<attr name=\"showEmojiKey\" format=\"boolean\" />"))
        // 5. KeyboardBuilder read + conjunction.
        val builder = java("rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardBuilder.java")
        assertTrue(builder.contains("R.styleable.Keyboard_Case_showEmojiKey"))
        assertTrue(builder.contains("&& showEmojiKeyMatched"))
        // 6. KeyboardSwitcher.loadKeyboard call.
        assertTrue(keyboardSwitcher.contains("builder.setShowEmojiKey(settingsValues.mShowEmojiKey)"))
        // 7. Settings screen switch row.
        val host = java("rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
        assertTrue(host.contains("switchRow(Settings.PREF_SHOW_EMOJI_KEY, true"))
    }

    @Test
    fun theEighthPointClearsTheKeyboardCacheFromThePrefChangeListener() {
        val host = java("rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
        val listener = host.substringAfter("private val prefChangeListener =")
            .substringBefore("override fun onCreate")
        // Without this, KeyboardId.equals()/computeHashCode() ignore the flag and the static
        // sKeyboardCache returns the previous layout, so the toggle would appear dead.
        assertTrue(listener.contains("Settings.PREF_SHOW_EMOJI_KEY == key"))
        assertTrue(listener.contains("KeyboardLayoutSet.onKeyboardThemeChanged()"))
    }

    @Test
    fun theEmojiFlagIsDeliberatelyAbsentFromKeyboardIdEqualityLikeTheNumberRow() {
        val keyboardId = java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardId.java")
        val hashBody = keyboardId.substringAfter("private static int computeHashCode(")
            .substringBefore("private boolean equalsId(")
        val equalsBody = keyboardId.substringAfter("private boolean equalsId(final KeyboardId other)")
            .substringBefore("private static boolean isAlphabetKeyboard(")
        // Same reason mShowNumberRow is absent: the prefChangeListener clears the cache instead.
        assertFalse(hashBody.contains("mShowEmojiKey"))
        assertFalse(equalsBody.contains("mShowEmojiKey"))
        assertFalse(hashBody.contains("mShowNumberRow"))
    }

    // --- Accessibility description for the key -------------------------------------------------

    @Test
    fun emojiKeyHasASpokenDescription() {
        val mapper = java("rkr/simplekeyboard/inputmethod/accessibility/KeyDescriptionMapper.kt")
        assertTrue(mapper.contains("Constants.CODE_EMOJI -> R.string.spoken_description_emoji"))
    }
}
