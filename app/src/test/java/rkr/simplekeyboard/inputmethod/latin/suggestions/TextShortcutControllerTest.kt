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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TextShortcutSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * The text-shortcut state machine as the controller runs it: the expansion offered while the
 * shortcut is the typed word, the separator-time commit through the replacement path, the undo
 * window covering it, the refusal after an undo, and the gates (no expansion where suggestions may
 * not run, no learning from an expansion).
 *
 * The harness reproduces `LatinIME.onEvent` in the order the service runs it, exactly like
 * [AutocorrectControllerTest]: the expansion BEFORE the separator is committed, the backspace undo
 * first, the one `onTextChanged()` after every edit.
 */
class TextShortcutControllerTest {

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        /** One painted band: the three cells. */
        val bands = mutableListOf<List<String?>>()

        /** The first cell as visible right now: a reserve or a hide clears it, like the view. */
        var shownFirst: String? = null
            private set
        var listener: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?) {
            bands.add(listOf(first, second, third))
            shownFirst = first
        }

        override fun reserve() {
            shownFirst = null
        }

        override fun hideSuggestions() {
            shownFirst = null
        }

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

        override fun cachedWordBeforeTrailingWord(): String =
            TatarWordUtils.extractWordBeforeTrailingWord(before, cacheReachedTextStart = true)

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

    /** Delivers every result synchronously; holds no verdicts unless the test pins one. */
    private class FakeEngine : EngineHandle {
        val suggestionsByWord = mutableMapOf<String, List<String>>()
        var advice: AutocorrectAdvice? = null
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

        override fun autocorrectAdvice(): AutocorrectAdvice? = advice

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

    /** Records what learning was told, so "an expansion teaches nothing" is checkable. */
    private class RecordingSink : WordCompletionSink {
        val completions = mutableListOf<String>()
        override fun onCleanCompletion(word: String) {
            completions.add(word)
        }

        override fun onInputFinished() = Unit
    }

    /** Records pair completions, so the expansion's boundary behavior is checkable. */
    private class RecordingPairSink : PairCompletionSink {
        val pairs = mutableListOf<Pair<String, String>>()
        override fun onCleanPairCompletion(contextWord: String, completedWord: String) {
            pairs.add(contextWord to completedWord)
        }
    }

    private class Harness {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        val sink = RecordingSink()
        val pairSink = RecordingPairSink()
        val shortcuts = mutableMapOf<String, String>()

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { resultCallback -> engine.apply { callback = resultCallback } },
            DirectExecutorService(),
            true,
        )

        init {
            controller.setCompletionSink(sink)
            controller.setPairCompletionSink(pairSink)
            controller.setShortcutSource(TextShortcutSource { word -> shortcuts[word] })
        }

        fun start(eligible: Boolean = true) {
            controller.onStartInput(eligible = eligible)
        }

        /** One ordinary character: committed by the input logic, then the single onTextChanged(). */
        fun type(text: String) {
            editor.before += text
            controller.onTextChanged()
        }

        /** A word separator, in the exact order `LatinIME.onEvent` runs it. */
        fun separator(separator: Char) {
            if (!controller.maybeExpandShortcutBeforeSeparator(separator.code)) {
                controller.maybeAutocorrectBeforeSeparator(separator.code)
            }
            editor.before += separator
            controller.onTextChanged()
        }

        /** A backspace, in the exact order `LatinIME.onEvent` runs it. */
        fun backspace() {
            if (controller.maybeRevertAutocorrect()) {
                controller.onTextChanged()
                return
            }
            editor.before = editor.before.dropLast(1)
            controller.onTextChanged()
        }

        fun typeWord(word: String) {
            word.forEach { type(it.toString()) }
        }

        fun tap(suggestion: String) {
            strip.listener?.onTap(suggestion)
        }
    }

    // --- The offer --------------------------------------------------------------------------------

    @Test
    fun theExpansionIsOfferedInTheFirstCellWhileTheShortcutIsTyped() {
        val h = Harness()
        h.start()
        h.shortcuts["тк"] = "Татарстан Республикасы"
        h.engine.suggestionsByWord["тк"] = listOf("ткы")

        h.typeWord("тк")

        assertEquals(listOf("Татарстан Республикасы", "ткы", null), h.strip.lastBand())
    }

    @Test
    fun theOfferAppearsEvenWhenTheDictionaryHasNoCompletion() {
        val h = Harness()
        h.start()
        h.shortcuts["мм"] = "мәхәббәт"

        h.typeWord("мм")

        assertEquals(listOf("мәхәббәт", null, null), h.strip.lastBand())
    }

    @Test
    fun noOfferForAWordThatIsNoShortcut() {
        val h = Harness()
        h.start()
        h.shortcuts["тк"] = "Татарстан"

        h.typeWord("тка")

        // The word grew past the shortcut: the strip no longer offers the expansion.
        assertTrue(h.strip.shownFirst != "Татарстан")
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun theMatchIsExactCasingIncluded() {
        val h = Harness()
        h.start()
        h.shortcuts["тк"] = "Татарстан"

        h.typeWord("Тк")

        // No offer: "Тк" is not the saved shortcut. A band of plain completions may exist, but
        // the first cell is never the expansion.
        assertTrue(h.strip.lastBand()?.first() != "Татарстан")
    }

    // --- The commit and its undo -------------------------------------------------------------------

    @Test
    fun theSeparatorCommitsTheExpansionThroughTheReplacementPath() {
        val h = Harness()
        h.start()
        h.shortcuts["тк"] = "Татарстан Республикасы"

        h.typeWord("тк")
        h.separator(' ')

        assertEquals(listOf("replace:тк->Татарстан Республикасы"), h.editor.edits)
        assertEquals("Татарстан Республикасы ", h.editor.before)
    }

    @Test
    fun theUndoWindowCoversTheExpansionAndRestoresTheShortcut() {
        val h = Harness()
        h.start()
        h.shortcuts["тк"] = "Татарстан Республикасы"
        h.typeWord("тк")
        h.separator(' ')
        assertEquals("Татарстан Республикасы ", h.editor.before)

        h.backspace()

        assertEquals("тк ", h.editor.before)
        assertEquals(
            listOf(
                "replace:тк->Татарстан Республикасы",
                "revert:Татарстан Республикасы ->тк ",
            ),
            h.editor.edits,
        )
        // The second backspace deletes a character; it does not repeat the undo.
        h.backspace()
        assertEquals("тк", h.editor.before)
        assertEquals(2, h.editor.edits.size)
    }

    @Test
    fun anUndoneExpansionIsNotRefiredInTheSameSession() {
        val h = Harness()
        h.start()
        h.shortcuts["тк"] = "Татарстан"
        // Ordinary completions exist for the word, so a band really paints after the undo; it must
        // not carry the expansion.
        h.engine.suggestionsByWord["тк"] = listOf("ткы")
        h.typeWord("тк")
        h.separator(' ')
        h.backspace()
        assertEquals("тк ", h.editor.before)
        val bandsAfterUndo = h.strip.bands.size

        // Retype the shortcut and commit it again: the refusal is remembered, the offer is gone
        // and the separator leaves the word as typed.
        h.typeWord("тк")
        assertEquals("the offer is suppressed after the undo",
            listOf("ткы", null, null), h.strip.bands.drop(bandsAfterUndo).lastOrNull())
        h.separator(' ')

        assertEquals("тк тк ", h.editor.before)
        assertEquals(1, h.editor.edits.count { it.startsWith("replace:") })
    }

    @Test
    fun tappingTheExpansionCellCommitsItLikeAnAcceptedSuggestion() {
        val h = Harness()
        h.start()
        h.shortcuts["тк"] = "Татарстан"
        h.typeWord("тк")

        h.tap("Татарстан")

        // The accepted-suggestion path, auto-space included — and no undo window is armed: the
        // next backspace deletes a character.
        assertEquals(listOf("commit:тк->Татарстан "), h.editor.edits)
        assertEquals("Татарстан ", h.editor.before)
        h.backspace()
        assertEquals("Татарстан", h.editor.before)
        assertEquals(1, h.editor.edits.size)
    }

    @Test
    fun theShortcutOutranksTheAutocorrectVerdict() {
        val h = Harness()
        h.start()
        h.shortcuts["китәп"] = "китап кибете"
        h.engine.advice = AutocorrectAdvice("китәп", "китап", 100_000L)

        h.typeWord("китәп")
        // The strip offers the expansion, not the correction preview's emphasis pair.
        assertEquals("китап кибете", h.strip.lastBand()?.first())

        h.separator(' ')
        assertEquals(listOf("replace:китәп->китап кибете"), h.editor.edits)
        assertEquals("китап кибете ", h.editor.before)
    }

    // --- The gates ---------------------------------------------------------------------------------

    @Test
    fun aFieldWhereSuggestionsMayNotRunSeesNeitherTheOfferNorTheExpansion() {
        // A password, numeric, incognito or lock-screen field arrives here as "not eligible": the
        // whole feature hangs off that one flag.
        val h = Harness()
        h.start(eligible = false)
        h.shortcuts["тк"] = "Татарстан"

        h.typeWord("тк")
        assertTrue(h.strip.bands.isEmpty())
        h.separator(' ')

        assertEquals("тк ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun theExpansionTeachesNeitherTheShortcutNorItsWords() {
        // Control: the same typing without the pair — the second word is cleanly completed and
        // learned (the session's first word is the witness boundary and is not seen from its
        // start).
        val control = Harness()
        control.start()
        control.typeWord("баш")
        control.separator(' ')
        control.typeWord("тк")
        control.separator(' ')
        assertEquals(listOf("тк"), control.sink.completions)

        val h = Harness()
        h.start()
        h.typeWord("баш")
        h.separator(' ')
        h.shortcuts["тк"] = "Татарстан"
        h.typeWord("тк")
        h.separator(' ')
        assertEquals("баш Татарстан ", h.editor.before)

        // The shortcut is never a completion. The expansion's boundary is trusted for pairs,
        // exactly as after a correction: the next cleanly typed word pairs with the expansion's
        // last word, and (as after any replacement) is not learned itself.
        h.typeWord("дөнья")
        h.separator(' ')

        assertTrue(h.sink.completions.isEmpty())
        assertEquals(listOf("Татарстан" to "дөнья"), h.pairSink.pairs)
    }

    @Test
    fun noExpansionWithoutASeparator() {
        val h = Harness()
        h.start()
        h.shortcuts["тк"] = "Татарстан"

        h.typeWord("тк")
        // The strip offers, but nothing is committed while no separator arrives.
        assertEquals("Татарстан", h.strip.lastBand()?.first())
        assertTrue(h.editor.edits.isEmpty())
        assertEquals("тк", h.editor.before)
    }

    @Test
    fun aShortcutWithNoSourceWiredIsPlainTyping() {
        val h = HarnessWithoutShortcuts()
        h.start()

        h.typeWord("тк")
        h.separator(' ')

        assertEquals("тк ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    /** A harness with no shortcut source at all, as in a build where the feature is not wired. */
    private class HarnessWithoutShortcuts {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { resultCallback -> engine.apply { callback = resultCallback } },
            DirectExecutorService(),
            true,
        )

        fun start() {
            controller.onStartInput(eligible = true)
        }

        fun typeWord(word: String) {
            word.forEach {
                editor.before += it.toString()
                controller.onTextChanged()
            }
        }

        fun separator(separator: Char) {
            if (!controller.maybeExpandShortcutBeforeSeparator(separator.code)) {
                controller.maybeAutocorrectBeforeSeparator(separator.code)
            }
            editor.before += separator
            controller.onTextChanged()
        }
    }
}
