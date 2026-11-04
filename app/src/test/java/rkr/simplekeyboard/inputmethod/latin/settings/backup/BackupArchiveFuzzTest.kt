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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.SeededFuzzHarness
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Seeded fuzzing of [BackupArchive.readAndVerify], the gate over the user-picked document. See
 * [SeededFuzzHarness].
 *
 * The base image is a real archive over every personal kind. The zip reader streams local entries
 * and never consults the central directory, so mutations landing only in the ignored tail can
 * legitimately validate; those shapes assert only that nothing but [BackupValidationException]
 * escapes. A personal payload corrupted past a refreshed manifest checksum must never validate:
 * the store validators are the inner gate.
 */
class BackupArchiveFuzzTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val baseArchive: ByteArray by lazy { buildFixtureArchive() }

    @Test
    fun baseFixtureRoundTrips() {
        val verified = BackupArchive.readAndVerify(baseArchive)
        assertEquals(FIXTURE_PREFERENCES, verified.preferences)
        assertEquals(5, verified.personalFiles.size)
    }

    @Test
    fun fuzzBitFlipsThrowOnlyTheFormatException() {
        val random = Random(SEED_BIT_FLIPS)
        repeat(2_000) { iteration ->
            val mutated = SeededFuzzHarness.flipBits(random, baseArchive)
            // A flip in bytes the stream reader ignores (the central directory, the END record,
            // unchecked header fields) legitimately validates.
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "bit-flips", SEED_BIT_FLIPS, iteration, mutated.detail,
                BackupValidationException::class.java, mustReject = false,
            ) {
                BackupArchive.readAndVerify(mutated.bytes)
            }
        }
    }

    @Test
    fun fuzzTruncationsThrowOnlyTheFormatException() {
        val random = Random(SEED_TRUNCATIONS)
        repeat(1_500) { iteration ->
            val mutated = SeededFuzzHarness.truncate(random, baseArchive)
            // A prefix cut after the last local entry is a complete stream to the reader.
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "truncations", SEED_TRUNCATIONS, iteration, mutated.detail,
                BackupValidationException::class.java, mustReject = false,
            ) {
                BackupArchive.readAndVerify(mutated.bytes)
            }
        }
    }

    @Test
    fun fuzzSizeFieldInflationStaysUnderTheCaps() {
        val fieldOffsets = zipCountAndSizeFieldOffsets(baseArchive)
        val random = Random(SEED_INFLATION)
        repeat(1_500) { iteration ->
            val mutated = SeededFuzzHarness.inflateFields(random, baseArchive, fieldOffsets)
            // The caps are measured on the stream, never on these fields: an inflated field may
            // break the parse, but it must never drive an allocation or an index.
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "inflation", SEED_INFLATION, iteration, mutated.detail,
                BackupValidationException::class.java, mustReject = false,
            ) {
                BackupArchive.readAndVerify(mutated.bytes)
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
                BackupArchive.readAndVerify(mutated.bytes)
            }
        }
    }

    @Test
    fun fuzzEntryPayloadCorruptionWithRefreshedChecksums() {
        val random = Random(SEED_PAYLOAD_CORRUPTION)
        repeat(2_500) { iteration ->
            val entries = unzipEntries(baseArchive)
            val personalPaths = entries.keys.filter {
                BackupFormat.classifyEntryPath(it) is BackupFormat.EntryPath.Personal
            }
            // A personal payload must fail its store's whole-file checksum; a corrupted settings
            // or manifest document can legitimately stay parseable (an informational field, a
            // dropped key), so only the personal arm pins rejection.
            val integrityProtected = random.nextInt(3) != 0
            val target = when {
                integrityProtected -> personalPaths[random.nextInt(personalPaths.size)]
                random.nextBoolean() -> BackupFormat.SETTINGS_PATH
                else -> BackupFormat.MANIFEST_PATH
            }
            val corrupted = SeededFuzzHarness.corruptBytes(random, entries.getValue(target))
            entries[target] = corrupted.bytes
            val mutated = if (target == BackupFormat.MANIFEST_PATH) {
                rezipKeepingManifest(entries) // the manifest lists no checksum of itself
            } else {
                rezipWithRefreshedManifest(entries)
            }
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "payload-corruption", SEED_PAYLOAD_CORRUPTION, iteration,
                "entry=$target ${corrupted.detail}",
                BackupValidationException::class.java, mustReject = integrityProtected,
            ) {
                BackupArchive.readAndVerify(mutated)
            }
        }
    }

    @Test
    fun fuzzNonCanonicalPathsNeverValidate() {
        val random = Random(SEED_PATHS)
        repeat(1_500) { iteration ->
            val entries = unzipEntries(baseArchive)
            val case = random.nextInt(PATH_CASES)
            val keys = entries.keys.toList()
            val path = nonCanonicalPath(case, keys[random.nextInt(keys.size)])
            entries[path] = ByteArray(8).also(random::nextBytes)
            // The manifest is refreshed to match, so the path gates themselves are what fires.
            val mutated = rezipWithRefreshedManifest(entries)
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "non-canonical-path", SEED_PATHS, iteration, "path-case=$case",
                BackupValidationException::class.java, mustReject = true,
            ) {
                BackupArchive.readAndVerify(mutated)
            }
        }
    }

    @Test
    fun fuzzTrailingGarbageThrowsOnlyTheFormatException() {
        val random = Random(SEED_TRAILING_GARBAGE)
        repeat(1_000) { iteration ->
            val mutated = SeededFuzzHarness.appendGarbage(random, baseArchive)
            // Bytes after the END record are never read: the stream stops at the last entry.
            SeededFuzzHarness.assertCleanOrValidationFailure(
                "trailing-garbage", SEED_TRAILING_GARBAGE, iteration, mutated.detail,
                BackupValidationException::class.java, mustReject = false,
            ) {
                BackupArchive.readAndVerify(mutated.bytes)
            }
        }
    }

    /** The valid image: real store bytes of every personal kind under the archive's own writer. */
    private fun buildFixtureArchive(): ByteArray {
        val directory = temporaryFolder.newFolder()
        return BackupArchive.create(
            versionCode = VERSION_CODE,
            preferences = FIXTURE_PREFERENCES,
            personalFiles = listOf(
                VerifiedPersonalFile(BackupFormat.PersonalKind.WORDS, "tt_RU",
                    BackupTestStores.writeWords(directory, "tt_RU",
                        listOf("абыйлар", "бәйрәм", "китап", "сүзлек"))),
                VerifiedPersonalFile(BackupFormat.PersonalKind.PAIRS, "ru",
                    BackupTestStores.writePairs(directory, "ru", "книга" to "читаю")),
                VerifiedPersonalFile(BackupFormat.PersonalKind.EMOJI, "tt_RU",
                    BackupTestStores.writeEmoji(directory, "tt_RU", "бәйрәм" to "🎉")),
                VerifiedPersonalFile(BackupFormat.PersonalKind.REFUSED, "tt_RU",
                    BackupTestStores.writeRefused(directory, "tt_RU", "китәп" to "китап")),
                VerifiedPersonalFile(BackupFormat.PersonalKind.SHORTCUTS, "",
                    BackupTestStores.writeShortcuts(directory, "бб", "бик булды")),
            ),
        )
    }

    /**
     * A path that never classifies: a bad segment or character, a leading or trailing slash, an
     * overlong name, or a clean name of nothing this build stores. Rejects for any [base].
     */
    private fun nonCanonicalPath(case: Int, base: String): String {
        val fileName = base.substringAfterLast('/')
        return when (case) {
            0 -> "personal/../$fileName"
            1 -> "/$base"
            2 -> "$base/"
            3 -> base.substring(0, 1) + "//" + base.substring(1)
            4 -> base.substring(0, 1) + "/./" + base.substring(1)
            5 -> "\\" + base.substring(1)
            6 -> "е" + base.substring(1) // Cyrillic: outside the ASCII path alphabet
            7 -> ":" + base.substring(1)
            8 -> " " + base.substring(1)
            9 -> base.uppercase()
            10 -> "personal/personal-en-s1-f1.tpers" // English has no personal store
            else -> "personal/" + "a".repeat(BackupFormat.MAX_PATH_LENGTH + 1)
        }
    }

    /**
     * The zip container's own count and size fields: per entry the local and central sizes, plus
     * the END record's entry counts, central-directory size and central-directory offset.
     */
    private fun zipCountAndSizeFieldOffsets(zip: ByteArray): IntArray {
        val buffer = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN)
        val end = zip.size - END_RECORD_BYTES
        require(end >= 0 && buffer.getInt(end) == END_SIGNATURE) { "fixture has no END tail" }
        val entryCount = buffer.getShort(end + 10).toInt() and 0xffff
        var central = buffer.getInt(end + 16)
        val offsets = ArrayList<Int>()
        repeat(entryCount) {
            offsets += central + 20 // central compressed size
            offsets += central + 24 // central uncompressed size
            val local = buffer.getInt(central + 42)
            offsets += local + 18 // local compressed size
            offsets += local + 22 // local uncompressed size
            val nameLength = buffer.getShort(central + 28).toInt() and 0xffff
            val extraLength = buffer.getShort(central + 30).toInt() and 0xffff
            val commentLength = buffer.getShort(central + 32).toInt() and 0xffff
            central += CENTRAL_HEADER_BYTES + nameLength + extraLength + commentLength
        }
        offsets += end + 8
        offsets += end + 12
        offsets += end + 16
        return offsets.toIntArray()
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
        val FIXTURE_PREFERENCES: Map<String, Any> =
            mapOf("auto_cap" to true, "pref_enabled_subtypes" to "tt_RU;ru")

        const val CENTRAL_HEADER_BYTES = 46
        const val PATH_CASES = 12
        const val END_SIGNATURE = 0x06054b50
        const val END_RECORD_BYTES = 22
        const val SEED_BIT_FLIPS = 0x5EED_A001L
        const val SEED_TRUNCATIONS = 0x5EED_A002L
        const val SEED_INFLATION = 0x5EED_A003L
        const val SEED_GARBAGE = 0x5EED_A004L
        const val SEED_PAYLOAD_CORRUPTION = 0x5EED_A005L
        const val SEED_PATHS = 0x5EED_A006L
        const val SEED_TRAILING_GARBAGE = 0x5EED_A007L
    }
}
