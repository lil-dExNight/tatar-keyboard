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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaryStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalMutationOutcome
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The two transfer directions over a [BackupTarget]: export → import round-trips byte-equal into
 * a fresh tree, a restore replaces (a local file absent from the archive is deleted), the internal
 * preference keys never cross, an unreadable recognized local file fails the whole export, and
 * an invalid archive writes nothing at all.
 *
 * One leg runs against targets backed by real stores, so the live reload is proven too: after an
 * import the store's published snapshot holds the imported words and not the old ones.
 */
class BackupTransferTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // ---- round trip ------------------------------------------------------------------------------

    @Test
    fun exportThenImportRoundTripsByteEqual() {
        val source = StoreBackedTarget(temporaryFolder.newFolder("source"))
        source.preferences.putAll(mapOf(
            "auto_cap" to true,
            "pref_enabled_subtypes" to "tt_RU;ru",
            "active_restrictions" to linkedSetOf("pref_personal_dictionary"),
        ))
        BackupTestStores.wordStore(source.directory, "tt_RU").addManually("абыйлар")
        BackupTestStores.pairStore(source.directory, "ru").apply {
            repeat(2) { notePair("книга", "читаю") }
        }
        BackupTestStores.emojiStore(source.directory, "tt_RU").apply {
            repeat(2) { noteObservation("бәйрәм", "🎉") }
        }
        BackupTestStores.writeRefused(source.directory, "tt_RU", "китәп" to "китап")
        BackupTestStores.writeShortcuts(source.directory, "бб", "бик булды")

        val archive = BackupTransfer.export(source)!!

        val destination = StoreBackedTarget(temporaryFolder.newFolder("destination"))
        assertEquals(BackupImportResult.IMPORTED, BackupTransfer.import(destination, archive))

        // Every store file crossed byte-equal, and only the store files: the salts and the pending
        // counters are not part of a backup.
        val sourceNames = source.storeFileNames()
        assertTrue(sourceNames.isNotEmpty())
        assertEquals(sourceNames, destination.storeFileNames())
        for (name in sourceNames) {
            assertArrayEquals("file $name must cross byte-equal",
                File(source.directory, name).readBytes(), File(destination.directory, name).readBytes())
        }
        // The settings crossed; the restriction cache did not.
        assertEquals(mapOf("auto_cap" to true, "pref_enabled_subtypes" to "tt_RU;ru"),
            destination.preferences)
    }

    @Test
    fun importPublishesTheImportedWordsLive() {
        val source = StoreBackedTarget(temporaryFolder.newFolder("source"))
        BackupTestStores.wordStore(source.directory, "tt_RU").addManually("абыйлар")
        val archive = BackupTransfer.export(source)!!

        val destination = StoreBackedTarget(temporaryFolder.newFolder("destination"))
        // The destination store is already open and holds another word: the restore must swap the
        // published snapshot, not just the file.
        val liveStore = BackupTestStores.wordStore(destination.directory, "tt_RU")
        liveStore.addManually("сүзлек")
        destination.liveWordStores["tt_RU"] = liveStore

        assertEquals(BackupImportResult.IMPORTED, BackupTransfer.import(destination, archive))

        assertEquals(listOf("абыйлар"), liveStore.snapshot.lookupRawForms("а"))
        assertTrue(liveStore.snapshot.lookupRawForms("с").isEmpty())
    }

    @Test
    fun restoreReplacesFilesAbsentFromTheArchive() {
        val source = StoreBackedTarget(temporaryFolder.newFolder("source"))
        BackupTestStores.wordStore(source.directory, "tt_RU").addManually("абыйлар")
        val archive = BackupTransfer.export(source)!!

        val destination = StoreBackedTarget(temporaryFolder.newFolder("destination"))
        BackupTestStores.wordStore(destination.directory, "ru").addManually("китап")
        val stale = File(destination.directory, TpersFormat.personalFileName("ru"))
        assertTrue(stale.isFile)

        assertEquals(BackupImportResult.IMPORTED, BackupTransfer.import(destination, archive))
        assertFalse("a local file with no counterpart in the archive is deleted", stale.exists())
    }

    @Test
    fun exportFailsInsteadOfSkippingACorruptRecognizedFile() {
        val source = StoreBackedTarget(temporaryFolder.newFolder("source"))
        BackupTestStores.wordStore(source.directory, "tt_RU").addManually("абыйлар")
        File(source.directory, TpersFormat.personalFileName("ru")).writeBytes(ByteArray(100) { 7 })

        assertNull(BackupTransfer.export(source))
    }

    @Test
    fun exportFailsInsteadOfSkippingAnUnreadableRecognizedFile() {
        val source = StoreBackedTarget(temporaryFolder.newFolder("source"))
        BackupTestStores.wordStore(source.directory, "tt_RU").addManually("абыйлар")
        source.failReads = true

        assertNull(BackupTransfer.export(source))
    }

    @Test
    fun importReportsPersonalDictionaryAndPauseLearningChanges() {
        for (key in listOf("pref_personal_dictionary", "pref_incognito_mode")) {
            val source = StoreBackedTarget(temporaryFolder.newFolder("source-$key"))
            source.preferences[key] = true
            val archive = BackupTransfer.export(source)!!
            val destination = StoreBackedTarget(temporaryFolder.newFolder("destination-$key"))
            destination.preferences[key] = false

            assertEquals(key, BackupImportResult.IMPORTED_PRIVACY_CHANGED,
                BackupTransfer.import(destination, archive))
            assertEquals(key, true, destination.preferences[key])
        }
    }

    @Test
    fun importReportsResettingAPrivacySwitchToItsDefault() {
        val source = StoreBackedTarget(temporaryFolder.newFolder("source"))
        val archive = BackupTransfer.export(source)!!
        val destination = StoreBackedTarget(temporaryFolder.newFolder("destination"))
        destination.preferences["pref_incognito_mode"] = true

        assertEquals(BackupImportResult.IMPORTED_PRIVACY_CHANGED,
            BackupTransfer.import(destination, archive))
        assertFalse(destination.preferences.containsKey("pref_incognito_mode"))
    }

    // ---- fail closed ------------------------------------------------------------------------------

    @Test
    fun invalidArchivesWriteNothing() {
        val source = StoreBackedTarget(temporaryFolder.newFolder("source"))
        BackupTestStores.wordStore(source.directory, "tt_RU").addManually("абыйлар")
        val valid = BackupTransfer.export(source)!!

        val variants = mutableListOf<ByteArray>()
        variants += ByteArray(64) { 3 } // not a zip
        variants += valid.copyOf(valid.size / 2) // truncated
        // Structurally fine, but the manifest lists a file the zip does not hold.
        val settings = BackupPreferencesXml.serialize(mapOf("auto_cap" to true))
        val ghost = ByteArray(16)
        variants += zipOf(
            "manifest.json" to BackupManifest(1, BackupFormat.ARCHIVE_PACKAGE, 45,
                listOf(
                    BackupManifest.Entry("settings/preferences.xml", settings.size.toLong(), sha256(settings)),
                    BackupManifest.Entry("personal/personal-tt_RU-s1-f1.tpers", 16, sha256(ghost)),
                )).serialize(),
            "settings/preferences.xml" to settings,
        )
        // A known preference key carrying a wrong value type: the schema gate rejects the archive.
        val wrongType = "<map><string name=\"auto_cap\">x</string></map>".toByteArray()
        variants += zipOf(
            "manifest.json" to BackupManifest(1, BackupFormat.ARCHIVE_PACKAGE, 45,
                listOf(BackupManifest.Entry("settings/preferences.xml",
                    wrongType.size.toLong(), sha256(wrongType)))).serialize(),
            "settings/preferences.xml" to wrongType,
        )

        for (variant in variants) {
            val destination = StoreBackedTarget(temporaryFolder.newFolder())
            destination.preferences["auto_cap"] = false
            BackupTestStores.wordStore(destination.directory, "ru").addManually("китап")
            val before = destination.storeFileNames()
                .associateWith { File(destination.directory, it).readBytes() }

            assertEquals(BackupImportResult.INVALID_FILE, BackupTransfer.import(destination, variant))
            assertEquals(mapOf<String, Any?>("auto_cap" to false), destination.preferences)
            for ((name, bytes) in before) {
                assertArrayEquals("nothing may be written: $name",
                    bytes, File(destination.directory, name).readBytes())
            }
        }
    }

    @Test
    fun writeFailureIsReported() {
        val source = StoreBackedTarget(temporaryFolder.newFolder("source"))
        BackupTestStores.wordStore(source.directory, "tt_RU").addManually("абыйлар")
        val archive = BackupTransfer.export(source)!!

        val destination = StoreBackedTarget(temporaryFolder.newFolder("destination"))
        destination.failWrites = true
        assertEquals(BackupImportResult.WRITE_FAILED, BackupTransfer.import(destination, archive))
    }

    @Test
    fun failureAfterEveryCommittedMutationRollsBackTheWholeState() {
        val operationCount = newTransactionFixture("operation-count").operationCount
        for (operation in 1..operationCount) {
            val fixture = newTransactionFixture("failure-$operation")
            val beforeFiles = directorySnapshot(fixture.target.directory)
            val beforePreferences = HashMap(fixture.target.preferences)
            fixture.target.failAfterMutation = operation

            assertEquals("operation $operation", BackupImportResult.WRITE_FAILED,
                BackupTransfer.import(fixture.target, fixture.archive))

            assertEquals("operation $operation", beforePreferences, fixture.target.preferences)
            assertDirectoryState("operation $operation", beforeFiles, fixture.target.directory)
            assertEquals(listOf("китап"),
                fixture.target.liveWordStores.getValue("ru").snapshot.lookupRawForms("к"))
            assertFalse("operation $operation", fixture.target.hasJournal())
        }
    }

    @Test
    fun processDeathAfterEveryCommittedMutationRecoversTheWholeOldState() {
        val operationCount = newTransactionFixture("death-operation-count").operationCount
        for (operation in 1..operationCount) {
            val fixture = newTransactionFixture("death-$operation")
            val beforeFiles = directorySnapshot(fixture.target.directory)
            val beforePreferences = HashMap(fixture.target.preferences)
            fixture.target.crashAfterMutation = operation

            try {
                BackupTransfer.import(fixture.target, fixture.archive)
                throw AssertionError("operation $operation did not inject process death")
            } catch (_: SimulatedProcessDeath) {
            }
            assertTrue("operation $operation", fixture.target.hasJournal())

            val recovered = StoreBackedTarget(fixture.target.directory)
            recovered.preferences.putAll(fixture.target.preferences)
            assertTrue("operation $operation", BackupTransfer.recoverPending(recovered))
            assertEquals("operation $operation", beforePreferences, recovered.preferences)
            assertDirectoryState("operation $operation", beforeFiles, recovered.directory)
            assertFalse("operation $operation", recovered.hasJournal())
        }
    }

    @Test
    fun successfulImportPreservesPendingCountersSaltAndQuarantine() {
        val fixture = newTransactionFixture("preserved-state")
        val before = directorySnapshot(fixture.target.directory)
        val preserved = before.filterKeys {
            BackupFormat.classifyPersonalFileName(it) == null
        }
        assertTrue(preserved.keys.any { it.startsWith("pending-") })
        assertTrue(preserved.keys.any { it.startsWith("salt-") })
        assertTrue(preserved.keys.any { it.endsWith(".quarantine") })

        assertEquals(BackupImportResult.IMPORTED,
            BackupTransfer.import(fixture.target, fixture.archive))

        val after = directorySnapshot(fixture.target.directory)
        for ((name, bytes) in preserved) {
            assertArrayEquals(name, bytes, after[name])
        }
    }

    @Test
    fun anUnreadableCurrentStorePreventsJournalingAndMutation() {
        val source = StoreBackedTarget(temporaryFolder.newFolder("snapshot-source"))
        BackupTestStores.wordStore(source.directory, "tt_RU").addManually("абыйлар")
        val archive = BackupTransfer.export(source)!!
        val destination = StoreBackedTarget(temporaryFolder.newFolder("snapshot-destination"))
        BackupTestStores.wordStore(destination.directory, "ru").addManually("китап")
        val before = directorySnapshot(destination.directory)
        destination.failReads = true

        assertEquals(BackupImportResult.WRITE_FAILED, BackupTransfer.import(destination, archive))
        assertDirectoryState("unreadable current store", before, destination.directory)
        assertFalse(destination.hasJournal())
    }

    private class TransactionFixture(
        val archive: ByteArray,
        val target: StoreBackedTarget,
        val operationCount: Int,
    )

    private class SimulatedProcessDeath : Error()

    /** Covers replacements, deletions and the final preference commit over real store owners. */
    private fun newTransactionFixture(name: String): TransactionFixture {
        val source = StoreBackedTarget(temporaryFolder.newFolder("$name-source"))
        source.preferences["auto_cap"] = true
        BackupTestStores.wordStore(source.directory, "tt_RU").addManually("абыйлар")
        BackupTestStores.pairStore(source.directory, "ru").apply {
            repeat(2) { notePair("книга", "читаю") }
        }
        BackupTestStores.writeShortcuts(source.directory, "бб", "бик булды")
        val archive = BackupTransfer.export(source)!!

        val destination = StoreBackedTarget(temporaryFolder.newFolder("$name-destination"))
        destination.preferences.putAll(mapOf(
            "auto_cap" to false,
            "legacy_setting" to "keep me",
            "active_restrictions" to linkedSetOf("pref_personal_dictionary"),
        ))
        val oldWordStore = BackupTestStores.wordStore(destination.directory, "ru")
        oldWordStore.addManually("китап")
        destination.liveWordStores["ru"] = oldWordStore
        BackupTestStores.emojiStore(destination.directory, "tt_RU").apply {
            repeat(2) { noteObservation("бәйрәм", "🎉") }
        }
        val pendingStore = BackupTestStores.wordStore(destination.directory, "tt_RU")
        pendingStore.noteCompletion("китапханә")
        pendingStore.noteCompletion("китапханә")
        pendingStore.flush()
        destination.liveWordStores["tt_RU"] = pendingStore
        File(destination.directory, TpersFormat.personalFileName("ru") + ".quarantine")
            .writeBytes(byteArrayOf(9, 8, 7))

        val operationCount = (source.storeFileNames() + destination.storeFileNames()).toSet().size + 1
        return TransactionFixture(archive, destination, operationCount)
    }

    private fun directorySnapshot(directory: File): Map<String, ByteArray> =
        directory.listFiles().orEmpty().filter { it.isFile }
            .associate { it.name to it.readBytes() }

    private fun assertDirectoryState(message: String, expected: Map<String, ByteArray>, directory: File) {
        val actual = directorySnapshot(directory)
        assertEquals(message, expected.keys, actual.keys)
        for ((name, bytes) in expected) {
            assertArrayEquals("$message: $name", bytes, actual[name])
        }
    }

    // ---- the target doubles -----------------------------------------------------------------------

    /**
     * A [BackupTarget] over a temp directory. Personal writes go through real stores created on
     * demand, so their write sequence and reload run for real; the preference map is a plain map.
     */
    private class StoreBackedTarget(
        val directory: File,
        override val versionCode: Int = 45,
    ) : BackupTarget {
        val preferences = LinkedHashMap<String, Any?>()
        val liveWordStores = HashMap<String, PersonalDictionaryStore>()
        var failReads = false
        var failWrites = false
        var failAfterMutation: Int? = null
        var crashAfterMutation: Int? = null
        private var mutationCount = 0
        private val journalFile = File(directory.parentFile, "${directory.name}.journal")

        override fun readPreferences(): Map<String, Any?> = HashMap(preferences)

        override fun personalFileNames(): List<String> = directory.list()?.toList() ?: emptyList()

        override fun readPersonalFile(fileName: String): ByteArray? =
            if (failReads) null else
                File(directory, fileName).let { if (it.isFile) it.readBytes() else null }

        override fun replacePersonalFile(fileName: String, bytes: ByteArray?): Boolean {
            if (failWrites) return false
            val (kind, subtypeId) = BackupFormat.classifyPersonalFileName(fileName) ?: return false
            var succeeded = false
            val outcome = PersonalMutationOutcome { ok -> succeeded = ok }
            when (kind) {
                BackupFormat.PersonalKind.WORDS ->
                    liveWordStores.getOrPut(subtypeId) {
                        BackupTestStores.wordStore(directory, subtypeId)
                    }.replaceAll(bytes, outcome)
                BackupFormat.PersonalKind.PAIRS ->
                    BackupTestStores.pairStore(directory, subtypeId).replaceAll(bytes, outcome)
                BackupFormat.PersonalKind.EMOJI ->
                    BackupTestStores.emojiStore(directory, subtypeId).replaceAll(bytes, outcome)
                BackupFormat.PersonalKind.REFUSED ->
                    BackupTestStores.refusedStore(directory, subtypeId).replaceAll(bytes, outcome)
                BackupFormat.PersonalKind.SHORTCUTS ->
                    BackupTestStores.shortcutStore(directory).replaceAll(bytes, outcome)
            }
            if (succeeded) mutationCommitted()
            return succeeded
        }

        override fun replacePreferences(values: Map<String, Any?>): Boolean {
            if (failWrites) return false
            preferences.clear()
            preferences.putAll(values)
            mutationCommitted()
            return true
        }

        override fun readImportJournal(): ByteArray? =
            if (journalFile.isFile) journalFile.readBytes() else null

        override fun writeImportJournal(bytes: ByteArray): Boolean {
            journalFile.writeBytes(bytes)
            return true
        }

        override fun clearImportJournal(): Boolean =
            !journalFile.exists() || journalFile.delete()

        fun hasJournal(): Boolean = journalFile.exists()

        private fun mutationCommitted() {
            mutationCount++
            if (mutationCount == failAfterMutation) {
                failAfterMutation = null
                throw IOException("injected mutation failure")
            }
            if (mutationCount == crashAfterMutation) {
                crashAfterMutation = null
                throw SimulatedProcessDeath()
            }
        }

        /** The personal store file names present, in sorted order. */
        fun storeFileNames(): List<String> = personalFileNames()
            .filter { BackupFormat.classifyPersonalFileName(it) != null }
            .sorted()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, bytes) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
