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
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.EmptyPrefixProber
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * The deferred empty-prefix observation: a cleanly typed word whose proper prefixes were never
 * observed empty (latest-only coalescing dropped the intermediate lookups) still graduates — the
 * separator's next-word lookup goes first, then the engine worker probes the shortest proper
 * prefix with an empty exact pass and the completion is reported against that observation.
 *
 * The harness follows [PersonalLearningRunTest]: one fake engine, a synchronous poster, and a
 * text model the word extraction helpers actually parse, so the separator produces a real
 * next-word context.
 */
class DeferredEmptyPrefixProbeTest {

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        override fun showSuggestions(first: String, second: String?, third: String?, fourth: String?) = Unit
        override fun reserve() = Unit
        override fun hideSuggestions() = Unit
        override fun setTapListener(listener: SuggestionTapListener) = Unit
    }

    /** A text model with the cursor at the end of [before]. */
    private class FakeEditor : EditorSurface {
        var before: String = ""

        override fun cachedWordBeforeCursor(): String =
            TatarWordUtils.extractTrailingWord(before)

        override fun cachedNextWordContext(): String =
            TatarWordUtils.extractNextWordContext(before, cacheReachedTextStart = true)

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = false
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
    }

    /**
     * Logs every call in order (`prefix:…`, `next:…`, `probe:…`), answers prefix lookups only
     * when the test delivers them, and answers the deferred probe inline or on demand.
     */
    private class FakeEngine : EngineHandle {
        val events = mutableListOf<String>()
        var callback: ResultCallback? = null
        private var serial = 0L

        /** The exact-miss verdict the controller reads when a delivered answer is applied. */
        var exactMiss: String? = null

        /** The probe's answer: the shortest empty proper-prefix length, or NO_EMPTY_PREFIX. */
        var probeAnswer = EmptyPrefixProber.NO_EMPTY_PREFIX

        /** When false, probe callbacks are parked in [pendingProbes] for the test to fire. */
        var deliverProbeInline = true
        val pendingProbes = mutableListOf<Pair<String, (Int) -> Unit>>()

        override fun request(
            editorSessionId: Long,
            subtypeId: String,
            prefixUtf8: ByteArray,
        ): Any {
            events += "prefix:" + String(prefixUtf8, Charsets.UTF_8)
            return ++serial
        }

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any {
            events += "next:" + String(contextWordUtf8, Charsets.UTF_8)
            return ++serial
        }

        override fun requestEmptyPrefixProbe(word: String, callback: (Int) -> Unit): Boolean {
            events += "probe:$word"
            if (deliverProbeInline) {
                callback(probeAnswer)
            } else {
                pendingProbes += word to callback
            }
            return true
        }

        /** The worker handoff of the newest prefix lookup's answer. */
        fun answer(suggestions: List<String>, exactMiss: String? = null) {
            this.exactMiss = exactMiss
            callback?.onResult(serial, suggestions, LookupKind.PREFIX)
        }

        override fun isCurrent(token: Any): Boolean = token == serial

        override fun finishInput() = Unit

        override fun exactMissPrefix(): String? = exactMiss

        override fun destroy(timeoutMs: Long): Boolean = true
    }

    private class DirectExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class Harness {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        val completions = mutableListOf<String>()

        val controller = SuggestionsController(
            strip, editor, UiPoster { it.run() },
            { resultCallback -> engine.apply { callback = resultCallback } },
            DirectExecutor(), true,
        )

        /** Types [word] one code point per text event; [answerAt] answers a grown prefix. */
        fun type(word: String, answerAt: (String) -> Pair<List<String>, String?>? = { null }) {
            var end = 0
            while (end < word.length) {
                val start = end
                end += Character.charCount(word.codePointAt(end))
                editor.before += word.substring(start, end)
                controller.onTextChanged()
                val answer = answerAt(word.substring(0, end))
                if (answer != null) engine.answer(answer.first, answer.second)
            }
        }

        /** The word ends: a space commits and the trailing word becomes empty. */
        fun endWord() {
            editor.before += " "
            controller.onTextChanged()
        }

        /**
         * One whole word cycle so the session has witnessed a boundary: the first word after a
         * dirty event is never counted ([CleanRunMachine] keeps the run dirty until one boundary
         * has passed), so this word neither reports nor parks a probe, whatever its lookups did.
         */
        fun witnessBoundary() {
            type("баш")
            endWord()
            engine.events.clear()
        }
    }

    private fun newHarness(withSink: Boolean = true): Harness {
        val h = Harness()
        if (withSink) {
            h.controller.setCompletionSink { word -> h.completions.add(word) }
        }
        h.controller.onStartInput(eligible = true)
        return h
    }

    // --- The deferred observation --------------------------------------------------------------

    @Test
    fun theDeferredObservationGraduatesAWordWhosePrefixesWereNeverObserved() {
        val h = newHarness()
        h.witnessBoundary()
        h.engine.probeAnswer = 2 // "үт" came back with an empty exact pass

        h.type("үтәли") // every lookup's answer is dropped, as under latest-only coalescing
        h.endWord()

        assertEquals(listOf("үтәли"), h.completions)
        // The probe ran behind the separator's next-word lookup, never ahead of it.
        val nextIndex = h.engine.events.indexOf("next:үтәли")
        val probeIndex = h.engine.events.indexOf("probe:үтәли")
        assertTrue("the next-word lookup was issued", nextIndex >= 0)
        assertTrue("the probe was enqueued behind it", probeIndex > nextIndex)
    }

    @Test
    fun anObservedEmptyPrefixStillGraduatesWithoutAnyProbe() {
        val h = newHarness()
        h.witnessBoundary()

        h.type("гүзәлия") { prefix ->
            // Every lookup is answered; the one at "гүзә" comes back empty.
            if (prefix == "гүзә") {
                emptyList<String>() to null
            } else {
                listOf(prefix + "л") to null
            }
        }
        h.endWord()

        assertEquals(listOf("гүзәлия"), h.completions)
        assertTrue(h.engine.events.none { it.startsWith("probe:") })
    }

    @Test
    fun aWordObservedEmptyOnlyAtItsFullLengthStillGraduatesThroughTheProbe() {
        val h = newHarness()
        h.witnessBoundary()
        h.engine.probeAnswer = 2 // "үт" came back with an empty exact pass

        // Coalescing dropped every intermediate lookup; only the full-word one landed, and even
        // it was exact-empty. A full-word observation is not a proper prefix, so the ordinary
        // path cannot report — the deferred probe reconstructs the missing observation.
        h.type("үтәли") { prefix ->
            if (prefix == "үтәли") emptyList<String>() to null else null
        }
        h.endWord()

        assertEquals(listOf("үтәли"), h.completions)
        assertTrue(h.engine.events.contains("probe:үтәли"))
    }

    @Test
    fun aProbeThatFindsNoEmptyPrefixLearnsNothing() {
        val h = newHarness()
        h.witnessBoundary()
        h.engine.probeAnswer = EmptyPrefixProber.NO_EMPTY_PREFIX

        h.type("үтәли")
        h.endWord()

        assertTrue(h.completions.isEmpty())
        assertTrue(h.engine.events.contains("probe:үтәли"))
    }

    @Test
    fun aProbeAnswerLandingMidNextWordStillGraduatesTheCompletedWord() {
        val h = newHarness()
        h.witnessBoundary()
        h.engine.deliverProbeInline = false

        h.type("үтәли")
        h.endWord()
        assertTrue(h.completions.isEmpty())

        // The next word is already growing when the worker's answer finally lands.
        h.type("кил")
        assertEquals(1, h.engine.pendingProbes.size)
        h.engine.pendingProbes.single().second(2)

        assertEquals(listOf("үтәли"), h.completions)
    }

    @Test
    fun aNewerParkedWordSupersedesTheStaleProbeAnswer() {
        val h = newHarness()
        h.witnessBoundary()
        h.engine.deliverProbeInline = false

        h.type("үтәли")
        h.endWord()
        h.type("башла")
        h.endWord()

        assertEquals(2, h.engine.pendingProbes.size)
        // The first word's answer arrives late; its slot was already taken by the second word.
        h.engine.pendingProbes[0].second(2)
        assertTrue(h.completions.isEmpty())
        h.engine.pendingProbes[1].second(3)
        assertEquals(listOf("башла"), h.completions)
    }

    @Test
    fun aSelectionChangeDropsTheParkedWord() {
        val h = newHarness()
        h.witnessBoundary()
        h.engine.deliverProbeInline = false

        h.type("үтәли")
        h.endWord()
        h.controller.onSelectionChanged()

        h.engine.pendingProbes.single().second(2)
        assertTrue(h.completions.isEmpty())
    }

    @Test
    fun aOneLetterWordHasNoProperPrefixToProbe() {
        val h = newHarness()
        h.witnessBoundary()

        h.type("ә")
        h.endWord()

        assertTrue(h.completions.isEmpty())
        assertTrue(h.engine.events.none { it.startsWith("probe:") })
    }

    @Test
    fun learningOffSkipsTheProbeEvenWithALearningSinkInstalled() {
        val h = newHarness()
        h.controller.setCompletionLearningPredicate { false }
        h.witnessBoundary()

        h.type("үтәли")
        h.endWord()

        assertTrue(h.engine.events.none { it.startsWith("probe:") })
        assertTrue(h.completions.isEmpty())
    }

    @Test
    fun withoutALearningSinkNoProbeEverRuns() {
        val h = newHarness(withSink = false)
        h.witnessBoundary()

        h.type("үтәли")
        h.endWord()

        assertTrue(h.engine.events.none { it.startsWith("probe:") })
    }
}
