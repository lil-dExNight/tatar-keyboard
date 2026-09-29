package rkr.simplekeyboard.inputmethod.latin.suggestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramPreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedBigramTableCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageFailure
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSuggestPreparation
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSuggestSource
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * PROPOSALS.md, "E5c. Готовность вычислителя двухступенчатая": bigram-table preparation and
 * attachment must never be on the path that publishes the dictionary engine — `strip.reserve()`
 * and the already-typed prefix being looked up must both have already happened by the time
 * [SuggestionsController.onStartInput] returns, whether or not bigram preparation has completed
 * (or exists, or fails) by then. [PendingBigramPreparation] models this faithfully: unlike a
 * fake that resolves synchronously, it holds its callback until the test fires it explicitly —
 * exactly the "requested now, resolves later, off the UI thread" shape the real
 * `BackgroundBigramPreparer` has, and the only way to observe a real ordering guarantee instead
 * of an accidental one produced by fully-synchronous fakes.
 */
class SuggestionsControllerBigramAttachTest {

    private class FakeStrip : StripSurface {
        var reserveCount = 0
        var hideCount = 0
        val shown = mutableListOf<Triple<String, String?, String?>>()

        override fun showSuggestions(first: String, second: String?, third: String?) {
            shown += Triple(first, second, third)
        }

        override fun reserve() {
            reserveCount++
        }

        override fun hideSuggestions() {
            hideCount++
        }

        override fun setTapListener(listener: SuggestionTapListener) {}
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""

        /** E5d: the live NEXT_WORD context the editor cache would re-derive. */
        var context: String = ""

        override fun cachedWordBeforeCursor(): String = word
        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = true
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
        override fun cachedNextWordContext(): String = context
    }

    /** Records call ORDER across every tracked method, so tests can assert relative sequencing. */
    private class FakeEngine : EngineHandle {
        val events = mutableListOf<String>()
        var attachCatalog: PublishedBigramTableCatalog? = null
        var attachResult: Boolean = true

        /** The result callback the controller handed to the engine factory. */
        var callback: ResultCallback? = null

        /** The result the next NEXT_WORD request answers. */
        var nextWordAnswer: List<String> = emptyList()

        /**
         * True (the default) = the answer is delivered synchronously inside the request, like a
         * warm table — the controller must digest that too. False = the test delivers by hand via
         * [deliverNextWord], which is the only way to model a COMPANION answer: the companion's
         * bookkeeping is written after the request returns, so a synchronous companion answer would
         * be dropped as not-yet-expected — an artifact of the fake, not of the controller.
         */
        var synchronousAnswers: Boolean = true
        private var lastToken: Any? = null
        val requestedContexts = mutableListOf<ByteArray>()

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            events += "request"
            return Any()
        }

        override fun requestNextWord(editorSessionId: Long, subtypeId: String, contextWordUtf8: ByteArray): Any? {
            events += "requestNextWord"
            requestedContexts += contextWordUtf8
            val token = Any()
            lastToken = token
            // The real engine answering instantly (table attached — or not attached yet, which is
            // exactly the same empty list from the controller's side): the controller must digest
            // a synchronous delivery too.
            if (synchronousAnswers) callback?.onResult(token, nextWordAnswer, LookupKind.NEXT_WORD)
            return token
        }

        /** The engine's answer to its newest NEXT_WORD request, delivered when the test says so. */
        fun deliverNextWord(suggestions: List<String>) {
            callback?.onResult(requireNotNull(lastToken), suggestions, LookupKind.NEXT_WORD)
        }

        override fun attachBigramSource(catalog: PublishedBigramTableCatalog): Boolean {
            events += "attachBigramSource"
            attachCatalog = catalog
            return attachResult
        }

        override fun isCurrent(token: Any): Boolean = true
        override fun finishInput() {}
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

    private class FakeBigramCatalog : PublishedBigramTableCatalog {
        override fun acquireLatestForActivation() = null
        override fun cleanupReleasedVersions() {}
    }

    /** Resolves only when [completeWith] is called — models a background result arriving later. */
    private class PendingBigramPreparation : BigramPreparation {
        var prepareCalls = 0
        private var pendingCallback: ((BigramPreparationResult) -> Unit)? = null
        private val catalog = FakeBigramCatalog()

        override fun prepare(onResult: (BigramPreparationResult) -> Unit) {
            prepareCalls++
            pendingCallback = onResult
        }

        override fun catalog(): PublishedBigramTableCatalog = catalog

        fun completeWith(result: BigramPreparationResult) {
            val callback = requireNotNull(pendingCallback) { "prepare() was never called" }
            pendingCallback = null
            callback(result)
        }
    }

