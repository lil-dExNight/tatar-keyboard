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

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.SeededFuzzHarness
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Seeded fuzzing of [BackupImportJournal.read]. See [SeededFuzzHarness].
 *
 * The base image is a real journal: two fixture archives in the frame. Bit flips, truncations and
 * trailing garbage can land in zip bytes the stream reader ignores, so those shapes assert only
 * that nothing but [BackupValidationException] escapes; frame-length inflation, random garbage
 * and payload corruption past a refreshed manifest checksum must never validate.
 */
class BackupImportJournalFuzzTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val beforeBackup: VerifiedBackup by lazy {
        val directory = temporaryFolder.newFolder()
        VerifiedBackup(
            preferences = BEFORE_PREFERENCES,
            personalFiles = listOf(
                VerifiedPersonalFile(BackupFormat.PersonalKind.WORDS, "tt_RU",
                    BackupTestStores.writeWords(directory, "tt_RU", listOf("абыйлар", "китап"))),
                VerifiedPersonalFile(BackupFormat.PersonalKind.SHORTCUTS, "",
                    BackupTestStores.writeShortcuts(directory, "бб", "бик булды")),
            ),
        )
    }

    private val afterArchive: ByteArray by lazy {
        val directory = temporaryFolder.newFolder()
        BackupArchive.create(
            versionCode = VERSION_CODE,
            preferences = AFTER_PREFERENCES,
            personalFiles = listOf(
                VerifiedPersonalFile(BackupFormat.PersonalKind.WORDS, "tt_RU",
                    BackupTestStores.writeWords(directory, "tt_RU", listOf("сүзлек"))),
                VerifiedPersonalFile(BackupFormat.PersonalKind.PAIRS, "ru",
                    BackupTestStores.writePairs(directory, "ru", "книга" to "читаю")),
            ),
        )
    }

    private val baseJournal: ByteArray by lazy {
        BackupImportJournal.create(VERSION_CODE, beforeBackup, afterArchive)
    }

    @Test
    fun baseJournalReads() {
        val journal = BackupImportJournal.read(baseJournal)
        assertEquals(beforeBackup.preferences, journal.before.preferences)
        assertEquals(beforeBackup.personalFiles.size, journal.before.personalFiles.size)
        assertEquals(AFTER_PREFERENCES, journal.after.preferences)
        assertEquals(2, journal.after.personalFiles.size)
    }

    @Test
    fun aJournalBeyondTheSizeCapIsRejected() {
        try {
            BackupImportJournal.read(ByteArray(BackupImportJournal.MAX_SERIALIZED_BYTES.toInt() + 1))
            fail("an oversize journal must be rejected")
        } catch (expected: BackupValidationException) {
        }
    }

    @Test
    fun fuzzBitFlipsThrowOnlyTheFormatException() {
        val random = Random(SEED_BIT_FLIPS)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.flipBits(random, baseJournal)
            // A flip in zip bytes the stream reader ignores legitimately validates.
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "bit-flips", SEED_BIT_FLIPS, iteration, mutated.detail,
                BackupValidationException::class.java, mustReject = false,
            ) {
                BackupImportJournal.read(mutated.bytes)
            }
        }
    }

    @Test
    fun fuzzTruncationsThrowOnlyTheFormatException() {
        val random = Random(SEED_TRUNCATIONS)
        repeat(1_500) { iteration ->
            val mutated = SeededFuzzHarness.truncate(random, baseJournal)
            // A cut after the last local entry of the after archive leaves a complete stream.
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "truncations", SEED_TRUNCATIONS, iteration, mutated.detail,
                BackupValidationException::class.java, mustReject = false,
            ) {
                BackupImportJournal.read(mutated.bytes)
            }
        }
    }

    @Test
    fun fuzzFrameLengthInflationNeverValidates() {
        val random = Random(SEED_INFLATION)
        repeat(1_500) { iteration ->
            // The frame length is the only count/size field the journal adds over the archives.
            val mutated = SeededFuzzHarness.inflateFields(random, baseJournal, FRAME_LENGTH_OFFSETS)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "inflation", SEED_INFLATION, iteration, mutated.detail,
                BackupValidationException::class.java, mustReject = true,
            ) {
                BackupImportJournal.read(mutated.bytes)
            }
        }
    }

    @Test
    fun fuzzRandomGarbageNeverValidates() {
        val random = Random(SEED_GARBAGE)
        repeat(1_500) { iteration ->
            val mutated = SeededFuzzHarness.garbage(random, 2_048)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "garbage", SEED_GARBAGE, iteration, mutated.detail,
                BackupValidationException::class.java, mustReject = true,
            ) {
                BackupImportJournal.read(mutated.bytes)
            }
        }
    }

    @Test
    fun fuzzPayloadCorruptionWithRefreshedChecksums() {
        val random = Random(SEED_PAYLOAD_CORRUPTION)
        repeat(2_000) { iteration ->
            val beforeSize = ByteBuffer.wrap(baseJournal).getInt(FRAME_LENGTH_OFFSET)
            val beforeBytes = baseJournal.copyOfRange(HEADER_BYTES, HEADER_BYTES + beforeSize)
            val afterBytes = baseJournal.copyOfRange(HEADER_BYTES + beforeSize, baseJournal.size)
            val corruptBefore = random.nextBoolean()
            val entries = unzipEntries(if (corruptBefore) beforeBytes else afterBytes)
            val personalPaths = entries.keys.filter {
                BackupFormat.classifyEntryPath(it) is BackupFormat.EntryPath.Personal
            }
            // A personal payload must fail its store's whole-file checksum; a corrupted settings
            // or manifest document can legitimately stay parseable.
            val integrityProtected = random.nextInt(3) != 0
            val target = when {
                integrityProtected -> personalPaths[random.nextInt(personalPaths.size)]
                random.nextBoolean() -> BackupFormat.SETTINGS_PATH
                else -> BackupFormat.MANIFEST_PATH
            }
            val corrupted = SeededFuzzHarness.corruptBytes(random, entries.getValue(target))
            entries[target] = corrupted.bytes
            val mutatedArchive = if (target == BackupFormat.MANIFEST_PATH) {
                rezipKeepingManifest(entries) // the manifest lists no checksum of itself
            } else {
                rezipWithRefreshedManifest(entries)
            }
            val mutated = if (corruptBefore) {
                reframeJournal(mutatedArchive, afterBytes)
            } else {
                reframeJournal(beforeBytes, mutatedArchive)
            }
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "payload-corruption", SEED_PAYLOAD_CORRUPTION, iteration,
                "half=${if (corruptBefore) "before" else "after"} entry=$target ${corrupted.detail}",
                BackupValidationException::class.java, mustReject = integrityProtected,
            ) {
                BackupImportJournal.read(mutated)
            }
        }
    }

    @Test
    fun fuzzTrailingGarbageThrowsOnlyTheFormatException() {
        val random = Random(SEED_TRAILING_GARBAGE)
        repeat(1_000) { iteration ->
            // The frame is length-prefixed, so a suffix lands inside the after archive, whose
            // stream reader never reaches past the last local entry.
            val mutated = SeededFuzzHarness.appendGarbage(random, baseJournal)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "trailing-garbage", SEED_TRAILING_GARBAGE, iteration, mutated.detail,
                BackupValidationException::class.java, mustReject = false,
            ) {
                BackupImportJournal.read(mutated.bytes)
            }
        }
    }

    /** The frame rebuilt around new halves; the magic bytes come from the fixture itself. */
    private fun reframeJournal(beforeBytes: ByteArray, afterBytes: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(HEADER_BYTES + beforeBytes.size + afterBytes.size)
        out.put(baseJournal.copyOfRange(0, MAGIC_BYTES))
        out.putInt(beforeBytes.size)
        out.put(beforeBytes)
        out.put(afterBytes)
        return out.array()
    }

    /** Every entry of [archive] in stream order, read without the archive layer's gates. */
    private fun unzipEntries(archive: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    /** The entry set re-zipped with the manifest regenerated, so payload edits pass the hash gate. */
    private fun rezipWithRefreshedManifest(entries: Map<String, ByteArray>): ByteArray {
        val payloads = entries.filterKeys { it != BackupFormat.MANIFEST_PATH }
        val manifest = BackupManifest(
            format = BackupFormat.FORMAT_VERSION.toLong(),
            packageName = BackupFormat.ARCHIVE_PACKAGE,
            versionCode = VERSION_CODE.toLong(),
            entries = payloads.map { (path, bytes) ->
                BackupManifest.Entry(path, bytes.size.toLong(), sha256Hex(bytes))
            },
        )
        return zipOf(listOf(BackupFormat.MANIFEST_PATH to manifest.serialize()) +
            payloads.entries.sortedBy { it.key }.map { it.key to it.value })
    }

    /** The entry set re-zipped unchanged: the manifest entry keeps its possibly corrupted bytes. */
    private fun rezipKeepingManifest(entries: Map<String, ByteArray>): ByteArray =
        zipOf(listOf(BackupFormat.MANIFEST_PATH to entries.getValue(BackupFormat.MANIFEST_PATH)) +
            entries.entries.filter { it.key != BackupFormat.MANIFEST_PATH }
                .sortedBy { it.key }.map { it.key to it.value })

    /** A zip written without the archive layer's guards, so hostile shapes are expressible. */
    private fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
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

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val VERSION_CODE = 45
        val BEFORE_PREFERENCES: Map<String, Any> =
            mapOf("auto_cap" to true, "pref_enabled_subtypes" to "tt_RU;ru")
        val AFTER_PREFERENCES: Map<String, Any> =
            mapOf("auto_cap" to false, "pref_enabled_subtypes" to "tt_RU")

        /** The journal frame layout: 5 magic bytes, the big-endian u32 before-archive length. */
        const val MAGIC_BYTES = 5
        const val FRAME_LENGTH_OFFSET = 5
        const val HEADER_BYTES = 9
        val FRAME_LENGTH_OFFSETS = intArrayOf(FRAME_LENGTH_OFFSET)

        const val SEED_BIT_FLIPS = 0x5EED_B001L
        const val SEED_TRUNCATIONS = 0x5EED_B002L
        const val SEED_INFLATION = 0x5EED_B003L
        const val SEED_GARBAGE = 0x5EED_B004L
        const val SEED_PAYLOAD_CORRUPTION = 0x5EED_B005L
        const val SEED_TRAILING_GARBAGE = 0x5EED_B006L
    }
}
