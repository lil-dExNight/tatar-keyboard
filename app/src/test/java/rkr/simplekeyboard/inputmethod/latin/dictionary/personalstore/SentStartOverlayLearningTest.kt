/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/**
 * The sentence-initial overlay's learning gates: the sink writes only when the shared predicate
 * ([PersonalLearningGates], the same one the personal dictionary consults) allows it at the moment
 * of the event — never in an ineligible field, never while learning is paused — and the read side
 * is untouched by the pause. The store end is driven for real through the injected sink; the
 * production wiring is pinned from source, as in `PersonalLearningGatesTest`.
 */
class SentStartOverlayLearningTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    @Test
    fun aClosedPredicateWritesNothingOnAnyEvent() {
        val harness = Harness(newDirectory(), learn = { false })
        repeat(PendingCounters.LEARN_THRESHOLD + 2) { harness.sink.onSentenceStartWord("сәләм") }
        harness.sink.onInputFinished()
        assertTrue(harness.store.snapshot.isEmpty)
        assertFalse(overlayFile(harness.directory).isFile)
        assertFalse("not even the pending counters were written",
            File(harness.directory, "pending-sentstart-$subtype-s1-f1.bin").isFile)
    }

    @Test
    fun aNullSubtypeWritesNothing() {
        val harness = Harness(newDirectory(), learn = { true }, subtype = { null })
        repeat(PendingCounters.LEARN_THRESHOLD + 2) { harness.sink.onSentenceStartWord("сәләм") }
        harness.sink.onInputFinished()
        assertTrue(harness.store.snapshot.isEmpty)
        assertFalse(overlayFile(harness.directory).isFile)
    }

    @Test
    fun thePauseStopsWritesButNeverReads() {
        var learn = true
        val harness = Harness(newDirectory(), learn = { learn })
        repeat(PendingCounters.LEARN_THRESHOLD - 1) { harness.sink.onSentenceStartWord("сәләм") }
        learn = false
        // Paused: the would-be graduation commit is dropped, and the flush writes nothing.
        harness.sink.onSentenceStartWord("сәләм")
        harness.sink.onInputFinished()
        assertTrue("the paused commit never reached the store", harness.store.snapshot.isEmpty)
        assertFalse(overlayFile(harness.directory).isFile)
        assertFalse(File(harness.directory, "pending-sentstart-$subtype-s1-f1.bin").isFile)

        // Nothing from the paused period is relearned retroactively, but the progress made before
        // the pause stands: exactly one more commit graduates the word.
        learn = true
        assertTrue("the paused commit was dropped, not deferred", harness.store.snapshot.isEmpty)
        harness.sink.onSentenceStartWord("сәләм")
        assertEquals(listOf("сәләм"), harness.store.snapshot.topWords())

        learn = false
        assertEquals("the read side keeps working while paused", listOf("сәләм"),
            harness.store.snapshot.topWords())
    }

    @Test
    fun theFlushIsGatedLikeEveryOtherWrite() {
        var learn = true
        val harness = Harness(newDirectory(), learn = { learn })
        repeat(PendingCounters.LEARN_THRESHOLD) { harness.sink.onSentenceStartWord("сәләм") }
        // A bump waiting for the session boundary.
        harness.sink.onSentenceStartWord("сәләм")
        val writesBefore = harness.store.writeCount
        learn = false
        harness.sink.onInputFinished()
        assertEquals("a session that became ineligible flushes nothing", writesBefore,
            harness.store.writeCount)
        assertEquals("the in-memory bump stands for the next eligible flush",
            listOf("сәләм"), harness.store.snapshot.topWords())
        learn = true
        harness.sink.onInputFinished()
        assertEquals("the next eligible flush writes it", writesBefore + 1, harness.store.writeCount)
    }

    @Test
    fun productionWiresTheSharedPredicateAndTheReadGateSeparately() {
        val ime = File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()
        assertTrue(
            "the sink is built by SentStartOverlayLearning under mayLearnPersonalWords",
            ime.contains("setSentenceStartSink(SentStartOverlayLearning.sinkFor(") &&
                ime.contains("this::mayLearnPersonalWords"),
        )
        assertTrue(
            "the overlay read is gated on the personal-dictionary setting, never on the pause",
            ime.contains("setSentStartOverlaySource") &&
                ime.contains("SentStartOverlays.topWordsFor(") &&
                ime.contains("Settings.readPersonalDictionaryEnabled(mDevicePrefs)"),
        )
        assertTrue(
            "the erase-all covers the overlay store",
            File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
                .readText().contains("SentStartOverlays.storeFor(this, subtypeId).clearAll"),
        )
    }

    // ---- harness ----------------------------------------------------------------------------------

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private fun newDirectory(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private class Harness(
        val directory: File,
        learn: () -> Boolean,
        subtype: () -> String? = { PersonalSubtypes.TATAR_RU },
    ) {
        val store = SentStartOverlayStore(
            subtypeId = PersonalSubtypes.TATAR_RU,
            directoryProvider = { directory },
            fileOps = RealOps,
            outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
            spaceProbe = SpaceProbe { Long.MAX_VALUE },
            clock = { 1000L },
            executor = Executor { it.run() },
        )
        val sink = SentStartOverlayLearning.sinkOver(
            ActiveSubtypeSupplier(subtype),
            PersonalLearningPredicate(learn),
        ) { store }
    }

    private fun overlayFile(directory: File) =
        File(directory, TsentFormat.sentStartFileName(subtype))

    /** Performs every durable op for real. */
    private object RealOps : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()

        override fun syncFile(fileDescriptor: FileDescriptor) {
            fileDescriptor.sync()
        }

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
