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
import rkr.simplekeyboard.inputmethod.keyboard.internal.KeyPreviewBalloonDrawable

/**
 * Stage B of `docs/ROADMAP-P8-PLAN.md` (all seven items authorized by the operator on
 * 2026-09-25). Source-level pins for the parts that are structure rather than pixels; the pixels
 * themselves were checked on the emulator and the screenshots are named in the stage report.
 *
 * - **B1/M5** settings screens animate on push and pop, and stand still when the system
 *   animation scale is 0.
 * - **B2/M4** an ACTION key is accent-filled and its glyph flips to the action colour.
 * - **B3/M3** the more-keys panel lives on the key surface and highlights the selected
 *   alternative in the accent colour with a white glyph.
 * - **B4/W5** the strip is 44dp everywhere, including the smoke test's calibration.
 * - **B5/M2** a letter key with a live balloon does not darken; functional keys and the glide
 *   highlight still do.
 * - **B6/S1** the droplet balloon honours the framework's padding contract.
 * - **B7/S2** the platform dialog carries the iOS card styling.
 */
class AppleUxStageBContractTest {

    private fun project(relative: String): File {
        val root = listOf(File("."), File("app"), File("..")).firstOrNull {
            File(it, relative).isFile
        } ?: error("cannot locate $relative from ${File(".").absolutePath}")
        return File(root, relative)
    }

    private fun source(relative: String) = project(relative).readText()

    // ----- B1 (M5) -----

    @Test
    fun b1PushAndPopSetTheTransitionDirection() {
        val host = source(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt",
        )
        assertTrue(
            "navigateTo pushes forward",
            host.substringAfter("internal fun navigateTo(screen: Screen)")
                .substringBefore("internal fun showScreen")
                .contains("pendingTransition = TRANSITION_FORWARD"),
        )
        assertTrue(
            "back pops backward",
            host.substringAfter("override fun onBackPressed()")
                .substringBefore("internal fun navigateTo")
                .contains("pendingTransition = TRANSITION_BACKWARD"),
        )
        assertTrue("200 ms", host.contains("SCREEN_TRANSITION_MS = 200L"))
    }

    @Test
    fun b1TheTransitionRespectsTheSystemAnimationScale() {
        val body = source(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt",
        ).substringAfter("private fun playScreenTransition()")
        assertTrue(
            "the animator duration scale decides",
            body.contains("ANIMATOR_DURATION_SCALE"),
        )
        assertTrue(
            "and a zero scale leaves the column in its resting state",
            body.contains("if (scale <= 0f)") &&
                body.substringAfter("if (scale <= 0f)").contains("contentView.alpha = 1f"),
        )
        assertTrue(
            "a rebuild without a navigation direction animates nothing",
            body.contains("if (direction == TRANSITION_NONE) return"),
        )
    }

    // ----- B2 (M4) -----

    @Test
    fun b2AnActionKeyIsAccentFilledWithItsOwnGlyphColour() {
        val normal = source("src/main/res/drawable/ios_key_normal.xml")
        val active = normal.substringAfter("<item android:state_active=\"true\">")
            .substringBefore("<item android:state_checkable=")
        assertTrue("the active item fills with the accent", active.contains("@color/app_accent"))
        val key = source("src/main/java/rkr/simplekeyboard/inputmethod/keyboard/Key.java")
        val selectColor = key.substringAfter("public final int selectTextColor(")
            .substringBefore("public final int selectHintTextSize(")
        assertTrue(
            "the action colour wins over the functional colour",
            selectColor.indexOf("mActionKeyTextColor") <
                selectColor.indexOf("LABEL_FLAGS_FOLLOW_FUNCTIONAL_TEXT_COLOR"),
        )
        assertTrue(
            "the theme supplies it",
            source("src/main/res/values/themes-tatar.xml")
                .contains("<item name=\"actionKeyTextColor\">@color/ios_key_action_text_color</item>"),
        )
    }

    @Test
    fun b2APlainReturnStaysFunctionalWhileTheImeActionsGoAccent() {
        val enter = source("src/main/res/xml/key_styles_enter.xml")
        assertTrue(
            "defaultEnterKeyStyle is functional",
            enter.substringAfter("latin:styleName=\"defaultEnterKeyStyle\"")
                .substringBefore("/>")
                .contains("latin:backgroundType=\"functional\""),
        )
        val actions = source("src/main/res/xml/key_styles_actions.xml")
        for (style in listOf(
            "goActionKeyStyle", "nextActionKeyStyle", "previousActionKeyStyle",
            "doneActionKeyStyle", "sendActionKeyStyle", "searchActionKeyStyle",
            "customLabelActionKeyStyle",
        )) {
            assertTrue(
                "$style must declare the action background",
                actions.substringAfter("latin:styleName=\"$style\"")
                    .substringBefore("/>")
                    .contains("latin:backgroundType=\"action\""),
            )
        }
    }

    // ----- B3 (M3) -----

