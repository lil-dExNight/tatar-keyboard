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
 * Contract of the shipped emoji-suggest asset (`assets/emoji/emoji_suggest_v1.txt`): a curated
 * word -> emoji table for the suggestion strip, built by `scripts/emoji_suggest_pack.py` from
 * `scripts/emoji_suggest_data.tsv`. Pins the data the way `EmojiSearchTest` pins the search
 * index: three tab-separated fields per line, only `ru`/`tt` words, only emoji the panel asset
 * can draw, known positive examples, and zero hits for the polysemy traps.
 */
class EmojiSuggestAssetTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private fun readTable(): Map<Pair<String, String>, String> {
        val text = File(sourceRoot(), "assets/emoji/emoji_suggest_v1.txt").readText()
        assertTrue(text.endsWith("\n"))
        assertFalse(text.contains('\r'))
        val mapping = LinkedHashMap<Pair<String, String>, String>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val fields = line.split('\t')
            assertEquals("bad field count in: $line", 3, fields.size)
            assertTrue("empty field in: $line", fields.all { it.isNotEmpty() })
            val key = fields[0] to fields[1]
            assertFalse("duplicate key in: $line", mapping.containsKey(key))
            mapping[key] = fields[2]
        }
        return mapping
    }

    @Test
    fun theShippedTableUsesOnlyRuAndTtWordsInLowercase() {
        val table = readTable()
        assertTrue("the table is suspiciously small", table.size > 3000)
        for ((lang, word) in table.keys) {
            assertTrue("unknown language in: $lang:$word", lang == "ru" || lang == "tt")
            assertEquals("not lowercase: $word", word.lowercase(), word)
        }
    }

    /** The suggestion strip may only ever offer emoji the panel can draw. */
    @Test
    fun theShippedTableNeverOffersWhatThePanelCannotDraw() {
        val headerRegex = Regex("^#[a-z][a-z0-9-]*$")
        val panel = File(sourceRoot(), "assets/emoji/emoji_set_v1.txt").readText()
            .split('\n')
            .filter { it.isNotEmpty() && !headerRegex.matches(it) }
            .toSet()
        for ((key, emoji) in readTable()) {
            assertTrue("emoji absent from the panel: $key -> $emoji", emoji in panel)
        }
    }

    /** Known positive examples. */
    @Test
    fun theShippedTableAnswersTheObviousQueries() {
        val table = readTable()
        assertEquals("❤️", table["ru" to "сердце"])
        assertEquals("❤️", table["ru" to "сердца"])
        assertEquals("❤️", table["ru" to "сердцу"])
        assertEquals("✈️", table["ru" to "самолет"])
        assertEquals("✈️", table["ru" to "самолета"])
        assertEquals("❤️", table["tt" to "йөрәк"])
        assertEquals("❤️", table["tt" to "йөрәккә"])
        assertEquals("👋", table["tt" to "сәлам"])
        assertEquals("💼", table["tt" to "эш"])
    }

    /**
     * False-positive traps: frequent and polysemous words and the curation denylist
     * (`scripts/emoji_suggest_pack.py`). None may have an emoji; the full checklist lives in
     * `tests/emoji_suggest_pack/`.
     */
    @Test
    fun theShippedTableNeverMapsThePolysemyTraps() {
        val table = readTable()
        val ruTraps = listOf(
            "можно", "работа", "день", "нет", "пока", "здесь", "очень", "когда",
            "если", "жизнь", "рука", "дело", "время", "мир", "лук", "замок",
            "ключ", "кит", "бар", "месяц", "молния", "земля", "нельзя", "очки",
            "мышь", "труба", "зарядка", "карта",
        )
        val ttTraps = listOf(
            "бар", "юк", "да", "көн", "юл", "баш", "тел", "кул", "сәгать",
            "һава", "яз", "кара", "җир", "кит", "ит", "ат", "эчке",
        )
        for (word in ruTraps) {
            assertFalse("ru trap mapped: $word", table.containsKey("ru" to word))
        }
        for (word in ttTraps) {
            assertFalse("tt trap mapped: $word", table.containsKey("tt" to word))
        }
    }

    @Test
    fun theShippedTableIsCreditedInTheNoticeBesideIt() {
        val notice = File(sourceRoot(), "assets/emoji/NOTICE.txt").readText()
        assertTrue(notice.contains("emoji_suggest_v1.txt"))
    }
}
