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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaryStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalMutationOutcome
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The two transfer directions over a [BackupTarget]: export → import round-trips byte-equal into
 * a fresh tree, a restore replaces (a local file absent from the archive is deleted), the internal
 * preference keys never cross, a corrupt local file is not exported, and — the fail-closed
 * property — an invalid archive writes nothing at all.
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

        val archive = BackupTransfer.export(source)

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
        val archive = BackupTransfer.export(source)

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
        val archive = BackupTransfer.export(source)

        val destination = StoreBackedTarget(temporaryFolder.newFolder("destination"))
        BackupTestStores.wordStore(destination.directory, "ru").addManually("китап")
        val stale = File(destination.directory, TpersFormat.personalFileName("ru"))
        assertTrue(stale.isFile)

        assertEquals(BackupImportResult.IMPORTED, BackupTransfer.import(destination, archive))
        assertFalse("a local file with no counterpart in the archive is deleted", stale.exists())
    }

    @Test
    fun exportSkipsACorruptLocalFile() {
        val source = StoreBackedTarget(temporaryFolder.newFolder("source"))
        BackupTestStores.wordStore(source.directory, "tt_RU").addManually("абыйлар")
        // A damaged Russian words file: the store would quarantine it on open; export skips it, so
        // the archive stays importable.
        File(source.directory, TpersFormat.personalFileName("ru")).writeBytes(ByteArray(100) { 7 })

        val archive = BackupTransfer.export(source)
        val destination = StoreBackedTarget(temporaryFolder.newFolder("destination"))
        assertEquals(BackupImportResult.IMPORTED, BackupTransfer.import(destination, archive))
        assertEquals(listOf(TpersFormat.personalFileName("tt_RU")), destination.storeFileNames())
    }

    // ---- fail closed ------------------------------------------------------------------------------

    @Test
    fun invalidArchivesWriteNothing() {
        val source = StoreBackedTarget(temporaryFolder.newFolder("source"))
        BackupTestStores.wordStore(source.directory, "tt_RU").addManually("абыйлар")
        val valid = BackupTransfer.export(source)

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
        val archive = BackupTransfer.export(source)

        val destination = StoreBackedTarget(temporaryFolder.newFolder("destination"))
        destination.failWrites = true
        assertEquals(BackupImportResult.WRITE_FAILED, BackupTransfer.import(destination, archive))
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
        var failWrites = false

        override fun readPreferences(): Map<String, Any?> = HashMap(preferences)

        override fun personalFileNames(): List<String> = directory.list()?.toList() ?: emptyList()

        override fun readPersonalFile(fileName: String): ByteArray? =
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
            return succeeded
        }

        override fun replacePreferences(values: Map<String, Any?>): Boolean {
            if (failWrites) return false
            preferences.clear()
            preferences.putAll(values)
            return true
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
