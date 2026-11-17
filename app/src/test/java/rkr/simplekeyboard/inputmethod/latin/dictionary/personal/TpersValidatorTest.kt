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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionaryTestFixtures.Entry

class TpersValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val validator = TpersValidator()

    private val golden = listOf(
        Entry("Гүзәл", count = 5, serial = 10),
        Entry("китап", count = 30, serial = 20),
        Entry("сүзлек", count = 12, serial = 5),
    )

    @Test
    fun acceptsGoldenFixtureThatStoresAnUppercaseRawForm() {
        val validated = validate(PersonalDictionaryTestFixtures.build(golden))

        assertEquals(3, validated.entryCount)
        assertEquals(PersonalSubtypes.TATAR_RU, validated.subtypeTag)
        // Words are ordered by their normalized form, and the raw uppercase form is preserved.
        assertEquals(listOf("Гүзәл", "китап", "сүзлек"), validated.rawForms)
        assertEquals(listOf("гүзәл", "китап", "сүзлек"), validated.normalizedForms)
        assertEquals(5, validated.usageCounts[0])
    }

    @Test
    fun acceptsAnEmptyDictionary() {
        val validated = validate(PersonalDictionaryTestFixtures.build(emptyList()))
        assertEquals(0, validated.entryCount)
    }

    @Test
    fun rejectsWrongMagic() =
        assertRejected(PersonalDictionaryTestFixtures.build(golden, magic = "TATDICT\u0000"))

    @Test
    fun rejectsUnsupportedSchemaId() =
        assertRejected(PersonalDictionaryTestFixtures.build(golden, schemaId = 2))

    @Test
    fun rejectsUnsupportedFormatVersion() =
        assertRejected(PersonalDictionaryTestFixtures.build(golden, formatVersion = 2))

    @Test
    fun rejectsUnexpectedHeaderSize() =
        assertRejected(PersonalDictionaryTestFixtures.build(golden, headerSize = 71))

    @Test
    fun rejectsUnsupportedChecksumAlgorithm() =
        assertRejected(PersonalDictionaryTestFixtures.build(golden, checksumAlgorithm = 2))

    @Test
    fun rejectsSubtypeTagThatDoesNotMatchTheRequestedSubtype() =
        assertRejected(PersonalDictionaryTestFixtures.build(golden, subtypeTag = "ru_RU"))

    @Test
    fun rejectsEntryCountAboveTheLimit() =
        assertRejected(
            PersonalDictionaryTestFixtures.build(
                golden,
                entryCountOverride = TpersFormat.MAX_PERSONAL_ENTRIES + 1,
            ),
        )

    @Test
    fun rejectsPayloadSizeThatDoesNotMatchTheFile() =
        assertRejected(
            PersonalDictionaryTestFixtures.build(golden, payloadSizeOverride = 4096),
        )

    @Test
    fun rejectsChecksumMismatch() {
        val bytes = PersonalDictionaryTestFixtures.build(golden)
        // Flip a byte of the stored checksum without refreshing it.
        bytes[TpersFormat.CHECKSUM_OFFSET] = (bytes[TpersFormat.CHECKSUM_OFFSET].toInt() xor 1).toByte()
        assertRejected(bytes)
    }

    @Test
    fun rejectsZeroUsageCount() =
        assertRejected(PersonalDictionaryTestFixtures.build(listOf(Entry("китап", count = 0))))

    @Test
    fun rejectsInvalidUtf8Word() {
        val bytes = PersonalDictionaryTestFixtures.build(listOf(Entry("әби")))
        // First payload word byte -> a stray 0xFF, which can never begin a UTF-8 sequence.
        bytes[TpersFormat.HEADER_SIZE + TpersFormat.RECORD_HEADER_SIZE] = 0xFF.toByte()
        assertRejected(PersonalDictionaryTestFixtures.refreshEmbeddedChecksum(bytes))
    }

    @Test
    fun rejectsMixedCaseWord() =
        assertRejected(PersonalDictionaryTestFixtures.build(listOf(Entry("аБв"))))

    @Test
    fun rejectsWordOutsideTheSubtypeAlphabet() =
        assertRejected(PersonalDictionaryTestFixtures.build(listOf(Entry("abc"))))

    @Test
    fun acceptsAnInternalAsciiHyphenAndKeepsTheRawForm() {
        val validated = validate(PersonalDictionaryTestFixtures.build(listOf(Entry("Азык-төлек"))))

        assertEquals(listOf("Азык-төлек"), validated.rawForms)
        assertEquals(listOf("азык-төлек"), validated.normalizedForms)
    }

    @Test
    fun rejectsHyphensOutsideTwoAlphabetLettersAndOtherDashes() {
        val words = listOf(
            "-азык",
            "азык-",
            "азык--төлек",
            "азык–төлек",
            "азык—төлек",
            "азык‐төлек",
            "азык’төлек",
        )
        for (word in words) {
            assertRejected(PersonalDictionaryTestFixtures.build(listOf(Entry(word))))
        }
    }

    @Test
    fun rejectsWordShorterThanThreeCodePoints() =
        assertRejected(PersonalDictionaryTestFixtures.build(listOf(Entry("аб"))))

    @Test
    fun rejectsWordLongerThanTwentyFourCodePoints() =
        assertRejected(PersonalDictionaryTestFixtures.build(listOf(Entry("а".repeat(25)))))

    @Test
    fun rejectsCombiningMarkRemainingAfterNfc() =
        // "а" + combining acute has no precomposed NFC form, so the mark survives normalization.
        assertRejected(PersonalDictionaryTestFixtures.build(listOf(Entry("а\u0301би"))))

    @Test
    fun rejectsNormalizedFormsThatAreNotStrictlyAscending() =
        assertRejected(
            PersonalDictionaryTestFixtures.build(
                listOf(Entry("гөл"), Entry("аби")),
                sort = false,
            ),
        )

    @Test
    fun rejectsDuplicateNormalizedForms() =
        // "Гүзәл" and "гүзәл" collapse to the same normalized form: a duplicate by the normalized key.
        assertRejected(
            PersonalDictionaryTestFixtures.build(
                listOf(Entry("Гүзәл"), Entry("гүзәл")),
                sort = false,
            ),
        )

    @Test
    fun rejectsFileShorterThanItsHeader() =
        assertRejected(ByteArray(TpersFormat.HEADER_SIZE - 1))

    private fun validate(bytes: ByteArray): ValidatedPersonalDictionary =
        validator.validate(writeFile(bytes), PersonalSubtypes.TATAR_RU)

    private fun assertRejected(bytes: ByteArray) {
        try {
            validator.validate(writeFile(bytes), PersonalSubtypes.TATAR_RU)
            fail("expected PersonalDictionaryValidationException")
        } catch (expected: PersonalDictionaryValidationException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    private fun writeFile(bytes: ByteArray) =
        temporaryFolder.newFile("fixture-${System.nanoTime()}.tpers").also { it.writeBytes(bytes) }
}
