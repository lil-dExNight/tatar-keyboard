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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.E3aTestFixtures.rawKey
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The companion exact-lead rule: when the active language's answer holds no exact candidate and
 * the companion's exact pass is non-empty, the companion's exact candidates lead the strip and
 * the active typo-recovery candidates fill the cells they leave. Outside that one case the
 * companion only ever appends, and it is never asked while the active language fills the strip.
 *
 * The harness runs two fake engines, one per language; the warm companion is produced by
 * switching to it and back, exactly the way [SuggestionsControllerLanguageSwitchTest] does.
 */
class CompanionExactLeadTest {

    private val tatar = PersonalSubtypes.TATAR_RU
    private val russian = PersonalSubtypes.RUSSIAN

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        val bands = mutableListOf<List<String?>>()

        override fun showSuggestions(first: String, second: String?, third: String?, fourth: String?) {
            bands.add(listOf(first, second, third, fourth))
        }

        override fun reserve() = Unit

        override fun hideSuggestions() = Unit

        override fun setTapListener(listener: SuggestionTapListener) = Unit

        fun lastBand(): List<String?>? = bands.lastOrNull()
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""
        override fun cachedWordBeforeCursor(): String = word
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = false
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
    }

    /** Records its lookups and delivers answers when the test says so. */
    private class FakeEngine(val callback: ResultCallback) : EngineHandle {
        val prefixRequests = mutableListOf<String>()
        var lastToken: Any? = null

        /** The exact-miss verdict the newest lookup would have left; null means exact hits. */
        var exactMiss: String? = null

        /** The exact-candidate count the newest lookup would have left. */
        var exactCount: Int = 0
        val neighborTables = mutableListOf<KeyNeighborTable?>()

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

        /** The worker handoff of the newest request's answer. */
        fun answer(suggestions: List<String>) {
            callback.onResult(requireNotNull(lastToken), suggestions, LookupKind.PREFIX)
        }

        override fun isCurrent(token: Any): Boolean = token === lastToken

        override fun finishInput() = Unit

        override fun updateKeyNeighbors(table: KeyNeighborTable?) {
            neighborTables += table
        }

        override fun exactMissPrefix(): String? = exactMiss

        override fun lastExactCount(): Int = exactCount

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

    private inner class Harness {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engines = LinkedHashMap<String, FakeEngine>()

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
            editor.word = word
            controller.onTextChanged()
        }
    }

    // --- The lead rule --------------------------------------------------------------------------

    @Test
    fun companionExactCandidatesLeadWhenTheActiveExactPassIsEmpty() {
        val h = Harness()
        h.startWithWarmCompanion()

        h.type("приве")
        // The active (Tatar) answer is all typo recovery: the exact pass found nothing.
        h.tatarEngine().exactMiss = "приве"
        h.tatarEngine().exactCount = 0
        h.tatarEngine().answer(listOf("привеле", "привеләр", "привелебез"))
        assertEquals(listOf("привеле", "привеләр", "привелебез", null), h.strip.lastBand())
        // The full strip did not stop the companion request: the exact-lead rule armed it.
        assertEquals(listOf("приве"), h.russianEngine().prefixRequests)

        h.russianEngine().exactCount = 1
        h.russianEngine().answer(listOf("привет", "приветик"))

        // The companion's exact candidate leads; the active typo recovery fills the rest, and
        // its third word stays off the three-cell strip.
        assertEquals(listOf("привет", "привеле", "привеләр", null), h.strip.lastBand())
    }

    @Test
    fun theLeadGroupHoldsEveryCompanionExactCandidate() {
        val h = Harness()
        h.startWithWarmCompanion()

        h.type("приве")
        h.tatarEngine().exactMiss = "приве"
        h.tatarEngine().answer(listOf("привеле"))

        h.russianEngine().exactCount = 2
        h.russianEngine().answer(listOf("привет", "приветик"))

        assertEquals(listOf("привет", "приветик", "привеле", null), h.strip.lastBand())
    }

    @Test
    fun anActiveExactPassIsNeverDisplaced() {
        val h = Harness()
        h.startWithWarmCompanion()

        h.type("кита")
        // One exact candidate: the exact pass is non-empty, so the rule does not engage.
        h.tatarEngine().exactMiss = null
        h.tatarEngine().exactCount = 1
        h.tatarEngine().answer(listOf("китап"))

        // The free cells still ask the companion, but unarmed: its answer appends.
        assertEquals(listOf("кита"), h.russianEngine().prefixRequests)
        h.russianEngine().exactCount = 2
        h.russianEngine().answer(listOf("китай", "китамичә"))

        assertEquals(listOf("китап", "китай", "китамичә", null), h.strip.lastBand())
    }

    @Test
    fun anEmptyCompanionExactPassLeavesTheActiveLeadInPlace() {
        val h = Harness()
        h.startWithWarmCompanion()

        h.type("приве")
        h.tatarEngine().exactMiss = "приве"
        h.tatarEngine().answer(listOf("привеле"))

        // The companion answer is non-empty but holds no exact candidate either: plain fill.
        h.russianEngine().exactCount = 0
        h.russianEngine().answer(listOf("привет", "приветик"))

        assertEquals(listOf("привеле", "привет", "приветик", null), h.strip.lastBand())
    }

    @Test
    fun aWordBothEnginesOfferLeadsOnceInItsCasedForm() {
        val h = Harness()
        h.startWithWarmCompanion()

        h.type("Приве")
        h.tatarEngine().exactMiss = "приве"
        h.tatarEngine().answer(listOf("привеле"))

        h.russianEngine().exactCount = 2
        h.russianEngine().answer(listOf("привеле", "привет"))

        // "привеле" shows once, cased like the typed prefix; the companion exact pass leads.
        assertEquals(listOf("Привеле", "Привет", null, null), h.strip.lastBand())
    }

    @Test
    fun aFullActiveAnswerWithExactCandidatesNeverAsksTheCompanion() {
        val h = Harness()
        h.startWithWarmCompanion()

        h.type("китап")
        h.tatarEngine().exactMiss = null
        h.tatarEngine().answer(listOf("китап", "китаплар", "китабы", "китапханә"))

        // The engine answered four; the default strip shows the first three and is full.
        assertEquals(listOf("китап", "китаплар", "китабы", null), h.strip.lastBand())
        assertTrue(h.russianEngine().prefixRequests.isEmpty())
    }

    @Test
    fun anEmptyActiveAnswerStillLetsTheCompanionLeadFromTheFirstCell() {
        val h = Harness()
        h.startWithWarmCompanion()

        h.type("приве")
        h.tatarEngine().exactMiss = "приве"
        h.tatarEngine().exactCount = 0
        h.tatarEngine().answer(emptyList())

        h.russianEngine().exactCount = 1
        h.russianEngine().answer(listOf("привет"))

        assertEquals(listOf("привет", null, null, null), h.strip.lastBand())
    }

    // --- The live layout's neighbor table --------------------------------------------------------

    @Test
    fun theLiveLayoutTableReachesTheWarmCompanion() {
        val h = Harness()
        val tatarTable = KeyNeighborTable.build("tt", true, listOf(rawKey('а'), rawKey('ә')))
        val russianTable = KeyNeighborTable.build("ru", true, listOf(rawKey('а'), rawKey('ы')))

        // The production order: the new layout's table is published before the switch.
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.controller.updateKeyNeighbors(tatarTable)
        h.controller.updateKeyNeighbors(russianTable)
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)
        h.controller.updateKeyNeighbors(tatarTable)
        h.controller.onSubtypeChanged(eligible = true, subtypeId = tatar)

        // The user types on the Tatar layout now: the warm Russian engine's typo recovery must
        // substitute against that layout, not against the one it last owned.
        assertSame(tatarTable, h.tatarEngine().neighborTables.last())
        assertSame(tatarTable, h.russianEngine().neighborTables.last())
    }
}
