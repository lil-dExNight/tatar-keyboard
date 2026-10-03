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
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The archive gate: a self-made archive round-trips, and every deviation — a zip-slip path, a
 * duplicate, a manifest mismatch, a wrong hash, a damaged zip, an oversized entry, an invalid
 * personal file — is rejected. The fail-closed consequence (nothing written) is pinned in
 * `BackupTransferTest`; here the rejection itself must fire.
 */
class BackupArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val preferences = mapOf<String, Any?>("auto_cap" to true, "pref_enabled_subtypes" to "tt_RU;ru")

    // ---- round trip -----------------------------------------------------------------------------

    @Test
    fun createThenVerifyRoundTrips() {
        val directory = temporaryFolder.newFolder()
        val words = BackupTestStores.writeWords(directory, "tt_RU", listOf("абыйлар", "сүзлек"))
        val pairs = BackupTestStores.writePairs(directory, "ru", "книга" to "читаю")
        val emoji = BackupTestStores.writeEmoji(directory, "tt_RU", "бәйрәм" to "🎉")

        val archive = BackupArchive.create(
            versionCode = 45,
            preferences = preferences,
            personalFiles = listOf(
                VerifiedPersonalFile(BackupFormat.PersonalKind.WORDS, "tt_RU", words),
                VerifiedPersonalFile(BackupFormat.PersonalKind.PAIRS, "ru", pairs),
                VerifiedPersonalFile(BackupFormat.PersonalKind.EMOJI, "tt_RU", emoji),
            ),
        )
        val verified = BackupArchive.readAndVerify(archive)
        assertEquals(preferences, verified.preferences)
        val byPath = verified.personalFiles.associateBy {
            BackupFormat.personalEntryPath(it.kind, it.subtypeId)
        }
        assertEquals(3, byPath.size)
        assertArrayEquals(words, byPath.getValue("personal/personal-tt_RU-s1-f1.tpers").bytes)
        assertArrayEquals(pairs, byPath.getValue("personal/personal-bigrams-ru-s1-f1.tpersb").bytes)
        assertArrayEquals(emoji, byPath.getValue("personal/personal-emoji-tt_RU-s1-f1.tpersem").bytes)
    }

    @Test
    fun createIsDeterministic() {
        val directory = temporaryFolder.newFolder()
        val words = BackupTestStores.writeWords(directory, "tt_RU", listOf("абыйлар"))
        val files = listOf(VerifiedPersonalFile(BackupFormat.PersonalKind.WORDS, "tt_RU", words))
        val first = BackupArchive.create(45, preferences, files)
        val second = BackupArchive.create(45, preferences, files)
        assertArrayEquals("equal inputs must give equal archive bytes", first, second)
    }

    // ---- path and structure rejections ----------------------------------------------------------

    @Test
    fun rejectsParentTraversal() {
        assertRejectedZipEntry("personal/../../evil.tpers")
    }

    @Test
    fun rejectsAbsolutePath() {
        assertRejectedZipEntry("/settings/preferences.xml")
    }

    @Test
    fun rejectsBackslashPath() {
        assertRejectedZipEntry("personal\\..\\evil.tpers")
    }

    @Test
    fun rejectsNulInPath() {
        assertRejectedZipEntry("settings/preferences.xml\u0000")
    }

    @Test
    fun rejectsEmptySegment() {
        assertRejectedZipEntry("personal//x.tpers")
    }

    @Test
    fun rejectsDotSegments() {
        assertRejectedZipEntry("personal/./personal-tt_RU-s1-f1.tpers")
    }

    @Test
    fun rejectsUnknownPath() {
        assertRejectedZipEntry("unknown/file.txt")
    }

    @Test
    fun rejectsUnknownSubtypeFile() {
        // English has no personal store; its file name is not a known path.
        assertRejectedZipEntry("personal/personal-en-s1-f1.tpers")
    }

    @Test
    fun rejectsDuplicateEntries() {
        // ZipOutputStream refuses duplicate names, so the decoy entry is written under a different
        // same-length name and patched into a duplicate afterwards (header and central directory).
        val settings = BackupPreferencesXml.serialize(preferences)
        val decoy = "settings/preferences.xmX"
        val archive = zipOf(
            "settings/preferences.xml" to settings,
            decoy to settings,
        )
        assertRejected(replaceBytes(archive, decoy.toByteArray(Charsets.UTF_8),
            "settings/preferences.xml".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun rejectsMissingManifest() {
        val archive = zipOf("settings/preferences.xml" to BackupPreferencesXml.serialize(preferences))
        assertRejected(archive)
    }

    @Test
    fun rejectsZipEntryMissingFromManifest() {
        val settings = BackupPreferencesXml.serialize(preferences)
        val extra = "manifest listed only" // never reaches the store: the set mismatch fires first
        val archive = zipOf(
            "manifest.json" to manifestBytes(listOf("settings/preferences.xml" to settings)),
            "settings/preferences.xml" to settings,
            "personal/personal-tt_RU-s1-f1.tpers" to extra.toByteArray(),
        )
        assertRejected(archive)
    }

    @Test
    fun rejectsManifestEntryMissingFromZip() {
        val settings = BackupPreferencesXml.serialize(preferences)
        val ghost = ByteArray(16)
        val archive = zipOf(
            "manifest.json" to manifestBytes(listOf(
                "settings/preferences.xml" to settings,
                "personal/personal-tt_RU-s1-f1.tpers" to ghost,
            )),
            "settings/preferences.xml" to settings,
        )
        assertRejected(archive)
    }

    @Test
    fun rejectsChecksumMismatch() {
        val settings = BackupPreferencesXml.serialize(preferences)
        val tampered = settings.copyOf().also { it[it.size - 10] = 'X'.code.toByte() }
        val archive = zipOf(
            "manifest.json" to manifestBytes(listOf("settings/preferences.xml" to settings)),
            "settings/preferences.xml" to tampered,
        )
        assertRejected(archive)
    }

    @Test
    fun rejectsSizeMismatch() {
        val settings = BackupPreferencesXml.serialize(preferences)
        val entry = BackupManifest.Entry("settings/preferences.xml", settings.size + 1L, sha256(settings))
        val manifest = BackupManifest(1, BackupFormat.ARCHIVE_PACKAGE, 45, listOf(entry))
        val archive = zipOf(
            "manifest.json" to manifest.serialize(),
            "settings/preferences.xml" to settings,
        )
        assertRejected(archive)
    }

    @Test
    fun rejectsUnsupportedFormatVersion() {
        val settings = BackupPreferencesXml.serialize(preferences)
        val manifest = BackupManifest(2, BackupFormat.ARCHIVE_PACKAGE, 45,
            listOf(BackupManifest.Entry("settings/preferences.xml", settings.size.toLong(), sha256(settings))))
        val archive = zipOf(
            "manifest.json" to manifest.serialize(),
            "settings/preferences.xml" to settings,
        )
        assertRejected(archive)
    }

    @Test
    fun rejectsForeignPackage() {
        val settings = BackupPreferencesXml.serialize(preferences)
        val manifest = BackupManifest(1, "org.example.other", 45,
            listOf(BackupManifest.Entry("settings/preferences.xml", settings.size.toLong(), sha256(settings))))
        val archive = zipOf(
            "manifest.json" to manifest.serialize(),
            "settings/preferences.xml" to settings,
        )
        assertRejected(archive)
    }

    @Test
    fun rejectsMalformedManifest() {
        val settings = BackupPreferencesXml.serialize(preferences)
        val archive = zipOf(
            "manifest.json" to "{not json".toByteArray(),
            "settings/preferences.xml" to settings,
        )
        assertRejected(archive)
    }

    @Test
    fun rejectsMalformedSettingsDocument() {
        val broken = "<map><int name=\"a\" value=\"x\" /></map>".toByteArray()
        val archive = zipOf(
            "manifest.json" to manifestBytes(listOf("settings/preferences.xml" to broken)),
            "settings/preferences.xml" to broken,
        )
        assertRejected(archive)
    }

    @Test
    fun rejectsInvalidPersonalFileContent() {
        // The hash and size match: only the store's own validator can catch this, and it must run.
        val garbage = ByteArray(128) { 0x55 }
        val archive = zipOf(
            "manifest.json" to manifestBytes(listOf(
                "settings/preferences.xml" to BackupPreferencesXml.serialize(preferences),
                "personal/personal-tt_RU-s1-f1.tpers" to garbage,
            )),
            "settings/preferences.xml" to BackupPreferencesXml.serialize(preferences),
            "personal/personal-tt_RU-s1-f1.tpers" to garbage,
        )
        assertRejected(archive)
    }

    // ---- container damage -----------------------------------------------------------------------

    @Test
    fun rejectsNonZipBytes() {
        assertRejected(ByteArray(256) { it.toByte() })
    }

    @Test
    fun rejectsTruncatedArchive() {
        val archive = BackupArchive.create(45, preferences, emptyList())
        assertRejected(archive.copyOf(archive.size / 2))
    }

    @Test
    fun rejectsOversizeArchive() {
        assertRejected(ByteArray(BackupFormat.MAX_ARCHIVE_BYTES.toInt() + 1))
    }

    @Test
    fun rejectsOversizeEntry() {
        // The settings document over its cap, deflated well under the archive cap.
        val big = ByteArray(BackupFormat.MAX_SETTINGS_BYTES.toInt() + 1) { ' '.code.toByte() }
        val archive = zipOf("settings/preferences.xml" to big)
        assertRejected(archive)
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun assertRejectedZipEntry(path: String) {
        val settings = BackupPreferencesXml.serialize(preferences)
        val archive = zipOf(
            "manifest.json" to manifestBytes(listOf("settings/preferences.xml" to settings)),
            "settings/preferences.xml" to settings,
            path to ByteArray(4),
        )
        assertRejected(archive)
    }

    private fun assertRejected(archive: ByteArray) {
        try {
            BackupArchive.readAndVerify(archive)
            fail("must reject the archive")
        } catch (expected: BackupValidationException) {
        }
    }

    private fun manifestBytes(entries: List<Pair<String, ByteArray>>): ByteArray =
        BackupManifest(1, BackupFormat.ARCHIVE_PACKAGE, 45,
            entries.map { (path, bytes) ->
                BackupManifest.Entry(path, bytes.size.toLong(), sha256(bytes))
            }).serialize()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** A zip written without the archive layer's guards, so hostile shapes are expressible. */
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

    /** Every occurrence of [from] replaced by [to]; equal lengths keep all offsets valid. */
    private fun replaceBytes(bytes: ByteArray, from: ByteArray, to: ByteArray): ByteArray {
        require(from.size == to.size)
        val out = bytes.copyOf()
        outer@ for (i in 0..bytes.size - from.size) {
            for (j in from.indices) {
                if (bytes[i + j] != from[j]) continue@outer
            }
            from.indices.forEach { j -> out[i + j] = to[j] }
        }
        return out
    }
}
