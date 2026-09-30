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

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiTextUtilsTest {

    /** Builds a string from raw code points so every test reads as its Unicode scalar values. */
    private fun seq(vararg codePoints: Int): String {
        val builder = StringBuilder()
        for (codePoint in codePoints) builder.appendCodePoint(codePoint)
        return builder.toString()
    }

    private fun length(text: String): Int = EmojiTextUtils.trailingEmojiClusterLength(text)

    // --- One test per cluster class that a single backspace must delete whole. ---

    @Test
    fun singleBmpEmojiIsDeletedWhole() {
        // ☺ U+263A occupies a single char; it is still a whole emoji.
        assertEquals(1, length(seq(0x263A)))
    }

    @Test
    fun surrogatePairIsDeletedWhole() {
        // 😀 U+1F600 is one supplementary code point, two java-char.
        assertEquals(2, length(seq(0x1F600)))
    }

    @Test
    fun onlyTheTrailingClusterIsMeasuredNotThePrecedingText() {
        // Ordinary text in front of the emoji must not be swept into the count.
        assertEquals(2, length("abc" + seq(0x1F600)))
    }

    @Test
    fun emojiWithVs16IsDeletedWhole() {
        // ❤️ = U+2764 (BMP) + VS16; deleting only the selector would leave a bare heart.
        assertEquals(2, length(seq(0x2764, 0xFE0F)))
    }

    @Test
    fun emojiWithVs15IsDeletedWhole() {
        // The text-presentation selector VS15 binds to its base exactly like VS16.
        assertEquals(2, length(seq(0x2764, 0xFE0E)))
    }

    @Test
    fun skinToneModifierIsDeletedWithItsBase() {
        // 👍🏽 = U+1F44D + U+1F3FD; the modifier must not survive on its own.
        assertEquals(4, length(seq(0x1F44D, 0x1F3FD)))
    }

    @Test
    fun zwjSequenceOfTwoBasesIsDeletedWhole() {
        // 👨‍👦 = man ZWJ boy.
        assertEquals(5, length(seq(0x1F468, 0x200D, 0x1F466)))
    }

    @Test
    fun zwjSequenceOfThreeBasesIsDeletedWhole() {
        // 👨‍👩‍👦 = man ZWJ woman ZWJ boy.
        assertEquals(8, length(seq(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F466)))
    }

    @Test
    fun keycapWithVariationSelectorIsDeletedWhole() {
        // 1️⃣ = "1" + VS16 + COMBINING ENCLOSING KEYCAP.
        assertEquals(3, length(seq(0x0031, 0xFE0F, 0x20E3)))
    }

    @Test
    fun keycapWithoutVariationSelectorIsDeletedWhole() {
        // #⃣ = "#" + COMBINING ENCLOSING KEYCAP, no selector.
        assertEquals(2, length(seq(0x0023, 0x20E3)))
    }

    @Test
    fun regionalIndicatorPairIsDeletedWhole() {
        // 🇷🇺 = two regional indicators, four java-char.
        assertEquals(4, length(seq(0x1F1F7, 0x1F1FA)))
    }

    @Test
    fun tagSequenceWithTerminatorIsDeletedWhole() {
        // 🏴 England = black flag + tag letters g b e n g + CANCEL TAG (U+E007F).
        assertEquals(
            14,
            length(seq(0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F)),
        )
    }

    // --- Characterization: non-emoji text deletes as many char as the plain code-point path. ---

    @Test
    fun combiningMarkInDecomposedTextReturnsZero() {
        // NFD "е" is "е" + U+0301; the frozen path deletes only the trailing mark (one char).
        val decomposed = Normalizer.normalize("é", Normalizer.Form.NFD)
        assertEquals(0, length(decomposed))
    }

    @Test
    fun tatarCyrillicReturnsZero() {
        // The six Tatar-specific letters must behave exactly as ordinary letters.
        for (letter in listOf("ә", "ө", "ү", "җ", "ң", "һ")) {
            assertEquals(0, length(letter))
        }
    }

    @Test
    fun russianCyrillicReturnsZero() {
        assertEquals(0, length("привет"))
    }

    @Test
    fun latinReturnsZero() {
        assertEquals(0, length("word"))
    }

    @Test
    fun digitReturnsZero() {
        // A bare digit is a keycap BASE, but without the enclosing keycap it is just a digit.
        assertEquals(0, length("5"))
    }

    @Test
    fun spaceReturnsZero() {
        assertEquals(0, length(" "))
    }

    @Test
    fun emptyInputReturnsZero() {
        assertEquals(0, length(""))
    }

    @Test
    fun loneHighSurrogateReturnsZero() {
        // A truncated surrogate pair is not an emoji; the old path deletes its single char.
        assertEquals(0, length("\uD83D"))
    }

    @Test
    fun loneLowSurrogateReturnsZero() {
        assertEquals(0, length("\uDE00"))
    }

    @Test
    fun nonEmojiSupplementaryCodePointReturnsZero() {
        // U+20000 (CJK Extension B) is a supplementary letter, not an emoji.
        assertEquals(0, length(seq(0x20000)))
    }

    // --- The 32-char cap. ---

    private fun zwjChain(elementCount: Int): String {
        val builder = StringBuilder()
        for (index in 0 until elementCount) {
            if (index > 0) builder.appendCodePoint(0x200D)
            builder.appendCodePoint(0x1F468)
        }
        return builder.toString()
    }

    @Test
    fun clusterExactlyAtThirtyTwoCharsIsDeletedWhole() {
        // 11 supplementary bases + 10 joiners = 32 char, right at the limit.
        val chain = zwjChain(11)
        assertEquals(32, chain.length)
        assertEquals(32, length(chain))
    }

    @Test
    fun clusterLongerThanThirtyTwoCharsReturnsZero() {
        // 12 bases + 11 joiners = 35 char, over the limit: fall back to the code-point delete.
        val chain = zwjChain(12)
        assertEquals(35, chain.length)
        assertEquals(0, length(chain))
    }

    @Test
    fun overLongTagSequenceReturnsZero() {
        // A base plus 20 tag characters and a terminator is 44 char, past the cap.
        val builder = StringBuilder()
        builder.appendCodePoint(0x1F3F4)
        repeat(20) { builder.appendCodePoint(0xE0067) }
        builder.appendCodePoint(0xE007F)
        assertEquals(44, builder.length)
        assertEquals(0, length(builder.toString()))
    }

    // --- extractContextBeforeEmoji: the word a picked emoji was co-used with. ---
    // ☀️ = U+2600 U+FE0F; 😊 = U+1F60A (a surrogate pair).

    private fun context(text: String): String? = EmojiTextUtils.extractContextBeforeEmoji(text)

    @Test
    fun aWordThenSpaceThenEmojiYieldsTheWord() {
        assertEquals("сәләм", context("сәләм " + seq(0x2600, 0xFE0F)))
    }

    @Test
    fun anEmojiHuggingItsWordYieldsTheWord() {
        assertEquals("сәләм", context("сәләм" + seq(0x2600, 0xFE0F)))
    }

    @Test
    fun anEmojiAloneYieldsNull() {
        assertNull(context(seq(0x2600, 0xFE0F)))
    }

    @Test
    fun aMultiEmojiRunIsOneBoundary() {
        // Consecutive clusters are peeled as one: the pick still belongs to the same word.
        assertEquals("сәләм", context("сәләм " + seq(0x2600, 0xFE0F) + seq(0x1F60A)))
    }

    @Test
    fun aMultiEmojiRunHuggingItsWordIsOneBoundary() {
        assertEquals("сәләм", context("сәләм" + seq(0x2600, 0xFE0F) + seq(0x1F60A)))
    }

    @Test
    fun emptyTextYieldsNull() {
        assertNull(context(""))
    }

    @Test
    fun noTrailingEmojiYieldsNull() {
        assertNull(context("сәләм "))
        assertNull(context("сәләм"))
    }

    @Test
    fun emojiAfterNothingButSpaceYieldsNull() {
        assertNull(context(" " + seq(0x2600, 0xFE0F)))
    }

    @Test
    fun nonFinalPunctuationBetweenWordAndEmojiKeepsTheWord() {
        // «сәләм, ☀️»: the comma run is transparent to the NEXT_WORD context rule.
        assertEquals("сәләм", context("сәләм, " + seq(0x2600, 0xFE0F)))
    }

    @Test
    fun sentenceFinalPunctuationBetweenWordAndEmojiYieldsNull() {
        // «сәләм. ☀️»: a sentence boundary resets the context, exactly like the NEXT_WORD rule.
        assertNull(context("сәләм. " + seq(0x2600, 0xFE0F)))
    }

    @Test
    fun anEarlierSpaceSeparatedEmojiBreaksTheChain() {
        // «сәләм ☀️ 😊»: the peel stops at the space, and what stands before the space run is an
        // emoji, not a word, so nothing is learned.
        assertNull(context("сәләм " + seq(0x2600, 0xFE0F) + " " + seq(0x1F60A)))
    }

    @Test
    fun theWordIsReturnedRawAndCasingIsKept() {
        // Normalization is the store's business; the extractor hands the word over exactly as it
        // stands in the editor.
        assertEquals("Йөрәк", context("Йөрәк " + seq(0x2600, 0xFE0F)))
    }

    @Test
    fun aCompositeClusterIsOneBoundary() {
        // 👨‍👩‍👦 (ZWJ sequence) peels as one cluster, exposing the word before it.
        val family = seq(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F466)
        assertEquals("сәләм", context("сәләм " + family))
        assertNull(context(family))
    }
}
