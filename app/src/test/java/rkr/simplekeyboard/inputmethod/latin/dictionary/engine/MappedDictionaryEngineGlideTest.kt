package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath
import rkr.simplekeyboard.inputmethod.latin.glide.GlideTestFixtures
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * P7-3 (docs/GLIDE-PLAN.md): the GLIDE lookup kind through the real engine stack —
 * [LatestOnlyPrefixEngine] token/serial discipline, the [CompositePrefixComputer] seam, the
 * lazily built [GlideDecoderHost] — over a fixture dictionary on the fixture Tatar geometry.
 *
 * The token discipline pins mirror the NEXT_WORD ones: a glide result is dropped the moment a
 * newer request of ANY kind supersedes it, and a rejected glide request never invalidates a
 * pending prefix one.
 */
class MappedDictionaryEngineGlideTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // Code-point sorted. The geometry is the fixture Tatar layout.
    private val entries = listOf(
        "алла" to 30L,
        "бала" to 60L,
        "китап" to 200L,
        "салым" to 50L,
        "сәләм" to 100L,
    )

    private fun glidePath(word: String): GlidePath =
        requireNotNull(GlideTestFixtures.idealPath(word, geometry))

    private fun startEngine(
        executor: ManualEngineExecutor,
        published: MutableList<LookupResult>,
    ): MappedDictionaryEngine {
        val artifact = DictionaryTestFixtures.artifact(generation = 1, entries = entries)
        val file = File.createTempFile("glide-engine-", ".tdict").also { it.writeBytes(artifact.raw) }
        val dictionary = PublishedDictionary(
            1, file, artifact.spec.expectedRawSize, artifact.spec.expectedEntryCount,
            artifact.spec.schemaId, artifact.spec.formatVersion, artifact.spec.expectedRawSha256,
        )
        return requireNotNull(
            MappedDictionaryEngine.start(
                object : PublishedDictionaryCatalog {
                    private var lease: DictionaryFileLease? = DictionaryFileLease(dictionary) {}
                    override fun acquireLatestForActivation(): DictionaryFileLease? =
                        lease.also { lease = null }

                    override fun cleanupReleasedVersions() = Unit
                },
                ResultHandoff { published += it },
                executorFactory = { executor },
                mapper = DictionaryMapper { f, _ -> ByteBuffer.wrap(f.readBytes()) },
            ),
        )
    }

    @Test
    fun aGlideDecodesThroughTheWorkerWithTheRightKind() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val engine = startEngine(executor, published)
        engine.updateGlideGeometry(geometry)

        val token = requireNotNull(engine.requestGlide(1, "tt", glidePath("сәләм")))
        executor.runAll()

        assertEquals(LookupKind.GLIDE, token.kind)
        assertEquals(1, published.size)
        assertEquals(LookupKind.GLIDE, published.single().kind)
        assertEquals("сәләм", published.single().suggestions.first())
        engine.destroy(1, TimeUnit.SECONDS)
    }

    @Test
    fun aNewerPrefixRequestStalesAnInFlightGlideResult() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val engine = startEngine(executor, published)
        engine.updateGlideGeometry(geometry)

        val glideToken = requireNotNull(engine.requestGlide(1, "tt", glidePath("сәләм")))
        val prefixToken = requireNotNull(engine.request(1, "tt", utf8("ки")))
        executor.runAll()

        // The glide computed first but its handoff was dropped as stale; only the prefix landed.
        assertEquals(1, published.size)
        assertEquals(LookupKind.PREFIX, published.single().kind)
        assertTrue(engine.suppressedStaleResultCount >= 1)
        assertTrue(engine.isCurrent(prefixToken))
        assertTrue(!engine.isCurrent(glideToken))
        engine.destroy(1, TimeUnit.SECONDS)
    }

    @Test
    fun aNewerGlideRequestStalesAnInFlightPrefixResult() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val engine = startEngine(executor, published)
        engine.updateGlideGeometry(geometry)

        engine.request(1, "tt", utf8("ки"))
        val glideToken = requireNotNull(engine.requestGlide(1, "tt", glidePath("сәләм")))
        executor.runAll()

        assertEquals(1, published.size)
        assertEquals(LookupKind.GLIDE, published.single().kind)
        assertTrue(engine.isCurrent(glideToken))
        engine.destroy(1, TimeUnit.SECONDS)
    }

    @Test
    fun withoutGeometryTheAnswerIsEmptyAndTheKindIsRight() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val engine = startEngine(executor, published)
        // No updateGlideGeometry at all: fail-closed.

        val token = requireNotNull(engine.requestGlide(1, "tt", glidePath("сәләм")))
        executor.runAll()

        assertEquals(LookupKind.GLIDE, published.single().kind)
        assertTrue(published.single().suggestions.isEmpty())
        engine.destroy(1, TimeUnit.SECONDS)
    }

    @Test
    fun anEmptyGeometryAnswersEmpty() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val engine = startEngine(executor, published)
        engine.updateGlideGeometry(GlideKeyGeometry.build(emptyList()))

        engine.requestGlide(1, "tt", glidePath("сәләм"))
        executor.runAll()

        assertTrue(published.single().suggestions.isEmpty())
        engine.destroy(1, TimeUnit.SECONDS)
    }

    @Test
    fun aDegeneratePathIsRejectedWithoutInvalidatingAPendingPrefix() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val engine = startEngine(executor, published)
        engine.updateGlideGeometry(geometry)

        val onePoint = GlidePath()
        onePoint.addPoint(1f, 2f, 0f)
        assertNull(engine.requestGlide(1, "tt", onePoint))

        // The prefix request issued after the rejected glide is still fully servable.
        val prefixToken = requireNotNull(engine.request(1, "tt", utf8("ки")))
        executor.runAll()
        assertEquals(1, published.size)
        assertEquals(LookupKind.PREFIX, published.single().kind)
        assertTrue(engine.isCurrent(prefixToken))
        engine.destroy(1, TimeUnit.SECONDS)
    }

    private fun utf8(value: String) = value.toByteArray(Charsets.UTF_8)

    private companion object {
        val geometry: GlideKeyGeometry = GlideTestFixtures.tatarGeometry()
    }
}
