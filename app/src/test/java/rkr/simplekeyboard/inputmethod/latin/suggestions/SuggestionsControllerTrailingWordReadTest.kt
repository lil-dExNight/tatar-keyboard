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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.EmptyPrefixProber
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TextShortcutSource
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * Pins the one-derivation rule of [SuggestionsController.onTextChanged]: a text change derives
 * [EditorSurface.cachedWordBeforeCursor] exactly once, and the same value serves both the
 * clean-run tracking and the ordinary current-prefix request. Before the single-read change the
 * ordinary growth event derived the word twice (once for the run machine, once inside
 * [SuggestionsController.requestCurrentPrefix]).
 *
 * The counting editor also proves the paths that must NOT adopt the reuse: the rescue binding
 * keeps its own live re-check (two derivations while it matches, one when its cursor check
 * short-circuits), every other [requestCurrentPrefix] caller derives the word live, and the
 * engine requests stay byte-identical everywhere.
 */
class SuggestionsControllerTrailingWordReadTest {

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
    }

    /** A text model with the cursor at the end of [before]; counts trailing-word derivations. */
    private class FakeEditor : EditorSurface {
        var before: String = ""
        var textAfterCursor: String = ""
        var trailingWordReads: Int = 0
            private set

        override fun cachedWordBeforeCursor(): String {
            trailingWordReads++
            return TatarWordUtils.extractTrailingWord(before)
        }

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            // The production re-check and replacement, modeled on the text model; reads the text
            // directly so a commit never inflates the derivation counter.
            if (TatarWordUtils.extractTrailingWord(before) != expectedPrefix) return false
            before = before.dropLast(expectedPrefix.length) + suggestion
            if (TatarWordUtils.needsAutoSpace(textAfterCursor)) before += " "
            return true
        }

        override fun hasKnownCursor(): Boolean = true

        override fun hasLetterAfterCursor(): Boolean =
            TatarWordUtils.startsWithWordCharacter(textAfterCursor)

        override fun cachedNextWordContext(): String =
            TatarWordUtils.extractNextWordContext(before, cacheReachedTextStart = true)

        override fun cachedWordBeforeTrailingWord(): String =
            TatarWordUtils.extractWordBeforeTrailingWord(before, cacheReachedTextStart = true)

        override fun cursorPosition(): Int = before.length

        override fun replaceTypedWord(expectedPrefix: String, replacement: String): Boolean {
            if (TatarWordUtils.extractTrailingWord(before) != expectedPrefix) return false
            before = before.dropLast(expectedPrefix.length) + replacement
            return true
        }

        override fun revertTypedWord(
            insertedForm: String,
            separator: String,
            typedForm: String,
        ): Boolean {
            val replaced = insertedForm + separator
            if (!before.endsWith(replaced)) return false
            before = before.dropLast(replaced.length) + typedForm + separator
            return true
        }

        override fun commitGlideWord(
            expectedContextWord: String,
            suggestion: String,
            expectedTrailingWord: String,
            expectedCursor: Int,
        ): Int {
            // The production re-checks, modeled: a changed trailing word, cursor or context
            // refuses; NO trailing space, and the leading space per the auto-space rule.
            if (expectedCursor != -1 && expectedCursor != before.length) {
                return EditorSurface.GLIDE_COMMIT_REFUSED
            }
            if (TatarWordUtils.extractTrailingWord(before) != expectedTrailingWord) {
                return EditorSurface.GLIDE_COMMIT_REFUSED
            }
            if (TatarWordUtils.extractNextWordContext(before, true) != expectedContextWord) {
                return EditorSurface.GLIDE_COMMIT_REFUSED
            }
            val prepend = TatarWordUtils.glideNeedsLeadingSpace(before)
            before += (if (prepend) " " else "") + suggestion
            return if (prepend) EditorSurface.GLIDE_COMMIT_PREPENDED else EditorSurface.GLIDE_COMMIT_BARE
        }

        override fun deleteGlideLiftedWord(committedWord: String, prependedSpace: Boolean): Boolean {
            val lifted = (if (prependedSpace) " " else "") + committedWord
            if (!before.endsWith(lifted)) return false
            before = before.dropLast(lifted.length)
            return true
        }
    }

    /**
     * Records every engine request: `events` in issue order (decoded, one entry per request) and
     * the prefix lookups as raw bytes, so the keys are pinned byte for byte. Canned answers are
     * delivered inline through the worker callback, exactly once per lookup.
     */
    private class FakeEngine : EngineHandle {
        val events = mutableListOf<String>()

        /** One entry per prefix lookup: the prefix bytes and the context-boost bytes. */
        val prefixLookups = mutableListOf<Pair<ByteArray, ByteArray>>()
        private var serial = 0L
        var callback: ResultCallback? = null
        val answers = mutableMapOf<String, List<String>>()
        val nextWordAnswers = mutableMapOf<String, List<String>>()
        var probeAnswer = EmptyPrefixProber.NO_EMPTY_PREFIX

        override fun request(
            editorSessionId: Long,
            subtypeId: String,
            prefixUtf8: ByteArray,
        ): Any? = request(editorSessionId, subtypeId, prefixUtf8, ByteArray(0))

        override fun request(
            editorSessionId: Long,
            subtypeId: String,
            prefixUtf8: ByteArray,
            contextWordUtf8: ByteArray,
        ): Any {
            val word = String(prefixUtf8, Charsets.UTF_8)
            events += "prefix:$word"
            prefixLookups += prefixUtf8 to contextWordUtf8
            answers[word]?.let { callback?.onResult(++serial, it, LookupKind.PREFIX) }
                ?: run { serial++ }
            return serial
        }

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            previousContextWordUtf8: ByteArray,
            contextWordUtf8: ByteArray,
        ): Any {
            val context = String(contextWordUtf8, Charsets.UTF_8)
            events += "next:$context"
            serial++
            nextWordAnswers[context]?.let { callback?.onResult(serial, it, LookupKind.NEXT_WORD) }
            return serial
        }

        override fun requestGlide(
            editorSessionId: Long,
            subtypeId: String,
            path: GlidePath,
            contextWordUtf8: ByteArray,
        ): Any {
            events += "glide"
            return ++serial
        }

        override fun requestEmptyPrefixProbe(word: String, callback: (Int) -> Unit): Boolean {
            events += "probe:$word"
            callback(probeAnswer)
            return true
        }

        /** The worker delivery of the newest glide decode's candidates. */
        fun answerGlide(suggestions: List<String>) {
            callback?.onResult(serial, suggestions, LookupKind.GLIDE)
        }

        override fun isCurrent(token: Any): Boolean = token == serial

        override fun finishInput() = Unit

        override fun destroy(timeoutMs: Long): Boolean = true
    }

    /** Runs submitted work inline so engine start is deterministic. */
    private class DirectExecutorService : AbstractExecutorService() {
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
            DirectExecutorService(), true,
        )

        init {
            controller.setCompletionSink { word -> completions.add(word) }
            // The glide strip lives on its own switch; the default is off.
            controller.setGlideGate(GlideGate { true })
        }

        fun start() {
            controller.onStartInput(eligible = true)
        }

        /** One ordinary character: the edit, then the single text-change event. */
        fun type(text: String) {
            editor.before += text
            controller.onTextChanged()
        }

        fun typeWord(word: String) {
            word.forEach { type(it.toString()) }
        }

        /** The word ends: a space commits and the trailing word becomes empty. */
        fun endWord() {
            editor.before += " "
            controller.onTextChanged()
        }

        /** One backspace: the edit, then the single text-change event. */
        fun backspace() {
            editor.before = editor.before.dropLast(1)
            controller.onTextChanged()
        }

        /**
         * Arms the whole-word-undo rescue state: a glide lifts "сәләм" at an empty position, the
         * decode commits the top candidate, and the undo deletes it. Returns the alternatives
         * band the strip showed for the gesture.
         */
        fun armGlideRescue(candidates: List<String>): List<String?> {
            start()
            controller.onGlideInput(GlidePath(4))
            engine.answerGlide(candidates)
            val alternates = strip.bands.last()
            assertTrue(controller.maybeUndoGlideCommit())
            assertEquals("", editor.before)
            return alternates
        }
    }

    // --- Assertions ----------------------------------------------------------------------------

    /** The lookup keys are the normalized words, byte for byte, contexts included. */
    private fun assertPrefixLookups(
        engine: FakeEngine,
        fromIndex: Int,
        expected: List<Pair<String, String>>,
    ) {
        val actual = engine.prefixLookups.drop(fromIndex)
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { index, (word, context) ->
            assertArrayEquals(
                "prefix lookup #$index",
                TatarWordUtils.toLookupBytes(TatarWordUtils.normalizeForLookup(word)),
                actual[index].first,
            )
            assertArrayEquals(
                "context of prefix lookup #$index",
                TatarWordUtils.toLookupBytes(TatarWordUtils.normalizeForLookup(context)),
                actual[index].second,
            )
        }
    }

    private fun newHarness(): Harness = Harness().also { it.start() }

    // --- Ordinary growth: one derivation per text change ---------------------------------------

    @Test
    fun ordinaryGrowthDerivesTheTrailingWordOncePerTextChange() {
        val h = newHarness()
        val readsBefore = h.editor.trailingWordReads

        // Three growth events: one derivation each (the old code derived twice per event).
        h.typeWord("сәләм")
        assertEquals(5, h.editor.trailingWordReads - readsBefore)
        assertPrefixLookups(h.engine, 0, listOf(
            "с" to "", "сә" to "", "сәл" to "", "сәлә" to "", "сәләм" to "",
        ))

        // The separator event derives once too (the empty word flows into the NEXT_WORD path).
        val readsAfterWord = h.editor.trailingWordReads
        val eventsAfterWord = h.engine.events.size
        h.endWord()
        assertEquals(1, h.editor.trailingWordReads - readsAfterWord)
        // The first clean word also runs its deferred learning probe behind the NEXT_WORD lookup.
        assertEquals(
            listOf("next:сәләм", "probe:сәләм"),
            h.engine.events.drop(eventsAfterWord),
        )

        // The next word's lookups are byte-identical, context boost included: the reuse never
        // leaks a stale word into a request.
        val readsAfterSeparator = h.editor.trailingWordReads
        h.typeWord("дөнья")
        assertEquals(5, h.editor.trailingWordReads - readsAfterSeparator)
        assertPrefixLookups(h.engine, 5, listOf(
            "д" to "сәләм", "дө" to "сәләм", "дөн" to "сәләм",
            "дөнь" to "сәләм", "дөнья" to "сәләм",
        ))
    }

    @Test
    fun ordinaryGrowthKeepsTheStripCellsByteIdentical() {
        val h = newHarness()
        h.engine.answers["с"] = listOf("сәләм")
        h.engine.answers["сә"] = listOf("сәләм")
        h.engine.answers["сәл"] = listOf("сәләм")
        h.engine.answers["сәлә"] = listOf("сәләм")
        h.engine.answers["сәләм"] = listOf("сәләм")
        h.engine.nextWordAnswers["сәләм"] = listOf("дөнья")
        h.engine.nextWordAnswers["дөнья"] = listOf("бар")

        val bands = mutableListOf<List<String?>>()
        "сәләм".forEach { char ->
            h.type(char.toString())
            bands.add(h.strip.bands.last())
        }
        h.endWord()
        bands.add(h.strip.bands.last())
        "дөнья".forEach { char ->
            h.type(char.toString())
            bands.add(h.strip.bands.last())
        }

        // Every event settled on exactly the canned answer, nothing else.
        val expected = List(5) { listOf("сәләм", null, null, null) } +
            listOf(listOf("дөнья", null, null, null)) +
            List(5) { listOf("дөнья", null, null, null) }
        assertEquals(expected, bands)
    }

    // --- Backspace: one derivation, a fresh request for the shorter word -----------------------

    @Test
    fun backspaceReRequestsWithOneDerivationPerEvent() {
        val h = newHarness()
        h.engine.answers["с"] = listOf("сүз")
        h.engine.answers["сү"] = listOf("сүзләр")
        h.engine.answers["сүз"] = listOf("сүзгә")
        h.typeWord("сүз")
        val readsBefore = h.editor.trailingWordReads
        val lookupsBefore = h.engine.prefixLookups.size

        h.backspace()

        assertEquals(1, h.editor.trailingWordReads - readsBefore)
        // The prefix revisited after a different one does not match the duplicate suppression:
        // "сү" is requested afresh, with the same bytes as the original "сү" lookup.
        assertPrefixLookups(h.engine, lookupsBefore, listOf("сү" to ""))
        assertEquals(listOf("сүзләр", null, null, null), h.strip.bands.last())
    }

    // --- Rescue: the binding keeps its own live re-check ---------------------------------------

    @Test
    fun theRescueBindingKeepsItsLiveRecheckAndRebindsTheCandidates() {
        val h = Harness()
        val alternates = h.armGlideRescue(listOf("сәләм", "сәлләм", "сүз"))
        val lookupsBefore = h.engine.prefixLookups.size
        val eventsBefore = h.engine.events.size
        val readsBefore = h.editor.trailingWordReads

        // The backspace path (LatinImeGlide) fires onTextChanged right after the undo; the
        // editor still shows exactly the emptied position, so the rescue re-binds.
        h.controller.onTextChanged()

        // Two derivations by design: the clean-run tracking, plus the binding's own live
        // re-check against the editor state — the rescue never trusts a passed-in value.
        assertEquals(2, h.editor.trailingWordReads - readsBefore)
        assertEquals(alternates, h.strip.bands.last())
        // The rescue branch returns before any derivation: no lookup rides the event.
        assertEquals(lookupsBefore, h.engine.prefixLookups.size)
        assertEquals(eventsBefore, h.engine.events.size)
    }

    @Test
    fun aRescueDroppedByAMismatchedEditDerivesFromTheLiveWord() {
        val h = Harness()
        h.armGlideRescue(listOf("сәләм", "сәлләм", "сүз"))
        h.engine.answers["б"] = listOf("бала")
        val readsBefore = h.editor.trailingWordReads
        val lookupsBefore = h.engine.prefixLookups.size

        // The user types on: cursor and trailing word no longer match the armed binding, so the
        // rescue drops and the ordinary derivation runs for the NEW word.
        h.type("б")

        // The binding's cursor check short-circuits before its word read: one derivation.
        assertEquals(1, h.editor.trailingWordReads - readsBefore)
        assertPrefixLookups(h.engine, lookupsBefore, listOf("б" to ""))
        assertEquals(listOf("бала", null, null, null), h.strip.bands.last())
    }

    // --- Revert cell: painted without an extra lookup, tap re-derives live ----------------------

    @Test
    fun theRevertCellPaintsWithoutAnExtraLookupAndTheTapReDerivesLive() {
        val h = newHarness()
        h.controller.setShortcutSource(TextShortcutSource { if (it == "сүз") "сүзе" else null })
        h.controller.setRevertCellDecorator(RevertCellDecorator { word -> "\"$word\"" })
        h.typeWord("сүз")

        // The shortcut expansion replaces the word before the separator reaches the editor.
        // This is the separator-keydown path, NOT the text-change event: it derives the word
        // live on its own (a retained live read), exactly as before the single-read change.
        assertTrue(h.controller.maybeExpandShortcutBeforeSeparator(' '.code))
        assertEquals("сүзе", h.editor.before)
        val readsBefore = h.editor.trailingWordReads
        val eventsBefore = h.engine.events.size

        // The separator text-change event paints the keep-typed cell and stops: no derivation
        // beyond the clean-run read, no lookup.
        h.endWord()
        assertEquals(1, h.editor.trailingWordReads - readsBefore)
        assertEquals(
            listOf("\"сүз\"", null, null, null),
            h.strip.bands.last(),
        )
        assertEquals(eventsBefore, h.engine.events.size)

        // Tapping the keep-typed cell restores the typed word and re-derives the strip from the
        // live editor state — the tap path is another caller that never adopts the reuse.
        h.strip.listener!!.onTap("\"сүз\"")
        assertEquals("сүз ", h.editor.before)
        assertTrue("next:сүз" in h.engine.events)
    }

    // --- Sink: a cleanly completed word still reaches the completion sink -----------------------

    @Test
    fun theCleanCompletionSinkStillLearnsBehindTheSeparatorLookup() {
        val h = newHarness()

        // The first word after a dirty boundary is never counted; witness one so the run machine
        // trusts the next word.
        h.typeWord("баш")
        h.endWord()
        h.engine.events.clear()

        h.engine.probeAnswer = 2 // "үт" came back with an empty exact pass
        val readsBefore = h.editor.trailingWordReads

        // Every lookup's answer is dropped, as under latest-only coalescing, so the word parks
        // for the deferred empty-prefix probe.
        h.typeWord("үтәли")
        assertEquals(5, h.editor.trailingWordReads - readsBefore)
        h.endWord()

        assertEquals(listOf("үтәли"), h.completions)
        assertEquals(1, h.editor.trailingWordReads - readsBefore - 5)
        // The probe ran behind the separator's next-word lookup, never ahead of it.
        val nextIndex = h.engine.events.indexOf("next:үтәли")
        val probeIndex = h.engine.events.indexOf("probe:үтәли")
        assertTrue("the next-word lookup was issued", nextIndex >= 0)
        assertTrue("the deferred probe was issued", probeIndex >= 0)
        assertTrue("the probe never overtakes the separator's lookup", nextIndex < probeIndex)
    }
}
