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

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Encrypted transfer behavior, including the absence of any store access on rejected input. */
class EncryptedBackupTransferTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun encryptedExportAndImportRoundTripEveryStoreAndSettings() {
        val source = populatedSource("round-trip-source")
        val exportPassword = PASSWORD.toCharArray()
        val archive = BackupTransfer.export(source, exportPassword)!!
        assertTrue(BackupEncryption.isEncrypted(archive))
        assertTrue(exportPassword.all { it == '\u0000' })

        val destination = populatedDestination("round-trip-destination")
        val before = destination.snapshot()
        assertEquals(BackupImportResult.PASSWORD_REQUIRED,
            BackupTransfer.import(destination, archive))
        destination.assertUnchanged("password prompt", before)

        val importPassword = PASSWORD.toCharArray()
        assertEquals(BackupImportResult.IMPORTED,
            BackupTransfer.import(destination, archive, importPassword))
        assertTrue(importPassword.all { it == '\u0000' })
        assertEquals(source.preferences.filterKeys {
            it !in BackupFormat.INTERNAL_PREFERENCE_KEYS
        }, destination.preferences)
        assertEquals(source.personalFiles.keys, destination.personalFiles.keys)
        for ((name, bytes) in source.personalFiles) {
            assertArrayEquals(name, bytes, destination.personalFiles[name])
        }
        assertNull(destination.journal)
    }

    @Test
    fun plaintextArchivesKeepWorkingWithoutAPassword() {
        val source = populatedSource("plain-source")
        val archive = BackupTransfer.export(source)!!
        assertFalse(BackupEncryption.isEncrypted(archive))
        val destination = MemoryBackupTarget()

        assertEquals(BackupImportResult.IMPORTED, BackupTransfer.import(destination, archive))
        assertEquals(source.preferences.filterKeys {
            it !in BackupFormat.INTERNAL_PREFERENCE_KEYS
        }, destination.preferences)
        assertEquals(source.personalFiles.keys, destination.personalFiles.keys)
    }

    @Test
    fun plaintextBytesCannotUseThePasswordPathAfterTheProviderChangesTheArchive() {
        val archive = BackupTransfer.export(populatedSource("downgrade-source"))!!
        assertFalse(BackupEncryption.isEncrypted(archive))
        val destination = populatedDestination("downgrade-destination")
        val before = destination.snapshot()
        val password = "secret".toCharArray()

        assertEquals(BackupImportResult.INVALID_FILE,
            BackupTransfer.import(destination, archive, password))

        assertTrue(password.all { it == '\u0000' })
        destination.assertUnchanged("plaintext archive after password prompt", before)
    }

    @Test
    fun wrongPasswordRejectsWithoutRecoveryOrAnyStoreChange() {
        val archive = BackupTransfer.export(populatedSource("wrong-source"),
            PASSWORD.toCharArray())!!
        val destination = populatedDestination("wrong-destination")
        val before = destination.snapshot()
        val wrong = "wrong-password".toCharArray()

        assertEquals(BackupImportResult.INVALID_FILE,
            BackupTransfer.import(destination, archive, wrong))

        assertTrue(wrong.all { it == '\u0000' })
        destination.assertUnchanged("wrong password", before)
    }

    @Test
    fun tamperingWithEveryComponentRejectsBeforeRecoveryOrStores() {
        val original = BackupTransfer.export(populatedSource("tamper-source"),
            PASSWORD.toCharArray())!!
        val destination = populatedDestination("tamper-destination")
        val before = destination.snapshot()
        val variants = listOf(
            "magic" to original.changed(0),
            "version" to original.changed(8),
            "KDF id" to original.changed(9),
            "cipher id" to original.changed(10),
            "reserved header" to original.changed(11),
            "iterations" to original.copyOf().also {
                ByteBuffer.wrap(it).putInt(12, BackupEncryption.DEFAULT_ITERATIONS + 1)
            },
            "salt" to original.changed(16),
            "IV" to original.changed(32),
            "payload length" to original.changed(47),
            "ciphertext" to original.changed(BackupEncryption.HEADER_BYTES),
            "GCM tag" to original.changed(original.lastIndex),
        )

        for ((component, archive) in variants) {
            assertEquals(component, BackupImportResult.INVALID_FILE,
                BackupTransfer.import(destination, archive, PASSWORD.toCharArray()))
            destination.assertUnchanged(component, before)
        }
    }

    @Test
    fun validEncryptedImportKeepsTheExistingRollbackSemantics() {
        val archive = BackupTransfer.export(populatedSource("rollback-source"),
            PASSWORD.toCharArray())!!
        val destination = populatedDestination("rollback-destination")
        val before = destination.snapshot()
        destination.failNextPreferenceWrite = true

        assertEquals(BackupImportResult.WRITE_FAILED,
            BackupTransfer.import(destination, archive, PASSWORD.toCharArray()))

        destination.assertContentEquals("rolled-back import", before)
        assertNull(destination.journal)
    }

    private fun populatedSource(name: String): MemoryBackupTarget {
        val directory = temporaryFolder.newFolder(name)
        BackupTestStores.writeWords(directory, "tt_RU", listOf("абыйлар"))
        BackupTestStores.writePairs(directory, "ru", "книга" to "читаю")
        BackupTestStores.writeEmoji(directory, "tt_RU", "бәйрәм" to "🎉")
        BackupTestStores.writeRefused(directory, "tt_RU", "китәп" to "китап")
        BackupTestStores.writeShortcuts(directory, "бб", "бик булды")
        val target = MemoryBackupTarget()
        target.preferences.putAll(mapOf(
            "auto_cap" to true,
            "pref_enabled_subtypes" to "tt_RU;ru",
            "active_restrictions" to linkedSetOf("pref_personal_dictionary"),
        ))
        for (file in directory.listFiles().orEmpty()) {
            if (BackupFormat.classifyPersonalFileName(file.name) != null) {
                target.personalFiles[file.name] = file.readBytes()
            }
        }
        return target
    }

    private fun populatedDestination(name: String): MemoryBackupTarget {
        val directory = temporaryFolder.newFolder(name)
        BackupTestStores.writeWords(directory, "ru", listOf("китап"))
        val target = MemoryBackupTarget()
        target.preferences["auto_cap"] = false
        target.preferences["legacy_setting"] = "keep me"
        for (file in directory.listFiles().orEmpty()) {
            if (BackupFormat.classifyPersonalFileName(file.name) != null) {
                target.personalFiles[file.name] = file.readBytes()
            }
        }
        return target
    }

    private class MemoryBackupTarget : BackupTarget {
        override val versionCode = 45
        val preferences = LinkedHashMap<String, Any?>()
        val personalFiles = LinkedHashMap<String, ByteArray>()
        var journal: ByteArray? = null
        var failNextPreferenceWrite = false
        var recoveryReads = 0
            private set
        var storeOpens = 0
            private set
        var journalWrites = 0
            private set
        var applies = 0
            private set

        override fun readPreferences(): Map<String, Any?> = HashMap(preferences)
        override fun personalFileNames(): List<String> = personalFiles.keys.toList()
        override fun readPersonalFile(fileName: String): ByteArray? = personalFiles[fileName]

        override fun openPersonalStore(fileName: String): Boolean {
            storeOpens++
            return true
        }

        override fun replacePersonalFile(fileName: String, bytes: ByteArray?): Boolean {
            applies++
            if (bytes == null) personalFiles.remove(fileName)
            else personalFiles[fileName] = bytes.copyOf()
            return true
        }

        override fun replacePreferences(values: Map<String, Any?>): Boolean {
            applies++
            if (failNextPreferenceWrite) {
                failNextPreferenceWrite = false
                return false
            }
            preferences.clear()
            preferences.putAll(values)
            return true
        }

        override fun readImportJournal(): ByteArray? {
            recoveryReads++
            return journal?.copyOf()
        }

        override fun writeImportJournal(bytes: ByteArray): Boolean {
            journalWrites++
            journal = bytes.copyOf()
            return true
        }

        override fun clearImportJournal(): Boolean {
            journal = null
            return true
        }

        fun snapshot(): Snapshot = Snapshot(preferences.toMap(), personalFiles.mapValues {
            it.value.copyOf()
        }, journal?.copyOf(), recoveryReads, storeOpens, journalWrites, applies)

        fun assertContentEquals(message: String, before: Snapshot) {
            assertEquals("$message: preferences", before.preferences, preferences)
            assertEquals("$message: file names", before.personalFiles.keys, personalFiles.keys)
            for ((name, bytes) in before.personalFiles) {
                assertArrayEquals("$message: $name", bytes, personalFiles[name])
            }
            if (before.journal == null) assertNull("$message: journal", journal)
            else assertArrayEquals("$message: journal", before.journal, journal)
        }

        /** Rejected input must not even recover a journal or open a store. */
        fun assertUnchanged(message: String, before: Snapshot) {
            assertContentEquals(message, before)
            assertEquals("$message: journal reads", before.recoveryReads, recoveryReads)
            assertEquals("$message: store opens", before.storeOpens, storeOpens)
            assertEquals("$message: journal writes", before.journalWrites, journalWrites)
            assertEquals("$message: applies", before.applies, applies)
        }
    }

    private class Snapshot(
        val preferences: Map<String, Any?>,
        val personalFiles: Map<String, ByteArray>,
        val journal: ByteArray?,
        val recoveryReads: Int,
        val storeOpens: Int,
        val journalWrites: Int,
        val applies: Int,
    )

    companion object {
        private const val PASSWORD = "юникод-safe-пароль"

        private fun ByteArray.changed(index: Int): ByteArray = copyOf().also {
            it[index] = (it[index].toInt() xor 1).toByte()
        }
    }
}
