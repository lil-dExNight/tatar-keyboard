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

import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog

/**
 * The sentence-start band (TT-SUGGESTIONS phase P4, `docs/TT-SUGGESTIONS.md`): where the frozen
 * contract used to show nothing — the start of the field, or sentence-ending punctuation followed
 * by space(s) — the Tatar slot shows the top of the sentence-start table, answered synchronously
 * from the loaded asset with NO engine request (a sentence boundary resets the context: no bigram
 * successors, no after-word forms). The Russian slot ships no table and is byte-identical to
 * before; every failure direction — not a sentence start, a broken or missing table, an
 * ineligible field — is the reserved empty band, exactly as pre-P4.
 */
class SuggestionsControllerSentStartTest {

    private val tatar = PersonalSubtypes.TATAR_RU
    private val russian = PersonalSubtypes.RUSSIAN

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        val shown = mutableListOf<List<String?>>()
        var reserveCount = 0
        var hideCount = 0
        var tap: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?) {
            shown.add(listOf(first, second, third))
        }

        override fun reserve() {
            reserveCount++
        }

        override fun hideSuggestions() {
            hideCount++
        }

        override fun setTapListener(listener: SuggestionTapListener) {
            tap = listener
        }

        /** The cells as the user would read them: nulls trimmed off the end. */
        fun lastCells(): List<String> =
            shown.lastOrNull()?.filterNotNull() ?: emptyList()
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""
        var contextWord: String = ""
        var sentenceStart: Boolean = false
        var letterAfterCursor: Boolean = false
        var knownCursor: Boolean = true
        val predictedCommits = mutableListOf<Pair<String, String>>()

        override fun cachedWordBeforeCursor(): String = word
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = false
        override fun hasKnownCursor(): Boolean = knownCursor
        override fun hasLetterAfterCursor(): Boolean = letterAfterCursor
        override fun cachedNextWordContext(): String = contextWord
        override fun isAtSentenceStart(): Boolean = sentenceStart
        override fun commitPredictedWord(expectedContextWord: String, suggestion: String): Boolean {
            predictedCommits.add(expectedContextWord to suggestion)
            return true
        }
    }

    /** One engine per language; every request gets a FRESH token, like the real engine's. */
    private class FakeEngine : EngineHandle {
        val nextWordRequests = mutableListOf<String>()
        val prefixRequests = mutableListOf<String>()
        private var current: Any? = null

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            prefixRequests.add(String(prefixUtf8, Charsets.UTF_8))
            return Any().also { current = it }
        }

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any? {
            nextWordRequests.add(String(contextWordUtf8, Charsets.UTF_8))
            return Any().also { current = it }
        }

        override fun isCurrent(token: Any): Boolean = token === current
        override fun finishInput() {
            current = null
        }

        override fun destroy(timeoutMs: Long): Boolean = true

        fun currentToken(): Any = requireNotNull(current)
    }

    private class FakeCatalog : PublishedDictionaryCatalog {
        override fun acquireLatestForActivation(): DictionaryFileLease? = null
        override fun cleanupReleasedVersions() = Unit
    }

    private class FakePreparation : DictionaryPreparation {
        private val catalog = FakeCatalog()
        override fun prepare(onResult: (PreparationResult) -> Unit) {
            onResult(
                PreparationResult.Published(
                    PublishedDictionary(
                        generation = 1,
                        file = File("/dev/null"),
                        rawSize = 72,
                        entryCount = 1,
                        schemaId = 1,
                        formatVersion = 1,
                        rawSha256 = "0".repeat(64),
                    ),
                    alreadyPresent = true,
                ),
            )
        }

        override fun catalog(): PublishedDictionaryCatalog = catalog
    }

    private class FakeSentStartSource(private val words: List<String>) : SentStartSource {
        var lookups = 0
        override fun topWords(maxOut: Int): List<String> {
            lookups++
            return words.take(maxOut)
        }
    }

    /** Immediate by default; [deferred] mode captures the callback for the test to fire by hand. */
    private class FakeSentStartPreparation(var source: SentStartSource?) : SentStartPreparation {
        var prepareCalls = 0
        var deferred = false
        private var pending: ((SentStartSource?) -> Unit)? = null

        override fun prepare(onResult: (SentStartSource?) -> Unit) {
            prepareCalls++
            if (deferred) {
                pending = onResult
            } else {
                onResult(source)
            }
        }

        fun fire() {
            requireNotNull(pending).invoke(source)
            pending = null
        }
    }

    private class DirectExecutorService : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class Harness(words: List<String> = TABLE) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val executor = DirectExecutorService()
        val engines = LinkedHashMap<String, FakeEngine>()
        val callbacks = LinkedHashMap<String, ResultCallback>()
        val source = FakeSentStartSource(words)
        val sentStartPreparation = FakeSentStartPreparation(source)

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { subtypeId, callback ->
                callbacks[subtypeId] = callback
                engines.getOrPut(subtypeId) { FakeEngine() }
            },
            { executor },
            { _: ExecutorService, _: String -> FakePreparation() },
            false,
            { _: ExecutorService, _: String -> null },
            { _: ExecutorService -> null },
            { _: ExecutorService -> sentStartPreparation },
        )

        fun engine(subtypeId: String): FakeEngine = engines.getValue(subtypeId)

        fun deliver(
            subtypeId: String,
            suggestions: List<String>,
            kind: LookupKind = LookupKind.NEXT_WORD,
        ) {
            callbacks.getValue(subtypeId)
                .onResult(engine(subtypeId).currentToken(), suggestions, kind)
        }

        /** Brings the Tatar slot up at a sentence start (an empty field's start). The band the
         * startup paints is deliberately NOT cleared — it is the thing under test. */
        fun startAtSentenceStart() {
            editor.word = ""
            editor.contextWord = ""
            editor.sentenceStart = true
            controller.onStartInput(eligible = true, subtypeId = PersonalSubtypes.TATAR_RU)
        }

        /** The user typed ". " after a word: the NEXT_WORD context is empty, the position is a
         * sentence start. */
        fun typeSentenceEndAndSpace() {
            editor.word = ""
            editor.contextWord = ""
            editor.sentenceStart = true
            controller.onTextChanged()
        }
    }

    // --- The band ------------------------------------------------------------------------------

    @Test
    fun theFieldStartOffersTheTopOfTheTableWithoutAskingTheEngine() {
        val h = Harness()
        h.startAtSentenceStart()

        assertEquals(TABLE.take(3), h.strip.lastCells())
        // The suppression contract, observable: no NEXT_WORD and no PREFIX request was ever made.
        assertTrue(h.engine(tatar).nextWordRequests.isEmpty())
        assertTrue(h.engine(tatar).prefixRequests.isEmpty())
    }

    @Test
    fun afterSentenceEndingPunctuationAndSpaceTheTableAnswers() {
        val h = Harness()
        h.startAtSentenceStart()

        h.typeSentenceEndAndSpace()

        assertEquals(TABLE.take(3), h.strip.lastCells())
        assertTrue(h.engine(tatar).nextWordRequests.isEmpty())
    }

    @Test
    fun aPositionThatIsNotASentenceStartKeepsTheFrozenSilence() {
        val h = Harness()
        h.editor.word = ""
        h.editor.contextWord = ""
        h.editor.sentenceStart = false // after ", " for instance
        h.controller.onStartInput(eligible = true, subtypeId = tatar)

        assertEquals(emptyList<String>(), h.strip.lastCells())
        assertTrue(h.engine(tatar).nextWordRequests.isEmpty())
    }

    @Test
    fun aRealContextWordStillGoesToTheEngine() {
        val h = Harness()
        h.startAtSentenceStart()
        h.editor.contextWord = "татар"
        h.editor.sentenceStart = false
        h.controller.onTextChanged()

        assertEquals(listOf("татар"), h.engine(tatar).nextWordRequests)
        h.deliver(tatar, listOf("белән"))
        assertEquals(listOf("белән"), h.strip.lastCells())
    }

    // --- The Russian slot: no asset, no behavior change -----------------------------------------

    @Test
    fun theRussianSlotNeverOffersSentenceStartAndNeverLoadsTheTable() {
        val h = Harness()
        h.editor.sentenceStart = true
        h.controller.onStartInput(eligible = true, subtypeId = russian)

        assertEquals(emptyList<String>(), h.strip.lastCells())
        assertEquals(0, h.sentStartPreparation.prepareCalls)
        assertTrue(h.engine(russian).nextWordRequests.isEmpty())
    }

    // --- The tap ---------------------------------------------------------------------------------

    @Test
    fun aTapCommitsThroughThePredictedWordPathWithTheEmptyContextBinding() {
        val h = Harness()
        h.startAtSentenceStart()
        h.typeSentenceEndAndSpace()

        requireNotNull(h.strip.tap).onTap("ул")

        assertEquals(listOf("" to "ул"), h.editor.predictedCommits)
    }

    @Test
    fun aStaleTapCommitsNothing() {
        val h = Harness()
        h.startAtSentenceStart()
        h.typeSentenceEndAndSpace()
        assertEquals(TABLE.take(3), h.strip.lastCells())

        // The user typed on before tapping: the band is unbound, the tap must be a no-op.
        h.editor.word = "б"
        h.editor.sentenceStart = false
        h.controller.onTextChanged()
        requireNotNull(h.strip.tap).onTap("ул")

        assertTrue(h.editor.predictedCommits.isEmpty())
    }

    @Test
    fun typingAPrefixLeavesTheSentenceStartBand() {
        val h = Harness()
        h.startAtSentenceStart()
        h.typeSentenceEndAndSpace()
        assertEquals(TABLE.take(3), h.strip.lastCells())

        h.editor.word = "б"
        h.editor.sentenceStart = false
        h.controller.onTextChanged()

        assertEquals(listOf("б"), h.engine(tatar).prefixRequests)
        h.deliver(tatar, listOf("бу", "бүген"), LookupKind.PREFIX)
        assertEquals(listOf("бу", "бүген"), h.strip.lastCells())
    }

    // --- Loading -------------------------------------------------------------------------------

    @Test
    fun theTableIsLoadedAtMostOncePerProcess() {
        val h = Harness()
        h.startAtSentenceStart()
        h.typeSentenceEndAndSpace()
        h.typeSentenceEndAndSpace()

        assertEquals(1, h.sentStartPreparation.prepareCalls)
    }

    @Test
    fun aFailedLoadIsSilentAndNeverRetried() {
        val h = Harness()
        h.sentStartPreparation.source = null
        h.startAtSentenceStart()
        assertEquals(emptyList<String>(), h.strip.lastCells())

        h.typeSentenceEndAndSpace()

        assertEquals(emptyList<String>(), h.strip.lastCells())
        assertEquals(1, h.sentStartPreparation.prepareCalls)
    }

    @Test
    fun aSentenceStartReachedBeforeTheLoadFinishedIsFilledWhenTheTableArrives() {
        val h = Harness()
        h.sentStartPreparation.deferred = true
        h.startAtSentenceStart()
        // The load is still in flight: the band is exactly what it would be without the feature.
        assertEquals(emptyList<String>(), h.strip.lastCells())

        h.sentStartPreparation.fire()

        assertEquals(TABLE.take(3), h.strip.lastCells())
    }

    @Test
    fun aLoadThatFinishedAfterTheUserTypedOnFillsNothing() {
        val h = Harness()
        h.sentStartPreparation.deferred = true
        h.startAtSentenceStart()

        // The user is typing the first word already when the table arrives.
        h.editor.word = "а"
        h.editor.sentenceStart = false
        h.controller.onTextChanged()
        h.sentStartPreparation.fire()

        assertEquals(emptyList<String>(), h.strip.lastCells())
    }

    @Test
    fun aLoadThatFinishedAtANonSentenceStartFillsNothing() {
        val h = Harness()
        h.sentStartPreparation.deferred = true
        h.startAtSentenceStart()

        // The cursor moved to a mid-sentence context-free position (after ", ").
        h.editor.sentenceStart = false
        h.controller.onSelectionChanged()
        h.controller.onCursorMoveSettled()
        h.sentStartPreparation.fire()

        assertEquals(emptyList<String>(), h.strip.lastCells())
    }

    @Test
    fun aThrowingSourceFailsClosedToTheReservedBand() {
        val h = Harness()
        h.sentStartPreparation.source = SentStartSource { throw RuntimeException("broken") }
        h.startAtSentenceStart()

        assertEquals(emptyList<String>(), h.strip.lastCells())
    }

    @Test
    fun anEmptyTableShowsTheReservedBand() {
        val h = Harness(words = emptyList())
        h.startAtSentenceStart()

        assertEquals(emptyList<String>(), h.strip.lastCells())
    }

    // --- The gates -------------------------------------------------------------------------------

    @Test
    fun anIneligibleFieldNeverLoadsTheTable() {
        val h = Harness()
        h.controller.onStartInput(eligible = false, subtypeId = tatar)
        h.editor.sentenceStart = true
        h.controller.onTextChanged()

        assertEquals(0, h.sentStartPreparation.prepareCalls)
        assertEquals(emptyList<String>(), h.strip.lastCells())
    }

    @Test
    fun aDestroyedControllerDoesNotFill() {
        val h = Harness()
        h.sentStartPreparation.deferred = true
        h.startAtSentenceStart()

        h.controller.onDestroy()
        h.sentStartPreparation.fire()

        assertTrue(h.strip.shown.isEmpty())
    }

    @Test
    fun theCompanionLanguageIsNeverAskedAtASentenceStart() {
        val h = Harness()
        // Warm the companion slot exactly like the language-priority tests do.
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)
        h.controller.onSubtypeChanged(eligible = true, subtypeId = tatar)
        h.strip.shown.clear()

        h.typeSentenceEndAndSpace()

        assertEquals(TABLE.take(3), h.strip.lastCells())
        assertTrue(h.engine(russian).nextWordRequests.isEmpty())
        assertTrue(h.engine(russian).prefixRequests.isEmpty())
    }

    private companion object {
        /** A four-word table, so the band cap (three cells) is observable. */
        val TABLE = listOf("бу", "ул", "ә", "бүген")
    }
}
