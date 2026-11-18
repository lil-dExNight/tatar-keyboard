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
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectAdvice
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.CompanionLexicon
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ValidatedCompanionList
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
 * The P0 performance/memory behavior of the controller:
 *  - with autocorrect OFF the cold-companion lists are never loaded and the engines are told to
 *    skip the verdict computation; the lists load when the setting turns on, not before;
 *  - a setting CHANGE resets the verdict of every lookup that ran before it, so a stale verdict
 *    can neither paint a preview nor fire at a separator; a fresh lookup re-arms it;
 *  - typo recovery and companion fills are untouched by all of this;
 *  - the session's first prefix lookup finishes before the full glide-index prewarm, with the
 *    immediate-prewarm fallbacks (glide-only session, armed gesture, rejected request).
 */
class PerformanceGatingTest {

    private val tatar = PersonalSubtypes.TATAR_RU
    private val russian = PersonalSubtypes.RUSSIAN

    // --- Fakes (shaped like SuggestionsControllerLanguagePriorityTest's, plus the perf hooks) ---

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
        val replaced = mutableListOf<Pair<String, String>>()

        override fun cachedWordBeforeCursor(): String = word
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = true
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
        override fun cachedNextWordContext(): String = contextWord
        override fun replaceTypedWord(expectedPrefix: String, replacement: String): Boolean {
            replaced.add(expectedPrefix to replacement)
            return true
        }