    /** Resolves synchronously, inline — for tests that only care about the terminal state. */
    private class ImmediateBigramPreparation(
        private val result: BigramPreparationResult,
    ) : BigramPreparation {
        private val catalog = FakeBigramCatalog()

        override fun prepare(onResult: (BigramPreparationResult) -> Unit) = onResult(result)
        override fun catalog(): PublishedBigramTableCatalog = catalog
    }

    /** The second language needs its own dictionary readiness; this publishes instantly. */
    private class FakeDictionaryPreparation : DictionaryPreparation {
        private val catalog = object : PublishedDictionaryCatalog {
            override fun acquireLatestForActivation(): DictionaryFileLease? = null
            override fun cleanupReleasedVersions() {}
        }

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

    /** A one-mapping emoji table, published the moment it is asked for (a warm emoji source). */
    private class FakeEmojiPreparation(
        private val table: Map<String, String>,
    ) : EmojiSuggestPreparation {
        override fun prepare(onResult: (EmojiSuggestSource?) -> Unit) {
            onResult(object : EmojiSuggestSource {
                override fun emojiFor(language: String, normalizedWord: String): String? =
                    table[normalizedWord]

                override fun spokenNameOf(emoji: String): String? = null
            })
        }
    }

    private fun controller(
        strip: FakeStrip,
        editor: FakeEditor,
        engine: FakeEngine,
        executor: ExecutorService,
        emojiPreparation: EmojiSuggestPreparation? = null,
        bigramPreparationFactory: (ExecutorService) -> BigramPreparation?,
    ) = SuggestionsController(
        strip,
        editor,
        UiPoster { it.run() },
        { _, callback -> engine.also { it.callback = callback } },
        { executor },
        { _, _ -> null },
        true,
        { backgroundExecutor, _ -> bigramPreparationFactory(backgroundExecutor) },
        { emojiPreparation },
    )

    @Test
    fun bigramPreparationStillPendingNeverBlocksReserveOrTheCachedPrefixLookup() {
        val strip = FakeStrip()
        val editor = FakeEditor().apply { word = "аб" }
        val engine = FakeEngine()
        val bigramTable = PendingBigramPreparation()
        val controller = controller(strip, editor, engine, DirectExecutorService()) { bigramTable }

        controller.onStartInput(eligible = true)

        // Publication of the dictionary engine — the band reserved, the already-typed prefix
        // looked up — has FULLY happened, even though bigram preparation was requested and has
        // not resolved yet. This is the property the contract actually names: not "which of the
        // two happens first", but "the second can never make the first wait".
        assertEquals(listOf("request"), engine.events)
        assertEquals(1, strip.reserveCount)
        assertEquals(1, bigramTable.prepareCalls)

        bigramTable.completeWith(BigramPreparationResult.Published(fakeTable(), alreadyPresent = false))

        assertEquals(listOf("request", "attachBigramSource"), engine.events)
        assertTrue(engine.attachCatalog === bigramTable.catalog())
    }

    @Test
    fun missingBigramPreparationNeverBlocksOrDelaysPublish() {
        val strip = FakeStrip()
        val editor = FakeEditor().apply { word = "аб" }
        val engine = FakeEngine()
        val controller = controller(strip, editor, engine, DirectExecutorService()) { null }

        controller.onStartInput(eligible = true)

        assertEquals(listOf("request"), engine.events)
        assertEquals(1, strip.reserveCount)
    }

    @Test
    fun unavailableBigramPreparationLeavesDictionarySuggestionsUnaffected() {
        val strip = FakeStrip()
        val editor = FakeEditor().apply { word = "аб" }
        val engine = FakeEngine()
        val bigramTable = ImmediateBigramPreparation(BigramPreparationResult.Unavailable(StorageFailure.INVALID_ASSET))
        val controller = controller(strip, editor, engine, DirectExecutorService()) { bigramTable }

        controller.onStartInput(eligible = true)

        assertEquals(listOf("request"), engine.events)
        assertEquals(1, strip.reserveCount)
        assertNull(engine.attachCatalog)
    }

    @Test
    fun bigramPreparationFactoryThrowingNeverPropagatesToPublish() {
        val strip = FakeStrip()
        val editor = FakeEditor().apply { word = "аб" }
        val engine = FakeEngine()
        val controller = controller(strip, editor, engine, DirectExecutorService()) {
            throw IllegalStateException("boom")
        }

        controller.onStartInput(eligible = true)

        assertEquals(listOf("request"), engine.events)
        assertEquals(1, strip.reserveCount)
    }

