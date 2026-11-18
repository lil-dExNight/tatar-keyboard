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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind

/**
 * TalkBack hears the suggestions once per appearance, never once per keystroke.
 *
 * The controller blanks the strip while a lookup is in flight, so the strip's own cells make
 * every letter look like a first appearance. [SuggestionAnnouncementPolicy] remembers that the
 * context already spoke: only a new field, language, cursor position or committed word, or a
 * strip left with nothing to show, starts the next appearance.
 */
class SuggestionAnnouncementTest {

    // --- the policy -----------------------------------------------------------------------------

    @Test
    fun theFirstWordsOfAContextAreAnAppearance() {
        val policy = SuggestionAnnouncementPolicy()

        assertTrue(policy.onPublication(populated = true))
        assertFalse(policy.onPublication(populated = true))
    }

    @Test
    fun updatesDuringOneRunAreNotAppearances() {
        val policy = SuggestionAnnouncementPolicy()
        assertTrue(policy.onPublication(populated = true))

        repeat(5) { assertFalse(policy.onPublication(populated = true)) }
    }

    @Test
    fun aContextBoundaryStartsTheNextAppearance() {
        val policy = SuggestionAnnouncementPolicy()
        assertTrue(policy.onPublication(populated = true))

        policy.onContextBoundary()

        assertTrue(policy.onPublication(populated = true))
    }

    @Test
    fun aSettledEmptyStripReArmsTheAppearance() {
        val policy = SuggestionAnnouncementPolicy()
        assertTrue(policy.onPublication(populated = true))

        assertFalse(policy.onPublication(populated = false))
        assertTrue(policy.onPublication(populated = true))
    }

    @Test
    fun anEmptyPublicationIsNeverAnAppearance() {
        assertFalse(SuggestionAnnouncementPolicy().onPublication(populated = false))
    }

    // --- fakes ----------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        var cells: List<String> = emptyList()
        /** Every appearance the controller asked the accessibility service to speak. */
        var appearances = 0
            private set
        var spokenLabels: List<String?> = listOf(null, null, null, null)
        var listener: SuggestionTapListener? = null

        override fun showSuggestions(
            first: String,
            second: String?,
            third: String?,
            fourth: String?,
        ) {
            cells = listOfNotNull(first, second, third, fourth).filter(String::isNotEmpty)
        }

        override fun markSuggestionAppearance() {
            appearances++
        }

        override fun setSpokenCellLabels(
            first: String?,
            second: String?,
            third: String?,
            fourth: String?,
        ) {
            spokenLabels = listOf(first, second, third, fourth)
        }

        override fun reserve() {
            cells = emptyList()
        }

        override fun hideSuggestions() {
            cells = emptyList()
        }

        override fun setTapListener(listener: SuggestionTapListener) {
            this.listener = listener
        }
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""
        var knownCursor: Boolean = true
        var letterAfterCursor: Boolean = false
        var nextWordContext: String = ""

