/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.setup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup done block teaches every gesture the keyboard has: glide (the only one taught
 * before), the space trackpad, the delete slide and the word-delete flick. The teaching is a
 * compact card of read-only TextViews inside the done block, plus a done hint that names all
 * four in one line. The block lives in an `Activity` layout, so the wiring is pinned by source
 * like the settings contract tests.
 */
class SetupGesturesSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private val layout by lazy {
        File(sourceRoot(), "res/layout/setup_activity.xml").readText()
    }

    private fun setupStrings(folder: String): String =
        File(sourceRoot(), "res/$folder/strings-setup.xml").readText()

    private val doneBlock by lazy {
        layout.substringAfter("<LinearLayout\n            android:id=\"@+id/setup_done_block\"")
            .substringBefore("</LinearLayout>\n    </LinearLayout>")
    }

    // --- The card sits in the done block -------------------------------------------------------

    @Test
    fun the_done_block_carries_a_gestures_card_after_the_hint() {
        val hint = doneBlock.indexOf("@string/setup_done_hint")
        val card = doneBlock.indexOf("android:id=\"@+id/setup_gestures_card\"")
        assertTrue("the done block has a gestures card", card >= 0)
        assertTrue("the card follows the done hint", card > hint)
        val title = doneBlock.indexOf("@string/gestures_card_title")
        assertTrue("the card shows the shared gestures title",
            title > card && title - card < 2000)
    }


    @Test
    fun every_gesture_line_is_a_read_only_text_view() {
        val card = doneBlock.substringAfter("android:id=\"@+id/setup_gestures_card\"")
            .substringBefore("</LinearLayout>")
        for (name in listOf("setup_gesture_glide", "setup_gesture_space_trackpad",
                "setup_gesture_delete_slide", "setup_gesture_word_delete_flick")) {
            assertTrue("the card lists $name", card.contains("@string/$name"))
        }
        assertFalse("no switch in the teaching card", card.contains("Switch"))
        assertFalse("no button in the teaching card", card.contains("Button"))
        assertFalse("nothing in the card is a tap target",
            card.contains("android:clickable=\"true\""))
    }

    // --- The hint names all four ---------------------------------------------------------------

    @Test
    fun the_done_hint_names_every_gesture_in_every_locale() {
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val strings = setupStrings(folder)
            val hint = strings.substringAfter("<string name=\"setup_done_hint\">")
                .substringBefore("</string>")
            assertTrue("$folder: the hint is more than the glide half", hint.length > 10)
            for (name in listOf("setup_gesture_glide", "setup_gesture_space_trackpad",
                    "setup_gesture_delete_slide", "setup_gesture_word_delete_flick")) {
                assertTrue("$folder/strings-setup.xml is missing $name",
                    strings.contains("<string name=\"$name\">"))
            }
        }
    }

    @Test
    fun the_try_it_field_still_sits_after_the_card() {
        val card = doneBlock.indexOf("android:id=\"@+id/setup_gestures_card\"")
        val field = doneBlock.indexOf("android:id=\"@+id/setup_test_field\"")
        assertTrue("the try-it field still follows the teaching card", field > card)
    }
}