    @Test
    fun completedAttachReRequestsTheNextWordContextItRaced() {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        val bigramTable = PendingBigramPreparation()
        val controller = controller(strip, editor, engine, DirectExecutorService()) { bigramTable }

        controller.onStartInput(eligible = true)
        // The engine is published, but the bigram attach is still in flight. The user finished a
        // word and pressed space BEFORE the attach completed: the NEXT_WORD request goes to an
        // engine without a table and gets the "not attached yet" empty list, which looks exactly
        // like "no prediction for this context" (docs/NEXTWORD-RACE.md).
        editor.context = "мин"
        controller.onTextChanged()

        assertEquals(1, engine.requestedContexts.size)
        assertTrue(strip.shown.isEmpty())

        // The table attached; the very same context has a real answer now.
        engine.nextWordAnswer = listOf("дә", "үзем", "бу")
        bigramTable.completeWith(BigramPreparationResult.Published(fakeTable(), alreadyPresent = false))

        // The fix: a completed attach re-asks the still-pending NEXT_WORD context, and the band
        // fills without another keystroke. Before it, the request count stayed at one and the
        // band stayed empty until the next key.
        assertEquals(2, engine.requestedContexts.size)
        assertEquals(Triple("дә", "үзем", "бу"), strip.shown.last())
    }

    @Test
    fun completedAttachWithoutAPendingNextWordMomentRequestsNothing() {
        val strip = FakeStrip()
        val editor = FakeEditor()   // an empty field: no NEXT_WORD moment is pending at all
        val engine = FakeEngine()
        val bigramTable = PendingBigramPreparation()
        val controller = controller(strip, editor, engine, DirectExecutorService()) { bigramTable }

        controller.onStartInput(eligible = true)
        bigramTable.completeWith(BigramPreparationResult.Published(fakeTable(), alreadyPresent = false))

        // Attach by itself builds no lookup: nothing was asked before it, nothing is asked by it —
        // "the band stays silent when there is nothing to answer" is preserved.
        assertEquals(listOf("attachBigramSource"), engine.events)
        assertTrue(engine.requestedContexts.isEmpty())
        assertTrue(strip.shown.isEmpty())
    }

    @Test
    fun completedAttachMidWordDoesNotReRequestAnything() {
        val strip = FakeStrip()
        val editor = FakeEditor().apply { word = "аб" }   // a PREFIX moment, not a NEXT_WORD one
        val engine = FakeEngine()
        val bigramTable = PendingBigramPreparation()
        val controller = controller(strip, editor, engine, DirectExecutorService()) { bigramTable }

        controller.onStartInput(eligible = true)
        bigramTable.completeWith(BigramPreparationResult.Published(fakeTable(), alreadyPresent = false))

        assertEquals(listOf("request", "attachBigramSource"), engine.events)
        assertTrue(engine.requestedContexts.isEmpty())
    }

    // --- Audit 2026-09-02, B4: the re-request guard asks "did the ACTIVE language put a WORD on
    // the band", not "is the band occupied" ---------------------------------------------------------

    @Test
    fun emojiOnlyBandDoesNotBlockTheAttachReRequest() {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine()
        val bigramTable = PendingBigramPreparation()
        val controller = controller(
            strip, editor, engine, DirectExecutorService(),
            emojiPreparation = FakeEmojiPreparation(mapOf("мин" to "🙂")),
        ) { bigramTable }
        controller.setEmojiSuggestGate { true }

        controller.onStartInput(eligible = true)
        // The NEXT_WORD request raced the attach and answered empty; the emoji table has a mapping
        // for the context word, so the band it painted is an emoji-ONLY band — no word cell of the
        // active language on it.
        editor.context = "мин"
        controller.onTextChanged()

        assertEquals(1, engine.requestedContexts.size)
        assertEquals(Triple("🙂", null, null), strip.shown.last())

        // The table attached; the same context has a real answer now. The emoji cell must not read
        // as "the band is filled": the re-request runs, and the words take their front cells with
        // the emoji still pinned to the tail.
        engine.nextWordAnswer = listOf("дә", "үзем")
        bigramTable.completeWith(BigramPreparationResult.Published(fakeTable(), alreadyPresent = false))

        assertEquals(2, engine.requestedContexts.size)
        assertEquals(Triple("дә", "үзем", "🙂"), strip.shown.last())
    }

    @Test
    fun wordBandDoesNotGetReRequestedAfterAttach() {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engine = FakeEngine().apply { nextWordAnswer = listOf("дә", "үзем", "бу") }
        val bigramTable = PendingBigramPreparation()
        val controller = controller(strip, editor, engine, DirectExecutorService()) { bigramTable }

        controller.onStartInput(eligible = true)
        // The request went to an engine whose table was... already warm in effect: the answer came
        // back with words BEFORE the attach completed. The band shows active-language words, so the
        // attach repairs nothing — re-asking would only repaint what is already correct.
        editor.context = "мин"
        controller.onTextChanged()

        assertEquals(1, engine.requestedContexts.size)
        assertEquals(Triple("дә", "үзем", "бу"), strip.shown.last())

        bigramTable.completeWith(BigramPreparationResult.Published(fakeTable(), alreadyPresent = false))

        assertEquals(1, engine.requestedContexts.size)
        assertEquals(Triple("дә", "үзем", "бу"), strip.shown.last())
    }

