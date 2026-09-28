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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalEmojiTestFixtures.Entry
import java.io.File

/**
 * The `.tpersem` format contract: the fail-closed validator accepts exactly what the writer
 * produces and rejects everything else — structure, checksum, subtype, UTF-8, alphabet, length,
 * cluster shape, ordering and duplicates. The mirror of [TpersbValidatorTest] for (word, emoji)
 * entries.
 */
class TpersemValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    private fun validate(bytes: ByteArray, requestedSubtype: String = subtype) =
        TpersemValidator().validate(fileOf(bytes), requestedSubtype)

    private fun fileOf(bytes: ByteArray): File =
        temporaryFolder.newFile("emoji-${System.nanoTime()}.tpersem").also { it.writeBytes(bytes) }

    private fun assertRejected(bytes: ByteArray, requestedSubtype: String = subtype) {
        try {
            validate(bytes, requestedSubtype)
            fail("the image must be rejected")
        } catch (expected: PersonalDictionaryValidationException) {
            val message = expected.message.orEmpty()
            assertTrue(message.isNotEmpty())
            assertFalse("no user text in the message", message.contains("сәләм"))
            assertFalse("no user text in the message", message.contains("☀️"))
        }
    }

    @Test
    fun aValidImageRoundTrips() {
        val bytes = PersonalEmojiTestFixtures.build(
            listOf(
                Entry("сәләм", "🌙", usage = 1, frequency = 2, serial = 7),
                Entry("сәләм", "☀️", usage = 0, frequency = 5, serial = 3),
                Entry("мин", "🎉", usage = 0, frequency = 2, serial = 9),
            ),
        )
        val validated = validate(bytes)
        assertEquals(3, validated.entryCount)
        // Entry-key order: (мин, 🎉) < (сәләм, ☀️) < (сәләм, 🌙) — ☀️ sorts before 🌙 by BYTES
        // (0xE2… < 0xF0…), which is also why the fixture must not sort emoji as Strings.
        assertEquals(listOf("мин", "сәләм", "сәләм"), validated.words)
        assertEquals(listOf("🎉", "☀️", "🌙"), validated.emojiClusters)
        assertEquals(1, validated.usageCounts[2])
        assertEquals(5, validated.frequencyCounts[1])
        assertEquals(9L, validated.lastUseSerials[0])
        assertEquals(subtype, validated.subtypeTag)
    }

    @Test
    fun aOneLetterWordIsAccepted() {
        // The floor is 1 code point, mirroring the pairs store — «а» is an ordinary Tatar word.
        val validated = validate(PersonalEmojiTestFixtures.build(listOf(Entry("а", "☀️"))))
        assertEquals(1, validated.entryCount)
    }

    @Test
    fun usageZeroIsAcceptedAndFrequencyZeroIsNot() {
        validate(PersonalEmojiTestFixtures.build(listOf(Entry("сәләм", "☀️", usage = 0))))
        assertRejected(
            PersonalEmojiTestFixtures.build(listOf(Entry("сәләм", "☀️", frequency = 0))),
        )
    }

    @Test
    fun theWholeHeaderIsChecked() {
        val good = listOf(Entry("сәләм", "☀️"))
        assertRejected(PersonalEmojiTestFixtures.build(good, magic = "TATPERSB"))
        assertRejected(PersonalEmojiTestFixtures.build(good, schemaId = 2))
        assertRejected(PersonalEmojiTestFixtures.build(good, formatVersion = 2))
        assertRejected(PersonalEmojiTestFixtures.build(good, headerSize = 70))
        assertRejected(PersonalEmojiTestFixtures.build(good, checksumAlgorithm = 2))
        assertRejected(PersonalEmojiTestFixtures.build(good, entryCountOverride = 501))
        assertRejected(
            PersonalEmojiTestFixtures.build(
                good, payloadSizeOverride = PersonalEmojiTestFixtures.build(good).size.toLong(),
            ),
        )
        assertRejected(PersonalEmojiTestFixtures.build(good, refreshChecksum = false))
    }

    @Test
    fun theSubtypeTagMustMatchTheRequestedSubtype() {
        val bytes = PersonalEmojiTestFixtures.build(
            listOf(Entry("слово", "🌙")), subtypeTag = PersonalSubtypes.RUSSIAN,
        )
        assertRejected(bytes) // a Russian file is not readable as the Tatar store
        // …and IS readable as the Russian one (the word passes the Russian alphabet).
        assertEquals(1, validate(bytes, PersonalSubtypes.RUSSIAN).entryCount)
    }

    @Test
    fun entriesMustBeStrictlyOrderedByTheEntryKey() {
        assertRejected(
            PersonalEmojiTestFixtures.build(
                listOf(Entry("сәләм", "☀️"), Entry("бәйрәм", "🎉")),
                sort = false, // written out of order on purpose
            ),
        )
        // Same word, emoji out of order: 🌙 (0xF0…) sorts AFTER ☀️ (0xE2…) by bytes.
        assertRejected(
            PersonalEmojiTestFixtures.build(
                listOf(Entry("сәләм", "🌙"), Entry("сәләм", "☀️")),
                sort = false,
            ),
        )
    }

    @Test
    fun aDuplicateEntryIsRejected() {
        assertRejected(
            PersonalEmojiTestFixtures.build(
                listOf(Entry("сәләм", "☀️"), Entry("сәләм", "☀️")),
            ),
        )
        // Same word, different emoji is NOT a duplicate: a word may learn several clusters.
        assertEquals(
            2,
            validate(
                PersonalEmojiTestFixtures.build(
                    listOf(Entry("сәләм", "☀️"), Entry("сәләм", "🌙")),
                ),
            ).entryCount,
        )
    }

    @Test
    fun aWordOutsideTheSubtypeAlphabetIsRejected() {
        assertRejected(PersonalEmojiTestFixtures.build(listOf(Entry("hello", "☀️"))))
        assertRejected(PersonalEmojiTestFixtures.build(listOf(Entry("код1234", "☀️"))))
        // Tatar-specific letters are outside the RUSSIAN alphabet.
        assertRejected(
            PersonalEmojiTestFixtures.build(
                listOf(Entry("сәләм", "☀️")), subtypeTag = PersonalSubtypes.RUSSIAN,
            ),
            PersonalSubtypes.RUSSIAN,
        )
    }

    @Test
    fun theWordIsStoredNormalizedOnly() {
        // A capitalized word is not the normalized form: the format stores none.
        assertRejected(
            PersonalEmojiTestFixtures.build(
                listOf(Entry("Мин", "☀️")), normalizeWords = false,
            ),
        )
    }

    @Test
    fun wordLengthBoundsAreEnforcedOnTheNormalizedForm() {
        val tooLong = "а".repeat(25)
        assertRejected(PersonalEmojiTestFixtures.build(listOf(Entry(tooLong, "☀️"))))
        assertEquals(
            1,
            validate(
                PersonalEmojiTestFixtures.build(listOf(Entry("а".repeat(24), "☀️"))),
            ).entryCount,
        )
    }

    @Test
    fun aCombiningMarkInTheWordIsRejected() {
        // U+0301 (combining acute) after ә has no precomposed form, so NFC leaves it in place.
        assertRejected(PersonalEmojiTestFixtures.build(listOf(Entry("сәлә́м", "☀️"))))
    }

    @Test
    fun anEmptyEmojiIsRejected() {
        assertRejected(PersonalEmojiTestFixtures.build(listOf(Entry("сәләм", ""))))
    }

    @Test
    fun plainTextIsNotAnEmojiCluster() {
        assertRejected(PersonalEmojiTestFixtures.build(listOf(Entry("сәләм", "abc"))))
        assertRejected(PersonalEmojiTestFixtures.build(listOf(Entry("сәләм", "5"))))
    }

    @Test
    fun twoClustersAreNotOneEntry() {
        // Two well-formed clusters back to back: the ruler measures only the trailing one.
        assertRejected(PersonalEmojiTestFixtures.build(listOf(Entry("сәләм", "☀️🌙"))))
        // Text followed by a cluster is not a cluster either.
        assertRejected(PersonalEmojiTestFixtures.build(listOf(Entry("сәләм", "a☀️"))))
    }

    @Test
    fun anOverlongClusterIsRejected() {
        // 16 👍 joined by ZWJ: 47 UTF-16 units, past the 32-unit cap.
        val overlong = List(16) { "👍" }.joinToString("‍")
        assertTrue(overlong.length > TpersemFormat.MAX_EMOJI_CLUSTER_CHARS)
        assertRejected(PersonalEmojiTestFixtures.build(listOf(Entry("сәләм", overlong))))
    }

    @Test
    fun everyEmojiClusterShapeTheEditorRecognizesIsAccepted() {
        val tagFlag = buildString {
            appendCodePoint(0x1F3F4) // 🏴
            for (tag in listOf(0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067)) appendCodePoint(tag)
            appendCodePoint(0xE007F)
        }
        val shapes = listOf(
            "☀", // bare BMP base
            "☀️", // base + VS16
            "🌙", // surrogate pair
            "👍🏽", // base + skin-tone modifier
            "👨‍👩‍👧", // ZWJ chain
            "1️⃣", // keycap
            "🇷🇺", // regional-indicator pair
            tagFlag, // tag sequence closed by U+E007F
        )
        for (emoji in shapes) {
            assertEquals(
                "[$emoji] must validate as one cluster",
                1,
                validate(PersonalEmojiTestFixtures.build(listOf(Entry("сәләм", emoji)))).entryCount,
            )
        }
    }

    @Test
    fun truncatedAndTrailingBytesAreRejected() {
        val bytes = PersonalEmojiTestFixtures.build(listOf(Entry("сәләм", "☀️")))
        assertRejected(bytes.copyOf(bytes.size - 1)) // truncated emoji bytes
        assertRejected(bytes + byteArrayOf(0)) // trailing garbage
        assertRejected(bytes.copyOf(TpersemFormat.HEADER_SIZE - 1)) // shorter than the header
    }

    @Test
    fun anOversizedFileIsRejectedBeforeParsing() {
        val valid = PersonalEmojiTestFixtures.build(listOf(Entry("сәләм", "☀️")))
        assertTrue(valid.size.toLong() <= TpersemFormat.MAX_FILE_SIZE)
        // A file PAST the cap is rejected on length alone — never parsed.
        val huge = valid + ByteArray((TpersemFormat.MAX_FILE_SIZE - valid.size + 1).toInt())
        assertRejected(huge)
    }

    @Test
    fun invalidUtf8IsRejectedOnEitherHalf() {
        val bytes = PersonalEmojiTestFixtures.build(listOf(Entry("сәләм", "☀️")))
        // 0xFF is never a valid UTF-8 byte. The last byte of the file is the emoji's last byte…
        val corruptEmoji = bytes.copyOf()
        corruptEmoji[corruptEmoji.size - 1] = 0xFF.toByte()
        assertRejected(PersonalEmojiTestFixtures.refreshEmbeddedChecksum(corruptEmoji))
        // …and the first payload byte after the record header is the word's first byte.
        val corruptWord = bytes.copyOf()
        corruptWord[TpersemFormat.HEADER_SIZE + TpersemFormat.RECORD_HEADER_SIZE] = 0xFF.toByte()
        assertRejected(PersonalEmojiTestFixtures.refreshEmbeddedChecksum(corruptWord))
    }
}
