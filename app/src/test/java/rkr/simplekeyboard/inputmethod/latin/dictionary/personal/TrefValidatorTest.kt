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
import org.junit.Test

/**
 * The strictness of the `.tref` validator: the header pins, the checksum, the subtype tag, the
 * record content rules and the duplicate rejection. Every rejection must come with a constant
 * message, so no user text leaves the file. Plain JVM.
 */
class TrefValidatorTest {

    @Test
    fun aWellFormedFileValidates() {
        val pairs = RefusedCorrections.EMPTY
            .noting("китәп", "китап", 500)
            .noting("бала", "бәлә", 500)
        val validated = TrefValidator().validate(pairs.serialize(TAG), TAG)
        assertEquals(2, validated.entryCount)
        assertEquals(listOf("китәп", "бала"), validated.typedWords)
        assertEquals(listOf("китап", "бәлә"), validated.replacements)
        assertEquals(listOf(1, 1), validated.refusalCounts)
        assertEquals(TAG, validated.subtypeTag)
    }

    @Test
    fun aWrongMagicIsRejected() {
        val bytes = validBytes()
        "TATPERS\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        assertRejected(bytes)
    }

    @Test
    fun aChecksumMismatchIsRejected() {
        val bytes = validBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x7f).toByte()
        assertRejected(bytes)
    }

    @Test
    fun aForeignSubtypeTagIsRejected() {
        val bytes = RefusedCorrections.EMPTY.noting("китәп", "китап", 500).serialize(TAG)
        assertRejected(bytes, PersonalSubtypes.RUSSIAN)
    }

    @Test
    fun aZeroRefusalCountIsRejected() {
        assertRejected(RefusedCorrectionsTestFixtures.build(
            RefusedCorrectionsTestFixtures.record("китәп", "китап", 0), entryCount = 1))
    }

    @Test
    fun aDuplicatePairIsRejected() {
        val payload = RefusedCorrectionsTestFixtures.record("китәп", "китап", 1) +
            RefusedCorrectionsTestFixtures.record("китәп", "китап", 2)
        assertRejected(RefusedCorrectionsTestFixtures.build(payload, entryCount = 2))
    }

    @Test
    fun anUnnormalizedWordIsRejected() {
        // Not lowercase...
        assertRejected(RefusedCorrectionsTestFixtures.build(
            RefusedCorrectionsTestFixtures.record("Китәп", "китап", 1), entryCount = 1))
        // ...and not NFC: the word "й" written as "и" + U+0306.
        assertRejected(RefusedCorrectionsTestFixtures.build(
            RefusedCorrectionsTestFixtures.record("\u0438\u0306", "й", 1), entryCount = 1))
    }

    @Test
    fun aWordThatIsNotLettersIsRejected() {
        assertRejected(RefusedCorrectionsTestFixtures.build(
            RefusedCorrectionsTestFixtures.record("ки2", "кит", 1), entryCount = 1))
        assertRejected(RefusedCorrectionsTestFixtures.build(
            RefusedCorrectionsTestFixtures.record("китәп", "", 1), entryCount = 1))
    }

    @Test
    fun aPairWhoseReplacementIsTheTypedWordIsRejected() {
        assertRejected(RefusedCorrectionsTestFixtures.build(
            RefusedCorrectionsTestFixtures.record("китәп", "китәп", 1), entryCount = 1))
    }

    @Test
    fun anOverlongWordIsRejected() {
        val long = "а".repeat(TrefFormat.MAX_WORD_CODE_POINTS + 1)
        assertRejected(RefusedCorrectionsTestFixtures.build(
            RefusedCorrectionsTestFixtures.record(long, "китап", 1), entryCount = 1))
    }

    @Test
    fun aTruncatedPayloadIsRejected() {
        val bytes = validBytes()
        assertRejected(bytes.copyOf(bytes.size - 1))
    }

    @Test
    fun trailingPayloadBytesAreRejected() {
        val bytes = validBytes() + byteArrayOf(0)
        assertRejected(bytes)
    }

    @Test
    fun theEntryCountCapIsRejected() {
        val payload = ByteArray(0)
        assertRejected(RefusedCorrectionsTestFixtures.build(
            payload, entryCount = TrefFormat.MAX_REFUSED_ENTRIES.toInt() + 1))
    }

    @Test
    fun theRejectionMessageNeverCarriesUserText() {
        val distinctive = "җидеһнкс"
        val bytes = RefusedCorrectionsTestFixtures.build(
            RefusedCorrectionsTestFixtures.record(distinctive, distinctive, 1), entryCount = 1)
        try {
            TrefValidator().validate(bytes, TAG)
            fail("the self-pair must be rejected")
        } catch (expected: RefusedCorrectionsValidationException) {
            val message = expected.message.orEmpty()
            assertTrue(message.isNotEmpty())
            assertFalse("the message leaked the word", message.contains(distinctive))
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun validBytes(): ByteArray = RefusedCorrections.EMPTY
        .noting("китәп", "китап", 500)
        .serialize(TAG)

    private fun assertRejected(bytes: ByteArray, subtypeId: String = TAG) {
        try {
            TrefValidator().validate(bytes, subtypeId)
            fail("the bytes must be rejected")
        } catch (expected: RefusedCorrectionsValidationException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    private companion object {
        const val TAG = PersonalSubtypes.TATAR_RU
    }
}