    @Test
    fun b3ThePanelUsesTheKeySurfaceAndAnAccentSelection() {
        assertTrue(
            "panel surface = key surface",
            source("src/main/res/values/themes-tatar.xml")
                .contains("<item name=\"popupPanelBackgroundColor\">@color/ios_key_normal</item>"),
        )
        for (palette in listOf("values", "values-night")) {
            val colors = source("src/main/res/$palette/colors.xml")
            assertTrue(
                "$palette: the selection is the accent",
                colors.contains("<color name=\"ios_popup_key_pressed\">@color/app_accent</color>"),
            )
            assertTrue(
                "$palette: and its glyph is white",
                colors.contains("<color name=\"ios_popup_key_selected_text\">#FFFFFF</color>"),
            )
        }
        val panel = source(
            "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/MoreKeysKeyboardView.java",
        )
        assertTrue(
            "the selected cell's label inverts",
            panel.substringAfter("protected int selectLabelColor(")
                .substringBefore("private Key detectKey(")
                .contains("key.isPressed()"),
        )
    }

    // ----- B4 (W5) -----

    @Test
    fun b4TheStripIsFortyFourDpEverywhere() {
        assertTrue(
            source("src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionStripState.kt")
                .contains("const val STRIP_HEIGHT_DP = 44"),
        )
        for (folder in listOf("layout", "layout-v28")) {
            val layout = source("src/main/res/$folder/input_view.xml")
            assertTrue("$folder: stub height", layout.contains("android:layout_height=\"44dp\""))
            assertFalse("$folder: no 40dp left", layout.contains("android:layout_height=\"40dp\""))
        }
    }

    @Test
    fun b4TheSmokeTestWasRecalibrated() {
        val smoke = project("../scripts/emulator-smoke.sh").let { candidate ->
            if (candidate.isFile) candidate.readText()
            else listOf(File("scripts/emulator-smoke.sh"), File("../scripts/emulator-smoke.sh"))
                .first(File::isFile).readText()
        }
        assertTrue("the strip cell moved with the height", smoke.contains("STRIP_CELL2=\"0.5000,0.5954\""))
    }

    // ----- B5 (M2) -----

    @Test
    fun b5ALetterKeyWithABalloonDoesNotDarken() {
        val body = source(
            "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/MainKeyboardView.java",
        ).substringAfter("public void onKeyPressed(final Key key, final boolean withPreview)")
            .substringBefore("private void showKeyPreview(")
        assertTrue(
            "the balloon has to be the one carrying the feedback",
            body.contains("key.isNormalBackground()") &&
                body.contains("mKeyPreviewDrawParams.isPopupEnabled()"),
        )
        assertTrue(
            "and only then is the press state skipped",
            body.contains("if (!balloonCarriesTheFeedback)") &&
                body.substringAfter("if (!balloonCarriesTheFeedback)").contains("key.onPressed()"),
        )
    }

    @Test
    fun b5TheGlideHighlightIsUntouched() {
        val tracker = source(
            "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java",
        ).substringAfter("private void updateGlideFeedback(")
            .substringBefore("private void endGlideFeedback(")
        assertTrue(
            "the glide still presses the hovered key with withPreview == false",
            tracker.contains("sDrawingProxy.onKeyPressed(key, false /* withPreview */)"),
        )
    }

    // ----- B6 (S1) -----

    @Test
    fun b6TheDropletHonoursThePaddingContract() {
        // height 122dp, bottom padding 60dp, neck 5dp -> the neck ends 67dp down, which is where
        // the parent key's top edge sits (keyPreviewOffset 55dp below the drawable's bottom).
        assertEquals(67f, KeyPreviewBalloonDrawable.neckBottomOf(122f, 60f, 5f), 0.001f)
    }

    @Test
    fun b6TheDropletReplacesTheRectangleAndAllocatesOnlyOnBoundsChange() {
        val choreographer = source(
            "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/internal/KeyPreviewChoreographer.java",
        )
        assertTrue(
            "the Tatar preview background becomes the droplet",
            choreographer.contains("new KeyPreviewBalloonDrawable(context)"),
        )
        val drawable = source(
            "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/internal/KeyPreviewBalloonDrawable.java",
        )
        assertTrue(
            "the path is built on bounds change",
            drawable.substringAfter("protected void onBoundsChange(").contains("buildPath("),
        )
        val draw = drawable.substringAfter("public void draw(final Canvas canvas)")
            .substringBefore("public void setAlpha(")
        assertFalse("draw() allocates nothing", draw.contains("new "))
    }

    // ----- B7 (S2) -----

    @Test
    fun b7ThePlatformDialogCarriesTheIosCard() {
        val theme = source("src/main/res/values/platform-theme.xml")
        assertTrue("card background", theme.contains("<item name=\"android:windowBackground\">@drawable/app_dialog_bg</item>"))
        assertTrue("centered title", theme.substringAfter("name=\"AppDialogTitle\"").contains("center"))
        assertTrue("buttons stop shouting", theme.substringAfter("name=\"AppDialogButton\"").contains("android:textAllCaps\">false"))
        assertTrue(
            "13dp alert radius",
            source("src/main/res/drawable/app_dialog_bg.xml").contains("android:radius=\"13dp\""),
        )
        assertTrue(
            "the dark palette gets the same styling",
            source("src/main/res/values-night/platform-theme.xml")
                .contains("<item name=\"android:windowBackground\">@drawable/app_dialog_bg</item>"),
        )
    }
}
