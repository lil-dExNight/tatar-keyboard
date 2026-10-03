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

package rkr.simplekeyboard.inputmethod.keyboard.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.common.Constants

/**
 * The emoji key resolves to CODE_EMOJI, the editing-menu names resolve to their codes, every code
 * name referenced from res/xml keeps its code, and ID_TO_NAME and DEFAULT have matching lengths,
 * so no name can index past DEFAULT and throw ArrayIndexOutOfBoundsException.
 */
class KeyboardCodesSetTest {

    // The full expected mapping. Indices 0..13 are the original upstream codes; key_emoji follows
    // key_language_switch; key_left/key_right fire the edit menu's cursor steps and
    // key_unspecified stays CODE_UNSPECIFIED.
    private val expected = linkedMapOf(
        "key_tab" to Constants.CODE_TAB,
        "key_enter" to Constants.CODE_ENTER,
        "key_space" to Constants.CODE_SPACE,
        "key_shift" to Constants.CODE_SHIFT,
        "key_capslock" to Constants.CODE_CAPSLOCK,
        "key_switch_alpha_symbol" to Constants.CODE_SWITCH_ALPHA_SYMBOL,
        "key_output_text" to Constants.CODE_OUTPUT_TEXT,
        "key_delete" to Constants.CODE_DELETE,
        "key_settings" to Constants.CODE_SETTINGS,
        "key_paste" to Constants.CODE_PASTE,
        "key_action_next" to Constants.CODE_ACTION_NEXT,
        "key_action_previous" to Constants.CODE_ACTION_PREVIOUS,
        "key_shift_enter" to Constants.CODE_SHIFT_ENTER,
        "key_language_switch" to Constants.CODE_LANGUAGE_SWITCH,
        "key_emoji" to Constants.CODE_EMOJI,
        "key_left" to Constants.CODE_CURSOR_LEFT,
        "key_right" to Constants.CODE_CURSOR_RIGHT,
        "key_unspecified" to Constants.CODE_UNSPECIFIED,
        "key_select_all" to Constants.CODE_SELECT_ALL,
        "key_cut" to Constants.CODE_CUT,
        "key_copy" to Constants.CODE_COPY,
        "key_paste_context_menu" to Constants.CODE_PASTE_CONTEXT_MENU,
    )

    @Test
    fun emojiNameResolvesToEmojiCode() {
        assertEquals(-14, Constants.CODE_EMOJI)
        assertEquals(Constants.CODE_EMOJI, KeyboardCodesSet.getCode("key_emoji"))
    }

    @Test
    fun everyNameInTheArrayResolvesToItsExpectedCode() {
        for ((name, code) in expected) {
            assertEquals("getCode($name)", code, KeyboardCodesSet.getCode(name))
        }
    }

    @Test
    fun previouslyOutOfBoundsNamesNoLongerThrow() {
        // With mismatched array lengths these would read past DEFAULT's end.
        assertEquals(Constants.CODE_CURSOR_RIGHT, KeyboardCodesSet.getCode("key_right"))
        assertEquals(Constants.CODE_UNSPECIFIED, KeyboardCodesSet.getCode("key_unspecified"))
        assertEquals(Constants.CODE_CURSOR_LEFT, KeyboardCodesSet.getCode("key_left"))
    }

    @Test
    fun everyCodeNameUsedInResXmlKeepsItsPreE2Code() {
        val regex = Regex("!code/([a-z_]+)")
        val used = sortedSetOf<String>()
        resRoot().walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .forEach { file ->
                regex.findAll(file.readText()).forEach { used.add(it.groupValues[1]) }
            }
        assertTrue("expected !code/ references in res", used.isNotEmpty())
        // key_emoji is referenced from res/xml (emojiKeyStyle).
        assertTrue("key_emoji referenced from res/xml", used.contains("key_emoji"))
        for (name in used) {
            val exp = expected[name] ?: error("res references unmapped code name $name")
            assertEquals("getCode($name) referenced in res", exp, KeyboardCodesSet.getCode(name))
        }
    }

    private fun resRoot(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/res from ${File(".").absolutePath}")
    }
}