    /** Two warm languages, one bigram preparation each, resolved by the test on demand. */
    private class TwoLanguageHarness {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val executor = DirectExecutorService()
        val engines = LinkedHashMap<String, FakeEngine>()
        val bigramTables = LinkedHashMap<String, PendingBigramPreparation>()

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { subtypeId, callback ->
                engines.getOrPut(subtypeId) { FakeEngine().also { it.callback = callback } }
            },
            { executor },
            { _: ExecutorService, _: String -> FakeDictionaryPreparation() },
            false,
            { _: ExecutorService, subtypeId: String ->
                bigramTables.getOrPut(subtypeId) { PendingBigramPreparation() }
            },
        )

        fun engine(subtypeId: String): FakeEngine = engines.getValue(subtypeId)
        fun bigramTable(subtypeId: String): PendingBigramPreparation = bigramTables.getValue(subtypeId)

        /** Brings both languages' engines up, leaving Tatar active in an empty field. */
        fun warmBoth() {
            controller.onStartInput(eligible = true, subtypeId = PersonalSubtypes.TATAR_RU)
            controller.onSubtypeChanged(eligible = true, subtypeId = PersonalSubtypes.RUSSIAN)
            controller.onSubtypeChanged(eligible = true, subtypeId = PersonalSubtypes.TATAR_RU)
            strip.shown.clear()
        }
    }

    @Test
    fun companionAttachReRequestsTheCompanionFill() {
        val h = TwoLanguageHarness()
        h.warmBoth()
        // The companion answers when the TEST says so — its bookkeeping is written after the
        // request returns, so a synchronous fake answer would be dropped as not-yet-expected.
        h.engine(PersonalSubtypes.RUSSIAN).synchronousAnswers = false
        // The ACTIVE table is attached before the moment begins: its empty answer below is a
        // legitimate "no prediction for this context", not a race.
        h.bigramTable(PersonalSubtypes.TATAR_RU)
            .completeWith(BigramPreparationResult.Published(fakeTable(), alreadyPresent = false))

        // NEXT_WORD moment: the active language answers empty, the companion is asked to fill the
        // band — but ITS table is still attaching, so its answer is the race's empty list.
        h.editor.context = "мин"
        h.controller.onTextChanged()
        h.engine(PersonalSubtypes.RUSSIAN).deliverNextWord(emptyList())

        assertEquals(1, h.engine(PersonalSubtypes.RUSSIAN).requestedContexts.size)
        assertTrue(h.strip.shown.isEmpty())

        // The companion table attached: the band still has no word of either language, so the
        // companion fill is re-asked and the words arrive without another keystroke.
        h.bigramTable(PersonalSubtypes.RUSSIAN)
            .completeWith(BigramPreparationResult.Published(fakeTable(), alreadyPresent = false))

        assertEquals(2, h.engine(PersonalSubtypes.RUSSIAN).requestedContexts.size)
        h.engine(PersonalSubtypes.RUSSIAN).deliverNextWord(listOf("слово", "дело"))
        assertEquals(Triple("слово", "дело", null), h.strip.shown.last())
    }

    @Test
    fun companionAttachOnAFullBandRequestsNothing() {
        val h = TwoLanguageHarness()
        h.warmBoth()
        h.bigramTable(PersonalSubtypes.TATAR_RU)
            .completeWith(BigramPreparationResult.Published(fakeTable(), alreadyPresent = false))

        // The active language fills all three cells: the companion is never even asked, and its
        // attach completing later must not change that.
        h.engine(PersonalSubtypes.TATAR_RU).nextWordAnswer = listOf("дә", "үзем", "бу")
        h.editor.context = "мин"
        h.controller.onTextChanged()

        assertTrue(h.engine(PersonalSubtypes.RUSSIAN).requestedContexts.isEmpty())
        assertEquals(Triple("дә", "үзем", "бу"), h.strip.shown.last())

        h.bigramTable(PersonalSubtypes.RUSSIAN)
            .completeWith(BigramPreparationResult.Published(fakeTable(), alreadyPresent = false))

        assertTrue(h.engine(PersonalSubtypes.RUSSIAN).requestedContexts.isEmpty())
        assertEquals(Triple("дә", "үзем", "бу"), h.strip.shown.last())
    }

    private fun fakeTable() = rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedBigramTable(
        1, "tt", java.io.File("unused"), 1, 1, 1, 1, 2, 1, "0".repeat(64),
    )
}
