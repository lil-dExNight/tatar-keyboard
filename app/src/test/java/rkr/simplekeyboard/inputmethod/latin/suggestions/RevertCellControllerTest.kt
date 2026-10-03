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
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectAdvice
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * The keep-typed cell of the undo window (the strip's inline revert offer): while the window holds
 * a revertable replacement, the strip's first cell is the typed word in quotes; a tap reverts
 * through the same undo as the one backspace; any further text change ends the window and the
 * ordinary strip resumes.
 *
 * The harness reproduces `LatinIME.onEvent` in the order the service runs it, exactly like
 * [AutocorrectControllerTest]: the separator's correction BEFORE the separator is committed, the
 * backspace undo first, the one `onTextChanged()` after every edit.
 */
class RevertCellControllerTest {

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        /** One painted band: the three cells. */
        val bands = mutableListOf<List<String?>>()

        /** The spoken labels of the last publication (null = speak the cell's text). */
        var spokenLabels = listOf<String?>(null, null, null)
        var listener: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?) {
            bands.add(listOf(first, second, third))
        }

        override fun setSpokenCellLabels(first: String?, second: String?, third: String?) {
            spokenLabels = listOf(first, second, third)
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

        override fun cachedWordBeforeCursor(): String =
            TatarWordUtils.extractTrailingWord(before)

        override fun cachedNextWordContext(): String =
            TatarWordUtils.extractNextWordContext(before, cacheReachedTextStart = true)

        override fun hasKnownCursor(): Boolean = true

        override fun hasLetterAfterCursor(): Boolean =
            TatarWordUtils.startsWithWordCharacter(after)

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            if (cachedWordBeforeCursor() != expectedPrefix) return false
            val committed =
                if (TatarWordUtils.needsAutoSpace(after)) "$suggestion " else suggestion
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
    }

    /** Delivers every result synchronously; the verdict belongs to the newest PREFIX lookup. */
    private class FakeEngine : EngineHandle {
        val suggestionsByWord = mutableMapOf<String, List<String>>()
        val adviceByWord = mutableMapOf<String, AutocorrectAdvice>()
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

    private class Harness(autocorrectOn: Boolean = true) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        var autocorrectEnabled = autocorrectOn

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { resultCallback -> engine.apply { callback = resultCallback } },
            DirectExecutorService(),
            true,
        )

        init {
            controller.setAutocorrectGate { autocorrectEnabled }
            // The production decorator reads the locale's quote pattern from a string resource;
            // the test pins the Tatar/Russian one.
            controller.setRevertCellDecorator { typedWord -> "«$typedWord»" }
        }

        fun start() {
            controller.onStartInput(eligible = true)
        }

        fun type(text: String) {
            editor.before += text
            controller.onTextChanged()
        }

        fun separator(separator: Char) {
            controller.maybeAutocorrectBeforeSeparator(separator.code)
            editor.before += separator
            controller.onTextChanged()
        }

        fun typeWord(word: String) {
            word.forEach { type(it.toString()) }
        }

        fun tap(suggestion: String) {
            strip.listener?.onTap(suggestion)
        }

        fun advise(typed: String, replacement: String) {
            engine.adviceByWord[typed] = AutocorrectAdvice(typed, replacement, 5_000L)
        }
    }

    // --- The cell inside the window ----------------------------------------------------------------

    @Test
    fun theKeepTypedCellPaintsWhileTheWindowIsOpen() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        // While typing, no keep-typed revert cell: the band is the autocorrect preview.
        assertTrue(h.strip.bands.none { it.first() == "«китәп»" })

        h.separator(' ')

        assertEquals("китап ", h.editor.before)
        assertEquals(listOf("«китәп»", null, null), h.strip.lastBand())
    }

    @Test
    fun theCellsSpokenLabelIsTheBareTypedWord() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')

        assertEquals(listOf("«китәп»", null, null), h.strip.lastBand())
        assertEquals(listOf("китәп", null, null), h.strip.spokenLabels)
    }

    @Test
    fun tappingTheCellRevertsTheReplacement() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')

        h.tap("«китәп»")

        // Byte-for-byte what the user typed, separator included — the same undo as the backspace.
        assertEquals("китәп ", h.editor.before)
        assertEquals(
            listOf("replace:китәп->китап", "revert:китап ->китәп "),
            h.editor.edits,
        )
        // The window is consumed: a second tap on the same text is a no-op.
        h.tap("«китәп»")
        assertEquals(2, h.editor.edits.size)
        assertEquals("китәп ", h.editor.before)
    }

    @Test
    fun theCellCommitsNothingWhenTheTextMovedOn() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')
        // The application edited the field itself, without any event reaching the keyboard.
        h.editor.before = "башка текст "

        h.tap("«китәп»")

        // The undo is refused by the editor's own suffix check, and the window is gone.
        assertEquals(listOf("replace:китәп->китап"), h.editor.edits)
        assertEquals("башка текст ", h.editor.before)
    }

    // --- The window's end ----------------------------------------------------------------------------

    @Test
    fun theCellSurvivesTheSettledCursorCallbackOfItsOwnCommit() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')
        assertEquals(listOf("«китәп»", null, null), h.strip.lastBand())

        // On device the commit's cursor move posts a settled callback that re-derives the strip;
        // the cell must own the strip until the next text change.
        h.controller.onCursorMoveSettled()

        assertEquals(listOf("«китәп»", null, null), h.strip.lastBand())
    }

    @Test
    fun aFurtherKeystrokeEndsTheWindowAndRestoresTheOrdinaryBand() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.engine.suggestionsByWord["д"] = listOf("дөнья")
        h.typeWord("китәп")
        h.separator(' ')
        assertEquals(listOf("«китәп»", null, null), h.strip.lastBand())

        h.type("д")

        // The ordinary PREFIX band of the new word, and the keep-typed cell is gone. What the
        // strip shows is what a tap delivers, so the revert offer cannot be accepted anymore.
        assertEquals(listOf("дөнья", null, null), h.strip.lastBand())
        assertEquals("китап д", h.editor.before)
        assertEquals(listOf("replace:китәп->китап"), h.editor.edits)
    }

    @Test
    fun theBackspaceUndoStillWorksUnchanged() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')

        assertTrue("the window is the same one the cell paints", h.controller.maybeRevertAutocorrect())
        assertEquals("китәп ", h.editor.before)
    }

    @Test
    fun noCellWhenTheSettingIsOffAndNoCorrectionFired() {
        val h = Harness(autocorrectOn = false)
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')

        assertEquals("китәп ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
        assertTrue(h.strip.bands.none { it.first() == "«китәп»" })
    }

    @Test
    fun noCellWithoutACorrection() {
        val h = Harness()
        h.start()
        h.typeWord("китап")
        h.separator(' ')

        assertTrue(h.strip.bands.none { it.first() == "«китап»" })
    }

    @Test
    fun theFieldChangeEndsTheWindowBeforeTheCellCouldBeTapped() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')
        assertEquals(listOf("«китәп»", null, null), h.strip.lastBand())

        h.controller.onFinishInput()
        h.tap("«китәп»")

        assertEquals(listOf("replace:китәп->китап"), h.editor.edits)
    }

    @Test
    fun anUndoneWordIsNotOfferedTheCellAgainWhenTheCorrectionIsRefused() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')
        h.tap("«китәп»")
        assertEquals("китәп ", h.editor.before)

        // The refusal is remembered: retyping the word neither corrects it nor offers the cell.
        h.typeWord("китәп")
        h.separator(' ')

        assertEquals("китәп китәп ", h.editor.before)
        assertEquals(2, h.editor.edits.size)
        // Exactly one keep-typed cell was ever painted: the one the tap already consumed.
        assertEquals(1, h.strip.bands.count { it.first() == "«китәп»" })
    }
}
