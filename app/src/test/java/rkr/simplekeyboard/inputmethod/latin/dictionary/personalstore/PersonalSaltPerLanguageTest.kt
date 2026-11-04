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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The pending-counter salt is per language (`salt-<tag>-s1-f1.bin` and the kindred files of the
 * pair, emoji and sentence-start stores): erasing one language must leave the other language's
 * salt and pending counters untouched, so its half-learned words still graduate after a process
 * restart. A fresh store over the same directory stands in for the restart.
 */
class PersonalSaltPerLanguageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val tatar = PersonalSubtypes.TATAR_RU
    private val russian = PersonalSubtypes.RUSSIAN

    @Test
    fun clearingOneLanguageKeepsTheOtherLanguagesWordProgress() {
        val directory = newPersonalDir()
        val tatarStore = wordStore(tatar, directory)
        val russianStore = wordStore(russian, directory)
        repeat(PendingCounters.LEARN_THRESHOLD - 1) { tatarStore.noteCompletion("дөнья") }
        tatarStore.flush()
        repeat(PendingCounters.LEARN_THRESHOLD - 1) { russianStore.noteCompletion("мир") }
        russianStore.flush()

        val tatarSalt = File(directory, "salt-$tatar-s1-f1.bin")
        val russianSalt = File(directory, "salt-$russian-s1-f1.bin")
        assertTrue("each language has its own salt file", tatarSalt.isFile && russianSalt.isFile)
        assertFalse("two languages never share one salt",
            tatarSalt.readBytes().contentEquals(russianSalt.readBytes()))

        var outcome: Boolean? = null
        tatarStore.clearAll { outcome = it }
        assertEquals(true, outcome)
        assertFalse(tatarSalt.exists())
        assertTrue("the other language's salt survives", russianSalt.isFile)
        assertTrue(File(directory, "pending-$russian-s1-f1.bin").isFile)

        val restarted = wordStore(russian, directory)
        restarted.prime()
        restarted.noteCompletion("мир")
        assertTrue("the kept salt keeps the pending count valid",
            restarted.snapshot.indexOfNormalized("мир") >= 0)
    }

    @Test
    fun clearingOneLanguageKeepsTheOtherLanguagesPairProgress() {
        val directory = newPersonalDir()
        val tatarStore = bigramStore(tatar, directory)
        val russianStore = bigramStore(russian, directory)
        tatarStore.notePair("сәләм", "дөнья")
        tatarStore.flush()
        russianStore.notePair("добрый", "день")
        russianStore.flush()

        val tatarSalt = File(directory, "salt-bigrams-$tatar-s1-f1.bin")
        val russianSalt = File(directory, "salt-bigrams-$russian-s1-f1.bin")
        assertTrue("each language has its own salt file", tatarSalt.isFile && russianSalt.isFile)

        tatarStore.clearAll()
        assertFalse(tatarSalt.exists())
        assertTrue("the other language's salt survives", russianSalt.isFile)
        assertTrue(File(directory, "pending-bigrams-$russian-s1-f1.bin").isFile)

        val restarted = bigramStore(russian, directory)
        restarted.prime()
        restarted.notePair("добрый", "день")
        assertTrue("the kept salt keeps the pending count valid",
            restarted.snapshot.indexOfPair("добрый", "день") >= 0)
    }

    @Test
    fun clearingOneLanguageKeepsTheOtherLanguagesEmojiProgress() {
        val directory = newPersonalDir()
        val tatarStore = emojiStore(tatar, directory)
        val russianStore = emojiStore(russian, directory)
        tatarStore.noteObservation("сәләм", "🌙")
        tatarStore.flush()
        russianStore.noteObservation("солнце", "☀️")
        russianStore.flush()

        val tatarSalt = File(directory, "salt-emoji-$tatar-s1-f1.bin")
        val russianSalt = File(directory, "salt-emoji-$russian-s1-f1.bin")
        assertTrue("each language has its own salt file", tatarSalt.isFile && russianSalt.isFile)

        tatarStore.clearAll()
        assertFalse(tatarSalt.exists())
        assertTrue("the other language's salt survives", russianSalt.isFile)
        assertTrue(File(directory, "pending-emoji-$russian-s1-f1.bin").isFile)

        val restarted = emojiStore(russian, directory)
        restarted.prime()
        restarted.noteObservation("солнце", "☀️")
        assertEquals("the kept salt keeps the pending count valid",
            "☀️", restarted.snapshot.emojiFor("солнце"))
    }

    @Test
    fun clearingOneLanguageKeepsTheOtherLanguagesSentenceStartProgress() {
        val directory = newPersonalDir()
        val tatarStore = sentStartStore(tatar, directory)
        val russianStore = sentStartStore(russian, directory)
        tatarStore.noteSentenceStart("Сәлам")
        tatarStore.flush()
        repeat(PendingCounters.LEARN_THRESHOLD - 1) { russianStore.noteSentenceStart("Привет") }
        russianStore.flush()

        val tatarSalt = File(directory, "salt-sentstart-$tatar-s1-f1.bin")
        val russianSalt = File(directory, "salt-sentstart-$russian-s1-f1.bin")
        assertTrue("each language has its own salt file", tatarSalt.isFile && russianSalt.isFile)

        tatarStore.clearAll()
        assertFalse(tatarSalt.exists())
        assertTrue("the other language's salt survives", russianSalt.isFile)
        assertTrue(File(directory, "pending-sentstart-$russian-s1-f1.bin").isFile)

        val restarted = sentStartStore(russian, directory)
        restarted.prime()
        restarted.noteSentenceStart("Привет")
        assertTrue("the kept salt keeps the pending count valid",
            restarted.snapshot.topWords().contains("Привет"))
    }

    // ---- the harness ----------------------------------------------------------------------------

    private fun wordStore(subtype: String, directory: File) = PersonalDictionaryStore(
        subtype, { directory }, RealOps(), opener, roomy, { 1000L }, direct,
    )

    private fun bigramStore(subtype: String, directory: File) = PersonalBigramStore(
        subtype, { directory }, RealOps(), opener, roomy, { 1000L }, direct,
        contextMembership = { _, _ -> true },
    )

    private fun emojiStore(subtype: String, directory: File) = PersonalEmojiStore(
        subtype, { directory }, RealOps(), opener, roomy, { 1000L }, direct,
    )

    private fun sentStartStore(subtype: String, directory: File) = SentStartOverlayStore(
        subtype, { directory }, RealOps(), opener, roomy, { 1000L }, direct,
    )

    private val direct = Executor { it.run() }
    private val opener = PersonalOutputOpener { temp -> FileOutputStream(temp) }
    private val roomy = SpaceProbe { Long.MAX_VALUE }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private class RealOps : DurableFileOps {
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
