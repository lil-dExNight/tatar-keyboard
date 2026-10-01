package rkr.simplekeyboard.inputmethod.latin.suggestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.CompositePrefixComputer
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.GlideDecoderHost
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ImmutableUtf8Prefix
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TatBigrPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictGlideInventory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.GlobalTopFrequencyFallbackFactory
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SnapshotPersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.BigramTableIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath
import rkr.simplekeyboard.inputmethod.latin.glide.GlideTestFixtures
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The glide path end to end — controller, real bundled assets (the Tatar and Russian dictionaries
 * and the Tatar bigram table), the fixture layout geometries, and the real decoder. The engine
 * handle is backed by the production machinery ([TdictPrefixIndex], [CompositePrefixComputer],
 * [GlideDecoderHost]) and delivers synchronously, so the whole flow — gesture → decode → band → tap
 * → commit → NEXT_WORD chain — is exercised with no emulator.
 */
class GlideEndToEndTest {

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        val shown = mutableListOf<List<String?>>()
        var reserveCount = 0
        var hideCount = 0
        var listener: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?) {
            shown.add(listOf(first, second, third))
        }

        override fun reserve() {
            reserveCount++
        }

        override fun hideSuggestions() {
            hideCount++
        }

        override fun setTapListener(listener: SuggestionTapListener) {
            this.listener = listener
        }
    }

    private class FakeEditor : EditorSurface {
        /** The field's text; the cursor is always at its end. */
        var text: String = ""
        var textAfterCursor: String = ""
        val predictedCommits = mutableListOf<Pair<String, String>>()

        /**
         * True while the before-cursor cache is empty although the field holds [text]: a reload
         * still in flight, or one that never ran after the field was focused again.
         */
        var cacheLost: Boolean = false

        /** What the before-cursor cache holds. */
        private val cache: String get() = if (cacheLost) "" else text

        /** The auto-capitalization setting. */
        var autoCap: Boolean = false

        /** Whether the field asks for sentence caps (`TYPE_TEXT_FLAG_CAP_SENTENCES`). */
        var capSentencesField: Boolean = true

        /** Whether the editor knows the cursor position. */
        var knownCursor: Boolean = true

        /** How often a refused commit asked for a cache reload. */
        var reloadRequests = 0

        override fun cursorPosition(): Int = text.length

        override fun glideStartsSentence(): Boolean =
            autoCap && capSentencesField && TatarWordUtils.glideStartsSentence(cache, !cacheLost)

        override fun cachedWordBeforeCursor(): String = TatarWordUtils.extractTrailingWord(cache)
        override fun hasKnownCursor(): Boolean = knownCursor
        override fun hasLetterAfterCursor(): Boolean =
            TatarWordUtils.startsWithWordCharacter(textAfterCursor)

        override fun cachedNextWordContext(): String =
            TatarWordUtils.extractNextWordContext(cache, !cacheLost)

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = false

        override fun commitPredictedWord(expectedContextWord: String, suggestion: String): Boolean {
            // The production re-checks, modeled: an empty trailing word, the live context must
            // match, and an empty context binds only at a sentence start.
            if (TatarWordUtils.extractTrailingWord(text).isNotEmpty()) return false
            if (TatarWordUtils.extractNextWordContext(text, true) != expectedContextWord) return false
            if (expectedContextWord.isEmpty() &&
                !TatarWordUtils.isSentenceStartContext(text, true)
            ) {
                return false
            }
            predictedCommits.add(expectedContextWord to suggestion)
            text += if (TatarWordUtils.needsAutoSpace(textAfterCursor)) "$suggestion " else suggestion
            return true
        }

        override fun commitGlideWord(
            expectedContextWord: String,
            suggestion: String,
            expectedTrailingWord: String,
            expectedCursor: Int,
        ): Int {
            // The glide path, modeled: an unknown cache refuses and asks for a reload; a trailing
            // word, cursor or context that changed since the gesture refuses; NO trailing space,
            // and one leading space where glideNeedsLeadingSpace holds. A letter right after the
            // cursor refuses as well.
            if (TatarWordUtils.startsWithWordCharacter(textAfterCursor)) return EditorSurface.GLIDE_COMMIT_REFUSED
            if (cacheLost && text.isNotEmpty()) {
                reloadRequests++
                return EditorSurface.GLIDE_COMMIT_REFUSED
            }
            if (expectedCursor != -1 && expectedCursor != text.length) return EditorSurface.GLIDE_COMMIT_REFUSED
            if (TatarWordUtils.extractTrailingWord(cache) != expectedTrailingWord) return EditorSurface.GLIDE_COMMIT_REFUSED
            if (TatarWordUtils.extractNextWordContext(cache, !cacheLost) != expectedContextWord) return EditorSurface.GLIDE_COMMIT_REFUSED
            val prepend = TatarWordUtils.glideNeedsLeadingSpace(cache)
            predictedCommits.add(expectedContextWord to suggestion)
            text += (if (prepend) " " else "") + suggestion
            return if (prepend) EditorSurface.GLIDE_COMMIT_PREPENDED else EditorSurface.GLIDE_COMMIT_BARE
        }

        override fun replaceGlideLiftedWord(committedWord: String, alternative: String, prependedSpace: Boolean): Boolean {
            // In place: the leading space is kept exactly as committed; the trailing word must BE
            // the committed word.
            if (TatarWordUtils.extractTrailingWord(text) != committedWord) return false
            val suffix = (if (prependedSpace) " " else "") + committedWord
            if (!text.endsWith(suffix)) return false
            text = text.dropLast(suffix.length) + (if (prependedSpace) " " else "") + alternative
            return true
        }

        override fun deleteGlideLiftedWord(committedWord: String, prependedSpace: Boolean): Boolean {
            if (TatarWordUtils.extractTrailingWord(text) != committedWord) return false
            val suffix = (if (prependedSpace) " " else "") + committedWord
            if (!text.endsWith(suffix)) return false
            text = text.dropLast(suffix.length)
            return true
        }
    }

    /** Synchronous delivery through the captured callback; the geometry arrives via the push. */
    private inner class RealBackedEngine(
        private val index: TdictPrefixIndex,
        private val composite: CompositePrefixComputer,
        personal: PersonalCandidateSource = PersonalCandidateSource.EMPTY,
    ) : EngineHandle {
        private val host = GlideDecoderHost(
            TdictGlideInventory(index), personal, index::containsWordCold,
        )
        private var callback: ResultCallback? = null
        private var latest: Any? = null
        val pushedGeometries = mutableListOf<GlideKeyGeometry?>()

        fun attach(callback: ResultCallback): RealBackedEngine {
            this.callback = callback
            return this
        }

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? {
            val token = Any().also { latest = it }
            val results = index.lookup(ImmutableUtf8Prefix.copyOf(prefixUtf8))
            callback!!.onResult(token, results, LookupKind.PREFIX)
            return token
        }

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any? {
            val token = Any().also { latest = it }
            val results = composite.predict(ImmutableUtf8Prefix.copyOf(contextWordUtf8))
            callback!!.onResult(token, results, LookupKind.NEXT_WORD)
            return token
        }

        /** When true, [requestGlide] decodes but holds the result until [deliverHeldGlide]. */
        var holdGlide = false
        private var heldGlide: (() -> Unit)? = null

        /** How many glide decodes the controller requested. */
        var glideRequests = 0

        override fun requestGlide(editorSessionId: Long, subtypeId: String, path: GlidePath): Any? {
            glideRequests++
            val token = Any().also { latest = it }
            val results = host.decodeGlide(path)
            if (holdGlide) {
                heldGlide = { callback!!.onResult(token, results, LookupKind.GLIDE) }
            } else {
                callback!!.onResult(token, results, LookupKind.GLIDE)
            }
            return token
        }

        /** Delivers the result [holdGlide] kept back, as a late decode would arrive. */
        fun deliverHeldGlide() {
            val deliver = requireNotNull(heldGlide) { "no glide result is held" }
            heldGlide = null
            deliver()
        }

        override fun updateGlideGeometry(geometry: GlideKeyGeometry?) {
            pushedGeometries.add(geometry)
            host.updateGlideGeometry(geometry)
        }

        override fun isCurrent(token: Any): Boolean = token === latest
        override fun finishInput() = Unit
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

    private inner class Harness {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val engines = LinkedHashMap<String, RealBackedEngine>()
        /** How often the controller asked for the refused-glide tick. */
        var ticks = 0
        /** Set BEFORE [start]: the personal source the Tatar engine's glide host is built with. */
        var tatarPersonal: PersonalCandidateSource = PersonalCandidateSource.EMPTY
        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { subtypeId, callback ->
                engines.getOrPut(subtypeId) {
                    when (subtypeId) {
                        PersonalSubtypes.RUSSIAN ->
                            RealBackedEngine(russianIndex, russianComputer).attach(callback)
                        else -> RealBackedEngine(tatarIndex, tatarComputer, tatarPersonal).attach(callback)
                    }
                }
            },
            { DirectExecutorService() },
            { _, _ -> null },
            false,
            { _, _ -> null },
            { null },
            { _, _ -> null },
        )

        init {
            controller.setGlideGate(GlideGate { true })
            controller.setGlideRefusalFeedback(GlideRefusalFeedback { ticks++ })
        }

        fun start(
            subtypeId: String = PersonalSubtypes.TATAR_RU,
            eligible: Boolean = true,
            glideEligible: Boolean = eligible,
        ) {
            controller.onStartInput(
                eligible = eligible, subtypeId = subtypeId, glideEligible = glideEligible)
            // The dictionary-ready notification fires on the ACTIVE slot — after onStartInput,
            // exactly like production's prepare callback.
            controller.signalDictionaryReadyForTest()
        }
    }

    // --- The pins ------------------------------------------------------------------------------

    @Test
    fun theLiftCommitsTheTop1AndShowsTheAlternatives() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        // The lift commits the top-1 through the glide's own commit path — no tap, NO auto-space.
        // The doubled twin needs loop evidence, so the no-loop path's top-1 is the PLAIN word, not
        // the more frequent сәлләм.
        assertEquals("сәләм", h.editor.text)
        assertEquals(listOf("" to "сәләм"), h.editor.predictedCommits)
        // The editor's trailing word after a glide commit IS the committed word — the strip's later
        // derivations treat it exactly as a typed word (the prefix path).
        assertEquals("сәләм", h.editor.cachedWordBeforeCursor())
        // The strip then shows the remaining candidates as tappable alternatives.
        val cells = h.strip.shown.last().filterNotNull()
        assertTrue("сәлләм must ride the alternatives, was $cells", cells.contains("сәлләм"))
        assertFalse("the committed word is not re-offered", cells.contains("сәләм"))
    }

    @Test
    fun tappingAnAlternativeReplacesTheCommittedWordAndTheChainFollows() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        h.strip.listener!!.onTap("сәлләм")

        // The alternative replaced the lift-committed word in the editor in place: no space added
        // or removed. (The plain word is the lift-commit, the doubled twin rides the alternatives.)
        assertEquals("сәлләм", h.editor.text)
        // The strip then behaves as if the word had been typed: the trailing word is "сәлләм", so
        // the band is the prefix path for it (forms), not the NEXT_WORD chain.
        assertEquals(listOf("сәлләмнең", "сәлләмгә", null), h.strip.shown.last())
    }

    @Test
    fun oneBackspaceRightAfterALiftDeletesTheWholeWord() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("сәләм", h.editor.text)
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("", h.editor.text)
    }

    @Test
    fun aBackspaceAfterTypingDeletesOnlyTheLetter() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        // The user types on: the undo window closes on the next text change.
        h.editor.text += "б"
        h.controller.onTextChanged()
        assertFalse(h.controller.maybeUndoGlideCommit())
        assertEquals("сәләмб", h.editor.text)
    }

    @Test
    fun anAlternativeReplacementMovesTheUndoToTheAlternative() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        h.strip.listener!!.onTap("сәлләм")
        assertEquals("сәлләм", h.editor.text)
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("", h.editor.text)
    }

    // --- Glide independent of the suggestions master switch ------------------------------------

    @Test
    fun theLiftCommitsWithSuggestionsOffAndTheStripShowsNothing() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start(eligible = false, glideEligible = true)
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        // The commit is typing, not a suggestion: the word lands (with NO auto-space)…
        assertEquals("сәләм", h.editor.text)
        // …and the strip — the suggestions surface — shows NOTHING: no alternatives band…
        assertTrue("no band may paint with the master off, was ${h.strip.shown}",
            h.strip.shown.isEmpty())
        // …and the undo still works: it is part of the gesture, not of the strip.
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("", h.editor.text)
    }

    @Test
    fun suggestionsOffStillHidesTheStripInNormalTyping() {
        // The master switch off keeps the band out of the typing path.
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start(eligible = false, glideEligible = true)
        h.editor.text = "с"
        h.controller.onTextChanged()
        assertTrue(h.strip.shown.isEmpty())
        assertTrue("the strip is hidden, never reserved", h.strip.hideCount > 0 && h.strip.reserveCount == 0)
    }

    @Test
    fun theLiftCommitsAtAFieldStart() {
        // The explicit field-start pin (every other test starts from an empty field too).
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals(listOf("" to "сәләм"), h.editor.predictedCommits)
        assertEquals("сәләм", h.editor.text)
    }

    @Test
    fun theLiftCommitsRightAfterAttachedSentenceFinalPunctuation() {
        // "сүз? " — a genuine sentence start.
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.editor.text = "китеп? "
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("китеп? сәләм", h.editor.text)
    }

    @Test
    fun theLiftCommitsAfterSentenceFinalPunctuationTypedWithTheSpaceHabit() {
        // "сүз ? " (a space BEFORE the mark) is context-free and NOT a sentence start (the
        // punctuation run must directly follow a letter). The prediction tap's stale-band guard
        // would refuse here; the glide commit must not, or the gesture looks dead.
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.editor.text = "Синен хэллэр ничек ? "
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("Синен хэллэр ничек ? сәләм", h.editor.text)
    }

    @Test
    fun aGlideWithShiftOnLiftCommitsTheCapitalizedTop1() {
        val h = Harness()
        h.controller.setGlideShiftStateGate(ShiftStateGate { TatarWordUtils.PrefixCasing.INITIAL_CAPS })
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("Сәләм", h.editor.text)
    }

    @Test
    fun aGlideUnderCapsLockCommitsAllCapsAndAllCapsAlternatives() {
        val h = Harness()
        h.controller.setGlideShiftStateGate(ShiftStateGate { TatarWordUtils.PrefixCasing.ALL_CAPS })
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("СӘЛӘМ", h.editor.text)
        val cells = h.strip.shown.last().filterNotNull()
        assertTrue("the doubled twin rides the alternatives in capitals, was $cells",
            cells.contains("СӘЛЛӘМ"))
        for (cell in cells) {
            assertEquals("an alternative carries the Caps Lock casing", cell.uppercase(), cell)
        }
    }

    @Test
    fun aGlideOnTheRussianLayoutLiftCommitsAgainstTheRussianDictionary() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.russianGeometry())
        h.start(PersonalSubtypes.RUSSIAN)
        h.controller.onGlideInput(GlideTestFixtures.idealPath("работа", GlideTestFixtures.russianGeometry())!!)
        // The top-1 of the Russian decode is committed on lift (NO trailing space); the
        // alternatives show the rest.
        assertTrue(h.editor.text.isNotEmpty())
        assertFalse("P7-7: a glide commits no auto-space", h.editor.text.endsWith(" "))
        assertTrue(h.strip.shown.isNotEmpty())
    }

    @Test
    fun aTatarWordWithTheHardSignDecodesFromAGestureOverTheSoftSignKey() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        // The ideal path visits the ь key for ъ: the word has no key of its own for that letter.
        h.controller.onGlideInput(GlideTestFixtures.idealPath("вәгъдә", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("вәгъдә", h.editor.text)
    }

    @Test
    fun aRussianWordWithYoDecodesFromAGestureOverTheIeKey() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.russianGeometry())
        h.start(PersonalSubtypes.RUSSIAN)
        h.controller.onGlideInput(
            GlideTestFixtures.idealPath("одноимённый", GlideTestFixtures.russianGeometry())!!,
        )
        assertEquals("одноимённый", h.editor.text)
    }

    /**
     * One globe-key step in LatinIME's order: the new layout's geometry is published while the
     * language being left is still active, then the controller switches language.
     */
    private fun Harness.switchTo(subtypeId: String, geometry: GlideKeyGeometry?) {
        controller.updateGlideGeometry(geometry)
        controller.onSubtypeChanged(true, subtypeId, true)
    }

    @Test
    fun aTatarGlideCommitsAfterACycleThroughRussianAndEnglish() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.switchTo(PersonalSubtypes.RUSSIAN, GlideTestFixtures.russianGeometry())
        h.controller.signalDictionaryReadyForTest()
        h.switchTo("en_US", null)
        h.switchTo(PersonalSubtypes.TATAR_RU, GlideTestFixtures.tatarGeometry())
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("сәләм", h.editor.text)
    }

    @Test
    fun aRussianGlideCommitsAfterACycleThroughEnglish() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.russianGeometry())
        h.start(PersonalSubtypes.RUSSIAN)
        h.switchTo("en_US", null)
        h.switchTo(PersonalSubtypes.RUSSIAN, GlideTestFixtures.russianGeometry())
        h.controller.onGlideInput(GlideTestFixtures.idealPath("работа", GlideTestFixtures.russianGeometry())!!)
        assertEquals("работа", h.editor.text)
    }

    @Test
    fun theGlideToggleOffCommitsAndShowsNothing() {
        val h = Harness()
        h.controller.setGlideGate(GlideGate { false })
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        val shownBefore = h.strip.shown.size
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals(shownBefore, h.strip.shown.size)
        assertTrue(h.editor.predictedCommits.isEmpty())
        assertEquals("", h.editor.text)
    }

    @Test
    fun aGlideAfterAHalfTypedWordStartsANewWord() {
        // The typed prefix is not completed: the glide word follows it after one space.
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.editor.text = "та"
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("та сәләм", h.editor.text)
    }

    @Test
    fun aGlideWithoutGeometryCommitsNothing() {
        val h = Harness()
        // No updateGlideGeometry at all: nothing is decoded, exactly like a missing layout.
        h.start()
        val shownBefore = h.strip.shown.size
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals(shownBefore, h.strip.shown.size)
        assertEquals("", h.editor.text)
        assertTrue(h.editor.predictedCommits.isEmpty())
    }

    @Test
    fun aSessionBumpBetweenTheGestureAndATapKeepsTheTapInert() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("сәләм", h.editor.text)
        // The alternatives band was painted for the pre-bump session: a tap must not edit.
        h.controller.onSelectionChanged()
        h.strip.listener!!.onTap("сәлләм")
        assertEquals("сәләм", h.editor.text)
    }

    @Test
    fun aLiftThatDecodesToNothingCommitsNothing() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        // A gesture far below the keyboard (every key is above it): no candidate's location channel
        // survives — the decode is empty, nothing is committed.
        val path = GlidePath()
        var x = 5_000f
        for (i in 0 until 40) {
            path.addPoint(x, 60_000f, i * 8f)
            x += 2_000f
        }
        h.controller.onGlideInput(path)
        assertEquals("", h.editor.text)
        assertTrue(h.editor.predictedCommits.isEmpty())
    }

    @Test
    fun typingAfterALiftCommitDissolvesTheAlternatives() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        // A typed letter: the alternatives are unbound — a tap on a stale alternative is inert.
        // (The fake appends the letter as is; the phantom space before it is InputLogic's. The
        // new prefix "сәләмб" has no band of its own, so the pin is the tap's inertness.)
        h.editor.text += "б"
        h.controller.onTextChanged()
        h.strip.listener!!.onTap("сәлләм")
        assertEquals("сәләмб", h.editor.text)
    }

    // --- Personal-dictionary glide candidates ----------------------------------------------------

    @Test
    fun aLearnedWordLiftCommitsAndTheStripShowsTheAlternatives() {
        // "сәлинә" is NOT in the bundled Tatar dictionary — only the personal side can produce it.
        // The membership guard keeps the test valid: if a dictionary rebuild ever adds the word,
        // pick another one.
        assertFalse(tatarIndex.containsWordCold("сәлинә"))
        val snapshot = GlideTestFixtures.personalDictionary("сәлинә" to 7)
        val h = Harness()
        h.tatarPersonal = SnapshotPersonalCandidateSource { snapshot }
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәлинә", GlideTestFixtures.tatarGeometry())!!)

        // The lift commits the learned word top-1 (no auto-space)…
        assertEquals("сәлинә", h.editor.text)
        assertEquals(listOf("" to "сәлинә"), h.editor.predictedCommits)
        // …and the strip shows the decode's remaining candidates as tappable alternatives,
        // exactly like a dictionary word's band; the committed word is not re-offered.
        val cells = h.strip.shown.last().filterNotNull()
        assertTrue("the decode tail must ride the strip, was $cells", cells.isNotEmpty())
        assertFalse("the committed word is not re-offered", cells.contains("сәлинә"))
        // The undo window treats it like any glide commit.
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("", h.editor.text)
    }

    // --- Glide-triggered learning: the commit counts as an acceptance ---------------------------

    /** Records the word sink's acceptance events; membership is the store side's business. */
    private class RecordingWordSink : WordCompletionSink {
        val accepted = mutableListOf<String>()
        override fun onCleanCompletion(word: String) = Unit
        override fun onAcceptedSuggestion(word: String) {
            accepted.add(word)
        }
    }

    @Test
    fun theLiftCommitAnnouncesAnAcceptedSuggestionToTheWordSink() {
        val h = Harness()
        val sink = RecordingWordSink()
        h.controller.setCompletionSink(sink)
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("сәләм", h.editor.text)
        // The lift-commit IS the acceptance — the sink hears the committed word exactly as if it
        // had been tapped in the strip.
        assertEquals(listOf("сәләм"), sink.accepted)
    }

    @Test
    fun anAlternativeReplacementAnnouncesTheAlternative() {
        val h = Harness()
        val sink = RecordingWordSink()
        h.controller.setCompletionSink(sink)
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        h.strip.listener!!.onTap("сәлләм")
        assertEquals("сәлләм", h.editor.text)
        // The lift counted the top-1; the explicit replacement counts the alternative.
        assertEquals(listOf("сәләм", "сәлләм"), sink.accepted)
    }

    @Test
    fun aRefusedGlideCommitAnnouncesNothing() {
        val h = Harness()
        val sink = RecordingWordSink()
        h.controller.setCompletionSink(sink)
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        // A letter typed between the lift and the result makes the commit path refuse: nothing
        // committed, nothing announced.
        h.editor.text = "та"
        h.engines.getValue(PersonalSubtypes.TATAR_RU).holdGlide = true
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        h.editor.text += "б"
        h.engines.getValue(PersonalSubtypes.TATAR_RU).deliverHeldGlide()
        assertEquals("таб", h.editor.text)
        assertTrue(sink.accepted.isEmpty())
    }

    @Test
    fun theUndoDoesNotRollBackTheAcceptance() {
        val h = Harness()
        val sink = RecordingWordSink()
        h.controller.setCompletionSink(sink)
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("", h.editor.text)
        // Pinned imprecision: the undo deletes the word from the editor, not its use record —
        // the tap path lives with the same rule (a bump survives a later backspace).
        assertEquals(listOf("сәләм"), sink.accepted)
    }

    @Test
    fun theLiftCommitAnnouncesWithTheSuggestionsMasterOff() {
        val h = Harness()
        val sink = RecordingWordSink()
        h.controller.setCompletionSink(sink)
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start(eligible = false, glideEligible = true)
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("сәләм", h.editor.text)
        // The commit is typing, not a suggestion — and the learning event fires anyway; the
        // predicate on the sink's side (LatinIME) is the only gate.
        assertEquals(listOf("сәләм"), sink.accepted)
    }

    // --- No trailing space: the leading space is the only separator ----------------------------

    @Test
    fun aSecondGlideChainsWithExactlyOnePrependedSpace() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("сәләм", h.editor.text)
        // The chain: the trailing word is the previous glide commit, so the second gesture is
        // allowed and prepends ONE space — and nothing trails.
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("сәләм сәләм", h.editor.text)
        assertEquals(listOf("" to "сәләм", "" to "сәләм"), h.editor.predictedCommits)
    }

    @Test
    fun undoingTheSecondGlideOfAChainReturnsToExactlyTheFirstWord() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("сәләм сәләм", h.editor.text)
        // The undo takes the prepended leading space along with the word.
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("сәләм", h.editor.text)
    }

    @Test
    fun aGlideAfterAUserTypedSpaceAddsNoSeparatorOfItsOwn() {
        // "сүз " typed (the user's own space): the trailing word is empty, the commit is bare.
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.editor.text = "сүз "
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("сүз сәләм", h.editor.text)
        // The undo takes ONLY the word: the user's own space is not the commit's.
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("сүз ", h.editor.text)
    }

    @Test
    fun aChainThroughAnAlternativeKeepsTheLeadingSpace() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        h.strip.listener!!.onTap("сәлләм") // fix the first word in place
        assertEquals("сәлләм", h.editor.text)
        // The chain continues after the replacement: the undo word is the alternative.
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("сәлләм сәләм", h.editor.text)
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("сәлләм", h.editor.text)
    }

    // --- The leading space: after punctuation, a typed word, a digit --------------------------

    /** A started Tatar harness whose field already holds [text]. */
    private fun tatarHarnessWith(text: String): Harness {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.editor.text = text
        return h
    }

    private fun Harness.glideSalam() {
        controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
    }

    private fun Harness.tatarEngine(): RealBackedEngine = engines.getValue(PersonalSubtypes.TATAR_RU)

    @Test
    fun aGlideAfterACommaGetsOneLeadingSpace() {
        val h = tatarHarnessWith("сүз,")
        h.glideSalam()
        assertEquals("сүз, сәләм", h.editor.text)
    }

    @Test
    fun aGlideAfterEveryMarkThatSwapsWithTheAutoSpaceGetsOneLeadingSpace() {
        for (mark in listOf(".", ",", ";", ":", "!", "?", ")", "]", "}")) {
            val h = tatarHarnessWith("сүз$mark")
            h.glideSalam()
            assertEquals("after '$mark'", "сүз$mark сәләм", h.editor.text)
        }
    }

    @Test
    fun aGlideAfterATypedWordGetsOneLeadingSpace() {
        val h = tatarHarnessWith("сүз")
        h.glideSalam()
        assertEquals("сүз сәләм", h.editor.text)
    }

    @Test
    fun aGlideIntoAFieldWhoseCacheWasNotReloadedNeverGlues() {
        // The field ends in a word, but the before-cursor cache is empty (the reload after the
        // field was focused again has not landed). The commit must not guess: nothing is edited,
        // and a reload is requested so the next gesture sees the text.
        val h = tatarHarnessWith("сүз")
        h.editor.cacheLost = true
        h.glideSalam()
        assertEquals("сүз", h.editor.text)
        assertTrue(h.editor.predictedCommits.isEmpty())
        assertEquals(1, h.editor.reloadRequests)
        // The reload lands; the redone gesture gets its space.
        h.editor.cacheLost = false
        h.glideSalam()
        assertEquals("сүз сәләм", h.editor.text)
    }

    @Test
    fun aCacheThatFillsBetweenTheGestureAndTheResultRefusesTheCommit() {
        val h = tatarHarnessWith("сүз")
        h.editor.cacheLost = true
        h.tatarEngine().holdGlide = true
        h.glideSalam()
        // The reload lands before the decode result: the trailing word differs from the one the
        // gesture saw, so the commit is refused rather than glued.
        h.editor.cacheLost = false
        h.tatarEngine().deliverHeldGlide()
        assertEquals("сүз", h.editor.text)
        assertTrue(h.editor.predictedCommits.isEmpty())
    }

    @Test
    fun aLetterTypedAfterACommaBeforeTheResultRefusesTheCommit() {
        val h = tatarHarnessWith("сүз,")
        h.tatarEngine().holdGlide = true
        h.glideSalam()
        h.editor.text += "а"
        h.tatarEngine().deliverHeldGlide()
        assertEquals("сүз,а", h.editor.text)
        assertTrue(h.editor.predictedCommits.isEmpty())
    }

    @Test
    fun aMarkTypedAfterACommaBeforeTheResultRefusesTheCommit() {
        // The trailing word stays "" and the context stays "": only the cursor shows the edit.
        val h = tatarHarnessWith("сүз,")
        h.tatarEngine().holdGlide = true
        h.glideSalam()
        h.editor.text += ","
        h.tatarEngine().deliverHeldGlide()
        assertEquals("сүз,,", h.editor.text)
        assertTrue(h.editor.predictedCommits.isEmpty())
    }

    @Test
    fun aLetterTypedAfterAChainedGlideBeforeTheResultRefusesTheCommit() {
        val h = tatarHarnessWith("")
        h.glideSalam()
        assertEquals("сәләм", h.editor.text)
        h.tatarEngine().holdGlide = true
        h.glideSalam()
        h.editor.text += "б"
        h.tatarEngine().deliverHeldGlide()
        assertEquals("сәләмб", h.editor.text)
        assertEquals(1, h.editor.predictedCommits.size)
    }

    @Test
    fun aGlideAfterADigitGetsOneLeadingSpace() {
        val h = tatarHarnessWith("5")
        h.glideSalam()
        assertEquals("5 сәләм", h.editor.text)
    }

    @Test
    fun noLeadingSpaceAfterWhitespaceBracketsOpeningQuotesDashesOrEmoji() {
        for (before in listOf(
            "сүз\n", "сүз\t", "(", "[", "«", "\"", "сүз «", "\u201C", "сүз \u201E", "сүз \"", "(\"",
            "сүз-", "сүз —", "сүз 🙂",
        )) {
            val h = tatarHarnessWith(before)
            h.glideSalam()
            assertEquals("after '$before'", before + "сәләм", h.editor.text)
        }
    }

    @Test
    fun aGlideAfterAClosingQuoteGetsOneLeadingSpace() {
        // A closing quote ends the quoted text like a word: », ” and a straight quote that
        // follows a non-space. Consecutive glides keep exactly one space each.
        for (before in listOf("«сүз»", "сүз\u201D", "\"сүз\"", "сүз.\"")) {
            val h = tatarHarnessWith(before)
            h.glideSalam()
            assertEquals("after '$before'", "$before сәләм", h.editor.text)
            h.glideSalam()
            assertEquals("chained after '$before'", "$before сәләм сәләм", h.editor.text)
        }
    }

    @Test
    fun oneBackspaceAfterAGlideAfterAClosingQuoteRemovesTheWordAndItsSpace() {
        val h = tatarHarnessWith("«сүз»")
        h.glideSalam()
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("«сүз»", h.editor.text)
    }

    @Test
    fun aGlideBeforePunctuationAfterTheCursorStillCommits() {
        val h = tatarHarnessWith("сүз")
        h.editor.textAfterCursor = ", дус"
        h.glideSalam()
        assertEquals("сүз сәләм", h.editor.text)
    }

    @Test
    fun oneBackspaceAfterAGlideAfterACommaRemovesTheWordAndItsSpace() {
        val h = tatarHarnessWith("сүз,")
        h.glideSalam()
        assertEquals("сүз, сәләм", h.editor.text)
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("сүз,", h.editor.text)
    }

    @Test
    fun oneBackspaceAfterAGlideAfterATypedWordRemovesTheWordAndItsSpace() {
        val h = tatarHarnessWith("сүз")
        h.glideSalam()
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("сүз", h.editor.text)
    }

    @Test
    fun anAlternativeAfterACommaKeepsTheLeadingSpace() {
        val h = tatarHarnessWith("сүз,")
        h.glideSalam()
        h.strip.listener!!.onTap("сәлләм")
        assertEquals("сүз, сәлләм", h.editor.text)
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("сүз,", h.editor.text)
    }

    // --- Sentence capitalization of the leading space ------------------------------------------

    @Test
    fun aGlideAfterAPeriodIsCapitalizedWithAutoCapOn() {
        for (mark in listOf(".", "!", "?")) {
            val h = tatarHarnessWith("сүз$mark")
            h.editor.autoCap = true
            h.glideSalam()
            assertEquals("after '$mark'", "сүз$mark Сәләм", h.editor.text)
            // The alternatives carry the same casing.
            for (cell in h.strip.shown.last().filterNotNull()) {
                assertTrue("an alternative is capitalized too, was $cell", cell[0].isUpperCase())
            }
        }
    }

    @Test
    fun aGlideAfterAPeriodStaysLowerCaseWithAutoCapOff() {
        val h = tatarHarnessWith("сүз.")
        h.glideSalam()
        assertEquals("сүз. сәләм", h.editor.text)
    }

    @Test
    fun aGlideAfterACommaStaysLowerCaseWithAutoCapOn() {
        val h = tatarHarnessWith("сүз,")
        h.editor.autoCap = true
        h.glideSalam()
        assertEquals("сүз, сәләм", h.editor.text)
    }

    @Test
    fun aGlideAfterAPeriodStaysLowerCaseInAFieldWithoutSentenceCaps() {
        // The field flags win, as for typed letters: without TYPE_TEXT_FLAG_CAP_SENTENCES the
        // keyboard does not shift after ". ", so the glided word stays lower case too.
        val h = tatarHarnessWith("сүз.")
        h.editor.autoCap = true
        h.editor.capSentencesField = false
        h.glideSalam()
        assertEquals("сүз. сәләм", h.editor.text)
        for (cell in h.strip.shown.last().filterNotNull()) {
            assertFalse("an alternative stays lower case too, was $cell", cell[0].isUpperCase())
        }
    }

    @Test
    fun capsLockKeepsItsMeaningAfterAPeriod() {
        val h = tatarHarnessWith("сүз.")
        h.editor.autoCap = true
        h.controller.setGlideShiftStateGate(ShiftStateGate { TatarWordUtils.PrefixCasing.ALL_CAPS })
        h.glideSalam()
        assertEquals("сүз. СӘЛӘМ", h.editor.text)
    }

    // --- Feedback for a refused glide ---------------------------------------------------------

    @Test
    fun aCommittedGlideGivesNoTick() {
        val h = tatarHarnessWith("сүз")
        h.glideSalam()
        assertEquals("сүз сәләм", h.editor.text)
        assertEquals(0, h.ticks)
    }

    @Test
    fun aGlideBeforeALetterOnlyTicksAndKeepsTheStrip() {
        val h = tatarHarnessWith("сүз ")
        h.editor.textAfterCursor = "дус"
        h.controller.onTextChanged()
        val shown = h.strip.shown.size
        val reserves = h.strip.reserveCount
        val hides = h.strip.hideCount
        h.glideSalam()
        assertEquals("сүз ", h.editor.text)
        assertTrue(h.editor.predictedCommits.isEmpty())
        assertEquals(1, h.ticks)
        // Nothing is decoded and the strip is left as it was: a candidate shown here could not
        // be inserted by a tap, since the same letter refuses the commit.
        assertEquals(0, h.tatarEngine().glideRequests)
        assertEquals(shown, h.strip.shown.size)
        assertEquals(reserves, h.strip.reserveCount)
        assertEquals(hides, h.strip.hideCount)
        h.strip.listener!!.onTap("сәләм")
        assertEquals("сүз ", h.editor.text)
        assertEquals(1, h.ticks)
    }

    @Test
    fun aLetterAfterTheCursorByTheResultTicksAndShowsNoCandidates() {
        val h = tatarHarnessWith("сүз ")
        h.tatarEngine().holdGlide = true
        h.glideSalam()
        val shown = h.strip.shown.size
        h.editor.textAfterCursor = "дус"
        h.tatarEngine().deliverHeldGlide()
        assertEquals("сүз ", h.editor.text)
        assertEquals(1, h.ticks)
        assertEquals("no refused candidates may paint, was ${h.strip.shown}", shown, h.strip.shown.size)
        // Nothing is bound, so a tap is inert even once the letter is gone.
        h.editor.textAfterCursor = ""
        h.strip.listener!!.onTap("сәләм")
        assertEquals("сүз ", h.editor.text)
        assertEquals(1, h.ticks)
    }

    @Test
    fun aStaleGlideIsRefusedWithATickAndATapInsertsWithTheGlideSpacing() {
        val h = tatarHarnessWith("сүз,")
        h.tatarEngine().holdGlide = true
        h.glideSalam()
        h.editor.text += "а"
        h.tatarEngine().deliverHeldGlide()
        assertEquals("сүз,а", h.editor.text)
        assertEquals(1, h.ticks)
        val cells = h.strip.shown.last()
        assertEquals("сәләм", cells[0])
        // The tap commits at the live cursor, with the glide's leading space and no trailing one.
        h.strip.listener!!.onTap(cells[1]!!)
        assertEquals("сүз,а ${cells[1]}", h.editor.text)
        // The whole-word undo covers the tapped word and its space.
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("сүз,а", h.editor.text)
    }

    @Test
    fun aGlideIntoAnUnknownCacheShowsItsCandidatesAndATapAfterTheReloadInsertsWithTheGlideSpacing() {
        for ((field, inserted) in listOf(
            "сүз" to "сүз сәләм",
            "сүз," to "сүз, сәләм",
            "сүз " to "сүз сәләм",
            "(" to "(сәләм",
        )) {
            val h = tatarHarnessWith(field)
            h.editor.cacheLost = true
            h.glideSalam()
            assertEquals(field, h.editor.text)
            assertEquals(1, h.ticks)
            assertEquals(1, h.editor.reloadRequests)
            assertEquals("сәләм", h.strip.shown.last()[0])
            // A tap before the reload lands is refused the same way: no edit, one more tick and
            // reload request, and the candidates stay.
            h.strip.listener!!.onTap("сәләм")
            assertEquals(field, h.editor.text)
            assertEquals(2, h.ticks)
            assertEquals(2, h.editor.reloadRequests)
            // The reload lands and asks the strip to refresh; the bound candidates stay.
            h.editor.cacheLost = false
            h.controller.onCursorMoveSettled()
            assertEquals("сәләм", h.strip.shown.last()[0])
            // The tap inserts with the glide's spacing for the reloaded text: one leading space
            // where one is needed, none after a space or an opening bracket, no trailing space.
            h.strip.listener!!.onTap("сәләм")
            assertEquals("after '$field'", inserted, h.editor.text)
            assertEquals(2, h.ticks)
            // The whole-word undo covers the tapped word and the space it added.
            assertTrue(h.controller.maybeUndoGlideCommit())
            assertEquals(field, h.editor.text)
        }
    }

    @Test
    fun aRefusedCandidateIsInertAfterTheSessionMoves() {
        val h = tatarHarnessWith("сүз")
        h.editor.cacheLost = true
        h.glideSalam()
        h.controller.onSelectionChanged()
        h.editor.cacheLost = false
        h.strip.listener!!.onTap("сәләм")
        assertEquals("сүз", h.editor.text)
    }

    @Test
    fun aRefusedGlideWithSuggestionsOffTicksAndShowsNothing() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start(eligible = false, glideEligible = true)
        h.editor.text = "сүз "
        h.editor.textAfterCursor = "дус"
        h.glideSalam()
        assertEquals("сүз ", h.editor.text)
        assertEquals(1, h.ticks)
        // The stale case reaches the commit path and ticks there.
        h.editor.textAfterCursor = ""
        h.tatarEngine().holdGlide = true
        h.glideSalam()
        h.editor.text += "а"
        h.tatarEngine().deliverHeldGlide()
        assertEquals("сүз а", h.editor.text)
        assertEquals(2, h.ticks)
        assertTrue("no strip with suggestions off, was ${h.strip.shown}", h.strip.shown.isEmpty())
    }

    @Test
    fun aGlideWithAnUnknownCursorTicks() {
        val h = tatarHarnessWith("сүз")
        h.editor.knownCursor = false
        h.glideSalam()
        assertEquals("сүз", h.editor.text)
        assertEquals(1, h.ticks)
    }

    // --- Real assets -----------------------------------------------------------------------------

    companion object {
        private lateinit var tatarIndex: TdictPrefixIndex
        private lateinit var russianIndex: TdictPrefixIndex
        private lateinit var tatarComputer: CompositePrefixComputer
        private lateinit var russianComputer: CompositePrefixComputer

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssets() {
            tatarIndex = openDictionary(DictionaryArtifactSpec.TATAR_TOP100K_V1)
            russianIndex = openDictionary(DictionaryArtifactSpec.RUSSIAN_TOP100K_V1)
            // The production NEXT_WORD shapes (see TtNextWordFillE2ETest): Tatar — suffix rules +
            // forms + fallback + bigrams; Russian — no word forms.
            tatarComputer = CompositePrefixComputer(
                tatarIndex, PersonalCandidateSource.EMPTY,
                TatarSuffixRules.createAfterWordForms(tatarIndex),
                GlobalTopFrequencyFallbackFactory.createFallbackWords(tatarIndex),
            ).also {
                it.attachBigramSource(openBigrams(BigramArtifactSpec.TATAR_BIGRAMS_V1, tatarIndex))
            }
            russianComputer = CompositePrefixComputer(
                russianIndex, PersonalCandidateSource.EMPTY,
                null,
                GlobalTopFrequencyFallbackFactory.createFallbackWords(russianIndex),
            )
        }

        private fun openDictionary(spec: DictionaryArtifactSpec): TdictPrefixIndex {
            val raw = inflate(spec)
            val identity = DictionaryIdentity(
                spec.generation, spec.schemaId, spec.formatVersion,
                MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) },
            )
            return requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw), identity, spec.expectedEntryCount, raw.size.toLong(),
                ),
            )
        }

        private fun openBigrams(spec: BigramArtifactSpec, dictionary: TdictPrefixIndex): TatBigrPrefixIndex {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("glide-e2e-bigr-", ".tatbigr")
            try {
                rawFile.outputStream().use { output ->
                    TatBigrValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TatBigrValidator().validateRaw(rawFile, spec)
                val identity = BigramTableIdentity(
                    spec.generation, spec.fileLanguageTag, validated.schemaId,
                    validated.formatVersion, validated.rawSha256,
                )
                return requireNotNull(
                    TatBigrPrefixIndex.open(
                        ByteBuffer.wrap(rawFile.readBytes()), identity, dictionary,
                        validated.headCount, validated.rawSize,
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun inflate(spec: DictionaryArtifactSpec): ByteArray {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("glide-e2e-dict-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                TdictValidator().validateRaw(rawFile, spec)
                return rawFile.readBytes()
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed test resource")
    }
}
