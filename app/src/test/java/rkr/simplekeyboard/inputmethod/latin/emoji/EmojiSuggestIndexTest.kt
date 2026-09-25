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

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils

/**
 * The pure reader of the emoji-suggest table (mission 2 of `docs/EMOJI-SUGGEST-PLAN.md`),
 * exercised the way `EmojiSearchTest` exercises the search index: format, fail-closed parsing,
 * language separation, the glyph-probe filter, and the shipped asset itself.
 */
class EmojiSuggestIndexTest {

    private fun indexOf(vararg lines: String): EmojiSuggestIndex =
        EmojiSuggestIndex.parse(lines.joinToString("\n") + "\n")

    // --- Parsing ------------------------------------------------------------------------------

    @Test
    fun aWellFormedAssetParsesIntoOneEntryPerLine() {
        val index = indexOf(
            "ru\tсамолет\t✈️",
            "ru\tсердце\t❤️",
            "tt\tйөрәк\t❤️",
        )
        assertEquals(3, index.entryCount)
        assertFalse(index.isEmpty)
        assertEquals("✈️", index.lookup("ru", "самолет"))
        assertEquals("❤️", index.lookup("ru", "сердце"))
        assertEquals("❤️", index.lookup("tt", "йөрәк"))
    }

    @Test
    fun malformedLinesAreDroppedAndTheRestStillParses() {
        val index = indexOf(
            "ru\tсамолет\t✈️",
            "no tabs at all",
            "ru\tтолько два поля",
            "\tсамолет\t✈️",
            "ru\t\t✈️",
            "ru\tсамолёт\t",
            "ru\tсердце\t❤️\tлишнее поле",
            "x".repeat(600) + "\ta\tb",
        )
        assertEquals(1, index.entryCount)
        assertEquals("✈️", index.lookup("ru", "самолет"))
    }

    @Test
    fun aDuplicateKeyKeepsItsFirstMapping() {
        val index = indexOf(
            "ru\tсердце\t❤️",
            "ru\tсердце\t💔",
        )
        assertEquals(1, index.entryCount)
        assertEquals("❤️", index.lookup("ru", "сердце"))
    }

    /**
     * 2026-09-25 audit, F16 (mirrors SentStartIndex): the record count is capped fail-closed —
     * the packer ships under 4k entries, so a corrupt asset reporting tens of thousands stops at
     * MAX_RECORDS instead of growing the map without bound. The literal 4096 pins the constant.
     */
    @Test
    fun theRecordCountIsCappedFailClosed() {
        val lines = Array(5000) { "ru\tword$it\t✈️" }
        val index = indexOf(*lines)

        assertEquals(4096, index.entryCount)
        assertEquals("the cap keeps the FIRST records, file order like the duplicate rule",
            "✈️", index.lookup("ru", "word0"))
        assertTrue(index.lookup("ru", "word4095") != null)
        assertNull(index.lookup("ru", "word4096"))
    }

    @Test
    fun theSameWordInTwoLanguagesIsTwoEntries() {
        val index = indexOf(
            "ru\tсердце\t❤️",
            "tt\tсердце\t💙",
        )
        assertEquals(2, index.entryCount)
        assertEquals("❤️", index.lookup("ru", "сердце"))
        assertEquals("💙", index.lookup("tt", "сердце"))
    }

    @Test
    fun anEmptyOrUnreadableInputYieldsTheEmptyIndex() {
        assertTrue(EmojiSuggestIndex.parse("").isEmpty)
        assertTrue(EmojiSuggestIndex.parse("\n\n\n").isEmpty)
        assertEquals(0, EmojiSuggestIndex.EMPTY.entryCount)
        assertNull(EmojiSuggestIndex.EMPTY.lookup("ru", "самолет"))
    }

    @Test
    fun parsingAnInputStreamIsTheSameAsParsingItsText() {
        val text = "ru\tсамолет\t✈️\ntt\tйөрәк\t❤️\n"
        val fromStream = EmojiSuggestIndex.parse(
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        )
        assertEquals(2, fromStream.entryCount)
        assertEquals("✈️", fromStream.lookup("ru", "самолет"))
    }

