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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectAdvice
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The language-priority rule: the layout the user chose with their own hand owns the band, and the
 * other language may only fill the cells that language left empty, from the end.
 *
 * Everything here is about the DISPLAY. Autocorrect, the personal-dictionary run and the
 * empty-result observation stay with the current language alone, and that is asserted too.
 */
class SuggestionsControllerLanguagePriorityTest {

    private val tatar = PersonalSubtypes.TATAR_RU
    private val russian = PersonalSubtypes.RUSSIAN

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        val shown = mutableListOf<List<String?>>()
        var reserveCount = 0
        var hideCount = 0

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

        var tap: SuggestionTapListener? = null

        /** The cells as the user would read them: nulls trimmed off the end. */
        fun lastCells(): List<String> =
            shown.lastOrNull()?.filterNotNull() ?: emptyList()
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""
        var contextWord: String = ""
        var letterAfterCursor: Boolean = false
        val committed = mutableListOf<Pair<String, String>>()
        val replaced = mutableListOf<Pair<String, String>>()

        override fun cachedWordBeforeCursor(): String = word
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            committed.add(expectedPrefix to suggestion)
            return true
        }

        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = letterAfterCursor
        override fun cachedNextWordContext(): String = contextWord
        override fun replaceTypedWord(expectedPrefix: String, replacement: String): Boolean {
            replaced.add(expectedPrefix to replacement)
            return true
        }

        override fun commitPredictedWord(expectedContextWord: String, suggestion: String): Boolean {
            committed.add(expectedContextWord to suggestion)
            return true
        }
    }

    /**
     * One engine per language. Every request gets a FRESH token, so a test can deliver a result for
     * an older one and watch it be dropped exactly as a stale engine result is.
     */
    private class FakeEngine(val subtypeId: String) : EngineHandle {
        val prefixRequests = mutableListOf<String>()
        val nextWordRequests = mutableListOf<String>()
        var advice: AutocorrectAdvice? = null
        var adviceReads = 0
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

        override fun autocorrectAdvice(): AutocorrectAdvice? {
            adviceReads++
            return advice
        }

        override fun finishInput() {
            current = null
        }

        override fun destroy(timeoutMs: Long): Boolean = true

        /** The token of the newest request; what the engine would hand back with its result. */
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

    private class DirectExecutorService : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class Harness {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val executor = DirectExecutorService()
        val engines = LinkedHashMap<String, FakeEngine>()
        val callbacks = LinkedHashMap<String, ResultCallback>()

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { subtypeId, callback ->
                callbacks[subtypeId] = callback
                engines.getOrPut(subtypeId) { FakeEngine(subtypeId) }
            },
            { executor },
            { _: ExecutorService, _: String -> FakePreparation() },
            false,
            { _: ExecutorService, _: String -> null },
        )

        fun engine(subtypeId: String): FakeEngine = engines.getValue(subtypeId)

        /** Delivers [suggestions] as the newest result of [subtypeId]'s engine. */
        fun deliver(
            subtypeId: String,
            suggestions: List<String>,
            kind: LookupKind = LookupKind.PREFIX,
        ) {
            callbacks.getValue(subtypeId)
                .onResult(engine(subtypeId).currentToken(), suggestions, kind)
        }

        /** Brings both languages' engines up, leaving [ending] active in a fresh field. */
        fun warmBoth(ending: String) {
            controller.onStartInput(eligible = true, subtypeId = PersonalSubtypes.TATAR_RU)
            controller.onSubtypeChanged(eligible = true, subtypeId = PersonalSubtypes.RUSSIAN)
            controller.onSubtypeChanged(eligible = true, subtypeId = ending)
            strip.shown.clear()
        }
    }

    // --- The rule ------------------------------------------------------------------------------

    @Test
    fun theOtherLanguageFillsOnlyTheCellsTheCurrentOneLeftEmpty() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.word = "бер"
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("бераз", "берәү"))

        assertEquals(listOf("бераз", "берәү"), h.strip.lastCells())
        assertEquals(listOf("бер"), h.engine(russian).prefixRequests)

        h.deliver(russian, listOf("берег", "берёза", "беречь"))
        assertEquals(listOf("бераз", "берәү", "берег"), h.strip.lastCells())
    }

    @Test
    fun aFullBandNeverAsksTheOtherLanguageAnything() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.word = "бер"
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("бераз", "берәү", "беренче"))

        assertEquals(listOf("бераз", "берәү", "беренче"), h.strip.lastCells())
        assertEquals(emptyList<String>(), h.engine(russian).prefixRequests)
    }

    @Test
    fun theOtherLanguageNeverTakesTheFirstCell() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.word = "бер"
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("бераз"))
        h.deliver(russian, listOf("берег", "берёза", "беречь"))

        assertEquals(listOf("бераз", "берег", "берёза"), h.strip.lastCells())
    }

    @Test
    fun anEmptyCurrentResultLetsTheOtherLanguageOpenTheBand() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.word = "прив"
        h.controller.onTextChanged()
        h.deliver(tatar, emptyList())

        assertEquals(emptyList<String>(), h.strip.lastCells())
        assertEquals(listOf("прив"), h.engine(russian).prefixRequests)

        h.deliver(russian, listOf("привет", "привык"))
        assertEquals(listOf("привет", "привык"), h.strip.lastCells())
    }

    @Test
    fun aWordBothLanguagesOfferOccupiesExactlyOneCell() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.word = "мин"
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("минем"))
        h.deliver(russian, listOf("минем", "минута", "минус"))

        assertEquals(listOf("минем", "минута", "минус"), h.strip.lastCells())
    }

    @Test
    fun theTypedCapitalizationReachesTheOtherLanguagesCellsToo() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.word = "Бер"
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("бераз"))
        h.deliver(russian, listOf("берег"))

        assertEquals(listOf("Бераз", "Берег"), h.strip.lastCells())
    }

    @Test
    fun tappingACellTheOtherLanguageFilledCommitsAgainstTheTypedPrefix() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.word = "бер"
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("бераз"))
        h.deliver(russian, listOf("берег"))

        requireNotNull(h.strip.tap).onTap("берег")
        assertEquals(listOf("бер" to "берег"), h.editor.committed)
    }

    @Test
    fun switchingLayoutSwapsWhoOwnsTheBandAndWhoFillsTheTail() {
        val h = Harness()
        h.warmBoth(russian)
        h.editor.word = "бер"
        h.controller.onTextChanged()
        h.deliver(russian, listOf("берег"))
        h.deliver(tatar, listOf("бераз", "берәү"))

        assertEquals(listOf("берег", "бераз", "берәү"), h.strip.lastCells())
    }

    @Test
    fun theFirstWordAfterASwitchIsAlreadyOwnedByTheNewLayout() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.word = "бер"
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("бераз"))
        h.deliver(russian, listOf("берег"))
        assertEquals(listOf("бераз", "берег"), h.strip.lastCells())

        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)
        h.controller.onTextChanged()
        h.deliver(russian, listOf("берег"))
        h.deliver(tatar, listOf("бераз"))
        assertEquals(listOf("берег", "бераз"), h.strip.lastCells())
    }

    // --- Nothing else changes ------------------------------------------------------------------

    @Test
    fun theOtherLanguageIsNotAskedWhenOnlyOneEngineIsWarm() {
        val h = Harness()
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.strip.shown.clear()
        h.editor.word = "бер"
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("бераз"))

        assertEquals(listOf("бераз"), h.strip.lastCells())
        assertFalse(h.engines.containsKey(russian))
    }

    @Test
    fun autocorrectNeverConsultsTheOtherLanguage() {
        val h = Harness()
        h.warmBoth(tatar)
        h.controller.setAutocorrectGate { true }
        h.editor.word = "бераз"
        h.controller.onTextChanged()
        h.deliver(tatar, emptyList())
        h.deliver(russian, listOf("берег", "берёза"))
        h.engine(russian).advice = AutocorrectAdvice("бераз", "берег", 1_000_000)

        assertFalse(h.controller.maybeAutocorrectBeforeSeparator(' '.code))
        assertEquals(emptyList<Pair<String, String>>(), h.editor.replaced)
        assertEquals(0, h.engine(russian).adviceReads)
    }

    @Test
    fun theOtherLanguagesEmptyResultNeverTeachesThePersonalDictionary() {
        val h = Harness()
        h.warmBoth(tatar)
        val learned = mutableListOf<String>()
        h.controller.setCompletionSink(object : WordCompletionSink {
            override fun onCleanCompletion(word: String) { learned.add(word) }
            override fun onInputFinished() = Unit
        })
        // The Tatar engine answers every prefix, so nothing about this run is evidence that the
        // word is missing from the dictionary — whatever the other language says about it.
        for (prefix in listOf("б", "бе", "бер")) {
            h.editor.word = prefix
            h.controller.onTextChanged()
            h.deliver(tatar, listOf("бераз", "берәү", "беренче"))
        }
        h.editor.word = "берни"
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("берни."))
        h.deliver(russian, emptyList())
        h.editor.word = ""
        h.controller.onTextChanged()

        assertEquals(emptyList<String>(), learned)
    }

    @Test
    fun anOtherLanguageResultForAPrefixTheUserHasLeftIsDropped() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.word = "бер"
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("бераз"))
        val staleToken = h.engine(russian).currentToken()

        h.editor.word = "берн"
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("берни", "берничә", "берничек"))
        val cellsBefore = h.strip.lastCells()

        h.callbacks.getValue(russian)
            .onResult(staleToken, listOf("берег"), LookupKind.PREFIX)
        assertEquals(cellsBefore, h.strip.lastCells())
    }

    @Test
    fun aCursorInsideAWordStillClearsTheBandWithoutAskingAnyone() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.letterAfterCursor = true
        h.editor.word = "бер"
        h.controller.onTextChanged()

        assertEquals(emptyList<String>(), h.engine(tatar).prefixRequests)
        assertEquals(emptyList<String>(), h.engine(russian).prefixRequests)
    }

    // --- NEXT_WORD -----------------------------------------------------------------------------

    @Test
    fun theSameRuleGovernsNextWordPredictions() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.word = ""
        h.editor.contextWord = "спасибо"
        h.controller.onTextChanged()
        h.deliver(tatar, emptyList(), LookupKind.NEXT_WORD)

        assertEquals(listOf("спасибо"), h.engine(russian).nextWordRequests)
        h.deliver(russian, listOf("за", "вам"), LookupKind.NEXT_WORD)
        assertEquals(listOf("за", "вам"), h.strip.lastCells())

        requireNotNull(h.strip.tap).onTap("за")
        assertEquals(listOf("спасибо" to "за"), h.editor.committed)
    }

    @Test
    fun aFullNextWordBandNeverAsksTheOtherLanguage() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.word = ""
        h.editor.contextWord = "мин"
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("бит", "дә", "инде"), LookupKind.NEXT_WORD)

        assertEquals(listOf("бит", "дә", "инде"), h.strip.lastCells())
        assertEquals(emptyList<String>(), h.engine(russian).nextWordRequests)
    }

    // --- Цена на UI-потоке ----------------------------------------------------------------------

    /**
     * Everything the rule adds to the UI thread itself, measured: dispatching the second lookup and
     * merging its answer into the band. The lookup's own compute happens on the other engine's
     * worker thread and is measured separately ([LanguagePriorityCostTest]); what is left here is
     * the only work that can ever sit between a keystroke and a frame.
     *
     * The budget is deliberately loose — this is a few string comparisons and one strip call, so a
     * whole millisecond is already three orders of magnitude of headroom over what it should cost.
     * It exists to catch a future change that puts real work on this path, not to police jitter.
     */
    @Test
    fun theUiThreadWorkTheRuleAddsPerKeystrokeIsUnderAMillisecond() {
        val h = Harness()
        h.warmBoth(PersonalSubtypes.TATAR_RU)
        val current = listOf("бераз")
        val other = listOf("берег", "берёза", "беречь")
        repeat(2_000) {
            h.editor.word = "бер"
            h.controller.onTextChanged()
            h.deliver(PersonalSubtypes.TATAR_RU, current)
            h.deliver(PersonalSubtypes.RUSSIAN, other)
        }

        val timings = LongArray(5_000)
        for (sample in timings.indices) {
            h.editor.word = "бер"
            h.controller.onTextChanged()
            h.deliver(PersonalSubtypes.TATAR_RU, current)
            val started = System.nanoTime()
            h.deliver(PersonalSubtypes.RUSSIAN, other)
            timings[sample] = System.nanoTime() - started
        }
        timings.sort()
        val median = timings[timings.size / 2] / 1_000_000.0
        val p95 = timings[(timings.size * 95) / 100] / 1_000_000.0
        println(
            "lang-priority ui-thread merge median=" +
                "${"%.4f".format(java.util.Locale.ROOT, median)} ms " +
                "p95=${"%.4f".format(java.util.Locale.ROOT, p95)} ms",
        )
        assertTrue("p95=$p95 ms", p95 <= 1.0)
        assertEquals(listOf("бераз", "берег", "берёза"), h.strip.lastCells())
    }

    @Test
    fun aPrefixResultNeverFillsANextWordBandAndBackwards() {
        val h = Harness()
        h.warmBoth(tatar)
        h.editor.word = ""
        h.editor.contextWord = "спасибо"
        h.controller.onTextChanged()
        h.deliver(tatar, emptyList(), LookupKind.NEXT_WORD)
        // The other language answers the WRONG kind: it must not reach the band at all.
        h.deliver(russian, listOf("привет"), LookupKind.PREFIX)

        assertEquals(emptyList<String>(), h.strip.lastCells())
    }
}
