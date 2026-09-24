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
 * P7-3 (docs/GLIDE-PLAN.md): the glide path end to end — controller, real shipped assets (the
 * Tatar and Russian dictionaries and the Tatar bigram table), the fixture layout geometries, and
 * the real decoder. The engine handle is backed by the production machinery ([TdictPrefixIndex],
 * [CompositePrefixComputer], [GlideDecoderHost]) and delivers synchronously, so the whole flow —
 * gesture → decode → band → tap → commit → NEXT_WORD chain — is exercised with no emulator.
 */
class GlideEndToEndTest {

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        val shown = mutableListOf<Triple<String, String?, String?>>()
        var reserveCount = 0
        var hideCount = 0
        var listener: SuggestionTapListener? = null

        override fun showSuggestions(first: String, second: String?, third: String?) {
            shown.add(Triple(first, second, third))
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

        override fun cachedWordBeforeCursor(): String = TatarWordUtils.extractTrailingWord(text)
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean =
            TatarWordUtils.startsWithWordCharacter(textAfterCursor)

        override fun cachedNextWordContext(): String =
            TatarWordUtils.extractNextWordContext(text, true)

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

        override fun commitGlideWord(expectedContextWord: String, suggestion: String): Boolean {
            // The P7-6 glide path, modeled: the same re-checks MINUS the sentence-start
            // requirement — a gesture at a context-free position still commits.
            if (TatarWordUtils.extractTrailingWord(text).isNotEmpty()) return false
            if (TatarWordUtils.extractNextWordContext(text, true) != expectedContextWord) return false
            predictedCommits.add(expectedContextWord to suggestion)
            text += if (TatarWordUtils.needsAutoSpace(textAfterCursor)) "$suggestion " else suggestion
            return true
        }

        override fun replaceGlideLiftedWord(committedWord: String, alternative: String): Boolean {
            val withSpace = "$committedWord "
            if (!text.endsWith(withSpace) && !text.endsWith(committedWord)) return false
            text = text.dropLast(if (text.endsWith(withSpace)) withSpace.length else committedWord.length)
            text += if (TatarWordUtils.needsAutoSpace(textAfterCursor)) "$alternative " else alternative
            return true
        }

        override fun deleteGlideLiftedWord(committedWord: String): Boolean {
            val withSpace = "$committedWord "
            if (!text.endsWith(withSpace) && !text.endsWith(committedWord)) return false
            text = text.dropLast(if (text.endsWith(withSpace)) withSpace.length else committedWord.length)
            return true
        }
    }

    /** Synchronous delivery through the captured callback; the geometry arrives via the push. */
    private inner class RealBackedEngine(
        private val index: TdictPrefixIndex,
        private val composite: CompositePrefixComputer,
    ) : EngineHandle {
        private val host = GlideDecoderHost(TdictGlideInventory(index))
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

        override fun requestGlide(editorSessionId: Long, subtypeId: String, path: GlidePath): Any? {
            val token = Any().also { latest = it }
            val results = host.decodeGlide(path)
            callback!!.onResult(token, results, LookupKind.GLIDE)
            return token
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
        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { subtypeId, callback ->
                engines.getOrPut(subtypeId) {
                    when (subtypeId) {
                        PersonalSubtypes.RUSSIAN ->
                            RealBackedEngine(russianIndex, russianComputer).attach(callback)
                        else -> RealBackedEngine(tatarIndex, tatarComputer).attach(callback)
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
        // The UX amendment (docs/ROADMAP-P7.md): the lift itself commits the top-1, with
        // auto-space, through the predicted-word path — no tap. сәлләм/сәләм are the documented
        // degenerate-path pair, so the top-1 is сәлләм by the frequency prior.
        assertEquals("сәлләм ", h.editor.text)
        assertEquals(listOf("" to "сәлләм"), h.editor.predictedCommits)
        // The strip then shows the remaining candidates as tappable alternatives — сәләм is one.
        val last = h.strip.shown.last()
        val cells = listOfNotNull(last.first, last.second, last.third)
        assertTrue("сәләм must ride the alternatives, was $cells", cells.contains("сәләм"))
        assertFalse("the committed word is not re-offered", cells.contains("сәлләм"))
    }

    @Test
    fun tappingAnAlternativeReplacesTheCommittedWordAndTheChainFollows() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        h.strip.listener!!.onTap("сәләм")

        // The alternative replaced the lift-committed word in the editor.
        assertEquals("сәләм ", h.editor.text)
        // The strip refreshed to the NEXT_WORD chain for the committed word — the pinned chain
        // of TtNextWordFillE2ETest (form first, then the global top words).
        val last = h.strip.shown.last()
        assertEquals(Triple("сәләмә", "һәм", "белән"), last)
    }

    @Test
    fun oneBackspaceRightAfterALiftDeletesTheWholeWord() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("сәлләм ", h.editor.text)
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
        assertEquals("сәлләм б", h.editor.text)
    }

    @Test
    fun anAlternativeReplacementMovesTheUndoToTheAlternative() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        h.strip.listener!!.onTap("сәләм")
        assertEquals("сәләм ", h.editor.text)
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("", h.editor.text)
    }

    // --- P7-6: glide independent of the suggestions master (the 2026-09-24 field reports) ------

    @Test
    fun theLiftCommitsWithSuggestionsOffAndTheStripShowsNothing() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start(eligible = false, glideEligible = true)
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        // The commit is typing, not a suggestion: the word lands with its auto-space…
        assertEquals("сәлләм ", h.editor.text)
        // …and the strip — the suggestions surface — shows NOTHING: no alternatives band…
        assertTrue("no band may paint with the master off, was ${h.strip.shown}",
            h.strip.shown.isEmpty())
        // …and the undo still works: it is part of the gesture, not of the strip.
        assertTrue(h.controller.maybeUndoGlideCommit())
        assertEquals("", h.editor.text)
    }

