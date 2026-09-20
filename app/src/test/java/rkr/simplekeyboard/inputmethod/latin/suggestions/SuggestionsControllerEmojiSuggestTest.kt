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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSuggestPreparation
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSuggestSource
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The emoji cell of the NEXT_WORD band (mission 2 of `docs/EMOJI-SUGGEST-PLAN.md`): it fills a
 * tail cell the word sources left free, never displaces a bigram or a companion word, taps commit
 * through the E5d predicted-word path, and every failure direction — the toggle off, a private
 * field, an unreadable asset, a word without a mapping — is silent.
 */
class SuggestionsControllerEmojiSuggestTest {

    private val tatar = PersonalSubtypes.TATAR_RU
    private val russian = PersonalSubtypes.RUSSIAN

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        val shown = mutableListOf<List<String?>>()
        var labels = listOf<String?>(null, null, null)
        var reserveCount = 0
        var hideCount = 0
        var tap: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?) {
            shown.add(listOf(first, second, third))
        }

        override fun setSpokenCellLabels(first: String?, second: String?, third: String?) {
            labels = listOf(first, second, third)
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
        var letterAfterCursor: Boolean = false
        var knownCursor: Boolean = true
        val predictedCommits = mutableListOf<Pair<String, String>>()

        override fun cachedWordBeforeCursor(): String = word
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = false
        override fun hasKnownCursor(): Boolean = knownCursor
        override fun hasLetterAfterCursor(): Boolean = letterAfterCursor
        override fun cachedNextWordContext(): String = contextWord
        override fun commitPredictedWord(expectedContextWord: String, suggestion: String): Boolean {
            predictedCommits.add(expectedContextWord to suggestion)
            // Model the synchronous cache update the production commit performs (see
            // SuggestionsControllerTest.FakeEditor): the inserted text plus its auto-space leave
            // an empty trailing word, and the committed word becomes the NEXT_WORD context — or no
            // context at all when the cell held no word (an emoji commits no letters).
            word = ""
            contextWord = if (suggestion.any { Character.isLetter(it) }) suggestion else ""
            return true
        }
    }

    /** One engine per language; every request gets a FRESH token, like the real engine's. */
    private class FakeEngine : EngineHandle {
        val nextWordRequests = mutableListOf<String>()
        val prefixRequests = mutableListOf<String>()
        var rejectRequests = false
        private var current: Any? = null

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            prefixRequests.add(String(prefixUtf8, Charsets.UTF_8))
            if (rejectRequests) return null
            return Any().also { current = it }
        }

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any? {
            nextWordRequests.add(String(contextWordUtf8, Charsets.UTF_8))
            if (rejectRequests) return null
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

    private class FakeEmojiSource(
        private val table: Map<Pair<String, String>, String>,
        private val names: Map<String, String> = emptyMap(),
    ) : EmojiSuggestSource {
        var lookups = 0
        override fun emojiFor(language: String, normalizedWord: String): String? {
            lookups++
            return table[language to normalizedWord]
        }

        override fun spokenNameOf(emoji: String): String? = names[emoji]
    }

    /** Immediate by default; [deferred] mode captures the callback for the test to fire by hand. */
    private class FakeEmojiPreparation(var source: EmojiSuggestSource?) : EmojiSuggestPreparation {
        var prepareCalls = 0
        var deferred = false
        private var pending: ((EmojiSuggestSource?) -> Unit)? = null

        override fun prepare(onResult: (EmojiSuggestSource?) -> Unit) {
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

    private class Harness(source: EmojiSuggestSource?) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val executor = DirectExecutorService()
        val engines = LinkedHashMap<String, FakeEngine>()
        val callbacks = LinkedHashMap<String, ResultCallback>()
        val emojiPreparation = FakeEmojiPreparation(source)
        var emojiGateOn = true

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
            { _: ExecutorService -> emojiPreparation },
        )

        init {
            controller.setEmojiSuggestGate { emojiGateOn }
        }

        fun engine(subtypeId: String): FakeEngine = engines.getValue(subtypeId)

        fun deliver(
            subtypeId: String,
            suggestions: List<String>,
            kind: LookupKind = LookupKind.NEXT_WORD,
        ) {
            callbacks.getValue(subtypeId)
                .onResult(engine(subtypeId).currentToken(), suggestions, kind)
        }

        /** Brings up the active engine and, when [companion] is set, the other language's too. */
        fun start(active: String, companion: String? = null) {
            controller.onStartInput(eligible = true, subtypeId = active)
            if (companion != null) {
                controller.onSubtypeChanged(eligible = true, subtypeId = companion)
                controller.onSubtypeChanged(eligible = true, subtypeId = active)
            }
            strip.shown.clear()
        }

        /** The user finished [word] with a space: a NEXT_WORD request goes to the engine. */
        fun typeWordAndSpace(word: String) {
            editor.word = ""
            editor.contextWord = word
            controller.onTextChanged()
        }
    }

    private fun harnessWithMapping(
        language: String,
        word: String,
        emoji: String,
        name: String? = null,
    ): Harness {
        val source = FakeEmojiSource(
            mapOf(language to word to emoji),
            if (name == null) emptyMap() else mapOf(emoji to name),
        )
        return Harness(source)
    }

    // --- The fill rule -------------------------------------------------------------------------

    @Test
    fun theEmojiFillsTheTailCellAfterTheBigrams() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, listOf("минем", "тибә"))

        assertEquals(listOf("минем", "тибә", "❤️"), h.strip.lastCells())
    }

    @Test
    fun theEmojiTakesTheTailCellAndTheFrontBigramsKeepTheirOrder() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, listOf("минем", "тибә", "китте"))

        // The band is full of bigrams: the emoji still takes the tail cell, and the two front
        // bigrams stand exactly where they were. Only bigram #3 yields.
        assertEquals(listOf("минем", "тибә", "❤️"), h.strip.lastCells())
    }

    @Test
    fun theEmojiTailSurvivesAFallbackFilledBand() {
        // TT-NEXTWORD-FILL: the fallback fills the NEXT_WORD cells the bigrams and forms leave
        // free, so a mapped context word can now arrive with a FULL three-word band — the emoji
        // still takes the tail cell and the two front words keep their order.
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, listOf("йөрәкләр", "һәм", "белән"))

        assertEquals(listOf("йөрәкләр", "һәм", "❤️"), h.strip.lastCells())
    }

    @Test
    fun theEmojiOpensTheBandWhenNoBigramAnswers() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, emptyList())

        assertEquals(listOf("❤️"), h.strip.lastCells())
    }

    @Test
    fun aWordWithoutAMappingLeavesTheBandExactlyAsBefore() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.typeWordAndSpace("кит") // denylisted: no mapping
        h.deliver(tatar, listOf("минем"))

        assertEquals(listOf("минем"), h.strip.lastCells())
    }

    @Test
    fun aMappingOfTheOtherLanguageDoesNotApply() {
        val h = harnessWithMapping("ru", "самолет", "✈️")
        h.start(tatar) // the active language is Tatar; the word is only mapped for Russian
        h.typeWordAndSpace("самолет")
        h.deliver(tatar, emptyList())

        assertEquals(emptyList<String>(), h.strip.lastCells())
    }

    @Test
    fun theCapitalizedWordHitsTheSameMapping() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.typeWordAndSpace("Йөрәк")
        h.deliver(tatar, emptyList())

        assertEquals(listOf("❤️"), h.strip.lastCells())
    }

    @Test
    fun thePrefixBandNeverShowsAnEmoji() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.editor.word = "йөрә" // still typing: PREFIX mode, not a finished word
        h.editor.contextWord = ""
        h.controller.onTextChanged()
        h.deliver(tatar, listOf("йөрәк"), LookupKind.PREFIX)

        assertEquals(listOf("йөрәк"), h.strip.lastCells())
    }

    // --- The toggle ----------------------------------------------------------------------------

    @Test
    fun theToggleOffMeansNoEmojiAndNoLoadAtAll() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.emojiGateOn = false
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, emptyList())

        assertEquals(emptyList<String>(), h.strip.lastCells())
        assertEquals(0, h.emojiPreparation.prepareCalls)
    }

    @Test
    fun theToggleIsReadLiveBetweenTwoWords() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.emojiGateOn = false
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, emptyList())
        assertEquals(emptyList<String>(), h.strip.lastCells())

        h.emojiGateOn = true
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, emptyList())

        assertEquals(listOf("❤️"), h.strip.lastCells())
        assertEquals(1, h.emojiPreparation.prepareCalls)
    }

    // --- Loading -------------------------------------------------------------------------------

    @Test
    fun theTableIsLoadedAtMostOncePerProcess() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, emptyList())
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, emptyList())

        assertEquals(1, h.emojiPreparation.prepareCalls)
    }

    @Test
    fun aBandPaintedBeforeTheLoadFinishedGetsItsCellWhenTheTableArrives() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.emojiPreparation.deferred = true
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, emptyList())
        // The load is still in flight: the band is exactly what it would be without the feature.
        assertEquals(emptyList<String>(), h.strip.lastCells())

        h.emojiPreparation.fire()

        assertEquals(listOf("❤️"), h.strip.lastCells())
    }

    @Test
    fun aFailedLoadIsSilentAndNeverRetried() {
        val h = Harness(null)
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, listOf("минем"))
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, listOf("минем"))

        assertEquals(listOf("минем"), h.strip.lastCells())
        assertEquals(1, h.emojiPreparation.prepareCalls)
    }

    @Test
    fun aLoadThatFinishedAfterTheUserTypedOnRefillsNothing() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.emojiPreparation.deferred = true
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, emptyList())

        // The user is typing the next word already when the table arrives.
        h.editor.word = "а"
        h.editor.contextWord = ""
        h.controller.onTextChanged()
        h.emojiPreparation.fire()

        assertEquals(emptyList<String>(), h.strip.lastCells())
    }

    // --- Coexistence with the companion language ------------------------------------------------

    @Test
    fun theCompanionWordInsertsBeforeTheEmojiTail() {
        val h = harnessWithMapping("ru", "самолет", "✈️")
        h.start(russian, companion = tatar)
        h.typeWordAndSpace("самолет")
        h.deliver(russian, listOf("улетел"))
        // The emoji is pinned to the tail from the moment the active language answers.
        assertEquals(listOf("улетел", "✈️"), h.strip.lastCells())

        h.deliver(tatar, listOf("очты"))

        // The companion word fills the free middle cell; the emoji keeps the tail.
        assertEquals(listOf("улетел", "очты", "✈️"), h.strip.lastCells())
    }

    @Test
    fun aBandFullOfWordsAndEmojiNeverAsksTheCompanion() {
        val h = harnessWithMapping("ru", "самолет", "✈️")
        h.start(russian, companion = tatar)
        h.typeWordAndSpace("самолет")
        h.deliver(russian, listOf("улетел", "прилетел"))

        assertEquals(listOf("улетел", "прилетел", "✈️"), h.strip.lastCells())
        assertTrue(h.engine(tatar).nextWordRequests.isEmpty())
    }

    @Test
    fun theEmojiSurvivesAnEmptyCompanionAnswer() {
        val h = harnessWithMapping("ru", "самолет", "✈️")
        h.start(russian, companion = tatar)
        h.typeWordAndSpace("самолет")
        h.deliver(russian, listOf("улетел"))
        assertEquals(listOf("улетел", "✈️"), h.strip.lastCells())

        h.deliver(tatar, emptyList())

        assertEquals(listOf("улетел", "✈️"), h.strip.lastCells())
    }

    @Test
    fun companionWordsFillAnEmojiOnlyBandAheadOfTheEmoji() {
        val h = harnessWithMapping("ru", "самолет", "✈️")
        h.start(russian, companion = tatar)
        h.typeWordAndSpace("самолет")
        h.deliver(russian, emptyList())
        assertEquals(listOf("✈️"), h.strip.lastCells())

        h.deliver(tatar, listOf("очты", "китте"))

        assertEquals(listOf("очты", "китте", "✈️"), h.strip.lastCells())
    }

    @Test
    fun anUnmappedWordKeepsTheThirdBigramCell() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.typeWordAndSpace("башка") // no mapping: the band is exactly what it was pre-feature
        h.deliver(tatar, listOf("минем", "тибә", "китте"))

        assertEquals(listOf("минем", "тибә", "китте"), h.strip.lastCells())
    }

    // --- The tap --------------------------------------------------------------------------------

    @Test
    fun tappingTheEmojiCommitsItThroughThePredictedWordPath() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, emptyList())

        requireNotNull(h.strip.tap).onTap("❤️")

        assertEquals(listOf("йөрәк" to "❤️"), h.editor.predictedCommits)
    }

    @Test
    fun aStaleEmojiCellCommitsNothing() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, emptyList())
        assertEquals(listOf("❤️"), h.strip.lastCells())

        // The user typed on before tapping: the band is unbound, the tap must be a no-op.
        h.editor.word = "а"
        h.editor.contextWord = ""
        h.controller.onTextChanged()
        requireNotNull(h.strip.tap).onTap("❤️")

        assertTrue(h.editor.predictedCommits.isEmpty())
    }

    @Test
    fun theBandAfterAnAcceptedWordCellStillCarriesTheEmojiTail() {
        // TT-TYPO-NEXT Phase A: the follow-up lookup after a tap-commit goes through the ordinary
        // NEXT_WORD fill path, so the emoji tail rule applies to it unchanged.
        val h = harnessWithMapping("tt", "китте", "❤️")
        h.start(tatar)
        h.typeWordAndSpace("башка") // no mapping for the FIRST context: a plain one-word band
        h.deliver(tatar, listOf("китте"))
        assertEquals(listOf("китте"), h.strip.lastCells())

        requireNotNull(h.strip.tap).onTap("китте")

        // The accepted word became the context synchronously, so the follow-up request is for IT...
        assertEquals(listOf("башка", "китте"), h.engine(tatar).nextWordRequests)
        // ...and its band gets the emoji tail exactly like a band built after typed input.
        h.deliver(tatar, listOf("бирегә"))
        assertEquals(listOf("бирегә", "❤️"), h.strip.lastCells())
    }

    // --- Accessibility --------------------------------------------------------------------------

    @Test
    fun theEmojiCellGetsTheEmojiNameAsItsSpokenLabel() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️", name = "йөрәк")
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, listOf("минем"))

        assertEquals(listOf("минем", "❤️"), h.strip.lastCells())
        assertEquals(listOf(null, "йөрәк", null), h.strip.labels)
    }

    @Test
    fun aWordBandCarriesNoLabels() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.typeWordAndSpace("башка") // no mapping: no emoji, no labels
        h.deliver(tatar, listOf("минем", "тибә"))

        assertEquals(listOf(null, null, null), h.strip.labels)
    }

    // --- The gates ------------------------------------------------------------------------------

    @Test
    fun anIneligibleFieldNeverLoadsTheTable() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.controller.onStartInput(eligible = false, subtypeId = tatar)
        h.editor.contextWord = "йөрәк"
        h.controller.onTextChanged()

        assertEquals(0, h.emojiPreparation.prepareCalls)
        assertEquals(emptyList<String>(), h.strip.lastCells())
    }

    @Test
    fun aRejectedNextWordRequestKeepsTheBandQuiet() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.start(tatar)
        h.engine(tatar).rejectRequests = true
        h.typeWordAndSpace("йөрәк")
        // No result will ever arrive for a rejected request; the table is not even consulted.
        assertEquals(emptyList<String>(), h.strip.lastCells())
    }

    @Test
    fun aDestroyedControllerDoesNotFill() {
        val h = harnessWithMapping("tt", "йөрәк", "❤️")
        h.emojiPreparation.deferred = true
        h.start(tatar)
        h.typeWordAndSpace("йөрәк")
        h.deliver(tatar, emptyList())

        h.controller.onDestroy()
        h.emojiPreparation.fire()

        assertNull(h.strip.shown.lastOrNull()?.filterNotNull()?.firstOrNull())
    }
}
