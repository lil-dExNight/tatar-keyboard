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
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectAdvice
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * The P2 state machine of ROADMAP Phase 3 (docs/ROADMAP-P3.md): the autocorrect preview the
 * strip shows WHILE the user types, the keep-typed refusal, and the way the separator-time
 * replacement (D3, pinned in [AutocorrectControllerTest]) composes with it.
 *
 * Unlike the D3 harness, whose fake engine never answers a lookup, this one delivers every
 * result synchronously — the preview lives in the result path, so the band must actually be
 * painted here. The editor fake is the same little text model, and the harness reproduces
 * `LatinIME.onEvent` in the order the service runs it: the separator's correction BEFORE the
 * separator is committed, the backspace undo first, the one `onTextChanged()` after every edit.
 */
class AutocorrectPreviewControllerTest {

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        /** One painted band: the three cells plus the emphasized cell index (NO_CELL = plain). */
        class Band(val cells: List<String?>, val emphasized: Int)

        val bands = mutableListOf<Band>()
        var listener: SuggestionTapListener? = null

        /** The marker the last publication left — any new band, reserve or hide resets it. */
        var currentEmphasis = SuggestionStripState.NO_CELL
            private set

        override fun showSuggestions(first: String, second: String?, third: String?) {
            currentEmphasis = SuggestionStripState.NO_CELL
            bands.add(Band(listOf(first, second, third), SuggestionStripState.NO_CELL))
        }

        override fun setEmphasizedCell(cell: Int) {
            currentEmphasis = cell
            if (bands.isNotEmpty()) {
                bands[bands.lastIndex] = Band(bands.last().cells, cell)
            }
        }

        override fun reserve() {
            currentEmphasis = SuggestionStripState.NO_CELL
        }

        override fun hideSuggestions() {
            currentEmphasis = SuggestionStripState.NO_CELL
        }

        override fun setTapListener(listener: SuggestionTapListener) {
            this.listener = listener
        }

        fun lastBand(): Band? = bands.lastOrNull()
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

        override fun cachedNextWordContext(): String =
            // The fake's "cache" always holds the whole field, so provenance is always provable.
            TatarWordUtils.extractNextWordContext(before, cacheReachedTextStart = true)

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

    /**
     * Delivers every result synchronously, exactly as the D3 fake deliberately does NOT: the
     * preview lives in the result path, so the band must be painted here. The verdict mirror is
     * unchanged — it belongs to the NEWEST completed PREFIX lookup, and to it only.
     */
    private class FakeEngine : EngineHandle {
        val adviceByWord = mutableMapOf<String, AutocorrectAdvice>()
        val suggestionsByWord = mutableMapOf<String, List<String>>()
        val nextWordByContext = mutableMapOf<String, List<String>>()
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
            // A NEXT_WORD lookup never moves the verdict — the D3 advice belongs to the newest
            // PREFIX lookup, exactly like CompositePrefixComputer.predict never touches it.
            val context = String(contextWordUtf8, Charsets.UTF_8)
            val token = Any()
            callback?.onResult(
                token, nextWordByContext[context] ?: emptyList(), LookupKind.NEXT_WORD,
            )
            return token
        }

        override fun isCurrent(token: Any): Boolean = true

        override fun finishInput() = Unit

        override fun updateKeyNeighbors(table: KeyNeighborTable?) = Unit

        override fun autocorrectAdvice(): AutocorrectAdvice? = forcedAdvice ?: adviceByWord[lastPrefix]

        /** A verdict pinned to the past: returned regardless of the newest lookup, like a
         * coalesced one. */
        var forcedAdvice: AutocorrectAdvice? = null

        override fun destroy(timeoutMs: Long): Boolean = true
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

    /** Records what E4c would have been told, so "a refused correction still learns" is checkable. */
    private class RecordingSink : WordCompletionSink {
        val completions = mutableListOf<String>()

        override fun onCleanCompletion(word: String) {
            completions.add(word)
        }

        override fun onInputFinished() = Unit
    }

    private class Harness(autocorrectOn: Boolean = true) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        val sink = RecordingSink()
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
            controller.setCompletionSink(sink)
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
            controller.maybeAutocorrectBeforeSeparator(separator.code)
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

        /** Types [word] one code point at a time, as a user does. */
        fun typeWord(word: String) {
            word.forEach { type(it.toString()) }
        }

        fun tap(suggestion: String) {
            strip.listener?.onTap(suggestion)
        }

