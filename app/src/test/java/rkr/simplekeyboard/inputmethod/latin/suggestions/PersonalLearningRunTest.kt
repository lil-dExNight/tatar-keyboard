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

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink

/**
 * E4c, the clean-run machine: which typing produces a "this word was completed cleanly" event and,
 * far more importantly, which typing produces none.
 *
 * The threshold of three lives in the store; what is under test here is the event itself — computed
 * from the hooks that already exist, with the "the dictionary does not know this word" filter built
 * from empty results the engine returned anyway.
 */
class PersonalLearningRunTest {

    private class FakeStrip : StripSurface {
        var listener: SuggestionTapListener? = null
        override fun showSuggestions(first: String, second: String?, third: String?) = Unit
        override fun reserve() = Unit
        override fun hideSuggestions() = Unit
        override fun setTapListener(listener: SuggestionTapListener) {
            this.listener = listener
        }
    }

    private class FakeEditor : EditorSurface {
        var word = ""
        override fun cachedWordBeforeCursor(): String = word
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = true
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
    }

    private class FakeEngine : EngineHandle {
        val requested = mutableListOf<String>()
        private var serial = 0L
        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            requested.add(String(prefixUtf8, Charsets.UTF_8))
            return ++serial
        }
        override fun isCurrent(token: Any): Boolean = token == serial
        override fun finishInput() = Unit
        override fun updateKeyNeighbors(table: KeyNeighborTable?) = Unit
        override fun destroy(timeoutMs: Long): Boolean = true
    }

    private class DirectExecutor : java.util.concurrent.AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class Harness {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        val completions = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        var flushes = 0
        var callback: ResultCallback? = null
        val controller = SuggestionsController(
            strip, editor, UiPoster { it.run() },
            { resultCallback -> callback = resultCallback; engine },
            DirectExecutor(), true,
        )

        init {
            controller.setCompletionSink(object : WordCompletionSink {
                override fun onCleanCompletion(word: String) {
                    completions.add(word)
                }

                override fun onAcceptedSuggestion(word: String) {
                    accepted.add(word)
                }

                override fun onInputFinished() {
                    flushes++
                }
            })
            controller.onStartInput(eligible = true)
        }

        /**
         * Feeds [word] the way the editor cache actually moves under typing: ONE appended code
         * point per text event, so a [word] that grows the current one arrives as its
         * intermediate prefixes. Anything that is not plain growth (a backspace, a replacement)
         * is delivered as the single event it is — and a multi-character jump from an empty word
         * is a paste, which the 2026-09-25 paste rule (CleanRunMachine) must read as dirty; use
         * [paste] to say that out loud. The lookup of the last observation is answered with
         * [result].
         */
        fun type(word: String, result: List<String> = emptyList()) {
            val current = editor.word
            if (word.length > current.length && word.startsWith(current)) {
                var end = current.length
                while (end < word.length) {
                    end += Character.charCount(word.codePointAt(end))
                    editor.word = word.substring(0, end)
                    controller.onTextChanged()
                }
            } else {
                editor.word = word
                controller.onTextChanged()
            }
            callback?.onResult(engine.requested.size.toLong(), result, LookupKind.PREFIX)
        }

        /** A paste or wholesale replacement: the whole [word] appears in ONE text event. */
        fun paste(word: String, result: List<String> = emptyList()) {
            editor.word = word
            controller.onTextChanged()
            callback?.onResult(engine.requested.size.toLong(), result, LookupKind.PREFIX)
        }

        /** The word ends: the trailing word becomes empty (a space, or any separator). */
        fun endWord() {
            editor.word = ""
            controller.onTextChanged()
        }

        /**
         * Witnesses one word boundary, which is what makes the NEXT run countable. A session starts
         * without one on purpose — see [theFirstWordOfASessionIsNotCountedAtAll].
         */
        fun witnessABoundary() {
            type("баш")
            endWord()
        }
    }

    @Test
    fun theFirstWordOfASessionIsNotCountedAtAll() {
        val h = Harness()
        // No word boundary has been witnessed yet, so the trailing word may well be text the app
        // pre-filled and the user merely appended to. Fail closed: it is not a run we saw start.
        h.type("гүз")
        h.type("гүзәлия")
        h.endWord()
        assertTrue(h.completions.isEmpty())
    }

    @Test
    fun aWordSpelledOutCleanlyAndUnknownToTheDictionaryIsReportedOnce() {
        val h = Harness()
        h.witnessABoundary()
        h.type("гүз")
        h.type("гүзә")
        h.type("гүзәли")
        h.type("гүзәлия")
        h.endWord()
        assertEquals(listOf("гүзәлия"), h.completions)
    }

    @Test
    fun aWordTheDictionaryKnowsIsNeverReported() {
        val h = Harness()
        h.witnessABoundary()
        // Every prefix returns candidates, so no prefix ever proves the word is unknown.
        h.type("гүз", listOf("гүзәл"))
        h.type("гүзә", listOf("гүзәл"))
        h.type("гүзәл", listOf("гүзәллек"))
        h.endWord()
        assertTrue("without an empty result the word is not reported", h.completions.isEmpty())
    }

    @Test
    fun anEmptyResultForTheWholeWordAloneIsNotEnough() {
        val h = Harness()
        h.witnessABoundary()
        // The only empty result arrives for the full word, not for a PROPER prefix of it: it says
        // nothing about whether the word itself is in the dictionary.
        h.type("гүзәлия", emptyList())
        h.endWord()
        assertTrue(h.completions.isEmpty())
    }

    @Test
    fun backspaceMakesTheRunDirtyAndNothingIsReported() {
        val h = Harness()
        h.witnessABoundary()
        h.type("гүз")
        h.type("гүзә")
        h.type("гүз") // backspace
        h.type("гүзәлия")
        h.endWord()
        assertTrue("a corrected word is not a clean run", h.completions.isEmpty())
    }

    @Test
    fun aSelectionChangeOrCursorGestureMakesTheRunDirty() {
        val h = Harness()
        h.witnessABoundary()
        h.type("гүз")
        h.type("гүзә")
        h.controller.onSelectionChanged()
        h.type("гүзәлия")
        h.endWord()
        assertTrue(h.completions.isEmpty())
    }

    @Test
    fun anAcceptedSuggestionMakesTheRunDirty() {
        val h = Harness()
        h.witnessABoundary()
        h.type("гүз")
        h.type("гүзә", listOf("гүзәлия"))
        h.strip.listener!!.onTap("гүзәлия")
        h.endWord()
        assertTrue("a word the user picked is not a word the user spelled out",
            h.completions.isEmpty())
    }

    @Test
    fun anAcceptedPrefixSuggestionCountsAsAUseOnTheWordSink() {
        // 2026-09-24 audit, finding 2: the usage bump the store always had is now reachable — a
        // tap on a PREFIX cell reports the accepted word to the word sink. Whether that word is a
        // saved personal one (and therefore whether a counter actually moves) is the sink's
        // decision; the store bumps in memory only and the file moves at the session boundary.
        val h = Harness()
        h.witnessABoundary()
        h.type("гүз")
        h.type("гүзә", listOf("гүзәлия"))

        h.strip.listener!!.onTap("гүзәлия")

        assertEquals(listOf("гүзәлия"), h.accepted)
    }

    @Test
    fun aCleanlyTypedWordIsNotAnAcceptance() {
        // The counter moves on taps, not on typing: a spelled-out completion must not arrive as
        // an accepted suggestion.
        val h = Harness()
        h.witnessABoundary()
        h.type("гүз")
        h.type("гүзәлия")
        h.endWord()
        assertEquals(listOf("гүзәлия"), h.completions)
        assertTrue(h.accepted.isEmpty())
    }

    @Test
    fun aSubtypeChangeMakesTheRunDirty() {
        val h = Harness()
        h.witnessABoundary()
        h.type("гүз")
        h.type("гүзә")
        h.controller.onSubtypeChanged(eligible = true)
        h.type("гүзәлия")
        h.endWord()
        assertTrue(h.completions.isEmpty())
    }

    @Test
    fun theNextWordAfterACleanOneIsCountedOnItsOwn() {
        val h = Harness()
        h.witnessABoundary()
        h.type("гүз")
        h.type("гүзәлия")
        h.endWord()
        h.type("зәй")
        h.type("зәйнәп")
        h.endWord()
        assertEquals(listOf("гүзәлия", "зәйнәп"), h.completions)
    }

    @Test
    fun theSessionEndFlushesExactlyOnce() {
        val h = Harness()
        h.type("гүз")
        h.type("гүзәлия")
        h.controller.onFinishInput()
        assertEquals(1, h.flushes)
        assertTrue("an unfinished word is not a completed one", h.completions.isEmpty())
    }

    @Test
    fun anIdempotentTextEventDoesNotDirtyTheRun() {
        val h = Harness()
        h.witnessABoundary()
        h.type("гүз")
        h.type("гүз") // same word again — e.g. a redundant onTextChanged
        h.type("гүзәлия")
        h.endWord()
        assertEquals(listOf("гүзәлия"), h.completions)
    }

    @Test
    fun aPastedStartMakesTheWholeRunDirty() {
        // 2026-09-25 audit, the paste rule: a fresh word whose FIRST observation already carries
        // 3+ UTF-16 units is a paste or a replacement, not typing — the run is born dirty, and
        // growing it by hand afterwards does not wash it clean (the empty result for a proper
        // prefix IS observed on the way, so without the rule this word would be reported).
        val h = Harness()
        h.witnessABoundary()
        h.paste("гүзәл")
        h.type("гүзәли")
        h.type("гүзәлия")
        h.endWord()
        assertTrue("a pasted word is not learned as typed", h.completions.isEmpty())
    }

    @Test
    fun aTwoUnitFirstObservationIsStillOneKeystroke() {
        // The budget's edge (2026-09-25 audit): two UTF-16 units in the first observation are one
        // keystroke's worth — a surrogate pair, or two events coalesced by the cache — so the run
        // stays clean.
        val h = Harness()
        h.witnessABoundary()
        h.paste("гү")
        h.type("гүзә")
        h.type("гүзәлия")
        h.endWord()
        assertEquals(listOf("гүзәлия"), h.completions)
    }
}
