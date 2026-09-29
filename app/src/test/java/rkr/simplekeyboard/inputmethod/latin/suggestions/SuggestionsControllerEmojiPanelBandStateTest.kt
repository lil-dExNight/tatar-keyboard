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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind

/**
 * Named test for the E2c "Состояния полосы" row (contract amendment 2026-07-27, procedure step 3):
 *
 *   "Настройка ON, ввод разрешён, активен татарский subtype, панель эмодзи показана →
 *    полоса видима, 40dp, lookup не выполняется, ячейки пустые и inert"
 *
 * plus its conditional clause: OFF / a privacy gate that forbids suggestions / a non-Tatar subtype
 * keep the prior GONE rows, and showing the panel does not make the band visible.
 *
 * Showing the panel reaches the controller as [SuggestionsController.onSelectionChanged] — see
 * `LatinIME`'s `EmojiSurface.showPanel`, which empties the strip through that idempotent path and
 * then swaps the surface — so this drives exactly that call. Its own fakes are modelled on the
 * (private) ones in [SuggestionsControllerTest]; existing tests are not touched.
 */
class SuggestionsControllerEmojiPanelBandStateTest {

    private class FakeStrip : StripSurface {
        val shown = mutableListOf<Triple<String, String?, String?>>()
        var hideCount = 0
        var reserveCount = 0
        var visible = false
        var listener: SuggestionTapListener? = null

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

        override fun setTapListener(listener: SuggestionTapListener) {
            this.listener = listener
        }
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""
        var commitResult: Boolean = true
        var knownCursor: Boolean = true
        var textAfterCursor: String = ""
        val commits = mutableListOf<Pair<String, String>>()

        override fun cachedWordBeforeCursor(): String = word

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            commits.add(expectedPrefix to suggestion)
            return commitResult
        }

        override fun hasKnownCursor(): Boolean = knownCursor

        override fun hasLetterAfterCursor(): Boolean =
            TatarWordUtils.startsWithWordCharacter(textAfterCursor)
    }

    private class FakeEngine : EngineHandle {
        val requestedPrefixes = mutableListOf<ByteArray>()
        var finishCount = 0

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            requestedPrefixes.add(prefixUtf8)
            return TOKEN
        }

        override fun isCurrent(token: Any): Boolean = true

        override fun finishInput() {
            finishCount++
        }

        override fun destroy(timeoutMs: Long): Boolean = true

        companion object {
            val TOKEN = Any()
        }
    }

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

    private class Harness(dictionaryReady: Boolean = true) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        val executor = DirectExecutorService()
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
            dictionaryReady,
        )
    }

    @Test
    fun panelShownWhileEligibleKeepsBandReservedEmptyInertAndDoesNoLookup() {
        val h = Harness()
        // Eligible field, dictionary ready: the engine starts inline and the band is reserved.
        h.controller.onStartInput(eligible = true)
        // Put words on the band so we can prove the panel empties it (and does not resize it).
        h.editor.word = "сүз"
        h.controller.onTextChanged()
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр", "сүзлек"), LookupKind.PREFIX)
        assertTrue("precondition: band shows words before the panel", h.strip.shown.isNotEmpty())
        assertTrue("precondition: band is visible before the panel", h.strip.visible)

        val reserveBefore = h.strip.reserveCount
        val hideBefore = h.strip.hideCount
        val shownBefore = h.strip.shown.size
        val requestsBefore = h.engine.requestedPrefixes.size
        val finishBefore = h.engine.finishCount

        // The emoji panel is shown -> controller.onSelectionChanged() (LatinIME.EmojiSurface).
        h.controller.onSelectionChanged()

        // Visible at the SAME height (reserved 40dp, not hidden): the panel does not manage the band.
        assertTrue("band must stay visible at the same height", h.strip.visible)
        assertEquals("panel must not hide the band", hideBefore, h.strip.hideCount)
        assertEquals("band re-published as the empty reserved band", reserveBefore + 1, h.strip.reserveCount)
        // Cells empty and inert: no words shown.
        assertEquals("no words shown while the panel is up", shownBefore, h.strip.shown.size)
        // Lookup not performed: engine.request is never called; the in-flight generation is finished.
        assertEquals("no lookup while the panel is up", requestsBefore, h.engine.requestedPrefixes.size)
        assertEquals("in-flight lookup invalidated", finishBefore + 1, h.engine.finishCount)

        // Inert: a tap on the band is a no-op and never commits (displayed candidates were unbound).
        h.strip.listener!!.onTap("сүзләр")
        assertTrue("tap while the panel is up must not commit", h.editor.commits.isEmpty())

        // A late result for the pre-panel prefix must not repaint the band either.
        h.capturedCallback!!.onResult(FakeEngine.TOKEN, listOf("сүзләр"), LookupKind.PREFIX)
        assertEquals("a stale result must not repaint", shownBefore, h.strip.shown.size)
    }

    @Test
    fun panelShownWhileIneligibleDoesNotMakeTheBandVisible() {
        // Setting OFF, a privacy gate that forbids suggestions, and a non-Tatar subtype all reduce
        // to eligible == false at the controller (LatinIME computes eligibility from the setting,
        // the editor gate and the subtype together), so one ineligible case covers the row's
        // "prior GONE rows still apply, and the panel does not affect them" clause.
        val h = Harness()
        h.controller.onStartInput(eligible = false)
        assertFalse("precondition: band is hidden when ineligible", h.strip.visible)
        val reserveBefore = h.strip.reserveCount
        val hideBefore = h.strip.hideCount

        h.controller.onSelectionChanged()

        assertFalse("panel must not make an ineligible band visible", h.strip.visible)
        assertEquals("no reserve for an ineligible field", reserveBefore, h.strip.reserveCount)
        assertEquals("ineligible onSelectionChanged leaves the strip untouched", hideBefore, h.strip.hideCount)
        assertTrue("no engine and no lookup when ineligible", h.engine.requestedPrefixes.isEmpty())
    }
}
