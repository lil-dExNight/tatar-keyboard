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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The P1 acceptance heart (docs/ROADMAP-P2.md), mirroring [PersonalDictionaryStoreWriteTest]:
 * the whole-file write sequence with a fault injected at EVERY step, the learn threshold of 2
 * with no plaintext before it, the context-membership gate at graduation, quarantine + salvage +
 * the no-resurrection rule of [PersonalBigramStore.forget], LRU overflow on disk, the unlock
 * gate, and the flush boundary. All plain JVM, offline.
 */
class PersonalBigramStoreWriteTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    // ---- the contract sequence, in order --------------------------------------------------------

    @Test
    fun graduationWriteFollowsTheContractSequenceAndPreservesEarlierPairs() {
        val directory = newPersonalDir()
        val events = mutableListOf<String>()
        val store = store(directory, RecordingOps(events), RecordingOpener(events))

        store.notePair("сәләм", "дөнья") // one observation: salt is written, the pair is NOT
        events.clear()

        store.notePair("сәләм", "дөнья") // second observation: graduation, whole-file write
        assertEquals(
            listOf("create", "write", "flush", "file-fsync", "replace", "dir-fsync"),
            events.toList(),
        )

        events.clear()
        repeat(2) { store.notePair("бәйрәм", "котлы") }
        assertEquals(
            listOf("create", "write", "flush", "file-fsync", "replace", "dir-fsync"),
            events.toList(),
        )
        assertEquals(
            setOf("бәйрәм" to "котлы", "сәләм" to "дөнья"),
            pairsOnDisk(directory).toSet(),
        )
    }

    // ---- one fault per step; prior file stays intact, no temp remains ---------------------------

    @Test
    fun tempCreationFailureKeepsThePriorFileIntactAndLeavesNoTemp() {
        assertFaultBetweenTempAndReplaceKeepsPrior(
            ops = object : RecordingOps() {
                override fun createNewFile(file: File): Boolean = throw IOException("temp create failed")
            },
        )
    }

    @Test
    fun writeFailureKeepsThePriorFileIntactAndLeavesNoTemp() {
        assertFaultBetweenTempAndReplaceKeepsPrior(
            opener = PersonalOutputOpener { temp ->
                object : FileOutputStream(temp) {
                    override fun write(bytes: ByteArray) = throw IOException("write failed")
                }
            },
        )
    }

    @Test
    fun flushFailureKeepsThePriorFileIntactAndLeavesNoTemp() {
        assertFaultBetweenTempAndReplaceKeepsPrior(
            opener = PersonalOutputOpener { temp ->
                object : FileOutputStream(temp) {
                    override fun flush() = throw IOException("flush failed")
                }
            },
        )
    }

    @Test
    fun fileFsyncFailureKeepsThePriorFileIntactAndLeavesNoTemp() {
        assertFaultBetweenTempAndReplaceKeepsPrior(
            ops = object : RecordingOps() {
                override fun syncFile(fileDescriptor: FileDescriptor) = throw IOException("fsync failed")
            },
        )
    }

    @Test
    fun revalidationFailureKeepsThePriorFileIntactAndLeavesNoTemp() {
        assertFaultBetweenTempAndReplaceKeepsPrior(
            opener = PersonalOutputOpener { temp ->
                object : FileOutputStream(temp) {
                    override fun write(bytes: ByteArray) {
                        val corrupt = bytes.copyOf()
                        corrupt[corrupt.size / 2] = (corrupt[corrupt.size / 2].toInt() xor 0x7f).toByte()
                        super.write(corrupt)
                    }
                }
            },
        )
    }

    @Test
    fun atomicReplaceFailureKeepsThePriorFileIntactAndLeavesNoTemp() {
        assertFaultBetweenTempAndReplaceKeepsPrior(
            ops = object : RecordingOps() {
                override fun atomicReplace(source: File, destination: File) =
                    throw IOException("replace failed")
            },
        )
    }

    @Test
    fun directoryFsyncFailureAfterReplaceLeavesTheNewValidFileAndNoTemp() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).also { repeat(2) { _ -> it.notePair("сәләм", "дөнья") } }

        val ops = object : RecordingOps() {
            private var replaced = false
            override fun atomicReplace(source: File, destination: File) {
                super.atomicReplace(source, destination)
                replaced = true
            }

            override fun syncDirectory(directory: File) {
                if (replaced) throw IOException("dir fsync failed")
            }
        }
        val store = store(directory, ops, RealOpener)
        repeat(2) { store.notePair("бәйрәм", "котлы") }

        // The replace already happened: the destination now holds the NEW valid data, readable, and
        // no temp is left.
        assertEquals(
            setOf("бәйрәм" to "котлы", "сәләм" to "дөнья"),
            pairsOnDisk(directory).toSet(),
        )
        assertNoTemp(directory)
        assertEquals(2, TpersbValidator().validate(destinationFile(directory), subtype).pairCount)
    }

    @Test
    fun firstWriteFailureLeavesNoFileAndNoTemp() {
        val directory = newPersonalDir()
        val ops = object : RecordingOps() {
            override fun atomicReplace(source: File, destination: File) = throw IOException("replace failed")
        }
        val store = store(directory, ops, RealOpener)
        repeat(2) { store.notePair("сәләм", "дөнья") }

        assertFalse("no main file for a failed first write", destinationFile(directory).isFile)
        assertNoTemp(directory)
    }

    @Test
    fun crashDuringReplaceLeavesATempThatTheNextOpenDiscards() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).also { repeat(2) { _ -> it.notePair("сәләм", "дөнья") } }
        val priorBytes = destinationFile(directory).readBytes()

        val crashingOps = object : RecordingOps() {
            override fun atomicReplace(source: File, destination: File): Unit = throw PersonalStoreCrash()
        }
        try {
            val store = store(directory, crashingOps, RealOpener)
            repeat(2) { store.notePair("бәйрәм", "котлы") }
            fail("expected the simulated process death to propagate")
        } catch (_: PersonalStoreCrash) {
            // Like process death: the store's fail-closed catch does not swallow an Error.
        }
        assertEquals(1, temps(directory).size) // a temp is left behind by the crash

        // A fresh store discards the stale temp on open and the next write succeeds normally.
        val recovered = store(directory, RecordingOps(), RealOpener)
        repeat(2) { recovered.notePair("бәйрәм", "котлы") }
        assertNoTemp(directory)
        assertEquals(
            setOf("бәйрәм" to "котлы", "сәләм" to "дөнья"),
            pairsOnDisk(directory).toSet(),
        )
        assertTrue(priorBytes.isNotEmpty())
    }

    @Test
    fun staleTempIsRemovedOnOpenAndNeverBlocksTheNextWrite() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).also { repeat(2) { _ -> it.notePair("сәләм", "дөнья") } }
        File(directory, ".personal-bigrams-$subtype.stale.tmp").writeBytes(byteArrayOf(1, 2, 3))

        // Any operation opens the directory and sweeps stale temps first (here an ineligible pair).
        val store = store(directory, RecordingOps(), RealOpener)
        store.notePair("код1234", "дөнья") // rejected by the filter, but open() still runs cleanup
        assertNoTemp(directory)
        assertEquals(setOf("сәләм" to "дөнья"), pairsOnDisk(directory).toSet())

        repeat(2) { store.notePair("бәйрәм", "котлы") }
        assertEquals(
            setOf("бәйрәм" to "котлы", "сәләм" to "дөнья"),
            pairsOnDisk(directory).toSet(),
        )
    }

    // ---- no space, overflow, gate, quarantine ---------------------------------------------------

    @Test
    fun noFreeSpaceDropsTheChangeAndKeepsThePriorFileWithoutTemp() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).also { repeat(2) { _ -> it.notePair("сәләм", "дөнья") } }
        val priorBytes = destinationFile(directory).readBytes()

        val store = store(directory, RecordingOps(), RealOpener, spaceProbe = SpaceProbe { 0 })
        repeat(2) { store.notePair("бәйрәм", "котлы") }

        assertArrayEquals(priorBytes, destinationFile(directory).readBytes())
        assertNoTemp(directory)
        assertEquals(setOf("сәләм" to "дөнья"), pairsOnDisk(directory).toSet())
    }

    @Test
    fun overflowEvictsTheLeastRecentlyUsedOnDiskToo() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener, maxPairs = 3)
        repeat(2) { store.notePair("ал", "бар") } // serial 1
        repeat(2) { store.notePair("сүз", "дөст") } // serial 2
        repeat(2) { store.notePair("кит", "кал") } // serial 3
        store.noteAcceptedPrediction("ал", "бар") // touch: (ал, бар) now has serial 4
        repeat(2) { store.notePair("күл", "буе") } // serial 5 -> evicts (сүз, дөст)

        assertEquals(3, store.snapshot.size)
        val onDisk = pairsOnDisk(directory).toSet()
        assertEquals(setOf("ал" to "бар", "кит" to "кал", "күл" to "буе"), onDisk)
        assertFalse(onDisk.contains("сүз" to "дөст"))
        assertTrue(destinationFile(directory).length() <= TpersbFormat.MAX_FILE_SIZE)
    }

    @Test
    fun ineligibleInputIsNeverStoredNorDoesItCreateAFile() {
        val badPairs = listOf(
            "код1234" to "дөнья", // digits in the context
            "сәләм" to "mir36", // digits in the successor
            "hello" to "дөнья", // Latin context
            "сәләм" to "world", // Latin successor
            "аБвгд" to "дөнья", // mixed-casing context
            "сәләм" to "аБвгд", // mixed-casing successor
            "а".repeat(25) to "дөнья", // context too long
            "сәләм" to "б".repeat(25), // successor too long
        )
        for ((context, word) in badPairs) {
            val directory = newPersonalDir()
            val store = store(directory, RecordingOps(), RealOpener)
            repeat(3) { store.notePair(context, word) }
            assertFalse("[$context][$word] must not create a file", destinationFile(directory).isFile)
            assertTrue("[$context][$word] must not enter the snapshot", store.snapshot.isEmpty)
            assertNoTemp(directory)
        }
    }

    @Test
    fun lockedDeviceSessionNeverTouchesTheExistingFile() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).also { repeat(2) { _ -> it.notePair("сәләм", "дөнья") } }
        val priorBytes = destinationFile(directory).readBytes()
        val priorNames = directory.list()!!.sorted()

        val locked = store(directory, RecordingOps(), RealOpener, unlockGate = { false })
        repeat(20) { locked.notePair("бәйрәм", "котлы") }
        locked.noteAcceptedPrediction("сәләм", "дөнья")
        locked.flush()

        assertArrayEquals(priorBytes, destinationFile(directory).readBytes())
        assertEquals(priorNames, directory.list()!!.sorted())
        assertTrue("locked store never publishes a snapshot", locked.snapshot.isEmpty)
    }

    @Test
    fun corruptFileIsQuarantinedOnOpenAndInputStillWorks() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).also { repeat(2) { _ -> it.notePair("сәләм", "дөнья") } }
        // Corrupt a payload byte so the checksum no longer matches.
        val destination = destinationFile(directory)
        val bytes = destination.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x7f).toByte()
        destination.writeBytes(bytes)

        var notices = 0
        val store = store(directory, RecordingOps(), RealOpener, quarantineNotice = { notices++ })
        store.notePair("код1234", "дөнья") // opens the directory, which quarantines the corrupt file
        assertTrue(store.snapshot.isEmpty)
        assertEquals("the notice is raised on the open that quarantined", 1, notices)

        // Ordinary input keeps working after the quarantine: a fresh valid file is written.
        repeat(2) { store.notePair("бәйрәм", "котлы") }
        assertEquals(setOf("бәйрәм" to "котлы"), pairsOnDisk(directory).toSet())
    }

    // ---- the learn threshold and what lives on disk before it -----------------------------------

    @Test
    fun oneObservationWritesNoPairAnywhereAndNoPlaintextAtAll() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener)

        store.notePair("сәләм", "дөнья")
        store.flush()

        assertFalse("no store file before the threshold", destinationFile(directory).isFile)
        assertTrue("the pair lives only in the pending counters", store.snapshot.isEmpty)
        val pending = File(directory, "pending-bigrams-$subtype-s1-f1.bin")
        assertTrue("the pending counters are written at the flush", pending.isFile)
        assertTrue("the salt exists", File(directory, "salt-bigrams.bin").isFile)
        // NOTHING on disk may carry the words themselves: the pending counters hold only salted
        // truncated hashes, and the store file does not exist yet.
        for (file in directory.listFiles().orEmpty()) {
            val bytes = file.readBytes()
            assertFalse(
                "${file.name} must not carry the context word",
                bytes.containsUtf8("сәләм"),
            )
            assertFalse(
                "${file.name} must not carry the completed word",
                bytes.containsUtf8("дөнья"),
            )
        }
    }

    @Test
    fun theSecondObservationGraduatesThePairIntoTheStore() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener)
        assertEquals(2, PersonalBigramStore.LEARN_THRESHOLD)

        store.notePair("сәләм", "дөнья")
        assertFalse(destinationFile(directory).isFile)
        store.notePair("сәләм", "дөнья")

        assertTrue(destinationFile(directory).isFile)
        val onDisk = TpersbValidator().validate(destinationFile(directory), subtype)
        assertEquals(1, onDisk.pairCount)
        assertEquals("сәләм", onDisk.contexts[0])
        assertEquals("дөнья", onDisk.successorRawForms[0])
        assertEquals("the pair really was observed twice", 2, onDisk.frequencyCounts[0])
        assertEquals(0, onDisk.usageCounts[0])
        // The pending trace of the graduated pair is gone at the next flush.
        store.flush()
        val pendingFile = File(directory, "pending-bigrams-$subtype-s1-f1.bin")
        assertEquals(0, PendingCounters.parse(pendingFile.readBytes()).size)
    }

    @Test
    fun anOversizedPendingFileIsReadAsEmptyAndLearningStillWorks() {
        val directory = newPersonalDir()
        File(directory, "pending-bigrams-$subtype-s1-f1.bin")
            .writeBytes(ByteArray(PendingCounters.MAX_SERIALIZED_BYTES + 1))

        val store = store(directory, RecordingOps(), RealOpener)
        repeat(2) { store.notePair("сәләм", "дөнья") }

        assertEquals(setOf("сәләм" to "дөнья"), pairsOnDisk(directory).toSet())
    }

    // ---- the context-membership gate at graduation ----------------------------------------------

    @Test
    fun aContextTheKeyboardDoesNotKnowIsDroppedAtGraduation() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener, membership = PersonalBigramContextMembership { _, _ -> false })
        repeat(3) { store.notePair("сәләм", "дөнья") }

        assertFalse(destinationFile(directory).isFile)
        assertTrue(store.snapshot.isEmpty)
    }

    @Test
    fun membershipIsCheckedAtGraduationNotAtObservation() {
        val directory = newPersonalDir()
        var known = false
        val store = store(directory, RecordingOps(), RealOpener, membership = PersonalBigramContextMembership { _, _ -> known })
        store.notePair("сәләм", "дөнья") // observed while the engine was cold
        known = true // the engine (or the personal dictionary) caught up by the second one
        store.notePair("сәләм", "дөнья")

        assertEquals(setOf("сәләм" to "дөнья"), pairsOnDisk(directory).toSet())
    }

    @Test
    fun aBrokenMembershipOracleVetoesTheLearnAndSurvives() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener, membership = PersonalBigramContextMembership { _, _ -> throw IOException("broken") })
        repeat(3) { store.notePair("сәләм", "дөнья") }
        assertTrue(store.snapshot.isEmpty)

        // The store itself keeps working: a pair that passes the gate still learns.
        val recovered = store(directory, RecordingOps(), RealOpener, membership = PersonalBigramContextMembership { _, _ -> true })
        repeat(2) { recovered.notePair("сәләм", "дөнья") }
        assertEquals(setOf("сәләм" to "дөнья"), pairsOnDisk(directory).toSet())
    }

    // ---- observations and acceptances of a learned pair: in memory, flushed once ----------------

    @Test
    fun furtherObservationsAndAcceptancesUpdateCountersInMemoryWithoutRewriting() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener)
        repeat(2) { store.notePair("сәләм", "дөнья") }
        assertEquals(1, store.writeCount)

        repeat(7) { store.notePair("сәләм", "дөнья") }
        repeat(5) { store.noteAcceptedPrediction("сәләм", "дөнья") }
        assertEquals("observations and taps must not rewrite the file", 1, store.writeCount)
        assertEquals(2 + 7, store.snapshot.frequencyCountAt(0))
        assertEquals(5, store.snapshot.usageCountAt(0))

        store.flush()
        assertEquals("the boundary flush writes exactly once", 2, store.writeCount)
        val onDisk = TpersbValidator().validate(destinationFile(directory), subtype)
        assertEquals(9, onDisk.frequencyCounts[0])
        assertEquals(5, onDisk.usageCounts[0])

        store.flush() // nothing dirty now
        assertEquals(2, store.writeCount)
    }

    @Test
    fun anAcceptanceOfAnUnknownPairIsAQuietNoOp() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener)
        store.noteAcceptedPrediction("сәләм", "дөнья") // never learned
        store.flush()
        assertFalse(destinationFile(directory).isFile)
        assertEquals(0, store.writeCount)
        assertTrue(store.snapshot.isEmpty)
    }

    // ---- forgetting, with the no-resurrection rule ----------------------------------------------

    @Test
    fun forgetRemovesThePairAndDropsItsPendingProgress() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener)
        repeat(2) { store.notePair("сәләм", "дөнья") }
        repeat(2) { store.notePair("бәйрәм", "котлы") }

        var outcome: Boolean? = null
        store.forget("сәләм", "дөнья") { outcome = it }
        assertEquals(true, outcome)
        assertEquals(setOf("бәйрәм" to "котлы"), pairsOnDisk(directory).toSet())
        assertTrue(store.snapshot.indexOfPair("сәләм", "дөнья") < 0)

        // Forgetting also dropped the pending progress: one more observation does NOT re-learn it.
        store.notePair("сәләм", "дөнья")
        assertFalse(pairsOnDisk(directory).contains("сәләм" to "дөнья"))
        store.notePair("сәләм", "дөнья")
        assertTrue("two fresh observations learn it again", pairsOnDisk(directory).contains("сәләм" to "дөнья"))
    }

    @Test
    fun forgetDeletesTheFileWhenTheLastPairIsGone() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener)
        repeat(2) { store.notePair("сәләм", "дөнья") }

        var outcome: Boolean? = null
        store.forget("сәләм", "дөнья") { outcome = it }
        assertEquals(true, outcome)
        assertFalse(destinationFile(directory).exists())
        assertTrue(store.snapshot.isEmpty)
        assertNoTemp(directory)
    }

    @Test
    fun aFailedRewriteKeepsThePairAndTheAnswerIsHonest() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener)
        repeat(2) { store.notePair("сәләм", "дөнья") }
        repeat(2) { store.notePair("бәйрәм", "котлы") }

        val failing = object : RecordingOps() {
            override fun atomicReplace(source: File, destination: File) = throw IOException("replace failed")
        }
        // The fault is injected through a NEW store over the same directory: the failed rewrite
        // there must answer false and keep the pair visible — on screen as much as on disk.
        val faulting = store(directory, failing, RealOpener)
        var outcome: Boolean? = null
        faulting.forget("сәләм", "дөнья") { outcome = it }
        assertEquals(false, outcome)
        assertTrue(faulting.snapshot.indexOfPair("сәләм", "дөнья") >= 0)
        assertTrue(pairsOnDisk(directory).contains("сәләм" to "дөнья"))
    }

    // ---- quarantine: salvage, restore, and the no-resurrection rule -----------------------------

    @Test
    fun salvageReadsWhatTheValidatorRefuses() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).also {
            repeat(2) { _ -> it.notePair("сәләм", "дөнья") }
            repeat(2) { _ -> it.notePair("бәйрәм", "котлы") }
        }
        quarantineCorruptingLastByte(directory)

        var report: PersonalQuarantineReport? = null
        var reportArrived = false
        val store = store(directory, RecordingOps(), RealOpener)
        store.prime() // quarantines the corrupt file
        store.inspectQuarantine { report = it; reportArrived = true }

        assertTrue(reportArrived)
        // The flip lands in the LAST record's successor bytes (the pairs sort (бәйрәм, котлы)
        // before (сәләм, дөнья)): the first pair survives, the second is lost to strict UTF-8.
        assertEquals(1, report?.wordCount)
        // The file is NOT read to its end, and the salvage says so — a partial recovery is never
        // presented as a whole one.
        assertEquals(false, report?.readToEnd)
    }

    @Test
    fun restoreQuarantineBringsBackSalvagedPairsAndSkipsExistingOnes() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).also {
            repeat(2) { _ -> it.notePair("сәләм", "дөнья") }
        }
        // The payload stays readable; only the checksum no longer matches.
        quarantineCorruptingChecksum(directory)

        val store = store(directory, RecordingOps(), RealOpener)
        store.prime()
        assertTrue(store.snapshot.isEmpty)

        var restored: Boolean? = null
        store.restoreQuarantine { restored = it }
        assertEquals(true, restored)
        assertTrue(store.snapshot.indexOfPair("сәләм", "дөнья") >= 0)

        // Running it twice is harmless: the already-present pair is skipped, not promoted.
        val before = store.snapshot
        var again: Boolean? = null
        store.restoreQuarantine { again = it }
        assertEquals(true, again)
        assertSame(before, store.snapshot)
    }

    @Test
    fun aForgottenPairIsNotResurrectedByARestore() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).also {
            repeat(2) { _ -> it.notePair("сәләм", "дөнья") }
            repeat(2) { _ -> it.notePair("бәйрәм", "котлы") }
        }
        quarantineCorruptingChecksum(directory)

        val store = store(directory, RecordingOps(), RealOpener)
        store.prime()
        var restored: Boolean? = null
        store.restoreQuarantine { restored = it }
        assertEquals(true, restored)
        assertEquals(2, store.snapshot.size)

        // The user deletes one pair; the copy is purged with it.
        store.forget("сәләм", "дөнья")
        // A SECOND restore must not bring the forgotten pair back — erased means erased, in the
        // copy as much as in the list.
        store.restoreQuarantine { restored = it }
        assertTrue(store.snapshot.indexOfPair("сәләм", "дөнья") < 0)
        assertTrue(store.snapshot.indexOfPair("бәйрәм", "котлы") >= 0)
    }

    @Test
    fun discardQuarantineRemovesTheCopyAndNothingElse() {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).also {
            repeat(2) { _ -> it.notePair("сәләм", "дөнья") }
        }
        quarantineCorruptingChecksum(directory)

        val store = store(directory, RecordingOps(), RealOpener)
        store.prime()
        var discarded: Boolean? = null
        store.discardQuarantine { discarded = it }
        assertEquals(true, discarded)

        var report: PersonalQuarantineReport? = null
        store.inspectQuarantine { report = it }
        assertNull("no copy remains to inspect", report)
    }

    // ---- clearAll, and the language boundary ------------------------------------------------------

    @Test
    fun clearAllEmptiesMemoryAndDeletesEveryFileOfTheStore() {
        val directory = newPersonalDir()
        val store = store(directory, RecordingOps(), RealOpener)
        store.notePair("сәләм", "дөнья") // pending + salt exist
        store.flush()
        repeat(1) { store.notePair("сәләм", "дөнья") } // graduation: the file exists
        quarantineCorruptingChecksum(directory)
        // The quarantine needs a FRESH store over the same directory: the one that wrote the file
        // is already loaded and would never re-read it.
        val reopened = store(directory, RecordingOps(), RealOpener)
        reopened.prime() // quarantines: the copy and the notice flag exist

        var outcome: Boolean? = null
        reopened.clearAll { outcome = it }
        assertEquals(true, outcome)
        assertTrue(reopened.snapshot.isEmpty)
        assertFalse(destinationFile(directory).exists())
        assertFalse(File(directory, "pending-bigrams-$subtype-s1-f1.bin").exists())
        assertFalse(File(directory, "salt-bigrams.bin").exists())
        assertFalse(File(directory, quarantineName()).exists())
        assertFalse(File(directory, "quarantine-notice-bigrams-$subtype-s1-f1.flag").exists())
        assertNoTemp(directory)

        // Nothing of the store survives: a fresh store over the same directory starts empty.
        val fresh = store(directory, RecordingOps(), RealOpener)
        fresh.prime()
        assertTrue(fresh.snapshot.isEmpty)
    }

    @Test
    fun pairsOfOneLanguageNeverSurfaceInTheOther() {
        val directory = newPersonalDir()
        val tatar = store(directory, RecordingOps(), RealOpener, subtypeId = PersonalSubtypes.TATAR_RU)
        repeat(2) { tatar.notePair("сәләм", "дөнья") }

        val russian = store(directory, RecordingOps(), RealOpener, subtypeId = PersonalSubtypes.RUSSIAN)
        russian.prime()
        assertTrue("the Russian store has its own file and its own snapshot", russian.snapshot.isEmpty)
        assertFalse(
            File(directory, TpersbFormat.personalBigramsFileName(PersonalSubtypes.RUSSIAN)).exists(),
        )

        repeat(2) { russian.notePair("мой", "мир") }
        assertEquals(1, russian.snapshot.size)
        // And the Tatar snapshot still holds only the Tatar pair.
        assertEquals(1, tatar.snapshot.size)
        assertTrue(tatar.snapshot.indexOfPair("мой", "мир") < 0)
    }

    // ---- shared fault harness -------------------------------------------------------------------

    /**
     * Writes a valid prior file, then runs a second graduation against a store faulting at exactly
     * one step BEFORE (or at) the atomic replace, and asserts the prior file is byte-for-byte
     * intact, the new pair never entered the snapshot, and no temp remains.
     */
    private fun assertFaultBetweenTempAndReplaceKeepsPrior(
        ops: RecordingOps = RecordingOps(),
        opener: PersonalOutputOpener = RealOpener,
    ) {
        val directory = newPersonalDir()
        store(directory, RecordingOps(), RealOpener).also { repeat(2) { _ -> it.notePair("сәләм", "дөнья") } }
        val priorBytes = destinationFile(directory).readBytes()

        val faulting = store(directory, ops, opener)
        repeat(2) { faulting.notePair("бәйрәм", "котлы") }

        assertArrayEquals("prior file must be untouched", priorBytes, destinationFile(directory).readBytes())
        assertNoTemp(directory)
        assertEquals(setOf("сәләм" to "дөнья"), pairsOnDisk(directory).toSet())
        assertEquals(1, faulting.snapshot.size)
    }

    /** Corrupts the last payload byte and lets the next open quarantine the file. */
    private fun quarantineCorruptingLastByte(directory: File) {
        val destination = destinationFile(directory)
        val bytes = destination.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x7f).toByte()
        destination.writeBytes(bytes)
    }

    /**
     * Corrupts one byte of the STORED checksum: validation fails, the payload stays readable —
     * the corruption shape every restore test needs, because the salvage ignores the checksum by
     * design.
     */
    private fun quarantineCorruptingChecksum(directory: File) {
        val destination = destinationFile(directory)
        val bytes = destination.readBytes()
        bytes[TpersbFormat.CHECKSUM_OFFSET] = (bytes[TpersbFormat.CHECKSUM_OFFSET].toInt() xor 0x7f).toByte()
        destination.writeBytes(bytes)
    }

    // ---- helpers --------------------------------------------------------------------------------

    private val directExecutor = Executor { it.run() }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private fun store(
        directory: File,
        ops: DurableFileOps,
        opener: PersonalOutputOpener,
        spaceProbe: SpaceProbe = SpaceProbe { Long.MAX_VALUE },
        unlockGate: () -> Boolean = { true },
        maxPairs: Int = TpersbFormat.MAX_PERSONAL_BIGRAM_PAIRS.toInt(),
        membership: PersonalBigramContextMembership = PersonalBigramContextMembership { _, _ -> true },
        subtypeId: String = subtype,
        quarantineNotice: PersonalQuarantineNotice? = null,
    ): PersonalBigramStore = PersonalBigramStore(
        subtypeId = subtypeId,
        directoryProvider = { directory },
        fileOps = ops,
        outputOpener = opener,
        spaceProbe = spaceProbe,
        clock = { 1000L },
        executor = directExecutor,
        contextMembership = membership,
        unlockGate = unlockGate,
        maxPairs = maxPairs,
        quarantineNotice = quarantineNotice,
    )

    private fun destinationFile(directory: File) =
        File(directory, TpersbFormat.personalBigramsFileName(subtype))

    private fun quarantineName(): String =
        TpersbFormat.personalBigramsFileName(subtype) + ".quarantine"

    private fun temps(directory: File): List<File> =
        directory.listFiles { f -> f.name.startsWith(".personal-bigrams-") && f.name.endsWith(".tmp") }
            ?.toList() ?: emptyList()

    private fun assertNoTemp(directory: File) =
        assertTrue("no temp must remain", temps(directory).isEmpty())

    private fun pairsOnDisk(directory: File): List<Pair<String, String>> {
        val validated = TpersbValidator().validate(destinationFile(directory), subtype)
        return (0 until validated.pairCount).map {
            validated.contexts[it] to validated.successorNormalizedForms[it]
        }
    }

    private fun ByteArray.containsUtf8(text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty() || this.size < needle.size) return false
        for (offset in 0..this.size - needle.size) {
            var found = true
            for (index in needle.indices) {
                if (this[offset + index] != needle[index]) {
                    found = false
                    break
                }
            }
            if (found) return true
        }
        return false
    }

    private class PersonalStoreCrash : Error()

    private val RealOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) }

    /** Records every durable op and performs it for real; overridable to inject a fault. */
    private open inner class RecordingOps(private val events: MutableList<String> = mutableListOf()) :
        DurableFileOps {
        override fun createNewFile(file: File): Boolean {
            events += "create"
            return file.createNewFile()
        }

        override fun syncFile(fileDescriptor: FileDescriptor) {
            events += "file-fsync"
            fileDescriptor.sync()
        }

        override fun atomicRename(source: File, destination: File) {
            events += "rename"
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }

        override fun atomicReplace(source: File, destination: File) {
            events += "replace"
            if (!source.renameTo(destination)) {
                destination.delete()
                if (!source.renameTo(destination)) throw IOException("replace failed")
            }
        }

        override fun syncDirectory(directory: File) {
            events += "dir-fsync"
        }

        override fun delete(file: File): Boolean {
            events += "delete"
            return file.delete()
        }
    }

    private inner class RecordingOpener(private val events: MutableList<String>) : PersonalOutputOpener {
        override fun open(temp: File): FileOutputStream = object : FileOutputStream(temp) {
            override fun write(bytes: ByteArray) {
                events += "write"
                super.write(bytes)
            }

            override fun flush() {
                events += "flush"
                super.flush()
            }
        }
    }
}
