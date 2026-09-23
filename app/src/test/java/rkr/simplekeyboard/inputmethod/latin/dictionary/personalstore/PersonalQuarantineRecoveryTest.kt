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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/**
 * Mission `tt-quarantine`: the store half of finishing the choice 1.8.2 made half-way.
 *
 * 1.8.2 stopped destroying an unreadable personal dictionary — it moves the bytes into one slot per
 * language and tells the user. Three things were left unbuilt, and each of them makes the kept copy
 * worth nothing on its own:
 *
 * - **B4, the copy could not be read back.** No code could open the slot, so the user's words were
 *   being preserved for a recovery path that did not exist. [PersonalQuarantineSalvage] reads them;
 *   what is tested here is the store API around it — inspect, restore, discard — and above all that
 *   a partial recovery is never presented as a whole one.
 * - **B5, the notice did not survive.** The "not told yet" mark was a field on a process that was
 *   usually about to end, and it waited for the next input start. A quarantine at the wrong moment
 *   was never mentioned again, ever. The mark goes to disk now — a flag, one byte, no text.
 * - **B6, the answer could still kill the keyboard.** Every mutation guarded its body and then
 *   called `outcome?.onFinished(...)` OUTSIDE the guard. That callback belongs to an Activity and
 *   posts to the UI thread; a throw from it lands on a bare single-thread executor whose default
 *   handler is `KillApplicationHandler`.
 *
 * The harness is the one [PersonalDictionarySilentFailureTest] already uses — a real directory, a
 * direct executor, injectable durable ops — and the corruption is the real one: a file cut short,
 * which is what a power cut in the middle of a write actually leaves behind.
 */
class PersonalQuarantineRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    // ---- B4: what is in the copy can be seen ----------------------------------------------------

    /**
     * The number the screen shows and the sentence it must add beside it. Two words came out of a
     * three-word copy, so the count is 2 AND the copy is known incomplete — the one outcome this
     * feature may never produce is "restored, all done" over a file that lost a third of itself.
     */
    @Test
    fun inspectingAPartlyReadableCopySaysHowManyCameOutAndThatTheRestIsLost() {
        val directory = quarantinedDirectory(dropTailBytes = 5)

        val report = inspect(store(directory))

        assertNotNull("there is a copy", report)
        assertEquals("the words in front of the cut are readable", 2, report!!.wordCount)
        assertFalse("and the screen is owed the other half of the truth", report.readToEnd)
    }

    /** No copy at all is a different answer from a copy that yielded nothing: `null`, not zero. */
    @Test
    fun inspectingWithNoCopyPresentReportsNoCopyRatherThanAnEmptyOne() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")

        assertNull(inspect(store(directory)))
    }

    /**
     * A copy cut down to its bare header yields no words — and is still a copy. The distinction is
     * not pedantry: those bytes are on the device, no screen shows them, and the user is entitled to
     * a button that removes them.
     */
    @Test
    fun aCopyThatYieldsNothingIsStillACopyTheUserCanBeOffered() {
        val directory = quarantinedDirectory(truncateTo = TpersFormat.HEADER_SIZE)

        val report = inspect(store(directory))

        assertNotNull("the bytes exist, so there is something to offer", report)
        assertEquals(0, report!!.wordCount)
        assertFalse(report.readToEnd)
    }

    /** Nothing is read behind the unlock gate: before the first unlock the path is not even there. */
    @Test
    fun nothingIsInspectedBeforeTheDeviceHasBeenUnlockedOnce() {
        val directory = quarantinedDirectory(dropTailBytes = 5)

        assertNull(inspect(store(directory, unlockGate = { false })))
    }

    // ---- B4: what is in the copy can be put back ------------------------------------------------

    /** The point of the whole mission: the words come back, and they come back into the list. */
    @Test
    fun restoringPutsTheSalvagedWordsBackIntoTheDictionary() {
        val directory = quarantinedDirectory(dropTailBytes = 5)
        val store = store(directory)
        store.prime()
        assertTrue("the corruption emptied the list, which is the state being repaired",
            store.snapshot.isEmpty)

        val outcomes = mutableListOf<Boolean>()
        store.restoreQuarantine { outcomes.add(it) }

        assertEquals(listOf(true), outcomes)
        assertEquals(2, store.snapshot.size)
        assertTrue(store.snapshot.indexOfNormalized("абыйлар") >= 0)
        assertTrue(store.snapshot.indexOfNormalized("бабай") >= 0)
        assertTrue("and they survive the process, not just the screen", destinationFile(directory).isFile)
    }

    /**
     * Restoring into a list that is not empty. The user may well have typed some of the same words
     * again between the corruption and the moment they find the button, and a restore that put them
     * in twice would be a repair that damages.
     */
    @Test
    fun restoringIntoANonEmptyDictionaryAddsNoDuplicates() {
        val directory = quarantinedDirectory(dropTailBytes = 5)
        val store = store(directory)
        store.addManually("бабай")
        assertEquals(1, store.snapshot.size)

        val outcomes = mutableListOf<Boolean>()
        store.restoreQuarantine { outcomes.add(it) }

        assertEquals(listOf(true), outcomes)
        assertEquals("two distinct words, not three entries", 2, store.snapshot.size)
        assertEquals(
            "and the word typed again is present exactly once",
            1,
            reopenedWords(directory).count { it == "бабай" },
        )
    }

    /** And running it twice is harmless — the button is on a screen, and screens get tapped twice. */
    @Test
    fun restoringTwiceLeavesTheSameListAsRestoringOnce() {
        val directory = quarantinedDirectory(dropTailBytes = 5)
        val store = store(directory)

        store.restoreQuarantine()
        val afterFirst = reopenedWords(directory)
        val outcomes = mutableListOf<Boolean>()
        store.restoreQuarantine { outcomes.add(it) }

        assertEquals("nothing left to do is not a failure", listOf(true), outcomes)
        assertEquals(afterFirst, reopenedWords(directory))
    }

    /**
     * Restoring does NOT remove the copy. The damaged tail is the part no parser could read THIS
     * time; a later, better reader can only try if the bytes are still there. Deleting them is the
     * user's own second decision.
     */
    @Test
    fun restoringLeavesTheCopyWhereItIs() {
        val directory = quarantinedDirectory(dropTailBytes = 5)

        store(directory).restoreQuarantine()

        assertTrue("the damaged tail is not the restore's to destroy", quarantineFile(directory).isFile)
    }

    /** With no copy there is nothing to restore, and the screen must not print "restored". */
    @Test
    fun restoringWithNoCopyPresentIsReportedAsAFailure() {
        val directory = newPersonalDir()
        val outcomes = mutableListOf<Boolean>()

        store(directory).restoreQuarantine { outcomes.add(it) }

        assertEquals(listOf(false), outcomes)
    }

    /**
     * A restore that cannot be written down is a failure, and it has to leave the copy alone: the
     * words are still only in the quarantine slot, and that is exactly when losing it would be
     * unrecoverable.
     */
    @Test
    fun aRestoreThatCannotBeWrittenAnswersFailureAndKeepsTheCopy() {
        val directory = quarantinedDirectory(dropTailBytes = 5)
        val outcomes = mutableListOf<Boolean>()

        store(directory, spaceProbe = SpaceProbe { 0L }).restoreQuarantine { outcomes.add(it) }

        assertEquals(listOf(false), outcomes)
        assertFalse("nothing was published either", destinationFile(directory).isFile)
        assertTrue("and the only copy of those words is still on the device",
            quarantineFile(directory).isFile)
    }

    // ---- B4: the copy can be thrown away --------------------------------------------------------

    /** The other button: the copy goes, and nothing else does. */
    @Test
    fun discardingRemovesTheCopyAndLeavesTheDictionaryAlone() {
        val directory = quarantinedDirectory(dropTailBytes = 5)
        val store = store(directory)
        store.restoreQuarantine()

        val outcomes = mutableListOf<Boolean>()
        store.discardQuarantine { outcomes.add(it) }

        assertEquals(listOf(true), outcomes)
        assertFalse(quarantineFile(directory).exists())
        assertEquals("the restored words stay", 2, store.snapshot.size)
    }

    /** A copy that will not go must be reported as still there — those are the user's own words. */
    @Test
    fun aDiscardThatCannotRemoveTheCopyIsReportedAsAFailure() {
        val directory = quarantinedDirectory(dropTailBytes = 5)
        val stubborn = object : PassthroughOps() {
            override fun delete(file: File): Boolean =
                if (file.name.endsWith(QUARANTINE_SUFFIX)) false else super.delete(file)
        }
        val outcomes = mutableListOf<Boolean>()

        store(directory, ops = stubborn).discardQuarantine { outcomes.add(it) }

        assertEquals(listOf(false), outcomes)
        assertTrue(quarantineFile(directory).isFile)
    }

    // ---- U7: a forgotten word is never resurrected by a restore -----------------------------------

    /**
     * The P1 pairs store pinned the no-resurrection rule first (`aForgottenPairIsNotResurrectedByARestore`);
     * the words store had the hole: `forget` removed the word from the dictionary but left it in the
     * quarantine copy, and the next restore brought it back from the dead. U7 of Phase 2
     * (docs/ROADMAP-P2.md) closes it with the same purge the pairs store runs.
     */
    @Test
    fun aForgottenWordIsNotResurrectedByARestore() {
        val directory = quarantinedDirectoryCorruptingChecksum()
        val store = store(directory)

        val restored = mutableListOf<Boolean>()
        store.restoreQuarantine { restored.add(it) }
        assertEquals(listOf(true), restored)
        assertEquals(3, store.snapshot.size)

        // The user deletes one word; the copy is purged with it.
        var forgotten: Boolean? = null
        store.forget("бабай") { forgotten = it }
        assertEquals(true, forgotten)
        // A SECOND restore must not bring the forgotten word back — erased means erased, in the
        // copy as much as in the list.
        store.restoreQuarantine()
        assertTrue(store.snapshot.indexOfNormalized("бабай") < 0)
        assertEquals("the other two came back", 2, store.snapshot.size)
        assertEquals("and the copy itself no longer carries the word",
            listOf("абыйлар", "гүзәл"), salvageWords(quarantineFile(directory)))
    }

    /**
     * The word need not be in the dictionary for the purge to run: a word that sits ONLY in the
     * copy (the corruption emptied the list, and it was never restored) is still the user's to
     * forget, and the copy must not keep what the user was told is gone.
     */
    @Test
    fun forgettingAWordThatIsOnlyInTheCopyPurgesTheCopyToo() {
        val directory = quarantinedDirectoryCorruptingChecksum()
        val store = store(directory)
        store.prime()
        assertTrue("the corruption emptied the list", store.snapshot.isEmpty)

        var outcome: Boolean? = null
        store.forget("бабай") { outcome = it }

        assertEquals("from where the user stands the word is gone", true, outcome)
        assertEquals(listOf("абыйлар", "гүзәл"), salvageWords(quarantineFile(directory)))
    }

    /** A copy whose every word was forgotten is a copy no longer: the file itself goes away. */
    @Test
    fun purgingTheLastWordOfTheCopyDeletesTheCopy() {
        val directory = quarantinedDirectoryCorruptingChecksum()
        val store = store(directory)
        store.restoreQuarantine()

        store.forget("абыйлар")
        store.forget("бабай")
        store.forget("гүзәл")

        assertFalse("a copy with nothing left in it goes away", quarantineFile(directory).exists())
        assertNull("and nothing remains to inspect", inspect(store))
    }

    /**
     * Fail-closed toward NOT resurrecting: a copy that cannot be rewritten without the deleted
     * word is deleted outright. Losing the salvage of the other words is the smaller lie than
     * keeping a word the user was told is gone.
     */
    @Test
    fun aCopyThatCannotBePurgedIsDeletedOutrightRatherThanLeftToResurrectTheWord() {
        val directory = quarantinedDirectoryCorruptingChecksum()
        val ops = object : PassthroughOps() {
            override fun atomicReplace(source: File, destination: File) {
                if (destination.name.endsWith(QUARANTINE_SUFFIX)) throw IOException("replace failed")
                super.atomicReplace(source, destination)
            }
        }
        // The fault is injected through a store over the same directory: the purge's rewrite of
        // the copy fails there, while the dictionary's own writes still succeed.
        val store = store(directory, ops = ops)
        store.restoreQuarantine()
        assertEquals(3, store.snapshot.size)

        var outcome: Boolean? = null
        store.forget("бабай") { outcome = it }

        assertEquals("the word is gone from the list", true, outcome)
        assertTrue(store.snapshot.indexOfNormalized("бабай") < 0)
        assertFalse(quarantineFile(directory).exists())
    }

    // ---- B5: the notice reaches the user, whatever happens to the process ------------------------

    /**
     * The finding itself. The mark used to be a `@Volatile` field, and the quarantine happens while
     * the store opens — on a keyboard, the moment an input field appears, with no settings screen in
     * sight. The notice waited for a window that never came, the process ended, and the loss was
     * never mentioned again.
     *
     * The second store below IS the next process: a new instance over the same directory, with
     * nothing carried over in memory.
     */
    @Test
    fun aNoticeNobodySawSurvivesTheProcessAndIsRaisedAgainAtTheNextOpen() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        truncateTheDictionary(directory, dropTailBytes = 5)

        var firstProcess = 0
        store(directory, notice = PersonalQuarantineNotice { firstProcess++ }).prime()
        assertEquals("the loss is announced when it happens", 1, firstProcess)
        assertTrue("and written down, because nobody may have been listening",
            noticeMark(directory).isFile)

        var secondProcess = 0
        store(directory, notice = PersonalQuarantineNotice { secondProcess++ }).prime()

        assertEquals("a notice nobody has taken is still owed", 1, secondProcess)
    }

    /** And it stops once it has really been shown — that is what taking the notice means. */
    @Test
    fun theNoticeStopsComingBackOnceItHasBeenDelivered() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        truncateTheDictionary(directory, dropTailBytes = 5)
        val shown = store(directory)
        shown.prime()

        shown.noticeDelivered()

        assertFalse("the mark is spent", noticeMark(directory).exists())
        var later = 0
        store(directory, notice = PersonalQuarantineNotice { later++ }).prime()
        assertEquals("nobody is told twice about the same loss", 0, later)
    }

    /** A dictionary that reads fine leaves no mark: the notice is not a startup event. */
    @Test
    fun aReadableDictionaryLeavesNoMarkBehind() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")

        store(directory).prime()

        assertFalse(noticeMark(directory).exists())
    }

    /**
     * "Erase all words" takes the mark too. The user emptied the list themselves; being told
     * afterwards that something was set aside would be a notice about nothing.
     */
    @Test
    fun erasingEverythingTakesTheMarkWithTheWords() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        truncateTheDictionary(directory, dropTailBytes = 5)
        store(directory).prime()
        assertTrue(noticeMark(directory).isFile)

        store(directory).clearAll()

        assertFalse(noticeMark(directory).exists())
        var later = 0
        store(directory, notice = PersonalQuarantineNotice { later++ }).prime()
        assertEquals(0, later)
    }

    /**
     * But a mark that will not delete may NOT sink the erasure's answer. It is not one of the user's
     * words; turning "your words are gone" into "the erasure failed" is the same class of lie as the
     * one this whole register exists to remove, only pointing the other way.
     */
    @Test
    fun aMarkThatCannotBeDeletedDoesNotTurnASuccessfulErasureIntoAFailure() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        truncateTheDictionary(directory, dropTailBytes = 5)
        store(directory).prime()

        val stubborn = object : PassthroughOps() {
            override fun delete(file: File): Boolean =
                if (file.name.endsWith(NOTICE_SUFFIX)) false else super.delete(file)
        }
        val outcomes = mutableListOf<Boolean>()
        store(directory, ops = stubborn).clearAll { outcomes.add(it) }

        assertTrue("the mark is the file that stayed", noticeMark(directory).isFile)
        assertEquals("the words did go, and that is what was asked", listOf(true), outcomes)
    }

    /**
     * And when the mark cannot be WRITTEN, this session still says it out loud. A silent loss is the
     * one outcome the whole feature exists to prevent; losing durability is bad, losing the sentence
     * is worse.
     */
    @Test
    fun aMarkThatCannotBeWrittenStillLeavesTheNoticeRaisedInThisSession() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        truncateTheDictionary(directory, dropTailBytes = 5)
        val cannotCreate = object : PassthroughOps() {
            override fun createNewFile(file: File): Boolean = throw IOException("no temp")
        }

        var notices = 0
        store(directory, ops = cannotCreate, notice = PersonalQuarantineNotice { notices++ }).prime()

        assertEquals(1, notices)
    }

    // ---- B6: nothing a caller does may kill the worker ------------------------------------------

    /**
     * The hole all three mutations shared. Each one guarded its body and then reported OUTSIDE the
     * guard, and the report is a callback owned by an Activity that posts to the UI thread: a
     * detached screen, a dead Handler, a listener swapped mid-flight — any of them throws from `post`
     * itself. On the production worker, a bare single-thread executor built with no
     * `UncaughtExceptionHandler`, that throw reaches `KillApplicationHandler` and the keyboard dies
     * while typing in someone else's app. The executor below is that worker in miniature, with a
     * handler standing exactly where the killer stands.
     */
    @Test
    fun aCallbackThatThrowsDoesNotKillTheWorkerOnAnyMutation() {
        for (case in mutationsWithAThrowingCallback()) {
            val directory = quarantinedDirectory(dropTailBytes = 5)
            val uncaught = mutableListOf<Throwable>()
            val store = store(directory, executor = workerWithKiller(uncaught))
            store.addManually("сүзлек")

            case.run(store)

            assertEquals(
                "'${case.name}': in production this list is a dead IME",
                emptyList<Throwable>(), uncaught,
            )
        }
    }

    /** The same for the inspection: it answers a screen through the same kind of seam. */
    @Test
    fun anInspectionSinkThatThrowsDoesNotKillTheWorker() {
        val directory = quarantinedDirectory(dropTailBytes = 5)
        val uncaught = mutableListOf<Throwable>()

        store(directory, executor = workerWithKiller(uncaught))
            .inspectQuarantine { throw RuntimeException("the screen went away") }

        assertEquals(emptyList<Throwable>(), uncaught)
    }

    /** And for the notice seam, which reaches an Activity by exactly the same road. */
    @Test
    fun aNoticeSeamThatThrowsDoesNotKillTheWorker() {
        val directory = newPersonalDir()
        store(directory).addManually("абыйлар")
        truncateTheDictionary(directory, dropTailBytes = 5)
        val uncaught = mutableListOf<Throwable>()

        store(
            directory,
            executor = workerWithKiller(uncaught),
            notice = PersonalQuarantineNotice { throw RuntimeException("the window went away") },
        ).prime()

        assertEquals(emptyList<Throwable>(), uncaught)
    }

    /**
     * The control that makes the four tests above worth their lines: the harness really does collect
     * what escapes a mutation. Without it, an executor that swallowed everything would make them all
     * pass over any implementation at all.
     */
    @Test
    fun theHarnessReallyCatchesWhatEscapesTheWorker() {
        val uncaught = mutableListOf<Throwable>()

        workerWithKiller(uncaught).execute { throw RuntimeException("escaped") }

        assertEquals(1, uncaught.size)
    }

    // ---- helpers --------------------------------------------------------------------------------

    private class Mutation(val name: String, val run: (PersonalDictionaryStore) -> Unit)

    private fun mutationsWithAThrowingCallback(): List<Mutation> {
        val thrower = PersonalMutationOutcome { throw RuntimeException("the screen went away") }
        return listOf(
            Mutation("addManually, saved") { it.addManually("китаплар", thrower) },
            Mutation("addManually, rejected") { it.addManually("ab1", thrower) },
            Mutation("forget, present") { it.forget("сүзлек", thrower) },
            Mutation("forget, absent") { it.forget("юк", thrower) },
            Mutation("restoreQuarantine") { it.restoreQuarantine(thrower) },
            Mutation("discardQuarantine") { it.discardQuarantine(thrower) },
            // Last, because it empties the directory the earlier cases work over.
            Mutation("clearAll") { it.clearAll(thrower) },
        )
    }

    private val directExecutor = Executor { it.run() }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    /**
     * The production worker in miniature: one thread per event, joined so the test stays as
     * sequential as the direct executor, and an `UncaughtExceptionHandler` standing where
     * `KillApplicationHandler` stands in the app.
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
        spaceProbe: SpaceProbe = SpaceProbe { Long.MAX_VALUE },
        unlockGate: () -> Boolean = { true },
        executor: Executor = directExecutor,
        notice: PersonalQuarantineNotice? = null,
    ): PersonalDictionaryStore = PersonalDictionaryStore(
        subtypeId = subtype,
        directoryProvider = { directory },
        fileOps = ops,
        outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
        spaceProbe = spaceProbe,
        clock = { 1000L },
        executor = executor,
        unlockGate = unlockGate,
        quarantineNotice = notice,
    )

    private fun inspect(store: PersonalDictionaryStore): PersonalQuarantineReport? {
        val seen = mutableListOf<PersonalQuarantineReport?>()
        store.inspectQuarantine { seen.add(it) }
        assertEquals("exactly one answer per inspection", 1, seen.size)
        return seen[0]
    }

    /**
     * Three words are written, the file is cut short the way an interrupted write cuts it, and the
     * store is opened once so the damaged file lands in the quarantine slot. Exactly one of
     * [dropTailBytes] and [truncateTo] is meaningful; both produce a real, really quarantined copy.
     */
    private fun quarantinedDirectory(dropTailBytes: Int = 0, truncateTo: Int = -1): File {
        val directory = newPersonalDir()
        store(directory).apply {
            addManually("абыйлар")
            addManually("бабай")
            addManually("гүзәл")
        }
        truncateTheDictionary(directory, dropTailBytes, truncateTo)
        store(directory).prime()
        assertTrue("the harness must really produce a copy", quarantineFile(directory).isFile)
        return directory
    }

    /**
     * Three words written, then quarantined by a CHECKSUM flip rather than a cut: the validator
     * refuses the file while every record of it still parses, so the copy salvages whole. The
     * no-resurrection tests need exactly that — a restore has to have all three words to bring back.
     */
    private fun quarantinedDirectoryCorruptingChecksum(): File {
        val directory = newPersonalDir()
        store(directory).apply {
            addManually("абыйлар")
            addManually("бабай")
            addManually("гүзәл")
        }
        val destination = destinationFile(directory)
        val bytes = destination.readBytes()
        bytes[TpersFormat.CHECKSUM_OFFSET] =
            (bytes[TpersFormat.CHECKSUM_OFFSET].toInt() xor 0xFF).toByte()
        destination.writeBytes(bytes)
        store(directory).prime()
        assertTrue("the harness must really produce a copy", quarantineFile(directory).isFile)
        return directory
    }

    /** The words still readable inside a copy, in their on-disk order — the purge is real only if they are gone from here too. */
    private fun salvageWords(copy: File): List<String> =
        PersonalQuarantineSalvage.read(copy, subtype)?.normalizedForms
            ?: error("the harness expects a readable copy")

    /** Cuts the tail off the dictionary file: the shape a power cut mid-write leaves behind. */
    private fun truncateTheDictionary(directory: File, dropTailBytes: Int = 0, truncateTo: Int = -1) {
        val destination = destinationFile(directory)
        val bytes = destination.readBytes()
        val keep = if (truncateTo >= 0) truncateTo else bytes.size - dropTailBytes
        destination.writeBytes(bytes.copyOf(keep))
    }

    /** The words a FRESH reader finds on disk — the restore is only real if it survives reopening. */
    private fun reopenedWords(directory: File): List<String> {
        val store = store(directory)
        store.prime()
        val snapshot = store.snapshot
        return (0 until snapshot.size).map { snapshot.rawFormAt(it) }.sorted()
    }

    private fun destinationFile(directory: File) =
        File(directory, TpersFormat.personalFileName(subtype))

    private fun quarantineFile(directory: File) =
        File(directory, TpersFormat.personalFileName(subtype) + QUARANTINE_SUFFIX)

    private fun noticeMark(directory: File) =
        File(directory, "quarantine-notice-$subtype-s1-f1.flag")

    private companion object {
        /** The names the store builds; see `PersonalDictionaryStore`. */
        const val QUARANTINE_SUFFIX = ".quarantine"
        const val NOTICE_SUFFIX = ".flag"
    }

    /** Performs every durable op for real; a test overrides the one it wants to fail. */
    private open class PassthroughOps : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()

        override fun syncFile(fileDescriptor: FileDescriptor) = fileDescriptor.sync()

        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }

        override fun atomicReplace(source: File, destination: File) {
            if (!source.renameTo(destination)) throw IOException("replace failed")
        }

        override fun delete(file: File): Boolean = file.delete()

        override fun syncDirectory(directory: File) = Unit
    }
}