    @Test
    fun suggestionsOffStillHidesTheStripInNormalTyping() {
        // The regression pin of the unhook: the master off keeps the band out of the typing path.
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
        assertEquals(listOf("" to "сәлләм"), h.editor.predictedCommits)
        assertEquals("сәлләм ", h.editor.text)
    }

    @Test
    fun theLiftCommitsRightAfterAttachedSentenceFinalPunctuation() {
        // "сүз? " — a genuine sentence start; worked before P7-6 as well.
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.editor.text = "китеп? "
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("китеп? сәлләм ", h.editor.text)
    }

    @Test
    fun theLiftCommitsAfterSentenceFinalPunctuationTypedWithTheSpaceHabit() {
        // The P7-6 field report: "сүз ? " (a space BEFORE the mark) is context-free and NOT a
        // sentence start (the punctuation run must directly follow a letter), so the prediction
        // tap's stale-band guard refused the lift-commit here and the gesture looked dead.
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.editor.text = "Синен хэллэр ничек ? "
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("Синен хэллэр ничек ? сәлләм ", h.editor.text)
    }

    @Test
    fun aGlideWithShiftOnLiftCommitsTheCapitalizedTop1() {
        val h = Harness()
        h.controller.setGlideShiftStateGate(ShiftStateGate { true })
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals("Сәлләм ", h.editor.text)
    }

    @Test
    fun aGlideOnTheRussianLayoutLiftCommitsAgainstTheRussianDictionary() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.russianGeometry())
        h.start(PersonalSubtypes.RUSSIAN)
        h.controller.onGlideInput(GlideTestFixtures.idealPath("работа", GlideTestFixtures.russianGeometry())!!)
        // The top-1 of the Russian decode is committed on lift; the alternatives show the rest.
        assertTrue(h.editor.text.isNotEmpty())
        assertTrue(h.editor.text.endsWith(" "))
        assertTrue(h.strip.shown.isNotEmpty())
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
    fun aGlideWithAHalfTypedWordInFrontDoesNothing() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.editor.text = "та"
        val shownBefore = h.strip.shown.size
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals(shownBefore, h.strip.shown.size)
        assertTrue(h.editor.predictedCommits.isEmpty())
        assertEquals("та", h.editor.text)
    }

    @Test
    fun aGlideWithoutGeometryCommitsNothing() {
        val h = Harness()
        // No updateGlideGeometry at all: fail-closed, exactly like a missing layout.
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
        assertEquals("сәлләм ", h.editor.text)
        // The alternatives band was painted for the pre-bump session: a tap must not edit.
        h.controller.onSelectionChanged()
        h.strip.listener!!.onTap("сәләм")
        assertEquals("сәлләм ", h.editor.text)
    }

    @Test
    fun aLiftThatDecodesToNothingCommitsNothing() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        // A gesture far below the keyboard (every key is above it): no candidate's location
        // channel survives — the decode is empty, nothing is committed (fail-closed).
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
        val lastGlide = h.strip.shown.last()
        // A typed letter: the alternatives dissolve and the prefix band takes over.
        h.editor.text += "б"
        h.controller.onTextChanged()
        val last = h.strip.shown.last()
        assertTrue("the strip moved on to the typed prefix's band", last != lastGlide)
        assertEquals("сәлләм б", h.editor.text)
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
