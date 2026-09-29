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
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.EngineExecutor
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LatestOnlyPrefixEngine
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupToken
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ResultHandoff
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The band must never be left describing nothing while the cursor sits at the end of a word the
 * dictionary answers.
 *
 * The failure this pins was reported from the operator's hands as three snapshots: «др» showed
 * `друг · другой · друга`, «дру» showed an EMPTY band with the cell dividers still drawn, and
 * «друг» showed `другой · друга · других` again. The dictionary is not at fault — the shipped
 * Russian artifact answers all three prefixes, which the first test below asserts against the real
 * asset. What went wrong is the state: a cursor move (an external one, or the keyboard's own space
 * slide / delete swipe) routes into [SuggestionsController.onSelectionChanged], which unbinds the
 * band, blanks it and invalidates the in-flight generation — and used to stop there. Nothing looked
 * anything up again, so the band stayed blank until the next keystroke although the trailing word
 * had not changed at all.
 *
 * [SuggestionsController.onCursorMoveSettled] is the other half, and this is its behaviour:
 * re-derive the band once the cursor has settled, and stay out of the way otherwise. The wiring
 * that calls it from every cursor-move path is pinned by [CursorMoveBandSourceContractTest].
 */
class CursorMoveBandTest {

    // --- fakes ----------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        var cells: List<String> = emptyList()
        var reserveCount = 0
        override fun showSuggestions(first: String, second: String?, third: String?) {
            cells = listOfNotNull(first, second, third).filter(String::isNotEmpty)
        }
        override fun reserve() { cells = emptyList(); reserveCount++ }
        override fun hideSuggestions() { cells = emptyList() }
        override fun setTapListener(listener: SuggestionTapListener) = Unit
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""
        var letterAfterCursor = false
        var knownCursor = true
        override fun cachedWordBeforeCursor(): String = word
        override fun commitSuggestion(expectedPrefix: String, suggestion: String) = true
        override fun hasKnownCursor(): Boolean = knownCursor
        override fun hasLetterAfterCursor(): Boolean = letterAfterCursor
        override fun cachedNextWordContext(): String = ""
    }

    private class DirectEngineExecutor : EngineExecutor {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private class RealHandle(index: TdictPrefixIndex, callback: ResultCallback) : EngineHandle {
        private val engine = LatestOnlyPrefixEngine(
            index.identity, index, DirectEngineExecutor(),
            ResultHandoff { callback.onResult(it.token, it.suggestions, it.kind) },
        )
        var requests = 0
            private set
        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            requests++
            return engine.request(editorSessionId, subtypeId, prefixUtf8)
        }
        override fun isCurrent(token: Any): Boolean =
            token is LookupToken && engine.isCurrent(token)
        override fun finishInput() = engine.finishInput()
        override fun updateKeyNeighbors(table: KeyNeighborTable?) = engine.updateKeyNeighbors(table)
        override fun destroy(timeoutMs: Long): Boolean =
            engine.destroy(timeoutMs, TimeUnit.MILLISECONDS)
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

    private class DirectExecutorService : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    /**
     * The controller wired to the REAL shipped Russian dictionary, with the poster modelling
     * `Handler.post`: a posted runnable runs after the call that posted it returns, never inside it.
     * That ordering is not cosmetic — it is what lets `requestCompanionFill` record its outstanding
     * request before the answer to it can arrive.
     */
    private class Harness {
        val strip = FakeStrip()
        val editor = FakeEditor()
        private val posted = ArrayDeque<Runnable>()
        val handles = LinkedHashMap<String, RealHandle>()
        val controller: SuggestionsController

        init {
            val executor = DirectExecutorService()
            controller = SuggestionsController(
                strip, editor, UiPoster { posted.addLast(it) },
                { subtypeId, callback ->
                    RealHandle(
                        if (subtypeId == PersonalSubtypes.RUSSIAN) requireNotNull(russianIndex)
                        else requireNotNull(tatarIndex),
                        callback,
                    ).also { handles[subtypeId] = it }
                },
                { executor },
                { _: ExecutorService, _: String -> FakePreparation() },
                false,
                { _: ExecutorService, _: String -> null },
            )
            controller.onStartInput(true, PersonalSubtypes.TATAR_RU)
            drain()
            controller.onSubtypeChanged(true, PersonalSubtypes.RUSSIAN)
            drain()
        }

        fun drain() {
            while (true) {
                val next = posted.removeFirstOrNull() ?: return
                next.run()
            }
        }

        /** One keystroke: the trailing word becomes [word]. */
        fun type(word: String): List<String> {
            editor.word = word
            controller.onTextChanged()
            drain()
            return strip.cells
        }

        /**
         * A cursor move that leaves the trailing word exactly as it was, routed exactly as
         * `LatinIME` routes one: invalidate now, re-derive when the move has settled.
         */
        fun moveCursorWithoutChangingTheWord(): List<String> {
            controller.onSelectionChanged()
            drain()
            controller.onCursorMoveSettled()
            drain()
            return strip.cells
        }

        fun requestsMadeFor(subtypeId: String): Int = handles.getValue(subtypeId).requests
    }

    // --- the three snapshots --------------------------------------------------------------------

    @Test
    fun theThreeReportedPrefixesAllAnswerFromTheShippedRussianDictionary() {
        val h = Harness()
        assertEquals(listOf("друг", "другой", "друга"), h.type("др"))
        assertEquals(listOf("друг", "другой", "друга"), h.type("дру"))
        assertEquals(listOf("другой", "друга", "других"), h.type("друг"))
    }

    // --- the defect ------------------------------------------------------------------------------

    @Test
    fun aCursorMoveThatLeavesTheWordAloneGetsTheBandBack() {
        val h = Harness()
        assertEquals(listOf("друг", "другой", "друга"), h.type("дру"))
        // The band is blanked the moment the move is seen: what it was painting is no longer bound
        // to the live editor state, and that half is unchanged.
        h.controller.onSelectionChanged()
        h.drain()
        assertEquals(emptyList<String>(), h.strip.cells)
        // ...and it comes back once the move has settled, because the word under the cursor is the
        // same word the dictionary answered a moment ago.
        h.controller.onCursorMoveSettled()
        h.drain()
        assertEquals(listOf("друг", "другой", "друга"), h.strip.cells)
    }

    @Test
    fun everyOneOfTheThreeReportedPrefixesSurvivesACursorMove() {
        for (prefix in listOf("др", "дру", "друг")) {
            val h = Harness()
            val typed = h.type(prefix)
            assertTrue("precondition: $prefix answers at all", typed.isNotEmpty())
            assertEquals("$prefix must survive a cursor move", typed, h.moveCursorWithoutChangingTheWord())
        }
    }

    // --- and stays out of the way ----------------------------------------------------------------

    @Test
    fun aSettledCursorMoveCostsNothingWhileTheBandIsAlreadyBound() {
        val h = Harness()
        h.type("дру")
        val before = h.requestsMadeFor(PersonalSubtypes.RUSSIAN)
        h.controller.onCursorMoveSettled()
        h.drain()
        assertEquals(
            "a band already describing the live text must not be looked up again",
            before,
            h.requestsMadeFor(PersonalSubtypes.RUSSIAN),
        )
        assertEquals(listOf("друг", "другой", "друга"), h.strip.cells)
    }

    @Test
    fun aSettledCursorMoveDoesNotSecondGuessTheCursorInsideAWord() {
        val h = Harness()
        h.type("дру")
        h.editor.letterAfterCursor = true
        assertEquals(
            "a cursor inside a word has no candidates by contract",
            emptyList<String>(),
            h.moveCursorWithoutChangingTheWord(),
        )
    }

    @Test
    fun aSettledCursorMoveWithAnUnknownCursorLeavesTheBandAlone() {
        val h = Harness()
        h.type("дру")
        h.editor.knownCursor = false
        assertEquals(emptyList<String>(), h.moveCursorWithoutChangingTheWord())
    }

    @Test
    fun aSettledCursorMoveIsANoOpAfterTheFieldIsFinished() {
        val h = Harness()
        h.type("дру")
        h.controller.onFinishInput()
        h.drain()
        val before = h.requestsMadeFor(PersonalSubtypes.RUSSIAN)
        h.controller.onCursorMoveSettled()
        h.drain()
        assertEquals(before, h.requestsMadeFor(PersonalSubtypes.RUSSIAN))
        assertEquals(emptyList<String>(), h.strip.cells)
    }

    companion object {
        private var russianIndex: TdictPrefixIndex? = null
        private var tatarIndex: TdictPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedDictionaries() {
            russianIndex = open(
                DictionaryArtifactSpec.RUSSIAN_TOP100K_V1,
                "russian_top100k_v1.tdict.zlib",
            )
            tatarIndex = open(
                DictionaryArtifactSpec.TATAR_TOP100K_V1,
                "tatar_top100k_v1.tdict.zlib",
            )
        }

        private fun open(spec: DictionaryArtifactSpec, assetName: String): TdictPrefixIndex {
            val asset = locate(
                "src/main/assets/dictionaries/$assetName",
                "app/src/main/assets/dictionaries/$assetName",
            )
            val rawFile = File.createTempFile("cursor-move-band-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                return requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(rawFile.readBytes()),
                        DictionaryIdentity(
                            spec.generation, validated.schemaId, validated.formatVersion,
                            validated.rawSha256,
                        ),
                        validated.entryCount,
                        validated.rawSize,
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate the committed dictionary")
    }
}
