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
