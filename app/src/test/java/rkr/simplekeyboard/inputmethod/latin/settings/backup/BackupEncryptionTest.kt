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

import java.nio.ByteBuffer
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupEncryptionTest {

    @Test
    fun encryptedContainerRoundTripsAndConsumesPasswords() {
        val plain = plainArchive()
        val exportPassword = PASSWORD.toCharArray()
        val encrypted = BackupEncryption.encrypt(plain, exportPassword)

        assertTrue(exportPassword.all { it == '\u0000' })
        assertTrue(BackupEncryption.isEncrypted(encrypted))
        assertEquals(plain.size + BackupEncryption.OVERHEAD_BYTES, encrypted.size)
        val header = ByteBuffer.wrap(encrypted)
        header.position(12)
        assertEquals(BackupEncryption.DEFAULT_ITERATIONS, header.int)
        header.position(44)
        assertEquals(plain.size, header.int)

        val importPassword = PASSWORD.toCharArray()
        assertArrayEquals(plain, BackupEncryption.decrypt(encrypted, importPassword))
        assertTrue(importPassword.all { it == '\u0000' })
        assertFalse(BackupEncryption.isEncrypted(plain))
    }

    @Test
    fun everyEncryptionUsesFreshSaltAndIv() {
        val plain = plainArchive()
        val first = BackupEncryption.encrypt(plain, PASSWORD.toCharArray())
        val second = BackupEncryption.encrypt(plain, PASSWORD.toCharArray())

        assertFalse("fresh encryption must not repeat the archive", first.contentEquals(second))
        val firstSalt = first.copyOfRange(16, 32)
        val secondSalt = second.copyOfRange(16, 32)
        assertFalse("fresh salt", firstSalt.contentEquals(secondSalt))
        val firstIv = first.copyOfRange(32, 44)
        val secondIv = second.copyOfRange(32, 44)
        assertFalse("fresh IV", firstIv.contentEquals(secondIv))
    }

    @Test
    fun wrongPasswordIsAnAuthenticatedRejection() {
        val encrypted = BackupEncryption.encrypt(plainArchive(), PASSWORD.toCharArray())
        val wrong = charArrayOf('n', 'o', 't', '-', 'i', 't')
        assertValidationFailure {
            BackupEncryption.decrypt(encrypted, wrong)
        }
        assertTrue(wrong.all { it == '\u0000' })
    }

    @Test
    fun iterationRangeIsBoundedAndBothAcceptedLimitsWork() {
        val plain = plainArchive()
        for (iterations in listOf(BackupEncryption.MIN_ITERATIONS,
                BackupEncryption.MAX_ITERATIONS)) {
            val foreign = encryptWithIterations(plain, PASSWORD.toCharArray(), iterations)
            assertArrayEquals("iterations $iterations", plain,
                BackupEncryption.decrypt(foreign, PASSWORD.toCharArray()))
        }

        val original = BackupEncryption.encrypt(plain, PASSWORD.toCharArray())
        for (iterations in listOf(0, BackupEncryption.MIN_ITERATIONS - 1,
                BackupEncryption.MAX_ITERATIONS + 1, Int.MAX_VALUE)) {
            val changed = original.copyOf()
            ByteBuffer.wrap(changed).putInt(12, iterations)
            assertValidationFailure("iterations $iterations") {
                BackupEncryption.decrypt(changed, PASSWORD.toCharArray())
            }
        }
    }

    @Test
    fun truncatedTrailingAndOversizedEncryptedContainersAreRejected() {
        val encrypted = BackupEncryption.encrypt(plainArchive(), PASSWORD.toCharArray())
        val minimum = encrypted.copyOf(BackupEncryption.HEADER_BYTES + 16 - 1)
        val maximum = ByteArray(BackupFormat.MAX_ENCRYPTED_ARCHIVE_BYTES.toInt() + 1)
        encrypted.copyInto(maximum, 0, 0, 8)

        assertTrue("the minimum case must reach the container guard",
            BackupEncryption.isEncrypted(minimum))
        assertValidationMessage("encrypted backup size is invalid") {
            BackupEncryption.decrypt(minimum, PASSWORD.toCharArray())
        }
        assertTrue("the maximum case must reach the container guard",
            BackupEncryption.isEncrypted(maximum))
        assertValidationMessage("encrypted backup size is invalid") {
            BackupEncryption.decrypt(maximum, PASSWORD.toCharArray())
        }
        assertValidationMessage("encrypted backup payload size is invalid") {
            BackupEncryption.decrypt(encrypted + byteArrayOf(0), PASSWORD.toCharArray())
        }
    }

    @Test
    fun android7HmacPathMatchesThePlatformPbkdf2Factory() {
        val salt = ByteArray(16) { (it * 7 + 3).toByte() }
        val password = "пароль-🔐".toCharArray()
        for (iterations in listOf(1, 2, 4096)) {
            val spec = PBEKeySpec(password, salt, iterations, 256)
            val expected = try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
            val actual = BackupEncryption.deriveSingleBlockKey(password, salt, iterations)
            assertArrayEquals("iterations $iterations", expected, actual)
            Arrays.fill(expected, 0.toByte())
            Arrays.fill(actual, 0.toByte())
        }
        Arrays.fill(password, '\u0000')
        Arrays.fill(salt, 0.toByte())
    }

    private fun plainArchive(): ByteArray = BackupArchive.create(
        versionCode = 45,
        preferences = mapOf("auto_cap" to true, "pref_enabled_subtypes" to "tt_RU;ru"),
        personalFiles = emptyList(),
    )

    /** Produces a structurally valid foreign container at an arbitrary supported KDF cost. */
    private fun encryptWithIterations(
        plain: ByteArray,
        password: CharArray,
        iterations: Int,
    ): ByteArray {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(12) { (31 - it).toByte() }
        val output = ByteArray(BackupEncryption.HEADER_BYTES + plain.size + 16)
        ByteBuffer.wrap(output)
            .put(byteArrayOf('T'.code.toByte(), 'T'.code.toByte(), 'K'.code.toByte(),
                'B'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 0))
            .put(1).put(1).put(1).put(0)
            .putInt(iterations).put(salt).put(iv).putInt(plain.size)
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            password.fill('\u0000')
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(output, 0, BackupEncryption.HEADER_BYTES)
            cipher.doFinal(plain, 0, plain.size, output, BackupEncryption.HEADER_BYTES)
        } finally {
            key.fill(0)
        }
        return output
    }

    companion object {
        private const val PASSWORD = "юникод-safe-пароль"

        private fun assertValidationFailure(message: String = "archive", action: () -> Unit) {
            try {
                action()
                fail("$message: expected BackupValidationException")
            } catch (_: BackupValidationException) {
            }
        }

        private fun assertValidationMessage(expected: String, action: () -> Unit) {
            try {
                action()
                fail("expected BackupValidationException: $expected")
            } catch (failure: BackupValidationException) {
                assertEquals(expected, failure.message)
            }
        }
    }
}
