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
 * The sentence-start band (TT-SUGGESTIONS phase P4, `docs/TT-SUGGESTIONS.md`; ROADMAP Phase 1
 * P3a/P3b/P4, `docs/ROADMAP-P1.md`): where the frozen contract used to show nothing — the start
 * of the field, or sentence-ending punctuation followed by space(s) — the slot shows the top of
 * ITS language's sentence-start table, answered synchronously from the loaded asset with NO
 * engine request (a sentence boundary resets the context: no bigram successors, no after-word
 * forms, no fallback). Since P3a the cells are shown AND committed capitalized; since P3b the
 * table is per language through the artifact-registry seam, so the Russian slot answers from the
 * Russian table exactly like the Tatar one; and since P4 non-final punctuation (`, ; :`) keeps
 * the word before it as an ordinary NEXT_WORD context — pinned here end-to-end through the REAL
 * extraction. A subtype with no table stays silent, exactly as pre-P4.
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

        /**
         * When set, the three text seams are answered by the REAL [TatarWordUtils] extraction
         * over this text (with cache-start provenance) — the end-to-end mode the P4 punctuation
         * cases are pinned through, so the controller and the extraction can never drift apart.
         */
        var rawText: String? = null
        val predictedCommits = mutableListOf<Pair<String, String>>()

        override fun cachedWordBeforeCursor(): String =
            rawText?.let { TatarWordUtils.extractTrailingWord(it) } ?: word

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = false
        override fun hasKnownCursor(): Boolean = knownCursor
        override fun hasLetterAfterCursor(): Boolean = letterAfterCursor
        override fun cachedNextWordContext(): String =
            rawText?.let { TatarWordUtils.extractNextWordContext(it, true) } ?: contextWord

        override fun isAtSentenceStart(): Boolean =
            rawText?.let { TatarWordUtils.isSentenceStartContext(it, true) } ?: sentenceStart

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

    /**
     * The per-language harness (P3b): [tables] is the factory's answer per subtype — the test's
     * stand-in for the artifact registry. A subtype absent from the map has NO table, exactly
     * like a language the registry carries without a `sentStartAssetPath`.
     */
    private class Harness(
        words: List<String> = TABLE,
        val tables: Map<String, List<String>> = mapOf(PersonalSubtypes.TATAR_RU to words),
    ) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val executor = DirectExecutorService()
        val engines = LinkedHashMap<String, FakeEngine>()
        val callbacks = LinkedHashMap<String, ResultCallback>()
        val preparations = LinkedHashMap<String, FakeSentStartPreparation>()

        private fun preparationFor(subtypeId: String): FakeSentStartPreparation =
            preparations.getOrPut(subtypeId) {
                FakeSentStartPreparation(FakeSentStartSource(tables.getValue(subtypeId)))
            }

        /** The Tatar table's preparation seam — created on first access, the same instance the
         * factory hands the controller, so a test can arm it BEFORE the first load. */
        val sentStartPreparation get() = preparationFor(PersonalSubtypes.TATAR_RU)

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
            { _: ExecutorService, subtypeId: String ->
                if (tables.containsKey(subtypeId)) preparationFor(subtypeId) else null
            },
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

        /** Brings [subtypeId]'s slot up at a sentence start (an empty field's start). The band
         * the startup paints is deliberately NOT cleared — it is the thing under test. */
        fun startAtSentenceStart(subtypeId: String = PersonalSubtypes.TATAR_RU) {
            editor.word = ""
            editor.contextWord = ""
            editor.sentenceStart = true
            controller.onStartInput(eligible = true, subtypeId = subtypeId)
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

        assertEquals(CAPITALIZED_TABLE.take(3), h.strip.lastCells())
        // The suppression contract, observable: no NEXT_WORD and no PREFIX request was ever made.
        assertTrue(h.engine(tatar).nextWordRequests.isEmpty())
        assertTrue(h.engine(tatar).prefixRequests.isEmpty())
    }

    @Test
    fun afterSentenceEndingPunctuationAndSpaceTheTableAnswers() {
        val h = Harness()
        h.startAtSentenceStart()

        h.typeSentenceEndAndSpace()

        assertEquals(CAPITALIZED_TABLE.take(3), h.strip.lastCells())
        assertTrue(h.engine(tatar).nextWordRequests.isEmpty())
    }

    @Test
    fun aPositionThatIsNotASentenceStartKeepsTheFrozenSilence() {
        val h = Harness()
        h.editor.word = ""
        h.editor.contextWord = ""
        h.editor.sentenceStart = false // a context-free mid-sentence position
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

    // --- P3a: the capitalization ---------------------------------------------------------------

    @Test
    fun theCellsAreShownAndCommittedCapitalized() {
        val h = Harness()
        h.startAtSentenceStart()
        h.typeSentenceEndAndSpace()
        assertEquals(CAPITALIZED_TABLE.take(3), h.strip.lastCells())

        requireNotNull(h.strip.tap).onTap("Ул")

        // The tap inserts the displayed (capitalized) form verbatim.
        assertEquals(listOf("" to "Ул"), h.editor.predictedCommits)
    }

    // --- P3b: the Russian slot answers from its own table ----------------------------------------

    @Test
    fun theRussianFieldStartOffersTheRussianTableCapitalized() {
        val h = Harness(tables = mapOf(tatar to TABLE, russian to RU_TABLE))
        h.startAtSentenceStart(russian)

        assertEquals(RU_CAPITALIZED_TABLE.take(3), h.strip.lastCells())
        // The Russian load never touches the Tatar table, and the engine is never asked.
        assertTrue(h.preparations.keys.none { it == tatar })
        assertTrue(h.engine(russian).nextWordRequests.isEmpty())
    }

    @Test
    fun eachLanguageLoadsItsOwnTableExactlyOnce() {
        val h = Harness(tables = mapOf(tatar to TABLE, russian to RU_TABLE))
        h.startAtSentenceStart(tatar)
        assertEquals(CAPITALIZED_TABLE.take(3), h.strip.lastCells())

        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)
        h.typeSentenceEndAndSpace()
        h.typeSentenceEndAndSpace()

        assertEquals(RU_CAPITALIZED_TABLE.take(3), h.strip.lastCells())
        assertEquals(1, h.preparations.getValue(tatar).prepareCalls)
        assertEquals(1, h.preparations.getValue(russian).prepareCalls)
    }

    @Test
    fun aSubtypeWithoutATableStaysSilentAndLoadsNothing() {
        val h = Harness() // Tatar only: the factory has no Russian table
        h.editor.sentenceStart = true
        h.controller.onStartInput(eligible = true, subtypeId = russian)

        assertEquals(emptyList<String>(), h.strip.lastCells())
        assertTrue(h.preparations.isEmpty())
        assertTrue(h.engine(russian).nextWordRequests.isEmpty())
    }

    // --- The tap ---------------------------------------------------------------------------------

    @Test
    fun aTapCommitsThroughThePredictedWordPathWithTheEmptyContextBinding() {
        val h = Harness()
        h.startAtSentenceStart()
        h.typeSentenceEndAndSpace()

        requireNotNull(h.strip.tap).onTap("Ул")

        assertEquals(listOf("" to "Ул"), h.editor.predictedCommits)
    }

    @Test
    fun aStaleTapCommitsNothing() {
        val h = Harness()
        h.startAtSentenceStart()
        h.typeSentenceEndAndSpace()
        assertEquals(CAPITALIZED_TABLE.take(3), h.strip.lastCells())

        // The user typed on before tapping: the band is unbound, the tap must be a no-op.
        h.editor.word = "б"
        h.editor.sentenceStart = false
        h.controller.onTextChanged()
        requireNotNull(h.strip.tap).onTap("Ул")

        assertTrue(h.editor.predictedCommits.isEmpty())
    }

    @Test
    fun typingAPrefixLeavesTheSentenceStartBand() {
        val h = Harness()
        h.startAtSentenceStart()
        h.typeSentenceEndAndSpace()
        assertEquals(CAPITALIZED_TABLE.take(3), h.strip.lastCells())

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
        h.sentStartPreparation.source = null // armed before the first sentence start
        h.startAtSentenceStart()
        assertEquals(emptyList<String>(), h.strip.lastCells())

        h.typeSentenceEndAndSpace()

        assertEquals(emptyList<String>(), h.strip.lastCells())
        assertEquals(1, h.sentStartPreparation.prepareCalls)
    }

    @Test
    fun aSentenceStartReachedBeforeTheLoadFinishedIsFilledWhenTheTableArrives() {
        val h = Harness()
        h.sentStartPreparation.deferred = true // armed before the first sentence start
        h.startAtSentenceStart()
        // The load is still in flight: the band is exactly what it would be without the feature.
        assertEquals(emptyList<String>(), h.strip.lastCells())

        h.sentStartPreparation.fire()

        assertEquals(CAPITALIZED_TABLE.take(3), h.strip.lastCells())
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

        // The cursor moved to a context-free mid-sentence position.
        h.editor.sentenceStart = false
        h.controller.onSelectionChanged()
        h.controller.onCursorMoveSettled()
        h.sentStartPreparation.fire()

        assertEquals(emptyList<String>(), h.strip.lastCells())
    }

    @Test
    fun aLoadForALanguageTheUserLeftDoesNotPaintItsBand() {
        val h = Harness(tables = mapOf(tatar to TABLE, russian to RU_TABLE))
        h.sentStartPreparation.deferred = true
        h.startAtSentenceStart(tatar) // the Tatar load is now in flight
        assertEquals(emptyList<String>(), h.strip.lastCells())

        // The user switches to Russian before the Tatar table lands; the late answer is stored
        // for Tatar but paints nothing on the Russian slot.
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)
        h.strip.shown.clear()
        h.sentStartPreparation.fire()
        assertEquals(emptyList<String>(), h.strip.lastCells())

        // The Russian band comes from the Russian table, never from the Tatar load.
        h.editor.sentenceStart = true
        h.controller.onTextChanged()
        assertEquals(RU_CAPITALIZED_TABLE.take(3), h.strip.lastCells())
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

    // --- P4: non-final punctuation keeps the word before it as the context -----------------------

    @Test
    fun afterACommaTheWordBeforeItIsTheContextAskedFromTheEngine() {
        val h = Harness()
        h.controller.onStartInput(eligible = true, subtypeId = tatar)

        h.editor.rawText = "сүз, "
        h.controller.onTextChanged()

        // The REAL extraction drives the request: bigrams/forms/fallback of сүз, and the
        // sentence-start table is never touched — a comma is not a sentence end.
        assertEquals(listOf("сүз"), h.engine(tatar).nextWordRequests)
        assertTrue(h.preparations.isEmpty())
        h.deliver(tatar, listOf("бар", "юк"))
        assertEquals(listOf("бар", "юк"), h.strip.lastCells())
    }

    @Test
    fun afterASemicolonOrColonTheWordBeforeItIsTheContextToo() {
        val h = Harness()
        h.controller.onStartInput(eligible = true, subtypeId = tatar)

        h.editor.rawText = "сүз; "
        h.controller.onTextChanged()
        h.editor.rawText = "сүз: "
        h.controller.onTextChanged()

        assertEquals(listOf("сүз", "сүз"), h.engine(tatar).nextWordRequests)
    }

    @Test
    fun aCommaWithNothingBeforeItPredictsNothing() {
        val h = Harness()
        h.controller.onStartInput(eligible = true, subtypeId = tatar)

        h.editor.rawText = ", "
        h.controller.onTextChanged()

        assertTrue(h.engine(tatar).nextWordRequests.isEmpty())
        assertEquals(emptyList<String>(), h.strip.lastCells())
        // ...and it is not a sentence start either, so no table load.
        assertTrue(h.preparations.isEmpty())
    }

    @Test
    fun aMixedPunctuationRunPredictsNothing() {
        val h = Harness()
        h.controller.onStartInput(eligible = true, subtypeId = tatar)

        // Final punctuation inside the run keeps the boundary closed: no context word, and the
        // comma at the end is not a sentence end either.
        h.editor.rawText = "сүз.., "
        h.controller.onTextChanged()

        assertTrue(h.engine(tatar).nextWordRequests.isEmpty())
        assertEquals(emptyList<String>(), h.strip.lastCells())
        assertTrue(h.preparations.isEmpty())
    }

    @Test
    fun russianAfterACommaKeepsTheRussianContext() {
        val h = Harness(tables = mapOf(tatar to TABLE, russian to RU_TABLE))
        h.controller.onStartInput(eligible = true, subtypeId = russian)

        h.editor.rawText = "слово, "
        h.controller.onTextChanged()

        assertEquals(listOf("слово"), h.engine(russian).nextWordRequests)
        assertTrue(h.preparations.isEmpty())
        h.deliver(russian, listOf("дело"))
        assertEquals(listOf("дело"), h.strip.lastCells())
    }

    @Test
    fun sentenceFinalPunctuationStillAnswersTheSentenceStartTableThroughTheRealDetector() {
        val h = Harness()
        h.controller.onStartInput(eligible = true, subtypeId = tatar)

        h.editor.rawText = "сүз. "
        h.controller.onTextChanged()

        assertEquals(CAPITALIZED_TABLE.take(3), h.strip.lastCells())
        assertTrue(h.engine(tatar).nextWordRequests.isEmpty())
    }

    // --- The gates -------------------------------------------------------------------------------

    @Test
    fun anIneligibleFieldNeverLoadsTheTable() {
        val h = Harness()
        h.controller.onStartInput(eligible = false, subtypeId = tatar)
        h.editor.sentenceStart = true
        h.controller.onTextChanged()

        assertTrue(h.preparations.isEmpty())
        assertEquals(emptyList<String>(), h.strip.lastCells())
    }

    @Test
    fun aDestroyedControllerDoesNotFill() {
        val h = Harness()
        h.sentStartPreparation.deferred = true
        h.startAtSentenceStart()

        h.controller.onDestroy()
        h.sentStartPreparation.fire()

        assertTrue(h.strip.shown.none { it.filterNotNull().isNotEmpty() })
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

        assertEquals(CAPITALIZED_TABLE.take(3), h.strip.lastCells())
        assertTrue(h.engine(russian).nextWordRequests.isEmpty())
        assertTrue(h.engine(russian).prefixRequests.isEmpty())
    }

    private companion object {
        /** A four-word table, so the band cap (three cells) is observable. */
        val TABLE = listOf("бу", "ул", "ә", "бүген")

        /** P3a: the cells the band actually shows — the table words, capitalized. */
        val CAPITALIZED_TABLE = listOf("Бу", "Ул", "Ә", "Бүген")

        val RU_TABLE = listOf("в", "по", "на", "он")
        val RU_CAPITALIZED_TABLE = listOf("В", "По", "На", "Он")
    }
}
