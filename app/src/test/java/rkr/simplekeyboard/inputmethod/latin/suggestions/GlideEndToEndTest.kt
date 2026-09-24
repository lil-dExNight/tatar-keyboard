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
        var word: String = ""
        var nextWordContext: String = ""
        var textAfterCursor: String = ""
        val predictedCommits = mutableListOf<Pair<String, String>>()

        override fun cachedWordBeforeCursor(): String = word
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean =
            TatarWordUtils.startsWithWordCharacter(textAfterCursor)

        override fun cachedNextWordContext(): String = nextWordContext

        override fun commitSuggestion(expectedPrefix: String, suggestion: String): Boolean = false

        override fun commitPredictedWord(expectedContextWord: String, suggestion: String): Boolean {
            predictedCommits.add(expectedContextWord to suggestion)
            // The production cache model (see SuggestionsControllerTest): with the auto-space
            // appended the trailing word empties and the committed word becomes the context.
            if (TatarWordUtils.needsAutoSpace(textAfterCursor)) {
                word = ""
                nextWordContext = suggestion
            } else {
                word = suggestion
                nextWordContext = ""
            }
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

        fun start(subtypeId: String = PersonalSubtypes.TATAR_RU) {
            controller.onStartInput(eligible = true, subtypeId = subtypeId)
            // The dictionary-ready notification fires on the ACTIVE slot — after onStartInput,
            // exactly like production's prepare callback.
            controller.signalDictionaryReadyForTest()
        }
    }

    // --- The pins ------------------------------------------------------------------------------

    @Test
    fun aGlideOverTheTatarLayoutShowsTheWordInTop3() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        val last = h.strip.shown.last()
        val cells = listOfNotNull(last.first, last.second, last.third)
        // The plan's e2e pin is top-3: сәләм/сәлләм share the degenerate ideal path (the doubled
        // letter's plain variant is a zero-length jog), so the frequency prior orders them — the
        // user picks the intended cell, which is exactly what the tap path commits.
        assertTrue("сәләм must be in the top-3, was $cells", cells.contains("сәләм"))
    }

    @Test
    fun aTapCommitsTheGlidedWordAndTheNextWordChainFollows() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        h.strip.listener!!.onTap("сәләм")

        // The commit went through the E5d predicted-word path with the field-start context.
        assertEquals(listOf("" to "сәләм"), h.editor.predictedCommits)
        // The strip immediately shows the NEXT_WORD predictions for the committed word — the
        // pinned chain of TtNextWordFillE2ETest (form first, then the global top words).
        val last = h.strip.shown.last()
        assertEquals(Triple("сәләмә", "һәм", "белән"), last)
    }

    @Test
    fun aGlideWithShiftOnCapitalizesTheCommittedWord() {
        val h = Harness()
        h.controller.setGlideShiftStateGate(ShiftStateGate { true })
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        // The shown cells are capitalized; the user taps whichever cell holds the intended word.
        val last = h.strip.shown.last()
        val cells = listOfNotNull(last.first, last.second, last.third)
        assertTrue("the capitalized Сәләм must be shown, was $cells", cells.contains("Сәләм"))
        h.strip.listener!!.onTap("Сәләм")
        assertEquals(listOf("" to "Сәләм"), h.editor.predictedCommits)
    }

    @Test
    fun aGlideOnTheRussianLayoutDecodesAgainstTheRussianDictionary() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.russianGeometry())
        h.start(PersonalSubtypes.RUSSIAN)
        h.controller.onGlideInput(GlideTestFixtures.idealPath("работа", GlideTestFixtures.russianGeometry())!!)
        val last = h.strip.shown.last()
        val cells = listOfNotNull(last.first, last.second, last.third)
        assertTrue("работа must be in the top-3, was $cells", cells.contains("работа"))
    }

    @Test
    fun theGlideToggleOffKeepsTheBandSilent() {
        val h = Harness()
        h.controller.setGlideGate(GlideGate { false })
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        val shownBefore = h.strip.shown.size
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals(shownBefore, h.strip.shown.size)
    }

    @Test
    fun aGlideWithAHalfTypedWordInFrontDoesNothing() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        h.editor.word = "та"
        val shownBefore = h.strip.shown.size
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals(shownBefore, h.strip.shown.size)
        assertTrue(h.editor.predictedCommits.isEmpty())
    }

    @Test
    fun aGlideWithoutGeometryAnswersNothing() {
        val h = Harness()
        // No updateGlideGeometry at all: fail-closed, exactly like a missing layout.
        h.start()
        val shownBefore = h.strip.shown.size
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        assertEquals(shownBefore, h.strip.shown.size)
    }

    @Test
    fun aJunkGestureBandCommitsNothing() {
        val h = Harness()
        h.controller.updateGlideGeometry(GlideTestFixtures.tatarGeometry())
        h.start()
        // A path between keys that decodes to nothing meaningful must never leave a committable
        // band behind: whatever it shows, a session bump turns any tap into a no-op.
        h.controller.onGlideInput(GlideTestFixtures.idealPath("сәләм", GlideTestFixtures.tatarGeometry())!!)
        h.controller.onSelectionChanged()
        h.strip.listener!!.onTap("сәләм")
        assertTrue(h.editor.predictedCommits.isEmpty())
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
