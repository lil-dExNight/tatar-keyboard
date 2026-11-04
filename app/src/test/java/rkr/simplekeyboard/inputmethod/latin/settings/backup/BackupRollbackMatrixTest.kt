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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import java.nio.ByteBuffer

/**
 * The rollback matrix of [BackupTransfer] over a fake device: process death before and after
 * every store apply, a store refusing at every apply boundary, journals that fail validation, a
 * refused journal write, a failed commit delete and an erase-all with a lingering journal. Every
 * row must end in the old state with no journal left. [BackupCoordinator] only wires this seam
 * to the device, so the matrix runs on the JVM.
 */
class BackupRollbackMatrixTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun theFixtureImportCommitsCleanly() {
        val fixture = newMatrixFixture("control")
        assertEquals(BackupImportResult.IMPORTED,
            BackupTransfer.import(FakeBackupTarget(fixture.device), fixture.archive))
        assertNull(fixture.device.journal)
        assertEquals(NEW_PREFERENCES, fixture.device.preferences)
        assertEquals(
            listOf("personal-bigrams-ru-s1-f1.tpersb", "personal-tt_RU-s1-f1.tpers",
                "shortcuts-s1-f1.tcut"),
            fixture.device.personalFiles.keys.sorted(),
        )
    }

    // ---- interrupted imports --------------------------------------------------------------------

    @Test
    fun processDeathBeforeAndAfterEveryApplyRecoversTheOldState() {
        val applyCount = countApplyCalls()
        assertTrue("the fixture transaction spans several applies", applyCount >= 2)
        for (deathAfter in 0..applyCount) {
            val fixture = newMatrixFixture("death-$deathAfter")
            val target = FakeBackupTarget(fixture.device)
            target.crashAfterApplies = deathAfter

            try {
                BackupTransfer.import(target, fixture.archive)
                throw AssertionError("death-after-$deathAfter: the import did not die")
            } catch (expected: SimulatedProcessDeath) {
            }
            assertNotNull("death-after-$deathAfter: the journal survives the death",
                fixture.device.journal)

            assertTrue("death-after-$deathAfter",
                BackupTransfer.recoverPending(FakeBackupTarget(fixture.device)))
            assertDeviceState("death-after-$deathAfter", fixture)
            assertNull("death-after-$deathAfter: a complete recovery clears the journal",
                fixture.device.journal)
        }
    }

    @Test
    fun aStoreRefusingAtEveryApplyBoundaryRollsTheWholeImportBack() {
        val applyCount = countApplyCalls()
        assertTrue("the fixture transaction spans several applies", applyCount >= 2)
        for (refusalAt in 1..applyCount) {
            val fixture = newMatrixFixture("refusal-$refusalAt")
            val target = FakeBackupTarget(fixture.device)
            target.refuseApplyAtCall = refusalAt

            assertEquals("refusal-at-$refusalAt", BackupImportResult.WRITE_FAILED,
                BackupTransfer.import(target, fixture.archive))
            assertDeviceState("refusal-at-$refusalAt", fixture)
            assertNull("refusal-at-$refusalAt: a rolled-back import clears its journal",
                fixture.device.journal)
        }
    }

    @Test
    fun aStoreRefusingToOpenBlocksTheImportBeforeAnyMutation() {
        val fixture = newMatrixFixture("open-refusal")
        val target = FakeBackupTarget(fixture.device)
        target.failStoreOpens = true

        assertEquals(BackupImportResult.WRITE_FAILED, BackupTransfer.import(target, fixture.archive))
        assertDeviceState("open-refusal", fixture)
        assertNull("a refused open must block the journal itself", fixture.device.journal)
    }

    // ---- journal failures -----------------------------------------------------------------------

    @Test
    fun aJournalWriteFailureBlocksTheImportBeforeAnyMutation() {
        val fixture = newMatrixFixture("journal-write-refused")
        val target = FakeBackupTarget(fixture.device)
        target.failJournalWrite = true

        assertEquals(BackupImportResult.WRITE_FAILED, BackupTransfer.import(target, fixture.archive))
        assertDeviceState("journal-write-refused", fixture)
        assertNull(fixture.device.journal)
    }

    @Test
    fun aFailedCommitDeleteLeavesAJournalThatRestoresTheOldState() {
        val fixture = newMatrixFixture("commit-clear-refused")
        val target = FakeBackupTarget(fixture.device)
        target.failJournalClear = true

        assertEquals("the commit delete fails after a complete apply",
            BackupImportResult.WRITE_FAILED, BackupTransfer.import(target, fixture.archive))
        assertNotNull("the journal survives the failed delete", fixture.device.journal)

        assertTrue(BackupTransfer.recoverPending(FakeBackupTarget(fixture.device)))
        assertDeviceState("commit-clear-refused", fixture)
        assertNull(fixture.device.journal)
    }

    @Test
    fun aJournalFailingValidationIsDiscardedWithoutTouchingTheDevice() {
        val variants = mutableListOf<Pair<String, ByteArray>>()
        variants += "random-bytes" to ByteArray(64) { it.toByte() }
        variants += "bad-magic" to wellFramedJournal("magic").also {
            it[0] = (it[0].toInt() xor 0xff).toByte()
        }
        // The frame's before-length no longer fits: structurally impossible, never applicable.
        variants += "truncated-frame" to wellFramedJournal("truncated")
            .copyOf(JOURNAL_HEADER_BYTES + 10)
        variants += "corrupted-before-archive" to wellFramedJournal("corrupt-before")
            .also { corruptFirstEntryPayload(it, JOURNAL_HEADER_BYTES) }
        variants += "corrupted-after-archive" to wellFramedJournal("corrupt-after").also { journal ->
            val beforeSize = ByteBuffer.wrap(journal).getInt(JOURNAL_HEADER_BYTES - 4)
            corruptFirstEntryPayload(journal, JOURNAL_HEADER_BYTES + beforeSize)
        }

        for ((detail, journalBytes) in variants) {
            val fixture = newMatrixFixture("journal-$detail")
            fixture.device.journal = journalBytes

            assertTrue(detail, BackupTransfer.recoverPending(FakeBackupTarget(fixture.device)))
            assertNull("$detail: a journal that can never be applied is deleted",
                fixture.device.journal)
            assertDeviceState(detail, fixture)

            assertEquals("$detail: a discarded journal blocks no later import",
                BackupImportResult.IMPORTED,
                BackupTransfer.import(FakeBackupTarget(fixture.device), fixture.archive))
        }
    }

    // ---- erase-all ------------------------------------------------------------------------------

    @Test
    fun eraseAllWithALingeringJournalStaysErased() {
        val fixture = newMatrixFixture("erase")
        val dying = FakeBackupTarget(fixture.device)
        dying.crashAfterApplies = 2
        try {
            BackupTransfer.import(dying, fixture.archive)
            throw AssertionError("the import did not die")
        } catch (expected: SimulatedProcessDeath) {
        }
        assertNotNull(fixture.device.journal)

        // "Erase everything saved": the journal goes first, then every store file and preference.
        assertTrue(BackupTransfer.discardPending(FakeBackupTarget(fixture.device)))
        fixture.device.personalFiles.clear()
        fixture.device.preferences.clear()

        assertTrue("recovery after the erase finds nothing to restore",
            BackupTransfer.recoverPending(FakeBackupTarget(fixture.device)))
        assertTrue(fixture.device.personalFiles.isEmpty())
        assertTrue(fixture.device.preferences.isEmpty())
        assertNull(fixture.device.journal)
    }

    // ---- fixture --------------------------------------------------------------------------------

    /** The number of mutation calls one import of the fixture makes: files, then preferences. */
    private fun countApplyCalls(): Int {
        val fixture = newMatrixFixture("apply-count")
        val target = FakeBackupTarget(fixture.device)
        assertEquals(BackupImportResult.IMPORTED, BackupTransfer.import(target, fixture.archive))
        return target.applyCalls
    }

    private class MatrixFixture(
        val archive: ByteArray,
        val device: FakeDevice,
        val oldPreferences: Map<String, Any?>,
        val oldFiles: Map<String, ByteArray>,
    )

    /**
     * The device holds an old words and an old emoji file; the archive brings another language's
     * words, a pairs file and the shortcuts: replacements, deletions, insertions, preference commit.
     */
    private fun newMatrixFixture(name: String): MatrixFixture {
        val directory = temporaryFolder.newFolder(name)
        val device = FakeDevice()
        device.preferences.putAll(OLD_PREFERENCES)
        device.personalFiles[TpersFormat.personalFileName("ru")] =
            BackupTestStores.writeWords(directory, "ru", listOf("китап"))
        device.personalFiles[TpersemFormat.personalEmojiFileName("tt_RU")] =
            BackupTestStores.writeEmoji(directory, "tt_RU", "бәйрәм" to "🎉")
        val archive = BackupArchive.create(VERSION_CODE, NEW_PREFERENCES, listOf(
            VerifiedPersonalFile(BackupFormat.PersonalKind.WORDS, "tt_RU",
                BackupTestStores.writeWords(directory, "tt_RU", listOf("абыйлар", "сүзлек"))),
            VerifiedPersonalFile(BackupFormat.PersonalKind.PAIRS, "ru",
                BackupTestStores.writePairs(directory, "ru", "книга" to "читаю")),
            VerifiedPersonalFile(BackupFormat.PersonalKind.SHORTCUTS, "",
                BackupTestStores.writeShortcuts(directory, "бб", "бик булды")),
        ))
        return MatrixFixture(archive, device, LinkedHashMap(device.preferences),
            device.personalFiles.mapValues { it.value.copyOf() })
    }

    /** A real journal over the matrix states, so the corruption variants isolate the frame gates. */
    private fun wellFramedJournal(name: String): ByteArray {
        val fixture = newMatrixFixture(name)
        val before = VerifiedBackup(
            OLD_PREFERENCES,
            fixture.oldFiles.map { (fileName, bytes) ->
                val (kind, subtypeId) = BackupFormat.classifyPersonalFileName(fileName)!!
                VerifiedPersonalFile(kind, subtypeId, bytes)
            },
        )
        return BackupImportJournal.create(VERSION_CODE, before, fixture.archive)
    }

    /** Flips bytes inside the first entry's payload of the archive framed at [archiveOffset]. */
    private fun corruptFirstEntryPayload(journal: ByteArray, archiveOffset: Int) {
        // The manifest is the first entry: fixed local header, then the name, then the payload.
        val data = archiveOffset + ZIP_LOCAL_HEADER_BYTES + BackupFormat.MANIFEST_PATH.length
        for (index in data until data + 4) {
            journal[index] = (journal[index].toInt() xor 0xff).toByte()
        }
    }

    private fun assertDeviceState(message: String, fixture: MatrixFixture) {
        assertEquals("$message: preferences", fixture.oldPreferences, fixture.device.preferences)
        assertEquals("$message: file set", fixture.oldFiles.keys, fixture.device.personalFiles.keys)
        for ((name, bytes) in fixture.oldFiles) {
            assertArrayEquals("$message: $name", bytes, fixture.device.personalFiles[name])
        }
    }

    private class SimulatedProcessDeath : Error()

    /** The mutable state a [FakeBackupTarget] sees: files, preferences and the journal slot. */
    private class FakeDevice {
        val preferences = LinkedHashMap<String, Any?>()
        val personalFiles = LinkedHashMap<String, ByteArray>()
        var journal: ByteArray? = null
    }

    /**
     * A [BackupTarget] over one [FakeDevice] with injectable failures. [crashAfterApplies] strikes
     * at the next mutation boundary after that many committed mutations (the apply count dies
     * before the commit delete); the device survives, so recovery gets a fresh target.
     */
    private class FakeBackupTarget(
        private val device: FakeDevice,
        override val versionCode: Int = VERSION_CODE,
    ) : BackupTarget {
        var crashAfterApplies: Int? = null
        var refuseApplyAtCall: Int? = null
        var failStoreOpens = false
        var failJournalWrite = false
        var failJournalClear = false
        var applyCalls = 0
            private set
        private var committedApplies = 0

        override fun readPreferences(): Map<String, Any?> = HashMap(device.preferences)

        override fun personalFileNames(): List<String> = device.personalFiles.keys.toList()

        override fun readPersonalFile(fileName: String): ByteArray? = device.personalFiles[fileName]

        override fun openPersonalStore(fileName: String): Boolean = !failStoreOpens

        override fun replacePersonalFile(fileName: String, bytes: ByteArray?): Boolean {
            if (!applyBoundary()) return false
            if (bytes == null) device.personalFiles.remove(fileName)
            else device.personalFiles[fileName] = bytes
            committedApplies++
            return true
        }

        override fun replacePreferences(values: Map<String, Any?>): Boolean {
            if (!applyBoundary()) return false
            device.preferences.clear()
            device.preferences.putAll(values)
            committedApplies++
            return true
        }

        override fun readImportJournal(): ByteArray? = device.journal

        override fun writeImportJournal(bytes: ByteArray): Boolean {
            if (failJournalWrite) return false
            device.journal = bytes
            return true
        }

        override fun clearImportJournal(): Boolean {
            // Death can strike between the last apply and the commit delete.
            deathBoundary()
            if (failJournalClear) return false
            device.journal = null
            return true
        }

        /** Counts one apply call; false when this call is the one the matrix row refuses. */
        private fun applyBoundary(): Boolean {
            deathBoundary()
            applyCalls++
            if (applyCalls == refuseApplyAtCall) {
                refuseApplyAtCall = null
                return false
            }
            return true
        }

        private fun deathBoundary() {
            if (committedApplies == crashAfterApplies) {
                crashAfterApplies = null
                throw SimulatedProcessDeath()
            }
        }
    }

    private companion object {
        const val VERSION_CODE = 45
        val OLD_PREFERENCES: Map<String, Any> = mapOf(
            "auto_cap" to false,
            "legacy_setting" to "keep me",
        )
        val NEW_PREFERENCES: Map<String, Any> = mapOf(
            "auto_cap" to true,
            "pref_enabled_subtypes" to "tt_RU;ru",
        )

        /** The journal frame header and the fixed part of a zip local header. */
        const val JOURNAL_HEADER_BYTES = 9
        const val ZIP_LOCAL_HEADER_BYTES = 30
    }
}