    // --- Lookup -------------------------------------------------------------------------------

    @Test
    fun anUnknownWordOrLanguageMissesSilently() {
        val index = indexOf("ru\tсамолет\t✈️")
        assertNull(index.lookup("ru", "кит"))
        assertNull(index.lookup("tt", "самолет"))
        assertNull(index.lookup("en", "самолет"))
        assertNull(index.lookup("ru", ""))
    }

    @Test
    fun theAssetLanguageOfASubtypeIsItsTagBeforeTheUnderscore() {
        assertEquals("tt", EmojiSuggestIndex.assetLanguageOf("tt_RU"))
        assertEquals("ru", EmojiSuggestIndex.assetLanguageOf("ru"))
        assertEquals("en", EmojiSuggestIndex.assetLanguageOf("en_US"))
    }

    // --- Glyph-probe filter -------------------------------------------------------------------

    @Test
    fun filterToKeepsOnlyEmojiTheDeviceCanDraw() {
        val index = indexOf(
            "ru\tсамолет\t✈️",
            "ru\tсердце\t❤️",
            "tt\tйөрәк\t❤️",
        )
        val filtered = index.filterTo(setOf("❤️"))
        assertEquals(2, filtered.entryCount)
        assertNull(filtered.lookup("ru", "самолет"))
        assertEquals("❤️", filtered.lookup("ru", "сердце"))
        assertEquals("❤️", filtered.lookup("tt", "йөрәк"))
    }

    @Test
    fun filterToWithNothingDrawableYieldsTheEmptyIndex() {
        val index = indexOf("ru\tсамолет\t✈️")
        assertTrue(index.filterTo(emptySet()).isEmpty)
        assertTrue(index.filterTo(setOf("🐙")).isEmpty)
    }

    @Test
    fun distinctEmojiListsEachEmojiOnce() {
        val index = indexOf(
            "ru\tсердце\t❤️",
            "tt\tйөрәк\t❤️",
            "ru\tсамолет\t✈️",
        )
        assertEquals(setOf("❤️", "✈️"), index.distinctEmoji())
    }

    // --- The shipped asset --------------------------------------------------------------------

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    @Test
    fun theShippedAssetParsesAndAnswersItsControls() {
        val file = File(sourceRoot(), "assets/emoji/emoji_suggest_v1.txt")
        val index = EmojiSuggestIndex.parse(file.inputStream())
        assertEquals("the shipped table is pinned at 3976 entries", 3976, index.entryCount)
        assertEquals("✈️", index.lookup("ru", "самолет"))
        assertEquals("❤️", index.lookup("ru", "сердце"))
        assertEquals("❤️", index.lookup("tt", "йөрәк"))
        // M4a/M4b: закрытые tt-пробелы и конфузибл «йорэк».
        assertEquals("🐓", index.lookup("tt", "әтәч"))
        assertEquals("❤️", index.lookup("tt", "йорэк"))
        assertEquals("👋", index.lookup("tt", "сәлам"))
        // The polysemy denylist: no emoji for «кит» in either language.
        assertNull(index.lookup("ru", "кит"))
        assertNull(index.lookup("tt", "кит"))
    }

    @Test
    fun theShippedAssetIsKeyedByTheSameFormTheLookupNormalizesTo() {
        val index = EmojiSuggestIndex.parse(
            File(sourceRoot(), "assets/emoji/emoji_suggest_v1.txt").inputStream()
        )
        // What the strip looks up is normalizeForLookup(what the user typed): a capitalized or
        // decomposed word must hit the same entry the lowercase composed one does.
        assertEquals("✈️", index.lookup("ru", TatarWordUtils.normalizeForLookup("Самолет")))
        assertEquals("❤️", index.lookup("tt", TatarWordUtils.normalizeForLookup("Йөрәк")))
    }
}
