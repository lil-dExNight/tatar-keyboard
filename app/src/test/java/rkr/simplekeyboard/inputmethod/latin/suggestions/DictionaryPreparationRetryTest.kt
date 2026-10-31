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
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageFailure

/**
 * A failed dictionary preparation is retried once, at the next eligible input start; a second
 * failure leaves the language without suggestions until the setting goes OFF -> ON.
 */
class DictionaryPreparationRetryTest {

    @Test
    fun aFailedPreparationIsRetriedOnceAtTheNextInputStart() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.preparation.completeUnavailable()
        h.controller.onFinishInput()

        h.controller.onStartInput(eligible = true)
        assertEquals("the next field retries", 2, h.preparation.prepareCalls)

        h.preparation.completeUnavailable()
        h.controller.onFinishInput()
        h.controller.onStartInput(eligible = true)
        h.controller.onFinishInput()
        h.controller.onStartInput(eligible = true)
        assertEquals("a second failure is terminal", 2, h.preparation.prepareCalls)
    }

    @Test
    fun aSuccessfulRetryStartsTheEngine() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.preparation.completeUnavailable()
        h.controller.onFinishInput()
        assertEquals(0, h.engineStarts)

        h.controller.onStartInput(eligible = true)
        h.preparation.completePublished()

        assertEquals("the published dictionary starts an engine", 1, h.engineStarts)
    }

    @Test
    fun anIneligibleFieldDoesNotSpendTheRetry() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.preparation.completeUnavailable()
        h.controller.onFinishInput()

        h.controller.onStartInput(eligible = false, glideEligible = false)
        h.controller.onFinishInput()
        assertEquals(1, h.preparation.prepareCalls)

        h.controller.onStartInput(eligible = true)
        assertEquals("the retry waits for a field that wants suggestions", 2, h.preparation.prepareCalls)
    }

    @Test
    fun aRequestStillInFlightIsNotDuplicated() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.controller.onFinishInput()
        h.controller.onStartInput(eligible = true)

        assertEquals(1, h.preparation.prepareCalls)
    }

    @Test
    fun theRetryIsSilentAndTheSettingToggleRestoresIt() {
        val h = Harness()
        var answered = 0
        h.controller.dictionaryUnavailableListener = DictionaryUnavailableListener { answered++ }
        h.controller.onSuggestionsSettingEnabled(eligible = true)
        h.preparation.completeUnavailable()
        assertEquals("the explicit enable is answered", 1, answered)

        h.controller.onFinishInput()
        h.controller.onStartInput(eligible = true)
        h.preparation.completeUnavailable()
        assertEquals("the retry nobody asked for stays silent", 1, answered)
        h.controller.onFinishInput()
        h.controller.onStartInput(eligible = true)
        assertEquals(2, h.preparation.prepareCalls)

        h.controller.onSuggestionsSettingDisabled()
        h.controller.onSuggestionsSettingEnabled(eligible = true)
        assertEquals("the toggle allows a fresh attempt", 3, h.preparation.prepareCalls)
        h.preparation.completeUnavailable()
        h.controller.onFinishInput()
        h.controller.onStartInput(eligible = true)
        assertEquals("and restores the retry", 4, h.preparation.prepareCalls)
    }

    // --- Fakes -----------------------------------------------------------------------------------

    private class Harness {
        val preparation = FakePreparation()
        var engineStarts = 0
        val controller = SuggestionsController(
            FakeStrip(),
            FakeEditor(),
            UiPoster { it.run() },
            { _: String, _: ResultCallback ->
                engineStarts++
                null
            },
            { DirectExecutorService() },
            { _: ExecutorService, _: String -> preparation },
            false,
            { _: ExecutorService, _: String -> null },
        )
    }

    /** Holds each result until the test releases it. */
    private class FakePreparation : DictionaryPreparation {
        var prepareCalls = 0
        private var awaiting: ((PreparationResult) -> Unit)? = null

        override fun prepare(onResult: (PreparationResult) -> Unit) {
            prepareCalls++
            awaiting = onResult
        }

        fun completeUnavailable() = complete(PreparationResult.Unavailable(StorageFailure.IO))

        fun completePublished() = complete(
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
                alreadyPresent = false,
            ),
        )

        private fun complete(result: PreparationResult) {
            val callback = awaiting ?: error("no preparation is outstanding")
            awaiting = null
            callback(result)
        }

        override fun catalog(): PublishedDictionaryCatalog = FakeCatalog()
    }

    private class FakeCatalog : PublishedDictionaryCatalog {
        override fun acquireLatestForActivation(): DictionaryFileLease? = null
        override fun cleanupReleasedVersions() = Unit
    }

    private class FakeStrip : StripSurface {
        override fun showSuggestions(first: String, second: String?, third: String?, fourth: String?) = Unit
        override fun reserve() = Unit
        override fun hideSuggestions() = Unit
        override fun setTapListener(listener: SuggestionTapListener) = Unit
    }

    private class FakeEditor : EditorSurface {
        override fun cachedWordBeforeCursor(): String = ""
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = false
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
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
}
