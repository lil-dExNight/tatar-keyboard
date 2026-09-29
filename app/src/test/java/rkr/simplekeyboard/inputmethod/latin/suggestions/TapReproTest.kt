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
import rkr.simplekeyboard.inputmethod.latin.RichInputConnection
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * Mission tt-tap-repro. Reproduces, from the symptom, the two defects the operator saw on 1.8.0.
 * The cursor-bookkeeping cause behind both symptom-2 tests was fixed in 1.8.1; they pass now and
 * stay here as regression tests. The symptom-1 test was @Ignore'd until mission tt-final: the
 * operator's 2026-08-22 decision made the repaint part of the assignment, and the invariant it
 * asserts — what the strip paints is tappable — now holds.
 *
 * The fakes here differ from [SuggestionsControllerTest]'s in ONE deliberate way: the engine has
 * real latest-only token semantics (a fresh token per request, `isCurrent` true only for the newest
 * one, `finishInput()` invalidating the generation) instead of a constant token with
 * `isCurrent == true`. A fake that answers "yes, current" to every token cannot express a dropped
 * result, which is the state both symptoms live in.
 */
class TapReproTest {

    // --- Fakes ---------------------------------------------------------------------------------

    /** Records what the user can actually SEE on the strip, not just the call log. */
    private class RecordingStrip : StripSurface {
        var visibleWords: List<String> = emptyList()
        var visible = false
        var listener: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?) {
            visibleWords = listOfNotNull(first, second, third)
            visible = true
        }

        override fun reserve() {
            visibleWords = emptyList()
            visible = true
        }

        override fun hideSuggestions() {
            visibleWords = emptyList()
            visible = false
        }

        override fun setTapListener(listener: SuggestionTapListener) {
            this.listener = listener
        }

        /** Exactly what a finger can do: tap a word that is currently painted. */
        fun tap(word: String) {
            assertTrue("tap() on a word the strip is not showing: $word", visibleWords.contains(word))
            listener!!.onTap(word)
        }
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""
        var commitResult: Boolean = true
        var knownCursor: Boolean = true
        var textAfterCursor: String = ""
        var nextWordContext: String = ""
        val commits = mutableListOf<Pair<String, String>>()
        val predictedCommits = mutableListOf<Pair<String, String>>()