        override fun commitPredictedWord(
            expectedPreviousContextWord: String,
            expectedContextWord: String,
            suggestion: String,
        ): Boolean =
            true
    }

    private class FakeEngine(val subtypeId: String) : EngineHandle {
        val prefixRequests = mutableListOf<String>()
        var advice: AutocorrectAdvice? = null
        var adviceReads = 0
        val adviceEnabledPushes = mutableListOf<Boolean>()
        var prewarmCount = 0
        var releaseCount = 0
        var rejectRequests = false
        var receivedLexicon: CompanionLexicon? = null
        private var current: Any? = null

        override fun containsWord(normalizedWord: String): Boolean = false

        override fun setCompanionLexicon(lexicon: CompanionLexicon?) {
            receivedLexicon = lexicon
        }

        override fun setAutocorrectAdviceEnabled(enabled: Boolean) {
            adviceEnabledPushes.add(enabled)
        }

        override fun prewarmGlideIndex() {
            prewarmCount++
        }

        override fun releaseGlideIndex() {
            releaseCount++
        }

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            prefixRequests.add(String(prefixUtf8, Charsets.UTF_8))
            if (rejectRequests) return null
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

    private class Harness(initialAutocorrectOn: Boolean) {
        var autocorrectOn = initialAutocorrectOn
        val strip = FakeStrip()
        val editor = FakeEditor()
        val executor = DirectExecutorService()
        val engines = LinkedHashMap<String, FakeEngine>()
        val callbacks = LinkedHashMap<String, ResultCallback>()
        var coldFactoryCalls = 0
        val pendingColdDeliveries = HashMap<String, ArrayDeque<(ValidatedCompanionList?) -> Unit>>()

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
            coldCompanionPreparationFactory = { _, subtypeId ->
                coldFactoryCalls++
                val captured = ArrayDeque<(ValidatedCompanionList?) -> Unit>()
                pendingColdDeliveries[subtypeId] = captured
                ColdCompanionPreparation { onResult -> captured.addLast(onResult) }
            },
        ).also { controller ->
            controller.setAutocorrectGate { autocorrectOn }
        }

        fun engine(subtypeId: String): FakeEngine = engines.getValue(subtypeId)

        fun deliverColdList(subtypeId: String, list: ValidatedCompanionList?) {
            pendingColdDeliveries[subtypeId]?.removeFirstOrNull()?.invoke(list)
        }

        fun deliver(
            subtypeId: String,
            suggestions: List<String>,
            kind: LookupKind = LookupKind.PREFIX,
        ) {
            callbacks.getValue(subtypeId)
                .onResult(engine(subtypeId).currentToken(), suggestions, kind)
        }

        fun typeAndDeliver(subtypeId: String, word: String, suggestions: List<String>) {
            editor.word = word
            controller.onTextChanged()
            deliver(subtypeId, suggestions)
        }
    }

    // --- Autocorrect off: no companion-list cost, no advice computation --------------------------

    @Test
    fun coldCompanionListsLoadOnlyOnceAutocorrectIsOn() {
        val h = Harness(initialAutocorrectOn = false)
        h.controller.onStartInput(eligible = true, subtypeId = tatar)

        // Published engine, but the setting is off: no list load, and the engine was told to
        // skip the verdict computation from its very first lookup.
        assertEquals(0, h.coldFactoryCalls)
        assertEquals(false, h.engine(tatar).adviceEnabledPushes.last())

        h.typeAndDeliver(tatar, "бераз", listOf("бераз"))
        assertEquals("still off: no load", 0, h.coldFactoryCalls)

        // The setting turns on: the next observation point starts the load of every OTHER
        // language's list and re-enables the engine-side advice.
        h.autocorrectOn = true
        h.typeAndDeliver(tatar, "беразг", listOf("беразг"))
        assertEquals(1, h.coldFactoryCalls)
        assertTrue(h.pendingColdDeliveries.containsKey(russian))
        assertEquals(true, h.engine(tatar).adviceEnabledPushes.last())

        h.deliverColdList(russian, ValidatedCompanionList(listOf("мама")))
        assertTrue(h.engine(tatar).receivedLexicon!!.containsWord("мама"))

        // Once loaded, no second per-process load is requested by later keystrokes.
        h.typeAndDeliver(tatar, "беразге", listOf("беразге"))
        assertEquals(1, h.coldFactoryCalls)
    }

    @Test
    fun typoRecoveryAndCompanionFillContinueWithAutocorrectOff() {
        val h = Harness(initialAutocorrectOn = false)
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)
        h.controller.onSubtypeChanged(eligible = true, subtypeId = tatar)

        h.typeAndDeliver(tatar, "бераз", listOf("бераз", "берәү"))
        // Two active cells out of four: the companion is still asked, still without any list.
        assertEquals(listOf("бераз"), h.engine(russian).prefixRequests)
        h.deliver(russian, listOf("берег"))

        assertEquals(listOf("бераз", "берәү", "берег"), h.strip.lastCells())
        assertEquals(0, h.coldFactoryCalls)
        assertTrue(h.engines.values.all { engine ->
            engine.adviceEnabledPushes.all { !it }
        })
    }

    // --- Verdict reset on setting changes -------------------------------------------------------

    private fun armedCorrection(h: Harness) {
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.typeAndDeliver(tatar, "китәп", listOf("китап"))
        h.engine(tatar).advice = AutocorrectAdvice(
            "китәп", "китап", 1_000_000, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
        )
    }

    @Test
    fun anUnchangedSettingKeepsTheVerdict() {
        val h = Harness(initialAutocorrectOn = true)
        armedCorrection(h)

        assertTrue(h.controller.maybeAutocorrectBeforeSeparator(' '.code))
        assertEquals(listOf("китәп" to "китап"), h.editor.replaced)
    }

    @Test
    fun aSettingChangeResetsTheVerdictUntilAFreshLookup() {
        val h = Harness(initialAutocorrectOn = true)
        armedCorrection(h)
        val readsBefore = h.engine(tatar).adviceReads

        h.autocorrectOn = false
        assertFalse(h.controller.maybeAutocorrectBeforeSeparator(' '.code))
        h.autocorrectOn = true
        // The lookup that computed this verdict ran BEFORE the change: it must not fire now,
        // and it is not even read again.
        assertFalse(h.controller.maybeAutocorrectBeforeSeparator(' '.code))
        assertTrue(h.editor.replaced.isEmpty())
        assertEquals(readsBefore, h.engine(tatar).adviceReads)

        // A fresh lookup (a different word first, so the request really re-issues) recomputes
        // the verdict and re-arms the correction.
        h.typeAndDeliver(tatar, "башка", listOf("башка"))
        h.typeAndDeliver(tatar, "китәп", listOf("китап"))
        assertTrue(h.controller.maybeAutocorrectBeforeSeparator(' '.code))
        assertEquals(listOf("китәп" to "китап"), h.editor.replaced)
    }

    // --- Prewarm ordering ------------------------------------------------------------------------

    @Test
    fun glideOnlySessionPrewarmsImmediatelyAtTrackingDown() {
        val h = Harness(initialAutocorrectOn = false)
        h.controller.setGlideGate(GlideGate { true })
        h.controller.onStartInput(eligible = false, subtypeId = tatar, glideEligible = true)

        // No prefix lookup exists in a glide-only session, so there is nothing to wait for.
        h.controller.onGlideTrackingStarted()
        assertEquals(1, h.engine(tatar).prewarmCount)
    }

    @Test
    fun anArmedGlideForcesTheDeferredPrewarmAndTheFirstResultDoesNotRepeatIt() {
        val h = Harness(initialAutocorrectOn = false)
        h.controller.setGlideGate(GlideGate { true })
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.controller.onGlideTrackingStarted()
        assertEquals("deferred behind the first lookup", 0, h.engine(tatar).prewarmCount)

        h.controller.onGlideArmed()
        assertEquals("a decode is imminent: forced", 1, h.engine(tatar).prewarmCount)

        h.typeAndDeliver(tatar, "сүз", listOf("сүзгә"))
        assertEquals("the pending build was already forced", 1, h.engine(tatar).prewarmCount)
    }

    @Test
    fun aRejectedFirstLookupReleasesTheDeferredPrewarm() {
        val h = Harness(initialAutocorrectOn = false)
        h.controller.setGlideGate(GlideGate { true })
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.controller.onGlideTrackingStarted()
        assertEquals(0, h.engine(tatar).prewarmCount)

        // No result will arrive for this request, so the build cannot wait for one.
        h.engine(tatar).rejectRequests = true
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        assertEquals(1, h.engine(tatar).prewarmCount)
    }
}
