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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/**
 * The feature-C learning gate — the mirror of [PersonalBigramLearningGatesTest] for the emoji
 * sink: the sink shares the ONE six-factor predicate of the words and pairs sinks, and every one
 * of its event paths (the observation, the accepted-suggestion use, the end-of-session flush)
 * consults it. With the predicate closed — incognito above all — not even a pending hash is
 * written.
 *
 * Two halves, exactly like the bigram pins: the SHAPE runs as real behavior (the production
 * gating logic of [PersonalEmojiLearning.sinkOver] over a real store on a direct executor, no
 * Android), and the WIRING is source-contract over the two places it lives — the sink factory and
 * `LatinIME`.
 */
class PersonalEmojiLearningGatesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU
    private val directExecutor = Executor { it.run() }

    // ---- the behavioral half: the real sink over a real store -----------------------------------

    private inner class Harness {
        val directory: File =
            File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }
        var learningOn = true
        var subtypeId: String? = subtype
        var storeResolutions = 0
        val store = newStore(directory, subtype)
        val sink = PersonalEmojiLearning.sinkOver(
            ActiveSubtypeSupplier { subtypeId },
            PersonalLearningPredicate { learningOn },
        ) { requested ->
            storeResolutions++
            check(requested == subtype)
            store
        }
    }

    private fun newStore(directory: File, subtypeId: String): PersonalEmojiStore =
        PersonalEmojiStore(
            subtypeId = subtypeId,
            directoryProvider = { directory },
            fileOps = RealOps,
            outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
            spaceProbe = SpaceProbe { Long.MAX_VALUE },
            clock = { 1000L },
            executor = directExecutor,
        )

    private fun storeFile(directory: File): File =
        File(directory, TpersemFormat.personalEmojiFileName(subtype))

    @Test
    fun aClosedPredicateWritesNothingOnAnyEventPath() {
        val h = Harness()
        h.learningOn = false
        repeat(3) {
            h.sink.noteObservation("сәләм", "☀️")
            h.sink.noteUse("сәләм", "☀️")
            h.sink.onInputFinished()
        }
        // The predicate answers before the subtype is even resolved: the store is never touched,
        // so nothing — no file, no pending counters, not even the salt — ever reaches the disk.
        assertEquals(0, h.storeResolutions)
        assertEquals(
            "a closed predicate leaves the personal directory untouched",
            emptyList<String>(),
            h.directory.list()?.toList() ?: emptyList<String>(),
        )
        assertTrue(h.store.snapshot.isEmpty)
    }

    @Test
    fun aNullSubtypeWritesNothing() {
        val h = Harness()
        h.subtypeId = null
        repeat(3) {
            h.sink.noteObservation("сәләм", "☀️")
            h.sink.noteUse("сәләм", "☀️")
            h.sink.onInputFinished()
        }
        assertEquals(0, h.storeResolutions)
        assertTrue(h.store.snapshot.isEmpty)
    }

    @Test
    fun incognitoPausesLearningEntirelyAndResumeLearns() {
        val h = Harness()
        h.sink.noteObservation("сәләм", "☀️") // pending 1, in memory only; the salt now exists
        assertNull(h.store.snapshot.emojiFor("сәләм"))
        val filesBeforePause = h.directory.list()?.toSet() ?: emptySet<String>()

        h.learningOn = false // incognito on
        h.sink.noteObservation("сәләм", "☀️")
        h.sink.noteUse("сәләм", "☀️")
        h.sink.onInputFinished()
        // The pause swallowed every event: no store file, no pending file, and the directory
        // holds exactly what it held before the pause (the salt alone).
        assertEquals(filesBeforePause, h.directory.list()?.toSet() ?: emptySet<String>())
        assertNull(h.store.snapshot.emojiFor("сәләм"))

        h.learningOn = true // incognito off: the pre-pause observation still counts in memory
        h.sink.noteObservation("сәләм", "☀️")
        assertEquals("☀️", h.store.snapshot.emojiFor("сәләм"))
        assertTrue(storeFile(h.directory).isFile)
    }

    // ---- the source-contract half (the wiring pins) ---------------------------------------------

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private val ime by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()
    }
    private val learning by lazy {
        File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore/PersonalEmojiLearning.kt",
        ).readText()
    }

    @Test
    fun everySinkMethodIsGatedInProductionToo() {
        val body = learning.substringAfter("fun sinkOver(")
        assertEquals(
            "the predicate is consulted on all three paths",
            3, Regex("if \\(!predicate\\.mayLearn\\(\\)\\) return").findAll(body).count(),
        )
    }

    @Test
    fun theSubtypeIsResolvedPerEventOnEveryPath() {
        val body = learning.substringAfter("fun sinkOver(")
        assertEquals(
            "a pick on the Russian layout reaches the Russian store and nothing else",
            3, Regex("activeSubtype\\.get\\(\\) \\?: return").findAll(body).count(),
        )
    }

    @Test
    fun theEmojiSinkIsWiredBesideTheOtherSinksUnderTheSamePredicate() {
        // One predicate, and all three sinks are wired with it — the emoji feature adds no second
        // place where "may we learn" is decided.
        assertEquals(
            "the emoji sink is wired exactly once",
            1, Regex("PersonalEmojiLearning\\.sinkFor\\(").findAll(ime).count(),
        )
        val wiring = ime.substringAfter("PersonalEmojiLearning.sinkFor(").substringBefore(");")
        assertTrue(
            "the SAME predicate instance gates emoji, pairs and words",
            wiring.contains("this::mayLearnPersonalWords"),
        )
        assertTrue(
            "the subtype is resolved at the moment of the event",
            wiring.contains("this::activeDictionarySubtype"),
        )
        assertTrue(
            "the controller is handed the emoji sink",
            ime.contains("mSuggestionsController.setPersonalEmojiSink("),
        )
    }

    @Test
    fun theErasureListenerIsInstalledAndClearedWithTheService() {
        assertEquals(
            "the erasure listener is installed exactly once (the second mention is onDestroy's nulling)",
            2, Regex("PersonalEmojiDictionaries\\.setErasureListener\\(").findAll(ime).count(),
        )
        assertTrue(
            "the install hops to the UI thread and unbinds the band through the path the words and pairs use",
            ime.contains(
                "PersonalEmojiDictionaries.setErasureListener(() -> mHandler.post(() -> {",
            ),
        )
        assertTrue(
            "and it is cleared on destroy — the store outlives the IME and must not hold it",
            ime.contains("PersonalEmojiDictionaries.setErasureListener(null);"),
        )
        assertTrue(
            "an erasure unbinds the band through the same path the words and pairs use",
            ime.contains("controller.onPersonalDictionaryErased();"),
        )
    }

    /** Plain durable ops over the real filesystem (no recording, no faults). */
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
