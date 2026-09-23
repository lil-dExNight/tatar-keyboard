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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink

/**
 * P1 of Phase 2 (docs/ROADMAP-P2.md), the pair-run machine: which typing produces a "this PAIR
 * was completed cleanly" event and, far more importantly, which typing produces none.
 *
 * The machine shares the run TEXT with the E4c clean-run machine and differs in exactly one
 * place: after an ACCEPTED suggestion the next typed word may still form a pair — the tapped
 * word is a legitimate context ("typed or tapped") and the cursor sits provably right after it.
 * The threshold, the filters and the membership gate live in the store; what is under test here
 * is the event itself — including the acceptance hook of the NEXT_WORD tap path.
 */
class PersonalBigramRunTest {

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
        var wordBeforeTrailing = ""
        var context = ""
        override fun cachedWordBeforeCursor(): String = word
        override fun cachedWordBeforeTrailingWord(): String = wordBeforeTrailing
        override fun cachedNextWordContext(): String = context
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = true
        override fun commitPredictedWord(expectedContextWord: String, suggestion: String): Boolean = true
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
    }

    private class FakeEngine : EngineHandle {
        val requested = mutableListOf<String>()
        var nextWordAnswer: List<String> = emptyList()
        var callback: ResultCallback? = null
        private var serial = 0L
        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            requested.add(String(prefixUtf8, Charsets.UTF_8))
            return ++serial
        }

        override fun requestNextWord(editorSessionId: Long, subtypeId: String, contextWordUtf8: ByteArray): Any? {
            val token = ++serial
            callback?.onResult(token, nextWordAnswer, LookupKind.NEXT_WORD)
            return token
        }

        override fun isCurrent(token: Any): Boolean = true
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
        val pairs = mutableListOf<Pair<String, String>>()
        val acceptances = mutableListOf<Pair<String, String>>()
        var flushes = 0
        val controller = SuggestionsController(
            strip, editor, UiPoster { it.run() },
            { resultCallback -> engine.callback = resultCallback; engine },
            DirectExecutor(), true,
        )

        init {
            controller.setPairCompletionSink(object : PairCompletionSink {
                override fun onCleanPairCompletion(contextWord: String, completedWord: String) {
                    pairs.add(contextWord to completedWord)
                }

                override fun onAcceptedPrediction(contextWord: String, word: String) {
                    acceptances.add(contextWord to word)
                }

                override fun onInputFinished() {
                    flushes++
                }
            })
            controller.onStartInput(eligible = true)
        }

        /** Types one more piece of the growing word and answers the lookup with [result]. */
        fun type(word: String, result: List<String> = emptyList()) {
            editor.word = word
            controller.onTextChanged()
            engine.callback?.onResult(engine.requested.size.toLong(), result, LookupKind.PREFIX)
        }

        /** The word ends (a separator): the trailing word becomes empty; the cache holds [context]. */
        fun endWord(context: String) {
            editor.word = ""
            editor.wordBeforeTrailing = context
            controller.onTextChanged()
        }

        /**
         * Witnesses one word boundary, which is what makes the NEXT run countable. A session starts
         * without one on purpose — see [theFirstBoundaryOfASessionReportsNothing].
         */
        fun witnessABoundary() {
            type("баш")
            endWord("")
        }
    }

    @Test
    fun theFirstBoundaryOfASessionReportsNothing() {
        val h = Harness()
        // No word boundary has been witnessed yet: the trailing word may be text the app pre-filled
        // and the user merely appended to. Fail closed, exactly like the words machine.
        h.type("дөн")
        h.type("дөнья")
        h.endWord("сәләм")
        assertTrue(h.pairs.isEmpty())
    }

    @Test
    fun aPairSpelledOutCleanlyIsReportedOnceWithItsContext() {
        val h = Harness()
        h.witnessABoundary()
        h.type("дөн")
        h.type("дөнья")
        h.endWord("сәләм")
        assertEquals(listOf("сәләм" to "дөнья"), h.pairs)
    }

    @Test
    fun anEmptyContextReportsNothing() {
        val h = Harness()
        h.witnessABoundary()
        h.type("дөнья")
        h.endWord("")
        assertTrue("the first word of a text has no pair", h.pairs.isEmpty())
    }

    @Test
    fun theCompletedWordIsReportedRawAndTheContextComesFromTheLiveCache() {
        val h = Harness()
        h.witnessABoundary()
        h.type("Гү")
        h.type("Гүзәл")
        h.endWord("Минем")
        // Both halves travel RAW, exactly as they stand in the editor: normalization is the
        // store's business, and a capitalized name keeps its capital here.
        assertEquals(listOf("Минем" to "Гүзәл"), h.pairs)
    }

    @Test
    fun backspaceMakesTheRunDirtyAndNoPairIsReported() {
        val h = Harness()
        h.witnessABoundary()
        h.type("дөн")
        h.type("дө") // backspace
        h.type("дөнья")
        h.endWord("сәләм")
        assertTrue("a corrected word is not a clean run", h.pairs.isEmpty())
    }

    @Test
    fun aSelectionChangeSacrificesTheNextWordOnly() {
        val h = Harness()
        h.witnessABoundary()
        h.controller.onSelectionChanged()
        h.type("дөнья")
        h.endWord("сәләм")
        assertTrue("the word after a position break is not trusted", h.pairs.isEmpty())
        // …and the boundary it completed re-arms the machine for the word after it.
        h.type("китап")
        h.endWord("мин")
        assertEquals(listOf("мин" to "китап"), h.pairs)
    }

    @Test
    fun aSubtypeChangeSacrificesTheNextWord() {
        val h = Harness()
        h.witnessABoundary()
        h.type("дөн")
        h.controller.onSubtypeChanged(eligible = true)
        h.type("дөнья")
        h.endWord("сәләм")
        assertTrue(h.pairs.isEmpty())
    }

    @Test
    fun aTappedCompletionCountsAsTheContextOfTheNextCleanWord() {
        val h = Harness()
        h.witnessABoundary()
        // The user ACCEPTS a suggestion («сәләм» from the band), then spells out the next word:
        // the tapped word is a committed context like any other ("typed or tapped").
        h.type("сәл", listOf("сәләм"))
        h.editor.word = ""
        h.strip.listener!!.onTap("сәләм")
        h.type("дөн")
        h.type("дөнья")
        h.endWord("сәләм")
        assertEquals(listOf("сәләм" to "дөнья"), h.pairs)
    }

    @Test
    fun theTappedWordItselfNeverCountsAsACleanCompletion() {
        val h = Harness()
        h.witnessABoundary()
        h.type("дөн", listOf("дөнья"))
        h.editor.word = ""
        h.strip.listener!!.onTap("дөнья")
        // The tap commits the word — no boundary report fires for it, and nothing pairs it.
        assertTrue(h.pairs.isEmpty())
        assertTrue(h.acceptances.isEmpty()) // a PREFIX tap is not a NEXT_WORD acceptance either
    }

    @Test
    fun twoPairsInARowCountOnTheirOwn() {
        val h = Harness()
        h.witnessABoundary()
        h.type("дөнья")
        h.endWord("сәләм")
        h.type("китап")
        h.endWord("мин")
        assertEquals(listOf("сәләм" to "дөнья", "мин" to "китап"), h.pairs)
    }

    @Test
    fun theSessionEndFlushesExactlyOnce() {
        val h = Harness()
        h.witnessABoundary()
        h.type("дөн")
        h.controller.onFinishInput()
        assertEquals(1, h.flushes)
        assertTrue("an unfinished word is not a completed one", h.pairs.isEmpty())
    }

    @Test
    fun anAcceptedNextWordPredictionBumpsThePairAcceptanceHook() {
        val h = Harness()
        h.witnessABoundary()
        // Bind a NEXT_WORD band for the context «сәләм»: the empty prefix falls through to
        // NEXT_WORD, and the engine's synchronous answer binds the band to that context.
        h.editor.context = "сәләм"
        h.engine.nextWordAnswer = listOf("дөнья")
        h.editor.word = ""
        h.controller.onTextChanged()

        h.strip.listener!!.onTap("дөнья")
        assertEquals(listOf("сәләм" to "дөнья"), h.acceptances)
        // And the accepted prediction is a trusted context for whatever the user types next.
        h.type("эштә")
        h.endWord("дөнья")
        assertEquals(listOf("дөнья" to "эштә"), h.pairs)
    }

    @Test
    fun aSentenceStartTapNeverReachesThePairSink() {
        val h = Harness()
        h.witnessABoundary()
        // A sentence-start band binds the EMPTY context; its tap must not become an acceptance.
        h.editor.context = ""
        h.engine.nextWordAnswer = listOf("Иң")
        h.editor.word = ""
        h.controller.onTextChanged()
        h.strip.listener!!.onTap("Иң")
        assertTrue(h.acceptances.isEmpty())
    }
}
