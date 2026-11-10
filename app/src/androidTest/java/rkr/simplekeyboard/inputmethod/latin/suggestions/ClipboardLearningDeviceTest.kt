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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import android.test.InstrumentationTestCase
import java.io.File
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SentenceStartSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PendingCounters
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaryStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalOutputOpener
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDirectoryProvider
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.SentStartOverlayStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidDurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import java.io.FileOutputStream

/**
 * The production paste rules against real personal stores on Android storage. The stores live in
 * a test directory, so neither the user's stores nor pending hashes are touched.
 */
class ClipboardLearningDeviceTest : InstrumentationTestCase() {

    private class RecordingEditor : EditorSurface {
        var context = ""
        var atSentenceStart = true
        override fun cachedWordBeforeCursor(): String = ""
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = false
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
        override fun cachedWordBeforeTrailingWord(): String = context
        override fun isTrailingWordAtSentenceStart(): Boolean = atSentenceStart
    }

    private class Harness(directory: File, private val editor: RecordingEditor) {
        val words = mutableListOf<String>()
        val pairs = mutableListOf<Pair<String, String>>()
        val starts = mutableListOf<String>()
        private val wordStore = PersonalDictionaryStore(
            "tt_RU",
            PersonalDirectoryProvider { directory },
            AndroidDurableFileOps,
            PersonalOutputOpener { file -> FileOutputStream(file) },
            SpaceProbe { Long.MAX_VALUE },
            { 1000L },
            { runnable -> runnable.run() },
        )
        private val pairStore = PersonalBigramStore(
            "tt_RU",
            PersonalDirectoryProvider { directory },
            AndroidDurableFileOps,
            PersonalOutputOpener { file -> FileOutputStream(file) },
            SpaceProbe { Long.MAX_VALUE },
            { 1000L },
            { runnable -> runnable.run() },
            { _, _ -> true },
        )
        private val startStore = SentStartOverlayStore(
            "tt_RU",
            PersonalDirectoryProvider { directory },
            AndroidDurableFileOps,
            PersonalOutputOpener { file -> FileOutputStream(file) },
            SpaceProbe { Long.MAX_VALUE },
            { 1000L },
            { runnable -> runnable.run() },
        )
        val machine = CleanRunMachine(editor)

        fun attachLearning() {
            machine.completionSink = WordCompletionSink { word ->
                words += word
                wordStore.noteCompletion(word)
            }
            machine.pairCompletionSink = PairCompletionSink { context, word ->
                pairs += context to word
                pairStore.notePair(context, word)
            }
            machine.sentenceStartSink = SentenceStartSink { word ->
                starts += word
                startStore.noteSentenceStart(word)
            }
        }

        fun detachLearning() {
            machine.completionSink = WordCompletionSink { word -> words += word }
            machine.pairCompletionSink = PairCompletionSink { context, word ->
                pairs += context to word
            }
            machine.sentenceStartSink = SentenceStartSink { word -> starts += word }
        }

        fun flushLearning() {
            machine.onInputFinished()
            wordStore.flush()
            pairStore.flush()
            startStore.flush()
        }

        fun completeTyped(first: String, suffix: String): String {
            val completed = first + suffix
            machine.trackCleanRun(first)
            machine.observeEmptyResult(first)
            machine.trackCleanRun(first + suffix.first())
            machine.trackCleanRun(completed)
            machine.trackCleanRun("")
            return completed
        }

        fun completePasted(first: String, suffix: String): String {
            machine.markClipboardPasteDirty()
            return completeTyped(first, suffix)
        }

        fun wordWriteCount(): Int = wordStore.writeCount
        fun pairWriteCount(): Int = pairStore.writeCount
        fun startWriteCount(): Int = startStore.writeCount
        fun pairSnapshot() = pairStore.snapshot
        fun wordSnapshot() = wordStore.snapshot
        fun startSnapshot() = startStore.snapshot
    }

    private lateinit var directory: File
    private lateinit var editor: RecordingEditor
    private lateinit var harness: Harness

    override fun setUp() {
        super.setUp()
        directory = File(
            instrumentation.targetContext.noBackupFilesDir,
            "clipboard-learning-device-test-${System.nanoTime()}",
        )
        assertTrue(directory.mkdirs())
        editor = RecordingEditor()
        harness = Harness(directory, editor)
        harness.detachLearning()
        harness.machine.trackCleanRun("б")
        harness.machine.observeEmptyResult("б")
        harness.machine.trackCleanRun("баш")
        harness.machine.trackCleanRun("")
        harness.words.clear()
        harness.pairs.clear()
        harness.starts.clear()
        harness.attachLearning()
    }