        override fun cachedWordBeforeCursor(): String = word

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            commits.add(expectedPrefix to suggestion)
            return commitResult
        }

        override fun hasKnownCursor(): Boolean = knownCursor

        override fun hasLetterAfterCursor(): Boolean =
            TatarWordUtils.startsWithWordCharacter(textAfterCursor)

        override fun cachedNextWordContext(): String = nextWordContext

        override fun commitPredictedWord(expectedContextWord: String, suggestion: String): Boolean {
            predictedCommits.add(expectedContextWord to suggestion)
            return true
        }
    }

    /** Mirrors LatestOnlyPrefixEngine's generation semantics, which the shared fake flattens away. */
    private class LatestOnlyEngine : EngineHandle {
        private var serial = 0L
        private var current: Any? = null
        val requested = mutableListOf<String>()
        var finishCount = 0

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            requested.add(String(prefixUtf8, Charsets.UTF_8))
            val token = "prefix#${serial++}"
            current = token
            return token
        }

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any? {
            requested.add("[next]" + String(contextWordUtf8, Charsets.UTF_8))
            val token = "next#${serial++}"
            current = token
            return token
        }

        override fun isCurrent(token: Any): Boolean = token == current

        override fun finishInput() {
            finishCount++
            current = null
        }

        override fun destroy(timeoutMs: Long): Boolean = true

        /** The token the newest request handed out, or null if the generation was invalidated. */
        fun newestToken(): Any? = current
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

    private class Harness {
        val strip = RecordingStrip()
        val editor = FakeEditor()
        val engine = LatestOnlyEngine()
        var callback: ResultCallback? = null

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { cb -> callback = cb; engine },
            DirectExecutorService(),
            true,
        )

        /**
         * The keyboard's own expected-selection bookkeeping — the REAL class, so the scenario
         * below is driven by production arithmetic rather than by an assumption about it.
         */
        val connection = RichInputConnection(null)

        /** One typed letter: the editor's cached word becomes [word], then the IME reacts. */
        fun type(word: String) {
            editor.word = word
            // A typed character goes through commitText, which collapses the expected pair; the
            // framework then reports the very same position, so no external move is seen.
            connection.updateSelection(word.length, word.length)
            controller.onTextChanged()
        }

        /**
         * One backspace, in the order LatinIME actually runs it: the edit and its bookkeeping
         * (InputLogic.handleBackspaceEvent -> deleteTextBeforeCursor), then
         * updateStateAfterInputTransaction -> onTextChanged, and only later the framework's
         * onUpdateSelection for the same edit.
         */
        fun backspace(word: String) {
            connection.deleteTextBeforeCursor(1)
            editor.word = word
            controller.onTextChanged()
            frameworkReportsCursor(word.length)
        }

        /** LatinIME.onUpdateSelection, lines 1300-1306, verbatim in shape. */
        fun frameworkReportsCursor(offset: Int) {
            val externalMove = offset != connection.expectedSelectionStart ||
                offset != connection.expectedSelectionEnd
            connection.updateSelection(offset, offset)
            if (externalMove) controller.onSelectionChanged()
        }

        /** The engine answers the newest outstanding request. */
        fun deliver(vararg suggestions: String) {
            val token = engine.newestToken() ?: return
            callback!!.onResult(token, suggestions.toList(), LookupKind.PREFIX)
        }

        /** The NEXT_WORD sibling of [deliver]. */
        fun deliverNextWord(vararg suggestions: String) {
            val token = engine.newestToken() ?: return
            callback!!.onResult(token, suggestions.toList(), LookupKind.NEXT_WORD)
        }
    }

    // --- Symptom 1 -------------------------------------------------------------------------------

    /**
     * The invariant, checked the way a finger checks it: whatever the strip is painting AT THIS
     * INSTANT must reach the editor when tapped.
     *
     * Deliberately stated as a property of the visible band rather than as an expected band
     * content, so it holds whichever way the controller chooses to keep it. An empty band satisfies
     * it by having nothing to offer — which is why every caller below also asserts that the band
     * comes back, so "paint nothing, ever" cannot pass.
     *
     * Consumes the tap it makes: a successful commit clears the band, so this may only be called at
     * the instant under test, never as a warm-up.
     */
    private fun assertNothingPaintedIsDead(h: Harness) {
        val painted = h.strip.visibleWords
        if (painted.isEmpty()) return
        val before = h.editor.commits.size + h.editor.predictedCommits.size
        h.strip.tap(painted[0])
        assertTrue(
            "the strip is painting \"${painted[0]}\" and tapping it reached nothing",
            h.editor.commits.size + h.editor.predictedCommits.size > before,
        )
    }

    /**
     * "Ячейка подсвечивается, но текст не меняется."
     *
     * The window: the live cached word has moved off the prefix the painted candidates were
     * computed for, and the answer to the new prefix has not come back yet. The controller unbinds
     * the candidates the instant the prefix changes, so a tap in this window can never commit —
     * whatever is still on the strip is a dead button.
     */
    @Test
    fun tappingAWordTheStripIsPaintingAlwaysReachesTheEditor() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        // "Сәләм абый сине" — the trailing word is "сине" and the band fills with its candidates.
        h.type("сине")
        h.deliver("синең", "сине", "сингапур")
        assertEquals(listOf("синең", "сине", "сингапур"), h.strip.visibleWords)

        // One more letter. The lookup for "синеп" is in flight; nothing has answered it yet.
        h.type("синеп")
        assertNothingPaintedIsDead(h)

        // And the band is not simply gone for good: the answer to the new prefix fills it back,
        // and what it paints is tappable like anything else.
        h.deliver("синеп", "синепле")
        assertEquals(listOf("синеп", "синепле"), h.strip.visibleWords)
        h.strip.tap("синеп")
        assertEquals("синеп" to "синеп", h.editor.commits.last())
    }

    /**
     * The same window on the NEXT_WORD side: a prediction band painted for one context word, then
     * the context word underneath it changes.
     */
    @Test
    fun tappingAPaintedPredictionAlwaysReachesTheEditor() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        // Empty trailing word plus a context word is the NEXT_WORD path.
        h.editor.nextWordContext = "мин"
        h.type("")
        h.deliverNextWord("барам", "киләм")
        assertEquals(listOf("барам", "киләм"), h.strip.visibleWords)

        // The word before the cursor is now a different one; the prediction on screen describes
        // text the user has already left.
        h.editor.nextWordContext = "син"
        h.type("")
        assertNothingPaintedIsDead(h)

        h.deliverNextWord("барасың")
        assertEquals(listOf("барасың"), h.strip.visibleWords)
        h.strip.tap("барасың")
        assertEquals("син" to "барасың", h.editor.predictedCommits.last())
    }

    /**
     * Rule 3 of the mission dossier, as a test: ordinary monolingual typing must not change. The
     * words the user ends up looking at after each answered keystroke are exactly the ones the
     * engine returned, in order, and every one of them commits.
     */
    @Test
    fun ordinaryTypingStillShowsTheEngineAnswerForEveryKeystroke() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.type("к")
        h.deliver("китап", "кеше", "кайда")
        assertEquals(listOf("китап", "кеше", "кайда"), h.strip.visibleWords)

        h.type("ки")
        h.deliver("китап", "кием", "кичә")
        assertEquals(listOf("китап", "кием", "кичә"), h.strip.visibleWords)

        h.type("кит")
        h.deliver("китап", "китә", "китте")
        assertEquals(listOf("китап", "китә", "китте"), h.strip.visibleWords)

        h.strip.tap("китап")
        assertEquals("кит" to "китап", h.editor.commits.last())
    }

    // --- Symptom 2 -------------------------------------------------------------------------------

    /**
     * "како" -> "какоц" (0 results, correct) -> backspace -> "како": the candidates that were on
     * screen one keystroke ago must come back.
     *
     * Nothing here asserts on the desync directly. The scenario simply runs the production
     * bookkeeping and lets it decide whether the keyboard's own backspace looks external.
     */
    @Test
    fun suggestionsComeBackAfterDeletingTheExtraLetter() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.type("како")
        h.deliver("какой", "какое", "какая")
        assertEquals(listOf("какой", "какое", "какая"), h.strip.visibleWords)

        // One letter too many: the dictionary has nothing, and the empty band is correct.
        h.type("какоц")
        h.deliver()
        assertEquals(emptyList<String>(), h.strip.visibleWords)

        // The extra letter is deleted and the lookup for the restored prefix is answered.
        h.backspace("како")
        h.deliver("какой", "какое", "какая")

        assertEquals(
            "the same prefix that had candidates one keystroke ago shows none",
            listOf("какой", "какое", "какая"),
            h.strip.visibleWords,
        )
    }

    /**
     * The same step with the opposite arrival order: the engine answers before the framework
     * reports the cursor. Either order ends with an empty band, which is why the defect reproduces
     * every time rather than intermittently.
     */
    @Test
    fun suggestionsSurviveTheCursorReportThatFollowsABackspace() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)

        h.type("како")
        h.deliver("какой", "какое", "какая")
        h.type("какоц")
        h.deliver()

        // Backspace, but with the result arriving before the framework's cursor report.
        h.connection.deleteTextBeforeCursor(1)
        h.editor.word = "како"
        h.controller.onTextChanged()
        h.deliver("какой", "какое", "какая")
        assertEquals(listOf("какой", "какое", "какая"), h.strip.visibleWords)

        h.frameworkReportsCursor("како".length)

        assertEquals(
            "the band is wiped by the keyboard's own backspace and nothing re-requests",
            listOf("какой", "какое", "какая"),
            h.strip.visibleWords,
        )
    }
}
