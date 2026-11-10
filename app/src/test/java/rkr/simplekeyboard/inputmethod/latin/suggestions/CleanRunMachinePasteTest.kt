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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SentenceStartSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PendingCounters
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramStore

/** Clipboard dirtiness shared by the word, pair and sentence-start learning paths. */
class CleanRunMachinePasteTest {

    private class FakeEditor : EditorSurface {
        var context = "дөнья"
        override fun cachedWordBeforeCursor(): String = ""
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = false
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
        override fun cachedWordBeforeTrailingWord(): String = context
        override fun isTrailingWordAtSentenceStart(): Boolean = true
    }

    private class Harness {
        val editor = FakeEditor()
        val words = mutableListOf<String>()
        val pairs = mutableListOf<Pair<String, String>>()
        val sentenceStarts = mutableListOf<String>()
        val machine = CleanRunMachine(editor)

        init {
            machine.completionSink = WordCompletionSink { word -> words.add(word) }
            machine.pairCompletionSink = PairCompletionSink { context, word ->
                pairs.add(context to word)
            }
            machine.sentenceStartSink = SentenceStartSink { word -> sentenceStarts.add(word) }
            // Arm the pair machine with an ordinary boundary, then remove its control events.
            machine.trackCleanRun("б")
            machine.observeEmptyResult("б")
            machine.trackCleanRun("баш")
            machine.trackCleanRun("")
            words.clear()
            pairs.clear()
            sentenceStarts.clear()
        }

        /** A fresh one-key observation, typed growth, then a typed separator. */
        fun completeShortObservation(first: String, fromClipboard: Boolean): String {
            if (fromClipboard) machine.markClipboardPasteDirty()
            val completed = first + "әләм"
            machine.trackCleanRun(first)
            machine.observeEmptyResult(first)
            machine.trackCleanRun(first + "ә")
            machine.trackCleanRun(completed)
            machine.trackCleanRun("")
            return completed
        }

        fun assertNothingReachedLearning() {
            assertTrue("no word event may advance a pending counter", words.isEmpty())
            assertTrue("no pair event may advance a pending counter", pairs.isEmpty())
            assertTrue("no sentence-start event may advance a pending counter", sentenceStarts.isEmpty())
        }

        fun assertTypedControlsAfterPastedContext(first: String) {
            val typedSuccessor = completeShortObservation(first, fromClipboard = false)
            assertEquals(listOf(typedSuccessor), words)
            assertTrue("the pasted context forms no pair", pairs.isEmpty())
            assertEquals(listOf(typedSuccessor), sentenceStarts)

            editor.context = typedSuccessor
            val following = completeShortObservation("б", fromClipboard = false)
            assertEquals(listOf(typedSuccessor, following), words)
            assertEquals(listOf(typedSuccessor to following), pairs)
            assertEquals(listOf(typedSuccessor, following), sentenceStarts)
        }
    }

    private fun assertShortPasteNeverLearns(first: String) {
        val h = Harness()
        // This reaches both plaintext thresholds: pairs graduate sooner than words and sentence
        // starts. No sink event means no pending-counter progress and no plaintext entry.
        repeat(maxOf(PendingCounters.LEARN_THRESHOLD, PersonalBigramStore.LEARN_THRESHOLD)) {
            h.completeShortObservation(first, fromClipboard = true)
        }
        h.assertNothingReachedLearning()

        h.assertTypedControlsAfterPastedContext(first)
    }

    @Test
    fun oneBmpCharacterAfterPasteNeverReachesAnyPlaintextThreshold() {
        assertShortPasteNeverLearns("с")
    }

    @Test
    fun oneSurrogatePairAfterPasteNeverReachesAnyPlaintextThreshold() {
        assertShortPasteNeverLearns("🙂")
    }

    @Test
    fun genericSessionInitializationAllowsATypedFieldStartSentence() {
        val h = Harness()

        h.machine.markRunDirty()
        val completed = h.completeShortObservation("с", fromClipboard = false)

        assertEquals("a typed field-start word still reaches the sentence overlay",
            listOf(completed), h.sentenceStarts)
        assertTrue("ordinary first-word dictionary caution is unchanged", h.words.isEmpty())
        assertTrue("the first session boundary still forms no pair", h.pairs.isEmpty())
    }

    @Test
    fun repeatedPastedContextsNeverPairWithTheNextTypedWord() {
        val h = Harness()
        val rounds = maxOf(PendingCounters.LEARN_THRESHOLD, PersonalBigramStore.LEARN_THRESHOLD)
        var typedSuccessor = ""
        repeat(rounds) {
            val pastedContext = h.completeShortObservation("с", fromClipboard = true)
            h.editor.context = pastedContext
            typedSuccessor = h.completeShortObservation("к", fromClipboard = false)
        }
        assertTrue("neither a pasted word nor its typed successor may advance a pair counter",
            h.pairs.isEmpty())
        assertEquals("only the typed successors reach the word sink",
            List(rounds) { typedSuccessor }, h.words)
        assertEquals("only the typed successors reach the sentence sink",
            List(rounds) { typedSuccessor }, h.sentenceStarts)

        // The last successor was typed: it is a valid context for one more ordinary typed word.
        h.editor.context = typedSuccessor
        val following = h.completeShortObservation("б", fromClipboard = false)
        assertEquals(listOf(typedSuccessor to following), h.pairs)
        assertEquals(List(rounds) { typedSuccessor } + following, h.words)
        assertEquals(List(rounds) { typedSuccessor } + following, h.sentenceStarts)
    }

    @Test
    fun aLongPasteNeverReachesAnyPlaintextThreshold() {
        val h = Harness()
        repeat(maxOf(PendingCounters.LEARN_THRESHOLD, PersonalBigramStore.LEARN_THRESHOLD)) {
            // The length cap recognizes a paste even when the clipboard notification is absent.
            h.machine.trackCleanRun("секрет")
            h.machine.observeEmptyResult("с")
            h.machine.trackCleanRun("")
        }
        h.assertNothingReachedLearning()

        h.assertTypedControlsAfterPastedContext("с")
    }
}
