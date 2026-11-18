/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/** The counts-only inventory and category deletion over the real personal stores. Plain JVM. */
class PersonalInventoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun emptyInventoryIsCompleteAndHasNoDeletableCategory() {
        val fixture = Fixture(newPersonalDir())

        val snapshot = fixture.snapshot()

        assertTrue(snapshot.unlocked)
        assertTrue(snapshot.complete)
        assertTrue(snapshot.isEmpty)
        for (category in PersonalInventoryCategory.values()) {
            assertEquals(0, snapshot.countOf(category))
            assertFalse(snapshot.hasData(category))
        }
    }

    @Test
    fun deletingEachCategoryLeavesEveryOtherCategoryUntouched() {
        for (category in PersonalInventoryCategory.values()) {
            val fixture = Fixture(newPersonalDir()).populate()
            val before = fixture.snapshot()
            assertEquals(1, before.countOf(PersonalInventoryCategory.WORDS))
            assertEquals(1, before.countOf(PersonalInventoryCategory.PAIRS))
            assertEquals(1, before.countOf(PersonalInventoryCategory.EMOJI))
            assertEquals(1, before.countOf(PersonalInventoryCategory.SENTENCE_STARTS))
            assertEquals(1, before.countOf(PersonalInventoryCategory.REFUSED_CORRECTIONS))
            assertEquals(1, before.countOf(PersonalInventoryCategory.TEXT_SHORTCUTS))
            assertEquals(4, before.countOf(PersonalInventoryCategory.PENDING_COUNTERS))
            assertEquals(2, before.countOf(PersonalInventoryCategory.QUARANTINE))

            val (cleared, after) = fixture.clear(category)

            assertTrue("$category deletion", cleared)
            assertTrue("$category fresh snapshot", after.complete)
            assertEquals("$category count after deletion", 0, after.countOf(category))
            assertFalse("$category has no remaining data", after.hasData(category))
            if (category == PersonalInventoryCategory.WORDS) {
                val pendingFile = File(fixture.directory,
                    "pending-${PersonalSubtypes.TATAR_RU}-s1-f1.bin")
                assertTrue("word deletion keeps its pending file", pendingFile.isFile)
                assertEquals("word deletion keeps the serialized pending counter",
                    1, PendingCounters.parse(pendingFile.readBytes()).size)
            }
            for (other in PersonalInventoryCategory.values()) {
                if (other == category) continue
                assertEquals("$category must not change $other",
                    before.countOf(other), after.countOf(other))
                if (other == PersonalInventoryCategory.PENDING_COUNTERS) {
                    assertEquals(before.pendingDataPresent, after.pendingDataPresent)
                }
            }
            if (category == PersonalInventoryCategory.PENDING_COUNTERS) {
                assertFalse(fixture.directory.listFiles()!!.any {
                    it.name.startsWith("pending-") && !it.name.endsWith(".quarantine") ||
                        it.name.startsWith("salt-")
                })
                assertTrue("quarantine is a separate category",
                    fixture.directory.listFiles()!!.any { it.name.endsWith(".quarantine") })
            }
            if (category == PersonalInventoryCategory.QUARANTINE) {
                assertFalse(fixture.directory.listFiles()!!.any { it.name.endsWith(".quarantine") })
                assertTrue("pending counters stay", fixture.directory.listFiles()!!.any {
                    it.name.startsWith("pending-")
                })
            }
        }
    }

    @Test
    fun entryDeletionKeepsUnflushedPendingCountersAndFlushesThemLater() {
        val categories = listOf(
            PersonalInventoryCategory.WORDS,
            PersonalInventoryCategory.PAIRS,
            PersonalInventoryCategory.EMOJI,
            PersonalInventoryCategory.SENTENCE_STARTS,
        )
        for (category in categories) {
            val fixture = Fixture(newPersonalDir()).populate()
            fixture.noteUnflushedPending(category)
            assertEquals(5, fixture.snapshot().countOf(PersonalInventoryCategory.PENDING_COUNTERS))

            val (cleared, after) = fixture.clear(category)

            assertTrue("$category deletion", cleared)
            assertEquals("$category keeps both unflushed and flushed counters",
                5, after.countOf(PersonalInventoryCategory.PENDING_COUNTERS))
            fixture.flush(category)
            val reopened = fixture.reopenedInventory(category)
            assertEquals("$category is deleted on disk", 0, reopened.entryCount)
            assertEquals("$category pending counters survive a disk reopen",
                2, reopened.pendingCount)
        }
    }

    @Test
    fun pendingDeletionKeepsUnflushedUsageCountersOnDisk() {
        val fixture = Fixture(newPersonalDir()).populate()
        val expected = fixture.bumpUsageWithoutFlush()

        val (cleared, _) = fixture.clear(PersonalInventoryCategory.PENDING_COUNTERS)
        fixture.flushAll()

        assertTrue(cleared)
        for ((category, usageCount) in expected) {
            assertEquals("$category keeps its unflushed usage counter after a disk reopen",
                usageCount, fixture.reopenedUsageCount(category))
            assertEquals("$category pending file stays deleted",
                0, fixture.reopenedInventory(category).pendingCount)
        }
    }

    @Test
    fun unreadableCategoryIsDeletableAndInventoryIsNotEmpty() {
        val directory = newPersonalDir()
        val failing = object : PersonalInventoryTarget {
            override fun readInventoryOnWorker(): PersonalStoreInventory =
                throw IOException("read failed")

            override fun clearEntriesOnWorker(): Boolean = true
        }
        val engine = PersonalInventoryEngine(
            targetsByCategory = mapOf(PersonalInventoryCategory.WORDS to listOf(failing)),
            directoryProvider = { directory },
            fileOps = RealOps,
            executor = direct,
            unlockGate = { true },
        )

        var snapshot: PersonalInventorySnapshot? = null
        engine.snapshot { snapshot = it }
        assertFalse(requireNotNull(snapshot).complete)
        assertFalse("an unreadable category must not produce the empty state", snapshot.isEmpty)
        assertTrue("an unreadable category still offers deletion",
            snapshot.hasData(PersonalInventoryCategory.WORDS))

        var cleared: Boolean? = null
        engine.clear(PersonalInventoryCategory.WORDS) { result, _ -> cleared = result }
        assertEquals(true, cleared)
    }

    @Test
    fun quarantineDeletionRemovesCopiesAndNoticeFlags() {
        val fixture = Fixture(newPersonalDir()).populate()
        val subtype = PersonalSubtypes.TATAR_RU
        for (name in listOf(
            "quarantine-notice-$subtype-s1-f1.flag",
            "quarantine-notice-$subtype-s1-f1-pending.flag",
            "quarantine-notice-bigrams-$subtype-s1-f1.flag",
            "quarantine-notice-emoji-$subtype-s1-f1.flag",
            "quarantine-notice-sentstart-$subtype-s1-f1.flag",
        )) {
            File(fixture.directory, name).writeBytes(ByteArray(1))
        }

        val (cleared, _) = fixture.clear(PersonalInventoryCategory.QUARANTINE)

        assertTrue(cleared)
        assertFalse(fixture.directory.listFiles()!!.any {
            it.name.endsWith(".quarantine") || it.name.startsWith("quarantine-notice-")
        })
    }

    @Test
    fun lockedInventoryShowsNoCountsAndDeletionLeavesEveryFileInPlace() {
        val fixture = Fixture(newPersonalDir()).populate()
        val before = fixture.directory.list()!!.toSet()
        fixture.lock()

        val locked = fixture.snapshot()
        val (cleared, after) = fixture.clear(PersonalInventoryCategory.WORDS)

        assertFalse(locked.unlocked)
        assertFalse(locked.complete)
        for (category in PersonalInventoryCategory.values()) {
            assertEquals(0, locked.countOf(category))
        }
        assertFalse(cleared)
        assertFalse(after.unlocked)
        assertEquals(before, fixture.directory.list()!!.toSet())
    }

    @Test
    fun existingEraseEverythingStillVerifiesTheFinalEmptyDirectory() {
        val fixture = Fixture(newPersonalDir()).populate()
        var erased: Boolean? = null

        PersonalEraseAll(
            stores = fixture.erasables,
            directoryProvider = { fixture.directory },
            fileOps = RealOps,
            executor = direct,
            unlockGate = { true },
        ).run { erased = it }

        assertEquals(true, erased)
        assertEquals(emptyList<String>(), fixture.directory.list()!!.toList())
        assertTrue(fixture.snapshot().isEmpty)
    }

    private inner class Fixture(val directory: File) {
        private var unlocked = true
        private val gate = { unlocked }
        private val subtype = PersonalSubtypes.TATAR_RU
        private val words = PersonalDictionaryStore(subtype, { directory }, RealOps, opener, roomy,
            { 1000L }, direct, unlockGate = gate)
        private val pairs = PersonalBigramStore(subtype, { directory }, RealOps, opener, roomy,
            { 1000L }, direct, contextMembership = { _, _ -> true }, unlockGate = gate)
        private val emoji = PersonalEmojiStore(subtype, { directory }, RealOps, opener, roomy,
            { 1000L }, direct, unlockGate = gate)
        private val starts = SentStartOverlayStore(subtype, { directory }, RealOps, opener, roomy,
            { 1000L }, direct, unlockGate = gate)
        private val refusals = RefusedCorrectionStore(subtype, { directory }, RealOps, opener, roomy,
            { 1000L }, direct, unlockGate = gate)
        private val shortcuts = TextShortcutStore({ directory }, RealOps, opener, roomy,
            { 1000L }, direct, unlockGate = gate)
        private val engine = PersonalInventoryEngine(
            targetsByCategory = mapOf(
                PersonalInventoryCategory.WORDS to listOf(words),
                PersonalInventoryCategory.PAIRS to listOf(pairs),
                PersonalInventoryCategory.EMOJI to listOf(emoji),
                PersonalInventoryCategory.SENTENCE_STARTS to listOf(starts),
                PersonalInventoryCategory.REFUSED_CORRECTIONS to listOf(refusals),
                PersonalInventoryCategory.TEXT_SHORTCUTS to listOf(shortcuts),
                PersonalInventoryCategory.PENDING_COUNTERS to listOf(words, pairs, emoji, starts),
                PersonalInventoryCategory.QUARANTINE to emptyList(),
            ),
            directoryProvider = { directory },
            fileOps = RealOps,
            executor = direct,
            unlockGate = gate,
        )
        val erasables = listOf(
            PersonalErasable { words.clearAll(it) },
            PersonalErasable { pairs.clearAll(it) },
            PersonalErasable { emoji.clearAll(it) },
            PersonalErasable { starts.clearAll(it) },
            PersonalErasable { refusals.clearAll(it) },
            PersonalErasable { shortcuts.clearAll(it) },
        )

        fun populate(): Fixture {
            words.addManually("абыйлар")
            words.noteCompletion("дөнья")
            words.flush()
            repeat(PersonalBigramStore.LEARN_THRESHOLD) { pairs.notePair("китап", "укыйм") }
            pairs.notePair("матур", "көн")
            pairs.flush()
            repeat(PersonalEmojiStore.LEARN_THRESHOLD) { emoji.noteObservation("сәләм", "☀️") }
            emoji.noteObservation("бәйрәм", "🎉")
            emoji.flush()
            repeat(PendingCounters.LEARN_THRESHOLD) { starts.noteSentenceStart("Сәлам") }
            starts.noteSentenceStart("Бүген")
            starts.flush()
            refusals.noteRefusal("китаб", "китап")
            shortcuts.put("кт", "китап")

            val wordFile = File(directory, TpersFormat.personalFileName(subtype))
            File(directory, wordFile.name + ".quarantine").writeBytes(wordFile.readBytes())
            val pendingFile = File(directory, "pending-$subtype-s1-f1.bin")
            File(directory, pendingFile.name + ".quarantine").writeBytes(pendingFile.readBytes())
            return this
        }

        fun snapshot(): PersonalInventorySnapshot {
            var result: PersonalInventorySnapshot? = null
            engine.snapshot { result = it }
            return requireNotNull(result)
        }

        fun clear(category: PersonalInventoryCategory): Pair<Boolean, PersonalInventorySnapshot> {
            var cleared: Boolean? = null
            var after: PersonalInventorySnapshot? = null
            engine.clear(category) { succeeded, snapshot ->
                cleared = succeeded
                after = snapshot
            }
            return requireNotNull(cleared) to requireNotNull(after)
        }

        fun noteUnflushedPending(category: PersonalInventoryCategory) {
            when (category) {
                PersonalInventoryCategory.WORDS -> words.noteCompletion("сүзләр")
                PersonalInventoryCategory.PAIRS -> pairs.notePair("яңа", "сүз")
                PersonalInventoryCategory.EMOJI -> emoji.noteObservation("яңа", "🌙")
                PersonalInventoryCategory.SENTENCE_STARTS -> starts.noteSentenceStart("Яңа")
                else -> error("not a learning category")
            }
        }

        fun flush(category: PersonalInventoryCategory) {
            when (category) {
                PersonalInventoryCategory.WORDS -> words.flush()
                PersonalInventoryCategory.PAIRS -> pairs.flush()
                PersonalInventoryCategory.EMOJI -> emoji.flush()
                PersonalInventoryCategory.SENTENCE_STARTS -> starts.flush()
                else -> error("not a learning category")
            }
        }

        fun flushAll() {
            for (category in listOf(
                PersonalInventoryCategory.WORDS,
                PersonalInventoryCategory.PAIRS,
                PersonalInventoryCategory.EMOJI,
                PersonalInventoryCategory.SENTENCE_STARTS,
            )) {
                flush(category)
            }
        }

        fun reopenedInventory(category: PersonalInventoryCategory): PersonalStoreInventory =
            newTarget(category).readInventoryOnWorker()

        fun bumpUsageWithoutFlush(): Map<PersonalInventoryCategory, Int> {
            words.noteAcceptedSuggestion("абыйлар")
            pairs.noteAcceptedPrediction("китап", "укыйм")
            emoji.noteUse("сәләм", "☀️")
            val startsFile = File(directory, "sentstart-$subtype-s1-f1.tsent")
            val expectedStarts = TsentValidator().validate(startsFile, subtype).usageCounts[0] + 1
            starts.noteSentenceStart("Сәлам")
            return mapOf(
                PersonalInventoryCategory.WORDS to words.snapshot.usageCountAt(0),
                PersonalInventoryCategory.PAIRS to pairs.snapshot.usageCountAt(0),
                PersonalInventoryCategory.EMOJI to emoji.snapshot.usageCountAt(0),
                PersonalInventoryCategory.SENTENCE_STARTS to expectedStarts,
            )
        }

        fun reopenedUsageCount(category: PersonalInventoryCategory): Int {
            val target = newTarget(category)
            target.readInventoryOnWorker()
            return when (target) {
                is PersonalDictionaryStore -> target.snapshot.usageCountAt(0)
                is PersonalBigramStore -> target.snapshot.usageCountAt(0)
                is PersonalEmojiStore -> target.snapshot.usageCountAt(0)
                is SentStartOverlayStore -> TsentValidator().validate(
                    File(directory, "sentstart-$subtype-s1-f1.tsent"), subtype).usageCounts[0]
                else -> error("not a learning category")
            }
        }

        private fun newTarget(category: PersonalInventoryCategory): PersonalInventoryTarget =
            when (category) {
                PersonalInventoryCategory.WORDS ->
                    PersonalDictionaryStore(subtype, { directory }, RealOps, opener, roomy,
                        { 1000L }, direct, unlockGate = gate)
                PersonalInventoryCategory.PAIRS ->
                    PersonalBigramStore(subtype, { directory }, RealOps, opener, roomy,
                        { 1000L }, direct, contextMembership = { _, _ -> true }, unlockGate = gate)
                PersonalInventoryCategory.EMOJI ->
                    PersonalEmojiStore(subtype, { directory }, RealOps, opener, roomy,
                        { 1000L }, direct, unlockGate = gate)
                PersonalInventoryCategory.SENTENCE_STARTS ->
                    SentStartOverlayStore(subtype, { directory }, RealOps, opener, roomy,
                        { 1000L }, direct, unlockGate = gate)
                else -> error("not a learning category")
            }

        fun lock() {
            unlocked = false
        }
    }

    private val direct = Executor { it.run() }
    private val opener = PersonalOutputOpener { temp -> FileOutputStream(temp) }
    private val roomy = SpaceProbe { Long.MAX_VALUE }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private object RealOps : DurableFileOps {
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
