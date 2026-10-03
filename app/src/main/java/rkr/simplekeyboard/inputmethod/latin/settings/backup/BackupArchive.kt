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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Thrown by [BackupArchive.readAndVerify] on any violation. The message is always a constant: it
 * never contains an entry path, a digest or any other archive content.
 */
class BackupValidationException internal constructor(message: String) : Exception(message)

/** One validated personal store file from an archive: its [BackupFormat.PersonalKind], subtype and bytes. */
class VerifiedPersonalFile(
    val kind: BackupFormat.PersonalKind,
    val subtypeId: String,
    val bytes: ByteArray,
)

/** An archive that passed every check: the parsed settings and the validated personal files. */
class VerifiedBackup(
    val preferences: Map<String, Any>,
    val personalFiles: List<VerifiedPersonalFile>,
)

/**
 * The zip container of the backup archive: entry layout, caps and integrity rules of
 * [BackupFormat], over `java.util.zip` on byte arrays, so the whole layer runs in plain JVM tests.
 *
 * [create] is deterministic: entries in a fixed order (manifest first), one fixed timestamp (an
 * export carries no clock information), default deflate. Callers pass content that already passed
 * the store validators; the checks here are `require` guards against programming errors.
 *
 * [readAndVerify] is the whole import gate, fail-closed. In order: archive size cap; per entry a
 * canonical known path, no duplicates, per-kind and total uncompressed size caps read from the
 * actual stream (never from the zip's own size fields); a present, parseable manifest with the
 * supported format version and this app's package; the manifest's entry set exactly equal to the
 * archive's; each entry's size and SHA-256 matching the manifest; the settings document parsing;
 * every personal file passing its store's strict binary validator. Any failure throws
 * [BackupValidationException] and nothing is considered read.
 */
object BackupArchive {

    /** 1980-01-01T00:00:00Z, the earliest instant the zip format holds. */
    private const val FIXED_ENTRY_TIME_MILLIS = 315532800000L

    private const val READ_BUFFER_BYTES = 8 * 1024

    fun create(
        versionCode: Int,
        preferences: Map<String, Any?>,
        personalFiles: List<VerifiedPersonalFile>,
    ): ByteArray {
        val payloads = ArrayList<Pair<String, ByteArray>>()
        val settingsBytes = BackupPreferencesXml.serialize(preferences)
        require(settingsBytes.size.toLong() <= BackupFormat.MAX_SETTINGS_BYTES)
        payloads.add(BackupFormat.SETTINGS_PATH to settingsBytes)
        for (file in personalFiles) {
            val path = BackupFormat.personalEntryPath(file.kind, file.subtypeId)
            require(file.bytes.size.toLong() <= file.kind.maxBytes)
            require(BackupFormat.contentPasses(file.kind, file.subtypeId, file.bytes))
            payloads.add(path to file.bytes)
        }
        require(payloads.size + 1 <= BackupFormat.MAX_ENTRIES)
        require(payloads.map { it.first }.distinct().size == payloads.size)

        val manifest = BackupManifest(
            format = BackupFormat.FORMAT_VERSION.toLong(),
            packageName = BackupFormat.ARCHIVE_PACKAGE,
            versionCode = versionCode.toLong(),
            entries = payloads.map { (path, bytes) ->
                BackupManifest.Entry(path, bytes.size.toLong(), sha256Hex(bytes))
            },
        )
        val ordered = listOf(BackupFormat.MANIFEST_PATH to manifest.serialize()) +
            payloads.sortedBy { it.first }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, bytes) in ordered) {
                val entry = ZipEntry(path)
                entry.time = FIXED_ENTRY_TIME_MILLIS
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Reads and fully verifies [archiveBytes]. See the class doc for the gate list.
     * @throws BackupValidationException on any violation.
     */
    fun readAndVerify(archiveBytes: ByteArray): VerifiedBackup {
        if (archiveBytes.size.toLong() > BackupFormat.MAX_ARCHIVE_BYTES) fail("archive too large")
        val entries = readEntries(archiveBytes)

        val manifestBytes = entries.remove(BackupFormat.MANIFEST_PATH) ?: fail("manifest is missing")
        val manifest = try {
            BackupManifest.parse(manifestBytes)
        } catch (e: BackupJson.JsonException) {
            fail("manifest is invalid")
        }
        if (manifest.format != BackupFormat.FORMAT_VERSION.toLong()) {
            fail("unsupported format version")
        }
        if (manifest.packageName != BackupFormat.ARCHIVE_PACKAGE) fail("not a backup of this app")

        val manifestPaths = manifest.entries.map { it.path }
        if (manifestPaths.distinct().size != manifestPaths.size) fail("duplicate manifest entry")
        if (manifestPaths.toSet() != entries.keys) fail("manifest does not match the archive")
        for (listed in manifest.entries) {
            val bytes = entries.getValue(listed.path)
            if (listed.size != bytes.size.toLong()) fail("entry size mismatch")
            if (listed.sha256 != sha256Hex(bytes)) fail("entry checksum mismatch")
        }

        val settingsBytes = entries.remove(BackupFormat.SETTINGS_PATH)
            ?: fail("settings entry is missing")
        val preferences = try {
            BackupPreferencesXml.parse(settingsBytes)
        } catch (e: BackupPreferencesXml.PreferencesXmlException) {
            fail("settings document is invalid")
        }

        val personalFiles = ArrayList<VerifiedPersonalFile>()
        for ((path, bytes) in entries) {
            val classified = BackupFormat.classifyEntryPath(path)
                as? BackupFormat.EntryPath.Personal ?: fail("unexpected entry")
            if (!BackupFormat.contentPasses(classified.kind, classified.subtypeId, bytes)) {
                fail("personal file failed validation")
            }
            personalFiles.add(VerifiedPersonalFile(classified.kind, classified.subtypeId, bytes))
        }
        return VerifiedBackup(preferences, personalFiles)
    }

    /** Unzips [archiveBytes] with every structural gate applied; the manifest stays in the map. */
    private fun readEntries(archiveBytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var totalUncompressed = 0L
        try {
            ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entries.size >= BackupFormat.MAX_ENTRIES) fail("too many entries")
                    val path = entry.name
                    if (!BackupFormat.isCanonicalEntryPath(path)) fail("entry path is not canonical")
                    val classified = BackupFormat.classifyEntryPath(path) ?: fail("unknown entry path")
                    if (entries.containsKey(path)) fail("duplicate entry")
                    val bytes = readEntryBytes(zip, BackupFormat.maxEntryBytes(classified))
                    entries[path] = bytes
                    totalUncompressed += bytes.size
                    if (totalUncompressed > BackupFormat.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        fail("archive content too large")
                    }
                }
            }
        } catch (exception: Exception) {
            if (exception is BackupValidationException) throw exception
            fail("archive is damaged")
        }
        return entries
    }

    /** One entry's bytes, bounded by [cap]: the zip's own size fields are never trusted. */
    private fun readEntryBytes(zip: ZipInputStream, cap: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size().toLong() > cap) fail("entry too large")
        }
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) out.append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
        return out.toString()
    }

    private fun fail(message: String): Nothing = throw BackupValidationException(message)
}
