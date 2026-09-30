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
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind

/**
 * Rapid start/finish-input churn: a hostile or buggy host can bounce the IME through start/finish
 * cycles at any rate, so a result requested in one editor session can arrive in the next one. A
 * result computed for a dead session must never paint the strip, and the controller must keep
 * working after the storm. Driven like [SuggestionsControllerTest] (the real controller over
 * fakes), extending its single-boundary stale-drop tests
 * ([staleResultDroppedWhenFinishInputBumpsSession] and siblings) with interleaving: results landing
 * mid-churn, repeated finish without start, selection changes and re-requests.
 */
class HostileHostSuggestionChurnTest {

    // --- Fakes (same shapes as SuggestionsControllerTest, trimmed to the driven surface) ---------

    private class FakeStrip : StripSurface {
        val shown = mutableListOf<List<String?>>()
        var hideCount = 0
        var visible = false

        override fun showSuggestions(first: String, second: String?, third: String?) {
            shown.add(listOf(first, second, third))
            visible = true
        }

        override fun reserve() {
            visible = true
        }

        override fun hideSuggestions() {
            hideCount++
            visible = false
        }

        override fun setTapListener(listener: SuggestionTapListener) {}
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""

        override fun cachedWordBeforeCursor(): String = word
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = true
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
    }

    private class FakeEngine : EngineHandle {
        val requestedPrefixes = mutableListOf<ByteArray>()
        var finishCount = 0
        var isCurrentResult: Boolean = true

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any {
            requestedPrefixes.add(prefixUtf8)
            return TOKEN
        }

        override fun isCurrent(token: Any): Boolean = isCurrentResult
        override fun finishInput() {
            finishCount++
        }

        override fun destroy(timeoutMs: Long): Boolean = true

        companion object {
            val TOKEN = Any()
        }
    }

    /** The engine start/publish hop runs synchronously, like SuggestionsControllerTest's. */
    private class DirectExecutorService : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class Harness {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        var capturedCallback: ResultCallback? = null

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { callback ->
                capturedCallback = callback
                engine
            },
            DirectExecutorService(),
            true,
        )
    }

    // --- The scripted core: a request issued before finishInput applies after it -------------------

    @Test
    fun aResultIssuedBeforeFinishInputAppliesSafelyAfterIt() {
        val h = Harness()
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        assertEquals("the lookup is in flight", 1, h.engine.requestedPrefixes.size)
        val paintsBefore = h.strip.shown.size
        val hidesBefore = h.strip.hideCount

        // The field goes away mid-flight; the engine answers for the dead session a beat later.
        h.controller.onFinishInput()
        h.engine.isCurrentResult = true // even a still-"current" engine answer must not win
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр"), LookupKind.PREFIX)

        assertEquals("a result for the finished session never paints",
            paintsBefore, h.strip.shown.size)
        assertEquals("finishInput hid the strip exactly once",
            hidesBefore + 1, h.strip.hideCount)
        assertTrue(!h.strip.visible)

        // The editor-facing state keeps working: a fresh session requests and paints normally.
        h.controller.onStartInput(eligible = true)
        h.editor.word = "кит"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("китап"), LookupKind.PREFIX)

        assertEquals(listOf("китап", null, null), h.strip.shown.last())
        assertTrue(h.strip.visible)
    }

    // --- The interleaved storm ----------------------------------------------------------------------

    /**
     * A seeded 4 000-step storm of start/finish/typing/selection-churn with a hostile engine
     * result landing at arbitrary points. Invariants after EVERY step: exactly the one cold-start
     * hide plus one hide per finishInput (nothing else may hide the strip), and no result is ever
     * painted while no session is open. The storm itself must not throw; afterwards a fresh
     * session must work end to end.
     */
    @Test
    fun aSeededStartFinishStormNeverPaintsAfterFinishAndNeverThrows() {
        val h = Harness()
        val random = java.util.Random(20260929L)
        var sessionOpen = false
        var expectedHides = 0
        // The first eligible start pays the one cold-engine hide (the strip must stay GONE until
        // the engine publishes); every finishInput hides exactly once. Warm re-starts only reserve.
        var coldStartHidePending = true

        repeat(4_000) { step ->
            when (random.nextInt(5)) {
                0 -> {
                    h.controller.onStartInput(eligible = true)
                    sessionOpen = true
                    if (coldStartHidePending) {
                        expectedHides++
                        coldStartHidePending = false
                    }
                }
                1 -> {
                    h.controller.onFinishInput()
                    sessionOpen = false
                    expectedHides++
                }
                2 -> {
                    h.editor.word = "сүз" + random.nextInt(100)
                    h.controller.onTextChanged()
                }
                3 -> h.controller.onSelectionChanged()
                4 -> {
                    val shownBefore = h.strip.shown.size
                    h.engine.isCurrentResult = random.nextBoolean()
                    h.capturedCallback?.onResult(
                        FakeEngine.TOKEN,
                        listOf("старая${random.nextInt(10)}"),
                        LookupKind.PREFIX,
                    )
                    assertTrue(
                        "step $step: a result delivered with no open session never paints",
                        sessionOpen || h.strip.shown.size == shownBefore,
                    )
                }
            }
            assertEquals(
                "step $step: exactly the cold-start hide plus one hide per finishInput",
                expectedHides, h.strip.hideCount)
        }

        // Recovery: whatever the storm left behind, a fresh session works end to end.
        h.controller.onStartInput(eligible = true)
        h.editor.word = "сәл"
        h.controller.onTextChanged()
        h.engine.isCurrentResult = true
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сәләм"), LookupKind.PREFIX)
        assertEquals(listOf("сәләм", null, null), h.strip.shown.last())
    }
}