        fun advise(typed: String, replacement: String, frequency: Long = 5_000L) {
            engine.adviceByWord[typed] = AutocorrectAdvice(typed, replacement, frequency)
        }
    }

    // --- When the preview appears ----------------------------------------------------------------

    @Test
    fun thePreviewAppearsWhileTypingExactlyWhenTheSeparatorPolicyWouldFire() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")

        h.typeWord("китәп")

        // The typed word leads, the correction follows, emphasized; the third cell stays empty.
        val band = h.strip.lastBand()!!
        assertEquals(listOf("китәп", "китап", null), band.cells)
        assertEquals(1, band.emphasized)
    }

    @Test
    fun aDictionaryWordShowsItsOrdinarySuggestionsInstead() {
        // The engine never advises a word people write; the fake mirrors that by simply holding
        // no verdict for it. The band must stay the plain ranked list — no typed-word cell, no
        // emphasis.
        val h = Harness()
        h.start()
        h.engine.suggestionsByWord["китап"] = listOf("китаплар", "китабы")

        h.typeWord("китап")

        val band = h.strip.lastBand()!!
        assertEquals(listOf("китаплар", "китабы", null), band.cells)
        assertEquals(SuggestionStripState.NO_CELL, band.emphasized)
        h.separator(' ')
        assertEquals("китап ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun aNonDictionaryWordWithoutAnAdviceKeepsTheReservedEmptyBand() {
        val h = Harness()
        h.start()

        h.typeWord("укыб")

        assertNull(h.strip.lastBand())
        assertEquals(SuggestionStripState.NO_CELL, h.strip.currentEmphasis)
        h.separator(' ')
        assertEquals("укыб ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun aWordShorterThanTheMinimumShowsNoPreview() {
        val h = Harness()
        h.start()
        h.advise("бал", "бәл")

        h.typeWord("бал")

        assertNull(h.strip.lastBand())
        assertEquals(SuggestionStripState.NO_CELL, h.strip.currentEmphasis)
    }

    @Test
    fun aCandidateBelowTheFrequencyFloorShowsNoPreview() {
        // 410 is one below MIN_CANDIDATE_FREQUENCY (411): the controller re-checks the floor for
        // the display exactly like the separator path re-checks it for the edit.
        val h = Harness()
        h.start()
        h.advise("китәп", "китап", frequency = 410L)

        h.typeWord("китәп")

        assertNull(h.strip.lastBand())
        assertEquals(SuggestionStripState.NO_CELL, h.strip.currentEmphasis)
    }

    @Test
    fun aVerdictComputedForADifferentWordPaintsNoPreview() {
        // Coalescing: the newest lookup never ran for the word that stands now, so the verdict
        // still names an older, shorter prefix. The provenance check refuses it for the display
        // exactly like the separator path refuses it for the edit.
        val h = Harness()
        h.start()
        h.advise("китә", "китап")
        h.typeWord("китә")
        h.engine.forcedAdvice = h.engine.adviceByWord["китә"]

        h.type("п")

        // The stale verdict is not announced for "китәп": the marker is gone, and the separator
        // then does nothing at all — the D3 provenance check, mirrored by the preview's.
        assertEquals(SuggestionStripState.NO_CELL, h.strip.currentEmphasis)
        h.separator(' ')
        assertEquals("китәп ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun theUsersCapitalizationIsCarriedOntoThePreviewedCorrection() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")

        h.typeWord("Китәп")

        val band = h.strip.lastBand()!!
        assertEquals(listOf("Китәп", "Китап", null), band.cells)
        assertEquals(1, band.emphasized)
    }

    @Test
    fun thePreviewFollowsTheWordAndVanishesTheKeystrokeItMovesPastTheAdvice() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        assertEquals(1, h.strip.currentEmphasis)

        h.type("а")

        // "китәпа" has no verdict: the band is the plain reserved one again, and no marker is
        // left over from the word before.
        assertEquals(SuggestionStripState.NO_CELL, h.strip.currentEmphasis)
    }

    // --- The two tappable cells ------------------------------------------------------------------

    @Test
    fun tappingTheCorrectionCellCommitsItLikeAnAcceptedSuggestion() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")

        h.tap("китап")

        // The same single commit an ordinary suggestion tap performs, auto-space included, and
        // the emphasized cell is the string that was inserted.
        assertEquals(listOf("commit:китәп->китап "), h.editor.edits)
        assertEquals("китап ", h.editor.before)
    }

    @Test
    fun tappingTheKeepTypedCellSuppressesTheAdviceAndRestoresThePlainBand() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.engine.suggestionsByWord["китәп"] = listOf("китәпләр")
        h.typeWord("китәп")
        assertEquals(1, h.strip.currentEmphasis)

        h.tap("китәп")

        // Nothing was committed: a refusal is not an edit.
        assertTrue(h.editor.edits.isEmpty())
        assertEquals("китәп", h.editor.before)
        // The band immediately falls back to this word's ordinary suggestions, with no marker.
        val band = h.strip.lastBand()!!
        assertEquals(listOf("китәпләр", null, null), band.cells)
        assertEquals(SuggestionStripState.NO_CELL, band.emphasized)
        // And the separator now commits the typed word as-is.
        h.separator(' ')
        assertEquals("китәп ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun aKeepTypedRefusalIsNotAnAcceptedSuggestionSoTheWordMayStillBeLearned() {
        // The user spelled every letter themselves and then kept the spelling: the clean run
        // survives the tap, so an unknown word reaches the personal dictionary exactly as it
        // would with autocorrect off. A correction, by contrast, teaches nothing (D3).
        val h = Harness()
        h.start()
        // E4c sacrifices the session's first word by contract (it was not seen from its start);
        // witness a boundary so the word below is a run of its own.
        h.typeWord("баш")
        h.separator(' ')
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.tap("китәп")

        h.separator(' ')

        assertEquals(listOf("китәп"), h.sink.completions)
    }

    @Test
    fun theSuppressionIsConsumedByTheFirstSeparatorAndDiesWithItsWord() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.tap("китәп")
        h.separator(' ')
        assertEquals("китәп ", h.editor.before)

        // The same word typed again is a new occurrence: the preview returns, and the separator
        // corrects again.
        h.typeWord("китәп")
        val band = h.strip.lastBand()!!
        assertEquals(listOf("китәп", "китап", null), band.cells)
        assertEquals(1, band.emphasized)
        h.separator(' ')
        assertEquals("китәп китап ", h.editor.before)
        assertEquals(listOf("replace:китәп->китап"), h.editor.edits)
    }

    @Test
    fun aTapForAnAlreadyGonePreviewIsANoOp() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.type("а")

        // The strip no longer shows the preview: a late tap naming its keep-typed cell must do
        // nothing and must NOT suppress anything either.
        h.tap("китәп")

        assertTrue(h.editor.edits.isEmpty())
        h.backspace()
        // The preview is back for the same word — the stale tap armed no refusal.
        assertEquals(1, h.strip.currentEmphasis)
        h.separator(' ')
        assertEquals("китап ", h.editor.before)
        assertEquals(listOf("replace:китәп->китап"), h.editor.edits)
    }

    // --- Composition with the separator-time replacement -----------------------------------------

    @Test
    fun theSeparatorStillAppliesExactlyTheEmphasizedCell() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        val emphasized = h.strip.lastBand()!!.cells[1]!!

        h.separator(' ')

        assertEquals(listOf("replace:китәп->$emphasized"), h.editor.edits)
        assertEquals("$emphasized ", h.editor.before)
    }

    @Test
    fun backspaceRightAfterAPreviewDrivenCorrectionStillReverts() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.typeWord("китәп")
        h.separator(' ')
        assertEquals("китап ", h.editor.before)

        h.backspace()

        // Byte-for-byte what the user typed, separator included — the D3 undo, unchanged.
        assertEquals("китәп ", h.editor.before)
        assertEquals(
            listOf("replace:китәп->китап", "revert:китап ->китәп "),
            h.editor.edits,
        )
        assertEquals(SuggestionStripState.NO_CELL, h.strip.currentEmphasis)

        // Delete the space: the word is back under the cursor, and so is the coming
        // correction — the verdict still names exactly this word.
        h.backspace()
        assertEquals("китәп", h.editor.before)
        val band = h.strip.lastBand()!!
        assertEquals(listOf("китәп", "китап", null), band.cells)
        assertEquals(1, band.emphasized)

        // The refusal path works from here: keep-typed, then the separator commits as-is.
        h.tap("китәп")
        h.separator('.')
        assertEquals("китәп.", h.editor.before)
        assertEquals(2, h.editor.edits.size)
    }

    @Test
    fun thePreviewNeverRidesANextWordBand() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")
        h.engine.nextWordByContext["китап"] = listOf("дөнья")
        h.typeWord("китәп")

        h.separator(' ')

        // After the correction the band belongs to the NEXT_WORD slot again — plain, unmarked.
        val band = h.strip.lastBand()!!
        assertEquals(listOf("дөнья", null, null), band.cells)
        assertEquals(SuggestionStripState.NO_CELL, band.emphasized)
    }

    // --- Fail-closed -----------------------------------------------------------------------------

    @Test
    fun withTheToggleOffNoPreviewEverAppearsAndNothingIsCorrected() {
        val h = Harness(autocorrectOn = false)
        h.start()
        h.advise("китәп", "китап")
        h.engine.suggestionsByWord["китәп"] = listOf("китәпләр")

        h.typeWord("китәп")

        val band = h.strip.lastBand()!!
        assertEquals(listOf("китәпләр", null, null), band.cells)
        assertEquals(SuggestionStripState.NO_CELL, band.emphasized)
        h.separator(' ')
        assertEquals("китәп ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun inAFieldWithoutSuggestionsNoPreviewEverAppears() {
        val h = Harness()
        h.controller.onStartInput(eligible = false)
        h.advise("китәп", "китап")

        h.typeWord("китәп")

        assertNull(h.strip.lastBand())
        assertEquals(SuggestionStripState.NO_CELL, h.strip.currentEmphasis)
        h.separator(' ')
        assertEquals("китәп ", h.editor.before)
        assertTrue(h.editor.edits.isEmpty())
    }

    @Test
    fun aMixedCaseWordShowsNeitherResultsNorAPreview() {
        val h = Harness()
        h.start()
        h.advise("китәп", "китап")

        h.typeWord("киТәп")

        assertNull(h.strip.lastBand())
        assertEquals(SuggestionStripState.NO_CELL, h.strip.currentEmphasis)
    }
}
