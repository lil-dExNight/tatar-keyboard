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
 * Source contract (like SuggestionStripSourceContractTest / EmojiSourceContractTest): greps the
 * source rather than exercising Android, guarding the panel content — insertion/deletion only
 * through LatinIME, no allocations in the hot bodies,
 * background-only snapshot preparation, exactly two functional keys, and a never-persisted probe.
 */
class EmojiPanelSourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun java(path: String) = File(sourceRoot(), "java/$path").readText()

    private val panel by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelView.kt")
    }
    private val controller by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelController.kt")
    }
    private val stateSource by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelState.kt")
    }
    private val latinIme by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/LatinIME.java")
    }
    private val keyboardSwitcher by lazy {
        java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java")
    }

    private fun onDrawBody() =
        panel.substringAfter("override fun onDraw").substringBefore("@Suppress(\"ClickableViewAccessibility\")")

    private fun onTouchBody() =
        panel.substringAfter("override fun onTouchEvent").substringBefore("override fun onVisibilityChanged")

    // --- Insertion and deletion go only through LatinIME ---------------------------------------

    @Test
    fun panelHasNoCommitOrDeleteOrLoggingOfItsOwn() {
        for (forbidden in listOf(
            "commitText",
            "deleteSurroundingText",
            "deleteTextBeforeCursor",
            "Log.",
            "println",
            "System.out",
            "java.net.",
        )) {
            assertFalse("EmojiPanelView contains $forbidden", panel.contains(forbidden))
        }
    }

    @Test
    fun insertionRoutesThroughOnTextInputAndDeletionThroughOnCodeInput() {
        // The panel only signals its listener; the wiring is in KeyboardSwitcher.
        assertTrue(panel.contains("onEmojiPanelPick"))
        assertTrue(panel.contains("onEmojiPanelDelete"))
        assertTrue(keyboardSwitcher.contains("mLatinIME.onTextInput(sequence)"))
        assertTrue(keyboardSwitcher.contains("mLatinIME.onCodeInput(Constants.CODE_DELETE"))
    }

    // --- No allocations in the hot bodies; only visible rows drawn -----------------------------

    @Test
    fun onDrawAndTouchBodiesContainNoKnownAllocationSites() {
        val drawBody = onDrawBody()
        val touchBody = onTouchBody()
        // Kept in sync with SuggestionStripSourceContractTest's list (arrayOf( covers intArrayOf(
        // and charArrayOf(; listOf( covers the collection literals).
        val forbidden = listOf(
            "= Rect(",
            "= Paint(",
            "MotionEvent.obtain",
            ".toString()",
            "TextUtils.",
            "arrayOf(",
            "listOf(",
        )
        listOf(drawBody, touchBody).forEach { body ->
            forbidden.forEach { token ->
                assertFalse("hot path contains $token", body.contains(token))
            }
        }
    }

    @Test
    fun onDrawPaintsOnlyTheVisibleGridRows() {
        val drawBody = onDrawBody()
        // The content is walked section by section, and inside a section row by row, both bounded
        // by the visible range rather than by the whole entry count.
        assertTrue(drawBody.contains("state.firstVisibleSection()"))
        assertTrue(drawBody.contains("state.lastVisibleSection()"))
        assertTrue(drawBody.contains("state.firstVisibleRowOf(section)"))
        assertTrue(drawBody.contains("state.lastVisibleRowOf(section)"))
        assertTrue(drawBody.contains("section in firstSection..lastSection"))
        assertTrue(drawBody.contains("while (row <= lastRow)"))
    }

    // --- Measurement and invalidation ------------------------------------------------------------

    @Test
    fun drawPathsNeverMeasureTextAndTheBackKeyWidthIsMeasuredOnceAtConstruction() {
        // onDrawBody spans onDraw AND drawContent (it ends at the touch handler), so this covers
        // the whole content loop; the painters file is checked whole.
        assertFalse(onDrawBody().contains("measureText"))
        val drawing = java("rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelDrawing.kt")
        assertFalse(drawing.contains("measureText"))
        // The panel's one text measurement is the "АБВ" key width, taken once at construction.
        assertTrue(panel.contains("private val backWidthPx = (labelPaint.measureText(BACK_LABEL)"))
    }

    @Test
    fun flingFramesAreChoreographerScheduledNotSelfInvalidating() {
        // Full-view invalidation is correct for a scrolling surface (every pixel moves; the
        // viewport-bounded content loop above is what keeps it cheap). What must hold: fling
        // frames come from postInvalidateOnAnimation, never from an unbounded self-post.
        val scrollBody = panel.substringAfter("override fun computeScroll()")
            .substringBefore("override fun onDraw")
        assertTrue(scrollBody.contains("postInvalidateOnAnimation()"))
        assertFalse(scrollBody.contains("postDelayed("))
    }

    // --- Exactly two functional keys, no space, no Enter ---------------------------------------

    @Test
    fun exactlyTwoFunctionalKeysAndNoSpaceOrEnter() {
        assertTrue(panel.contains("BACK_LABEL"))
        assertTrue(panel.contains("DELETE_LABEL"))
        // The panel never introduces a space or Enter key of its own.
        for (forbidden in listOf("CODE_SPACE", "CODE_ENTER", "CODE_SHIFT_ENTER")) {
            assertFalse("EmojiPanelView contains $forbidden", panel.contains(forbidden))
        }
        // The tap dispatch has three destinations only: pick, back, tab.
        val dispatch = panel.substringAfter("private fun dispatchTarget(")
            .substringBefore("private fun cancelDeleteRepeat(")
        assertTrue(dispatch.contains("onEmojiPanelPick(state.entryAt(target))"))
        assertTrue(dispatch.contains("onEmojiPanelBackToKeyboard()"))
        assertTrue(dispatch.contains("setActiveCategory(EmojiPanelState.tabIndexOf(target))"))
    }

    // --- Delete auto-repeat: existing resources, only on delete, stops on all five conditions ---

    @Test
    fun deleteAutoRepeatUsesExistingConfigResourcesAndOnlyTheDeleteKey() {
        assertTrue(panel.contains("R.integer.config_key_repeat_start_timeout"))
        assertTrue(panel.contains("R.integer.config_key_repeat_interval"))
        // The repeat is armed only when the pressed target is delete; "АБВ" never arms it.
        assertTrue(panel.contains("EmojiPanelState.isDelete(target) && deleteRepeat.begin()"))
    }

    @Test
    fun deleteAutoRepeatStopsOnEveryOneOfTheFiveConditions() {
        val touchBody = onTouchBody()
        // ACTION_UP and ACTION_CANCEL both cancel the repeat.
        assertTrue(touchBody.contains("MotionEvent.ACTION_UP"))
        assertTrue(touchBody.contains("MotionEvent.ACTION_CANCEL"))
        val upBlock = touchBody.substringAfter("MotionEvent.ACTION_UP ->").substringBefore("MotionEvent.ACTION_CANCEL ->")
        assertTrue(upBlock.contains("cancelDeleteRepeat()"))
        val cancelBlock = touchBody.substringAfter("MotionEvent.ACTION_CANCEL ->")
        assertTrue(cancelBlock.contains("cancelDeleteRepeat()"))
        // Finger leaving the delete key during a move stops the repeat.
        assertTrue(touchBody.contains("if (!EmojiPanelState.isDelete(state.pressedTarget())) {"))
        // Panel hidden stops the repeat.
        val visBody = panel.substringAfter("override fun onVisibilityChanged")
            .substringBefore("override fun onDetachedFromWindow")
        assertTrue(visBody.contains("cancelDeleteRepeat()"))
        // Input-view recreation / detach goes through release(), which cancels the repeat.
        val releaseBody = panel.substringAfter("fun release()").substringBefore("override fun onMeasure")
        assertTrue(releaseBody.contains("cancelDeleteRepeat()"))
        assertTrue(
            panel.substringAfter("override fun onDetachedFromWindow")
                .substringBefore("private fun dispatchTarget")
                .contains("release()"),
        )
    }

    // --- Snapshot preparation is background-only and off the cold-start path --------------------

    @Test
    fun assetAccessAndGlyphProbingLiveOnlyInThePreparationCodeNeverInTheView() {
        for (forbidden in listOf("AssetManager", "hasGlyph", "PaintGlyphProbe", "assets.open", "EmojiSet.build")) {
            assertFalse("EmojiPanelView contains $forbidden", panel.contains(forbidden))
        }
        // The controller reads the asset and probes glyphs only inside AssetSnapshotSource.build.
        assertTrue(controller.contains("context.assets.open"))
        assertTrue(controller.contains("EmojiSet.build(input, PaintGlyphProbe"))
    }

    @Test
    fun latinImeOnCreateAndOnStartInputViewNeverTouchTheAssetManagerOrGlyphProbe() {
        val onCreate = latinIme.substringAfter("public void onCreate()")
            .substringBefore("private void loadSettings()")
        val onStart = latinIme
            .substringAfter("void onStartInputViewInternal(final EditorInfo editorInfo, final boolean restarting) {")
            .substringBefore("public void onWindowShown()")
        for (body in listOf(onCreate, onStart)) {
            for (forbidden in listOf("AssetManager", "getAssets(", "hasGlyph", "PaintGlyphProbe", "EmojiSet.build")) {
                assertFalse("LatinIME hot path contains $forbidden", body.contains(forbidden))
            }
        }
    }

    @Test
    fun preparationStartsOnlyOnTheFirstKeyPressNotInOnCreate() {
        // The onCreate wiring only constructs the controller; it never presses the key.
        val setup = latinIme.substringAfter("private void setUpEmojiPanelController()")
            .substringBefore("private void setUpSuggestionsOffer()")
        assertFalse(setup.contains("onEmojiKeyPressed"))
        // The NOT_PREPARED branch is the only place that starts a preparation; PREPARING does not.
        val onPressed = controller.substringAfter("fun onEmojiKeyPressed()")
            .substringBefore("private fun cancelPendingShow()")
        val notPrepared = onPressed.substringAfter("EmojiPanelPreparation.NOT_PREPARED ->")
            .substringBefore("EmojiPanelPreparation.PREPARING ->")
        val preparing = onPressed.substringAfter("EmojiPanelPreparation.PREPARING ->")
        assertTrue(notPrepared.contains("startPreparation()"))
        assertFalse(preparing.contains("startPreparation"))
    }

    // --- The glyph-probe result is never persisted ---------------------------------------------

    @Test
    fun snapshotAndProbeResultAreNeverWrittenToPreferencesOrAFile() {
        for (source in listOf(panel, controller, stateSource)) {
            for (forbidden in listOf(
                "SharedPreferences",
                "getSharedPreferences",
                "openFileOutput",
                "FileOutputStream",
                "FileWriter",
                ".edit()",
            )) {
                assertFalse("emoji panel source persists via $forbidden", source.contains(forbidden))
            }
        }
    }

    // --- Showing the panel empties the strip through the idempotent path ------------------------

    @Test
    fun showingThePanelEmptiesTheStripThroughOnSelectionChanged() {
        val body = latinIme.substringAfter("public void showPanel(")
            .substringBefore("mEmojiPanelController = new EmojiPanelController")
        assertTrue(body.contains("mSuggestionsController.onSelectionChanged()"))
        assertTrue(body.contains("mKeyboardSwitcher.showEmojiPanel(snapshot)"))
    }

    @Test
    fun pendingShowIsCancelledOnEditorSessionFinishInputViewAndRecreation() {
        assertTrue(latinIme.contains("mEmojiPanelController.onEditorSessionChanged()"))
        assertTrue(latinIme.contains("mEmojiPanelController.onFinishInputView()"))
        assertTrue(latinIme.contains("mEmojiPanelController.onInputViewRecreated()"))
    }
}
