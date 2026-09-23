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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramTestFixtures.Entry
import java.io.File

/**
 * The `.tpersb` format contract (P1 of Phase 2, docs/ROADMAP-P2.md): the fail-closed validator
 * accepts exactly what the writer produces and rejects everything else — structure, checksum,
 * subtype, UTF-8, casing, alphabet, length, ordering and duplicates. The mirror of
 * [TpersValidatorTest] for word pairs.
 */
class TpersbValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    private fun validate(bytes: ByteArray, requestedSubtype: String = subtype) =
        TpersbValidator().validate(fileOf(bytes), requestedSubtype)

    private fun fileOf(bytes: ByteArray): File =
        temporaryFolder.newFile("pairs-${System.nanoTime()}.tpersb").also { it.writeBytes(bytes) }

    private fun assertRejected(bytes: ByteArray, requestedSubtype: String = subtype) {
        try {
            validate(bytes, requestedSubtype)
            fail("the image must be rejected")
        } catch (expected: PersonalDictionaryValidationException) {
            val message = expected.message.orEmpty()
            assertTrue(message.isNotEmpty())
            assertFalse("no user text in the message", message.contains("сәләм"))
            assertFalse("no user text in the message", message.contains("дөнья"))
        }
    }

    @Test
    fun aValidImageRoundTrips() {
        val bytes = PersonalBigramTestFixtures.build(
            listOf(
                Entry("сәләм", "дөнья", usage = 1, frequency = 2, serial = 7),
                Entry("сәләм", "абый", usage = 0, frequency = 5, serial = 3),
                Entry("мин", "Гүзәл", usage = 0, frequency = 2, serial = 9),
            ),
        )
        val validated = validate(bytes)
        assertEquals(3, validated.pairCount)
        // Pair-key order: (мин, гүзәл) < (сәләм, абый) < (сәләм, дөнья).
        assertEquals(listOf("мин", "сәләм", "сәләм"), validated.contexts)
        assertEquals(listOf("Гүзәл", "абый", "дөнья"), validated.successorRawForms)
        assertEquals(listOf("гүзәл", "абый", "дөнья"), validated.successorNormalizedForms)
        assertEquals(1, validated.usageCounts[2])
        assertEquals(5, validated.frequencyCounts[1])
        assertEquals(9L, validated.lastUseSerials[0])
        assertEquals(subtype, validated.subtypeTag)
    }

    @Test
    fun aOneLetterPairMemberIsAccepted() {
        // The P1 floor is 1 code point — «а» is an ordinary Tatar word.
        val validated = validate(PersonalBigramTestFixtures.build(listOf(Entry("а", "б"))))
        assertEquals(1, validated.pairCount)
    }

    @Test
    fun usageZeroIsAcceptedAndFrequencyZeroIsNot() {
        validate(PersonalBigramTestFixtures.build(listOf(Entry("сәләм", "дөнья", usage = 0))))
        assertRejected(
            PersonalBigramTestFixtures.build(listOf(Entry("сәләм", "дөнья", frequency = 0))),
        )
    }

    @Test
    fun theWholeHeaderIsChecked() {
        val good = listOf(Entry("сәләм", "дөнья"))
        assertRejected(PersonalBigramTestFixtures.build(good, magic = "TATPERS\u0000"))
        assertRejected(PersonalBigramTestFixtures.build(good, schemaId = 2))
        assertRejected(PersonalBigramTestFixtures.build(good, formatVersion = 2))
        assertRejected(PersonalBigramTestFixtures.build(good, headerSize = 70))
        assertRejected(PersonalBigramTestFixtures.build(good, checksumAlgorithm = 2))
        assertRejected(PersonalBigramTestFixtures.build(good, pairCountOverride = 1001))
        assertRejected(
            PersonalBigramTestFixtures.build(
                good, payloadSizeOverride = PersonalBigramTestFixtures.build(good).size.toLong(),
            ),
        )
        assertRejected(
            PersonalBigramTestFixtures.build(good, refreshChecksum = false),
        )
    }

    @Test
    fun theSubtypeTagMustMatchTheRequestedSubtype() {
        val bytes = PersonalBigramTestFixtures.build(
            listOf(Entry("слово", "мир")), subtypeTag = PersonalSubtypes.RUSSIAN,
        )
        assertRejected(bytes) // a Russian file is not readable as the Tatar store
        // …and IS readable as the Russian one (both words pass the Russian alphabet).
        assertEquals(1, validate(bytes, PersonalSubtypes.RUSSIAN).pairCount)
    }

    @Test
    fun pairsMustBeStrictlyOrderedByThePairKey() {
        assertRejected(
            PersonalBigramTestFixtures.build(
                listOf(Entry("сәләм", "дөнья"), Entry("бәйрәм", "котлы")),
                sort = false, // written out of order on purpose
            ),
        )
        // Same context, successors out of order.
        assertRejected(
            PersonalBigramTestFixtures.build(
                listOf(Entry("сәләм", "дөнья"), Entry("сәләм", "абый")),
                sort = false,
            ),
        )
    }

    @Test
    fun aDuplicatePairIsRejected() {
        assertRejected(
            PersonalBigramTestFixtures.build(
                listOf(Entry("сәләм", "дөнья"), Entry("сәләм", "дөнья")),
            ),
        )
    }

    @Test
    fun theKeyBoundaryIsPartOfThePairKey() {
        // («аб», «вг») and («абв», «г») concatenate to the same bytes: both must survive — a
        // validator comparing concatenations would call the second a duplicate of the first.
        val validated = validate(
            PersonalBigramTestFixtures.build(
                listOf(Entry("аб", "вг"), Entry("абв", "г")),
            ),
        )
        assertEquals(2, validated.pairCount)
        assertEquals(listOf("аб", "абв"), validated.contexts)
    }

    @Test
    fun aWordOutsideTheSubtypeAlphabetIsRejected() {
        assertRejected(PersonalBigramTestFixtures.build(listOf(Entry("hello", "дөнья"))))
        assertRejected(PersonalBigramTestFixtures.build(listOf(Entry("сәләм", "world"))))
        assertRejected(PersonalBigramTestFixtures.build(listOf(Entry("код1234", "дөнья"))))
        // Tatar-specific letters are outside the RUSSIAN alphabet.
        assertRejected(
            PersonalBigramTestFixtures.build(
                listOf(Entry("сәләм", "дөнья")), subtypeTag = PersonalSubtypes.RUSSIAN,
            ),
            PersonalSubtypes.RUSSIAN,
        )
    }

    @Test
    fun mixedCasingOfTheSuccessorIsRejectedButACapitalIsNot() {
        assertRejected(PersonalBigramTestFixtures.build(listOf(Entry("мин", "гҮзәл"))))
        assertEquals(
            1,
            validate(PersonalBigramTestFixtures.build(listOf(Entry("мин", "Гүзәл")))).pairCount,
        )
    }

    @Test
    fun theContextIsStoredNormalizedOnly() {
        // A capitalized context is not the normalized form: the format stores none.
        assertRejected(
            PersonalBigramTestFixtures.build(
                listOf(Entry("Мин", "гүзәл")), normalizeContexts = false,
            ),
        )
    }

    @Test
    fun wordLengthBoundsAreEnforcedOnTheNormalizedForm() {
        val tooLong = "а".repeat(25)
        assertRejected(PersonalBigramTestFixtures.build(listOf(Entry(tooLong, "дөнья"))))
        assertRejected(PersonalBigramTestFixtures.build(listOf(Entry("сәләм", tooLong))))
        assertEquals(
            1,
            validate(
                PersonalBigramTestFixtures.build(listOf(Entry("а".repeat(24), "дөнья"))),
            ).pairCount,
        )
    }

    @Test
    fun truncatedAndTrailingBytesAreRejected() {
        val bytes = PersonalBigramTestFixtures.build(listOf(Entry("сәләм", "дөнья")))
        assertRejected(bytes.copyOf(bytes.size - 1)) // truncated successor bytes
        assertRejected(bytes + byteArrayOf(0)) // trailing garbage
        assertRejected(bytes.copyOf(TpersbFormat.HEADER_SIZE - 1)) // shorter than the header
    }

    @Test
    fun anOversizedFileIsRejectedBeforeParsing() {
        val valid = PersonalBigramTestFixtures.build(listOf(Entry("сәләм", "дөнья")))
        assertTrue(valid.size.toLong() <= TpersbFormat.MAX_FILE_SIZE)
        // A file PAST the cap is rejected on length alone — never parsed.
        val huge = valid + ByteArray((TpersbFormat.MAX_FILE_SIZE - valid.size + 1).toInt())
        assertRejected(huge)
    }

    @Test
    fun invalidUtf8IsRejected() {
        val bytes = PersonalBigramTestFixtures.build(listOf(Entry("сәләм", "дөнья")))
        // 0xFF is never a valid UTF-8 byte, wherever it lands: the successor fails strict decode.
        val corrupted = bytes.copyOf()
        corrupted[corrupted.size - 1] = 0xFF.toByte()
        assertRejected(PersonalBigramTestFixtures.refreshEmbeddedChecksum(corrupted))
    }
}
