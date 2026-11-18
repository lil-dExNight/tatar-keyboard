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
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional authenticated encryption around the complete, ordinary backup ZIP.
 *
 * The fixed 48-byte header is magic `TTKBENC\0`, version, KDF and cipher identifiers, a zero
 * reserved byte, big-endian u32 PBKDF2 iterations, a 16-byte salt, a 12-byte IV and big-endian
 * u32 plaintext length. Ciphertext follows, then the 16-byte AES-GCM tag. The complete header is
 * additional authenticated data, so no parameter or length can be changed independently.
 *
 * Both methods consume [CharArray] passwords and clear them before returning or throwing. Derived
 * key bytes and KDF password copies are also cleared. Archives and keys never leave this layer.
 */
internal object BackupEncryption {
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'T'.code.toByte(), 'K'.code.toByte(),
        'B'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 0)
    private const val VERSION = 1
    private const val KDF_PBKDF2_HMAC_SHA256 = 1
    private const val CIPHER_AES_GCM = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val TAG_BITS = TAG_BYTES * 8
    private const val KEY_BITS = 256
    private val RANDOM = SecureRandom()
    internal const val HEADER_BYTES = 48
    internal const val OVERHEAD_BYTES = HEADER_BYTES + TAG_BYTES

    /** Export cost on the backup worker; imports accept a bounded range to permit later tuning. */
    internal const val DEFAULT_ITERATIONS = 210_000
    internal const val MIN_ITERATIONS = 50_000
    internal const val MAX_ITERATIONS = 500_000

    fun isEncrypted(archive: ByteArray): Boolean {
        if (archive.size < MAGIC.size) return false
        return MAGIC.indices.all { archive[it] == MAGIC[it] }
    }

    /** Encrypts [plainArchive] with a fresh salt and IV and consumes [password]. */
    fun encrypt(plainArchive: ByteArray, password: CharArray): ByteArray {
        try {
            require(plainArchive.isNotEmpty())
            require(plainArchive.size.toLong() <= BackupFormat.MAX_ARCHIVE_BYTES)
            val salt = ByteArray(SALT_BYTES)
            val iv = ByteArray(IV_BYTES)
            RANDOM.nextBytes(salt)
            RANDOM.nextBytes(iv)

            val encrypted = ByteArray(HEADER_BYTES + plainArchive.size + TAG_BYTES)
            ByteBuffer.wrap(encrypted)
                .put(MAGIC)
                .put(VERSION.toByte())
                .put(KDF_PBKDF2_HMAC_SHA256.toByte())
                .put(CIPHER_AES_GCM.toByte())
                .put(0)
                .putInt(DEFAULT_ITERATIONS)
                .put(salt)
                .put(iv)
                .putInt(plainArchive.size)
            val keyBytes = deriveKey(password, salt, DEFAULT_ITERATIONS)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(encrypted, 0, HEADER_BYTES)
                val written = cipher.doFinal(plainArchive, 0, plainArchive.size,
                    encrypted, HEADER_BYTES)
                if (written != plainArchive.size + TAG_BYTES) {
                    throw GeneralSecurityException("unexpected AES-GCM output size")
                }
            } finally {
                Arrays.fill(keyBytes, 0.toByte())
                Arrays.fill(salt, 0.toByte())
                Arrays.fill(iv, 0.toByte())
            }
            return encrypted
        } catch (e: GeneralSecurityException) {
            throw BackupValidationException("backup encryption is unavailable")
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Authenticates and decrypts [encryptedArchive], consuming [password]. No plaintext is
     * returned until the GCM tag has passed. The iteration range is checked before the KDF.
     *
     * @throws BackupValidationException for a malformed header, wrong password or modification.
     */
    fun decrypt(encryptedArchive: ByteArray, password: CharArray): ByteArray {
        try {
            if (!isEncrypted(encryptedArchive) ||
                    encryptedArchive.size < HEADER_BYTES + TAG_BYTES + 1 ||
                    encryptedArchive.size.toLong() > BackupFormat.MAX_ENCRYPTED_ARCHIVE_BYTES) {
                fail("encrypted backup size is invalid")
            }
            val input = ByteBuffer.wrap(encryptedArchive)
            input.position(MAGIC.size)
            if (input.get() != VERSION.toByte()) fail("encrypted backup version is unsupported")
            if (input.get() != KDF_PBKDF2_HMAC_SHA256.toByte()) fail("backup KDF is unsupported")
            if (input.get() != CIPHER_AES_GCM.toByte()) fail("backup cipher is unsupported")
            if (input.get() != 0.toByte()) fail("encrypted backup flags are invalid")
            val iterations = input.int
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                fail("backup KDF cost is outside the supported range")
            }
            val salt = ByteArray(SALT_BYTES)
            val iv = ByteArray(IV_BYTES)
            input.get(salt)
            input.get(iv)
            val plainSize = input.int
            if (plainSize <= 0 || plainSize.toLong() > BackupFormat.MAX_ARCHIVE_BYTES ||
                    HEADER_BYTES.toLong() + plainSize + TAG_BYTES != encryptedArchive.size.toLong()) {
                fail("encrypted backup payload size is invalid")
            }

            val keyBytes = deriveKey(password, salt, iterations)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(encryptedArchive, 0, HEADER_BYTES)
                val plain = ByteArray(plainSize)
                val written = try {
                    cipher.doFinal(encryptedArchive, input.position(),
                        plainSize + TAG_BYTES, plain, 0)
                } catch (e: GeneralSecurityException) {
                    Arrays.fill(plain, 0.toByte())
                    throw e
                }
                if (written != plainSize) {
                    Arrays.fill(plain, 0.toByte())
                    fail("encrypted backup plaintext size is invalid")
                }
                return plain
            } finally {
                Arrays.fill(keyBytes, 0.toByte())
                Arrays.fill(salt, 0.toByte())
                Arrays.fill(iv, 0.toByte())
            }
        } catch (e: BackupValidationException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw BackupValidationException("encrypted backup authentication failed")
        } finally {
            password.fill('\u0000')
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        try {
            val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        } catch (_: NoSuchAlgorithmException) {
            // Android exposes this standard PBKDF2 factory only from API 26. The minSdk path uses
            // the same platform HMAC and the standard one-block construction, byte-identical on
            // every API level, rather than adding a dependency or lowering the hash.
            return deriveSingleBlockKey(password, salt, iterations)
        }
    }

    /** PBKDF2-HMAC-SHA256 with one output block (32 bytes), for Android 7.x. */
    internal fun deriveSingleBlockKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val passwordBytes = Charsets.UTF_8.encode(CharBuffer.wrap(password))
        try {
            val passwordArray = ByteArray(passwordBytes.remaining())
            try {
                passwordBytes.get(passwordArray)
                val salted = salt.copyOf(salt.size + 4)
                try {
                    salted[salt.size + 3] = 1
                    var u: ByteArray? = null
                    try {
                        val mac = Mac.getInstance("HmacSHA256")
                        mac.init(SecretKeySpec(passwordArray, "HmacSHA256"))
                        u = mac.doFinal(salted)
                        val result = u.copyOf()
                        try {
                            for (round in 1 until iterations) {
                                // HMAC consumes update() input immediately, so input and output
                                // can share the buffer.
                                mac.update(u)
                                mac.doFinal(u, 0)
                                for (index in result.indices) {
                                    result[index] = (result[index].toInt() xor
                                        u[index].toInt()).toByte()
                                }
                            }
                            return result
                        } catch (failure: Throwable) {
                            // A successful result belongs to the caller; every failed path wipes it.
                            result.fill(0.toByte())
                            throw failure
                        }
                    } finally {
                        u?.fill(0.toByte())
                    }
                } finally {
                    Arrays.fill(salted, 0.toByte())
                }
            } finally {
                Arrays.fill(passwordArray, 0.toByte())
            }
        } finally {
            Arrays.fill(passwordBytes.array(), 0.toByte())
        }
    }

    private fun fail(message: String): Nothing = throw BackupValidationException(message)
}
