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

package rkr.simplekeyboard.inputmethod.latin.setup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Onboarding teaches the keyboard ONCE: a single guide card in the done block covers the Tatar
 * layout (the extra top row and the long-press partners), the four gestures and saving a word to
 * the personal dictionary. The previous version said the gestures twice — a one-line hint and a
 * second card repeating the same four lines — so the hint is gone and the card is the only guide.
 *
 * The guide lives in an `Activity` layout, so the wiring is pinned by source like the settings
 * contract tests; the texts themselves are pinned in all three shipped locales.
 */
class SetupGuideSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private val layout by lazy {
        File(sourceRoot(), "res/layout/setup_activity.xml").readText()
    }

    private fun setupStrings(folder: String): String =
        File(sourceRoot(), "res/$folder/strings-setup.xml").readText()

    private val doneBlock by lazy {
        layout.substringAfter("android:id=\"@+id/setup_done_block\"")
            .substringBefore("</LinearLayout>\n    </LinearLayout>")
    }

    private val guideCard by lazy {
        doneBlock.substringAfter("android:id=\"@+id/setup_guide_card\"")
            .substringBefore("</LinearLayout>")
    }

    /** Every line the single guide must carry, in teaching order. */
    private val guideLines = listOf(
        "setup_guide_layout",
        "setup_guide_long_press",
        "setup_gesture_glide",
        "setup_gesture_space_trackpad",
        "setup_gesture_delete_slide",
        "setup_gesture_word_delete_flick",
        "setup_guide_save_word",
    )

    // --- One guide, no repeated summary ------------------------------------------------------

    @Test
    fun the_done_block_carries_exactly_one_guide_card() {
        assertTrue("the done block has the guide card",
            doneBlock.contains("android:id=\"@+id/setup_guide_card\""))
        assertFalse("the old gestures card id must not survive beside the guide",
            layout.contains("setup_gestures_card"))
        assertTrue("the guide is titled",
            guideCard.contains("@string/setup_guide_title"))
    }

    @Test
    fun the_gestures_are_not_summarised_twice() {
        assertFalse("the duplicated one-line gesture hint is removed from the layout",
            layout.contains("setup_done_hint"))
        for (folder in listOf("values", "values-ru", "values-tt")) {
            assertFalse("$folder: the duplicated gesture hint string is removed",
                setupStrings(folder).contains("<string name=\"setup_done_hint\">"))
        }
    }

    @Test
    fun every_guide_line_is_a_read_only_text_view_in_teaching_order() {
        var previous = -1
        for (name in guideLines) {
            val at = guideCard.indexOf("@string/$name")
            assertTrue("the guide lists $name", at >= 0)
            assertTrue("$name must follow the previous guide line", at > previous)
            previous = at
        }
        assertFalse("no switch in the guide card", guideCard.contains("Switch"))
        assertFalse("no button in the guide card", guideCard.contains("Button"))
        assertFalse("nothing in the guide card is a tap target",
            guideCard.contains("android:clickable=\"true\""))
    }

    // --- The guide is fully localized --------------------------------------------------------

    @Test
    fun every_guide_line_exists_in_every_shipped_locale() {
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val strings = setupStrings(folder)
            for (name in guideLines + "setup_guide_title") {
                assertTrue("$folder/strings-setup.xml is missing $name",
                    strings.contains("<string name=\"$name\">"))
            }
        }
    }

    @Test
    fun the_layout_line_names_the_whole_extra_row_in_every_locale() {
        // The eight keys of rowkeys_tatar_extra.xml: the guide must not teach a shorter row.
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val line = setupStrings(folder)
                .substringAfter("<string name=\"setup_guide_layout\">").substringBefore("</string>")
            for (letter in listOf("ә", "ө", "ү", "җ", "ң", "һ", "ё", "ъ")) {
                assertTrue("$folder: the layout line must name $letter", line.contains(letter))
            }
        }
    }

    @Test
    fun the_long_press_line_teaches_the_saved_partners_in_every_locale() {
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val line = setupStrings(folder)
                .substringAfter("<string name=\"setup_guide_long_press\">")
                .substringBefore("</string>")
            // The pairs the layout actually carries: е→ё and ь→ъ keep working now that both
            // letters also have their own key, and the Tatar partners stay discoverable.
            for (pair in listOf("ё", "ъ", "ә", "ө", "ү", "җ", "ң", "һ")) {
                assertTrue("$folder: the long-press line must mention $pair", line.contains(pair))
            }
        }
    }

    // --- Setup completion and accessibility survive -----------------------------------------

    @Test
    fun the_try_it_field_still_sits_after_the_guide() {
        val card = doneBlock.indexOf("android:id=\"@+id/setup_guide_card\"")
        val field = doneBlock.indexOf("android:id=\"@+id/setup_test_field\"")
        assertTrue("the try-it field still follows the guide", field > card)
        val doneButton = doneBlock.indexOf("android:id=\"@+id/setup_done_button\"")
        assertTrue("the done button still completes setup", doneButton > field)
    }

    @Test
    fun the_done_block_stays_a_live_region_and_the_field_keeps_its_spoken_hint() {
        val blockTag = layout.substringAfter("android:id=\"@+id/setup_done_block\"")
            .substringBefore(">")
        assertTrue("TalkBack must announce the done block when it appears",
            blockTag.contains("android:accessibilityLiveRegion=\"polite\""))
        val field = layout.substringAfter("android:id=\"@+id/setup_test_field\"")
            .substringBefore("/>")
        assertTrue("the try-it field's hint is its TalkBack label",
            field.contains("android:hint=\"@string/setup_test_field_hint\""))
        for (folder in listOf("values", "values-ru", "values-tt")) {
            assertTrue("$folder: the try-it hint must survive",
                setupStrings(folder).contains("<string name=\"setup_test_field_hint\">"))
            assertTrue("$folder: the step status descriptions must survive",
                setupStrings(folder).contains("<string name=\"setup_step_status_done\">"))
        }
    }

    @Test
    fun the_suggestions_switch_and_completion_steps_are_untouched() {
        for (id in listOf("setup_step1_button", "setup_step2_button", "setup_suggestions_switch",
                "setup_done_button")) {
            assertTrue("the setup flow must keep $id", layout.contains("android:id=\"@+id/$id\""))
        }
    }
}
