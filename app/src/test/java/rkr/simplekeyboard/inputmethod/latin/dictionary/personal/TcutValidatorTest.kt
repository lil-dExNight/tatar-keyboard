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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TextShortcutTestFixtures.Entry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * The `.tcut` validator: a valid image parses, and every structural, checksum, content, ordering
 * or duplicate violation is rejected. Byte images come from [TextShortcutTestFixtures], so both
 * valid and deliberately corrupt layouts are covered. Plain JVM.
 */
class TcutValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun aValidImageParsesIntoOrderedPairs() {
        val validated = TcutValidator().validate(
            write(TextShortcutTestFixtures.build(
                listOf(Entry("бик", "бик үк"), Entry("тк", "Татарстан")))))

        assertEquals(listOf("бик", "тк"), validated.shortcuts)
        assertEquals(listOf("бик үк", "Татарстан"), validated.expansions)
    }

    @Test
    fun anEmptyImageParsesIntoNoPairs() {
        assertEquals(
            0,
            TcutValidator().validate(write(TextShortcutTestFixtures.build(emptyList()))).entryCount)
    }

    @Test
    fun rejectsTheWrongMagic() = assertRejected(
        TextShortcutTestFixtures.build(listOf(Entry("тк", "Татарстан")), magic = "TATPERS\u0000"))

    @Test
    fun rejectsAnUnsupportedSchemaAndFormat() {
        assertRejected(TextShortcutTestFixtures.build(listOf(Entry("тк", "Татарстан")), schemaId = 2))
        assertRejected(TextShortcutTestFixtures.build(listOf(Entry("тк", "Татарстан")), formatVersion = 2))
    }

    @Test
    fun rejectsAChecksumMismatch() {
        val image = TextShortcutTestFixtures.build(listOf(Entry("тк", "Татарстан")))
        image[image.size - 1] = (image[image.size - 1].toInt() xor 0x7f).toByte()
        assertRejected(image)
    }

    @Test
    fun rejectsAStalePayloadSize() = assertRejected(
        TextShortcutTestFixtures.build(listOf(Entry("тк", "Татарстан")), payloadSizeOverride = 1L))

    @Test
    fun rejectsAnEntryCountOverTheCap() = assertRejected(
        TextShortcutTestFixtures.build(
            emptyList(), entryCountOverride = TcutFormat.MAX_SHORTCUT_ENTRIES + 1))

    @Test
    fun rejectsUnsortedAndDuplicateShortcuts() {
        assertRejected(TextShortcutTestFixtures.build(
            listOf(Entry("тк", "Татарстан"), Entry("бик", "бик үк")), sort = false))
        assertRejected(TextShortcutTestFixtures.build(
            listOf(Entry("тк", "Татарстан"), Entry("тк", "Татарстан")), sort = false))
    }

    @Test
    fun rejectsTrailingPayloadBytes() {
        // One recordless payload byte: the sizes agree, the checksum is fresh, and the parser must
        // still refuse bytes no record claimed.
        val base = TextShortcutTestFixtures.build(emptyList())
        val image = base.copyOf(base.size + 1)
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TcutFormat.PAYLOAD_SIZE_OFFSET, 1)
        assertRejected(TextShortcutTestFixtures.refreshEmbeddedChecksum(image))
    }

    @Test
    fun rejectsNonUtf8Records() {
        val image = TextShortcutTestFixtures.buildRawRecord(
            byteArrayOf(0xC3.toByte()), "текст".toByteArray(StandardCharsets.UTF_8))
        assertRejected(image)
    }

    @Test
    fun rejectsContentTheFilterWouldNeverAccept() {
        assertRejected(
            TextShortcutTestFixtures.build(listOf(Entry("тк2", "санау"))),
            "a digit can never be typed as one word")
        assertRejected(
            TextShortcutTestFixtures.buildRawRecord(
                "тк".toByteArray(StandardCharsets.UTF_8),
                "line\nbreak".toByteArray(StandardCharsets.UTF_8)),
            "a line break is not a fixed phrase")
        assertRejected(TextShortcutTestFixtures.build(listOf(Entry("", "буш"))), "an empty shortcut")
    }

    @Test
    fun rejectsFormatCharactersInExpansions() {
        // A bidi override or a zero-width space would disguise what the cell shows and commits.
        assertRejected(
            TextShortcutTestFixtures.build(listOf(Entry("тк", "казань\u202Eсъезд"))), "a bidi override")
        assertRejected(
            TextShortcutTestFixtures.build(listOf(Entry("тк", "ка\u200Bзань"))), "a zero-width space")
    }

    @Test
    fun theExpansionRuleRejectsFormatCharactersDirectly() {
        assertFalse(TcutValidator.isWellFormedExpansion("a\u202Eb"))
        assertFalse(TcutValidator.isWellFormedExpansion("a\u200Bb"))
        assertFalse(TcutValidator.isWellFormedExpansion("a\u00ADb")) // soft hyphen
        assertTrue(TcutValidator.isWellFormedExpansion("бик үк"))
    }

    private fun assertRejected(image: ByteArray, why: String = "") {
        try {
            TcutValidator().validate(write(image))
            fail("must be rejected: $why")
        } catch (_: TextShortcutValidationException) {
            // Expected.
        }
    }

    private fun write(image: ByteArray): File =
        temporaryFolder.newFile().apply { writeBytes(image) }
}
