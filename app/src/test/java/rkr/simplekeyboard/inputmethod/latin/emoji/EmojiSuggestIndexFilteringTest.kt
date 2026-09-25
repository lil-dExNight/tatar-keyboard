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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2 of `docs/ROADMAP-P8-PLAN.md`: the emoji-suggestion table is parsed AND glyph-filtered in one
 * pass, so the load no longer holds two copies of the table at its peak.
 *
 * The filtering verdicts must be identical to the old two-pass shape (parse everything →
 * `distinctEmoji()` → `filterTo`), and the probe must still be asked once per DISTINCT emoji
 * sequence, not once per record.
 */
class EmojiSuggestIndexFilteringTest {

    private val table = """
        tt${'\t'}сәләм${'\t'}👋
        tt${'\t'}рәхмәт${'\t'}🙏
        tt${'\t'}сау${'\t'}👋
        ru${'\t'}привет${'\t'}👋
        ru${'\t'}спасибо${'\t'}🙏
        ru${'\t'}ракета${'\t'}🚀
    """.trimIndent()

    private fun parseFiltered(
        keep: (String) -> Boolean,
        onProbe: (String) -> Unit = {},
    ): EmojiSuggestIndex =
        EmojiSuggestIndex.parse(ByteArrayInputStream(table.toByteArray())) { sequence ->
            onProbe(sequence)
            keep(sequence)
        }

    @Test
    fun anAcceptEverythingFilterKeepsTheWholeTable() {
        val index = parseFiltered({ true })
        assertEquals("👋", index.lookup("tt", "сәләм"))
        assertEquals("🙏", index.lookup("ru", "спасибо"))
        assertEquals("🚀", index.lookup("ru", "ракета"))
    }

    @Test
    fun aRejectedEmojiNeverEntersTheIndex() {
        val index = parseFiltered({ it != "👋" })
        assertNull("every record of the rejected emoji is gone", index.lookup("tt", "сәләм"))
        assertNull(index.lookup("tt", "сау"))
        assertNull(index.lookup("ru", "привет"))
        assertEquals("the others survive", "🙏", index.lookup("tt", "рәхмәт"))
    }

    @Test
    fun theProbeIsAskedOncePerDistinctEmoji() {
        val asked = ArrayList<String>()
        parseFiltered({ true }, onProbe = { asked.add(it) })
        assertEquals("six records, three distinct emoji", 3, asked.size)
        assertEquals(listOf("👋", "🙏", "🚀"), asked)
    }

    @Test
    fun aLanguageThatLosesEveryRecordDisappears() {
        val index = parseFiltered({ it == "🚀" })
        assertNull(index.lookup("tt", "сәләм"))
        assertEquals("🚀", index.lookup("ru", "ракета"))
        assertTrue("the index itself is still usable", !index.isEmpty)
    }

    @Test
    fun rejectingEverythingYieldsTheEmptyIndex() {
        assertTrue(parseFiltered({ false }).isEmpty)
    }
}
