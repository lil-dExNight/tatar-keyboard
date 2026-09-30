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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalEmojiDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SnapshotPersonalEmojiSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/**
 * Learned emoji end to end, one layer below
 * [rkr.simplekeyboard.inputmethod.latin.suggestions.PersonalBigramRunTest]: the real learning
 * sink ([PersonalEmojiLearning.sinkOver]), the real [PersonalEmojiStore] over a temp directory on
 * a direct executor, and the real read path ([SnapshotPersonalEmojiSource] under a live gate, as
 * [PersonalEmojiDictionaries.sourceFor] builds it). Pinned: two clean observations graduate a
 * (word, emoji) pair that the source then offers; pausing learning stops it without losing prior
 * observations; the read gate hides without erasing; erasure empties the source; and usage
 * outranks frequency.
 */
class PersonalEmojiRunTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val tatar = PersonalSubtypes.TATAR_RU
    private val russian = PersonalSubtypes.RUSSIAN
    private val directExecutor = Executor { it.run() }

    private inner class Harness {
        val directory: File =
            File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

        /** The sink's predicate — the six production factors folded into the one switch a test flips. */
        var learningOn = true

        /** The read-side live gate — the personal-dictionary setting, exactly as production reads it. */
        var personalGateOn = true

        /** The active layout: resolved per event by the sink and per query by the source. */
        var currentSubtype: String? = tatar

        val stores = HashMap<String, PersonalEmojiStore>()
        val sink = PersonalEmojiLearning.sinkOver(
            ActiveSubtypeSupplier { currentSubtype },
            PersonalLearningPredicate { learningOn },
        ) { subtypeId -> storeFor(subtypeId) }

        /** The production read shape: the CURRENT subtype's snapshot behind the live gate. */
        val source = SnapshotPersonalEmojiSource {
            val id = currentSubtype
            val store = if (id == null) null else stores[id]
            if (personalGateOn && store != null) store.snapshot else PersonalEmojiDictionary.EMPTY
        }

        fun storeFor(subtypeId: String): PersonalEmojiStore =
            stores.getOrPut(subtypeId) {
                PersonalEmojiStore(
                    subtypeId = subtypeId,
                    directoryProvider = { directory },
                    fileOps = RealOps,
                    outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
                    spaceProbe = SpaceProbe { Long.MAX_VALUE },
                    clock = { 1000L },
                    executor = directExecutor,
                )
            }

        fun storeFile(subtypeId: String): File =
            File(directory, TpersemFormat.personalEmojiFileName(subtypeId))
    }

    @Test
    fun twoCleanObservationsGraduateThePairAndTheSourceOffersIt() {
        val h = Harness()
        h.sink.noteObservation("сәләм", "☀️")
        assertNull("one observation is below the learn threshold", h.source.emojiFor("сәләм"))
        assertFalse("nothing is written before graduation", h.storeFile(tatar).exists())

        h.sink.noteObservation("сәләм", "☀️")
        assertEquals("☀️", h.source.emojiFor("сәләм"))
        assertTrue("graduation writes the file", h.storeFile(tatar).isFile)
    }

    @Test
    fun incognitoMidWayPausesLearningAndResumeLearns() {
        val h = Harness()
        h.sink.noteObservation("сәләм", "☀️") // pending 1, in memory
        h.learningOn = false
        h.sink.noteObservation("сәләм", "☀️") // swallowed by the pause: never reaches the store
        h.sink.noteUse("сәләм", "☀️") // swallowed too
        h.sink.onInputFinished() // and the flush is gated: the pending hash stays off the disk
        assertNull(h.source.emojiFor("сәләм"))
        assertFalse(h.storeFile(tatar).exists())
        assertFalse(File(h.directory, "pending-emoji-$tatar-s1-f1.bin").exists())

        h.learningOn = true
        h.sink.noteObservation("сәләм", "☀️") // the pre-pause observation still counts: graduation
        assertEquals("☀️", h.source.emojiFor("сәләм"))
    }

    @Test
    fun theReadGateHidesWithoutErasing() {
        val h = Harness()
        repeat(2) { h.sink.noteObservation("сәләм", "☀️") }
        assertEquals("☀️", h.source.emojiFor("сәләм"))

        h.personalGateOn = false
        assertNull("the setting off hides the learned emoji on the very next lookup",
            h.source.emojiFor("сәләм"))

        h.personalGateOn = true
        assertEquals("☀️", h.source.emojiFor("сәләм"))
    }

    @Test
    fun erasureEmptiesTheSource() {
        val h = Harness()
        repeat(2) { h.sink.noteObservation("сәләм", "☀️") }
        assertEquals("☀️", h.source.emojiFor("сәләм"))

        h.storeFor(tatar).clearAll()
        assertNull(h.source.emojiFor("сәләм"))
        assertTrue(h.storeFor(tatar).snapshot.isEmpty)
        assertFalse(h.storeFile(tatar).exists())
    }

    @Test
    fun anAcceptedUseOutranksFrequency() {
        val h = Harness()
        repeat(2) { h.sink.noteObservation("сәләм", "☀️") }
        repeat(3) { h.sink.noteObservation("сәләм", "🌙") }
        assertEquals("🌙", h.source.emojiFor("сәләм")) // frequency 3 beats 2 among the never-used

        h.sink.noteUse("сәләм", "☀️") // one acceptance: usage outranks frequency
        assertEquals("☀️", h.source.emojiFor("сәләм"))
    }

    @Test
    fun theSubtypeIsResolvedPerEventAndPerQuery() {
        val h = Harness()
        repeat(2) { h.sink.noteObservation("сәләм", "☀️") }
        h.currentSubtype = russian
        // The Russian store's alphabet filter rejects ә, so the co-usage is pinned on a word that
        // is plain Cyrillic in BOTH alphabets — separation is about the store, not the filter.
        repeat(2) { h.sink.noteObservation("привет", "🌙") }
        // Each language learned into its own store; the source follows the ACTIVE subtype.
        assertEquals("🌙", h.source.emojiFor("привет"))
        assertNull(h.source.emojiFor("сәләм"))
        h.currentSubtype = tatar
        assertEquals("☀️", h.source.emojiFor("сәләм"))
        assertNull(h.source.emojiFor("привет"))
    }

    @Test
    fun theSessionBoundaryPersistsThePendingCounters() {
        val h = Harness()
        h.sink.noteObservation("сәләм", "☀️") // below the threshold: in-memory pending only
        h.sink.onInputFinished()
        assertTrue(
            "the flush is the one boundary where pending hashes reach the disk",
            File(h.directory, "pending-emoji-$tatar-s1-f1.bin").isFile,
        )

        // A later session (a fresh store over the same directory) reads the counters back, and the
        // next observation graduates the pair.
        h.stores.remove(tatar)
        h.sink.noteObservation("сәләм", "☀️")
        assertEquals("☀️", h.source.emojiFor("сәләм"))
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
