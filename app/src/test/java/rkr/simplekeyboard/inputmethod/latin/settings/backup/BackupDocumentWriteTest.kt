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

package rkr.simplekeyboard.inputmethod.latin.settings.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Deterministic fake-provider coverage for the export protocol. The stream and the four document
 * operations are exactly the seams the Android resolver uses; no Android classes are needed here.
 */
class BackupDocumentWriteTest {
    @Test
    fun roundTripReadsBackAndVerifiesBeforeReportingSuccess() {
        val archive = byteArrayOf(1, 2, 3, 4)
        val written = ByteArrayOutputStream()
        var verified = false
        var deleted = false

        val result = BackupDocumentWrite.writeDocument(
            archive,
            openOutputStream = { written },
            readBack = { written.toByteArray() },
            verifyArchive = { bytes ->
                assertArrayEquals(archive, bytes)
                verified = true
            },
            delete = { deleted = true; true },
        )

        assertEquals(BackupExportResult.SAVED, result)
        assertTrue("the completed document was not verified", verified)
        assertFalse("a successful export was deleted", deleted)
    }

    @Test
    fun shortWriteCannotReportSuccess() {
        val archive = byteArrayOf(1, 2, 3, 4)
        val sink = ByteArrayOutputStream()
        val shortStream = object : OutputStream() {
            override fun write(value: Int) = sink.write(value)

            // Simulate a provider's stream accepting only a prefix. It violates the contract a
            // caller cannot assume: the read-back catches it instead of trusting write().
            override fun write(bytes: ByteArray) {
                sink.write(bytes, 0, bytes.size / 2)
            }
        }
        var deleted = false

        val result = BackupDocumentWrite.writeDocument(
            archive,
            openOutputStream = { shortStream },
            readBack = { sink.toByteArray() },
            verifyArchive = { error("a short write reached verification") },
            delete = { deleted = true; true },
        )

        assertEquals(BackupExportResult.FAILED_WITH_POSSIBLE_PARTIAL_DOCUMENT, result)
        assertTrue(deleted)
    }

    @Test
    fun writeFailureClosesAndDeletes() {
        var closed = false
        var deleted = false
        val failing = object : OutputStream() {
            override fun write(value: Int) = throw IOException("injected write failure")
            override fun close() {
                closed = true
                super.close()
            }
        }

        val result = BackupDocumentWrite.writeDocument(
            byteArrayOf(1),
            openOutputStream = { failing },
            readBack = { error("an unwritten document was read") },
            verifyArchive = { error("an unwritten document was verified") },
            delete = { deleted = true; true },
        )

        assertEquals(BackupExportResult.FAILED_WITH_POSSIBLE_PARTIAL_DOCUMENT, result)
        assertTrue(closed)
        assertTrue(deleted)
    }

    @Test
    fun closeFailureCannotReportSuccess() {
        var readBack = false
        var deleted = false
        val stream = object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                throw IOException("injected close failure")
            }
        }

        val result = BackupDocumentWrite.writeDocument(
            byteArrayOf(1, 2),
            openOutputStream = { stream },
            readBack = { readBack = true; stream.toByteArray() },
            verifyArchive = { error("a failed close reached verification") },
            delete = { deleted = true; true },
        )

        assertEquals(BackupExportResult.FAILED_WITH_POSSIBLE_PARTIAL_DOCUMENT, result)
        assertFalse("a failed close must stop before read-back", readBack)
        assertTrue(deleted)
    }

    @Test
    fun readBackMismatchCannotReportSuccess() {
        var deleted = false
        val result = BackupDocumentWrite.writeDocument(
            byteArrayOf(1, 2),
            openOutputStream = { ByteArrayOutputStream() },
            readBack = { byteArrayOf(1, 9) },
            verifyArchive = { error("mismatched bytes reached verification") },
            delete = { deleted = true; true },
        )

        assertEquals(BackupExportResult.FAILED_WITH_POSSIBLE_PARTIAL_DOCUMENT, result)
        assertTrue(deleted)
    }

    @Test
    fun fullArchiveVerificationFailureCannotReportSuccess() {
        val archive = byteArrayOf(1, 2)
        val sink = ByteArrayOutputStream()
        var deleted = false

        val result = BackupDocumentWrite.writeDocument(
            archive,
            openOutputStream = { sink },
            readBack = { sink.toByteArray() },
            verifyArchive = { throw IOException("injected archive failure") },
            delete = { deleted = true; true },
        )

        assertEquals(BackupExportResult.FAILED_WITH_POSSIBLE_PARTIAL_DOCUMENT, result)
        assertTrue(deleted)
    }

    @Test
    fun verifierDecryptsAndAppliesTheRealArchiveGate() {
        val archive = BackupArchive.create(
            versionCode = 1,
            preferences = mapOf("pref_space_swipe" to true),
            personalFiles = emptyList(),
        )
        BackupDocumentWrite.verifyExportedArchive(archive, null)

        val encrypted = BackupEncryption.encrypt(archive, "secret".toCharArray())
        val password = "secret".toCharArray()
        BackupDocumentWrite.verifyExportedArchive(encrypted, password)
        assertTrue("verification did not consume the password", password.all { it == '\u0000' })

        val damaged = encrypted.copyOf()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        try {
            BackupDocumentWrite.verifyExportedArchive(damaged, "secret".toCharArray())
            fail("a modified encrypted archive passed verification")
        } catch (_: BackupValidationException) {
        }
    }

    @Test
    fun deleteRefusalReportsPossiblePartialDocument() {
        val result = BackupDocumentWrite.writeDocument(
            byteArrayOf(1, 2),
            openOutputStream = { ByteArrayOutputStream() },
            readBack = { byteArrayOf(1) },
            verifyArchive = { error("mismatched bytes reached verification") },
            delete = { throw SecurityException("injected delete refusal") },
        )

        assertEquals(BackupExportResult.FAILED_WITH_POSSIBLE_PARTIAL_DOCUMENT, result)
    }

    @Test
    fun openFailureStillAttemptsCleanupAndReportsPartialRisk() {
        var deleted = false
        val result = BackupDocumentWrite.writeDocument(
            byteArrayOf(1),
            openOutputStream = { null },
            readBack = { error("an unopened document was read") },
            verifyArchive = { error("an unopened document was verified") },
            delete = { deleted = true; true },
        )

        assertEquals(BackupExportResult.FAILED_WITH_POSSIBLE_PARTIAL_DOCUMENT, result)
        assertTrue(deleted)
    }
}