    override fun tearDown() {
        directory.deleteRecursively()
        super.tearDown()
    }

    fun testShortBmpPasteReachesNoRealStoreAfterTheThreshold() {
        val rounds = maxOf(PendingCounters.LEARN_THRESHOLD, PersonalBigramStore.LEARN_THRESHOLD)
        val suffix = uniqueSuffix()
        val pasted = harness.completePasted("с", suffix)
        editor.atSentenceStart = true
        repeat(rounds - 1) { harness.completePasted("с", suffix) }

        harness.flushLearning()
        assertNothingLearned()
        assertTrue(harness.wordSnapshot().indexOfNormalized(pasted) < 0)
        assertTrue(harness.startSnapshot().topWords().none {
            it.equals(pasted, ignoreCase = true)
        })
        assertEquals(0, harness.wordWriteCount())
        assertEquals(0, harness.startWriteCount())

        harness.detachLearning()
        val typed = harness.completeTyped("с", suffix)
        assertEquals("typed growth still produces the word and sentence-start events",
            listOf(typed), harness.words)
        assertEquals(listOf(typed), harness.starts)
    }

    fun testShortSurrogatePasteReachesNoRealStoreAfterTheThreshold() {
        val rounds = maxOf(PendingCounters.LEARN_THRESHOLD, PersonalBigramStore.LEARN_THRESHOLD)
        val suffix = uniqueSuffix()
        editor.atSentenceStart = true
        repeat(rounds) { harness.completePasted("🙂", suffix) }

        harness.flushLearning()
        assertNothingLearned()
        assertEquals(0, harness.wordWriteCount())
        assertEquals(0, harness.startWriteCount())

        harness.detachLearning()
        val typed = harness.completeTyped("с", suffix)
        assertEquals("the control typed word is still a clean run",
            listOf(typed), harness.words)
        assertEquals(listOf(typed), harness.starts)
    }

    fun testPastedContextNeverFormsARealPairWithATypedSuccessor() {
        val rounds = maxOf(PendingCounters.LEARN_THRESHOLD, PersonalBigramStore.LEARN_THRESHOLD)
        val contextSuffix = uniqueSuffix()
        val successorSuffix = uniqueSuffix()
        editor.atSentenceStart = false
        val pastedContext = harness.completePasted("с", contextSuffix)
        editor.context = pastedContext
        val successor = harness.completeTyped("к", successorSuffix)
        repeat(rounds - 1) {
            editor.context = pastedContext
            harness.completePasted("с", contextSuffix)
            editor.context = pastedContext
            harness.completeTyped("к", successorSuffix)
        }

        harness.flushLearning()
        assertEquals("only typed successors reached the word sink",
            List(rounds) { successor }, harness.words)
        assertTrue("a pasted context reached the pair sink", harness.pairs.isEmpty())
        assertTrue("a non-sentence word reached the sentence-start sink", harness.starts.isEmpty())
        assertEquals(0, harness.pairWriteCount())
        assertTrue(harness.pairSnapshot().indexOfPair(
            pastedContext.lowercase(), successor.lowercase(),
        ) < 0)

        harness.detachLearning()
        editor.context = ""
        val typedContext = harness.completeTyped("с", contextSuffix)
        editor.context = typedContext
        val typedSuccessor = harness.completeTyped("к", successorSuffix)
        assertEquals("typed growth after the same context still produces the pair event",
            listOf(typedContext to typedSuccessor), harness.pairs)
    }

    private fun assertNothingLearned() {
        assertTrue("a pasted word reached the word sink", harness.words.isEmpty())
        assertTrue("a pasted context reached the pair sink", harness.pairs.isEmpty())
        assertTrue("a pasted word reached the sentence-start sink", harness.starts.isEmpty())
    }

    private fun uniqueSuffix(): String {
        val alphabet = "гүзәлмн"
        var value = System.nanoTime()
        val suffix = StringBuilder()
        repeat(6) {
            suffix.append(alphabet[(value % alphabet.length).toInt()])
            value /= alphabet.length
        }
        return suffix.toString()
    }
}
