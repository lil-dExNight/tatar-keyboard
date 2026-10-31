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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TcutFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TrefFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import java.nio.file.Files

/**
 * "Erase everything saved" over real stores in one directory: every kind of file the personal
 * stores write (saved entries, pending counters, salts, quarantined copies, notice marks) plus
 * stray temps and unknown files must be gone afterwards, and only then is success reported.
 */
class PersonalEraseAllTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val languages = listOf(PersonalSubtypes.TATAR_RU, PersonalSubtypes.RUSSIAN)

    @Test
    fun erasingEverythingLeavesNoFileInThePersonalDirectory() {
        val directory = newPersonalDir()
        val stores = populatedStores(directory)
        val names = directory.list()!!.toSet()
        for (subtype in languages) {
            for (expected in listOf(
                TpersFormat.personalFileName(subtype),
                TpersbFormat.personalBigramsFileName(subtype),
                TpersemFormat.personalEmojiFileName(subtype),
                TsentFormat.sentStartFileName(subtype),
                TrefFormat.refusedCorrectionsFileName(subtype),
                TpersFormat.personalFileName(subtype) + ".quarantine",
                TrefFormat.refusedCorrectionsFileName(subtype) + ".quarantine",
                "pending-$subtype-s1-f1.bin",
                "pending-sentstart-$subtype-s1-f1.bin.quarantine",
                "quarantine-notice-emoji-$subtype-s1-f1.flag",
            )) {
                assertTrue("the setup wrote $expected", expected in names)
            }
        }
        assertTrue(TcutFormat.shortcutsFileName() in names)
        assertTrue(TcutFormat.shortcutsFileName() + ".quarantine" in names)
        assertTrue("salt.bin" in names && "salt-bigrams.bin" in names)
        assertTrue("salt-emoji.bin" in names && "salt-sentstart.bin" in names)

        val answers = mutableListOf<Boolean>()
        eraser(directory, stores).run { answers.add(it) }

        assertEquals(listOf(true), answers)
        assertEquals("nothing is left", emptyList<String>(), directory.list()!!.toList())
        assertTrue("and nothing is still shown", stores.all { it.isEmpty() })
    }

    @Test
    fun aFileThatCannotBeDeletedIsAFailedErasure() {
        val directory = newPersonalDir()
        val stores = populatedStores(directory)
        val stubborn = File(directory, "future-format.bin")
        val ops = object : RealOps() {
            override fun delete(file: File): Boolean =
                if (file.name == stubborn.name) false else super.delete(file)
        }

        val answers = mutableListOf<Boolean>()
        eraser(directory, stores, ops = ops).run { answers.add(it) }

        assertEquals(listOf(false), answers)
        assertEquals(listOf(stubborn.name), directory.list()!!.toList())
    }

    @Test
    fun aStoreThatReportsAFailureFailsTheErasureEvenWhenTheDirectoryEndsUpEmpty() {
        val directory = newPersonalDir()
        val failing = PersonalErasable { outcome -> outcome.onFinished(false) }

        val answers = mutableListOf<Boolean>()
        eraser(directory, emptyList(), extra = listOf(failing)).run { answers.add(it) }

        assertEquals(listOf(false), answers)
    }

    @Test
    fun beforeTheFirstUnlockNothingIsDeletedAndTheErasureFails() {
        val directory = newPersonalDir()
        var unlocked = true
        val stores = populatedStores(directory) { unlocked }
        val before = directory.list()!!.toSet()
        unlocked = false

        val answers = mutableListOf<Boolean>()
        eraser(directory, stores, unlocked = false).run { answers.add(it) }

        assertEquals(listOf(false), answers)
        assertEquals(before, directory.list()!!.toSet())
    }

    @Test
    fun aLinkInsideTheDirectoryIsRemovedWithoutEnteringIt() {
        val directory = newPersonalDir()
        val outside = temporaryFolder.newFolder("outside")
        val kept = File(outside, "keep.txt").also { it.writeText("x") }
        try {
            Files.createSymbolicLink(File(directory, "link").toPath(), outside.toPath())
        } catch (_: Exception) {
            return // the file system cannot hold a link; nothing to check
        }

        val answers = mutableListOf<Boolean>()
        eraser(directory, emptyList()).run { answers.add(it) }

        assertEquals(listOf(true), answers)
        assertTrue("the sweep never leaves the personal directory", kept.isFile)
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test
    fun theProbeSeesAnyFileAndOnlyThen() {
        val directory = newPersonalDir()
        val answers = mutableListOf<Boolean>()
        eraser(directory, emptyList()).hasSavedFiles { answers.add(it) }
        File(directory, ".personal-stale.tmp").writeBytes(byteArrayOf(1))
        eraser(directory, emptyList()).hasSavedFiles { answers.add(it) }
        eraser(File(directory, "absent"), emptyList()).hasSavedFiles { answers.add(it) }

        assertEquals(listOf(false, true, false), answers)
    }

    // ---- the populated directory ----------------------------------------------------------------

    /** One erasable store and a check of its published snapshot. */
    private class Store(val erasable: PersonalErasable, val isEmpty: () -> Boolean)

    /**
     * Every store of both languages over [directory], with: a damaged main file set aside for the
     * words, refused corrections and text shortcuts; a damaged pending file set aside for the
     * sentence starts; a saved entry and a pending counter in each learning store; and stray files.
     */
    private fun populatedStores(directory: File, gate: () -> Boolean = { true }): List<Store> {
        val garbage = ByteArray(64) { 0x5a }
        for (subtype in languages) {
            File(directory, TpersFormat.personalFileName(subtype)).writeBytes(garbage)
            File(directory, TrefFormat.refusedCorrectionsFileName(subtype)).writeBytes(garbage)
            File(directory, "pending-sentstart-$subtype-s1-f1.bin").writeBytes(garbage)
            File(directory, TpersemFormat.personalEmojiFileName(subtype)).writeBytes(garbage)
        }
        File(directory, TcutFormat.shortcutsFileName()).writeBytes(garbage)

        val result = ArrayList<Store>()
        for (subtype in languages) {
            val tatar = subtype == PersonalSubtypes.TATAR_RU
            val words = PersonalDictionaryStore(subtype, { directory }, RealOps(), opener, roomy,
                { 1000L }, direct, unlockGate = gate)
            words.prime()
            words.addManually(if (tatar) "абыйлар" else "привет")
            words.noteCompletion(if (tatar) "дөнья" else "мир")
            words.flush()
            result.add(Store({ words.clearAll(it) }, { words.snapshot.isEmpty }))

            val pairs = PersonalBigramStore(subtype, { directory }, RealOps(), opener, roomy,
                { 1000L }, direct, contextMembership = { _, _ -> true }, unlockGate = gate)
            repeat(PersonalBigramStore.LEARN_THRESHOLD) {
                pairs.notePair(if (tatar) "китап" else "добрый", if (tatar) "укыйм" else "день")
            }
            pairs.notePair(if (tatar) "матур" else "белый", if (tatar) "көн" else "снег")
            pairs.flush()
            result.add(Store({ pairs.clearAll(it) }, { pairs.snapshot.isEmpty }))

            val emoji = PersonalEmojiStore(subtype, { directory }, RealOps(), opener, roomy,
                { 1000L }, direct, unlockGate = gate)
            emoji.prime()
            repeat(PersonalEmojiStore.LEARN_THRESHOLD) {
                emoji.noteObservation(if (tatar) "сәләм" else "солнце", "☀️")
            }
            emoji.noteObservation(if (tatar) "бәйрәм" else "праздник", "🎉")
            emoji.flush()
            result.add(Store({ emoji.clearAll(it) }, { emoji.snapshot.isEmpty }))

            val starts = SentStartOverlayStore(subtype, { directory }, RealOps(), opener, roomy,
                { 1000L }, direct, unlockGate = gate)
            repeat(PendingCounters.LEARN_THRESHOLD) {
                starts.noteSentenceStart(if (tatar) "Сәлам" else "Привет")
            }
            starts.noteSentenceStart(if (tatar) "Бүген" else "Сегодня")
            starts.flush()
            result.add(Store({ starts.clearAll(it) }, { starts.snapshot.isEmpty }))

            val refusals = RefusedCorrectionStore(subtype, { directory }, RealOps(), opener, roomy,
                { 1000L }, direct, unlockGate = gate)
            refusals.noteRefusal(if (tatar) "китаб" else "превет", if (tatar) "китап" else "привет")
            result.add(Store({ refusals.clearAll(it) }, { refusals.snapshot.isEmpty }))
        }
        val shortcuts = TextShortcutStore({ directory }, RealOps(), opener, roomy, { 1000L }, direct,
            unlockGate = gate)
        shortcuts.put("кт", "китап")
        result.add(Store({ shortcuts.clearAll(it) }, { shortcuts.snapshot.isEmpty }))

        // What no store names: a temp a dead process left, a file of another build, a folder.
        File(directory, ".personal-tt_RU.1.0.tmp").writeBytes(byteArrayOf(1))
        File(directory, ".personal-emoji-ru.1.0.tmp").writeBytes(byteArrayOf(1))
        File(directory, "future-format.bin").writeBytes(byteArrayOf(1))
        File(directory, "nested").mkdir()
        File(directory, "nested/inner.bin").writeBytes(byteArrayOf(1))
        return result
    }

    private fun eraser(
        directory: File,
        stores: List<Store>,
        ops: DurableFileOps = RealOps(),
        unlocked: Boolean = true,
        extra: List<PersonalErasable> = emptyList(),
    ) = PersonalEraseAll(
        stores = stores.map { it.erasable } + extra,
        directoryProvider = { directory },
        fileOps = ops,
        executor = direct,
        unlockGate = { unlocked },
    )

    private val direct = Executor { it.run() }
    private val opener = PersonalOutputOpener { temp -> FileOutputStream(temp) }
    private val roomy = SpaceProbe { Long.MAX_VALUE }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

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
}
