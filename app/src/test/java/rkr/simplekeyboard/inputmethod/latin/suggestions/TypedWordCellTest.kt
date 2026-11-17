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
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectAdvice
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
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
 * The keep-typed cell of an ordinary completion strip: when the active language's exact pass for
 * the typed prefix is empty — a word the dictionary does not hold — the typed word itself leads
 * the strip, a tap commits exactly that form with the auto-space, and the companion and the typo
 * recovery fill the cells after it. A strip whose exact pass found candidates, and every special
 * strip, keeps its documented cells.
 */
class TypedWordCellTest {

    private val tatar = PersonalSubtypes.TATAR_RU
    private val russian = PersonalSubtypes.RUSSIAN

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        /** One painted band: the four cells plus the emphasized cell index (NO_CELL = plain). */
        class Band(val cells: List<String?>, val emphasized: Int)

        val bands = mutableListOf<Band>()
        var listener: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?, fourth: String?) {
            bands.add(Band(listOf(first, second, third, fourth), SuggestionStripState.NO_CELL))
        }

        override fun setEmphasizedCell(cell: Int) {
            if (bands.isNotEmpty()) bands[bands.lastIndex] = Band(bands.last().cells, cell)
        }

        override fun reserve() = Unit

        override fun hideSuggestions() = Unit

        override fun setTapListener(listener: SuggestionTapListener) {
            this.listener = listener
        }

        fun lastBand(): List<String?>? = bands.lastOrNull()?.cells

        fun lastEmphasis(): Int = bands.lastOrNull()?.emphasized ?: SuggestionStripState.NO_CELL
    }

    /** A text model with a collapsed cursor between [before] and [after]. */
    private class FakeEditor : EditorSurface {
        var before: String = ""
        var after: String = ""

        /** Every commit this surface actually performed, in order. */
        val edits = mutableListOf<String>()

        override fun cachedWordBeforeCursor(): String =
            TatarWordUtils.extractTrailingWord(before)

        override fun hasKnownCursor(): Boolean = true

        override fun hasLetterAfterCursor(): Boolean =
            TatarWordUtils.startsWithWordCharacter(after)

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            if (!canReplace(expectedPrefix)) return false
            val committed =
                if (TatarWordUtils.needsAutoSpace(after)) "$suggestion " else suggestion
            edits.add("commit:$expectedPrefix->$committed")
            before = before.dropLast(expectedPrefix.length) + committed
            return true
        }

        private fun canReplace(expectedPrefix: String): Boolean {
            if (TatarWordUtils.startsWithWordCharacter(after)) return false
            return cachedWordBeforeCursor() == expectedPrefix
        }
    }

    /** Records its lookups and delivers answers when the test says so. */
    private class FakeEngine(val callback: ResultCallback) : EngineHandle {
        val prefixRequests = mutableListOf<String>()
        val nextWordRequests = mutableListOf<String>()
        private var lastToken: Any? = null

        /** The exact-miss verdict the newest lookup would have left; null means exact hits. */
        var exactMiss: String? = null

        /** The exact-candidate count the newest lookup would have left. */
        var exactCount: Int = 0

        /** The verdict of the newest lookup; null means the strip shows no preview. */
        var advice: AutocorrectAdvice? = null

        override fun request(
            editorSessionId: Long,
            subtypeId: String,
            prefixUtf8: ByteArray,
        ): Any {
            prefixRequests += String(prefixUtf8, Charsets.UTF_8)
            val token = Any()
            lastToken = token
            return token
        }

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any {
            nextWordRequests += String(contextWordUtf8, Charsets.UTF_8)
            val token = Any()
            lastToken = token
            return token
        }

        /** The worker delivery of the newest prefix request's answer. */
        fun answer(suggestions: List<String>) {
            callback.onResult(requireNotNull(lastToken), suggestions, LookupKind.PREFIX)
        }

        override fun isCurrent(token: Any): Boolean = token === lastToken

        override fun finishInput() = Unit

        override fun updateKeyNeighbors(table: KeyNeighborTable?) = Unit

        override fun exactMissPrefix(): String? = exactMiss

        override fun lastExactCount(): Int = exactCount

        override fun autocorrectAdvice(): AutocorrectAdvice? = advice

        override fun destroy(timeoutMs: Long): Boolean = true
    }

    private class FakeCatalog : PublishedDictionaryCatalog {
        override fun acquireLatestForActivation(): DictionaryFileLease? = null
        override fun cleanupReleasedVersions() = Unit
    }

    /** Publishes inline, so the engine starts inside the call that asked for preparation. */
    private class FakePreparation : DictionaryPreparation {
        private val catalog = FakeCatalog()

        override fun prepare(onResult: (PreparationResult) -> Unit) = onResult(
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

        override fun catalog(): PublishedDictionaryCatalog = catalog
    }

    private class DirectExecutorService : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private inner class Harness(autocorrectOn: Boolean = false) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engines = LinkedHashMap<String, FakeEngine>()
        val completions = mutableListOf<String>()
        val acceptedSuggestions = mutableListOf<String>()

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { subtypeId, callback ->
                engines.getOrPut(subtypeId) { FakeEngine(callback) }
            },
            { DirectExecutorService() },
            { _: ExecutorService, _: String -> FakePreparation() },
            false,
            { _: ExecutorService, _: String -> null },
        )

        init {
            controller.setAutocorrectGate { autocorrectOn }
            controller.setCompletionSink(object : WordCompletionSink {
                override fun onCleanCompletion(word: String) {
                    completions.add(word)
                }

                override fun onAcceptedSuggestion(word: String) {
                    acceptedSuggestions.add(word)
                }
            })
        }

        fun start() {
            controller.onStartInput(eligible = true, subtypeId = tatar)
            strip.bands.clear()
        }

        /** Starts on the Tatar layout, warms the Russian engine, and comes back. */
        fun startWithWarmCompanion() {
            controller.onStartInput(eligible = true, subtypeId = tatar)
            controller.onSubtypeChanged(eligible = true, subtypeId = russian)
            controller.onSubtypeChanged(eligible = true, subtypeId = tatar)
            // The switches request the live (empty) prefix and paint nothing word-like.
            strip.bands.clear()
        }

        fun tatarEngine(): FakeEngine = engines.getValue(tatar)

        fun russianEngine(): FakeEngine = engines.getValue(russian)

        fun type(word: String) {
            editor.before = word
            controller.onTextChanged()
        }

        fun tap(suggestion: String) {
            strip.listener?.onTap(suggestion)
        }

        /** An OOV answer for [word]: the exact pass missed, [candidates] are typo recovery. */
        fun answerUnknown(word: String, candidates: List<String>) {
            val engine = tatarEngine()
            engine.exactMiss = TatarWordUtils.normalizeForLookup(word)
            engine.exactCount = 0
            engine.answer(candidates)
        }
    }

    // --- The keep-typed cell --------------------------------------------------------------------

    @Test
    fun anUnknownWordLeadsTheStripWithAutocorrectOff() {
        val h = Harness(autocorrectOn = false)
        h.start()

        h.type("күле")
        h.answerUnknown("күле", listOf("көле", "күлә"))

        assertEquals(listOf("күле", "көле", "күлә", null), h.strip.lastBand())
    }

    @Test
    fun paintingTheKeepTypedCellNeitherCommitsNorSaves() {
        val h = Harness()
        h.start()

        // Typed one letter at a time, so the run is clean and a completion would be reported at
        // the word boundary.
        "күле".forEach { h.type(h.editor.before + it) }
        h.answerUnknown("күле", listOf("көле"))

        assertEquals(listOf("күле", "көле", null, null), h.strip.lastBand())
        assertTrue("painting the cell performs no edit", h.editor.edits.isEmpty())
        assertTrue("painting the cell accepts nothing", h.acceptedSuggestions.isEmpty())
        assertTrue("painting the cell completes nothing", h.completions.isEmpty())
    }

    @Test
    fun anUnknownWordLeadsTheStripWithAutocorrectOn() {
        // The setting is on, but this answer carries no verdict, so the strip is the ordinary
        // completion band rather than a preview.
        val h = Harness(autocorrectOn = true)
        h.start()

        h.type("күле")
        h.answerUnknown("күле", listOf("көле"))

        assertEquals(listOf("күле", "көле", null, null), h.strip.lastBand())
        assertEquals(SuggestionStripState.NO_CELL, h.strip.lastEmphasis())
    }

    @Test
    fun aTapOnTheKeepTypedCellCommitsExactlyTheTypedForm() {
        val h = Harness()
        h.start()

        h.type("күле")
        h.answerUnknown("күле", listOf("көле"))
        h.tap("күле")

        assertEquals(listOf("commit:күле->күле "), h.editor.edits)
        assertEquals("күле ", h.editor.before)
    }

    @Test
    fun anEmptyActiveAnswerKeepsTheTypedWordTappable() {
        val h = Harness()
        h.start()

        h.type("күле")
        h.answerUnknown("күле", emptyList())

        assertEquals(listOf("күле", null, null, null), h.strip.lastBand())

        h.tap("күле")
        assertEquals(listOf("commit:күле->күле "), h.editor.edits)
    }

    @Test
    fun theTypedCasingIsShownVerbatimAndTheCandidatesFollowIt() {
        val h = Harness()
        h.start()

        h.type("Күле")
        h.answerUnknown("Күле", listOf("көле", "күлә"))

        assertEquals(listOf("Күле", "Көле", "Күлә", null), h.strip.lastBand())
    }

    @Test
    fun aTypedWordThatAnEngineWordRepeatsOccupiesOneCell() {
        val h = Harness()
        h.start()

        h.type("Күле")
        // The personal word the exact pass adds can carry the typed word's own spelling.
        h.answerUnknown("Күле", listOf("Күле", "Күлем"))

        assertEquals(listOf("Күле", "Күлем", null, null), h.strip.lastBand())
    }

    // --- The companion around the keep-typed cell ------------------------------------------------

    @Test
    fun companionExactCandidatesStartAfterTheKeepTypedCell() {
        val h = Harness()
        h.startWithWarmCompanion()

        h.type("күле")
        h.answerUnknown("күле", listOf("көле", "күлә"))
        assertEquals(listOf("күле", "көле", "күлә", null), h.strip.lastBand())
        assertEquals(listOf("күле"), h.russianEngine().prefixRequests)

        h.russianEngine().exactCount = 1
        h.russianEngine().answer(listOf("кул"))

        // The companion's exact candidate takes the cell after the keep-typed one; the active
        // typo recovery fills the rest.
        assertEquals(listOf("күле", "кул", "көле", null), h.strip.lastBand())
    }

    @Test
    fun companionFillSkipsAWordEqualToTheKeepTypedCell() {
        val h = Harness()
        h.startWithWarmCompanion()

        h.type("күле")
        h.answerUnknown("күле", listOf("көле"))

        // No exact candidate either: the fill appends in the companion's order and drops the
        // spelling the keep-typed cell already shows.
        h.russianEngine().exactCount = 0
        h.russianEngine().answer(listOf("күле", "кул"))

        assertEquals(listOf("күле", "көле", "кул", null), h.strip.lastBand())
    }

    @Test
    fun anExactActiveStripHasNoKeepTypedCell() {
        val h = Harness()
        h.startWithWarmCompanion()

        h.type("кита")
        // One exact candidate: the exact pass is not empty, so the typed word stays off the strip.
        h.tatarEngine().exactMiss = null
        h.tatarEngine().exactCount = 1
        h.tatarEngine().answer(listOf("китап"))

        assertEquals(listOf("китап", null, null, null), h.strip.lastBand())
    }

    @Test
    fun theAutocorrectPreviewStillOwnsTheStrip() {
        val h = Harness(autocorrectOn = true)
        h.startWithWarmCompanion()

        h.type("китәп")
        // The engine advises a correction, so the strip announces the coming replacement instead
        // of showing completions; the exact pass is empty, which alone would have armed the cell.
        h.tatarEngine().advice = AutocorrectAdvice(
            "китәп", "китап", 5_000L, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
        )
        h.answerUnknown("китәп", listOf("китәпле"))

        assertEquals(listOf("китәп", "китап", null, null), h.strip.lastBand())
        assertEquals(PREVIEW_EMPHASIZED_CELL, h.strip.lastEmphasis())
        assertTrue(
            "no companion fill on a preview strip",
            h.russianEngine().prefixRequests.isEmpty(),
        )
    }
}
