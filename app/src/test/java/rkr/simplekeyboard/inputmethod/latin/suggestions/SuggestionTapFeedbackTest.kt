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
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectAdvice
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath

/**
 * The [SuggestionTapFeedback] seam: exactly one tick per strip tap that took effect — an accepted
 * suggestion, a predicted word, a reverted replacement, a keep-typed refusal, a pasted clip or a
 * glide alternative — and zero ticks on a tap the editor refused, on a dead cell, and on a tap
 * with nothing on the strip. The harness reproduces `LatinIME.onEvent` in the order the service
 * runs it, exactly like [RevertCellControllerTest].
 */
class SuggestionTapFeedbackTest {

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        val bands = mutableListOf<List<String?>>()
        var listener: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?, fourth: String?) {
            bands.add(listOf(first, second, third, fourth))
        }

        override fun reserve() = Unit

        override fun hideSuggestions() = Unit

        override fun setTapListener(listener: SuggestionTapListener) {
            this.listener = listener
        }

        fun lastBand(): List<String?>? = bands.lastOrNull()
    }

    /** A text model with a collapsed cursor between [before] and [after]. */
    private class FakeEditor : EditorSurface {
        var before: String = ""
        var after: String = ""

        /** Every edit this surface actually performed, in order. */
        val edits = mutableListOf<String>()

        /** True models the commit path refusing a glide (a stale cache that never reloaded). */
        var glideCommitsRefused: Boolean = false

        override fun cachedWordBeforeCursor(): String =
            TatarWordUtils.extractTrailingWord(before)

        override fun cachedNextWordContext(): String =
            TatarWordUtils.extractNextWordContext(before, cacheReachedTextStart = true)

        override fun hasKnownCursor(): Boolean = true

        override fun hasLetterAfterCursor(): Boolean =
            TatarWordUtils.startsWithWordCharacter(after)

        override fun cursorPosition(): Int = before.length

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            if (cachedWordBeforeCursor() != expectedPrefix) return false
            val committed = if (TatarWordUtils.needsAutoSpace(after)) "$suggestion " else suggestion
            edits.add("commit:$expectedPrefix->$committed")
            before = before.dropLast(expectedPrefix.length) + committed
            return true
        }

        override fun replaceTypedWord(expectedPrefix: String, replacement: String): Boolean {
            if (cachedWordBeforeCursor() != expectedPrefix) return false
            edits.add("replace:$expectedPrefix->$replacement")
            before = before.dropLast(expectedPrefix.length) + replacement
            return true
        }

        override fun revertTypedWord(
            insertedForm: String,
            separator: String,
            typedForm: String,
        ): Boolean {
            val inserted = insertedForm + separator
            if (!before.endsWith(inserted)) return false
            edits.add("revert:$inserted->$typedForm$separator")
            before = before.dropLast(inserted.length) + typedForm + separator
            return true
        }

        override fun commitPredictedWord(expectedContextWord: String, suggestion: String): Boolean {
            // The production re-checks, modeled: an empty trailing word, the live context must
            // match, and an empty context binds only at a sentence start.
            if (TatarWordUtils.extractTrailingWord(before).isNotEmpty()) return false
            if (TatarWordUtils.extractNextWordContext(before, true) != expectedContextWord) {
                return false
            }
            if (expectedContextWord.isEmpty() &&
                !TatarWordUtils.isSentenceStartContext(before, true)
            ) {
                return false
            }
            edits.add("predict:$expectedContextWord->$suggestion")
            before += if (TatarWordUtils.needsAutoSpace(after)) "$suggestion " else suggestion
            return true
        }

        override fun commitClipText(text: String): Boolean {
            edits.add("clip:$text")
            before += text
            return true
        }

        override fun commitGlideWord(
            expectedContextWord: String,
            suggestion: String,
            expectedTrailingWord: String,
            expectedCursor: Int,
        ): Int {
            if (glideCommitsRefused) return EditorSurface.GLIDE_COMMIT_REFUSED
            if (TatarWordUtils.startsWithWordCharacter(after)) return EditorSurface.GLIDE_COMMIT_REFUSED
            if (TatarWordUtils.extractTrailingWord(before) != expectedTrailingWord) {
                return EditorSurface.GLIDE_COMMIT_REFUSED
            }
            if (TatarWordUtils.extractNextWordContext(before, true) != expectedContextWord) {
                return EditorSurface.GLIDE_COMMIT_REFUSED
            }
            val prepend = TatarWordUtils.glideNeedsLeadingSpace(before)
            edits.add("glide:$suggestion")
            before += (if (prepend) " " else "") + suggestion
            return if (prepend) EditorSurface.GLIDE_COMMIT_PREPENDED else EditorSurface.GLIDE_COMMIT_BARE
        }

        override fun replaceGlideLiftedWord(
            committedWord: String,
            alternative: String,
            prependedSpace: Boolean,
        ): Boolean {
            // In place: the leading space is kept exactly as committed; the trailing word must BE
            // the committed word.
            if (TatarWordUtils.extractTrailingWord(before) != committedWord) return false
            val suffix = (if (prependedSpace) " " else "") + committedWord
            if (!before.endsWith(suffix)) return false
            edits.add("glide-alt:$committedWord->$alternative")
            before = before.dropLast(suffix.length) + (if (prependedSpace) " " else "") + alternative
            return true
        }
    }

    /** Delivers every result synchronously. */
    private class FakeEngine : EngineHandle {
        val suggestionsByWord = mutableMapOf<String, List<String>>()
        val nextWordByContext = mutableMapOf<String, List<String>>()
        val adviceByWord = mutableMapOf<String, AutocorrectAdvice>()

        /** The N-best the next glide request answers, top candidate first. */
        var glideCandidates: List<String> = emptyList()
        var callback: ResultCallback? = null
        private var lastPrefix: String = ""

        override fun request(
            editorSessionId: Long,
            subtypeId: String,
            prefixUtf8: ByteArray,
        ): Any? {
            val word = String(prefixUtf8, Charsets.UTF_8)
            lastPrefix = word
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

        override fun requestGlide(
            editorSessionId: Long,
            subtypeId: String,
            path: GlidePath,
            contextWordUtf8: ByteArray,
        ): Any? {
            val token = Any()
            callback?.onResult(token, glideCandidates, LookupKind.GLIDE)
            return token
        }

        override fun isCurrent(token: Any): Boolean = true

        override fun finishInput() = Unit

        override fun autocorrectAdvice(): AutocorrectAdvice? = adviceByWord[lastPrefix]

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

    private class Harness {
        var now = 10_000_000L

        /** The live keyguard state the controller's gate reads; false = unlocked. */
        var keyguardLocked = false
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        var ticks = 0
        var refusalTicks = 0

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { _: String, resultCallback -> engine.apply { callback = resultCallback } },
            { DirectExecutorService() },
            { _: ExecutorService, _: String -> null },
            true,
        )

        init {
            controller.setAutocorrectGate { true }
            // The production decorator reads the locale's quote pattern from a string resource;
            // the test pins the Tatar/Russian one.
            controller.setRevertCellDecorator { typedWord -> "«$typedWord»" }
            controller.setGlideGate(GlideGate { true })
            controller.setKeyguardGate(KeyguardGate { keyguardLocked })
            controller.replaceRecentClipCellForTest(RecentClipCell { now })
            controller.setSuggestionTapFeedback { ticks++ }
            controller.setGlideRefusalFeedback { refusalTicks++ }
        }

        fun start() {
            controller.onStartInput(eligible = true)
        }

        fun type(text: String) {
            editor.before += text
            controller.onTextChanged()
        }

        fun typeWord(word: String) {
            word.forEach { type(it.toString()) }
        }

        fun separator(separator: Char) {
            controller.maybeAutocorrectBeforeSeparator(separator.code)
            editor.before += separator
            controller.onTextChanged()
        }

        /** A clipboard change event, as LatinIME's listener delivers it. */
        fun clip(text: String?) {
            controller.onPrimaryClipChanged(text, now)
        }

        fun glide() {
            controller.onGlideInput(GlidePath())
        }

        fun tap(suggestion: String) {
            strip.listener?.onTap(suggestion)
        }

        fun advise(typed: String, replacement: String) {
            engine.adviceByWord[typed] = AutocorrectAdvice(
                typed, replacement, 5_000L, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
            )
        }
    }

    // --- accepted suggestion (prefix binding) -----------------------------------------------------

    @Test
    fun anAcceptedSuggestionTicksExactlyOnce() {
        val h = Harness()
        h.start()
        h.engine.suggestionsByWord["сүз"] = listOf("сүзең", "сүзләр")
        h.typeWord("сүз")
        assertEquals(listOf("сүзең", "сүзләр", null, null), h.strip.lastBand())

        h.tap("сүзең")

        assertEquals(1, h.ticks)
        assertEquals(listOf("commit:сүз->сүзең "), h.editor.edits)
    }

    @Test
    fun aSuggestionCommitTheEditorRefusesTicksNever() {
        val h = Harness()
        h.start()
        h.engine.suggestionsByWord["сүз"] = listOf("сүзең")
        h.typeWord("сүз")
        // The field moved on without any event reaching the keyboard: the tap is stale.
        h.editor.before = "башка"

        h.tap("сүзең")

        assertEquals(0, h.ticks)
        assertTrue(h.editor.edits.isEmpty())
    }

    // --- accepted prediction (next-word binding) ---------------------------------------------------

    @Test
    fun anAcceptedPredictionTicksExactlyOnce() {
        val h = Harness()
        h.start()
        h.engine.nextWordByContext["сүз"] = listOf("бөек", "китап")
        h.typeWord("сүз")
        h.separator(' ')
        assertEquals(listOf("бөек", "китап", null, null), h.strip.lastBand())

        h.tap("бөек")

        assertEquals(1, h.ticks)
        assertEquals("сүз бөек ", h.editor.before)
    }

    @Test
    fun aPredictionCommitTheEditorRefusesTicksNever() {
        val h = Harness()
        h.start()
        h.engine.nextWordByContext["сүз"] = listOf("бөек")
        h.typeWord("сүз")
        h.separator(' ')
        // The live context no longer matches the one the strip was painted for.
        h.editor.before = "китап "

        h.tap("бөек")

        assertEquals(0, h.ticks)
        assertTrue(h.editor.edits.isEmpty())
    }

    // --- the revert cell of the undo window ---------------------------------------------------------

    @Test
    fun aRevertCellTapTicksExactlyOnceWhenTheRevertLands() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')
        assertEquals(listOf("«китәп»", null, null, null), h.strip.lastBand())

        h.tap("«китәп»")

        assertEquals(1, h.ticks)
        assertEquals("китәп ", h.editor.before)
    }

    @Test
    fun aRevertCellTapTicksNeverWhenTheEditorRefusesTheRevert() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')
        // The application edited the field itself, without any event reaching the keyboard.
        h.editor.before = "башка текст "

        h.tap("«китәп»")

        assertEquals(0, h.ticks)
        assertEquals("башка текст ", h.editor.before)
    }

    // --- the keep-typed cell of the autocorrect preview ---------------------------------------------

    @Test
    fun aKeepTypedTapTicksExactlyOnceAndCommitsNothing() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        // The preview strip: the typed word first, the coming correction second.
        assertEquals(listOf("китәп", "китап", null, null), h.strip.lastBand())

        h.tap("китәп")

        assertEquals(1, h.ticks)
        assertTrue("a keep-typed tap edits no text", h.editor.edits.isEmpty())
    }

    // --- the recent-clip cell -----------------------------------------------------------------------

    @Test
    fun aClipTapTicksExactlyOnce() {
        val h = Harness()
        h.clip("беренче юл\nикенче юл")
        h.start()
        val offer = h.strip.lastBand()!!.first()!!

        h.tap(offer)

        assertEquals(1, h.ticks)
        assertEquals(listOf("clip:беренче юл\nикенче юл"), h.editor.edits)
    }

    @Test
    fun aClipTapUnderTheLockTicksNever() {
        val h = Harness()
        h.clip("сәләм дөнья")
        h.start()
        val offer = h.strip.lastBand()!!.first()!!
        // The screen locked after the cell was painted: the tap commits nothing.
        h.keyguardLocked = true

        h.tap(offer)

        assertEquals(0, h.ticks)
        assertTrue(h.editor.edits.isEmpty())
    }

    // --- glide alternative and refused-glide candidate ----------------------------------------------

    @Test
    fun aGlideAlternativeTapTicksExactlyOnce() {
        val h = Harness()
        h.start()
        h.engine.glideCandidates = listOf("сәләм", "сәлләм", "сәлми")
        h.glide()
        // The lift itself is the gesture, not a tap: no tick for it.
        assertEquals(0, h.ticks)
        assertEquals("сәләм", h.editor.before)
        assertEquals(listOf("сәлләм", "сәлми", null, null), h.strip.lastBand())

        h.tap("сәлләм")

        assertEquals(1, h.ticks)
        assertEquals("сәлләм", h.editor.before)
    }

    @Test
    fun aGlideAlternativeTapTicksNeverWhenTheWordMovedOn() {
        val h = Harness()
        h.start()
        h.engine.glideCandidates = listOf("сәләм", "сәлләм")
        h.glide()
        assertEquals("сәләм", h.editor.before)
        // The field moved on without any event reaching the keyboard.
        h.editor.before = "сәләмдә"

        h.tap("сәлләм")

        assertEquals(0, h.ticks)
        assertEquals("сәләмдә", h.editor.before)
    }

    @Test
    fun aRefusedGlideCandidateTapTicksOnceWhenTheCommitLands() {
        val h = Harness()
        h.start()
        h.engine.glideCandidates = listOf("сәләм", "сәлләм")
        h.editor.glideCommitsRefused = true
        h.glide()
        // The refused lift: the refusal tick fired, no suggestion tick, the candidates stay.
        assertEquals(1, h.refusalTicks)
        assertEquals(0, h.ticks)
        assertEquals(listOf("сәләм", "сәлләм", null, null), h.strip.lastBand())

        h.editor.glideCommitsRefused = false
        h.tap("сәлләм")

        assertEquals(1, h.ticks)
        assertEquals("сәлләм", h.editor.before)
    }

    @Test
    fun aRefusedGlideCandidateTapTicksNeverWhileTheCommitIsRefused() {
        val h = Harness()
        h.start()
        h.engine.glideCandidates = listOf("сәләм", "сәлләм")
        h.editor.glideCommitsRefused = true
        h.glide()
        assertEquals(1, h.refusalTicks)

        h.tap("сәләм")

        // The tap goes through the glide commit path and is refused again: the refusal tick
        // repeats, the suggestion tick stays silent.
        assertEquals(2, h.refusalTicks)
        assertEquals(0, h.ticks)
        assertEquals("", h.editor.before)
    }

    // --- no-op taps ------------------------------------------------------------------------------------

    @Test
    fun aTapWithNothingOnTheStripTicksNever() {
        val h = Harness()
        h.start()

        h.tap("сүзең")

        assertEquals(0, h.ticks)
        assertTrue(h.editor.edits.isEmpty())
    }
}
