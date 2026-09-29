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
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectAdvice
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * The D3 state machine as the controller runs it: when a word is replaced, when the single undo is
 * still possible, and what happens with the feature switched off.
 *
 * The editor fake below is a real little text model, and its two D3 methods mirror the guards of
 * `InputLogic.replaceTrailingWord` / `InputLogic.revertTatarAutocorrection` — the collapsed
 * selection, the cursor not inside a word, the live trailing word, the exact suffix. The production
 * methods themselves cannot be instantiated without Android; what they DO is pinned by
 * [AutocorrectSourceContractTest], and what the controller decides is pinned here.
 *
 * The harness reproduces `LatinIME.onEvent` in the order the service runs it: a backspace is offered
 * to the undo first, a separator gets its correction BEFORE it is committed, and every edit is
 * followed by the one `onTextChanged()` the service emits.
 */
class AutocorrectControllerTest {

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        val events = mutableListOf<String>()
        var listener: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?) {
            events.add("show:$first")
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
    }

    /** A text model with a collapsed cursor between [before] and [after]. */
    private class FakeEditor : EditorSurface {
        var before: String = ""
        var after: String = ""
        var hasSelection: Boolean = false
        var knownCursor: Boolean = true

        /** Every edit this surface actually performed, in order. */
        val edits = mutableListOf<String>()

        override fun cachedWordBeforeCursor(): String =
            TatarWordUtils.extractTrailingWord(before)

        override fun cachedWordBeforeTrailingWord(): String =
            TatarWordUtils.extractWordBeforeTrailingWord(before, cacheReachedTextStart = true)

        override fun hasKnownCursor(): Boolean = knownCursor

        override fun hasLetterAfterCursor(): Boolean =
            TatarWordUtils.startsWithWordCharacter(after)

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            if (!canReplace(expectedPrefix)) return false
            val committed =
                if (TatarWordUtils.needsAutoSpace(after)) "$suggestion " else suggestion
            edits.add("commit:$expectedPrefix->$committed")
            before = before.dropLast(expectedPrefix.length) + committed
            return true
        }

        override fun replaceTypedWord(expectedPrefix: String, replacement: String): Boolean {
            if (!canReplace(expectedPrefix)) return false
            edits.add("replace:$expectedPrefix->$replacement")
            before = before.dropLast(expectedPrefix.length) + replacement
            return true
        }

        override fun revertTypedWord(
            insertedForm: String,
            separator: String,
            typedForm: String,
        ): Boolean {
            if (hasSelection) return false
            if (TatarWordUtils.startsWithWordCharacter(after)) return false
            val inserted = insertedForm + separator
            if (!before.endsWith(inserted)) return false
            edits.add("revert:$inserted->$typedForm$separator")
            before = before.dropLast(inserted.length) + typedForm + separator
            return true
        }

        private fun canReplace(expectedPrefix: String): Boolean {
            if (hasSelection) return false
            if (TatarWordUtils.startsWithWordCharacter(after)) return false
            return cachedWordBeforeCursor() == expectedPrefix
        }
    }

    private class FakeEngine : EngineHandle {
        val adviceByWord = mutableMapOf<String, AutocorrectAdvice>()
        var lastLookedUp: String = ""
        var answersLookups: Boolean = true

        override fun request(
            editorSessionId: Long,
            subtypeId: String,
            prefixUtf8: ByteArray,
        ): Any? {
            if (answersLookups) lastLookedUp = String(prefixUtf8, Charsets.UTF_8)
            return TOKEN
        }

        override fun isCurrent(token: Any): Boolean = true

        override fun finishInput() = Unit

        override fun updateKeyNeighbors(table: KeyNeighborTable?) = Unit

        /** Mirrors the engine: the verdict belongs to the NEWEST completed lookup, and to it only. */
        override fun autocorrectAdvice(): AutocorrectAdvice? = adviceByWord[lastLookedUp]

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

    /** Records what E4c would have been told, so "an autocorrection teaches nothing" is checkable. */
    private class RecordingSink : WordCompletionSink {
        val completions = mutableListOf<String>()

        override fun onCleanCompletion(word: String) {
            completions.add(word)
        }

        override fun onInputFinished() = Unit
    }

    /** Records what the P1 pair machine would have been told, so pair context is checkable. */
    private class RecordingPairSink : PairCompletionSink {
        val pairs = mutableListOf<Pair<String, String>>()

        override fun onCleanPairCompletion(contextWord: String, completedWord: String) {
            pairs.add(contextWord to completedWord)
        }
    }

    private class Harness(autocorrectOn: Boolean = true, wireAutocorrect: Boolean = true) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        val sink = RecordingSink()
        val pairSink = RecordingPairSink()
        var autocorrectEnabled = autocorrectOn

        /** False reproduces a build that never calls the D3 entry points at all. */
        private val wired = wireAutocorrect

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { engine },
            DirectExecutorService(),
            true,
        )

        init {
            controller.setCompletionSink(sink)
            controller.setPairCompletionSink(pairSink)
            controller.setAutocorrectGate { autocorrectEnabled }
        }

        fun start() {
            controller.onStartInput(eligible = true)
        }

        /** One ordinary character: committed by the input logic, then the single onTextChanged(). */
        fun type(text: String) {
            editor.before += text
            controller.onTextChanged()
        }

        /** A word separator, in the exact order `LatinIME.onEvent` runs it. */
        fun separator(separator: Char) {
            if (wired) controller.maybeAutocorrectBeforeSeparator(separator.code)
            editor.before += separator
            controller.onTextChanged()
        }

        /** A backspace, in the exact order `LatinIME.onEvent` runs it. */
        fun backspace() {
            if (wired && controller.maybeRevertAutocorrect()) {
                controller.onTextChanged()
                return
            }
            editor.before = editor.before.dropLast(1)
            controller.onTextChanged()
        }

        /** Types [word] one code point at a time, as a user does. */
        fun typeWord(word: String) {
            word.forEach { type(it.toString()) }
        }

        fun advise(typed: String, replacement: String, frequency: Long = 5_000L) {
            engine.adviceByWord[typed] = AutocorrectAdvice(typed, replacement, frequency)
        }
    }

    // --- The single insertion path ---------------------------------------------------------------

    @Test
    fun autocorrectInsertsThroughTheSameSingleCommitAsAnAcceptedSuggestion() {
        // The correction: one edit, then the separator the user pressed.
        val corrected = Harness()
        corrected.start()
        corrected.advise("китәп", "китап")
        corrected.typeWord("китәп")
        corrected.separator(' ')

        // The same candidate, accepted by tapping it instead.
        val tapped = Harness()
        tapped.start()
        tapped.typeWord("китәп")
        assertTrue(tapped.editor.commitSuggestion("китәп", "китап"))

        // ONE editor edit performed the replacement — no second commit, no intermediate state.
        assertEquals(listOf("replace:китәп->китап"), corrected.editor.edits)
        // And the text it leaves behind is byte-for-byte what the accepted suggestion leaves: the
        // word plus one separator. The auto-space of the tap path is the separator the user typed.
        assertEquals(tapped.editor.before, corrected.editor.before)
        assertEquals("китап ", corrected.editor.before)
    }

    @Test
    fun theUsersCapitalizationIsCarriedOntoTheReplacement() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")

        h.typeWord("Китәп")
        h.separator(' ')

        assertEquals("Китап ", h.editor.before)
    }

    @Test
    fun aMixedCaseWordIsNeverAutocorrected() {
        // Mixed case has no defined display form in the frozen contract (0 results), so it has no
        // defined replacement form either.
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")

        h.typeWord("киТәп")
        h.separator(' ')

        assertEquals("киТәп ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun aVerdictComputedForADifferentWordIsNeverApplied() {
        // Coalescing: the lookup for the finished word never ran, so the newest verdict belongs to a
        // shorter prefix. Nothing is replaced — fail-closed, exactly like the E4c clean-run filter.
        val h = Harness()
        h.start()
        h.advise("китә", "китап")
        h.typeWord("китә")
        h.engine.answersLookups = false
        h.type("п")

        h.separator(' ')

        assertEquals("китәп ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun aCandidateBelowTheFrequencyThresholdIsRefusedByTheControllerToo() {
        // The engine already applies the threshold; the controller re-checks it, because one side of
        // a two-sided decision must not be the only place a rule lives. 410 is one below
        // MIN_CANDIDATE_FREQUENCY (411 since the 2026-09-20 P2 rebuild).
        val h = Harness()
        h.start()
        h.advise("китәп", "китап", frequency = 410L)

        h.typeWord("китәп")
        h.separator(' ')

        assertEquals("китәп ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun aWordShorterThanTheMinimumIsRefusedByTheControllerToo() {
        val h = Harness()
        h.start()
        h.advise("бал", "бәл")

        h.typeWord("бал")
        h.separator(' ')

        assertEquals("бал ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun inAFieldWithoutSuggestionsNoReplacementEverHappens() {
        // A password field, a field asking for no personalized learning, or any field that suppresses
        // suggestions arrives here as "not eligible": the whole phase hangs off that one flag.
        val h = Harness()
        h.controller.onStartInput(eligible = false)
        h.advise("китәп", "китап")

        h.typeWord("китәп")
        h.separator(' ')

        assertEquals("китәп ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun theReplacedWordTeachesNothing() {
        // "Автозамена не учит": the run is marked dirty exactly as an accepted suggestion marks it,
        // so neither the corrected word nor what the user typed reaches the personal dictionary.
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")

        h.typeWord("китәп")
        h.separator(' ')

        assertTrue(h.sink.completions.isEmpty())
    }

    @Test
    fun aCorrectedWordStillBecomesPairContextForTheNextCleanWord() {
        // 2026-09-24 audit, finding 7: the replacement re-arms the pair boundary exactly like an
        // accepted suggestion does — without it the run machine's no-change early return would
        // keep the pair machine dirty past the separator, and the corrected word could never be a
        // pair's context half.
        val h = Harness()
        h.start()
        h.typeWord("баш")
        h.separator(' ')
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')
        assertEquals("баш китап ", h.editor.before)

        h.typeWord("дөнья")
        h.separator(' ')

        assertEquals(listOf("китап" to "дөнья"), h.pairSink.pairs)
        // The words machine's half is untouched: the correction still teaches nothing.
        assertTrue(h.sink.completions.isEmpty())
    }

    // --- The single undo -------------------------------------------------------------------------

    @Test
    fun backspaceRightAfterAReplacementRestoresTheTypedForm() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')
        assertEquals("китап ", h.editor.before)

        h.backspace()

        // Byte-for-byte what the user typed, separator included.
        assertEquals("китәп ", h.editor.before)
        assertEquals(listOf("replace:китәп->китап", "revert:китап ->китәп "), h.editor.edits)

        // The second backspace deletes a character; it does not repeat the undo.
        h.backspace()
        assertEquals("китәп", h.editor.before)
        assertEquals(2, h.editor.edits.size)
    }

    @Test
    fun theUndoAlsoWorksAfterAPunctuationSeparator() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(',')
        assertEquals("китап,", h.editor.before)

        h.backspace()

        assertEquals("китәп,", h.editor.before)
    }

    @Test
    fun anUndoIsRefusedWhenTheTextBeforeTheCursorIsNoLongerWhatWasInserted() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')
        // The application edited the field itself, without any event reaching the keyboard.
        h.editor.before = "башка текст "

        h.backspace()

        // No edit, and the state is gone: the backspace fell through to the ordinary path.
        assertEquals("башка текст", h.editor.before)
        assertEquals(listOf("replace:китәп->китап"), h.editor.edits)
    }

    @Test
    fun anySixthEventBetweenReplacementAndBackspaceMakesRevertImpossible() {
        // The six events the contract names, one case each. Two of them — an external selection
        // change and an internal cursor gesture — reach the controller through the same entry point,
        // which is why they are listed twice here and behave identically.
        assertRevertImpossibleAfter("typed character") { h -> h.type("а") }
        assertRevertImpossibleAfter("selection change") { h -> h.controller.onSelectionChanged() }
        assertRevertImpossibleAfter("cursor gesture") { h -> h.controller.onSelectionChanged() }
        assertRevertImpossibleAfter("accepted suggestion") { h ->
            h.strip.listener?.onTap("сүзләр")
        }
        assertRevertImpossibleAfter("subtype change") { h -> h.controller.onSubtypeChanged(true) }
        assertRevertImpossibleAfter("field change") { h -> h.controller.onStartInput(true) }
    }

    private fun assertRevertImpossibleAfter(name: String, event: (Harness) -> Unit) {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')
        assertEquals("китап ", h.editor.before)
        val editsAfterReplacement = h.editor.edits.size

        event(h)
        val editsAfterEvent = h.editor.edits.size
        h.backspace()

        assertFalse(
            "$name must make the revert impossible",
            h.editor.edits.drop(editsAfterEvent).any { it.startsWith("revert:") },
        )
        // And the backspace behaved like any other backspace: it deleted one character.
        assertTrue(
            "$name must leave an ordinary backspace behind",
            h.editor.edits.size == editsAfterEvent,
        )
        assertTrue(editsAfterReplacement >= 1)
    }

    @Test
    fun theUndoWindowDoesNotSurviveTheEndOfTheEditorSession() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')

        h.controller.onFinishInput()
        h.controller.onStartInput(eligible = true)
        h.backspace()

        assertEquals(listOf("replace:китәп->китап"), h.editor.edits)
    }

    @Test
    fun turningTheSettingOffBetweenTheReplacementAndTheBackspaceClosesTheWindow() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')

        h.autocorrectEnabled = false
        h.backspace()

        assertEquals(listOf("replace:китәп->китап"), h.editor.edits)
        assertEquals("китап", h.editor.before)
    }

    // --- Fail-closed acceptance ------------------------------------------------------------------

    @Test
    fun withAutocorrectOffTheResultIsByteForByteTheResultBeforeIt() {
        // Left: the shipped build with the setting off, D3 entry points called on every separator
        // and every backspace exactly as LatinIME calls them.
        val off = Harness(autocorrectOn = false)
        // Right: a build that has no D3 at all — the entry points are never reached.
        val without = Harness(autocorrectOn = false, wireAutocorrect = false)

        for (h in listOf(off, without)) {
            h.start()
            h.advise("китәп", "китап")
            h.typeWord("китәп")
            h.separator(' ')
            h.typeWord("бала")
            h.separator(',')
            h.backspace()
            h.backspace()
        }

        assertEquals(without.editor.before, off.editor.before)
        assertEquals(without.editor.edits, off.editor.edits)
        assertEquals(without.strip.events, off.strip.events)
        // Nothing was edited by this phase at all, and the text is what plain typing produces.
        assertTrue(off.editor.edits.isEmpty())
        assertEquals("китәп бал", off.editor.before)
    }

    // --- Which separators fire at all ------------------------------------------------------------

    @Test
    fun onlySpaceAndPunctuationAreAutocorrectSeparators() {
        assertTrue(TatarWordUtils.isAutocorrectSeparator(' '.code))
        // The non-breaking space separates a word just as well as a plain one; written as an
        // escape because an invisible literal in a source file is a trap.
        assertTrue(TatarWordUtils.isAutocorrectSeparator('\u00A0'.code))
        assertTrue(TatarWordUtils.isAutocorrectSeparator(','.code))
        assertTrue(TatarWordUtils.isAutocorrectSeparator('.'.code))
        assertTrue(TatarWordUtils.isAutocorrectSeparator('!'.code))
        assertTrue(TatarWordUtils.isAutocorrectSeparator('—'.code))
        assertTrue(TatarWordUtils.isAutocorrectSeparator('«'.code))
        // Enter and Tab are word separators, and deliberately NOT autocorrect separators: Enter may
        // perform an editor action instead of committing anything.
        assertFalse(TatarWordUtils.isAutocorrectSeparator('\n'.code))
        assertFalse(TatarWordUtils.isAutocorrectSeparator('\t'.code))
        // Letters, digits and math symbols end nothing.
        assertFalse(TatarWordUtils.isAutocorrectSeparator('а'.code))
        assertFalse(TatarWordUtils.isAutocorrectSeparator('7'.code))
        assertFalse(TatarWordUtils.isAutocorrectSeparator('+'.code))
    }
}
