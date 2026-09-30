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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/**
 * Silent-failure cases of the personal dictionary store. The store may not log, so every failure
 * that matters to the user has to reach the caller as a result or a notice: hand-added words,
 * "Forget word", erasure, and unreadable files.
 *
 * The harness is the one [PersonalDictionaryStoreWriteTest] uses (a real directory, a direct
 * executor, injectable durable ops), because these failures happen in the write sequence.
 */
class PersonalDictionarySilentFailureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    // ---- the outcome of a hand-added word -------------------------------------------------------

    /**
     * A write that never lands (no space, failed re-validation, no directory) reports failure to
     * the caller: the package may not log, and the screen must not show "saved".
     */
    @Test
    fun addingAWordThatCannotBeWrittenReportsFailure() {
        val directory = newPersonalDir()
        val store = store(directory, spaceProbe = SpaceProbe { 0L })

        val outcomes = mutableListOf<Boolean>()
        store.addManually("абыйлар") { outcomes.add(it) }

        assertEquals("exactly one answer per mutation", 1, outcomes.size)
        assertFalse("the word never reached the disk and the caller must hear so", outcomes[0])
        assertTrue("and nothing was published either", store.snapshot.isEmpty)
        assertFalse(destinationFile(directory).exists())
    }

    /** A write that did land reports success, exactly once. */
    @Test
    fun addingAWordThatIsWrittenReportsSuccessAfterThePublish() {
        val directory = newPersonalDir()
        val store = store(directory)

        val seenAtCallback = mutableListOf<Int>()
        val outcomes = mutableListOf<Boolean>()
        store.addManually("абыйлар") {
            outcomes.add(it)
            // The report must not run before the snapshot exists: the screen repaints from this
            // callback, and repainting from an older snapshot would show the wrong list.
            seenAtCallback.add(store.snapshot.size)
        }

        assertEquals(listOf(true), outcomes)
        assertEquals("the published snapshot already carries the word", listOf(1), seenAtCallback)
    }

    /** A word the content filter rejects is a failure too; the screen must not repaint blind. */
    @Test
    fun aRejectedWordAlsoProducesExactlyOneAnswer() {
        val store = store(newPersonalDir())

        val outcomes = mutableListOf<Boolean>()
        store.addManually("ab1") { outcomes.add(it) }

        assertEquals(listOf(false), outcomes)
    }

    // ---- "Forget word" that did not forget -----------------------------------------------------

    /**
     * When the rewrite fails, the word stays in memory and on disk, so the caller must hear
     * failure; otherwise the dialog closes, and the word comes back on the next keystroke.
     */
    @Test
    fun forgettingAWordReportsFailureWhenTheRewriteDoesNotLand() {
        val directory = newPersonalDir()
        store(directory).apply {
            addManually("абыйлар")
            addManually("сүзлек")
        }
        // A second store over the same directory, this one unable to write anything at all.
        val faulting = store(directory, spaceProbe = SpaceProbe { 0L })

        val outcomes = mutableListOf<Boolean>()
        faulting.forget("сүзлек") { outcomes.add(it) }

        assertEquals(listOf(false), outcomes)
        assertTrue(
            "the word is still saved, which is precisely why the caller has to be told",
            faulting.snapshot.indexOfNormalized("сүзлек") >= 0,
        )
    }

    /** A rewrite that lands reports success and the word is gone. */
    @Test
    fun forgettingAWordReportsSuccessWhenTheRewriteLands() {
        val directory = newPersonalDir()
        val store = store(directory)
        store.addManually("абыйлар")
        store.addManually("сүзлек")

        val outcomes = mutableListOf<Boolean>()
        store.forget("сүзлек") { outcomes.add(it) }

        assertEquals(listOf(true), outcomes)
        assertTrue(store.snapshot.indexOfNormalized("сүзлек") < 0)
    }

    /** Forgetting a word that was never saved is not a failure: from the user's view it is gone. */
    @Test
    fun forgettingAWordThatIsNotSavedIsNotReportedAsAFailure() {
        val directory = newPersonalDir()
        val store = store(directory)
        store.addManually("абыйлар")

        val outcomes = mutableListOf<Boolean>()
        store.forget("сүзлек") { outcomes.add(it) }

        assertEquals(listOf(true), outcomes)
    }

    // ---- an erased word is gone before the write finishes --------------------------------------

    /**
     * The suggestion strip unbinds as soon as the dialog is confirmed, so the snapshot the engine
     * reads must drop the word before the whole-file write (serialize, fsync, re-validate,
     * replace, fsync), not after it. Otherwise a keystroke during the write could show the erased
     * word again.
     *
     * The probe reads the published snapshot from inside the write itself.
     */
    @Test
    fun theErasedWordIsGoneFromThePublishedSnapshotBeforeTheWriteBegins() {
        val directory = newPersonalDir()
        var underTest: PersonalDictionaryStore? = null
        val visibleDuringWrite = mutableListOf<Boolean>()
        val probingOpener = PersonalOutputOpener { temp ->
            object : FileOutputStream(temp) {
                override fun write(bytes: ByteArray) {
                    val store = underTest
                    if (store != null) {
                        visibleDuringWrite.add(store.snapshot.indexOfNormalized("сүзлек") >= 0)
                    }
                    super.write(bytes)
                }
            }
        }
        val store = store(directory, opener = probingOpener)
        underTest = store
        store.addManually("абыйлар")
        store.addManually("сүзлек")
        visibleDuringWrite.clear()

        store.forget("сүзлек")

        assertEquals("the rewrite happened", 1, visibleDuringWrite.size)
        assertFalse(
            "a reader that looks while the erasure is being written must not still see the word",
            visibleDuringWrite[0],
        )
    }

    /**
     * When the write fails the word is still saved, so the snapshot has to show it again rather
     * than claim it is gone.
     */
    @Test
    fun aFailedErasureRestoresThePublishedSnapshot() {
        val directory = newPersonalDir()
        store(directory).apply {
            addManually("абыйлар")
            addManually("сүзлек")
        }
        val faulting = store(directory, spaceProbe = SpaceProbe { 0L })
        faulting.prime()

        faulting.forget("сүзлек")

        assertEquals("both words are still saved", 2, faulting.snapshot.size)
        assertTrue(faulting.snapshot.indexOfNormalized("сүзлек") >= 0)
    }

    // ---- "Erase all" must not stop at the first failure ----------------------------------------

    /**
     * Each deletion runs on its own, so a failure on the dictionary file does not skip the pending
     * counters and the salt. Left behind with the same salt, those counters would let half-learned
     * words return after a few more completions while the screen shows an empty list.
     */
    @Test
    fun erasingEverythingStillRemovesTheSaltAndTheCountersWhenTheDictionaryCannotBeDeleted() {
        val directory = newPersonalDir()
        val store = store(directory)
        store.addManually("абыйлар")
        store.noteCompletion("сүзләр")
        store.flush()
        assertTrue(destinationFile(directory).isFile)
        assertNotNull("the counters file exists before the erasure", pendingFile(directory))
        assertTrue("the salt exists before the erasure", saltFile(directory).isFile)

        // Only the dictionary file refuses to go: deleteFile() throws on it.
        val stubborn = object : PassthroughOps() {
            override fun delete(file: File): Boolean =
                if (file.name.endsWith(".tpers")) false else super.delete(file)
        }
        val outcomes = mutableListOf<Boolean>()
        store(directory, ops = stubborn).clearAll { outcomes.add(it) }

        assertTrue("the dictionary file is the one that could not go", destinationFile(directory).isFile)
        assertEquals("the counters must go anyway", null, pendingFile(directory))
        assertFalse("and so must the salt, or the same hashes match again", saltFile(directory).isFile)
        assertEquals(
            "an erasure that left the dictionary behind is not a successful erasure",
            listOf(false), outcomes,
        )
    }

    /** Nothing on disk was touched because the user is locked out: also a failure. */
    @Test
    fun erasingEverythingBehindTheUnlockGateIsReportedAsAFailure() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")

        val outcomes = mutableListOf<Boolean>()
        store(directory, unlockGate = { false }).clearAll { outcomes.add(it) }

        assertEquals(listOf(false), outcomes)
        assertTrue("the file is untouched, which is exactly the case that must not pass",
            destinationFile(directory).isFile)
    }

    /** The ordinary path: everything goes, and that is reported as success. */
    @Test
    fun erasingEverythingReportsSuccessWhenEveryFileIsGone() {
        val directory = newPersonalDir()
        val store = store(directory)
        store.addManually("абыйлар")
        store.noteCompletion("сүзләр")
        store.flush()

        val outcomes = mutableListOf<Boolean>()
        store.clearAll { outcomes.add(it) }

        assertEquals(listOf(true), outcomes)
        assertFalse(destinationFile(directory).exists())
        assertEquals(null, pendingFile(directory))
        assertFalse(saltFile(directory).exists())
    }


    // ---- an unreadable file is set aside, not destroyed ----------------------------------------

    /**
     * A file that fails validation (a write cut short by power loss, a checksum mismatch, a format
     * a later version changes) is moved to a quarantine copy instead of being deleted. Most
     * corruption is an interrupted write, so most of the words survive and can be salvaged.
     */
    @Test
    fun anUnreadableFileIsMovedAsideInsteadOfDestroyed() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        val unreadableBytes = corruptTheDictionary(directory)

        val store = store(directory)
        store.prime()

        assertFalse(
            "the unreadable file must not stay where the reader looks for a valid one",
            destinationFile(directory).exists(),
        )
        assertTrue("the bytes are still on the device", quarantineFile(directory).isFile)
        assertArrayEquals(
            "byte for byte: a repair path that cannot read the original is worth nothing",
            unreadableBytes,
            quarantineFile(directory).readBytes(),
        )
        assertTrue("and fail-closed still holds — nothing unreadable is published", store.snapshot.isEmpty)
    }

    /**
     * There is one quarantine slot per language, and the next corruption overwrites it, so disk
     * use is bounded by one file.
     */
    @Test
    fun aSecondUnreadableFileOverwritesTheOneQuarantineSlot() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        corruptTheDictionary(directory)
        store(directory).prime()

        // A fresh valid file is written after the first quarantine, and then it goes bad too.
        store(directory).addManually("сүзлек")
        val secondUnreadableBytes = corruptTheDictionary(directory)
        store(directory).prime()

        assertEquals(
            "one slot, however many corruptions: the copy may not accumulate on the device",
            1,
            quarantineFiles(directory).size,
        )
        assertArrayEquals(
            "and the slot holds the latest corruption, not the first",
            secondUnreadableBytes,
            quarantineFile(directory).readBytes(),
        )
    }

    /**
     * "Erase all" erases the quarantine copy too: those bytes are the user's own words, and no
     * other screen lets the user remove them.
     */
    @Test
    fun erasingEverythingRemovesTheQuarantineCopyToo() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        corruptTheDictionary(directory)
        store(directory).prime()
        assertTrue("the copy exists before the erasure", quarantineFile(directory).isFile)

        val outcomes = mutableListOf<Boolean>()
        store(directory).clearAll { outcomes.add(it) }

        assertFalse(
            "the one copy of the user's words that no screen shows must still be removable",
            quarantineFile(directory).exists(),
        )
        assertEquals(listOf(true), outcomes)
    }

    /** A quarantine copy that cannot be deleted is a failed erasure: the words are still there. */
    @Test
    fun anErasureThatCannotRemoveTheQuarantineCopyIsNotReportedAsSuccess() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        corruptTheDictionary(directory)
        store(directory).prime()

        val stubborn = object : PassthroughOps() {
            override fun delete(file: File): Boolean =
                if (file.name.endsWith(QUARANTINE_SUFFIX)) false else super.delete(file)
        }
        val outcomes = mutableListOf<Boolean>()
        store(directory, ops = stubborn).clearAll { outcomes.add(it) }

        assertTrue("the copy is the file that could not go", quarantineFile(directory).isFile)
        assertEquals(
            "words still on the device after 'erase everything' is not success",
            listOf(false), outcomes,
        )
    }

    /**
     * The user is told when the list was emptied by a quarantine, since the package may not log
     * and the user cannot otherwise tell it from their own erasure.
     *
     * Once, not once per open: the second `prime()` here is the ordinary second reader arriving.
     */
    @Test
    fun theUserIsToldOnceWhenAnUnreadableFileIsSetAside() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        corruptTheDictionary(directory)

        var notices = 0
        val store = store(directory, notice = PersonalQuarantineNotice { notices++ })
        store.prime()
        store.prime()

        assertEquals("exactly one notice per corruption", 1, notices)
    }

    /** Nothing is said when the file reads fine: the notice is not a startup event. */
    @Test
    fun aReadableFileSaysNothing() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")

        var notices = 0
        store(directory, notice = PersonalQuarantineNotice { notices++ }).prime()

        assertEquals(0, notices)
    }

    /**
     * If the move itself fails, the unreadable file is removed (left in place it would fail
     * validation on every start), and the user is told either way.
     */
    @Test
    fun theUserIsToldEvenWhenTheCopyCannotBeMade() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        corruptTheDictionary(directory)
        val cannotMove = object : PassthroughOps() {
            override fun atomicReplace(source: File, destination: File) =
                throw IOException("no rename")
        }

        var notices = 0
        val store = store(directory, ops = cannotMove, notice = PersonalQuarantineNotice { notices++ })
        store.prime()

        assertEquals(1, notices)
        assertFalse(
            "an unreadable file left in place would fail validation on every start",
            destinationFile(directory).exists(),
        )
        assertTrue(store.snapshot.isEmpty)
    }

    // ---- a failed removal is an answer, not a dead process -------------------------------------

    /**
     * `deleteFile()` returns true or throws, so removing the last saved word can throw. `forget`
     * must catch it: the store's worker is a single-thread executor, and an uncaught exception
     * there reaches `KillApplicationHandler` and kills the keyboard mid-typing.
     *
     * The executor here mimics that worker: one thread per event, with an uncaught-exception
     * handler where the app's killer would be. Anything it collects would have been a crash.
     */
    @Test
    fun aRemovalThatCannotDeleteTheFileAnswersInsteadOfKillingTheWorker() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        val stubborn = object : PassthroughOps() {
            override fun delete(file: File): Boolean =
                if (file.name.endsWith(".tpers")) false else super.delete(file)
        }
        val uncaught = mutableListOf<Throwable>()
        val faulting = store(directory, ops = stubborn, executor = workerWithKiller(uncaught))

        val outcomes = mutableListOf<Boolean>()
        faulting.forget("абыйлар") { outcomes.add(it) }

        assertEquals(
            "nothing may reach the worker's uncaught handler: in production that handler kills the IME",
            emptyList<Throwable>(), uncaught,
        )
        assertEquals("the user hears a refusal instead", listOf(false), outcomes)
    }

    /**
     * A delete that did not happen leaves the word saved, so the published snapshot must show it
     * again; the store may not claim the word is gone.
     */
    @Test
    fun aRemovalThatCannotDeleteTheFileLeavesTheWordSavedAndSaysSo() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        val stubborn = object : PassthroughOps() {
            override fun delete(file: File): Boolean =
                if (file.name.endsWith(".tpers")) false else super.delete(file)
        }
        val faulting = store(directory, ops = stubborn)

        val outcomes = mutableListOf<Boolean>()
        faulting.forget("абыйлар") { outcomes.add(it) }

        assertEquals(listOf(false), outcomes)
        assertTrue("the word is still on disk", destinationFile(directory).isFile)
        assertTrue(
            "and still published, because it is still saved",
            faulting.snapshot.indexOfNormalized("абыйлар") >= 0,
        )
    }

    // ---- helpers --------------------------------------------------------------------------------

    private val directExecutor = Executor { it.run() }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    /**
     * Mimics the store's worker: one thread per event, joined so the test stays as sequential as
     * the direct executor, and an `UncaughtExceptionHandler` in place of `KillApplicationHandler`.
     */
    private fun workerWithKiller(uncaught: MutableList<Throwable>) = Executor { runnable ->
        val thread = Thread(runnable, "personal-dictionary-test")
        thread.setUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        thread.start()
        thread.join()
    }

    private fun store(
        directory: File,
        ops: DurableFileOps = PassthroughOps(),
        opener: PersonalOutputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
        spaceProbe: SpaceProbe = SpaceProbe { Long.MAX_VALUE },
        unlockGate: () -> Boolean = { true },
        executor: Executor = directExecutor,
        notice: PersonalQuarantineNotice? = null,
    ): PersonalDictionaryStore = PersonalDictionaryStore(
        subtypeId = subtype,
        directoryProvider = { directory },
        fileOps = ops,
        outputOpener = opener,
        spaceProbe = spaceProbe,
        clock = { 1000L },
        executor = executor,
        unlockGate = unlockGate,
        quarantineNotice = notice,
    )

    private fun destinationFile(directory: File) =
        File(directory, TpersFormat.personalFileName(subtype))

    private fun pendingFile(directory: File): File? =
        directory.listFiles { file -> file.name.startsWith("pending-") }?.firstOrNull()

    private fun saltFile(directory: File) = File(directory, "salt.bin")

    /** Flips one payload byte so the checksum no longer matches; returns the unreadable bytes. */
    private fun corruptTheDictionary(directory: File): ByteArray {
        val destination = destinationFile(directory)
        val bytes = destination.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x7f).toByte()
        destination.writeBytes(bytes)
        return bytes
    }

    private fun quarantineFile(directory: File) =
        File(directory, TpersFormat.personalFileName(subtype) + QUARANTINE_SUFFIX)

    private fun quarantineFiles(directory: File): List<File> =
        directory.listFiles { file -> file.name.endsWith(QUARANTINE_SUFFIX) }?.toList() ?: emptyList()

    private companion object {
        /** The suffix the store appends for the one quarantine slot; see `PersonalDictionaryStore`. */
        const val QUARANTINE_SUFFIX = ".quarantine"
    }

    /** Performs every durable op for real; a test overrides the one it wants to fail. */
    private open class PassthroughOps : DurableFileOps {
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
}