        override fun cachedWordBeforeCursor(): String = word

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            word = ""
            nextWordContext = suggestion
            return true
        }

        override fun hasKnownCursor(): Boolean = knownCursor

        override fun hasLetterAfterCursor(): Boolean = letterAfterCursor

        override fun cachedNextWordContext(): String = nextWordContext
    }

    private class FakeEngine : EngineHandle {
        override fun request(
            editorSessionId: Long,
            subtypeId: String,
            prefixUtf8: ByteArray,
        ): Any = TOKEN

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any = TOKEN

        override fun isCurrent(token: Any): Boolean = true

        override fun finishInput() = Unit

        override fun destroy(timeoutMs: Long): Boolean = true

        companion object {
            val TOKEN = Any()
        }
    }

    private class DirectExecutorService : AbstractExecutorService() {
        private var stopped = false

        override fun execute(command: Runnable) = command.run()

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = stopped

        override fun isTerminated(): Boolean = stopped

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    /** The controller wired to fakes, with every worker callback delivered inline. */
    private class Harness(clipLabel: String? = null) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        private val engine = FakeEngine()
        private var callback: ResultCallback? = null

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { _, resultCallback ->
                callback = resultCallback
                engine
            },
            { DirectExecutorService() },
            { _: ExecutorService, _: String -> null },
            true,
            clipCellSpokenLabel = { clipLabel },
        )

        fun start() {
            controller.onStartInput(eligible = true)
        }

        /** One keystroke: the trailing word becomes [word], answered with [suggestions]. */
        fun type(word: String, suggestions: List<String> = listOf("сүз", "сүзгә")) {
            editor.word = word
            controller.onTextChanged()
            deliver(suggestions)
        }

        /** A tap on a strip cell, routed as the view routes one. */
        fun tap(suggestion: String) {
            requireNotNull(strip.listener).onTap(suggestion)
        }

        fun deliver(suggestions: List<String>, kind: LookupKind = LookupKind.PREFIX) {
            requireNotNull(callback).onResult(FakeEngine.TOKEN, suggestions, kind)
        }
    }

    // --- the typing run -------------------------------------------------------------------------

    @Test
    fun typingOneWordMarksOneAppearance() {
        val h = Harness()
        h.start()
        assertEquals("the reserved empty strip says nothing", 0, h.strip.appearances)

        h.type("с")
        assertEquals(1, h.strip.appearances)

        h.type("сү")
        h.type("сүз")
        assertEquals("every letter republishes, only the first one appears", 1, h.strip.appearances)
        assertEquals(listOf("сүз", "сүзгә"), h.strip.cells)
    }

    @Test
    fun aWordBoundaryBetweenTheLettersDoesNotSpeakAgain() {
        val h = Harness()
        h.start()
        h.type("сүз")
        assertEquals(1, h.strip.appearances)

        // A separator: the trailing word is empty and the NEXT_WORD predictions arrive.
        h.editor.word = ""
        h.editor.nextWordContext = "сүз"
        h.controller.onTextChanged()
        h.deliver(listOf("һәм", "белән"), LookupKind.NEXT_WORD)

        assertEquals("the same typing run does not speak twice", 1, h.strip.appearances)
        assertEquals(listOf("һәм", "белән"), h.strip.cells)
    }

    @Test
    fun aCursorMoveToTheSameWordStartsANewAppearance() {
        val h = Harness()
        h.start()
        h.type("сүз")
        assertEquals(1, h.strip.appearances)

        h.controller.onSelectionChanged()
        h.controller.onCursorMoveSettled()
        h.deliver(listOf("сүз", "сүзгә"))

        assertEquals("a cursor position is a context of its own", 2, h.strip.appearances)
    }

    @Test
    fun anEmptyResultReArmsTheAppearanceForTheWordsThatFollow() {
        val h = Harness()
        h.start()
        h.type("сүз")
        assertEquals(1, h.strip.appearances)

        h.type("сүзл", suggestions = emptyList())
        assertEquals(emptyList<String>(), h.strip.cells)
        assertEquals(1, h.strip.appearances)

        h.type("сүзлә")
        assertEquals("the strip was left with nothing to show", 2, h.strip.appearances)
    }

    @Test
    fun aCommittedSuggestionStartsANewAppearance() {
        val h = Harness()
        h.start()
        h.type("сүз")
        assertEquals(1, h.strip.appearances)

        h.tap("сүз")
        h.deliver(listOf("һәм", "белән"), LookupKind.NEXT_WORD)

        assertEquals("the committed word is a new context", 2, h.strip.appearances)
    }

    // --- the clip cell --------------------------------------------------------------------------

    @Test
    fun theRecentClipAppearanceCarriesOnlyItsActionLabel() {
        val h = Harness(clipLabel = "Вставить из буфера")
        h.start()
        h.controller.onPrimaryClipChanged("сәләм дөнья", System.currentTimeMillis())

        h.editor.word = ""
        h.controller.onTextChanged()

        assertEquals(listOf("сәләм дөнья"), h.strip.cells)
        assertEquals(1, h.strip.appearances)
        assertEquals(
            "the clip is spoken by its action, never by its text",
            listOf("Вставить из буфера", null, null, null),
            h.strip.spokenLabels,
        )
    }
}
