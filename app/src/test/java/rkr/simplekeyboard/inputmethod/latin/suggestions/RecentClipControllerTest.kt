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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The recent-clip cell as the controller runs it: offered only at an idle strip (empty prefix, no
 * context word) in an eligible field while the clip is fresh; the tap commits the full clip text;
 * nothing is offered or committed in ineligible fields, with a stale clip or after the window hid;
 * and nothing about the clip is ever learned.
 */
class RecentClipControllerTest {

    companion object {
        /** The fake sentence-start table's content; shown initial-caps by the controller. */
        private val SENT_START = listOf("бу", "ул", "ә")
    }

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        val bands = mutableListOf<List<String?>>()

        /** The strip's publication events, in order: "show" per band, "reserve", "hide". */
        val events = mutableListOf<String>()
        var listener: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?) {
            bands.add(listOf(first, second, third))
            events.add("show")
        }

        override fun reserve() {
            events.add("reserve")
        }

        override fun hideSuggestions() {
            events.add("hide")
        }

        override fun setTapListener(listener: SuggestionTapListener) {
            this.listener = listener
        }

        fun lastBand(): List<String?>? = bands.lastOrNull()
    }

    /** A text model with a collapsed cursor between [before] and [after]. */
    private class FakeEditor : EditorSurface {
        var before: String = ""

        /** Every edit this surface actually performed, in order. */
        val edits = mutableListOf<String>()

        override fun cachedWordBeforeCursor(): String =
            TatarWordUtils.extractTrailingWord(before)

        override fun cachedNextWordContext(): String =
            TatarWordUtils.extractNextWordContext(before, cacheReachedTextStart = true)

        override fun cachedWordBeforeTrailingWord(): String =
            TatarWordUtils.extractWordBeforeTrailingWord(before, cacheReachedTextStart = true)

        override fun hasKnownCursor(): Boolean = true

        override fun hasLetterAfterCursor(): Boolean = false

        override fun isAtSentenceStart(): Boolean =
            TatarWordUtils.isSentenceStartContext(before, cacheReachedTextStart = true)

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            if (cachedWordBeforeCursor() != expectedPrefix) return false
            val committed = "$suggestion "
            edits.add("commit:$expectedPrefix->$committed")
            before = before.dropLast(expectedPrefix.length) + committed
            return true
        }

        override fun commitClipText(text: String): Boolean {
            edits.add("clip:$text")
            before += text
            return true
        }
    }

    /** Delivers every result synchronously. */
    private class FakeEngine : EngineHandle {
        val suggestionsByWord = mutableMapOf<String, List<String>>()
        val nextWordByContext = mutableMapOf<String, List<String>>()
        var callback: ResultCallback? = null

        override fun request(
            editorSessionId: Long,
            subtypeId: String,
            prefixUtf8: ByteArray,
        ): Any? {
            val word = String(prefixUtf8, Charsets.UTF_8)
            val token = Any()
            callback?.onResult(token, suggestionsByWord[word] ?: emptyList(), LookupKind.PREFIX)
            return token
        }

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any? {
            val context = String(contextWordUtf8, Charsets.UTF_8)
            val token = Any()
            callback?.onResult(token, nextWordByContext[context] ?: emptyList(), LookupKind.NEXT_WORD)
            return token
        }

        override fun isCurrent(token: Any): Boolean = true

        override fun finishInput() = Unit

        override fun destroy(timeoutMs: Long): Boolean = true
    }

    private class DirectExecutorService : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    /** Records what learning was told, so "a clip teaches nothing" is checkable. */
    private class RecordingSink : WordCompletionSink {
        val completions = mutableListOf<String>()
        override fun onCleanCompletion(word: String) {
            completions.add(word)
        }

        override fun onInputFinished() = Unit
    }

    private class RecordingPairSink : PairCompletionSink {
        val pairs = mutableListOf<Pair<String, String>>()
        override fun onCleanPairCompletion(contextWord: String, completedWord: String) {
            pairs.add(contextWord to completedWord)
        }
    }

    private class Harness {
        var now = 10_000_000L
        /** When true, the sentence-start table answers (the production default); the clip's
         * precedence over it is part of the contract under test. */
        var withSentStart = false
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        val sink = RecordingSink()
        val pairSink = RecordingPairSink()

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { _: String, resultCallback -> engine.apply { callback = resultCallback } },
            { DirectExecutorService() },
            { _: ExecutorService, _: String -> null },
            true,
            // The sentence-start table is present in these tests: the clip's precedence over it
            // is part of the contract under test.
            sentStartPreparationFactory = { _, _ ->
                if (withSentStart) {
                    SentStartPreparation { onResult -> onResult(SentStartSource { SENT_START }) }
                } else {
                    null
                }
            },
        )

        init {
            controller.setCompletionSink(sink)
            controller.setPairCompletionSink(pairSink)
            controller.replaceRecentClipCellForTest(RecentClipCell { now })
        }

        fun start(eligible: Boolean = true) {
            controller.onStartInput(eligible = eligible)
        }

        /** A clipboard change event, as LatinIME's listener delivers it. */
        fun clip(text: String?) {
            controller.onPrimaryClipChanged(text, now)
        }

        fun type(text: String) {
            editor.before += text
            controller.onTextChanged()
        }

        fun typeWord(word: String) {
            word.forEach { type(it.toString()) }
        }

        fun separator(separator: Char) {
            editor.before += separator
            controller.onTextChanged()
        }

        fun tap(suggestion: String) {
            strip.listener?.onTap(suggestion)
        }
    }

    @Test
    fun theClipOutranksTheSentenceStartTableAtAnEmptyPosition() {
        val h = Harness()
        h.withSentStart = true
        h.clip("сәләм дөнья")

        h.start()

        assertEquals(listOf("сәләм дөнья", null, null), h.strip.lastBand())
    }

    @Test
    fun withoutAClipTheSentenceStartTableAnswers() {
        val h = Harness()
        h.withSentStart = true

        h.start()
        h.type(" ")  // a separator change drives the idle re-derivation

        assertEquals(
            listOf("Бу", "Ул", "Ә"),
            h.strip.lastBand(),
        )
    }

    // --- The offer --------------------------------------------------------------------------------

    @Test
    fun aFreshClipIsOfferedAtAnEmptyIdlePosition() {
        val h = Harness()
        h.clip("сәләм дөнья")

        h.start()

        assertEquals(listOf("сәләм дөнья", null, null), h.strip.lastBand())
    }

    @Test
    fun aClipCopiedWhileTheKeyboardIsUpIsOffered() {
        val h = Harness()
        h.start()
        assertNull(h.strip.lastBand())

        h.clip("сәләм")
        // The event alone does not repaint an idle empty strip; the next derivation does.
        h.controller.onTextChanged()

        assertEquals(listOf("сәләм", null, null), h.strip.lastBand())
    }

    @Test
    fun aStaleClipIsNeverOffered() {
        val h = Harness()
        h.clip("сәләм дөнья")
        h.now += RecentClipCell.TTL_MILLIS + 1

        h.start()

        assertNull(h.strip.lastBand())
    }

    @Test
    fun anIneligibleFieldSeesNoClipCell() {
        // A password, numeric, incognito or lock-screen field arrives here as "not eligible": the
        // whole offer hangs off that one flag.
        val h = Harness()
        h.clip("сәләм дөнья")

        h.start(eligible = false)
        h.controller.onTextChanged()

        assertTrue(h.strip.bands.isEmpty())
        assertTrue(h.strip.events.none { it == "show" })
    }

    @Test
    fun noClipCellWhileAWordIsBeingTyped() {
        val h = Harness()
        h.start()
        h.clip("сәләм дөнья")

        h.type("су")

        assertTrue(h.strip.bands.none { it.first() == "сәләм дөнья" })
    }

    @Test
    fun noClipCellWhenAContextWordOffersPredictions() {
        val h = Harness()
        h.editor.before = "сүз "
        h.engine.nextWordByContext["сүз"] = listOf("өйгә")
        h.clip("сәләм дөнья")

        h.start()

        assertEquals(listOf("өйгә", null, null), h.strip.lastBand())
    }

    @Test
    fun aNewClipRepaintsTheShownCellAtOnce() {
        val h = Harness()
        h.clip("беренче")
        h.start()
        assertEquals(listOf("беренче", null, null), h.strip.lastBand())

        h.clip("икенче")

        assertEquals(listOf("икенче", null, null), h.strip.lastBand())
    }

    @Test
    fun aClearedClipboardDropsTheShownCellAtOnce() {
        val h = Harness()
        h.clip("беренче")
        h.start()
        assertEquals(listOf("беренче", null, null), h.strip.lastBand())

        // A non-text clip (or a cleared clipboard) arrives as null: the cell goes away.
        h.clip(null)

        // The strip is re-derived at once: no band paints, the reserved (empty) strip stays.
        assertEquals("reserve", h.strip.events.last())
        assertEquals(1, h.strip.bands.size)
    }

    @Test
    fun hidingTheInputViewDropsTheClip() {
        val h = Harness()
        h.clip("сәләм")
        h.start()
        assertEquals(listOf("сәләм", null, null), h.strip.lastBand())

        h.controller.onInputViewHidden()
        h.controller.onTextChanged()

        assertEquals("reserve", h.strip.events.last())
        assertEquals(1, h.strip.bands.size)
    }

    // --- The tap --------------------------------------------------------------------------------

    @Test
    fun tappingTheCellCommitsTheFullClipText() {
        val h = Harness()
        h.clip("беренче юл\nикенче юл")
        h.start()
        val offer = h.strip.lastBand()!!.first()!!

        h.tap(offer)

        assertEquals(listOf("clip:беренче юл\nикенче юл"), h.editor.edits)
        assertEquals("беренче юл\nикенче юл", h.editor.before)
    }

    @Test
    fun theCommittedClipIsOneShot() {
        val h = Harness()
        h.clip("сәләм")
        h.start()
        h.tap("сәләм")

        h.controller.onTextChanged()

        assertEquals("the offer is consumed with the tap",
            1, h.strip.bands.count { it.first() == "сәләм" })
    }

    @Test
    fun aStaleClipCellIsADeadCell() {
        val h = Harness()
        h.clip("сәләм")
        h.start()
        assertEquals(listOf("сәләм", null, null), h.strip.lastBand())

        // The clip expired while the cell was on screen: the tap commits nothing.
        h.now += RecentClipCell.TTL_MILLIS + 1
        h.tap("сәләм")

        assertTrue(h.editor.edits.isEmpty())
        assertEquals("", h.editor.before)
    }

    @Test
    fun theClipTeachesNeitherTheWordStoreNorThePairStore() {
        val h = Harness()
        h.clip("башкала шәһәре")
        h.start()
        h.tap("башкала шәһәре")

        // A word typed after the commit: not learned itself (the run was dirtied by the paste-like
        // commit), and no pair with the clip's last word is learned.
        h.typeWord("дөнья")
        h.separator(' ')

        assertTrue(h.sink.completions.isEmpty())
        assertTrue(h.pairSink.pairs.isEmpty())
    }

    @Test
    fun aTapNamingTheClipTextDuringAnOrdinaryBandCommitsNoClip() {
        // The clip cell is recognized from the strip's state, never from the string: an ordinary
        // PREFIX band holding the same word as the clip is a plain suggestion strip, and its tap
        // goes through the suggestion commit path.
        val h = Harness()
        h.start()
        h.clip("сүзләр")
        h.engine.suggestionsByWord["сү"] = listOf("сүзләр")
        h.typeWord("сү")

        h.tap("сүзләр")

        assertEquals(listOf("commit:сү->сүзләр "), h.editor.edits)
        assertEquals("сүзләр ", h.editor.before)
    }
}
