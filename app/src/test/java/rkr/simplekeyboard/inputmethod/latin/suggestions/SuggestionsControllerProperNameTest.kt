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
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog

/**
 * Reviewed proper-name display in the strip: a typed prefix keeps its typed casing, with the
 * approved display form supplying the internal capitals of a compound name; next-word prediction
 * shows that display form regardless of the context's casing; a personal raw form wins over the
 * table; and a table that arrives after the strip was painted repaints it.
 */
class SuggestionsControllerProperNameTest {

    private class FakeStrip : StripSurface {
        val shown = mutableListOf<List<String?>>()
        override fun showSuggestions(first: String, second: String?, third: String?, fourth: String?) {
            shown.add(listOf(first, second, third, fourth))
        }
        override fun reserve() = Unit
        override fun hideSuggestions() = Unit
        override fun setTapListener(listener: SuggestionTapListener) = Unit
        fun lastCells(): List<String> = shown.lastOrNull()?.filterNotNull() ?: emptyList()
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""
        var contextWord: String = ""
        override fun cachedWordBeforeCursor(): String = word
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = true
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
        override fun cachedNextWordContext(): String = contextWord
        override fun isAtSentenceStart(): Boolean = contextWord.isEmpty() && word.isEmpty()
        override fun commitPredictedWord(
            expectedPreviousContextWord: String,
            expectedContextWord: String,
            suggestion: String,
        ): Boolean = true
    }

    private class FakeEngine : EngineHandle {
        private var current: Any? = null
        var prefixRequests = 0
        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            prefixRequests++
            return Any().also { current = it }
        }
        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            previousContextWordUtf8: ByteArray,
            contextWordUtf8: ByteArray,
        ): Any? = Any().also { current = it }
        override fun isCurrent(token: Any): Boolean = token === current
        override fun finishInput() { current = null }
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
                    PublishedDictionary(1, File("/dev/null"), 72, 1, 1, 1, "0".repeat(64)),
                    alreadyPresent = true,
                ),
            )
        }
        override fun catalog(): PublishedDictionaryCatalog = catalog
    }

    private class FakeProperNamePreparation(
        var index: ProperNameIndex?,
    ) : ProperNamePreparation {
        var deferred = false
        private var pending: ((ProperNameIndex?) -> Unit)? = null
        override fun prepare(onResult: (ProperNameIndex?) -> Unit) {
            if (deferred) pending = onResult else onResult(index)
        }
        fun fire() {
            requireNotNull(pending).invoke(index)
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

    private class Harness(index: ProperNameIndex? = INDEX, delayed: Boolean = false) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val executor = DirectExecutorService()
        val engine = FakeEngine()
        var callback: ResultCallback? = null
        val preparation = FakeProperNamePreparation(index).also { it.deferred = delayed }
        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { _, result -> callback = result; engine },
            { executor },
            { _: ExecutorService, _: String -> FakePreparation() },
            false,
            { _: ExecutorService, _: String -> null },
            { _: ExecutorService -> null },
            { _: ExecutorService, _: String -> null },
            { _: ExecutorService, _: String -> null },
            properNamePreparationFactory = { _: ExecutorService, _: String -> preparation },
        )

        fun start() {
            controller.onStartInput(eligible = true, subtypeId = PersonalSubtypes.TATAR_RU)
        }

        fun deliver(suggestions: List<String>, kind: LookupKind) {
            requireNotNull(callback).onResult(engine.currentToken(), suggestions, kind)
        }
    }

    private fun prefixCandidates(candidates: List<String>, typed: String): List<String> {
        val h = Harness()
        h.start()
        h.editor.word = typed
        h.controller.onTextChanged()
        h.deliver(candidates, LookupKind.PREFIX)
        return h.strip.lastCells()
    }

    @Test
    fun initialCapsUsesTheDisplayFormInternalCapitals() {
        assertEquals(
            listOf("Санкт-Петербург", "Салам"),
            prefixCandidates(listOf("санкт-петербург", "салам"), "Сан"),
        )
    }

    @Test
    fun allCapsUppercasesTheDisplayForm() {
        assertEquals(
            listOf("САНКТ-ПЕТЕРБУРГ"),
            prefixCandidates(listOf("санкт-петербург"), "САН"),
        )
    }

    @Test
    fun aLowercaseTypedPrefixKeepsTheDictionaryKey() {
        assertEquals(
            listOf("санкт-петербург"),
            prefixCandidates(listOf("санкт-петербург"), "сан"),
        )
    }

    @Test
    fun nextWordShowsTheDisplayFormForALowercaseKey() {
        val h = Harness()
        h.start()
        h.editor.word = ""
        h.editor.contextWord = "шәһәр"
        h.controller.onTextChanged()
        h.deliver(listOf("санкт-петербург", "татарстан", "салам"), LookupKind.NEXT_WORD)
        assertEquals(
            listOf("Санкт-Петербург", "Татарстан", "салам"),
            h.strip.lastCells(),
        )
    }

    @Test
    fun aPersonalRawFormIsNeverOverridden() {
        val h = Harness()
        h.start()
        h.editor.word = "Сан"
        h.controller.onTextChanged()
        h.deliver(listOf("Санкт-петербург"), LookupKind.PREFIX)
        assertEquals(listOf("Санкт-петербург"), h.strip.lastCells())
    }

    @Test
    fun anEmptyTableLeavesTheOldCasing() {
        val h = Harness(ProperNameIndex.EMPTY)
        h.start()
        h.editor.word = "Сан"
        h.controller.onTextChanged()
        h.deliver(listOf("санкт-петербург"), LookupKind.PREFIX)
        assertEquals(listOf("Санкт-петербург"), h.strip.lastCells())
    }

    @Test
    fun aLateTableRepaintsTheBoundPrefix() {
        val h = Harness(INDEX, delayed = true)
        h.start()
        h.editor.word = "Сан"
        h.controller.onTextChanged()
        h.deliver(listOf("санкт-петербург"), LookupKind.PREFIX)
        assertEquals(listOf("Санкт-петербург"), h.strip.lastCells())

        val before = h.engine.prefixRequests
        h.preparation.fire()
        // The late table itself must re-request the bound prefix; no delivery follows yet.
        assertEquals(before + 1, h.engine.prefixRequests)
        h.deliver(listOf("санкт-петербург"), LookupKind.PREFIX)
        assertEquals(listOf("Санкт-Петербург"), h.strip.lastCells())
    }

    private companion object {
        val INDEX = ProperNameIndex.parse(
            "санкт-петербург\tСанкт-Петербург\nтатарстан\tТатарстан\n"
        )
    }
}
