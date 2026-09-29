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

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageFailure

/**
 * Mission `tt-personal-dict`, finding A1 of `docs/SILENT-AUDIT.md`: the provenance of a preparation
 * request.
 *
 * [DictionaryUnavailableListener] exists for exactly one situation — the user turned the setting on
 * themselves and it could not be honoured — because, in the words of the comment on it, "leaving
 * that unanswered would look like the setting simply did nothing". The provenance was recorded on a
 * first-writer-wins field AFTER the de-duplication guard, so an explicit enable arriving while an
 * implicit preparation was still in flight was dropped, and that is precisely the case where the
 * user is standing in the settings screen watching the switch.
 */
class DictionaryUnavailableProvenanceTest {

    @Test
    fun turningTheSettingOnWhileAPreparationIsInFlightStillAnswersTheUser() {
        val harness = Harness()
        var answered = 0
        harness.controller.dictionaryUnavailableListener =
            DictionaryUnavailableListener { answered++ }

        // 1. The field opens with the setting already on: the controller becomes eligible and asks
        //    for preparation ITSELF. Unpacking and validating the artifact takes real time, so the
        //    result is still outstanding.
        harness.controller.onStartInput(eligible = true)
        assertEquals("the implicit request went out", 1, harness.preparation.prepareCalls)
        assertTrue("and it has not come back yet", harness.preparation.isPending)

        // 2. No band appears, so the user goes to the settings and flips the switch OFF -> ON. The
        //    de-duplication guard is right to send no second request; the provenance is not its to
        //    throw away.
        harness.controller.onSuggestionsSettingEnabled(eligible = true)
        assertEquals("no second preparation is queued", 1, harness.preparation.prepareCalls)

        // 3. The preparation ends badly.
        harness.preparation.completeUnavailable()

        assertEquals(
            "the switch the user just flipped must not look like it did nothing",
            1, answered,
        )
    }

    /**
     * The upgrade goes one way only. A field opening (implicit) while an explicit request is in
     * flight must not turn the outstanding request into an anonymous one, or the message would be
     * lost by the opposite ordering of the same two events.
     */
    @Test
    fun anImplicitRequestDoesNotDowngradeAnExplicitOneAlreadyInFlight() {
        val harness = Harness()
        var answered = 0
        harness.controller.dictionaryUnavailableListener =
            DictionaryUnavailableListener { answered++ }

        harness.controller.onSuggestionsSettingEnabled(eligible = true)
        assertEquals(1, harness.preparation.prepareCalls)
        harness.controller.onStartInput(eligible = true)
        assertEquals(1, harness.preparation.prepareCalls)

        harness.preparation.completeUnavailable()

        assertEquals("the explicit enable is still the reason the request exists", 1, answered)
    }

    /** And a purely implicit request is still answered with silence, which is the whole point. */
    @Test
    fun aPreparationNobodyAskedForStaysSilentWhenItFails() {
        val harness = Harness()
        var answered = 0
        harness.controller.dictionaryUnavailableListener =
            DictionaryUnavailableListener { answered++ }

        harness.controller.onStartInput(eligible = true)
        harness.preparation.completeUnavailable()

        assertEquals("nothing the user did went unanswered, so nothing is said", 0, answered)
    }

    // --- Fakes -----------------------------------------------------------------------------------

    private class Harness {
        val preparation = FakePreparation()
        val controller = SuggestionsController(
            FakeStrip(),
            FakeEditor(),
            UiPoster { it.run() },
            { _: String, _: ResultCallback -> null },
            { DirectExecutorService() },
            { _: ExecutorService, _: String -> preparation },
            false,
            { _: ExecutorService, _: String -> null },
        )
    }

    /** Holds the result until the test releases it: that window IS the finding. */
    private class FakePreparation : DictionaryPreparation {
        var prepareCalls = 0
        private var awaiting: ((PreparationResult) -> Unit)? = null

        val isPending: Boolean get() = awaiting != null

        override fun prepare(onResult: (PreparationResult) -> Unit) {
            prepareCalls++
            awaiting = onResult
        }

        fun completeUnavailable() {
            val callback = awaiting ?: error("no preparation is outstanding")
            awaiting = null
            callback(PreparationResult.Unavailable(StorageFailure.IO))
        }

        override fun catalog(): PublishedDictionaryCatalog = FakeCatalog()
    }

    private class FakeCatalog : PublishedDictionaryCatalog {
        override fun acquireLatestForActivation(): DictionaryFileLease? = null
        override fun cleanupReleasedVersions() = Unit
    }

    private class FakeStrip : StripSurface {
        override fun showSuggestions(first: String, second: String?, third: String?) = Unit
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
