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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The dictionary follows the layout: typing on the Russian layout is answered by the Russian
 * dictionary, typing on the Tatar one by the Tatar dictionary, and switching between them costs
 * neither a blocked keystroke nor a second inflation.
 */
class SuggestionsControllerLanguageSwitchTest {

    private val tatar = PersonalSubtypes.TATAR_RU
    private val russian = PersonalSubtypes.RUSSIAN

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        val shown = mutableListOf<Triple<String, String?, String?>>()
        var hideCount = 0
        var reserveCount = 0
        var visible = false

        override fun showSuggestions(first: String, second: String?, third: String?) {
            shown.add(Triple(first, second, third))
            visible = true
        }

        override fun reserve() {
            reserveCount++
            visible = true
        }

        override fun hideSuggestions() {
            hideCount++
            visible = false
        }

        override fun setTapListener(listener: SuggestionTapListener) = Unit
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""
        override fun cachedWordBeforeCursor(): String = word
        override fun commitSuggestion(expectedPrefix: String, suggestion: String) = true
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
    }

    /** One engine per language; remembers which subtype every lookup was keyed by. */
    private class FakeEngine(val subtypeId: String) : EngineHandle {
        val requestedKeys = mutableListOf<String>()
        val requestedPrefixes = mutableListOf<String>()
        var finishCount = 0
        var destroyCount = 0

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            requestedKeys.add(subtypeId)
            requestedPrefixes.add(String(prefixUtf8, Charsets.UTF_8))
            return TOKEN
        }

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any? = null

        override fun isCurrent(token: Any): Boolean = token === TOKEN

        override fun finishInput() {
            finishCount++
        }

        override fun destroy(timeoutMs: Long): Boolean {
            destroyCount++
            return true
        }

        companion object {
            val TOKEN = Any()
        }
    }

    private class FakeCatalog : PublishedDictionaryCatalog {
        override fun acquireLatestForActivation(): DictionaryFileLease? = null
        override fun cleanupReleasedVersions() = Unit
    }

    /** Publishes on demand, so a test can hold one language's dictionary unprepared. */
    private class FakePreparation(val subtypeId: String, val publishNow: Boolean) :
        DictionaryPreparation {
        var prepareCalls = 0
        private var pending: ((PreparationResult) -> Unit)? = null
        private val catalog = FakeCatalog()

        override fun prepare(onResult: (PreparationResult) -> Unit) {
            prepareCalls++
            if (publishNow) onResult(published()) else pending = onResult
        }

        fun publishPending() {
            val callback = pending ?: return
            pending = null
            callback(published())
        }

        override fun catalog(): PublishedDictionaryCatalog = catalog

        private fun published() = PreparationResult.Published(
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
        )
    }

    private class DirectExecutorService : AbstractExecutorService() {
        private var shutdown = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class Harness(russianPublishesNow: Boolean = true) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val executor = DirectExecutorService()
        val engines = LinkedHashMap<String, FakeEngine>()
        val preparations = LinkedHashMap<String, FakePreparation>()
        val engineFactoryCalls = mutableListOf<String>()
        val callbacks = LinkedHashMap<String, ResultCallback>()

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { subtypeId, callback ->
                engineFactoryCalls.add(subtypeId)
                callbacks[subtypeId] = callback
                engines.getOrPut(subtypeId) { FakeEngine(subtypeId) }
            },
            { executor },
            { _: ExecutorService, subtypeId: String ->
                preparations.getOrPut(subtypeId) {
                    FakePreparation(
                        subtypeId,
                        publishNow = subtypeId != PersonalSubtypes.RUSSIAN || russianPublishesNow,
                    )
                }
            },
            false,
            { _: ExecutorService, _: String -> null },
        )
    }

    // --- Tests ---------------------------------------------------------------------------------

    @Test
    fun bothShippedLanguagesResolveToTheirOwnArtifactAndNothingElseDoes() {
        assertSame(
            DictionaryArtifactSpec.TATAR_TOP100K_V1,
            DictionaryArtifactSpec.forSubtype(tatar),
        )
        assertSame(
            DictionaryArtifactSpec.RUSSIAN_TOP100K_V1,
            DictionaryArtifactSpec.forSubtype(russian),
        )
        assertNull(DictionaryArtifactSpec.forSubtype("en_US"))
        // Separate families and separate directories: this is what keeps an update from 1.6.1 from
        // touching the Tatar file the device already inflated.
        val specs = DictionaryArtifactSpec.ALL
        assertEquals(specs.size, specs.map { it.family }.distinct().size)
        assertEquals(specs.size, specs.map { it.storageDirectoryName }.distinct().size)
        assertEquals("dictionaries", DictionaryArtifactSpec.TATAR_TOP100K_V1.storageDirectoryName)
        assertTrue(
            DictionaryArtifactSpec.TATAR_TOP100K_V1.finalFileName.startsWith("tatar_top100k-v000001-"),
        )
        assertTrue(
            DictionaryArtifactSpec.RUSSIAN_TOP100K_V1.finalFileName.startsWith("russian_top100k-v000001-"),
        )
        // Neither family's final-name pattern may ever match the other's file.
        assertTrue(
            DictionaryArtifactSpec.TATAR_TOP100K_V1.finalFilePattern
                .matches(DictionaryArtifactSpec.TATAR_TOP100K_V1.finalFileName),
        )
        assertTrue(
            !DictionaryArtifactSpec.TATAR_TOP100K_V1.finalFilePattern
                .matches(DictionaryArtifactSpec.RUSSIAN_TOP100K_V1.finalFileName),
        )
    }

    @Test
    fun switchingLayoutSwitchesWhichDictionaryAnswers() {
        val h = Harness()
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        assertEquals(listOf(tatar), h.engineFactoryCalls)
        assertEquals(listOf("сүз"), h.engines.getValue(tatar).requestedPrefixes)

        h.editor.word = "сло"
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)

        // A second engine, over the Russian dictionary, keyed by the Russian subtype.
        assertEquals(listOf(tatar, russian), h.engineFactoryCalls)
        assertEquals(listOf("сло"), h.engines.getValue(russian).requestedPrefixes)
        assertEquals(listOf(russian), h.engines.getValue(russian).requestedKeys)
        // The Tatar engine was asked nothing more and was idled, not destroyed.
        assertEquals(listOf("сүз"), h.engines.getValue(tatar).requestedPrefixes)
        assertEquals(0, h.engines.getValue(tatar).destroyCount)
        assertTrue(h.engines.getValue(tatar).finishCount > 0)
    }

    @Test
    fun switchingBackReusesTheWarmEngineAndPreparesNothingTwice() {
        val h = Harness()
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)
        h.editor.word = "сүзл"
        h.controller.onSubtypeChanged(eligible = true, subtypeId = tatar)

        // No third engine and no second preparation of either language.
        assertEquals(listOf(tatar, russian), h.engineFactoryCalls)
        assertEquals(1, h.preparations.getValue(tatar).prepareCalls)
        assertEquals(1, h.preparations.getValue(russian).prepareCalls)
        // The warm Tatar engine answered at once — no keystroke was needed to wake it.
        assertEquals(listOf("сүз", "сүзл"), h.engines.getValue(tatar).requestedPrefixes)
        assertTrue(h.strip.visible)
    }

    @Test
    fun aLayoutWithNoDictionaryShowsNothingAndPreparesNothing() {
        val h = Harness()
        h.editor.word = "word"
        // LatinIME reports ineligible for such a subtype; the controller closes eligibility itself
        // as well, so a caller that got the boolean wrong still cannot expose a band.
        h.controller.onStartInput(eligible = true, subtypeId = "en_US")

        assertTrue(h.engineFactoryCalls.isEmpty())
        assertTrue(h.preparations.isEmpty())
        assertEquals(0, h.strip.reserveCount)
        assertTrue(h.strip.hideCount > 0)
    }

    @Test
    fun aResultFromTheLanguageJustLeftNeverRepaintsTheBand() {
        val h = Harness()
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        val tatarCallback = h.controller.let { _ -> h.callbacks.getValue(tatar) }

        h.editor.word = "сло"
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)
        val shownBefore = h.strip.shown.size

        // The Tatar worker finishes the lookup it had already started and reports it. The band the
        // user is looking at belongs to another language now, so it must not change by one cell.
        tatarCallback.onResult(FakeEngine.TOKEN, listOf("сүзләр", "сүзен"), LookupKind.PREFIX)

        assertEquals(shownBefore, h.strip.shown.size)

        // The Russian result for the very same band does paint.
        h.callbacks.getValue(russian)
            .onResult(FakeEngine.TOKEN, listOf("слово", "словом"), LookupKind.PREFIX)
        assertEquals(shownBefore + 1, h.strip.shown.size)
        assertEquals("слово", h.strip.shown.last().first)
    }

    @Test
    fun theSecondLanguageStillPreparingLeavesTheBandHiddenAndThenOpensIt() {
        val h = Harness(russianPublishesNow = false)
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.editor.word = "сло"
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)

        // Inflation has not finished: no engine, and the frozen state table requires GONE.
        assertEquals(listOf(tatar), h.engineFactoryCalls)
        assertTrue(!h.strip.visible)

        h.preparations.getValue(russian).publishPending()

        assertEquals(listOf(tatar, russian), h.engineFactoryCalls)
        assertTrue(h.strip.visible)
        assertEquals(listOf("сло"), h.engines.getValue(russian).requestedPrefixes)
    }

    @Test
    fun turningTheSettingOffReleasesEveryLanguageAtTheNextBoundary() {
        // The whole feature off = the master AND the glide toggle (P7-6): the release machinery
        // is pinned for that combination.
        val h = Harness()
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar, glideEligible = false)
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian, glideEligible = false)

        h.controller.onSuggestionsSettingDisabled()
        // Deferred: the keystroke that flipped the setting must not pay for two teardowns.
        assertEquals(0, h.engines.getValue(tatar).destroyCount)
        assertEquals(0, h.engines.getValue(russian).destroyCount)

        h.controller.onFinishInput()

        assertEquals(1, h.engines.getValue(tatar).destroyCount)
        assertEquals(1, h.engines.getValue(russian).destroyCount)
    }

    @Test
    fun turningTheMasterOffKeepsTheEngineWarmForGlide() {
        // P7-6: glide is independent — with the glide gate open, the master OFF transition must
        // not schedule the engine teardown; the band dies alone.
        val h = Harness()
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)

        h.controller.onSuggestionsSettingDisabled()
        h.controller.onFinishInput()

        assertEquals("the engine survives a master-off while glide is on",
            0, h.engines.getValue(tatar).destroyCount)
    }

    @Test
    fun eachLanguageKeysItsOwnLookups() {
        val h = Harness()
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)
        h.editor.word = "слово"
        h.controller.onTextChanged()

        assertEquals(listOf(tatar), h.engines.getValue(tatar).requestedKeys)
        assertEquals(listOf(russian, russian), h.engines.getValue(russian).requestedKeys)
    }

    @Test
    fun aWarmEngineOfTheOtherLanguageIsNeverAskedAnythingWhileItIsNotActive() {
        val h = Harness()
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.editor.word = "сло"
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)

        val tatarRequestsAtSwitch = h.engines.getValue(tatar).requestedPrefixes.size
        h.editor.word = "слов"
        h.controller.onTextChanged()
        h.editor.word = "слово"
        h.controller.onTextChanged()

        assertEquals(tatarRequestsAtSwitch, h.engines.getValue(tatar).requestedPrefixes.size)
        assertEquals(
            listOf("сло", "слов", "слово"),
            h.engines.getValue(russian).requestedPrefixes,
        )
    }
}
