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

/** The skin-tone table: parsing, composition, and the shipped asset. */
class EmojiSkinTonesTest {

    private val lightTone = "🏻" // U+1F3FB
    private val darkTone = "🏿" // U+1F3FF

    private fun tonesOf(vararg lines: String): EmojiSkinTones =
        EmojiSkinTones.parse(lines.joinToString("\n") + "\n")

    @Test
    fun aWellFormedAssetParsesIntoOneBasePerLine() {
        val tones = tonesOf("👋\t👋\t", "👌\t👌\t")
        assertEquals(2, tones.baseCount)
        assertFalse(tones.isEmpty)
        assertTrue(tones.hasTones("👋"))
        assertFalse(tones.hasTones("😀"))
    }

    @Test
    fun malformedLinesAndDuplicatesAreDroppedAndAnUnreadableAssetIsEmpty() {
        val tones = tonesOf(
            "👋\t👋\t",
            "no tabs",
            "👌\tonly two fields",
            "\t👋\t",          // no sequence
            "🤚\t\t",           // empty prefix: nothing to compose from
            "👋\t👋\t",         // duplicate
            "👍\t👍\t\textra",
        )
        assertEquals(1, tones.baseCount)
        assertTrue(EmojiSkinTones.parse("").isEmpty)
        assertTrue(EmojiSkinTones.parse("junk\njunk\n").isEmpty)
    }

    @Test
    fun variantZeroIsTheNeutralSequenceAndTonesFollowInUnicodeOrder() {
        val tones = tonesOf("👋\t👋\t")
        assertEquals("👋", tones.variantAt("👋", 0))
        assertEquals("👋$lightTone", tones.variantAt("👋", 1))
        assertEquals("👋$darkTone", tones.variantAt("👋", EmojiSkinTones.TONE_COUNT))
    }

    /**
     * The reason the asset stores a prefix instead of the panel sequence: a tone REPLACES U+FE0F.
     * The grid draws U+1F590 U+FE0F, but the toned form is U+1F590 U+1F3FB.
     */
    @Test
    fun aToneReplacesTheVariationSelector() {
        val tones = tonesOf("🖐️\t🖐\t")
        assertEquals("🖐️", tones.variantAt("🖐️", 0))
        assertEquals("🖐$lightTone", tones.variantAt("🖐️", 1))
    }

    @Test
    fun anUnknownSequenceOrOutOfRangeVariantIsReturnedUnchanged() {
        val tones = tonesOf("👋\t👋\t")
        assertEquals("😀", tones.variantAt("😀", 3))
        assertEquals("👋", tones.variantAt("👋", -1))
        assertEquals("👋", tones.variantAt("👋", EmojiSkinTones.TONE_COUNT + 1))
        assertEquals("😀", EmojiSkinTones.EMPTY.variantAt("😀", 1))
    }

    @Test
    fun allTonedSequencesIsEveryToneOfEveryBase() {
        val tones = tonesOf("👋\t👋\t", "👌\t👌\t")
        val all = tones.allTonedSequences()
        assertEquals(2 * EmojiSkinTones.TONE_COUNT, all.size)
        assertTrue("👋$lightTone" in all)
        assertTrue("👌$darkTone" in all)
        // The neutral sequences themselves are not toned forms and are not in the set.
        assertFalse("👋" in all)
    }

    // --- The shipped asset --------------------------------------------------------------------

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    /**
     * The committed table may only name cells the committed panel asset actually draws: a long
     * press can never offer tones for an emoji that has no grid cell.
     */
    @Test
    fun theShippedTableNamesOnlyPanelSequences() {
        val assets = File(sourceRoot(), "assets/emoji")
        val headerRegex = Regex("^#[a-z][a-z0-9-]*$")
        val panelSequences = File(assets, "emoji_set_v1.txt").readText().split('\n')
            .filter { it.isNotEmpty() && !headerRegex.matches(it) }
            .toSet()
        val skinText = File(assets, "emoji_skin_v1.txt").readText()
        val bases = skinText.split('\n').filter { it.isNotEmpty() }.map { it.substringBefore('\t') }

        assertEquals("the table holds a duplicate", bases.size, bases.toSet().size)
        for (base in bases) {
            assertTrue("skin-tone base absent from the panel: $base", base in panelSequences)
        }
        for (line in skinText.split('\n')) {
            if (line.isEmpty()) continue
            assertEquals("bad field count in: $line", 2, line.count { it == '\t' })
        }
    }

    /**
     * The panel asset itself must stay free of toned sequences: the grid shows one neutral cell per
     * emoji and the tones live behind the long press, reached through the skin-tone table.
     */
    @Test
    fun thePanelAssetStillCarriesNoTonedSequence() {
        val panel = File(sourceRoot(), "assets/emoji/emoji_set_v1.txt").readText()
        for (codePoint in 0x1F3FB..0x1F3FF) {
            val tone = String(Character.toChars(codePoint))
            assertFalse("the panel asset carries a toned sequence", panel.contains(tone))
        }
    }

    @Test
    fun theShippedTableComposesRealSequencesForEveryBase() {
        val assets = File(sourceRoot(), "assets/emoji")
        val tones = EmojiSkinTones.parse(File(assets, "emoji_skin_v1.txt").readText())
        assertTrue(tones.baseCount > 100)
        assertTrue(tones.hasTones("👋"))
        assertTrue(tones.hasTones("👍"))
        assertEquals("👋$lightTone", tones.variantAt("👋", 1))
        // Every composed form carries exactly one modifier and keeps its base in front of it.
        for (toned in tones.allTonedSequences()) {
            val modifiers = toned.codePoints().filter { it in 0x1F3FB..0x1F3FF }.count()
            assertEquals("wrong modifier count in $toned", 1L, modifiers)
        }
        assertEquals(tones.baseCount * EmojiSkinTones.TONE_COUNT, tones.allTonedSequences().size)
    }

    @Test
    fun theShippedTableIsCreditedInTheNoticeBesideIt() {
        val notice = File(sourceRoot(), "assets/emoji/NOTICE.txt").readText()
        assertTrue(notice.contains("emoji_skin_v1.txt"))
    }
}
