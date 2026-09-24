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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.CompositePrefixComputer
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.EngineTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LatestOnlyPrefixEngine
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupToken
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ManualEngineExecutor
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ResultHandoff
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class SuggestionsControllerTest {

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        val shown = mutableListOf<Triple<String, String?, String?>>()
        val visibilityEvents = mutableListOf<String>()
        var hideCount = 0
        var reserveCount = 0
        var visible = false
        var listener: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?) {
            shown.add(Triple(first, second, third))
            visible = true
            visibilityEvents.add("show")
        }

        override fun reserve() {
            reserveCount++
            visible = true
            visibilityEvents.add("reserve")
        }

        override fun hideSuggestions() {
            hideCount++
            visible = false
            visibilityEvents.add("hide")
        }

        override fun setTapListener(listener: SuggestionTapListener) {
            this.listener = listener
        }
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""
        var commitResult: Boolean = true
        var knownCursor: Boolean = true

        /** Raw text right after the cursor; classified by the production predicate. */
        var textAfterCursor: String = ""
        val commits = mutableListOf<Pair<String, String>>()

        // --- E5d NEXT_WORD ------------------------------------------------------------------
        var nextWordContext: String = ""
        var predictedCommitResult: Boolean = true
        val predictedCommits = mutableListOf<Pair<String, String>>()

        override fun cachedWordBeforeCursor(): String = word

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            commits.add(expectedPrefix to suggestion)
            if (!commitResult) return false
            applySuccessfulInsert(suggestion)
            return true
        }

        override fun hasKnownCursor(): Boolean = knownCursor

        override fun hasLetterAfterCursor(): Boolean =
            TatarWordUtils.startsWithWordCharacter(textAfterCursor)

        override fun cachedNextWordContext(): String = nextWordContext

        override fun commitPredictedWord(expectedContextWord: String, suggestion: String): Boolean {
            predictedCommits.add(expectedContextWord to suggestion)
            if (!predictedCommitResult) return false
            applySuccessfulInsert(suggestion)
            return true
        }

        /**
         * Models what a successful production commit leaves in the RichInputConnection text cache
         * SYNCHRONOUSLY, before the call returns (InputLogic.replaceTrailingWord /
         * commitPredictedWord edit the cache inside the same batch edit): the committed word takes
         * the cursor, and the auto-space rule decides the shape — with the space appended the
         * trailing word is empty and the committed word becomes the NEXT_WORD context; without it
         * (the text after the cursor already separates the word) the committed word IS the new
         * trailing word, exactly as if the user had typed it.
         */
        private fun applySuccessfulInsert(suggestion: String) {
            if (TatarWordUtils.needsAutoSpace(textAfterCursor)) {
                word = ""
                nextWordContext = suggestion
            } else {
                word = suggestion
                nextWordContext = ""
            }
        }
    }

    private class FakeEngine : EngineHandle {
        val requestedPrefixes = mutableListOf<ByteArray>()
        val requestedContexts = mutableListOf<ByteArray>()
        var nextToken: Any? = TOKEN
        var isCurrentResult: Boolean = true
        var finishCount = 0
        var destroyCount = 0
        var destroyResult: Boolean = true

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            requestedPrefixes.add(prefixUtf8)
            return nextToken
        }

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any? {
            requestedContexts.add(contextWordUtf8)
            return nextToken
        }

        override fun isCurrent(token: Any): Boolean = isCurrentResult

        override fun finishInput() {
            finishCount++
        }

        override fun destroy(timeoutMs: Long): Boolean {
            destroyCount++
            return destroyResult
        }

        companion object {
            val TOKEN = Any()
        }
    }

    /** Runs submitted work inline so engine start is deterministic in tests. */
    private class DirectExecutorService : AbstractExecutorService() {
        private var shutdown = false

        override fun execute(command: Runnable) = command.run()

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    /** Captures submitted work so engine start can be completed after onDestroy() deterministically. */
    private class QueuingExecutorService : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var shutdown = false

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        /** Runs the oldest queued task inline; tasks survive shutdownNow so a post-destroy start can complete. */
        fun runNext() = tasks.removeFirst().run()

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class Harness(dictionaryReady: Boolean = true) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        val executor = DirectExecutorService()
        var capturedCallback: ResultCallback? = null
        var factoryCalls = 0
        var factoryResult: EngineHandle? = engine

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { callback ->
                factoryCalls++
                capturedCallback = callback
                factoryResult
            },
            executor,
            dictionaryReady,
        )
    }

    // --- Eligibility gating --------------------------------------------------------------------

    @Test
    fun ineligibleStartHidesAndNeverStartsEngineOrRequests() {
        val h = Harness()

        h.controller.onStartInput(eligible = false)

        assertEquals(1, h.strip.hideCount)
        assertEquals(0, h.factoryCalls)

        // A subsequent text change must not reach the (never-started) engine.
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        assertTrue(h.engine.requestedPrefixes.isEmpty())
    }

    @Test
    fun eligibleStartStartsEngineAndWiresTapListener() {
        val h = Harness()

        h.controller.onStartInput(eligible = true)

        assertEquals(1, h.factoryCalls)
        assertNotNull(h.strip.listener)
        assertNotNull(h.capturedCallback)
    }

    @Test
    fun eligibleStartShowsBandOnlyAfterColdEnginePublishes() {
        val h = Harness()

        h.controller.onStartInput(eligible = true)

        // DirectExecutor publishes inline: the transition must still be GONE first, then reserve
        // only after the engine handle has published successfully. The second "reserve" is E5d's
        // widened re-request gate (publishEngine no longer skips requestCurrentPrefix() on an empty
        // prefix, because an empty prefix is exactly when NEXT_WORD needs to fire): with no context
        // word available either (the fake editor's default), requestNextWordContext() falls through
        // to clearToReservedBand(), an idempotent no-op re-assertion of the already-reserved band.
        assertEquals(listOf("hide", "reserve", "reserve"), h.strip.visibilityEvents)
        assertEquals(2, h.strip.reserveCount)
        assertEquals(1, h.strip.hideCount)
        assertTrue(h.strip.visible)
    }

    @Test
    fun secondStartDoesNotRestartEngine() {
        val h = Harness()

        h.controller.onStartInput(eligible = true)
        h.controller.onStartInput(eligible = true)

        assertEquals(1, h.factoryCalls)
    }

    // --- Request dispatch ----------------------------------------------------------------------

    @Test
    fun emptyWordReservesAndDoesNotRequest() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        val reserveBefore = h.strip.reserveCount
        val hideBefore = h.strip.hideCount

        h.editor.word = ""
        h.controller.onTextChanged()

        assertTrue(h.engine.requestedPrefixes.isEmpty())
        assertEquals(reserveBefore + 1, h.strip.reserveCount)
        assertEquals(hideBefore, h.strip.hideCount)
    }

    @Test
    fun nonEmptyWordRequestsNormalizedUtf8() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.editor.word = "СҮЗ"
        h.controller.onTextChanged()

        assertEquals(1, h.engine.requestedPrefixes.size)
        assertTrue(
            h.engine.requestedPrefixes.single()
                .contentEquals("сүз".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun nullTokenReservesStrip() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.engine.nextToken = null
        val reserveBefore = h.strip.reserveCount
        val hideBefore = h.strip.hideCount

        h.editor.word = "сүз"
        h.controller.onTextChanged()

        assertEquals(reserveBefore + 1, h.strip.reserveCount)
        assertEquals(hideBefore, h.strip.hideCount)
    }

    // --- Result application --------------------------------------------------------------------

    @Test
    fun currentResultIsShownTopThree() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"
        h.controller.onTextChanged()

        h.engine.isCurrentResult = true
        h.capturedCallback!!.onResult(
            FakeEngine.TOKEN,
            listOf("сүзләр", "сүзлек", "сүзсез", "сүзчән"),
            LookupKind.PREFIX,
        )

        assertEquals(1, h.strip.shown.size)
        assertEquals(Triple("сүзләр", "сүзлек", "сүзсез"), h.strip.shown.single())
    }

    @Test
    fun emptySuggestionListReserves() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        val reserveBefore = h.strip.reserveCount
        val hideBefore = h.strip.hideCount

        h.capturedCallback!!.onResult(FakeEngine.TOKEN, emptyList(), LookupKind.PREFIX)

        assertTrue(h.strip.shown.isEmpty())
        assertEquals(reserveBefore + 1, h.strip.reserveCount)
        assertEquals(hideBefore, h.strip.hideCount)
    }

    @Test
    fun staleResultDroppedWhenSelectionChangedBumpsSession() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"
        h.controller.onTextChanged()

        // Session bumps between request and result; engine.isCurrent still true, but the session
        // guard must drop the result anyway.
        h.controller.onSelectionChanged()
        h.engine.isCurrentResult = true
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр"), LookupKind.PREFIX)

        assertTrue(h.strip.shown.isEmpty())
    }

    @Test
    fun staleResultDroppedWhenFinishInputBumpsSession() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"
        h.controller.onTextChanged()

        h.controller.onFinishInput()
        h.engine.isCurrentResult = true
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр"), LookupKind.PREFIX)

        assertTrue(h.strip.shown.isEmpty())
    }

    @Test
    fun staleResultDroppedWhenEngineNotCurrent() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"
        h.controller.onTextChanged()

        h.engine.isCurrentResult = false
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр"), LookupKind.PREFIX)

        assertTrue(h.strip.shown.isEmpty())
    }

    // --- Tap routing ---------------------------------------------------------------------------

    @Test
    fun tapCommitsDisplayedPrefixAndReservesOnSuccess() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        // A tap only commits against a prefix that is actually DISPLAYED, so deliver a result first.
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр", "сүзлек"), LookupKind.PREFIX)
        val reserveBefore = h.strip.reserveCount
        val hideBefore = h.strip.hideCount

        h.editor.commitResult = true
        h.strip.listener!!.onTap("сүзләр")

        assertEquals(listOf("сүз" to "сүзләр"), h.editor.commits)
        assertEquals(reserveBefore + 1, h.strip.reserveCount)
        assertEquals(hideBefore, h.strip.hideCount)
    }

    @Test
    fun tapDoesNotReserveOrHideWhenCommitFails() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр", "сүзлек"), LookupKind.PREFIX)
        val reserveBefore = h.strip.reserveCount
        val hideBefore = h.strip.hideCount

        h.editor.commitResult = false
        h.strip.listener!!.onTap("сүзләр")

        assertEquals(listOf("сүз" to "сүзләр"), h.editor.commits)
        assertEquals(reserveBefore, h.strip.reserveCount)
        assertEquals(hideBefore, h.strip.hideCount)
    }

    @Test
    fun acceptedPrefixSuggestionIsFollowedByNextWordPredictionsForTheAcceptedWord() {
        // The E5 contract (docs/archive/PROPOSALS.md, "## E5"): after an ACCEPTED or typed word and
        // a space the strip shows the continuations of the word that was just committed — a tap is
        // no exception. This test pinned the pre-NEXT_WORD behavior as
        // bandStaysEmptyAndVisibleAfterTheAutoSpacedCommitEndsTheWord (D1 era); the empty band it
        // asserted was a defect, not a design (TT-TYPO-NEXT Phase A, amendment recorded in
        // docs/TT-TYPO-NEXT.md on 2026-09-20).
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр", "сүзлек"), LookupKind.PREFIX)
        val prefixesBefore = h.engine.requestedPrefixes.size
        val hideBefore = h.strip.hideCount

        h.editor.commitResult = true
        h.strip.listener!!.onTap("сүзләр")

        // The successful commit consumed the prefix and appended the auto-space, so the controller
        // immediately issues a NEXT_WORD request for the word it just committed — without waiting
        // for another keystroke — and never re-requests the consumed prefix.
        assertEquals(listOf("сүз" to "сүзләр"), h.editor.commits)
        assertEquals(
            listOf("сүзләр"),
            h.engine.requestedContexts.map { String(it, Charsets.UTF_8) },
        )
        assertEquals(prefixesBefore, h.engine.requestedPrefixes.size)
        // The band stays reserved and visible throughout; it never hides.
        assertEquals(hideBefore, h.strip.hideCount)
        assertTrue(h.strip.visible)
        // A repeat tap while the follow-up request is still in flight commits nothing: both
        // displayed* bindings were dropped and nothing new has been bound yet.
        h.strip.listener!!.onTap("сүзләр")
        assertEquals(listOf("сүз" to "сүзләр"), h.editor.commits)
        // The moment the answer arrives the strip shows the accepted word's successors.
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("дип", "дигән"), LookupKind.NEXT_WORD)
        assertEquals(Triple("дип", "дигән", null), h.strip.shown.last())
        assertTrue(h.strip.visible)
    }

    @Test
    fun tapOnNextWordPredictionChainsPredictionsForTheNewlyCommittedWord() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.editor.word = ""
        h.editor.nextWordContext = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("өйгә", "китте"), LookupKind.NEXT_WORD)
        assertEquals(Triple("өйгә", "китте", null), h.strip.shown.last())

        h.editor.predictedCommitResult = true
        h.strip.listener!!.onTap("өйгә")

        // The commit inserted "өйгә " (auto-space), so the NEXT_WORD context advanced to the word
        // just committed and predictions chain: a fresh NEXT_WORD request for "өйгә" is issued
        // inside the tap, without waiting for a keystroke.
        assertEquals(listOf("сүз" to "өйгә"), h.editor.predictedCommits)
        assertEquals(
            listOf("сүз", "өйгә"),
            h.engine.requestedContexts.map { String(it, Charsets.UTF_8) },
        )
        assertTrue("the NEXT_WORD tap must not touch the PREFIX path", h.engine.requestedPrefixes.isEmpty())

        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("керде"), LookupKind.NEXT_WORD)
        assertEquals(Triple("керде", null, null), h.strip.shown.last())
    }

    @Test
    fun refusedCommitsIssueNoFollowUpRequest() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        // PREFIX branch: the editor refuses the commit (a stale tap it caught itself), so nothing
        // about the text changed and no follow-up lookup may be issued.
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр"), LookupKind.PREFIX)
        var prefixesBefore = h.engine.requestedPrefixes.size
        var contextsBefore = h.engine.requestedContexts.size

        h.editor.commitResult = false
        h.strip.listener!!.onTap("сүзләр")

        assertEquals(listOf("сүз" to "сүзләр"), h.editor.commits) // the attempt itself was made
        assertEquals(prefixesBefore, h.engine.requestedPrefixes.size)
        assertEquals(contextsBefore, h.engine.requestedContexts.size)

        // NEXT_WORD branch, same rule.
        h.editor.word = ""
        h.editor.nextWordContext = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("өйгә"), LookupKind.NEXT_WORD)
        prefixesBefore = h.engine.requestedPrefixes.size
        contextsBefore = h.engine.requestedContexts.size

        h.editor.predictedCommitResult = false
        h.strip.listener!!.onTap("өйгә")

        assertEquals(listOf("сүз" to "өйгә"), h.editor.predictedCommits)
        assertEquals(prefixesBefore, h.engine.requestedPrefixes.size)
        assertEquals(contextsBefore, h.engine.requestedContexts.size)
    }

    @Test
    fun lateCursorMoveSettledAfterATapDoesNotDuplicateTheFollowUpRequest() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр"), LookupKind.PREFIX)
        val prefixesBefore = h.engine.requestedPrefixes.size

        h.strip.listener!!.onTap("сүзләр")
        assertEquals(1, h.engine.requestedContexts.size)

        // The cache reload behind the tap's own commit can still deliver a cursor-settled report.
        // The follow-up request issued by the tap is already in flight for THIS session, so the
        // requestSessionId == sessionId guard must cut the backstop — no duplicate lookup.
        h.controller.onCursorMoveSettled()
        assertEquals(1, h.engine.requestedContexts.size)
        assertEquals(prefixesBefore, h.engine.requestedPrefixes.size)

        // And once the answer has landed the band is bound again, which is the other guard.
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("дип"), LookupKind.NEXT_WORD)
        h.controller.onCursorMoveSettled()
        assertEquals(1, h.engine.requestedContexts.size)
        assertEquals(prefixesBefore, h.engine.requestedPrefixes.size)
    }

    @Test
    fun commitWithoutAutoSpaceFallsIntoThePrefixPathForTheCommittedWord() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        // The text after the cursor already separates the word (hugging punctuation), so the commit
        // appends no space (InputLogic's needsAutoSpace rule) and the committed word becomes the new
        // trailing word: the follow-up lookup is a PREFIX request, exactly as after typed input.
        h.editor.word = "сүз"
        h.editor.textAfterCursor = ", дигән"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр"), LookupKind.PREFIX)
        val prefixesBefore = h.engine.requestedPrefixes.size

        h.strip.listener!!.onTap("сүзләр")

        assertEquals(listOf("сүз" to "сүзләр"), h.editor.commits)
        assertEquals(prefixesBefore + 1, h.engine.requestedPrefixes.size)
        assertTrue(
            h.engine.requestedPrefixes.last()
                .contentEquals("сүзләр".toByteArray(Charsets.UTF_8)),
        )
        assertTrue(
            "no trailing space, no NEXT_WORD moment",
            h.engine.requestedContexts.isEmpty(),
        )
    }

    @Test
    fun tapWithNothingDisplayedIsNoOp() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        // No result was ever delivered, so nothing is bound/displayed: a tap must not commit.
        h.strip.listener!!.onTap("сүзләр")

        assertTrue(h.editor.commits.isEmpty())
    }

    // --- D1e regression: readiness / eligibility lifecycle (BUG 1) -----------------------------

    @Test
    fun eligibleStartWhileDictionaryNotReadyStaysHiddenUntilReadyPublish() {
        val h = Harness(dictionaryReady = false)
        h.editor.word = "сүз"

        // Field opens eligible while preparation is in flight: the frozen state table requires
        // GONE/0dp, with no engine or request.
        h.controller.onStartInput(eligible = true)
        assertEquals(0, h.factoryCalls)
        assertTrue(h.engine.requestedPrefixes.isEmpty())
        assertEquals(0, h.strip.reserveCount)
        assertFalse(h.strip.visible)

        // Readiness fires later: successful publication reserves the band and looks up the current
        // cached prefix without waiting for another keystroke.
        h.controller.signalDictionaryReadyForTest()

        assertEquals(1, h.factoryCalls)
        assertEquals(1, h.strip.reserveCount)
        assertTrue(h.strip.visible)
        assertEquals(1, h.engine.requestedPrefixes.size)
        assertTrue(
            h.engine.requestedPrefixes.single()
                .contentEquals("сүз".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun subtypeChangeToEligibleStartsEngineAndRequestsCurrentPrefix() {
        val h = Harness()

        // Field opens ineligible (e.g. non-Tatar subtype): engine never started.
        h.controller.onStartInput(eligible = false)
        assertEquals(0, h.factoryCalls)

        // Switch INTO the Tatar subtype in the already-open field.
        h.editor.word = "сүз"
        h.controller.onSubtypeChanged(eligible = true)

        assertEquals(1, h.factoryCalls)
        assertEquals(1, h.engine.requestedPrefixes.size)
        assertTrue(
            h.engine.requestedPrefixes.single()
                .contentEquals("сүз".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun subtypeChangeToEligibleWithWarmEngineReRequestsCurrentPrefix() {
        val h = Harness()
        h.editor.word = "сүз"

        // Opening an eligible field cold publishes the engine and requests the cached prefix once.
        h.controller.onStartInput(eligible = true)
        assertEquals(1, h.factoryCalls)
        assertEquals(1, h.engine.requestedPrefixes.size)

        // The same engine remains alive across tt -> non-tt -> tt. Returning to the eligible
        // subtype must immediately re-request the still-cached prefix without restarting it.
        h.controller.onSubtypeChanged(eligible = false)
        h.controller.onSubtypeChanged(eligible = true)

        assertEquals(1, h.factoryCalls)
        assertEquals(2, h.engine.requestedPrefixes.size)
        assertTrue(
            h.engine.requestedPrefixes.last()
                .contentEquals("сүз".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun lateReadinessCallbackAfterDestroyStartsNothingAndRequestsNothing() {
        val h = Harness(dictionaryReady = false)
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true)

        h.controller.onDestroy()

        // A readiness notification that lands after teardown must start nothing and request nothing.
        h.controller.signalDictionaryReadyForTest()

        assertEquals(0, h.factoryCalls)
        assertTrue(h.engine.requestedPrefixes.isEmpty())
    }

    @Test
    fun readinessCallbackAfterFinishInputStartsNothingAndKeepsStripHidden() {
        val h = Harness(dictionaryReady = false)
        h.editor.word = "сүз"

        h.controller.onStartInput(eligible = true)
        assertFalse(h.strip.visible)
        assertEquals(0, h.strip.reserveCount)

        h.controller.onFinishInput()
        val hidesAfterFinish = h.strip.hideCount

        // Finishing the editor session closes eligibility before this late readiness notification.
        h.controller.signalDictionaryReadyForTest()

        assertEquals(0, h.factoryCalls)
        assertTrue(h.engine.requestedPrefixes.isEmpty())
        assertEquals(0, h.strip.reserveCount)
        assertEquals(hidesAfterFinish, h.strip.hideCount)
        assertFalse(h.strip.visible)
    }

    @Test
    fun enginePublishAfterFinishInputStaysHiddenAndDoesNotRequest() {
        val strip = FakeStrip()
        val editor = FakeEditor().apply { word = "сүз" }
        val engine = FakeEngine()
        val executor = QueuingExecutorService()
        var factoryCalls = 0
        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { _ ->
                factoryCalls++
                engine
            },
            executor,
        )

        // Engine start is queued, but its handle has not published when the session finishes.
        controller.onStartInput(eligible = true)
        controller.onFinishInput()
        assertEquals(0, strip.reserveCount)
        assertFalse(strip.visible)

        executor.runNext()

        // The late handle is retained for safe reuse but immediately finished for the closed
        // session. It cannot expose the band or look up the old editor prefix.
        assertEquals(1, factoryCalls)
        assertEquals(1, engine.finishCount)
        assertTrue(engine.requestedPrefixes.isEmpty())
        assertEquals(0, strip.reserveCount)
        assertFalse(strip.visible)
    }

    @Test
    fun eligibleStartWithWarmEngineReRequestsCurrentPrefix() {
        val strip = FakeStrip()
        val editor = FakeEditor().apply { word = "иске" }
        val engine = FakeEngine()
        val executor = QueuingExecutorService()
        var factoryCalls = 0
        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { _ ->
                factoryCalls++
                engine
            },
            executor,
        )

        // Publish the first session's handle only after that session has finished. The handle is
        // retained and warm, but the old editor prefix must never be requested.
        controller.onStartInput(eligible = true)
        controller.onFinishInput()
        executor.runNext()
        assertEquals(1, factoryCalls)
        assertTrue(engine.requestedPrefixes.isEmpty())

        // A later eligible field already contains a cached word. Reusing the warm handle must issue
        // exactly one lookup immediately, without restarting the factory or waiting for a keypress.
        editor.word = "сүз"
        controller.onStartInput(eligible = true)

        assertEquals(1, factoryCalls)
        assertEquals(1, engine.requestedPrefixes.size)
        assertTrue(
            engine.requestedPrefixes.single()
                .contentEquals("сүз".toByteArray(Charsets.UTF_8)),
        )
        assertTrue(strip.visible)
    }

    // --- D1e regression: atomic candidate binding (BUG 2) --------------------------------------

    @Test
    fun tapOnStaleCandidateAfterTextChangeIsNoOp() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        // Type A ("сүз") and receive a real result: candidates are displayed and bound to A.
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр", "сүзлек"), LookupKind.PREFIX)
        assertEquals(1, h.strip.shown.size)

        // Text changes to B ("сүзл"): the displayed A candidates must be invalidated immediately.
        h.editor.word = "сүзл"
        h.controller.onTextChanged()

        // Tapping the now-stale A candidate is a no-op: no commit, text unchanged.
        h.strip.listener!!.onTap("сүзләр")

        assertTrue(h.editor.commits.isEmpty())
    }

    @Test
    fun tapAfterRealResultForCurrentPrefixCommitsSafely() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        // A real result for B ("сүз") is delivered and shown.
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр", "сүзлек"), LookupKind.PREFIX)
        assertEquals(1, h.strip.shown.size)

        // Tapping the displayed candidate commits against the displayed prefix.
        h.editor.commitResult = true
        h.strip.listener!!.onTap("сүзләр")

        assertEquals(listOf("сүз" to "сүзләр"), h.editor.commits)
    }

    // --- Teardown ------------------------------------------------------------------------------

    @Test
    fun destroyTearsDownEngineAndExecutor() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.controller.onDestroy()

        assertEquals(1, h.engine.destroyCount)
        assertTrue(h.executor.isShutdown)
    }

    @Test
    fun failedEngineStartLeavesControllerIdleAndStripHidden() {
        val h = Harness()
        h.factoryResult = null

        h.controller.onStartInput(eligible = true)
        assertEquals(0, h.strip.reserveCount)
        assertFalse(h.strip.visible)

        // Engine never published; a text change must not crash or request.
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        assertEquals(1, h.factoryCalls)
        assertTrue(h.engine.requestedPrefixes.isEmpty())
        assertEquals(0, h.strip.reserveCount)
        assertFalse(h.strip.visible)
        assertFalse(h.executor.isShutdown)
    }

    @Test
    fun engineStartCompletingAfterDestroyIsTornDownNotActivated() {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        val executor = QueuingExecutorService()
        var capturedCallback: ResultCallback? = null
        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { callback ->
                capturedCallback = callback
                engine
            },
            executor,
        )

        // Start the engine but leave the background start task queued (not yet published).
        controller.onStartInput(eligible = true)
        assertEquals(0, engine.destroyCount)

        // Controller is torn down while the start is still in flight.
        controller.onDestroy()

        // Now the background start completes and posts publishEngine onto the UI thread.
        executor.runNext()

        // The in-flight engine must be destroyed, not activated.
        assertEquals(1, engine.destroyCount)

        // It never became the active engine: a late result cannot be applied.
        capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр"), LookupKind.PREFIX)
        assertTrue(strip.shown.isEmpty())
    }

    @Test
    fun unknownCursorReservesAndDoesNotRequest() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        val reserveBefore = h.strip.reserveCount
        val hideBefore = h.strip.hideCount

        h.editor.knownCursor = false
        h.editor.word = "сүз"
        h.controller.onTextChanged()

        assertTrue(h.engine.requestedPrefixes.isEmpty())
        assertEquals(reserveBefore + 1, h.strip.reserveCount)
        assertEquals(hideBefore, h.strip.hideCount)
    }

    // --- Reserve vs GONE lifecycle -------------------------------------------------------------

    @Test
    fun externalSelectionChangeWhileEligibleReservesAndNeverHides() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        val reserveBefore = h.strip.reserveCount
        val hideBefore = h.strip.hideCount

        h.controller.onSelectionChanged()

        assertEquals(reserveBefore + 1, h.strip.reserveCount)
        assertEquals(hideBefore, h.strip.hideCount)
    }

    @Test
    fun selectionChangeWhileIneligibleDoesNotReserve() {
        val h = Harness()
        h.controller.onStartInput(eligible = false)
        val reserveBefore = h.strip.reserveCount

        h.controller.onSelectionChanged()

        assertEquals(reserveBefore, h.strip.reserveCount)
    }

    @Test
    fun finishInputHidesStrip() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        val hideBefore = h.strip.hideCount

        h.controller.onFinishInput()

        assertEquals(hideBefore + 1, h.strip.hideCount)
    }

    @Test
    fun subtypeChangedToEligibleReservesBand() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        val reserveBefore = h.strip.reserveCount
        val hideBefore = h.strip.hideCount

        h.controller.onSubtypeChanged(eligible = true)

        // +2, not +1: E5d's widened re-request gate (see the comment in
        // eligibleStartShowsBandOnlyAfterColdEnginePublishes) fires requestCurrentPrefix() here too,
        // and with no prefix and no context word available it falls through to an idempotent
        // clearToReservedBand() on top of the explicit reserve() just above it.
        assertEquals(reserveBefore + 2, h.strip.reserveCount)
        assertEquals(hideBefore, h.strip.hideCount)
    }

    @Test
    fun subtypeChangedToIneligibleHidesBand() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        val hideBefore = h.strip.hideCount

        h.controller.onSubtypeChanged(eligible = false)

        assertEquals(hideBefore + 1, h.strip.hideCount)
    }

    // --- Tap listener across subtype activation -------------------------------------------------

    @Test
    fun subtypeChangeToEligibleRewiresTapListenerSoTapsStillCommit() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        // The strip view is created lazily: model the production case where the listener the
        // controller installed never reached a real view (the band had not been inflated yet).
        h.strip.listener = null

        h.editor.word = "сүз"
        h.controller.onSubtypeChanged(eligible = true)

        assertNotNull(h.strip.listener)

        // The re-registered listener must be live: a tap on a displayed candidate commits.
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр", "сүзлек"), LookupKind.PREFIX)
        h.strip.listener!!.onTap("сүзләр")

        assertEquals(listOf("сүз" to "сүзләр"), h.editor.commits)
    }

    // --- Right context: no replacement in the middle of a word ---------------------------------

    @Test
    fun letterAfterCursorClearsResultsAndNeverRequests() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        val reserveBefore = h.strip.reserveCount
        val hideBefore = h.strip.hideCount

        // "ки|тап" with a freshly typed "т": the trailing word is "кит", but replacing it would
        // splice the candidate into the middle of the user's text.
        h.editor.word = "кит"
        h.editor.textAfterCursor = "ап"
        h.controller.onTextChanged()

        assertTrue(h.engine.requestedPrefixes.isEmpty())
        assertEquals(reserveBefore + 1, h.strip.reserveCount)
        assertEquals(hideBefore, h.strip.hideCount)
        assertTrue(h.strip.shown.isEmpty())
    }

    @Test
    fun tapIsNoOpWhileTheCursorSitsInsideAWord() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        // Candidates are displayed for a word typed at the end of the text.
        h.editor.word = "кит"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("китап", "китаплар"), LookupKind.PREFIX)
        assertEquals(1, h.strip.shown.size)

        // The cursor then ends up inside a word (the user typed into "ки|тап"). The next text
        // event must unbind the candidates, and a late result for the old request must not
        // re-display them, so a tap cannot commit.
        h.editor.textAfterCursor = "ап"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("китап", "китаплар"), LookupKind.PREFIX)
        h.strip.listener!!.onTap("китаплар")

        assertEquals(1, h.strip.shown.size)
        assertTrue(h.editor.commits.isEmpty())
    }

    @Test
    fun nonLetterAfterCursorKeepsRequestingAsBefore() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"

        // A space, punctuation and the end of the text all end the word: business as usual.
        h.editor.textAfterCursor = " дигән"
        h.controller.onTextChanged()
        h.editor.textAfterCursor = ", дигән"
        h.controller.onTextChanged()
        h.editor.textAfterCursor = ""
        h.controller.onTextChanged()

        assertEquals(3, h.engine.requestedPrefixes.size)
        h.engine.requestedPrefixes.forEach {
            assertTrue(it.contentEquals("сүз".toByteArray(Charsets.UTF_8)))
        }
    }

    // --- Casing contract -----------------------------------------------------------------------

    @Test
    fun lowerCasePrefixShowsAndCommitsDictionaryFormUnchanged() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.editor.word = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр", "сүзлек", "сүзсез"), LookupKind.PREFIX)

        assertEquals(Triple("сүзләр", "сүзлек", "сүзсез"), h.strip.shown.single())

        h.strip.listener!!.onTap("сүзләр")
        assertEquals(listOf("сүз" to "сүзләр"), h.editor.commits)
    }

    @Test
    fun initialCapsPrefixShowsAndCommitsInitialCapsForms() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.editor.word = "Сүз"
        h.controller.onTextChanged()
        // The lookup key is always the normalized lowercase form.
        assertTrue(
            h.engine.requestedPrefixes.single()
                .contentEquals("сүз".toByteArray(Charsets.UTF_8)),
        )
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр", "сүзлек", "сүзсез"), LookupKind.PREFIX)

        assertEquals(Triple("Сүзләр", "Сүзлек", "Сүзсез"), h.strip.shown.single())

        // The inserted form is exactly the displayed one; the guard prefix stays the raw word.
        h.strip.listener!!.onTap("Сүзләр")
        assertEquals(listOf("Сүз" to "Сүзләр"), h.editor.commits)
    }

    @Test
    fun allCapsPrefixShowsAndCommitsAllCapsForms() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.editor.word = "СҮЗ"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр", "сүзлек"), LookupKind.PREFIX)

        assertEquals(Triple("СҮЗЛӘР", "СҮЗЛЕК", null), h.strip.shown.single())

        h.strip.listener!!.onTap("СҮЗЛӘР")
        assertEquals(listOf("СҮЗ" to "СҮЗЛӘР"), h.editor.commits)
    }

    @Test
    fun mixedCasePrefixYieldsNoRequestAndAnEmptyBand() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        val reserveBefore = h.strip.reserveCount
        val hideBefore = h.strip.hideCount

        h.editor.word = "сҮз"
        h.controller.onTextChanged()

        assertTrue(h.engine.requestedPrefixes.isEmpty())
        assertEquals(reserveBefore + 1, h.strip.reserveCount)
        assertEquals(hideBefore, h.strip.hideCount)

        // Nothing is bound, so nothing can be tapped either.
        h.strip.listener!!.onTap("сүзләр")
        assertTrue(h.strip.shown.isEmpty())
        assertTrue(h.editor.commits.isEmpty())
    }

    // --- Internal cursor gestures --------------------------------------------------------------

    @Test
    fun internalCursorGestureUnbindsDisplayedCandidates() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр", "сүзлек"), LookupKind.PREFIX)
        assertEquals(1, h.strip.shown.size)
        val reserveBefore = h.strip.reserveCount

        // A space slide / delete swipe performed by the keyboard itself notifies the controller
        // directly, because the connection keeps the expected selection in sync and the framework
        // callback reports no external move.
        h.controller.onSelectionChanged()

        assertEquals(reserveBefore + 1, h.strip.reserveCount)
        h.strip.listener!!.onTap("сүзләр")
        assertTrue(h.editor.commits.isEmpty())
    }

    // --- Deferred engine release across the setting -------------------------------------------

    /**
     * Controller wired to a factory that hands out a fresh engine per start, so a test can tell an
     * engine that was released and replaced from one that was merely kept.
     */
    private class ReleaseHarness {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val executor = DirectExecutorService()
        val engines = mutableListOf<FakeEngine>()

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            {
                val engine = FakeEngine()
                engines.add(engine)
                engine
            },
            executor,
        )

        fun latestEngine(): FakeEngine = engines.last()
    }

    @Test
    fun deferredReleaseIsCancelledWhenTheSettingComesBackOnBeforeTheBoundary() {
        val h = ReleaseHarness()
        h.controller.onStartInput(eligible = true)
        assertEquals(1, h.engines.size)
        h.editor.word = "сүз"

        h.controller.onSuggestionsSettingDisabled()
        h.controller.onSuggestionsSettingEnabled(eligible = true)
        // Re-enabling reuses the warm engine and looks the typed prefix up exactly once, without
        // waiting for another keystroke.
        assertEquals(1, h.latestEngine().requestedPrefixes.size)

        // The boundary that would have run the release.
        h.controller.onStartInput(eligible = true)

        // The engine was never asked to close and was never replaced.
        assertEquals(0, h.latestEngine().destroyCount)
        assertEquals(1, h.engines.size)
    }

    @Test
    fun reEnablingAfterARefusedReleaseRetriesItInsteadOfKeepingADeadEngine() {
        val h = ReleaseHarness()
        h.controller.onStartInput(eligible = true, glideEligible = false)
        val dead = h.latestEngine()
        // The lease misses both deadlines of destroyHandle: the handle is in teardown for good and
        // rejects every later lookup, which is what request() returning null models here.
        dead.destroyResult = false
        dead.nextToken = null
        h.editor.word = "сүз"

        h.controller.onSuggestionsSettingDisabled()
        h.controller.onStartInput(eligible = false)
        // Quick attempt plus the single longer retry, both refused.
        assertEquals(2, dead.destroyCount)

        // The user turns suggestions back on. The refused release must NOT be cancelled: the band
        // stays hidden rather than being reserved for an engine that can no longer answer.
        val hideBefore = h.strip.hideCount
        val reserveBefore = h.strip.reserveCount
        h.controller.onSuggestionsSettingEnabled(eligible = true)
        assertEquals(hideBefore + 1, h.strip.hideCount)
        assertEquals(reserveBefore, h.strip.reserveCount)
        assertEquals(1, h.engines.size)
        // A keystroke in this state must not reserve a band the dead engine can never fill.
        h.controller.onTextChanged()
        assertEquals(reserveBefore, h.strip.reserveCount)
        assertTrue(dead.requestedPrefixes.isEmpty())

        // Next boundary: the lease finally closes, and a fresh engine replaces it in the same call.
        dead.destroyResult = true
        h.controller.onStartInput(eligible = true)

        assertEquals(3, dead.destroyCount)
        assertEquals(2, h.engines.size)
        assertEquals(0, h.latestEngine().destroyCount)
        assertTrue(h.strip.visible)
        // The fresh engine serves the prefix that was already typed, without another keystroke.
        assertEquals(1, h.latestEngine().requestedPrefixes.size)
    }

    @Test
    fun refusedReleaseIsRetriedAtEveryLifecycleBoundary() {
        val h = ReleaseHarness()
        h.controller.onStartInput(eligible = true, glideEligible = false)
        val dead = h.latestEngine()
        dead.destroyResult = false

        h.controller.onSuggestionsSettingDisabled()
        h.controller.onFinishInput()
        assertEquals(2, dead.destroyCount)
        h.controller.onStartInput(eligible = false)
        assertEquals(4, dead.destroyCount)

        // No second engine is ever mapped on top of an unreleased lease.
        assertEquals(1, h.engines.size)
    }

    // --- E5d NEXT_WORD ("Контракт текста" amendment, 2026-08-17) --------------------------------

    @Test
    fun emptyPrefixWithSuggestionsOnAndAvailableContextBuildsANextWordRequest() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.editor.word = ""
        h.editor.nextWordContext = "сүз"
        h.controller.onTextChanged()

        assertEquals(
            listOf("сүз"),
            h.engine.requestedContexts.map { String(it, Charsets.UTF_8) },
        )
        assertTrue("an empty prefix must never also build a PREFIX request", h.engine.requestedPrefixes.isEmpty())

        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("өйгә"), LookupKind.NEXT_WORD)
        assertEquals(Triple("өйгә", null, null), h.strip.shown.single())

        h.strip.listener!!.onTap("өйгә")
        assertEquals(listOf("сүз" to "өйгә"), h.editor.predictedCommits)
        assertTrue("PREFIX commit path must never see a NEXT_WORD tap", h.editor.commits.isEmpty())
    }

    @Test
    fun emptyPrefixWithSuggestionsOffClearsResultsAndNeverBuildsARequest() {
        val h = Harness()
        h.controller.onStartInput(eligible = false)

        h.editor.word = ""
        h.editor.nextWordContext = "сүз"
        h.controller.onTextChanged()

        assertTrue(h.engine.requestedContexts.isEmpty())
        assertTrue(h.engine.requestedPrefixes.isEmpty())
    }

    @Test
    fun emptyPrefixWithNoContextClearsResultsAndNeverBuildsARequest() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        val reserveBefore = h.strip.reserveCount

        h.editor.word = ""
        h.editor.nextWordContext = ""
        h.controller.onTextChanged()

        assertTrue(h.engine.requestedContexts.isEmpty())
        assertTrue(h.engine.requestedPrefixes.isEmpty())
        assertEquals(reserveBefore + 1, h.strip.reserveCount)
    }

    @Test
    fun selectionOrLetterAfterCursorClearsResultsRegardlessOfContext() {
        // The LETTER-after-cursor half is testable at this level; the SELECTION half is not — no
        // EditorSurface method exposes selection state to the controller at all (the same gap the
        // frozen contract already names for D3: hasSelection() is checked only inside
        // InputLogic.commitPredictedWord/replaceTrailingWord, verified there by source-contract
        // tests below, not observable from a JVM fake at the controller level).
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.editor.word = ""
        h.editor.nextWordContext = "сүз"
        h.editor.textAfterCursor = "т" // a letter sits right after the cursor
        h.controller.onTextChanged()

        assertTrue(
            "a letter after the cursor must suppress NEXT_WORD even with a context word available",
            h.engine.requestedContexts.isEmpty(),
        )
        assertTrue(h.engine.requestedPrefixes.isEmpty())
    }

    @Test
    fun nonPrefixModeSuggestionsAreNeverMixedWithPredictionsInOneBand() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        // NEXT_WORD first.
        h.editor.word = ""
        h.editor.nextWordContext = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("өйгә"), LookupKind.NEXT_WORD)
        assertEquals(1, h.strip.shown.size)

        // A non-empty prefix must unconditionally switch to PREFIX mode: the NEXT_WORD binding is
        // dropped even though no new NEXT_WORD result has arrived to explicitly clear it.
        h.editor.word = "кит"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("китап"), LookupKind.PREFIX)
        assertEquals(Triple("китап", null, null), h.strip.shown.last())

        // A tap now commits through the PREFIX path, never the NEXT_WORD one — proving the earlier
        // prediction binding is gone, not merely shadowed.
        h.strip.listener!!.onTap("китап")
        assertEquals(listOf("кит" to "китап"), h.editor.commits)
        assertTrue(h.editor.predictedCommits.isEmpty())
    }

    @Test
    fun aNonEmptyPrefixNeverShowsAPredictionAndAnEmptyPrefixNeverShowsAPrefixSuggestion() {
        // PROPOSALS.md, "E5d." fail-closed acceptance: both directions of "Сосуществование" checked
        // explicitly, not just inferred from the mode-switch test above.
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.editor.word = "кит"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("китап"), LookupKind.PREFIX)
        h.strip.listener!!.onTap("китап")
        assertTrue(
            "a non-empty prefix must never leave a NEXT_WORD binding live",
            h.editor.predictedCommits.isEmpty(),
        )

        h.editor.word = ""
        h.editor.nextWordContext = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("өйгә"), LookupKind.NEXT_WORD)
        h.strip.listener!!.onTap("өйгә")
        assertTrue(
            "an empty prefix must never leave a PREFIX binding live",
            h.editor.commits.size == 1, // only the earlier "кит"->"китап" commit, nothing new
        )
    }

    @Test
    fun predictionsKeepThePackingOrderAndAreNeverReRanked() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = ""
        h.editor.nextWordContext = "сүз"
        h.controller.onTextChanged()

        // "яр" sorts before "әби" in code-point order (see docs/DICTIONARY-E5B.md on 'з' < 'ә'), so
        // an alphabetical or frequency re-sort would visibly reorder this pair; the packing order
        // handed to onResult must survive to the strip byte-for-byte instead.
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("яр", "әби", "зур"), LookupKind.NEXT_WORD)

        assertEquals(Triple("яр", "әби", "зур"), h.strip.shown.single())
    }

    @Test
    fun predictedWordCasingIsNeverDerivedFromContextCasing() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = ""
        h.editor.nextWordContext = "Сүз" // capitalized context — PREFIX mode would re-apply this
        h.controller.onTextChanged()

        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("өйгә"), LookupKind.NEXT_WORD)

        // Shown exactly as the table stores it (lowercase) — not "Өйгә": the capitalized context
        // casing is never carried into the prediction, unlike the PREFIX casing rules above.
        assertEquals(Triple("өйгә", null, null), h.strip.shown.single())
    }

    @Test
    fun mixedCaseContextDoesNotSuppressPredictions() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = ""
        h.editor.nextWordContext = "сҮз" // mixed case — PREFIX mode would give 0 results for this
        h.controller.onTextChanged()

        // Unlike PrefixCasing.MIXED for PREFIX, NEXT_WORD has no casing gate at all: the request is
        // still built — normalized (NFC + lowercase) for the lookup key, the same as PREFIX does.
        assertEquals(
            listOf("сүз"),
            h.engine.requestedContexts.map { String(it, Charsets.UTF_8) },
        )
    }

    // --- "Состояния полосы" amendment, 2026-08-17, пятый пункт ----------------------------------

    @Test
    fun emptyBandStatesShowNextWordRowsWithoutChangingHeightOrCellCount() {
        // Both new rows are a "частный случай уже существующих строк" by construction: NEXT_WORD
        // reuses the exact same StripSurface.showSuggestions/reserve calls PREFIX does, so there is
        // no separate height/cell-count code path that could diverge. Proven here by showing both
        // the 0-results and the filled NEXT_WORD state through the one StripSurface seam.
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.editor.word = ""
        h.editor.nextWordContext = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, emptyList(), LookupKind.NEXT_WORD)
        assertTrue("0 predictions still reserves the band, same as 0 PREFIX results", h.strip.visible)
        assertTrue(h.strip.shown.isEmpty())

        h.editor.nextWordContext = "юл"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("өйгә"), LookupKind.NEXT_WORD)
        assertTrue(h.strip.visible)
        assertEquals(1, h.strip.shown.size)
    }

    // --- P3 after-word forms through the real engine (docs/TT-SUGGESTIONS.md) --------------------
    //
    // The merge itself is pinned in CompositePrefixComputerTest and TatarAfterWordFormsTest; what
    // is proven here is the full path the user sees: committed word + space -> engine worker ->
    // strip cells (bigram successors first, then the forms) -> tap commits through the NEXT_WORD
    // insertion path. The handle drives a real LatestOnlyPrefixEngine over a fixture dictionary and
    // a fixture bigram table, with the executor run inline so the answer lands synchronously — a
    // shape the controller already digests (SuggestionsControllerBigramAttachTest).

    private class RealNextWordEngineHandle(
        private val delegate: LatestOnlyPrefixEngine,
        private val executor: ManualEngineExecutor,
    ) : EngineHandle {
        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? =
            delegate.request(editorSessionId, subtypeId, prefixUtf8)?.also { executor.runAll() }

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any? = delegate.requestNextWord(editorSessionId, subtypeId, contextWordUtf8)
            ?.also { executor.runAll() }

        override fun isCurrent(token: Any): Boolean =
            token is LookupToken && delegate.isCurrent(token)

        override fun finishInput() = delegate.finishInput()

        override fun destroy(timeoutMs: Long): Boolean = true
    }

    private fun realTatarNextWordEngine(
        h: Harness,
        bigramSuccessors: List<String>,
    ): RealNextWordEngineHandle {
        val dictionary = EngineTestFixtures.index(
            listOf(
                "татар" to 134_412L,
                "татарлар" to 12_085L,
                "татарның" to 2_752L,
                "татарча" to 9_093L,
            ),
        )
        val computer = CompositePrefixComputer(
            dictionary,
            PersonalCandidateSource.EMPTY,
            TatarSuffixRules.createAfterWordForms(dictionary),
        )
        computer.attachBigramSource(
            EngineTestFixtures.bigramIndex(listOf("татар" to bigramSuccessors)),
        )
        val executor = ManualEngineExecutor()
        val delegate = LatestOnlyPrefixEngine(
            dictionary.identity,
            computer,
            executor,
            ResultHandoff { result ->
                // The callback arrives at engine start, which the controller performs after this
                // handle is handed over — read it lazily.
                h.capturedCallback!!.onResult(result.token, result.suggestions, result.kind)
            },
        )
        return RealNextWordEngineHandle(delegate, executor)
    }

    @Test
    fun committedTatarWordPlusSpaceOffersItsInflectionsAfterTheBigramSuccessors() {
        val h = Harness()
        h.factoryResult = realTatarNextWordEngine(h, listOf("белән"))
        h.controller.onStartInput(eligible = true)

        h.editor.word = ""
        h.editor.nextWordContext = "татар"
        h.controller.onTextChanged()

        // The bigram successor keeps the lead; the strip cells it leaves free carry the forms of
        // татар, frequency-ranked (12 085 > 9 093 > the unshown татарның 2 752).
        assertEquals(Triple("белән", "татарлар", "татарча"), h.strip.shown.last())

        // A form cell commits exactly like a predicted word: the NEXT_WORD insertion path.
        h.strip.listener!!.onTap("татарча")
        assertEquals(listOf("татар" to "татарча"), h.editor.predictedCommits)
        assertTrue(h.editor.commits.isEmpty())
    }

    @Test
    fun bigramSuccessorsTakingAllThreeCellsLeaveNoRoomForForms() {
        val h = Harness()
        h.factoryResult = realTatarNextWordEngine(h, listOf("белән", "дип", "туры"))
        h.controller.onStartInput(eligible = true)

        h.editor.word = ""
        h.editor.nextWordContext = "татар"
        h.controller.onTextChanged()

        assertEquals(Triple("белән", "дип", "туры"), h.strip.shown.last())
    }
}
