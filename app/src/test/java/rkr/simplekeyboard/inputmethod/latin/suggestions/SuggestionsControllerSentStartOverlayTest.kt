/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
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

import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SentenceStartSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog

/**
 * The personal sentence-initial overlay in the strip: at a sentence start the band leads with the
 * user's own learned words (usage order), then fills from the static table, never repeating a word
 * already shown, and showing fewer than four cells only when both run out. The learning side is
 * driven through the REAL text extraction ([TatarWordUtils]) end to end: a cleanly typed word
 * counts exactly when it was committed at a sentence start, a sentence-start strip tap counts, a
 * prefix completion tapped at a sentence start counts, and a paste never counts.
 *
 * The static-table behavior without the overlay is pinned in `SuggestionsControllerSentStartTest`.
 */
class SuggestionsControllerSentStartOverlayTest {

    private val tatar = PersonalSubtypes.TATAR_RU

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        val shown = mutableListOf<List<String?>>()
        var reserveCount = 0
        var tap: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?, fourth: String?) {
            shown.add(listOf(first, second, third, fourth))
        }

        override fun reserve() {
            reserveCount++
        }

        override fun hideSuggestions() = Unit

        override fun setTapListener(listener: SuggestionTapListener) {
            tap = listener
        }

        /** The cells as the user would read them: nulls trimmed off the end. */
        fun lastCells(): List<String> = shown.lastOrNull()?.filterNotNull() ?: emptyList()
    }

    /**
     * The editor seam answered by the REAL [TatarWordUtils] extraction over [rawText] (the cache
     * provably reached the text start), so the controller and the extraction can never drift
     * apart. The commit paths move the text the way the real editor does.
     */
    private class FakeEditor : EditorSurface {
        var rawText: String = ""
        val predictedCommits = mutableListOf<Pair<String, String>>()
        val suggestionCommits = mutableListOf<Pair<String, String>>()

        override fun cachedWordBeforeCursor(): String = TatarWordUtils.extractTrailingWord(rawText)
        override fun cachedNextWordContext(): String =
            TatarWordUtils.extractNextWordContext(rawText, true)
        override fun cachedWordBeforeTrailingWord(): String =
            TatarWordUtils.extractWordBeforeTrailingWord(rawText, true)
        override fun isAtSentenceStart(): Boolean =
            TatarWordUtils.isSentenceStartContext(rawText, true)
        override fun isTrailingWordAtSentenceStart(): Boolean =
            TatarWordUtils.isTrailingWordAtSentenceStart(rawText, true)
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean {
            if (TatarWordUtils.extractTrailingWord(rawText) != expectedPrefix) return false
            suggestionCommits.add(expectedPrefix to suggestion)
            rawText = rawText.dropLast(expectedPrefix.length) + suggestion + " "
            return true
        }

        override fun commitPredictedWord(expectedContextWord: String, suggestion: String): Boolean {
            // The real editor re-derives the live sentence start for an empty context.
            if (expectedContextWord.isEmpty() && !isAtSentenceStart()) return false
            if (expectedContextWord.isNotEmpty() &&
                TatarWordUtils.extractNextWordContext(rawText, true) != expectedContextWord
            ) {
                return false
            }
            predictedCommits.add(expectedContextWord to suggestion)
            rawText += suggestion + " "
            return true
        }
    }

    /** One engine per language; every request gets a FRESH token, like the real engine's. */
    private class FakeEngine : EngineHandle {
        val nextWordRequests = mutableListOf<String>()
        val prefixRequests = mutableListOf<String>()
        private var current: Any? = null

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            prefixRequests.add(String(prefixUtf8, Charsets.UTF_8))
            return Any().also { current = it }
        }

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any? {
            nextWordRequests.add(String(contextWordUtf8, Charsets.UTF_8))
            return Any().also { current = it }
        }

        override fun isCurrent(token: Any): Boolean = token === current
        override fun finishInput() {
            current = null
        }

        override fun destroy(timeoutMs: Long): Boolean = true

        fun currentToken(): Any = requireNotNull(current)
    }

    private class FakeCatalog : PublishedDictionaryCatalog {
        override fun acquireLatestForActivation(): DictionaryFileLease? = null
        override fun cleanupReleasedVersions() = Unit
    }

    private class FakePreparation : DictionaryPreparation {
        private val catalog = FakeCatalog()
        override fun prepare(onResult: (PreparationResult) -> Unit) {
            onResult(
                PreparationResult.Published(
                    PublishedDictionary(
                        generation = 1,
                        file = File("/dev/null"),
                        rawSize = 72,
                        entryCount = 1,
                        schemaId = 1,
                        formatVersion = 1,
                        rawSha256 = "0".repeat(64),
                    ),
                    alreadyPresent = true,
                ),
            )
        }

        override fun catalog(): PublishedDictionaryCatalog = catalog
    }

    private class FakeSentStartSource(private val words: List<String>) : SentStartSource {
        override fun topWords(maxOut: Int): List<String> = words.take(maxOut)
    }

    private class DirectExecutorService : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    /**
     * The harness: the static [table] of the Tatar slot (null = no table, like a language the
     * artifact registry carries without one) plus the live [overlayWords] behind the overlay seam.
     */
    private class Harness(
        table: List<String>? = TABLE,
        val overlayWords: MutableList<String> = mutableListOf(),
    ) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val executor = DirectExecutorService()
        val engine = FakeEngine()
        var callback: ResultCallback? = null
        val learned = mutableListOf<String>()
        var flushes = 0

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { _, cb -> callback = cb; engine },
            { executor },
            { _: ExecutorService, _: String -> FakePreparation() },
            false,
            { _: ExecutorService, _: String -> null },
            { _: ExecutorService -> null },
            { _: ExecutorService, subtypeId: String ->
                table?.let { SentStartPreparation { onResult -> onResult(FakeSentStartSource(it)) } }
            },
        )

        init {
            controller.setSentStartOverlaySource(SentStartOverlaySource { overlayWords.toList() })
            controller.setSentenceStartSink(object : SentenceStartSink {
                override fun onSentenceStartWord(word: String) {
                    learned.add(word)
                }

                override fun onInputFinished() {
                    flushes++
                }
            })
        }

        /** Starts the Tatar slot at the start of an empty field. */
        fun startAtSentenceStart() {
            editor.rawText = ""
            controller.onStartInput(eligible = true, subtypeId = PersonalSubtypes.TATAR_RU)
        }

        /** Types [text] one character at a time, the way keystrokes arrive. */
        fun type(text: String) {
            for (char in text) {
                editor.rawText += char
                controller.onTextChanged()
            }
        }

        /** A paste: the whole [text] appears in ONE text event. */
        fun paste(text: String) {
            editor.rawText += text
            controller.onTextChanged()
        }

        /** One backspace. */
        fun backspace() {
            editor.rawText = editor.rawText.dropLast(1)
            controller.onTextChanged()
        }

        /** The separator that finishes the current word. */
        fun endWord() {
            editor.rawText += " "
            controller.onTextChanged()
        }

        /** Delivers the engine's PREFIX answer for the current lookup. */
        fun deliverPrefix(suggestions: List<String>) {
            callback?.onResult(engine.currentToken(), suggestions, LookupKind.PREFIX)
        }
    }

    // --- The fill: overlay first, then the static table ------------------------------------------

    @Test
    fun theStaticTableAloneFillsWhenTheOverlayIsEmpty() {
        val h = Harness(overlayWords = mutableListOf())
        h.startAtSentenceStart()

        assertEquals(listOf("Бу", "Ул", "Ә", "Бүген"), h.strip.lastCells())
    }

    @Test
    fun theOverlayLeadsAndTheStaticTableFillsInItsOwnOrder() {
        val h = Harness(overlayWords = mutableListOf("мин", "бүген"))
        h.startAtSentenceStart()

        // The overlay's two words lead (its usage order), then the table's top in ITS order.
        assertEquals(listOf("Мин", "Бүген", "Бу", "Ул"), h.strip.lastCells())
    }

    @Test
    fun anOverlayWordTheTableAlsoCarriesIsShownOnceInTheOverlayPosition() {
        val h = Harness(overlayWords = mutableListOf("бүген", "мин"))
        h.startAtSentenceStart()

        // «бүген» leaves the table's tail: the next static word fills the cell instead.
        assertEquals(listOf("Бүген", "Мин", "Бу", "Ул"), h.strip.lastCells())
    }

    @Test
    fun anOverlayLongerThanTheStripCapsAtFourCells() {
        val h = Harness(overlayWords = mutableListOf("мин", "син", "ул", "бүген", "әле"))
        h.startAtSentenceStart()

        assertEquals(listOf("Мин", "Син", "Ул", "Бүген"), h.strip.lastCells())
    }

    @Test
    fun theOverlayAloneFillsWhenTheLanguageHasNoTable() {
        val h = Harness(table = null, overlayWords = mutableListOf("мин", "бүген"))
        h.startAtSentenceStart()

        assertEquals(listOf("Мин", "Бүген"), h.strip.lastCells())
    }

    @Test
    fun fewerThanFourCellsOnlyWhenBothRunOut() {
        val h = Harness(table = listOf("бу"), overlayWords = mutableListOf("мин"))
        h.startAtSentenceStart()

        assertEquals(listOf("Мин", "Бу"), h.strip.lastCells())
    }

    @Test
    fun anEmptyOverlayAndAnEmptyTableKeepTheReservedBand() {
        val h = Harness(table = null, overlayWords = mutableListOf())
        h.startAtSentenceStart()

        assertEquals(emptyList<String>(), h.strip.lastCells())
        assertTrue(h.strip.reserveCount > 0)
    }

    // --- Learning: what counts as a sentence-initial commit ----------------------------------------

    @Test
    fun aWordTypedOutAtAFieldStartCounts() {
        val h = Harness()
        h.startAtSentenceStart()

        h.type("Сәләм")
        h.endWord()

        assertEquals(listOf("Сәләм"), h.learned)
    }

    @Test
    fun aWordTypedOutAfterASentenceEndCounts() {
        val h = Harness()
        h.startAtSentenceStart()

        h.type("Сәләм")
        h.type(".") // the period completes «Сәләм» — the field-start commit
        h.endWord() // the space after the period: the next sentence's start
        h.type("Дөнья")
        h.endWord()

        assertEquals(listOf("Сәләм", "Дөнья"), h.learned)
    }

    @Test
    fun aWordTypedOutMidSentenceNeverCounts() {
        val h = Harness()
        h.startAtSentenceStart()

        h.type("Сәләм")
        h.endWord()
        h.type("дөнья")
        h.endWord()

        assertEquals(listOf("Сәләм"), h.learned)
    }

    @Test
    fun aPastedWordNeverCounts() {
        val h = Harness()
        h.startAtSentenceStart()

        h.paste("Сәләм")
        h.endWord()

        assertTrue(h.learned.isEmpty())
    }

    @Test
    fun aWordRetypedAfterABackspaceNeverCounts() {
        val h = Harness()
        h.startAtSentenceStart()

        h.type("Сәләм")
        h.backspace()
        h.type("м")
        h.endWord()

        assertTrue("a non-growth transition dirties the run", h.learned.isEmpty())
    }

    @Test
    fun aSentenceStartStripTapCountsTheCommittedWord() {
        val h = Harness()
        h.startAtSentenceStart()
        assertEquals(listOf("Бу", "Ул", "Ә", "Бүген"), h.strip.lastCells())

        requireNotNull(h.strip.tap).onTap("Бүген")

        assertEquals(listOf("" to "Бүген"), h.editor.predictedCommits)
        assertEquals(listOf("Бүген"), h.learned)
    }

    @Test
    fun aPrefixCompletionTappedAtASentenceStartCounts() {
        val h = Harness()
        h.startAtSentenceStart()

        h.type("сә")
        h.deliverPrefix(listOf("сәләм"))
        requireNotNull(h.strip.tap).onTap("сәләм")

        assertEquals(listOf("сә" to "сәләм"), h.editor.suggestionCommits)
        assertEquals(listOf("сәләм"), h.learned)
    }

    @Test
    fun aPrefixCompletionTappedMidSentenceNeverCounts() {
        val h = Harness()
        h.startAtSentenceStart()
        h.type("Сәләм")
        h.endWord()
        h.learned.clear()

        h.type("до")
        h.deliverPrefix(listOf("дөнья"))
        requireNotNull(h.strip.tap).onTap("дөнья")

        assertEquals(listOf("до" to "дөнья"), h.editor.suggestionCommits)
        assertTrue(h.learned.isEmpty())
    }

    @Test
    fun aRefusedSentenceStartTapLearnsNothing() {
        val h = Harness()
        h.startAtSentenceStart()
        // The position moved on before the tap: the editor refuses the stale commit.
        h.type("б")

        requireNotNull(h.strip.tap).onTap("Бүген")

        assertTrue(h.editor.predictedCommits.isEmpty())
        assertTrue(h.learned.isEmpty())
    }

    @Test
    fun theSessionEndFlushesTheOverlaySink() {
        val h = Harness()
        h.startAtSentenceStart()
        h.controller.onFinishInput()

        assertEquals(1, h.flushes)
    }

    private companion object {
        /** A four-word static table, so the band cap is exactly observable. */
        val TABLE = listOf("бу", "ул", "ә", "бүген")
    }
}
