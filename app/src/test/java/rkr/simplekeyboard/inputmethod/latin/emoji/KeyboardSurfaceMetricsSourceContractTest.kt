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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two rules that live in the wiring rather than in any pure class, pinned at the source level
 * because neither can be reached from a plain JVM test.
 *
 *  * Every text a keyboard surface draws is sized in **dp**, never in **sp**. Each surface is a
 *    row of fixed dp height (suggestion strip, tab row, search query row, section header), so sp
 *    text would grow with the system font scale inside a row that does not: at `font_scale 2.0`
 *    the strip cells truncate until they are indistinguishable. The letter keys use dp too.
 *  * The emoji panel reserves the user's "Bottom offset" just as the letter keyboard does;
 *    otherwise the panel fills the space the user freed and the two surfaces jump when swapped.
 */
class KeyboardSurfaceMetricsSourceContractTest {

    private fun mainSource(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/java from ${File(".").absolutePath}")
    }

    private fun source(relative: String): String {
        val file = File(mainSource(), relative)
        assertTrue("нет файла $relative", file.isFile)
        return file.readText()
    }

    private companion object {
        private const val STRIP =
            "rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionStripView.kt"
        private const val PANEL =
            "rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelView.kt"
        private const val SEARCH =
            "rkr/simplekeyboard/inputmethod/latin/emoji/EmojiSearchView.kt"
        private const val SWITCHER =
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java"
    }

    /** No keyboard surface may size text in sp. */
    @Test
    fun keyboardSurfacesSizeTheirTextInDpNotSp() {
        for (path in listOf(STRIP, PANEL, SEARCH)) {
            val text = source(path)
            assertEquals(
                "$path обязан считать размеры текста в dp: COMPLEX_UNIT_SP найден",
                0,
                Regex("COMPLEX_UNIT_SP").findAll(text).count(),
            )
            assertTrue(
                "$path обязан применять COMPLEX_UNIT_DIP",
                text.contains("TypedValue.COMPLEX_UNIT_DIP"),
            )
        }
    }

    /** The constants are named for the unit they carry, so the next reader cannot re-mix them up. */
    @Test
    fun textSizeConstantsAreNamedInDp() {
        for (path in listOf(STRIP, PANEL, SEARCH)) {
            val text = source(path)
            assertEquals(
                "$path: константа размера текста всё ещё названа *_SP",
                0,
                Regex("""TEXT_SIZE_SP""").findAll(text).count(),
            )
            assertTrue(
                "$path: ожидалась константа *_TEXT_SIZE_DP",
                Regex("""TEXT_SIZE_DP""").containsMatchIn(text),
            )
        }
    }

    /** The switcher hands the keyboard's bottom offset to the panel when it shows it. */
    @Test
    fun switcherHandsTheBottomOffsetToThePanel() {
        val text = source(SWITCHER)
        assertTrue(
            "KeyboardSwitcher обязан запоминать отступ, посчитанный для геометрии клавиатуры",
            text.contains("mKeyboardBottomOffset = keyboardBottomOffset"),
        )
        assertTrue(
            "KeyboardSwitcher обязан передавать отступ панели при показе",
            text.contains("setKeyboardBottomOffsetPx(mKeyboardBottomOffset)"),
        )
    }

    /** And the panel adds it to what it reserves at the bottom, on top of the bar overlap. */
    @Test
    fun panelReservesTheBottomOffsetTogetherWithTheBarOverlap() {
        val text = source(PANEL)
        assertTrue(
            "EmojiPanelView обязан принимать отступ клавиатуры",
            text.contains("fun setKeyboardBottomOffsetPx("),
        )
        assertTrue(
            "резерв обязан складывать перекрытие навбара и отступ клавиатуры",
            Regex("""overlap\.coerceAtLeast\(0\)\s*\+\s*keyboardBottomOffsetPx""")
                .containsMatchIn(text),
        )
    }
}
