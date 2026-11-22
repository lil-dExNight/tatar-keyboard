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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SentStartOverlayTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SentStartOverlayTestFixtures.Entry
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/** Sentence-start damaged-file inspection, restore, discard, notice and delete-after-restore. */
class SentStartQuarantineRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    @Test
    fun aTruncatedFileOffersPartialRecoveryWithoutClaimingItIsWhole() {
        val directory = quarantinedDirectory(dropTailBytes = 5)
        val store = store(directory)
        store.prime()

        val report = inspect(store)
        assertNotNull(report)
        assertEquals(2, report!!.wordCount)
        assertFalse(report.readToEnd)

        val outcomes = mutableListOf<Boolean>()
        store.restoreQuarantine { outcomes.add(it) }

        assertEquals(listOf(true), outcomes)
        assertEquals(setOf("абыйлар", "бабай"), store.snapshot.topWords().toSet())
        assertEquals(
            listOf("абыйлар", "бабай"),
            TsentValidator().validate(overlayFile(directory), subtype).rawForms,
        )
        assertTrue("the damaged tail remains available to a better reader", quarantineFile(directory).isFile)
    }

    @Test
    fun aChecksumDamagedFileRestoresEveryEntryOnceAndCanBeDiscarded() {
        val directory = quarantinedDirectory()
        val store = store(directory)
        store.prime()
        assertTrue(store.snapshot.isEmpty)

        store.restoreQuarantine()
        val afterFirst = TsentValidator().validate(overlayFile(directory), subtype).rawForms
        val second = mutableListOf<Boolean>()
        store.restoreQuarantine { second.add(it) }

        assertEquals(listOf(true), second)
        assertEquals(setOf("абыйлар", "бабай", "гүзәл"), store.snapshot.topWords().toSet())
        assertEquals(afterFirst, TsentValidator().validate(overlayFile(directory), subtype).rawForms)
        assertTrue(quarantineFile(directory).isFile)

        val discarded = mutableListOf<Boolean>()
        store.discardQuarantine { discarded.add(it) }
        assertEquals(listOf(true), discarded)
        assertFalse(quarantineFile(directory).exists())
        assertEquals(3, store.snapshot.topWords().size)
    }

    @Test
    fun deletingAfterRestorePurgesTheCopySoAnotherRestoreCannotResurrectTheWord() {
        val directory = quarantinedDirectory()
        val store = store(directory)
        store.restoreQuarantine()
        assertTrue(store.snapshot.topWords().contains("бабай"))

        var forgotten: Boolean? = null
        store.forget("бабай") { forgotten = it }
        assertEquals(true, forgotten)
        store.restoreQuarantine()

        assertFalse(store.snapshot.topWords().contains("бабай"))
        assertEquals(setOf("абыйлар", "гүзәл"), store.snapshot.topWords().toSet())
        assertFalse(TsentValidator().validate(overlayFile(directory), subtype)
            .normalizedForms.contains("бабай"))
        assertFalse(SentStartQuarantineSalvage.read(quarantineFile(directory), subtype)!!
            .normalizedForms.contains("бабай"))
    }

    @Test
    fun deletingAWordThatExistsOnlyInTheCopyStillPurgesIt() {
        val directory = quarantinedDirectory()
        val store = store(directory)
        store.prime()
        assertTrue(store.snapshot.isEmpty)

        var forgotten: Boolean? = null
        store.forget("бабай") { forgotten = it }
        assertEquals(true, forgotten)
        store.restoreQuarantine()

        assertEquals(setOf("абыйлар", "гүзәл"), store.snapshot.topWords().toSet())
        assertFalse(SentStartQuarantineSalvage.read(quarantineFile(directory), subtype)!!
            .normalizedForms.contains("бабай"))
    }

    @Test
    fun aFailedQuarantinePurgeRollsBackTheActiveDeletion() {
        val directory = quarantinedDirectory()
        val ops = object : RealOps() {
            override fun atomicReplace(source: File, destination: File) {
                if (destination.name.endsWith(QUARANTINE_SUFFIX)) throw IOException("replace failed")
                super.atomicReplace(source, destination)
            }
        }
        val store = store(directory, ops = ops)
        store.restoreQuarantine()
        val activeBefore = overlayFile(directory).readBytes()
        val copyBefore = quarantineFile(directory).readBytes()

        var forgotten: Boolean? = null
        store.forget("бабай") { forgotten = it }

        assertEquals(false, forgotten)
        assertTrue(store.snapshot.topWords().contains("бабай"))
        assertArrayEquals(activeBefore, overlayFile(directory).readBytes())
        assertArrayEquals(copyBefore, quarantineFile(directory).readBytes())
    }

    @Test
    fun aPartialCopyFailsDeletionClosedBecauseItsUnreadTailMayContainTheWord() {
        val directory = quarantinedDirectory(dropTailBytes = 5)
        val store = store(directory)
        store.restoreQuarantine()
        val activeBefore = overlayFile(directory).readBytes()

        var forgotten: Boolean? = null
        store.forget("бабай") { forgotten = it }

        assertEquals(false, forgotten)
        assertTrue(store.snapshot.topWords().contains("бабай"))
        assertArrayEquals(activeBefore, overlayFile(directory).readBytes())
    }

    @Test
    fun theNoticeSurvivesAProcessAndStopsAfterDelivery() {
        val directory = quarantinedDirectory()
        var firstProcess = 0
        store(directory, notice = { firstProcess++ }).prime()
        assertEquals(1, firstProcess)
        assertTrue(noticeMark(directory).isFile)

        var secondProcess = 0
        val second = store(directory, notice = { secondProcess++ })
        second.prime()
        assertEquals(1, secondProcess)
        second.noticeDelivered()
        assertFalse(noticeMark(directory).exists())

        var later = 0
        store(directory, notice = { later++ }).prime()
        assertEquals(0, later)
    }

    @Test
    fun noCopyIsDifferentFromAnUnreadableCopy() {
        val directory = newPersonalDir()
        val store = store(directory)

        assertNull(inspect(store))
        val outcomes = mutableListOf<Boolean>()
        store.restoreQuarantine { outcomes.add(it) }
        assertEquals(listOf(false), outcomes)

        // Deleting a word the overlay never had is a successful no-op.
        var forgotten: Boolean? = null
        store.forget("бабай") { forgotten = it }
        assertEquals(true, forgotten)
    }

    @Test
    fun forgettingOnAFeatureWithNoDirectoryIsASuccessfulNoOp() {
        val empty = temporaryFolder.newFolder()
        var forgotten: Boolean? = null

        store(empty).forget("бабай") { forgotten = it }

        assertEquals(true, forgotten)
    }

    private fun inspect(store: SentStartOverlayStore): PersonalQuarantineReport? {
        var answered = false
        var report: PersonalQuarantineReport? = null
        store.inspectQuarantine {
            answered = true
            report = it
        }
        assertTrue(answered)
        return report
    }

    private fun quarantinedDirectory(dropTailBytes: Int = 0): File {
        val directory = newPersonalDir()
        val whole = SentStartOverlayTestFixtures.build(
            listOf(Entry("абыйлар"), Entry("бабай"), Entry("гүзәл")),
            subtypeTag = subtype,
        )
        val damaged = if (dropTailBytes > 0) {
            whole.copyOf(whole.size - dropTailBytes)
        } else {
            whole.copyOf().also {
                it[TsentFormat.CHECKSUM_OFFSET] =
                    (it[TsentFormat.CHECKSUM_OFFSET].toInt() xor 0x7f).toByte()
            }
        }
        overlayFile(directory).writeBytes(damaged)
        store(directory).prime()
        assertTrue(quarantineFile(directory).isFile)
        assertFalse(overlayFile(directory).exists())
        return directory
    }

    private val directExecutor = Executor { it.run() }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private fun store(
        directory: File,
        ops: DurableFileOps = RealOps(),
        notice: PersonalQuarantineNotice? = null,
    ): SentStartOverlayStore = SentStartOverlayStore(
        subtypeId = subtype,
        directoryProvider = { directory },
        fileOps = ops,
        outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
        spaceProbe = SpaceProbe { Long.MAX_VALUE },
        clock = { 1000L },
        executor = directExecutor,
        quarantineNotice = notice,
    )

    private fun overlayFile(directory: File) =
        File(directory, TsentFormat.sentStartFileName(subtype))

    private fun quarantineFile(directory: File) =
        File(directory, TsentFormat.sentStartFileName(subtype) + QUARANTINE_SUFFIX)

    private fun noticeMark(directory: File) =
        File(directory, "quarantine-notice-sentstart-$subtype-s1-f1.flag")

    private open class RealOps : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()

        override fun syncFile(fileDescriptor: FileDescriptor) = fileDescriptor.sync()

        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }

        override fun atomicReplace(source: File, destination: File) {
            if (!source.renameTo(destination)) {
                destination.delete()
                if (!source.renameTo(destination)) throw IOException("replace failed")
            }
        }

        override fun syncDirectory(directory: File) = Unit

        override fun delete(file: File): Boolean = file.delete()
    }

    private companion object {
        const val QUARANTINE_SUFFIX = ".quarantine"
    }
}
